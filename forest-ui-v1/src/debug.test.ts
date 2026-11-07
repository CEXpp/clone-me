import { afterEach, expect, it, vi } from "vitest";
import { api, resetCsrf } from "./api";
import { debugFlow, debugPath } from "./debug";

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  resetCsrf();
});

it("正式构建默认不输出教学日志，显式开启后可用于排障", () => {
  const info = vi.spyOn(console, "info").mockImplementation(() => {});
  vi.stubEnv("DEV", false);
  vi.stubEnv("VITE_DEBUG_LOGS", "false");
  debugFlow("BOOT", "开始启动");
  expect(info).not.toHaveBeenCalled();
  vi.stubEnv("VITE_DEBUG_LOGS", "true");
  debugFlow("BOOT", "开始启动");
  expect(info).toHaveBeenCalledTimes(1);
});

it("路径日志移除查询参数、请求键和备份名称", () => {
  expect(
    debugPath("/api/public/requests/private-key?question=private-text"),
  ).toBe("/api/public/requests/:id");
  expect(debugPath("/api/admin/backups/private-name.zip")).toBe(
    "/api/admin/backups/:id",
  );
});

it("开发日志可关联后端请求编号，且不输出请求正文、响应正文或防伪令牌", async () => {
  const info = vi.spyOn(console, "info").mockImplementation(() => {});
  vi.stubEnv("DEV", true);
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            token: "private-csrf-token",
            headerName: "X-XSRF-TOKEN",
          }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ answer: "private-answer" }), {
          headers: { "X-Request-Id": "trace-fixture-id" },
        }),
      ),
  );
  await api("/api/public/chat", "POST", {
    message: "private-question",
    password: "private-password",
  });
  const output = JSON.stringify(info.mock.calls);
  expect(output).toContain("trace-fixture-id");
  for (const secret of [
    "private-csrf-token",
    "private-answer",
    "private-question",
    "private-password",
  ])
    expect(output).not.toContain(secret);
});
