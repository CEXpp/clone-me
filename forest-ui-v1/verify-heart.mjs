import { chromium } from "@playwright/test";
import { mkdir } from "node:fs/promises";
const browser = await chromium.launch({ headless: true, channel: "msedge" });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
await mkdir("previews/heart-3d", { recursive: true });
await page.addInitScript(() => localStorage.setItem("alter-motion", "on"));
await page.goto("http://127.0.0.1:5174/");
await page.locator("canvas").waitFor();
await page.waitForTimeout(350);
await page.screenshot({ path: "previews/heart-3d/front.png", fullPage: true });
const geometry = await page.evaluate(async () => {
  const { createHeartParticles, HEART_TURN_SECONDS } =
    await import("/src/heartModel.ts");
  const vertices = createHeartParticles();
  const range = (key) =>
    Math.max(...vertices.map((p) => p[key])) -
    Math.min(...vertices.map((p) => p[key]));
  return {
    count: vertices.length,
    width: range("x"),
    depth: range("z"),
    height: range("y"),
    turnSeconds: HEART_TURN_SECONDS,
    finite: vertices.every((p) => Object.values(p).every(Number.isFinite)),
  };
});
if (!geometry.finite || geometry.depth / geometry.width < 0.6)
  throw new Error("Heart needs a full, finite side volume");
console.log("GEOMETRY", geometry);
for (const view of ["side", "back", "other-side"]) {
  await page.waitForTimeout(7500);
  await page
    .locator("canvas")
    .screenshot({ path: `previews/heart-3d/${view}.png` });
  console.log("Captured", view);
}
await page.getByRole("button", { name: "暂停背景动画" }).click();
await page.waitForTimeout(80);
const paused = await page.locator("canvas").evaluate((c) => c.toDataURL());
await page.waitForTimeout(100);
if (paused !== (await page.locator("canvas").evaluate((c) => c.toDataURL())))
  throw new Error("Pause failed");
const favicon = await page.locator('link[rel="icon"]').getAttribute("href");
const icon = await page.request.get("http://127.0.0.1:5174" + favicon);
if (!icon.ok() || !(await icon.text()).includes("heart-ribbon"))
  throw new Error("New favicon was not served");
for (const [width, height] of [
  [375, 812],
  [768, 1024],
  [1024, 768],
]) {
  await page.setViewportSize({ width, height });
  await page.waitForTimeout(150);
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > innerWidth,
  );
  if (overflow) throw new Error(`Overflow at ${width}`);
  if (width === 375)
    await page.screenshot({
      path: "previews/heart-3d/mobile.png",
      fullPage: true,
    });
}
if (errors.length) throw new Error(errors.join("\n"));
console.log(
  "PASS: volumetric geometry, rotation views, animation pause, new favicon, responsive widths; no browser errors.",
);
await browser.close();
