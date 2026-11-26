/**
 * 管理页面跳转规则与强制退出导航状态，供路由和未保存提醒共用。
 */
export const authNavigation = { expired: false, logoutConfirmed: false };

/** 每次进入管理页面先向后端核验登录状态；界面布尔值不能代替服务端认证。 */
export async function adminDestination(
  path: string,
  verify: () => Promise<unknown>,
): Promise<true | string> {
  if (!path.startsWith("/admin")) return true;
  try {
    await verify();
    return path === "/admin/login" ? "/admin/notes" : true;
  } catch {
    return path === "/admin/login" ? true : "/admin/login";
  }
}
