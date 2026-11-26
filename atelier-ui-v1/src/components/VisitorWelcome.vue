<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { visitorLocation, loadVisitorLocation } from "../visitorLocation";

const loading = ref(true);
const imageFailed = ref(false);
const imageVersion = ref(0);
let controller: AbortController | undefined;

const greeting = computed(() =>
  visitorLocation.value?.place
    ? `来访节点 · ${visitorLocation.value.place}`
    : loading.value
      ? "正在查询来访节点…"
      : "来访网络节点",
);

const cardUrl = computed(() => {
  const params = new URLSearchParams({
    style: "cyber",
    sticker: "st-boss",
    w: "360",
  });
  if (visitorLocation.value?.ip) params.set("ip", visitorLocation.value.ip);
  if (imageVersion.value) params.set("v", String(imageVersion.value));
  return `https://card.net.coffee/v1/card.svg?${params}`;
});

async function load() {
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  imageFailed.value = false;
  try {
    await loadVisitorLocation(controller.signal);
  } catch {
    /* 忽略外部查询超时 */
  } finally {
    loading.value = false;
  }
}

function retry() {
  imageVersion.value++;
  void load();
}

onMounted(load);
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <aside class="node-card" aria-label="访客节点卡片">
    <div class="flex items-center justify-between gap-2 mb-2">
      <span class="text-xs font-medium">{{ greeting }}</span>
      <button
        v-if="!loading && !visitorLocation?.place && !imageFailed"
        type="button"
        class="btn-ghost text-xs"
        @click="retry"
      >
        重试
      </button>
    </div>
    <a
      v-if="!imageFailed"
      href="https://card.net.coffee/ip/"
      target="_blank"
      rel="noopener noreferrer"
      aria-label="查看 Net.Coffee IP 信息卡片"
      class="block rounded-lg overflow-hidden border border-[var(--border-hair)]"
    >
      <img
        :src="cardUrl"
        width="360"
        height="170"
        alt="当前访客的 IP 与归属地节点"
        referrerpolicy="no-referrer"
        @error="imageFailed = true"
      />
    </a>
    <p v-else class="empty !py-6">
      节点卡片暂不可用
      <button type="button" class="underline ml-2" @click="retry">重试</button>
    </p>
  </aside>
</template>

<style scoped>
.node-card {
  padding: 12px;
  border-radius: 12px;
  background: var(--bg-subtle);
  border: 1px solid var(--border-hair);
}

img {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 36 / 17;
}
</style>
