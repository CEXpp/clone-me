<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
import { theme, toggleTheme } from "../shared";
import Icon from "./Icon.vue";
import PrismLogo from "./PrismLogo.vue";

defineProps<{ authenticated: boolean; busy: boolean; error: string }>();
defineEmits<{ logout: [] }>();

const route = useRoute();
const systemMenu = ref<HTMLDetailsElement>();

const isNotebook = () =>
  ["/admin/notes", "/admin/knowledge"].includes(route.path);

const isSystemRoute = () =>
  ["/admin/usage", "/admin/backups"].includes(route.path);

watch(
  () => route.path,
  () => {
    if (systemMenu.value) systemMenu.value.open = false;
  },
);
</script>

<template>
  <main v-if="authenticated" class="admin-shell">
    <header class="admin-bar">
      <div class="admin-left">
        <RouterLink to="/admin/notes" class="admin-brand">
          <PrismLogo :size="20" />
          <span>PRISM</span>
        </RouterLink>

        <nav class="admin-nav" aria-label="后台功能区">
          <RouterLink
            to="/admin/notes"
            :class="{ active: route.path === '/admin/notes' }"
            >笔记</RouterLink
          >
          <RouterLink
            to="/admin/knowledge"
            :class="{ active: route.path === '/admin/knowledge' }"
            >知识库</RouterLink
          >
          <RouterLink
            to="/admin/profile"
            :class="{ active: route.path === '/admin/profile' }"
            >名片</RouterLink
          >

          <details ref="systemMenu" class="sys-dropdown">
            <summary :class="{ active: isSystemRoute() }">系统 ▾</summary>
            <div class="sys-menu">
              <RouterLink to="/admin/usage">费用与模型</RouterLink>
              <RouterLink to="/admin/backups">备份与状态</RouterLink>
            </div>
          </details>
        </nav>
      </div>

      <div class="admin-right">
        <button
          type="button"
          class="btn-ghost"
          :aria-label="
            theme === 'obsidian' ? '切换钛银浅色模式' : '切换暗曜深色模式'
          "
          @click="toggleTheme"
        >
          <Icon :name="theme === 'obsidian' ? 'sun' : 'moon'" :size="15" />
        </button>
        <RouterLink to="/" class="btn-ghost">主页 ↗</RouterLink>
        <button
          type="button"
          class="btn-ghost text-[var(--danger)]"
          :disabled="busy"
          @click="$emit('logout')"
        >
          退出
        </button>
      </div>
    </header>

    <p v-if="error" role="alert" class="error mx-4 mt-3">{{ error }}</p>

    <div class="admin-body" :class="{ 'admin-scroll': !isNotebook() }">
      <div v-if="!isNotebook()" class="admin-container">
        <RouterView />
      </div>
      <RouterView v-else />
    </div>
  </main>
  <div v-else role="status" class="empty m-6">正在核验权限…</div>
</template>

<style scoped>
.admin-shell {
  height: 100dvh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-void);
  color: var(--text-main);
}

.admin-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 16px;
  min-height: 48px;
  background: var(--bg-panel);
  border-bottom: 1px solid var(--border-hair);
  flex-shrink: 0;
  flex-wrap: wrap;
}

.admin-left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.admin-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  font-size: 13px;
  letter-spacing: 0.08em;
  color: var(--text-main);
  text-decoration: none;
}

.admin-nav {
  display: flex;
  align-items: center;
  gap: 4px;
}

.admin-nav > a,
.sys-dropdown > summary {
  padding: 6px 11px;
  border-radius: 9999px;
  font-size: 13px;
  color: var(--text-sub);
  text-decoration: none;
  cursor: pointer;
  list-style: none;
  white-space: nowrap;
}

.sys-dropdown > summary::-webkit-details-marker {
  display: none;
}

.admin-nav > a:hover,
.sys-dropdown > summary:hover {
  color: var(--text-main);
  background: var(--bg-subtle);
}

.admin-nav > a.active,
.sys-dropdown > summary.active {
  color: var(--text-main);
  background: var(--bg-hover);
  box-shadow: inset 0 0 0 1px var(--border-glow);
}

.sys-dropdown {
  position: relative;
}

.sys-menu {
  position: absolute;
  left: 0;
  top: calc(100% + 6px);
  min-width: 148px;
  padding: 6px;
  border-radius: 12px;
  background: var(--bg-panel);
  border: 1px solid var(--border-hair);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.45);
  z-index: 40;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sys-menu a {
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-sub);
  text-decoration: none;
}

.sys-menu a:hover,
.sys-menu a.router-link-active {
  background: var(--bg-subtle);
  color: var(--text-main);
}

.admin-right {
  display: flex;
  align-items: center;
  gap: 4px;
}

.admin-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.admin-scroll {
  overflow-y: auto;
}

.admin-container {
  max-width: 960px;
  width: 100%;
  margin: 0 auto;
  padding: 24px 18px 48px;
}
</style>
