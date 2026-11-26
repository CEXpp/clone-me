import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("访客空间核心、主题切换、分类抽屉与直接提问续聊", async ({ page }) => {
  await page.goto("/");

  // 1. 验证全屏 3D Canvas 已渲染且无横向溢出
  const canvasBox = await page.locator("canvas").boundingBox();
  expect(canvasBox).not.toBeNull();
  expect(canvasBox!.width).toBeGreaterThan(100);
  expect(canvasBox!.height).toBeGreaterThan(100);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();

  // 2. 验证暗曜/钛银双主题切换
  await expect(page.locator("html")).toHaveAttribute("data-theme", "obsidian");
  await page.getByRole("button", { name: "切换钛银浅色模式" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "titanium");
  await page.getByRole("button", { name: "切换暗曜深色模式" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "obsidian");

  // 3. 验证节点与联络已分类收起在抽屉内
  await page.getByRole("button", { name: /节点与联络/ }).click();
  const sheet = page.getByRole("dialog");
  await expect(sheet).toBeVisible();
  await expect(sheet.getByLabel("访客节点卡片")).toBeVisible();
  await sheet.getByRole("button", { name: "关闭面板" }).click();
  await expect(sheet).toHaveCount(0);

  // 4. 通过首屏 Omnibox 直发问题进入 /chat，验证续聊与清除
  await page.getByLabel("快速提问").fill("你有未发布的经历吗？");
  await page.getByRole("button", { name: "提问" }).click();
  await expect(page).toHaveURL(/#\/chat/);
  await expect(
    page.getByText("知识库中没有足够资料回答这个问题。", { exact: true }),
  ).toBeVisible();

  await page.reload();
  await expect(
    page.getByText("你有未发布的经历吗？", { exact: true }),
  ).toBeVisible();

  await page.getByRole("button", { name: "新开对谈" }).click();
  const confirmDialog = page.getByRole("dialog");
  await confirmDialog.getByRole("button", { name: "确认清除" }).click();
  await expect(
    page.getByText("你有未发布的经历吗？", { exact: true }),
  ).toHaveCount(0);

  // 验证从会话页可通过显式返回入口与顶部胶囊导航回到首页
  await page.getByRole("link", { name: "返回首页", exact: true }).click();
  await expect(page).toHaveURL(/#\/$/);
  await expect(page.getByLabel("快速提问")).toBeVisible();

  await page
    .getByRole("navigation", { name: "主导航" })
    .getByRole("link", { name: "对话", exact: true })
    .click();
  await expect(page).toHaveURL(/#\/chat/);

  await page
    .getByRole("navigation", { name: "主导航" })
    .getByRole("link", { name: "首页", exact: true })
    .click();
  await expect(page).toHaveURL(/#\/$/);
  await expect(page.getByLabel("快速提问")).toBeVisible();
});

test("管理员登录、折叠式名片编纂与笔记目录收起及同步检索", async ({
  page,
}, info) => {
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

  // 1. 折叠式公开名片编纂
  await page.getByRole("link", { name: "名片", exact: true }).click();
  await expect(page).toHaveURL(/admin\/profile/);
  await page
    .getByLabel(/署名 \/ 显示名称/)
    .fill("PRISM · " + info.project.name);

  await page.getByText("02 · 快捷提问").click();
  await page
    .getByLabel("新增快捷提问")
    .fill("核心架构取舍 · " + info.project.name);
  await page.getByRole("button", { name: "添加提问", exact: true }).click();

  await page.getByText("03 · 代表项目").click();
  await page.getByRole("button", { name: "＋ 添加项目" }).click();
  await page
    .getByLabel("项目名称 *")
    .last()
    .fill("空间检索核心 · " + info.project.name);

  await page.getByRole("button", { name: "保存并发布名片" }).click();
  await expect(page.getByText(/公开名片已发布/)).toBeVisible();

  // 2. 验证首屏按需展开分类芯片与项目抽屉
  await page.goto("/");
  await page.getByRole("button", { name: /灵感/ }).click();
  await expect(
    page.getByRole("button", {
      name: "核心架构取舍 · " + info.project.name,
    }),
  ).toBeVisible();

  await page.getByRole("button", { name: /项目/ }).click();
  await expect(
    page.getByRole("dialog").getByText("空间检索核心 · " + info.project.name),
  ).toBeVisible();
  await page.getByRole("button", { name: "关闭面板" }).click();

  // 3. 验证笔记目录收起/展开、新建保存与知识库检索
  await page.goto("/#/admin/notes");
  if (info.project.name === "desktop") {
    await page.getByRole("button", { name: "收起目录" }).click();
    await expect(
      page.getByRole("complementary", { name: "笔记列表" }),
    ).toBeHidden();
    await page.getByRole("button", { name: "展开目录" }).click();
    await expect(
      page.getByRole("complementary", { name: "笔记列表" }),
    ).toBeVisible();
  } else {
    await page.getByLabel("显示页面列表").click();
  }

  await page.getByRole("button", { name: /新建分区/ }).click();
  const categoryName = page.getByLabel("分区名称", { exact: true });
  await expect(categoryName).toBeFocused();
  await categoryName.fill("空间分区 " + info.project.name);
  await categoryName.press("Enter");
  await expect(categoryName).toHaveCount(0);

  await page
    .getByRole("button", { name: "新建笔记", exact: true })
    .first()
    .click();
  await page
    .getByLabel("笔记标题", { exact: true })
    .fill("空间笔记 " + info.project.name);
  await page
    .getByRole("textbox", { name: "笔记正文", exact: true })
    .fill("Java 虚拟线程配合 SQLite 读写分离可降低阻塞抖动。");
  await expect(page.getByText("已保存", { exact: true })).toBeVisible();

  await page.getByLabel("页面选项", { exact: true }).click();
  await page.getByLabel("用于访客问答", { exact: true }).check();
  await expect(page.getByText("已保存", { exact: true })).toBeVisible();

  await page.getByRole("link", { name: "知识库", exact: true }).click();
  if (info.project.name === "mobile") {
    await page.getByLabel("显示页面列表").click();
  }
  await expect(
    page.getByText("知识库已同步", { exact: true }).first(),
  ).toBeVisible({ timeout: 25000 });

  await page.getByText("测试知识库检索", { exact: true }).click();
  await page.getByLabel("测试问题").fill("Java 虚拟线程 SQLite");
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.locator(".search-passage").first()).toContainText(
    "虚拟线程",
  );

  if (info.project.name === "mobile") {
    await page.setViewportSize({ width: 375, height: 812 });
  }
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
});
