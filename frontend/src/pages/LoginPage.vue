<script setup lang="ts">
/**
 * 真实管理员登录表单，提交密码与动态口令或一次性恢复码。
 * 登录后刷新防伪状态再进入管理台，不在浏览器保存口令或伪造登录标记。
 */
import { debugFlow } from "../debug";
import { ref } from "vue";
import { useRouter } from "vue-router";
import { api, refreshCsrf, resetCsrf } from "../api";
import { useAction } from "../shared";
const router = useRouter(),
  { busy, error, run } = useAction();
const username = ref(""),
  password = ref(""),
  code = ref(""),
  recovery = ref(false);
/** 登录数据仅通过 API 提交；日志不输出账号、密码和动态验证码。 */
async function login() {
  debugFlow("ADMIN", "开始提交管理员登录");
  // 输入框由 ref 绑定；await 顺序保证登录完成、CSRF 刷新后才进入需要身份的管理页面。
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
    await router.replace("/admin/sources");
  });
}
</script>
<template>
  <main class="max-w-md mx-auto px-4 py-16 sm:py-24">
    <form
      class="panel !p-7 sm:!p-9 space-y-6 shadow-sm"
      @submit.prevent="login"
    >
      <div>
        <p class="eyebrow mb-2">ADMIN AUTHENTICATION · 01</p>
        <h1 class="text-2xl font-semibold tracking-tight">管理员登录</h1>
        <p class="muted mt-2">
          此入口仅供本人管理资料。请输入密码与动态验证码。
        </p>
      </div>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <label class="block"
        ><span class="field-label">用户名</span
        ><input
          v-model="username"
          class="field"
          autocomplete="username"
          required
          maxlength="40"
          autofocus
      /></label>
      <label class="block"
        ><span class="field-label">密码</span
        ><input
          v-model="password"
          class="field"
          type="password"
          autocomplete="current-password"
          required
      /></label>
      <label class="block"
        ><span class="field-label">{{
          recovery ? "一次性恢复码" : "动态验证码"
        }}</span
        ><input
          v-model="code"
          class="field font-mono"
          :inputmode="recovery ? 'text' : 'numeric'"
          :pattern="recovery ? undefined : '[0-9]{6}'"
          :maxlength="recovery ? 80 : 6"
          autocomplete="one-time-code"
          required
          :placeholder="recovery ? '输入离线保存的恢复码' : '6 位动态验证码'"
      /></label>
      <button
        type="button"
        class="text-xs underline underline-offset-4 text-slate-500"
        @click="
          recovery = !recovery;
          code = '';
        "
      >
        {{ recovery ? "使用动态验证码" : "改用一次性恢复码" }}
      </button>
      <button class="btn-primary w-full !py-3" :disabled="busy">
        {{ busy ? "正在验证…" : "登录管理台 →" }}
      </button>
      <p class="muted">
        没有注册入口。若尚未初始化管理员，请先按本地开发文档完成初始化。
      </p>
    </form>
  </main>
</template>
