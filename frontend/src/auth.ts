export const authNavigation = { expired: false, logoutConfirmed: false };
/** Deny protected navigation until the server has confirmed the administrator session. */
export async function adminDestination(
  path: string,
  verify: () => Promise<unknown>,
): Promise<true | string> {
  if (!path.startsWith("/admin")) return true;
  try {
    await verify();
    return path === "/admin/login" ? "/admin/sources" : true;
  } catch {
    return path === "/admin/login" ? true : "/admin/login";
  }
}
