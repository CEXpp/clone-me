<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from "vue";
declare global {
  interface Window {
    turnstile?: {
      render: (node: HTMLElement, options: any) => string;
      remove: (id: string) => void;
      reset: (id: string) => void;
    };
  }
}
const props = defineProps<{ siteKey: string }>();
const emit = defineEmits<{ token: [value: string] }>();
const host = ref<HTMLElement>(),
  error = ref("");
let widget: string | undefined,
  disposed = false;
function restart() {
  if (widget) {
    window.turnstile?.reset(widget);
    emit("token", "");
    error.value = "";
  }
}
onMounted(async () => {
  try {
    if (!window.turnstile)
      await new Promise<void>((resolve, reject) => {
        const script = document.createElement("script");
        script.src =
          "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
        script.async = true;
        script.onload = () => resolve();
        script.onerror = () =>
          reject(new Error("验证组件加载失败，请稍后重试"));
        document.head.append(script);
      });
    if (disposed || !host.value) return;
    widget = window.turnstile!.render(host.value, {
      sitekey: props.siteKey,
      action: "chat",
      callback: (token: string) => emit("token", token),
      "expired-callback": () => emit("token", ""),
      "error-callback": () => {
        error.value = "人机验证未完成，请刷新验证后重试";
        emit("token", "");
      },
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : "验证不可用";
  }
});
onBeforeUnmount(() => {
  disposed = true;
  if (widget) window.turnstile?.remove(widget);
});
</script>
<template>
  <div class="notice">
    <p class="mb-2">请求较频繁，请完成人机验证后主动重试。</p>
    <div ref="host"></div>
    <p v-if="error" role="alert">{{ error }}</p>
    <button v-if="widget" class="btn mt-2" @click="restart">重新验证</button>
  </div>
</template>
