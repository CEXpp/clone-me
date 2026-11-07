/**
 * 管理页面跳转规则与强制退出导航状态，供路由和未保存提醒共用。
 * 会话过期或用户已确认退出时，不能被编辑页的离开提醒阻止返回登录页。
 */
export const authNavigation = { expired: false, logoutConfirmed: false };
/** 每次进入管理页面都先向服务器验证登录状态；界面上的布尔值不能作为身份凭据。 */
export async function adminDestination(
  path: string,
  verify: () => Promise<unknown>,
): Promise<true | string> {
  // 返回 true 允许原导航，返回路径字符串则重定向；verify 由路由传入真实的服务端查询。
  if (!path.startsWith("/admin")) return true;
  try {
    await verify();
    return path === "/admin/login" ? "/admin/notes" : true;
  } catch {
    return path === "/admin/login" ? true : "/admin/login";
  }
}
