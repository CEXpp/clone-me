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
    // The security filter may rotate the cookie on an authentication transition.
    // Obtain the current double-submit token before an explicit write; never replay the write.
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
    if (response.status === 403 && value.code === "ACCESS_DENIED") resetCsrf(); // Next explicit submission obtains a new token; never replay paid requests.
    throw new ApiError(
      response.status,
      value.code || "REQUEST_FAILED",
      value.message || "操作未完成，请稍后重试",
      value.requestId || response.headers.get("X-Request-Id") || "",
    );
  }
  return value;
}
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
