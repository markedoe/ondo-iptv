#!/usr/bin/env python3
"""Only our own launches may run on the self-hosted Linux runners, and no publishing secret may reach them.

A job may leave GitHub's machines in one way only: its `runs-on` is exactly RUNS_ON, which picks the runners
named by the LINUX_RUNNER repository variable for a push, a run by hand or a schedule, and GitHub's ubuntu-24.04
for every other event (a pull request, from a fork or not, never reaches the VPS). Unset, the variable keeps every
job on GitHub's machines. See docs/RUNNERS.md.

Fails when:
- a `runs-on` is neither one of GitHub's own machines (ubuntu-, macos-, windows-, as a plain label) nor exactly
  RUNS_ON: no other expression, variable, input, matrix value or `self-hosted` label;
- a job that can run self-hosted reads a secret other than GITHUB_TOKEN (in the job or the workflow's `env:`),
  passes `secrets: inherit`, or uses an `environment:` (whose secrets it would get).

The workflows are read line by line (CI runs this in a container without PyYAML): jobs are the keys indented by
two spaces under `jobs:`, as every workflow here is written.

Usage: check_runners.py [repository root]. Exit code 1 lists the problems.
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
RUNS_ON = ("${{ fromJSON((github.event_name == 'push' || github.event_name == 'workflow_dispatch' || "
           "github.event_name == 'schedule') && vars.LINUX_RUNNER || '\"ubuntu-24.04\"') }}")
HOSTED = re.compile(r"^(ubuntu|macos|windows)-[a-z0-9.-]+$")
# A secret is any `secrets.NAME` or `secrets['NAME']` but the run's own GITHUB_TOKEN.
SECRET = re.compile(r"secrets\s*(?:\.(?!GITHUB_TOKEN\b)[A-Za-z_]|\[)|secrets:\s*inherit")



def blocks(lines: list[str], indent: int) -> list[tuple[str, list[str]]]:
    """The keys at this indentation and the lines under each, in order."""
    found: list[tuple[str, list[str]]] = []
    pattern = re.compile(r"^" + " " * indent + r"([A-Za-z0-9_-]+):(.*)$")
    for line in lines:
        match = pattern.match(line)
        if match:
            found.append((match.group(1), [line]))
        elif found and (not line.strip() or line.lstrip().startswith("#") or line.startswith(" " * (indent + 1))):
            # A comment at any indentation stays in the block it is written in (never ends a job early).
            found[-1][1].append(line)
        elif line.strip() and found:
            found.append(("", [line]))
    return found


def runs_on(job: list[str]) -> str | None:
    """The job's runs-on value, a list or a mapping written on the lines after it joined with spaces."""
    for index, line in enumerate(job):
        match = re.match(r"^    runs-on:(.*)$", line)
        if match:
            value = [match.group(1).strip()]
            for following in job[index + 1:]:
                if following.strip() and not following.startswith("     "):
                    break
                value.append(following.strip())
            return " ".join(part for part in value if part)
    return None


def check(path: Path) -> tuple[list[str], int]:
    """The problems of a workflow and the number of its jobs."""
    problems = []
    count = 0
    name = path.relative_to(ROOT).as_posix()
    # Comments are read too: an expression in a shell comment of a run block is still evaluated.
    lines = path.read_text(encoding="utf-8").splitlines()
    top = blocks(lines, 0)
    workflow_secrets = any(key == "env" and any(SECRET.search(line) for line in body) for key, body in top)
    jobs = next((body[1:] for key, body in top if key == "jobs"), [])
    for job, body in blocks(jobs, 2):
        if not job:
            continue
        count += 1
        value = runs_on(body)
        if value is None:
            if any(re.match(r"^    uses:", line) for line in body):
                continue  # A reusable workflow: its own jobs are checked in its file.
            problems.append(f"{name}: job {job} has no runs-on this check can read")
            continue
        if HOSTED.match(value.strip("'\"")):
            continue
        if value != RUNS_ON:
            problems.append(f"{name}: job {job}: runs-on is {value}; a job runs on GitHub's machines (a plain "
                            f"ubuntu-, macos- or windows- label) or, to use the self-hosted runners for our own "
                            f"launches only, exactly {RUNS_ON}")
            continue
        if workflow_secrets:
            problems.append(f"{name}: job {job} can run self-hosted, but the workflow's env reads a secret")
        for line in body:
            if SECRET.search(line):
                problems.append(f"{name}: job {job} can run self-hosted, but reads a secret: {line.strip()}")
            if re.match(r"^    environment:", line):
                problems.append(f"{name}: job {job} can run self-hosted, but uses an environment (and its secrets)")
    return problems, count


def main() -> int:
    workflows = sorted((ROOT / ".github/workflows").glob("*.y*ml"))
    results = [check(path) for path in workflows]
    problems = [problem for found, _ in results for problem in found]
    jobs = sum(count for _, count in results)
    for problem in problems:
        print(f"::error::{problem}")
    if not problems:
        print(f"{len(workflows)} workflows, {jobs} jobs: self-hosted runners for our own launches only, no secret on them")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
