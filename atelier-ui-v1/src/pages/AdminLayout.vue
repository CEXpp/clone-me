<script setup lang="ts">
import { useRouter } from "vue-router";
import { api, resetCsrf } from "../api";
import { authenticated } from "../router";
import { useAction } from "../shared";
import AtelierAdminShell from "../components/AtelierAdminShell.vue";

const router = useRouter();
const { busy, error, run } = useAction();

async function logout() {
  await run(async () => {
    // 离开笔记/名片编辑器时触发路由守卫，先保存或确认未保存变更再注销会话。
    if (await router.push("/admin/usage")) return;
    await api("/api/auth/logout", "POST", {});
    authenticated.value = false;
    resetCsrf();
    await router.replace("/admin/login");
  });
}
</script>

<template>
  <AtelierAdminShell
    :authenticated="authenticated"
    :busy="busy"
    :error="error"
    @logout="logout"
  />
</template>
