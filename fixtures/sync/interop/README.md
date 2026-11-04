# Android and Apple sync each other

Snapshots made by the two cores, each read and merged by the other: the proof that an Android device and an iPhone, iPad
or Mac sync profiles, each profile's data, row orders, shared lists, hidden categories and deletions with each other, beyond the
hand-written examples of `fixtures/sync`.

For each scenario (`profiles`, `shared-lists`, `deletions`, `hidden`: each profile's hidden categories, languages and
styles, hidden and shown again on either side), both devices start from the same data (what they had after
an earlier session), scripted the same way in both cores, with every time given by a `SyncClock` at a known wall time.
Then each changes things on its own, and they have one session, the iPhone as the client:

| File                  | Written by | What it is                                                                              |
|-----------------------|------------|-----------------------------------------------------------------------------------------|
| `android.json`        | Kotlin     | The Android device's snapshot, as it sends it (`SyncInteropTest`, android/core).        |
| `apple.json`          | Swift      | The iPhone's snapshot, as it sends it (`SyncInteropTests`, Packages/IPTVCore).          |
| `answer.json`         | Kotlin     | The Android device's answer: its snapshot merged with the iPhone's.                     |
| `kept.json`           | Swift      | What the iPhone keeps: its snapshot merged with the answer.                             |
| `android-digests.json`| Kotlin     | The Kotlin digests of `android.json` and `answer.json`, and the canonical digest.       |
| `apple-digests.json`  | Swift      | The Swift digests of `apple.json` and `kept.json`, and the canonical digest.            |

Two more scenarios put an Android TV of the previous version (which does not share lists) between the iPhone and an iPad:
`old-relay-share` (the iPhone shares a list) and `old-relay-take-back` (the iPhone takes a shared list back). There,
`android.json` and `answer.json` are what that TV keeps and answers (no household, no `placedAt`), and `kept.json` is what
the iPad, which only meets the TV, keeps of the answer: the list is never lost. Shared, it stays in the profile's lists on
the iPad until the two meet; taken back, it is the profile's on both.

Each core's test, in CI (android.yml, ci.yml; both run when this folder changes):

- makes its own device's snapshot and its own merge again, and finds them as committed: a change to that core's format,
  merge or digest shows here, and the files must be written again (below);
- reads the files the other core wrote, does the other device's half of the session itself, and finds what the other
  core got (the decoded snapshots are compared, not the bytes: each core writes JSON its own way);
- checks that both devices end with the same data, in either order of the merge, and with the same canonical digest:
  SHA-256 of the snapshot's JSON with members sorted, empty ones and who sends it left out, whole numbers without a
  decimal point. A device's own digest (docs/SYNC.md, "Transport") is compared only with its own earlier ones and those a
  peer sent, so the two cores need not compute it alike; the canonical one shows that their data is the same.

Committed files rather than files passed from one CI job to the other: a job never waits for, or silently skips, the
other's output, and a change on either side fails the other side's test until the files are written again.

To write them again after a change to the format or the merge: run the Kotlin test with `ONDO_WRITE_INTEROP=1`
(`android.json`, and `answer.json` once `apple.json` exists), then the Swift tests with it (`apple.json`, and `kept.json`
and the digests once `answer.json` exists), then each once more, then both without it.
