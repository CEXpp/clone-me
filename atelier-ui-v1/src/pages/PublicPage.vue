<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { api } from "../api";
import { errorText, normalizeProfile, profile } from "../shared";
import ArchiveSheet, { type ArchiveTab } from "../components/ArchiveSheet.vue";
import Icon from "../components/Icon.vue";

const router = useRouter();
const omnibox = ref("");
const showPrompts = ref(false);
const sheetOpen = ref(false);
const sheetTab = ref<ArchiveTab>("projects");
const profileError = ref("");

async function reloadProfile() {
  profileError.value = "";
  try {
    profile.value = normalizeProfile(await api("/api/public/profile"));
  } catch (e) {
    profileError.value = errorText(e);
  }
}

function submitAsk() {
  const q = omnibox.value.trim();
  if (!q) {
    void router.push("/chat");
    return;
  }
  void router.push({ path: "/chat", query: { ask: q.slice(0, 2000) } });
}

function askChip(q: string) {
  void router.push({ path: "/chat", query: { ask: q.slice(0, 2000) } });
}

function openCategory(tab: ArchiveTab) {
  sheetTab.value = tab;
  sheetOpen.value = true;
}

onMounted(reloadProfile);
</script>

<template>
  <main class="hero-stage">
    <div class="status-pill">
      <span
        class="status-dot"
        :class="{ offline: profile.chatEnabled === false }"
      ></span>
      <span>{{ profile.chatEnabled === false ? "OFFLINE" : "ONLINE" }}</span>
    </div>

    <h1 class="hero-title">
      {{ profile.displayName || "DIGITAL TWIN" }}
    </h1>

    <p class="hero-sub">
      {{ profile.headline || "基于核验记忆的数字分身" }}
    </p>

    <p v-if="profileError" role="alert" class="error mb-5 max-w-md">
      {{ profileError }}
      <button type="button" class="underline ml-2" @click="reloadProfile">
        重试
      </button>
    </p>

    <form class="omnibox" @submit.prevent="submitAsk">
      <input
        v-model="omnibox"
        aria-label="快速提问"
        maxlength="2000"
        placeholder="输入问题，直接对话…"
      />
      <button type="submit" class="btn-primary !py-2 !px-5">
        <span>提问</span>
        <Icon name="arrow" :size="14" />
      </button>
    </form>

    <div class="dock-row" role="group" aria-label="分类功能底座">
      <button
        type="button"
        class="dock-pill"
        :class="{ active: showPrompts }"
        :aria-expanded="showPrompts"
        @click="showPrompts = !showPrompts"
      >
        <span>✦ 灵感</span>
        <span v-if="profile.quickQuestions.length" class="dock-count">{{
          profile.quickQuestions.length
        }}</span>
      </button>

      <button type="button" class="dock-pill" @click="openCategory('projects')">
        <span>◈ 项目</span>
        <span class="dock-count">{{ profile.projects.length }}</span>
      </button>

      <button
        type="button"
        class="dock-pill"
        @click="openCategory('experience')"
      >
        <span>◎ 履历</span>
        <span class="dock-count">{{
          profile.experiences.length + profile.skills.length
        }}</span>
      </button>

      <button type="button" class="dock-pill" @click="openCategory('connect')">
        <span>⌘ 节点与联络</span>
      </button>
    </div>

    <div v-if="showPrompts" class="prompt-tray">
      <button
        v-for="q in profile.quickQuestions"
        :key="q"
        type="button"
        class="prompt-chip"
        @click="askChip(q)"
      >
        {{ q }}
      </button>
      <span v-if="!profile.quickQuestions.length" class="mono-label py-2">
        暂无快捷提问
      </span>
    </div>

    <ArchiveSheet
      :open="sheetOpen"
      :tab="sheetTab"
      @update:tab="sheetTab = $event"
      @close="sheetOpen = false"
    />
  </main>
</template>
