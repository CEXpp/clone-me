/**
 * 统一的同源 API 请求层，负责防伪令牌、错误码、登录过期事件和备份下载。
 * 写请求不自动重试：网络断开时后端可能已经执行，重发可能重复扣费或修改资料。
 */
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public requestId = "",
  ) {
    super(message);
  }
}
let csrf: { token: string; headerName: string } | undefined;
let csrfPending: Promise<void> | undefined;
export function resetCsrf() {
  csrf = undefined;
}
/** 共享正在获取令牌的 Promise，合并并发准备请求；完成后释放，下次写入仍重新获取当前令牌。 */
export async function refreshCsrf() {
  if (csrfPending) return csrfPending;
  csrfPending = (async () => {
    const res = await fetch("/api/auth/csrf", {
      credentials: "same-origin",
      cache: "no-store",
    });
    if (!res.ok)
      throw new ApiError(
        res.status,
        "CSRF_UNAVAILABLE",
        "无法建立安全连接，请稍后重试",
      );
    csrf = await res.json();
  })().finally(() => {
    csrfPending = undefined;
  });
  return csrfPending;
}
export async function api<T = any>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const headers = new Headers();
  const write = !["GET", "HEAD"].includes(method);
  if (write) {
    // 登录或退出时，安全框架可能轮换防伪 Cookie，不能一直复用旧令牌。
    // 每次用户主动提交写操作前获取当前令牌；网络失败时不自动重发，避免重复付费或重复修改。
    await refreshCsrf();
    headers.set(csrf!.headerName, csrf!.token);
  }
  const multipart = body instanceof FormData;
  if (body !== undefined && !multipart)
    headers.set("Content-Type", "application/json");
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      cache: "no-store",
      body:
        body === undefined
          ? undefined
          : multipart
            ? (body as FormData)
            : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(
      0,
      "NETWORK_ERROR",
      "网络连接中断。操作可能已被处理，请先刷新记录，勿重复提交。",
    );
  }
  const value = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && path.startsWith("/api/admin/"))
      window.dispatchEvent(new Event("admin-session-expired"));
    if (response.status === 403 && value.code === "ACCESS_DENIED") resetCsrf(); // 清除旧令牌，等待用户下次主动提交时刷新；此处不重发可能已经执行的付费请求。
    throw new ApiError(
      response.status,
      value.code || "REQUEST_FAILED",
      value.message || "操作未完成，请稍后重试",
      value.requestId || response.headers.get("X-Request-Id") || "",
    );
  }
  return value;
}
/** 通过认证请求取二进制文件，临时对象地址只用于当前浏览器下载，使用后及时释放。 */
export async function downloadBackup(name: string) {
  const response = await fetch(
    "/api/admin/backups/" + encodeURIComponent(name),
    { credentials: "same-origin", cache: "no-store" },
  );
  if (!response.ok) {
    if (response.status === 401)
      window.dispatchEvent(new Event("admin-session-expired"));
    throw new ApiError(
      response.status,
      "DOWNLOAD_FAILED",
      "下载失败，请确认登录状态",
    );
  }
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
