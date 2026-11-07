/**
 * 验证请求层防伪、登录跳转和公开资料归一化，使用模拟请求，不连接外部服务。
 * 重点检查写操作不会被自动重放，以及未认证用户不能直接进入管理页面。
 */
import { beforeEach, afterEach, describe, it, expect, vi } from "vitest";
import { api, ApiError, resetCsrf } from "./api";
import { adminDestination } from "./auth";
import { safeUrl, normalizeProfile } from "./shared";
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json",
      "X-Request-Id": "fixture-id",
    },
  });
beforeEach(() => {
  resetCsrf();
  vi.stubGlobal("window", new EventTarget());
});
afterEach(() => vi.unstubAllGlobals());
describe("请求安全与防止重复计费", () => {
  it("无效安全令牌阻止写请求，而不是发送缺少防伪头的请求", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(json({ token: null, headerName: "X-XSRF-TOKEN" }));
    vi.stubGlobal("fetch", fetch);
    await expect(api("/api/admin/notes", "POST", {})).rejects.toMatchObject({
      code: "CSRF_UNAVAILABLE",
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("连续写操作各自获取当前令牌，避免登录状态变化后沿用旧令牌", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        json({ token: "first", headerName: "X-XSRF-TOKEN" }),
      )
      .mockResolvedValueOnce(json({ ok: true }))
      .mockResolvedValueOnce(
        json({ token: "rotated", headerName: "X-XSRF-TOKEN" }),
      )
      .mockResolvedValueOnce(json({ ok: true }));
    vi.stubGlobal("fetch", fetch);
    await api("/api/admin/one", "PUT", {});
    await api("/api/admin/two", "PUT", {});
    expect(fetch).toHaveBeenCalledTimes(4);
    expect(fetch.mock.calls[3][1].headers.get("X-XSRF-TOKEN")).toBe("rotated");
  });
  it("并发写请求共用一次 CSRF 获取，且使用同源 cookie", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        json({ token: "fixture-csrf", headerName: "X-XSRF-TOKEN" }),
      )
      .mockImplementation(async () => json({ ok: true }));
    vi.stubGlobal("fetch", fetch);
    await Promise.all([
      api("/api/admin/one", "POST", {}),
      api("/api/admin/two", "PUT", {}),
    ]);
    expect(fetch).toHaveBeenCalledTimes(3);
    for (const [path, options] of fetch.mock.calls.slice(1)) {
      expect(options.credentials).toBe("same-origin");
      expect(options.headers.get("X-XSRF-TOKEN")).toBe("fixture-csrf");
    }
  });
  it("模型聊天网络失败时不会自动重复提交", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        json({ token: "fixture", headerName: "X-XSRF-TOKEN" }),
      )
      .mockRejectedValueOnce(new Error("network"));
    vi.stubGlobal("fetch", fetch);
    await expect(
      api("/api/public/chat", "POST", { message: "question" }),
    ).rejects.toMatchObject({ code: "NETWORK_ERROR" });
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it("CSRF 拒绝不重放请求，下次主动提交才刷新令牌", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "old", headerName: "X-XSRF-TOKEN" }))
      .mockResolvedValueOnce(json({ code: "ACCESS_DENIED" }, 403))
      .mockResolvedValueOnce(json({ token: "new", headerName: "X-XSRF-TOKEN" }))
      .mockResolvedValueOnce(json({ ok: true }));
    vi.stubGlobal("fetch", fetch);
    await expect(api("/api/public/chat", "POST", {})).rejects.toBeInstanceOf(
      ApiError,
    );
    expect(fetch).toHaveBeenCalledTimes(2);
    await api("/api/public/chat", "POST", {});
    expect(fetch).toHaveBeenCalledTimes(4);
    expect(fetch.mock.calls[3][1].headers.get("X-XSRF-TOKEN")).toBe("new");
  });
  it("上传保留 FormData，不覆盖浏览器 boundary", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        json({ token: "csrf", headerName: "X-XSRF-TOKEN" }),
      )
      .mockResolvedValueOnce(json({ ok: true }));
    vi.stubGlobal("fetch", fetch);
    const data = new FormData();
    data.set("file", new Blob(["fixture"]), "note.txt");
    await api("/api/admin/notes", "POST", data);
    expect(fetch.mock.calls[1][1].body).toBe(data);
    expect(fetch.mock.calls[1][1].headers.has("Content-Type")).toBe(false);
  });
  it("后台过期通知只由受保护接口触发，普通访客过期不跳后台登录", async () => {
    let expired = 0;
    window.addEventListener("admin-session-expired", () => expired++);
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation(async () => json({ code: "AUTH_REQUIRED" }, 401)),
    );
    await expect(api("/api/public/session")).rejects.toBeInstanceOf(ApiError);
    expect(expired).toBe(0);
    await expect(api("/api/admin/notes")).rejects.toBeInstanceOf(ApiError);
    expect(expired).toBe(1);
  });
});
describe("管理员路由守卫", () => {
  it("未认证的直接后台访问进入登录页", async () => {
    expect(
      await adminDestination("/admin/notes", async () => {
        throw new Error("401");
      }),
    ).toBe("/admin/login");
  });
  it("服务器确认前不会完成后台导航", async () => {
    let resolve!: () => void;
    let done = false;
    const pending = adminDestination(
      "/admin/usage",
      () => new Promise<void>((r) => (resolve = r)),
    ).then((value) => {
      done = true;
      return value;
    });
    await Promise.resolve();
    expect(done).toBe(false);
    resolve();
    expect(await pending).toBe(true);
  });
  it("身份服务不可用时拒绝进入后台", async () => {
    expect(
      await adminDestination("/admin/usage", async () => {
        throw new Error("503");
      }),
    ).toBe("/admin/login");
  });
  it("已有会话跳过登录表单进入后台，访客页面不要求认证", async () => {
    expect(await adminDestination("/admin/login", async () => ({}))).toBe(
      "/admin/notes",
    );
    const verify = vi.fn();
    expect(await adminDestination("/", verify)).toBe(true);
    expect(verify).not.toHaveBeenCalled();
  });
});
describe("公开资料安全", () => {
  it("拒绝脚本和 data 链接，保留公开联系方式", () => {
    expect(safeUrl("javascript:alert(1)")).toBeUndefined();
    expect(safeUrl("data:text/html,evil")).toBeUndefined();
    expect(safeUrl("https://example.com")).toBe("https://example.com/");
    expect(safeUrl("mailto:owner@example.com")).toBe(
      "mailto:owner@example.com",
    );
  });
  it("缺失或异常资料不产生示例履历", () => {
    const profile = normalizeProfile({
      skills: [null, "wrong"],
      projects: "wrong",
      displayName: 123,
    });
    expect(profile.displayName).toBe("");
    expect(profile.skills).toEqual([]);
    expect(profile.projects).toEqual([]);
    expect(profile.experiences).toEqual([]);
  });
});
