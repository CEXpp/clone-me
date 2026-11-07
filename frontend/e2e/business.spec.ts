import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("访客聊天刷新续聊，并可主动清除", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("向文字分身提问").fill("你有未发布的经历吗？");
  await page.getByRole("button", { name: "发送 ↑", exact: true }).click();
  await expect(
    page.getByText("知识库中没有足够资料回答这个问题。", { exact: true }),
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

test("笔记编辑、自动保存、历史与知识库同步", async ({ page }, info) => {
  const auth = JSON.parse(
    await readFile(
      new URL("../../.cache/e2e-auth.json", import.meta.url),
      "utf8",
    ),
  ) as { username: string; password: string; codes: string[] };
  await page.goto("/#/admin/notes");
  await page.getByLabel("用户名", { exact: true }).fill(auth.username);
  await page.getByLabel("密码", { exact: true }).fill(auth.password);
  await page.getByRole("button", { name: "改用一次性恢复码" }).click();
  await page
    .getByLabel("一次性恢复码", { exact: true })
    .fill(auth.codes[info.project.name === "desktop" ? 0 : 1]);
  await page.getByRole("button", { name: /登录管理台/ }).click();
  await expect(page).toHaveURL(/admin\/notes/);
  let unexpectedDialog = false;
  page.on("dialog", async (dialog) => {
    unexpectedDialog = true;
    await dialog.dismiss();
  });
  if (info.project.name === "mobile")
    await page.getByLabel("显示页面列表").click();
  await page.getByRole("button", { name: /新建分区/ }).click();
  const categoryName = page.getByLabel("分区名称", { exact: true });
  await expect(categoryName).toBeFocused();
  await categoryName.press("Enter");
  await expect(page.getByText("请输入名称", { exact: true })).toBeVisible();
  await categoryName.fill("界面分区 " + info.project.name);
  await page.screenshot({ path: info.outputPath("inline-section.png") });
  await categoryName.press("Enter");
  await expect(categoryName).toHaveCount(0);
  if (info.project.name === "mobile") {
    // 输入框关闭后仍需等待刷新和分区切换结束，才操作移动导航。
    await expect(
      page.getByRole("complementary", { name: "笔记列表" }),
    ).toBeVisible();
    await page.getByLabel("显示页面列表").click();
  }
  await expect(
    page.getByRole("button", {
      name: "界面分区 " + info.project.name,
      exact: true,
    }),
  ).toBeVisible();
  await page.getByLabel("新建笔记本", { exact: true }).click();
  await expect(page.getByLabel("笔记本名称", { exact: true })).toBeFocused();
  await page.getByLabel("笔记本名称", { exact: true }).press("Escape");
  await expect(page.getByLabel("笔记本名称", { exact: true })).toHaveCount(0);
  expect(unexpectedDialog).toBe(false);
  await page.getByRole("button", { name: "随手记", exact: true }).click();

  await page
    .getByRole("button", { name: "新建笔记", exact: true })
    .first()
    .click();
  await page
    .getByLabel("笔记标题", { exact: true })
    .fill("浏览器笔记 " + info.project.name);
  await page
    .getByRole("textbox", { name: "笔记正文", exact: true })
    .fill("Java 数据库索引可以提高查询效率。这是一篇用于验证自动保存的笔记。");
  await expect(page.getByText("已保存", { exact: true })).toBeVisible();
  await page.reload();
  await page
    .getByRole("button")
    .filter({
      has: page.locator("strong", {
        hasText: "浏览器笔记 " + info.project.name,
      }),
    })
    .click();
  await expect(
    page.getByRole("textbox", { name: "笔记正文", exact: true }),
  ).toContainText("数据库索引");
  await page.getByLabel("页面选项", { exact: true }).click();
  await page.getByLabel("用于访客问答", { exact: true }).check();
  await expect(page.getByText("已保存", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: /访客知识库/ }).click();
  if (info.project.name === "mobile")
    await page.getByLabel("显示页面列表").click();
  await expect(
    page.getByText("知识库已同步", { exact: true }).first(),
  ).toBeVisible({ timeout: 25000 });
  if (info.project.name === "mobile")
    await page
      .locator(".note-card")
      .filter({ hasText: "浏览器笔记 " + info.project.name })
      .click();
  await page.getByLabel("页面选项", { exact: true }).click();
  await page.getByRole("button", { name: "历史", exact: true }).click();
  await expect(page.locator(".history-list")).toContainText("修订");
  await page.getByLabel("关闭历史").click();
  await page.getByText("测试知识库检索", { exact: true }).click();
  await page.getByLabel("测试问题").fill("Java 数据库索引");
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.locator(".search-passage").first()).toContainText(
    "数据库索引",
  );
  if (await page.locator(".admin-mode").count())
    await expect(page.locator(".note-workspace")).toHaveCSS(
      "background-color",
      "rgb(32, 31, 30)",
    );
  await page.getByText("测试知识库检索", { exact: true }).click();
  await expect(page.locator(".notebook-titlebar")).toHaveCSS(
    "background-color",
    "rgb(9, 9, 9)",
  );
  await page.emulateMedia({ reducedMotion: "reduce" });
  if (info.project.name === "mobile")
    await page.setViewportSize({ width: 375, height: 812 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({
    path: info.outputPath("notebooks.png"),
    fullPage: true,
  });
});
