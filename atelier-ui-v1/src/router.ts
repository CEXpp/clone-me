/**
 * 使用哈希路由，由服务端静态目录托管时无需额外回退规则。
 * 进入任何管理路由前都先向服务器验证身份。
 */
import { createRouter, createWebHashHistory } from "vue-router";
import { ref } from "vue";
import { api } from "./api";
import { adminDestination, authNavigation } from "./auth";
import { debugFlow } from "./debug";

export const authenticated = ref(false);

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", component: () => import("./pages/PublicPage.vue") },
    {
      path: "/folio/:section(overview|projects|experience|contact)",
      component: () => import("./pages/FolioPage.vue"),
    },
    {
      path: "/explore/:section(overview|projects|experience|contact)",
      redirect: (to) => `/folio/${to.params.section}`,
    },
    { path: "/chat", component: () => import("./pages/DialoguePage.vue") },
    { path: "/admin/login", component: () => import("./pages/LoginPage.vue") },
    {
      path: "/admin",
      component: () => import("./pages/AdminLayout.vue"),
      meta: { requiresAuth: true },
      children: [
        { path: "", redirect: "/admin/notes" },
        { path: "notes", component: () => import("./pages/NotesPage.vue") },
        { path: "sources", redirect: "/admin/notes" },
        { path: "knowledge", component: () => import("./pages/NotesPage.vue") },
        { path: "profile", component: () => import("./pages/ProfilePage.vue") },
        { path: "publishing", redirect: "/admin/profile" },
        { path: "activity", redirect: "/admin/notes" },
        { path: "usage", component: () => import("./pages/UsagePage.vue") },
        { path: "backups", component: () => import("./pages/BackupsPage.vue") },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});

router.beforeEach(async (to) => {
  debugFlow("ROUTER", "开始路由导航", { path: to.path });
  return adminDestination(to.path, async () => {
    try {
      await api("/api/auth/me");
      authenticated.value = true;
    } catch (error) {
      authenticated.value = false;
      throw error;
    }
  });
});

router.afterEach((to, _from, failure) => {
  debugFlow("ROUTER", "导航处理结束", { path: to.path, succeeded: !failure });
});

router.onError(() =>
  debugFlow("ROUTER", "页面模块加载失败，请检查网络连接或刷新重试"),
);

window.addEventListener("admin-session-expired", () => {
  debugFlow("AUTH", "管理员登录已过期，返回登录页");
  authenticated.value = false;
  authNavigation.expired = true;
  void router
    .replace("/admin/login")
    .finally(() => (authNavigation.expired = false));
});
