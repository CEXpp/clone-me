<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api } from "./api";
import { profile, normalizeProfile, toast } from "./shared";
import Icon from "./components/Icon.vue";
import HeartEmblem from "./components/HeartEmblem.vue";
import "./neural.css";
import "./console.css";
const route = useRoute();
const motion = ref(localStorage.getItem("alter-motion") !== "off");
watch(motion, (value) =>
  localStorage.setItem("alter-motion", value ? "on" : "off"),
);
onMounted(async () => {
  try {
    profile.value = normalizeProfile(await api("/api/public/profile"));
  } catch {
    /* Data pages provide retry feedback. */
  }
});
watch(
  () => profile.value.displayName,
  (name) =>
    (document.title = `${name ? name + " · " : ""}一颗心 · 个人数字分身`),
);
</script>
<template>
  <div
    class="neural-app"
    :class="{
      'motion-off': !motion,
      'admin-mode': route.path.startsWith('/admin'),
    }"
  >
    <header v-if="!route.path.startsWith('/admin')" class="neural-header">
      <RouterLink to="/" class="logo" aria-label="一颗心 首页"
        ><HeartEmblem />一颗心<span class="logo-caption"
          >PERSONAL INTELLIGENCE</span
        ></RouterLink
      >
      <nav class="header-nav" aria-label="主导航">
        <RouterLink to="/">概览</RouterLink
        ><RouterLink to="/explore/projects">个人档案</RouterLink
        ><RouterLink to="/chat">分身对话</RouterLink>
      </nav>
      <div class="header-actions">
        <button
          class="motion-button"
          :aria-pressed="motion"
          :aria-label="motion ? '暂停背景动画' : '开启背景动画'"
          @click="motion = !motion"
        >
          <Icon :name="motion ? 'pause' : 'play'" /></button
        ><RouterLink class="console-button" to="/admin/notes"
          ><Icon name="grid" /><span>控制台</span><Icon name="arrow"
        /></RouterLink>
      </div>
    </header>
    <RouterView v-slot="{ Component }"
      ><Transition name="page" mode="out-in"
        ><component
          :is="Component"
          :key="
            route.path.startsWith('/admin')
              ? 'admin'
              : route.path.startsWith('/explore')
                ? 'explore'
                : route.path
          "
          :motion="motion" /></Transition
    ></RouterView>
    <div v-if="toast" role="status" class="neural-toast">{{ toast }}</div>
  </div>
</template>
