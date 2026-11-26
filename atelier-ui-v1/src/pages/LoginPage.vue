<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { api, refreshCsrf, resetCsrf } from "../api";
import { debugFlow } from "../debug";
import { useAction } from "../shared";
import PrismLogo from "../components/PrismLogo.vue";

const router = useRouter();
const { busy, error, run } = useAction();
const username = ref(""),
  password = ref(""),
  code = ref(""),
  recovery = ref(false);

async function login() {
  debugFlow("ADMIN", "提交管理员登录");
  await run(async () => {
    await api("/api/auth/login", "POST", {
      username: username.value,
      password: password.value,
      code: code.value.trim(),
    });
    password.value = "";
    code.value = "";
    resetCsrf();
    await refreshCsrf();
    await router.replace("/admin/notes");
  });
}
</script>

<template>
  <main class="min-h-dvh flex items-center justify-center px-4 py-12">
    <form
      class="glass-panel w-full max-w-sm p-7 space-y-5"
      @submit.prevent="login"
    >
      <div
        class="flex items-center gap-3 pb-3 border-b border-[var(--border-hair)]"
      >
        <PrismLogo :size="24" />
        <div>
          <p class="mono-label">ADMIN ACCESS</p>
          <h1 class="text-lg font-semibold">控制台登录</h1>
        </div>
      </div>

      <p v-if="error" role="alert" class="error">{{ error }}</p>

      <label class="block">
        <span class="field-label">用户名</span>
        <input
          v-model="username"
          class="field"
          autocomplete="username"
          required
          maxlength="40"
          autofocus
        />
      </label>

      <label class="block">
        <span class="field-label">密码</span>
        <input
          v-model="password"
          class="field"
          type="password"
          autocomplete="current-password"
          required
        />
      </label>

      <label class="block">
        <span class="field-label">{{
          recovery ? "一次性恢复码" : "动态验证码"
        }}</span>
        <input
          v-model="code"
          class="field font-mono"
          :inputmode="recovery ? 'text' : 'numeric'"
          :pattern="recovery ? undefined : '[0-9]{6}'"
          :maxlength="recovery ? 80 : 6"
          autocomplete="one-time-code"
          required
          :placeholder="recovery ? '输入离线恢复码' : '6 位动态验证码'"
        />
      </label>

      <button
        type="button"
        class="btn-ghost px-0 text-xs"
        @click="
          recovery = !recovery;
          code = '';
        "
      >
        {{ recovery ? "使用动态验证码" : "改用一次性恢复码" }}
      </button>

      <button type="submit" class="btn-primary w-full" :disabled="busy">
        {{ busy ? "验证中…" : "登录管理台 →" }}
      </button>
    </form>
  </main>
</template>
