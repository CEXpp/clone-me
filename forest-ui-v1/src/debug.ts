/**
 * 前端流程日志输出在浏览器开发者工具的 Console 中。
 * 开发模式默认开启；正式构建可通过 VITE_DEBUG_LOGS=true 在构建时开启。
 * 只接受简单诊断字段，调用处不要传密码、Cookie、令牌、聊天正文或完整响应。
 */
export function debugFlow(
  stage: string,
  action: string,
  details: Record<string, string | number | boolean | undefined> = {},
) {
  if (import.meta.env.DEV || import.meta.env.VITE_DEBUG_LOGS === "true") {
    // console.info 默认可见；不用 console.debug，避免小白因控制台过滤等级看不到日志。
    console.info(`[流程][${stage}] ${action}`, details);
  }
}

/** 去掉查询参数和动态资源名，保留接口形状即可定位，避免日志暴露筛选值或备份名称。 */
export function debugPath(path: string) {
  return path
    .split("?")[0]
    .replace(
      /(\/(?:requests|sources|candidates|releases|backups)\/)[^/]+/g,
      "$1:id",
    );
}
