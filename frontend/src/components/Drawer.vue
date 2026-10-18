<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch, useId } from "vue";
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
    } else unlock();
  },
);
onBeforeUnmount(() => {
  if (props.open) unlock();
});
</script>
<template>
  <Teleport to="body"
    ><div
      v-if="open"
      class="fixed inset-0 z-50 flex"
      :class="center ? 'items-center justify-center p-4' : 'justify-end'"
    >
      <div
        class="absolute inset-0 bg-zinc-950/20 backdrop-blur-[2px]"
        @click="emit('close')"
      ></div>
      <section
        ref="panel"
        tabindex="-1"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        class="relative bg-white shadow-2xl w-full flex flex-col focus:outline-none"
        :class="
          center
            ? 'max-w-md rounded-3xl max-h-[85dvh]'
            : wide
              ? 'max-w-3xl h-dvh border-l border-slate-200'
              : 'max-w-xl h-dvh border-l border-slate-200'
        "
      >
        <header
          class="flex items-center justify-between gap-4 p-5 sm:p-6 border-b border-slate-200"
        >
          <h2 :id="titleId" class="text-sm font-semibold tracking-tight">
            {{ title }}
          </h2>
          <button
            type="button"
            class="btn !p-2 !border-transparent"
            aria-label="关闭面板"
            @click="emit('close')"
          >
            ✕
          </button>
        </header>
        <div class="overflow-y-auto p-5 sm:p-7 space-y-5 safe-bottom">
          <slot />
        </div>
      </section></div
  ></Teleport>
</template>
