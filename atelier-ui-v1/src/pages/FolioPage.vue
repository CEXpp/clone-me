<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api";
import { errorText, normalizeProfile, profile, safeUrl } from "../shared";
import Icon from "../components/Icon.vue";

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const error = ref("");

const tabs = [
  { key: "overview", label: "简介" },
  { key: "projects", label: "项目" },
  { key: "experience", label: "经历" },
  { key: "contact", label: "联络" },
];

const section = computed(() => String(route.params.section || "projects"));

async function load() {
  loading.value = true;
  error.value = "";
  try {
    profile.value = normalizeProfile(await api("/api/public/profile"));
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

function ask(q: string) {
  void router.push({ path: "/chat", query: { ask: q.slice(0, 2000) } });
}

onMounted(load);
</script>

<template>
  <main class="spatial-page">
    <div class="glass-panel p-5 sm:p-7 space-y-5">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="flex flex-wrap items-center gap-2">
          <RouterLink
            to="/"
            class="btn-ghost !py-1.5 !px-3 text-xs"
            aria-label="返回首页"
          >
            <Icon name="back" :size="14" />
            <span>首页</span>
          </RouterLink>
          <div
            class="flex flex-wrap gap-1.5"
            role="tablist"
            aria-label="档案分类"
          >
            <RouterLink
              v-for="tab in tabs"
              :key="tab.key"
              :to="'/folio/' + tab.key"
              class="dock-pill no-underline"
              :class="{ active: section === tab.key }"
            >
              {{ tab.label }}
            </RouterLink>
          </div>
        </div>

        <RouterLink to="/chat" class="btn-primary !py-1.5 !px-4 text-xs">
          <span>进入对话</span>
          <Icon name="arrow" :size="13" />
        </RouterLink>
      </div>

      <p v-if="error" role="alert" class="error">
        {{ error }}
        <button type="button" class="underline ml-2" @click="load">重试</button>
      </p>
      <p v-else-if="loading" role="status" class="muted">加载中…</p>

      <template v-else-if="section === 'overview'">
        <div class="solid-panel p-4 space-y-2">
          <h1 class="text-lg font-semibold">
            {{ profile.displayName || "未配置署名" }}
          </h1>
          <p v-if="profile.headline" class="muted">{{ profile.headline }}</p>
          <p v-if="profile.bio" class="muted prose-text">{{ profile.bio }}</p>
        </div>

        <div v-if="profile.skills.length" class="space-y-2">
          <details
            v-for="skill in profile.skills"
            :key="skill.name"
            class="fold-card"
          >
            <summary>
              <div class="flex items-center gap-2">
                <span>{{ skill.name }}</span>
                <span v-if="skill.level" class="tag">{{ skill.level }}</span>
              </div>
              <span class="fold-chevron">▸</span>
            </summary>
            <div v-if="skill.summary" class="fold-body muted prose-text">
              {{ skill.summary }}
            </div>
          </details>
        </div>
      </template>

      <template v-else-if="section === 'projects'">
        <div v-if="profile.projects.length" class="space-y-2.5">
          <details
            v-for="proj in profile.projects"
            :key="proj.id || proj.name"
            class="fold-card"
            open
          >
            <summary>
              <div class="flex items-center gap-2.5 min-w-0">
                <h2 class="text-sm font-semibold truncate">{{ proj.name }}</h2>
                <span v-if="proj.role" class="tag">{{ proj.role }}</span>
              </div>
              <span class="fold-chevron">▸</span>
            </summary>
            <div class="fold-body space-y-3">
              <p v-if="proj.summary" class="muted prose-text">
                {{ proj.summary }}
              </p>
              <div v-if="proj.stack.length" class="flex flex-wrap gap-1.5">
                <span v-for="t in proj.stack" :key="t" class="tag">{{
                  t
                }}</span>
              </div>
              <div class="flex flex-wrap items-center justify-between gap-2">
                <div class="flex flex-wrap gap-3 text-xs">
                  <template v-for="link in proj.links" :key="link.url">
                    <a
                      v-if="safeUrl(link.url)"
                      :href="safeUrl(link.url)"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="underline underline-offset-4 text-[var(--accent)]"
                    >
                      {{ link.label }} ↗
                    </a>
                  </template>
                </div>
                <button
                  type="button"
                  class="btn"
                  @click="
                    ask(
                      '请介绍「' +
                        proj.name +
                        '」中你个人负责的部分和技术取舍。',
                    )
                  "
                >
                  <span>追问</span>
                  <Icon name="arrow" :size="13" />
                </button>
              </div>
            </div>
          </details>
        </div>
        <p v-else class="empty">暂无公开项目</p>
      </template>

      <template v-else-if="section === 'experience'">
        <div v-if="profile.experiences.length" class="space-y-2.5">
          <details
            v-for="exp in profile.experiences"
            :key="exp.title + exp.period"
            class="fold-card"
          >
            <summary>
              <div class="flex items-center gap-2.5 min-w-0">
                <span class="truncate">{{ exp.title }}</span>
                <span v-if="exp.period" class="tag">{{ exp.period }}</span>
              </div>
              <span class="fold-chevron">▸</span>
            </summary>
            <div class="fold-body space-y-2.5">
              <p v-if="exp.organization" class="mono-label">
                {{ exp.organization }}
              </p>
              <p v-if="exp.summary" class="muted prose-text">
                {{ exp.summary }}
              </p>
              <button
                type="button"
                class="btn"
                @click="
                  ask('请介绍你在' + exp.title + '这段经历中负责的工作。')
                "
              >
                <span>追问</span>
                <Icon name="arrow" :size="13" />
              </button>
            </div>
          </details>
        </div>
        <p v-else class="empty">暂无公开履历</p>
      </template>

      <template v-else>
        <div v-if="profile.contacts.length" class="solid-panel p-2 space-y-1">
          <template v-for="c in profile.contacts" :key="c.url">
            <a
              v-if="safeUrl(c.url)"
              :href="safeUrl(c.url)"
              target="_blank"
              rel="noopener noreferrer"
              class="flex items-center justify-between px-3 py-2.5 rounded-lg hover:bg-[var(--bg-hover)] text-sm"
            >
              <span>{{ c.label }}</span>
              <span class="mono-label">↗</span>
            </a>
          </template>
        </div>
        <p v-else class="empty">暂无联系方式</p>

        <div
          v-if="profile.filings.length"
          class="flex flex-wrap gap-3 pt-1 text-xs text-[var(--text-dim)]"
        >
          <template v-for="f in profile.filings" :key="f.label">
            <a
              v-if="safeUrl(f.url)?.startsWith('https://')"
              :href="safeUrl(f.url)"
              target="_blank"
              rel="noopener noreferrer"
              class="underline underline-offset-4"
            >
              {{ f.label }} ↗
            </a>
          </template>
        </div>
      </template>

      <div class="pt-3 border-t border-[var(--border-hair)] flex justify-end">
        <span class="mono-label">AI 分身 · 仅基于确认资料</span>
      </div>
    </div>
  </main>
</template>
