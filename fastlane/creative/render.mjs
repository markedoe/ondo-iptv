// Renders the App Store header and search results assets (template.html) for each listing language, in
// fastlane/creative/<locale>/: header.png (3840 × 1646, 21:9: PNG only) and search.jpg (3840 × 2560, 3:2); and
// the GitHub social preview, fastlane/creative/social.jpg (2560 × 1280, under GitHub's 1 MB; Settings → General →
// Social preview).
//
//   git fetch origin appstore-screenshots && mkdir -p /tmp/shots && git archive FETCH_HEAD | tar -x -C /tmp/shots
//   NODE_PATH="$(npm root -g)" node fastlane/creative/render.mjs /tmp/shots
//
// Needs Playwright's Chromium and the Inter font. The App Store Connect upload is
// .github/workflows/app-store-assets.yml.
import { mkdirSync, readFileSync } from "node:fs";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { dirname, join, normalize, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

// Through require, so that NODE_PATH finds a Playwright installed globally (ES imports ignore NODE_PATH).
const { chromium } = createRequire(import.meta.url)("playwright");
const here = dirname(fileURLToPath(import.meta.url));
const shots = resolve(process.argv[2] ?? "shots");
// App Store Connect takes the 21:9 header as PNG only; Chromium's PNG and JPEG have no alpha channel, which the
// App Store refuses for these assets.
const assets = { header: [3840, 1646, "png"], search: [3840, 2560, "jpeg"] };

// The page and the screenshots from one local origin: Chromium honours `image-orientation: none` (template.html)
// for same-origin images only.
const server = createServer((request, response) => {
  const path = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
  const [root, rest] = path.startsWith("/shots/") ? [shots, path.slice("/shots/".length)] : [here, path.slice(1)];
  const file = normalize(join(root, rest));
  if (!file.startsWith(root + sep)) {
    response.writeHead(404).end();
    return;
  }
  try {
    const type = file.endsWith(".html") ? "text/html; charset=utf-8" : "image/png";
    response.writeHead(200, { "content-type": type }).end(readFileSync(file));
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
const origin = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
try {
  for (const locale of ["en-US", "fr-FR"]) {
    mkdirSync(join(here, locale), { recursive: true });
    for (const [kind, [width, height, type]] of Object.entries(assets)) {
      const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
      const url = new URL("/template.html", origin);
      url.search = new URLSearchParams({ kind, locale, shots: "/shots" }).toString();
      await page.goto(url.href);
      await page.waitForSelector("body[data-ready='1']");
      const file = join(here, locale, `${kind}.${type === "png" ? "png" : "jpg"}`);
      await page.screenshot({ path: file, type, ...(type === "jpeg" ? { quality: 92 } : {}) });
      await page.close();
      console.log(file);
    }
  }
  // English only: one card for every visitor.
  const page = await browser.newPage({ viewport: { width: 2560, height: 1280 }, deviceScaleFactor: 1 });
  const url = new URL("/template.html", origin);
  url.search = new URLSearchParams({ kind: "social", locale: "en-US", shots: "/shots" }).toString();
  await page.goto(url.href);
  await page.waitForSelector("body[data-ready='1']");
  await page.screenshot({ path: join(here, "social.jpg"), type: "jpeg", quality: 85 });
  await page.close();
  console.log(join(here, "social.jpg"));
} finally {
  await browser.close();
  server.close();
}
