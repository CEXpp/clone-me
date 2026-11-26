<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "./api";
import {
  motion,
  normalizeProfile,
  profile,
  theme,
  toast,
  toggleTheme,
} from "./shared";
import Icon from "./components/Icon.vue";
import PrismLogo from "./components/PrismLogo.vue";
import SingularityCanvas from "./components/SingularityCanvas.vue";

const route = useRoute();
const router = useRouter();
const navigating = ref(false);

const stopBefore = router.beforeEach(() => {
  navigating.value = true;
});
const stopAfter = router.afterEach(() => {
  navigating.value = false;
});

onMounted(async () => {
  try {
    profile.value = normalizeProfile(await api("/api/public/profile"));
  } catch {
    /* 访客页面展示重试提示 */
  }
});

onBeforeUnmount(() => {
  stopBefore();
  stopAfter();
});

watch(
  () => profile.value.displayName,
  (name) => {
    document.title = name ? `${name} · PRISM` : "PRISM · 个人数字分身";
  },
  { immediate: true },
);
</script>

<template>
  <div class="prism-shell" :class="{ 'motion-off': !motion }">
    <SingularityCanvas
      v-if="!route.path.startsWith('/admin')"
      :motion="motion"
      :dimmed="route.path !== '/'"
      :active="route.path === '/chat'"
    />

    <header v-if="!route.path.startsWith('/admin')" class="capsule-header">
      <div class="capsule-bar">
        <RouterLink to="/" class="capsule-brand" title="返回首页">
          <PrismLogo :size="20" />
          <span>{{ profile.displayName || "PRISM" }}</span>
        </RouterLink>

        <nav class="capsule-links" aria-label="主导航">
          <RouterLink to="/" :class="{ 'nav-active': route.path === '/' }"
            >首页</RouterLink
          >
          <RouterLink
            to="/chat"
            :class="{ 'nav-active': route.path === '/chat' }"
            >对话</RouterLink
          >
          <RouterLink
            to="/folio/projects"
            :class="{ 'nav-active': route.path.startsWith('/folio') }"
            >档案</RouterLink
          >
        </nav>

        <div class="flex items-center gap-1">
          <button
            type="button"
            class="btn-ghost"
            :aria-pressed="theme === 'titanium'"
            :aria-label="
              theme === 'obsidian' ? '切换钛银浅色模式' : '切换暗曜深色模式'
            "
            @click="toggleTheme"
          >
            <Icon :name="theme === 'obsidian' ? 'sun' : 'moon'" :size="15" />
          </button>
          <button
            type="button"
            class="btn-ghost"
            :aria-pressed="motion"
            :aria-label="motion ? '暂停空间动效' : '开启空间动效'"
            @click="motion = !motion"
          >
            <Icon :name="motion ? 'pause' : 'play'" :size="14" />
          </button>
          <RouterLink to="/admin/notes" class="btn !py-1.5 !px-3 text-xs">
            控制台
          </RouterLink>
        </div>
      </div>
    </header>

    <div
      v-if="navigating"
      role="status"
      aria-label="加载中"
      class="fixed top-0 inset-x-0 h-0.5 z-50 bg-[var(--accent)]"
    ></div>

    <RouterView v-slot="{ Component }">
      <Transition name="prism-fade" mode="out-in">
        <component
          :is="Component"
          :key="
            route.path.startsWith('/admin')
              ? 'admin'
              : route.path.startsWith('/folio')
                ? 'folio'
                : route.path
          "
        />
      </Transition>
    </RouterView>

    <div v-if="toast" role="status" class="prism-toast">{{ toast }}</div>
  </div>
</template>
