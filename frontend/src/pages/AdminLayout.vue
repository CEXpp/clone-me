<script setup lang="ts">
/**
 * 管理台导航与会话状态框架，退出时明确确认并调用服务端撤销接口。
 * 会话过期由公共请求层触发事件，路由统一返回登录界面。
 */
import { useRouter } from "vue-router";
import { ref } from "vue";
import Drawer from "../components/Drawer.vue";
import { api, resetCsrf } from "../api";
import { authenticated } from "../router";
import { authNavigation } from "../auth";
import { useAction } from "../shared";
const router = useRouter(),
  { busy, error, run } = useAction();
const logoutOpen = ref(false);
const tabs = [
  ["sources", "01", "资料审阅"],
  ["publishing", "02", "版本与展示"],
  ["activity", "03", "任务与诊断"],
  ["usage", "04", "用量与模型"],
  ["backups", "05", "通知与备份"],
];
async function logout() {
  authNavigation.logoutConfirmed = true;
  await run(async () => {
    await api("/api/auth/logout", "POST", {});
    authenticated.value = false;
    resetCsrf();
    await router.replace("/admin/login");
  });
  authNavigation.logoutConfirmed = false;
  logoutOpen.value = false;
}
</script>
<template>
  <main
    v-if="authenticated"
    class="relative max-w-6xl mx-auto px-4 sm:px-8 py-7 sm:py-10"
  >
    <header class="flex flex-wrap justify-between gap-4 mb-7">
      <div>
        <div class="flex gap-3 items-center">
          <p class="eyebrow">OWNER CONSOLE</p>
          <span class="tag !bg-emerald-50 !text-emerald-800">● 已认证</span>
        </div>
        <h1 class="mt-2 text-xl font-semibold tracking-tight">
          分身知识库与运行管理台
        </h1>
      </div>
      <button
        class="btn self-start"
        :disabled="busy"
        @click="logoutOpen = true"
      >
        退出登录
      </button>
    </header>
    <p v-if="error" role="alert" class="error mb-4">{{ error }}</p>
    <nav
      aria-label="管理功能"
      class="flex overflow-x-auto gap-2 border-b border-slate-200 pb-4 mb-6"
    >
      <RouterLink
        v-for="tab in tabs"
        :key="tab[0]"
        :to="'/admin/' + tab[0]"
        class="btn whitespace-nowrap"
        active-class="!bg-zinc-950 !text-white !border-zinc-950"
        ><span class="font-mono text-[10px] opacity-60">{{ tab[1] }}</span
        >{{ tab[2] }}</RouterLink
      >
    </nav>
    <RouterView />
  </main>
  <div v-else class="empty">正在确认管理员身份…</div>
  <Drawer
    :open="logoutOpen"
    title="退出管理员登录？"
    center
    @close="logoutOpen = false"
    ><p class="text-sm text-slate-600">
      未保存的修改将丢失，访客聊天记录仍然保留。
    </p>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="flex justify-end gap-2">
      <button class="btn" @click="logoutOpen = false">取消</button
      ><button class="btn-primary" :disabled="busy" @click="logout">
        确认退出
      </button>
    </div></Drawer
  >
</template>
