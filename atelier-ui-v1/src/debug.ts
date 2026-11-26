/**
 * 前端流程日志输出在浏览器开发者工具的 Console 中。
 * 开发模式默认开启；正式构建可通过 VITE_DEBUG_LOGS=true 开启。
 * 只记录脱敏后的阶段字段，绝不输出密码、Cookie、令牌、聊天正文或完整响应。
 */
export function debugFlow(
  stage: string,
  action: string,
  details: Record<string, string | number | boolean | undefined> = {},
) {
  if (import.meta.env.DEV || import.meta.env.VITE_DEBUG_LOGS === "true") {
    console.info(`[流程][${stage}] ${action}`, details);
  }
}

/** 去掉查询参数与动态资源名，保留接口形态以定位调用链。 */
export function debugPath(path: string) {
  return path
    .split("?")[0]
    .replace(
      /(\/(?:requests|sources|candidates|releases|backups|notes|note-assets)\/)[^/]+/g,
      "$1:id",
    );
}
