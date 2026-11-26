/**
 * 使用哈希路由，静态资源由后端提供时无需额外的页面路径回退。
 * 进入管理页先向服务器核实登录状态；服务器的接口鉴权才是最终访问边界。
 */
import { createRouter, createWebHashHistory } from "vue-router";
import { debugFlow } from "./debug";
import { ref } from "vue";
import { api, ApiError } from "./api";
import { adminDestination, authNavigation } from "./auth";
export const authenticated = ref(false);
export const router = createRouter({
  // createWebHashHistory 使用 # 后的路径，例如 /#/admin/sources；刷新仍请求站点根页面。
  history: createWebHashHistory(),
  routes: [
    { path: "/", component: () => import("./pages/PublicPage.vue") },
    { path: "/chat", component: () => import("./pages/ChatPage.vue") },
    {
      path: "/explore/:section(overview|projects|experience|contact)",
      component: () => import("./pages/ExplorePage.vue"),
    },
    { path: "/admin/login", component: () => import("./pages/LoginPage.vue") },
    {
      path: "/admin",
      component: () => import("./pages/AdminLayout.vue"),
      meta: { requiresAuth: true },
      children: [
        { path: "", redirect: "/admin/notes" },
        {
          path: "notes",
          component: () => import("../../frontend/src/pages/NotesPage.vue"),
        },
        { path: "sources", redirect: "/admin/notes" },
        {
          path: "knowledge",
          component: () => import("../../frontend/src/pages/NotesPage.vue"),
        },
        {
          path: "publishing",
          redirect: "/admin/knowledge",
        },
        {
          path: "activity",
          redirect: "/admin/notes",
        },
        { path: "usage", component: () => import("./pages/UsagePage.vue") },
        { path: "backups", component: () => import("./pages/BackupsPage.vue") },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
  // beforeEach 在导航前运行，懒加载 component: () => import(...) 则按需加载目标页面。
  debugFlow("ROUTER", "开始导航，管理页面将向后端核实登录状态", {
    path: to.path,
  });
  return adminDestination(to.path, async () => {
    try {
      await api("/api/auth/me");
      authenticated.value = true;
      debugFlow("AUTH", "服务器确认管理员身份，允许继续导航");
    } catch (error) {
      authenticated.value = false;
      debugFlow("AUTH", "管理员身份核实失败，交给导航规则处理");
      throw error;
    }
  });
});
// afterEach 记录导航结果；failure 存在时不能误报页面已切换成功。
router.afterEach((to, _from, failure) => {
  debugFlow("ROUTER", "导航处理结束", { path: to.path, succeeded: !failure });
});
router.onError(() =>
  debugFlow("ROUTER", "导航发生异常，请检查网络和页面资源加载"),
);
window.addEventListener("admin-session-expired", () => {
  debugFlow("AUTH", "收到登录过期事件，转到登录页");
  authenticated.value = false;
  authNavigation.expired = true;
  void router
    .replace("/admin/login")
    .finally(() => (authNavigation.expired = false));
});
