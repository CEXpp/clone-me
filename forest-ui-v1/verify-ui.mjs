import { chromium } from "@playwright/test";
import { mkdir } from "node:fs/promises";
const browser = await chromium.launch({ headless: true, channel: "msedge" });
await mkdir("previews", { recursive: true });
const page = await browser.newPage({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 1,
});
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
await page.goto("http://127.0.0.1:5174/");
await page
  .getByRole("heading", { name: "我的另一面。 此刻，与你对话。" })
  .waitFor();
await page.waitForTimeout(600);
await page.screenshot({ path: "previews/home-desktop.png", fullPage: true });
console.log(
  "HOME",
  await page.evaluate(() => ({
    width: innerWidth,
    height: innerHeight,
    scrollWidth: document.documentElement.scrollWidth,
    scrollHeight: document.documentElement.scrollHeight,
  })),
);
const before = await page.locator("canvas").evaluate((c) => c.toDataURL());
await page.waitForTimeout(100);
const after = await page.locator("canvas").evaluate((c) => c.toDataURL());
if (before === after) throw new Error("Canvas should animate");
await page.getByRole("button", { name: "暂停背景动画" }).click();
await page.waitForTimeout(100);
const paused = await page.locator("canvas").evaluate((c) => c.toDataURL());
await page.waitForTimeout(150);
if (paused !== (await page.locator("canvas").evaluate((c) => c.toDataURL())))
  throw new Error("Pause failed");
await page.getByRole("link", { name: "开启分身对话" }).click();
await page.getByRole("heading", { name: "你好，想了解什么？" }).waitFor();
await page.screenshot({ path: "previews/chat-desktop.png", fullPage: true });
await page.getByRole("link", { name: "返回概览" }).click();
await page.getByRole("link", { name: /02.*项目档案/ }).click();
await page.getByRole("heading", { name: "项目档案." }).waitFor();
await page.screenshot({
  path: "previews/projects-desktop.png",
  fullPage: true,
});
for (const s of ["overview", "projects", "experience", "contact"]) {
  await page.goto("http://127.0.0.1:5174/#/explore/" + s);
  await page.locator(".inner-heading h1").waitFor();
}
for (const [width, height] of [
  [375, 812],
  [768, 1024],
  [1024, 768],
  [1440, 900],
]) {
  await page.setViewportSize({ width, height });
  await page.goto("http://127.0.0.1:5174/");
  await page.locator(".entry-grid").waitFor();
  await page.waitForTimeout(200);
  const dims = await page.evaluate(() => ({
    width: innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  if (dims.scrollWidth > dims.width)
    throw new Error("Horizontal overflow " + JSON.stringify(dims));
  if (width === 375)
    await page.screenshot({ path: "previews/home-mobile.png", fullPage: true });
}
await page.setViewportSize({ width: 375, height: 812 });
await page.goto("http://127.0.0.1:5174/#/chat");
await page.locator(".chat-panel").waitFor();
await page.screenshot({ path: "previews/chat-mobile.png", fullPage: true });
await page.goto("http://127.0.0.1:5174/#/admin/login");
await page.locator("input").first().waitFor();
await page.screenshot({ path: "previews/login.png", fullPage: true });
console.log("PAGE ERRORS", errors);
if (errors.length) throw new Error(errors.join("\n"));
// Isolated fixtures: verify the UI contract without issuing paid backend requests.
const context = await browser.newContext({
  viewport: { width: 1280, height: 800 },
});
let hasSession = false,
  sent = 0;
await context.route("**/api/**", async (route) => {
  const url = new URL(route.request().url()),
    method = route.request().method();
  let status = 200,
    body = {};
  if (url.pathname === "/api/public/profile")
    body = {
      displayName: "界面验证",
      headline: "测试资料，仅用于浏览器检查",
      quickQuestions: ["介绍你的项目"],
      projects: [
        {
          id: "fixture",
          name: "验证项目",
          role: "个人贡献",
          summary: "测试项目摘要",
          stack: ["Vue"],
          links: [],
        },
      ],
    };
  else if (url.pathname === "/api/public/challenge") body = { siteKey: "" };
  else if (url.pathname === "/api/auth/csrf")
    body = { headerName: "X-CSRF-TOKEN", token: "fixture-token" };
  else if (url.pathname === "/api/public/session") {
    if (method === "GET" && !hasSession) {
      status = 401;
      body = { code: "VISITOR_SESSION_REQUIRED", message: "无当前会话" };
    } else if (method === "DELETE") {
      hasSession = false;
      body = {};
    } else {
      hasSession = true;
      body = {
        sessionId: "fixture",
        expiresAt: Date.now() + 86400000,
        messages: [],
      };
    }
  } else if (url.pathname === "/api/public/chat") {
    sent++;
    body = {
      answer: "这是来自已确认资料的测试回答。",
      status: "ANSWERED",
      sources: [{ id: "s1", title: "验证项目资料", location: "第 1 节" }],
      requestId: "fixture-answer",
      expiresAt: Date.now() + 86400000,
    };
  }
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
});
const fixture = await context.newPage();
await fixture.goto("http://127.0.0.1:5174/#/explore/projects");
await fixture.getByRole("link", { name: "聊聊这个项目" }).click();
await fixture.waitForFunction(
  () => !document.querySelector("#chat-input")?.disabled,
);
if (!(await fixture.locator("#chat-input").inputValue()).includes("验证项目"))
  throw new Error("Project question was lost");
await fixture.getByRole("button", { name: "发送", exact: true }).click();
await fixture
  .getByText("这是来自已确认资料的测试回答。", { exact: true })
  .waitFor();
if (sent !== 1) throw new Error("Unexpected duplicate submission");
await fixture.getByText("查看依据 · 1").click();
await fixture.getByText("验证项目资料 / 第 1 节").waitFor();
await fixture.getByRole("button", { name: "新对话", exact: true }).click();
await fixture.getByRole("dialog").waitFor();
await fixture.keyboard.press("Escape");
await fixture.getByRole("dialog").waitFor({ state: "hidden" });
await fixture.getByRole("button", { name: "新对话", exact: true }).click();
await fixture.getByRole("button", { name: "确认清除" }).click();
await fixture.getByRole("heading", { name: "你好，想了解什么？" }).waitFor();
await fixture.goto("http://127.0.0.1:5174/");
await fixture.emulateMedia({ reducedMotion: "reduce" });
await fixture.locator("canvas").waitFor();
await fixture.waitForTimeout(100);
const still = await fixture.locator("canvas").evaluate((c) => c.toDataURL());
await fixture.waitForTimeout(150);
if (still !== (await fixture.locator("canvas").evaluate((c) => c.toDataURL())))
  throw new Error("Reduced motion failed");
console.log(
  "PASS: routes, responsive widths, animation/pause, reduced motion, project question, single chat submission, citations, accessible clear dialog.",
);
await context.close();
await browser.close();
