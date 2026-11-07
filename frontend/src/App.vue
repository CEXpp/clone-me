<script setup lang="ts">
/**
 * 全站框架，提供公开导航、管理入口和操作提示；具体聊天与管理业务由子路由承载。
 * 从公开页面切换到管理台必须经过路由的服务端身份检查。
 */
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "./api";
import { normalizeProfile, profile, toast } from "./shared";
const route = useRoute(),
  router = useRouter();
const loading = ref(false);
const sections = [
  ["overview", "01", "简介与能力"],
  ["experience", "02", "经历"],
  ["projects", "03", "项目"],
  ["contact", "04", "联系"],
];
function open(section: string) {
  // 抽屉选择写入 URL 查询参数，PublicPage 从路由派生打开状态，浏览器前进后退也能还原。
  if (route.path !== "/") void router.push({ path: "/", query: { section } });
  else void router.replace({ query: { section } });
}
const stopBefore = router.beforeEach(() => {
  loading.value = true;
});
const stopAfter = router.afterEach(() => {
  loading.value = false;
});
onMounted(async () => {
  try {
    profile.value = normalizeProfile(await api("/api/public/profile"));
  } catch {
    /* 资料加载失败由访客页面展示错误和重试入口，顶栏保持可用。 */
  }
});
onBeforeUnmount(() => {
  stopBefore();
  stopAfter();
});
watch(
  () => profile.value.displayName,
  (name) => (document.title = name ? `${name} · 文字分身` : "个人文字分身"),
);
</script>
<template>
  <header
    v-if="!route.path.startsWith('/admin')"
    class="topbar fixed inset-x-0 top-0 z-30 border-b border-slate-200/80 bg-white/90 backdrop-blur-md px-4 lg:px-8 flex flex-wrap items-center justify-between gap-x-3"
  >
    <RouterLink
      to="/"
      class="flex items-center gap-2.5 text-sm font-semibold tracking-tight"
      ><span class="h-2 w-2 rounded-full bg-zinc-950"></span
      ><span
        class="max-w-[38vw] sm:max-w-[200px] truncate"
        :title="profile.displayName"
        >{{ profile.displayName || "个人文字分身" }}</span
      ><span class="hidden lg:inline eyebrow border border-slate-200 px-2 py-1"
        >AI MONOGRAPH</span
      ></RouterLink
    >
    <nav
      v-if="route.path === '/'"
      aria-label="个人资料"
      class="order-last sm:order-none w-full sm:w-auto flex gap-1 overflow-x-auto pb-2 sm:pb-0 sm:mr-auto sm:ml-4 sm:pl-4 sm:border-l border-slate-200"
    >
      <button
        v-for="section in sections"
        :key="section[0]"
        class="btn !px-2.5 !py-1 !border-transparent whitespace-nowrap"
        @click="open(section[0])"
      >
        <span class="eyebrow">{{ section[1] }}</span
        >{{ section[2] }}
      </button>
    </nav>
    <nav
      aria-label="站点模式"
      class="inline-flex rounded-full bg-slate-100 border border-slate-200 p-0.5 text-xs whitespace-nowrap"
    >
      <RouterLink
        to="/"
        class="px-3 py-1.5 rounded-full"
        :class="
          route.path === '/' ? 'bg-zinc-950 text-white' : 'text-slate-500'
        "
        >访客对话</RouterLink
      >
      <RouterLink
        to="/admin/notes"
        class="px-3 py-1.5 rounded-full"
        :class="
          route.path.startsWith('/admin')
            ? 'bg-zinc-950 text-white'
            : 'text-slate-500'
        "
        >管理台</RouterLink
      >
    </nav>
  </header>
  <div
    v-if="loading"
    role="status"
    class="fixed top-0 inset-x-0 h-0.5 z-50 bg-slate-500"
  ></div>
  <div
    class="page-root min-h-dvh relative"
    :class="{ 'swiss-grid': !route.path.startsWith('/admin') }"
    :style="route.path.startsWith('/admin') ? { paddingTop: '0' } : undefined"
  >
    <RouterView />
  </div>
  <div
    v-if="toast"
    role="status"
    class="fixed bottom-5 inset-x-4 sm:left-auto sm:right-5 z-[80] rounded-2xl bg-zinc-950 text-white text-xs px-5 py-3 shadow-lg max-w-lg"
  >
    {{ toast }}
  </div>
</template>
