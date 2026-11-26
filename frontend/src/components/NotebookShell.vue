<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
defineProps<{ authenticated: boolean; busy: boolean; error: string }>();
defineEmits<{ logout: [] }>();
const route = useRoute();
const menu = ref<HTMLDetailsElement>();
const isNotebook = computed(() =>
  ["/admin/notes", "/admin/knowledge"].includes(route.path),
);
watch(
  () => route.path,
  () => {
    if (menu.value) menu.value.open = false;
  },
);
</script>
<template>
  <main v-if="authenticated" class="notebook-shell">
    <header class="notebook-titlebar">
      <RouterLink to="/admin/notes" class="notebook-brand">
        <svg viewBox="0 0 24 24" width="21" height="21" aria-hidden="true">
          <path
            d="M5 3h14v18H5zM2 7h5M2 12h5M2 17h5M10 8h5M10 12h5"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
          />
        </svg>
        <span>笔记</span>
      </RouterLink>
      <details ref="menu" class="account-menu">
        <summary aria-label="设置">
          设置 <span aria-hidden="true">⌄</span>
        </summary>
        <nav aria-label="笔记设置">
          <RouterLink to="/admin/usage">模型与用量</RouterLink>
          <RouterLink to="/admin/backups">备份</RouterLink>
          <RouterLink to="/">访客首页</RouterLink>
          <button :disabled="busy" @click="$emit('logout')">退出登录</button>
        </nav>
      </details>
    </header>
    <p v-if="error" role="alert" class="shell-error">{{ error }}</p>
    <div class="notebook-body" :class="{ 'notebook-settings': !isNotebook }">
      <RouterView />
    </div>
  </main>
  <div v-else role="status" class="empty">正在打开笔记…</div>
</template>
<style scoped>
.notebook-shell {
  height: 100dvh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color-scheme: dark;
  background: #201f1e;
  color: #e6e6e6;
  font-family: "Segoe UI", "Microsoft YaHei", sans-serif;
}
.notebook-titlebar {
  height: 42px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
  background: #090909;
  color: #fff;
  font-size: 13px;
}
.notebook-brand {
  display: flex;
  gap: 10px;
  align-items: center;
  font-weight: 600;
  color: inherit;
  text-decoration: none;
}
.notebook-brand svg {
  color: #b973d3;
}
.account-menu {
  position: relative;
}
.account-menu summary {
  list-style: none;
  cursor: pointer;
  padding: 10px 12px;
  display: flex;
  gap: 12px;
}
.account-menu summary::-webkit-details-marker {
  display: none;
}
.account-menu nav {
  position: absolute;
  z-index: 60;
  right: 0;
  top: 38px;
  min-width: 170px;
  padding: 5px;
  background: #201f1e;
  color: #e6e6e6;
  border: 1px solid #3b3b3b;
  box-shadow: 0 4px 12px #0002;
}
.account-menu nav a,
.account-menu nav button {
  display: block;
  width: 100%;
  padding: 11px 14px;
  text-align: left;
  text-decoration: none;
  color: inherit;
  font-size: 13px;
}
.account-menu nav a:hover,
.account-menu nav button:hover {
  background: #3b3b3b;
}
.notebook-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.notebook-settings {
  overflow: auto;
  padding: 28px;
  max-width: 1200px;
  width: 100%;
  margin: auto;
  color: #292929;
  background: #fff;
}
.shell-error {
  padding: 10px 20px;
  background: #fff1df;
  color: #713d12;
  font-size: 13px;
}
:global(.admin-mode .notebook-shell) {
  background: #201f1e;
  color: #ececf0;
}
:global(.admin-mode .account-menu nav) {
  background: #292929;
  color: #eee;
  border-color: #3b3b3b;
}
:global(.admin-mode .account-menu nav a:hover),
:global(.admin-mode .account-menu nav button:hover) {
  background: #3b3b3b;
}
:global(.admin-mode .notebook-settings) {
  background: #201f1e;
  color: #e6e6e6;
}
button:focus-visible,
a:focus-visible,
summary:focus-visible {
  outline: 2px solid #b49ac5;
  outline-offset: -2px;
}
@media (max-width: 600px) {
  .notebook-titlebar {
    padding: 0 12px;
  }
  .notebook-settings {
    padding: 18px;
  }
}
</style>
