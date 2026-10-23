import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("访客聊天刷新续聊，并可主动清除", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("向文字分身提问").fill("你有未发布的经历吗？");
  await page.getByRole("button", { name: "发送 ↑", exact: true }).click();
  await expect(
    page.getByText(
      "这部分我目前没有足够的已确认资料可以展开，先不凭印象补充了。",
      { exact: true },
    ),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByText("你有未发布的经历吗？", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "新聊天 / 清除记录" }).click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("button", { name: /确认|清除/ })
    .last()
    .click();
  await expect(
    page.getByText("你有未发布的经历吗？", { exact: true }),
  ).toHaveCount(0);
});

test("真实登录、上传、人工确认、发布和退出", async ({ page }, info) => {
  const auth = JSON.parse(
    await readFile(
      new URL("../../.cache/e2e-auth.json", import.meta.url),
      "utf8",
    ),
  ) as { username: string; password: string; codes: string[] };
  await page.goto("/#/admin/sources");
  await expect(page).toHaveURL(/admin\/login/);
  await page.getByLabel("用户名", { exact: true }).fill(auth.username);
  await page.getByLabel("密码", { exact: true }).fill(auth.password);
  await page.getByRole("button", { name: "改用一次性恢复码" }).click();
  await page
    .getByLabel("一次性恢复码", { exact: true })
    .fill(auth.codes[info.project.name === "desktop" ? 0 : 1]);
  await page.getByRole("button", { name: /登录管理台/ }).click();
  await expect(page).toHaveURL(/admin\/sources/);
  await page.getByRole("button", { name: /上传资料 \/ JSON 映射/ }).click();
  const drawer = page.getByRole("dialog");
  await drawer.getByLabel("选择一个或多个文件").setInputFiles({
    name: "browser-" + info.project.name + ".txt",
    mimeType: "text/plain",
    buffer: Buffer.from("我在个人项目中使用 Java 编写接口。"),
  });
  await drawer.getByRole("button", { name: "开始上传", exact: true }).click();
  await expect(drawer.getByText("已接收，后台解析中")).toBeVisible();
  await drawer.getByRole("button", { name: "关闭面板" }).click();
  const select = page.getByLabel("当前审阅资料", { exact: true });
  const option = select
    .locator("option")
    .filter({ hasText: "browser-" + info.project.name + ".txt" });
  await select.selectOption((await option.getAttribute("value"))!);
  await expect(async () => {
    await page.getByRole("button", { name: "刷新解析结果" }).click();
    await expect(page.getByLabel("候选记录正文").first()).toBeVisible({
      timeout: 750,
    });
  }).toPass({ timeout: 15_000 });
  await page
    .getByRole("combobox", { name: "类型", exact: true })
    .first()
    .selectOption("EXPERIENCE");
  await page
    .getByRole("combobox", { name: "掌握程度", exact: true })
    .first()
    .selectOption("USED");
  await page
    .getByRole("button", { name: "保存并确认", exact: true })
    .first()
    .click();
  await expect(page.getByText("已确认进入待发布").first()).toBeVisible();
  await page.getByRole("link", { name: /版本与展示/ }).click();
  await page.getByLabel("新版本名称").fill("浏览器验证 " + info.project.name);
  await page.getByLabel("仅关键词发布").check();
  await page.getByRole("button", { name: "创建发布版本", exact: true }).click();
  await expect(
    page
      .locator("article")
      .filter({ hasText: "浏览器验证 " + info.project.name })
      .getByText("当前版本", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: /确认退出/ })
    .click();
  await expect(page).toHaveURL(/admin\/login/);
});
