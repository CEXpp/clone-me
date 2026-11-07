<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { visitorLocation, loadVisitorLocation } from "../visitorLocation";
defineProps<{ dark?: boolean }>();
const loading = ref(true);
const imageFailed = ref(false);
const imageVersion = ref(0);
let controller: AbortController | undefined;
const greeting = computed(() =>
  visitorLocation.value?.place
    ? `欢迎来自${visitorLocation.value.place}的 BOSS`
    : loading.value
      ? "正在为 BOSS 查询来访地…"
      : "欢迎光临，BOSS",
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
    /* 定位不可用时保留通用欢迎语，其他功能照常使用。 */
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
  <aside
    class="visitor-welcome"
    :class="{ 'visitor-dark': dark }"
    aria-label="访客欢迎卡片"
  >
    <p class="visitor-greeting" role="status">{{ greeting }}</p>
    <a
      v-if="!imageFailed"
      href="https://card.net.coffee/ip/"
      target="_blank"
      rel="noopener noreferrer"
      aria-label="查看 Net.Coffee IP 信息卡片"
    >
      <img
        :src="cardUrl"
        width="360"
        height="170"
        alt="当前访客的 IP、归属地与网络信息"
        referrerpolicy="no-referrer"
        @error="imageFailed = true"
      />
    </a>
    <p v-else class="visitor-fallback">
      IP 卡片暂时不可用 <button type="button" @click="retry">重试</button>
    </p>
    <div class="visitor-caption">
      <span>IP 归属地仅供参考 · Net.Coffee</span
      ><button
        v-if="!loading && !visitorLocation?.place && !imageFailed"
        type="button"
        @click="retry"
      >
        重试定位
      </button>
    </div>
  </aside>
</template>
<style scoped>
.visitor-welcome {
  width: min(280px, 100%);
  border: 1px solid #cbd5e1;
  border-radius: 12px;
  padding: 12px;
  background: #ffffffed;
  color: #1e293b;
  box-shadow: 0 6px 24px #0f172a08;
}
.visitor-greeting {
  font-size: 13px;
  line-height: 1.7;
  font-weight: 500;
  margin: 0 0 8px;
  overflow-wrap: anywhere;
}
a {
  display: block;
  border-radius: 7px;
  overflow: hidden;
}
img {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 36 / 17;
}
.visitor-caption {
  display: flex;
  gap: 6px;
  justify-content: space-between;
  font-size: 10px;
  line-height: 1.6;
  margin-top: 7px;
  color: #52647c;
}
.visitor-fallback {
  min-height: 110px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 12px;
}
button {
  text-decoration: underline;
  text-underline-offset: 3px;
  color: inherit;
  cursor: pointer;
}
button:focus-visible,
a:focus-visible {
  outline: 2px solid #0891b2;
  outline-offset: 4px;
}
.visitor-dark {
  background: #101d2ded;
  border-color: #35485f;
  color: #dcecf7;
  box-shadow: 0 10px 24px #0002;
}
.visitor-dark .visitor-caption {
  color: #a5b6cb;
}
</style>
