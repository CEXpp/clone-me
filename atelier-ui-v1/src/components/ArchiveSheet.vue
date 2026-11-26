<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import { profile, safeUrl } from "../shared";
import Drawer from "./Drawer.vue";
import Icon from "./Icon.vue";
import VisitorWelcome from "./VisitorWelcome.vue";

export type ArchiveTab = "overview" | "projects" | "experience" | "connect";

const props = defineProps<{
  open: boolean;
  tab: ArchiveTab;
}>();
const emit = defineEmits<{
  close: [];
  "update:tab": [tab: ArchiveTab];
}>();

const router = useRouter();
const tabs: { key: ArchiveTab; label: string }[] = [
  { key: "projects", label: "项目" },
  { key: "experience", label: "经历" },
  { key: "overview", label: "简介" },
  { key: "connect", label: "节点与联络" },
];

const title = computed(
  () => tabs.find((t) => t.key === props.tab)?.label || "资料档案",
);

function ask(question: string) {
  emit("close");
  void router.push({ path: "/chat", query: { ask: question.slice(0, 2000) } });
}
</script>

<template>
  <Drawer :open="open" :title="title" wide @close="emit('close')">
    <nav class="flex flex-wrap gap-1.5" aria-label="档案分类">
      <button
        v-for="item in tabs"
        :key="item.key"
        type="button"
        class="dock-pill"
        :class="{ active: tab === item.key }"
        @click="emit('update:tab', item.key)"
      >
        {{ item.label }}
      </button>
    </nav>

    <!-- 项目（可折叠） -->
    <template v-if="tab === 'projects'">
      <div v-if="profile.projects.length" class="space-y-2.5">
        <details
          v-for="proj in profile.projects"
          :key="proj.id || proj.name"
          class="fold-card"
        >
          <summary>
            <div class="flex items-center gap-2.5 min-w-0">
              <span class="truncate">{{ proj.name }}</span>
              <span v-if="proj.role" class="tag">{{ proj.role }}</span>
            </div>
            <span class="fold-chevron">▸</span>
          </summary>
          <div class="fold-body space-y-3">
            <p v-if="proj.summary" class="muted prose-text">
              {{ proj.summary }}
            </p>
            <div v-if="proj.stack.length" class="flex flex-wrap gap-1.5">
              <span v-for="t in proj.stack" :key="t" class="tag">{{ t }}</span>
            </div>
            <div class="flex flex-wrap items-center justify-between gap-2 pt-1">
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
                    '请介绍「' + proj.name + '」中你个人负责的部分和技术取舍。',
                  )
                "
              >
                <span>追问此项目</span>
                <Icon name="arrow" :size="13" />
              </button>
            </div>
          </div>
        </details>
      </div>
      <p v-else class="empty">暂未发布项目</p>
    </template>

    <!-- 经历（可折叠） -->
    <template v-else-if="tab === 'experience'">
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
              @click="ask('请介绍你在' + exp.title + '这段经历中负责的工作。')"
            >
              <span>追问这段经历</span>
              <Icon name="arrow" :size="13" />
            </button>
          </div>
        </details>
      </div>
      <p v-else class="empty">暂未发布经历</p>
    </template>

    <!-- 简介与技能 -->
    <template v-else-if="tab === 'overview'">
      <div class="solid-panel p-4 space-y-2">
        <p class="font-semibold">{{ profile.displayName || "未配置署名" }}</p>
        <p v-if="profile.bio" class="muted prose-text">{{ profile.bio }}</p>
        <p v-else class="muted">个人简介尚未发布。</p>
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

    <!-- 联络与访客节点（全部收拢于此） -->
    <template v-else>
      <div v-if="profile.contacts.length" class="solid-panel p-3 space-y-1">
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
      <p v-else class="empty">暂未公开联系方式</p>

      <VisitorWelcome />

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
  </Drawer>
</template>
