import { createRouter, createWebHashHistory } from "vue-router";
import { ref } from "vue";
import { api, ApiError } from "./api";
import { adminDestination, authNavigation } from "./auth";
export const authenticated = ref(false);
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", component: () => import("./pages/PublicPage.vue") },
    { path: "/admin/login", component: () => import("./pages/LoginPage.vue") },
    {
      path: "/admin",
      component: () => import("./pages/AdminLayout.vue"),
      meta: { requiresAuth: true },
      children: [
        { path: "", redirect: "/admin/sources" },
        { path: "sources", component: () => import("./pages/SourcesPage.vue") },
        {
          path: "publishing",
          component: () => import("./pages/PublishingPage.vue"),
        },
        {
          path: "activity",
          component: () => import("./pages/ActivityPage.vue"),
        },
        { path: "usage", component: () => import("./pages/UsagePage.vue") },
        { path: "backups", component: () => import("./pages/BackupsPage.vue") },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
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
window.addEventListener("admin-session-expired", () => {
  authenticated.value = false;
  authNavigation.expired = true;
  void router
    .replace("/admin/login")
    .finally(() => (authNavigation.expired = false));
});
