<script setup lang="ts">
/**
 * 空间毛玻璃抽屉/弹窗：焦点环绕、Escape 关闭、背景 inert 锁定与焦点还原。
 */
import { nextTick, onBeforeUnmount, ref, useId, watch } from "vue";
import Icon from "./Icon.vue";

const props = defineProps<{
  open: boolean;
  title: string;
  wide?: boolean;
  center?: boolean;
}>();
const emit = defineEmits<{ close: [] }>();
const panel = ref<HTMLElement>();
const titleId = useId();
let previous: HTMLElement | null = null,
  previousOverflow = "";

function keyboard(event: KeyboardEvent) {
  if (!props.open) return;
  if (event.key === "Escape") {
    event.preventDefault();
    emit("close");
  }
  if (event.key === "Tab") {
    const nodes = panel.value?.querySelectorAll<HTMLElement>(
      'button:not(:disabled),a[href],input:not(:disabled),textarea:not(:disabled),select:not(:disabled),[tabindex="0"]',
    );
    const focusable = Array.from(nodes || []).filter(
      (n) => n.getClientRects().length,
    );
    const first = focusable[0],
      last = focusable.at(-1);
    if (!first) {
      event.preventDefault();
      panel.value?.focus();
      return;
    }
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
}

function unlock() {
  document.body.style.overflow = previousOverflow;
  const app = document.getElementById("app");
  if (app) app.inert = false;
  window.removeEventListener("keydown", keyboard);
  previous?.focus();
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      previous = document.activeElement as HTMLElement;
      previousOverflow = document.body.style.overflow;
      document.body.style.overflow = "hidden";
      const app = document.getElementById("app");
      if (app) app.inert = true;
      window.addEventListener("keydown", keyboard);
      await nextTick();
      panel.value?.focus();
    } else {
      unlock();
    }
  },
);

onBeforeUnmount(() => {
  if (props.open) unlock();
});
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-50 flex"
      :class="center ? 'items-center justify-center p-4' : 'justify-end'"
    >
      <div
        class="drawer-backdrop absolute inset-0"
        @click="emit('close')"
      ></div>
      <section
        ref="panel"
        tabindex="-1"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        class="drawer-panel relative w-full flex flex-col focus:outline-none"
        :class="
          center
            ? 'max-w-lg rounded-2xl max-h-[85dvh]'
            : wide
              ? 'max-w-2xl h-dvh'
              : 'max-w-xl h-dvh'
        "
      >
        <header class="drawer-header">
          <h2 :id="titleId" class="text-sm font-semibold tracking-tight">
            {{ title }}
          </h2>
          <button
            type="button"
            class="btn-ghost"
            aria-label="关闭面板"
            @click="emit('close')"
          >
            <Icon name="close" />
          </button>
        </header>
        <div class="overflow-y-auto p-5 space-y-4 safe-bottom">
          <slot />
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.drawer-backdrop {
  background: var(--scrim);
  backdrop-filter: blur(8px);
}

.drawer-panel {
  background: var(--bg-panel);
  color: var(--text-main);
  border: 1px solid var(--border-hair);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.5);
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-hair);
}
</style>
