<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, ApiError } from "../api";
import {
  profile,
  normalizeProfile,
  safeUrl,
  notify,
  errorText,
  date,
} from "../shared";
import Drawer from "../components/Drawer.vue";
import Challenge from "../components/Challenge.vue";
interface Message {
  role: string;
  content: string;
  status?: string;
  sources?: { id: string; title: string; location: string }[];
  requestId?: string;
  created_at?: number;
}
const route = useRoute(),
  router = useRouter();
const input = ref(""),
  messages = ref<Message[]>([]),
  session = ref<{ sessionId: string; expiresAt: number } | null>(null);
const busy = ref(false),
  recovering = ref(false),
  pending = ref(""),
  error = ref(""),
  profileError = ref(""),
  checking = ref(true),
  showQuick = ref(true),
  clearOpen = ref(false),
  challengeNeeded = ref(false),
  challengeKey = ref(0),
  challengeToken = ref(""),
  siteKey = ref("");
const composer = ref<HTMLTextAreaElement>(),
  bottom = ref<HTMLElement>();
const section = computed(() =>
  typeof route.query.section === "string" &&
  ["overview", "experience", "projects", "contact"].includes(
    route.query.section,
  )
    ? route.query.section
    : "",
);
const drawerTitles: Record<string, string> = {
  overview: "简介与能力范围",
  experience: "工作与学习经历",
  projects: "项目与个人贡献",
  contact: "公开联系方式",
};
const title = computed(() => drawerTitles[section.value] || "");
async function refreshProfile() {
  profileError.value = "";
  try {
    profile.value = normalizeProfile(await api("/api/public/profile"));
  } catch (e) {
    profileError.value = errorText(e);
  }
}
async function recover() {
  recovering.value = true;
  error.value = "";
  try {
    const value = await api("/api/public/session");
    session.value = value;
    messages.value = Array.isArray(value.messages) ? value.messages : [];
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      session.value = null;
      messages.value = [];
    } else error.value = errorText(e);
  } finally {
    recovering.value = false;
    checking.value = false;
  }
}
async function begin() {
  if (session.value) return;
  session.value = await api("/api/public/session", "POST", {
    challengeToken: challengeToken.value || undefined,
  });
}
async function scroll() {
  await nextTick();
  bottom.value?.scrollIntoView({
    block: "nearest",
    behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches
      ? "instant"
      : "smooth",
  });
}
async function send() {
  const question = input.value.trim();
  if (!question || question.length > 2000 || busy.value || checking.value)
    return;
  busy.value = true;
  pending.value = question;
  error.value = "";
  await scroll();
  try {
    await begin();
    const answer = await api("/api/public/chat", "POST", {
      message: question,
      challengeToken: challengeToken.value || undefined,
    });
    messages.value.push(
      { role: "user", content: question },
      {
        role: "assistant",
        content: answer.answer,
        status: answer.status,
        sources: answer.sources,
        requestId: answer.requestId,
      },
    );
    if (session.value) session.value.expiresAt = answer.expiresAt;
    if (input.value.trim() === question) input.value = "";
    challengeNeeded.value = false;
  } catch (e) {
    error.value = errorText(e);
    if (e instanceof ApiError) {
      if (e.code.startsWith("CHALLENGE_")) challengeNeeded.value = true;
      if (
        e.code === "VISITOR_SESSION_EXPIRED" ||
        e.code === "VISITOR_SESSION_REQUIRED"
      )
        session.value = null;
    }
  } finally {
    busy.value = false;
    pending.value = "";
    challengeToken.value = "";
    challengeKey.value++;
    await scroll();
  }
}
async function clear() {
  if (busy.value) return;
  try {
    await api("/api/public/session", "DELETE");
    session.value = null;
    messages.value = [];
    clearOpen.value = false;
    error.value = "";
    notify("当前浏览器的聊天记录已清除");
  } catch (e) {
    error.value = errorText(e);
  }
}
async function chooseQuestion(question: string) {
  await router.replace({ query: {} });
  input.value = question.slice(0, 2000);
  await nextTick();
  composer.value?.focus();
}
async function closeDrawer() {
  await router.replace({ query: {} });
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    notify("已复制");
  } catch {
    notify("复制不可用，请手动选择文本复制");
  }
}
function keydown(event: KeyboardEvent) {
  if (
    event.key === "Enter" &&
    !event.shiftKey &&
    !event.isComposing &&
    event.keyCode !== 229
  ) {
    event.preventDefault();
    void send();
  }
}
onMounted(async () => {
  await Promise.allSettled([
    refreshProfile(),
    recover(),
    api("/api/public/challenge").then((value) => {
      siteKey.value = value.siteKey || "";
    }),
  ]);
});
watch(() => messages.value.length, scroll);
</script>
<template>
  <div
    class="pointer-events-none fixed inset-0 overflow-hidden opacity-60"
    aria-hidden="true"
  >
    <svg class="h-full w-full" viewBox="0 0 1440 900" fill="none">
      <circle
        cx="720"
        cy="420"
        r="310"
        stroke="#E2E8F0"
        stroke-dasharray="3 6"
      />
      <circle cx="720" cy="420" r="190" stroke="#F1F5F9" />
      <path d="M720 0V900M0 420H1440" stroke="#F1F5F9" />
    </svg>
  </div>
  <main
    class="public-main relative flex flex-col justify-between max-w-3xl w-full mx-auto px-4 sm:px-6"
  >
    <div
      v-if="session"
      class="flex flex-wrap items-center justify-between gap-2 py-4 border-b border-slate-200/70 text-[11px] text-slate-500"
    >
      <span title="同一浏览器可继续上次交流"
        >● 会话保留至 {{ date(session.expiresAt) }}</span
      ><button
        class="underline underline-offset-4"
        :disabled="busy"
        @click="clearOpen = true"
      >
        新聊天 / 清除记录
      </button>
    </div>
    <section
      v-if="!messages.length && !pending"
      class="my-auto py-12 sm:py-16 text-center flex flex-col items-center"
    >
      <p
        class="inline-flex items-center gap-2 rounded-full bg-slate-100/90 border border-slate-200/80 px-3 py-1 text-[10px] sm:text-[11px] text-slate-500 mb-6"
      >
        <span class="font-mono">PERSONAL EDITION · 01</span
        ><span class="text-slate-300">/</span>本人确认资料驱动
      </p>
      <h1 class="text-3xl sm:text-5xl font-light tracking-tight leading-[1.25]">
        你好，<template v-if="profile.displayName"
          >我是
          <strong class="font-semibold">{{ profile.displayName }}</strong>
          的文字分身。</template
        ><template v-else>欢迎来了解我。</template>
      </h1>
      <p
        class="mt-4 text-sm sm:text-base text-slate-500 max-w-xl leading-relaxed"
      >
        {{
          profile.headline ||
          "个人介绍正在准备中。你可以浏览已发布资料，或留下关于经历、项目与技术取舍的问题。"
        }}
      </p>
      <p v-if="profile.bio" class="muted max-w-xl mt-3">{{ profile.bio }}</p>
      <p v-if="profileError" role="alert" class="error mt-4">
        {{ profileError }}
        <button class="underline" @click="refreshProfile">重新加载介绍</button>
      </p>
      <div class="mt-6 flex flex-wrap justify-center gap-2">
        <button
          class="btn"
          @click="router.replace({ query: { section: 'projects' } })"
        >
          <span class="eyebrow">03</span>浏览项目
          <span v-if="profile.projects.length"
            >· {{ profile.projects.length }}</span
          >
          →</button
        ><button
          class="btn"
          @click="router.replace({ query: { section: 'experience' } })"
        >
          <span class="eyebrow">02</span>查看经历</button
        ><button
          class="btn"
          @click="router.replace({ query: { section: 'overview' } })"
        >
          <span class="eyebrow">01</span>技术栈与能力边界
        </button>
      </div>
    </section>
    <section
      v-if="messages.length || pending"
      aria-label="聊天记录"
      aria-live="polite"
      class="flex-1 py-7 space-y-7"
    >
      <article
        v-for="(message, index) in messages"
        :key="index"
        :class="
          message.role === 'user'
            ? 'ml-auto max-w-[90%] rounded-2xl bg-slate-100 px-4 py-3'
            : 'border-l-2 border-zinc-900 pl-5 py-1'
        "
      >
        <p class="eyebrow mb-2">
          {{ message.role === "user" ? "YOU" : "AI · 本人资料分身" }}
        </p>
        <div class="prose-text text-sm sm:text-base">{{ message.content }}</div>
        <div v-if="message.role === 'assistant'" class="mt-3 space-y-3">
          <p
            v-if="message.status && message.status !== 'ANSWERED'"
            class="muted"
          >
            依据尚不足以展开，这部分先保留。
          </p>
          <details
            v-if="message.sources?.length"
            class="text-xs text-slate-500"
          >
            <summary class="cursor-pointer">
              查看依据 · {{ message.sources.length }} 处
            </summary>
            <ul class="mt-2 space-y-2">
              <li v-for="source in message.sources" :key="source.id">
                {{ source.title }}
                <span class="text-slate-400">/ {{ source.location }}</span>
              </li>
            </ul>
          </details>
          <div class="flex flex-wrap gap-4 text-[11px] text-slate-400">
            <button @click="copy(message.content)">复制回答</button
            ><button v-if="message.requestId" @click="copy(message.requestId)">
              复制请求编号
            </button>
          </div>
        </div>
      </article>
      <div v-if="pending" class="space-y-5">
        <div
          class="ml-auto max-w-[90%] rounded-2xl bg-slate-100 p-4 prose-text text-sm"
        >
          {{ pending }}
        </div>
        <p role="status" class="text-xs text-slate-500 pl-5">● 正在准备回复…</p>
      </div>
      <div ref="bottom"></div>
    </section>
    <section
      class="sticky bottom-0 z-20 pt-3 safe-bottom bg-gradient-to-t from-white via-white/95 to-transparent"
    >
      <div v-if="error" role="alert" class="error mb-3">
        <p>{{ error }}</p>
        <button
          class="underline mt-2"
          :disabled="busy || recovering"
          @click="recover"
        >
          先恢复聊天记录</button
        ><span class="ml-3 text-[11px]"
          >确认记录后，再主动发送；不会自动重复提问。</span
        >
      </div>
      <Challenge
        v-if="challengeNeeded && siteKey"
        :key="challengeKey"
        :site-key="siteKey"
        class="mb-3"
        @token="(value) => (challengeToken = value)"
      />
      <form class="composer rounded-3xl p-3 sm:p-4" @submit.prevent="send">
        <label class="sr-only" for="chat-input">向文字分身提问</label
        ><textarea
          id="chat-input"
          ref="composer"
          v-model="input"
          rows="2"
          maxlength="2000"
          :disabled="checking"
          class="w-full bg-transparent resize-none border-0 focus:outline-none focus-visible:outline-none text-sm sm:text-base leading-relaxed placeholder-slate-400"
          placeholder="直接问我关于项目、技术取舍或经历…"
          @keydown="keydown"
        ></textarea>
        <div
          class="flex justify-between items-center gap-2 pt-3 mt-1 border-t border-slate-200/60"
        >
          <div class="flex gap-3 items-center">
            <button
              type="button"
              class="text-xs text-slate-500"
              :aria-expanded="showQuick"
              @click="showQuick = !showQuick"
            >
              ✦ 快捷追问 {{ showQuick ? "▴" : "▾" }}</button
            ><span class="hidden sm:inline eyebrow !tracking-normal"
              >Enter 发送 · Shift+Enter 换行</span
            >
          </div>
          <div class="flex gap-3 items-center">
            <span class="eyebrow !tracking-normal"
              >{{ input.length }} / 2000</span
            ><button
              class="btn-primary"
              :disabled="
                busy ||
                checking ||
                !input.trim() ||
                (challengeNeeded && !challengeToken)
              "
            >
              {{ checking ? "恢复中" : busy ? "准备中" : "发送 ↑" }}
            </button>
          </div>
        </div>
      </form>
      <div
        v-if="showQuick && profile.quickQuestions.length"
        class="mt-3 flex flex-wrap justify-center gap-2"
      >
        <button
          v-for="question in profile.quickQuestions"
          :key="question"
          class="rounded-full px-3 py-1.5 bg-slate-100/90 text-[11px] text-slate-500 hover:text-zinc-950"
          @click="chooseQuestion(question)"
        >
          {{ question }}
        </button>
      </div>
      <footer
        class="mt-4 text-center flex flex-wrap justify-center gap-x-3 gap-y-1 text-[10px] sm:text-[11px] text-slate-400"
      >
        <span>AI 文字分身 · 回答依据为本人确认资料</span
        ><span>同浏览器保留 5 天 · 不跨设备识别</span>
        <template v-for="filing in profile.filings" :key="filing.label"
          ><a
            v-if="safeUrl(filing.url)?.startsWith('https://')"
            :href="safeUrl(filing.url)"
            target="_blank"
            rel="noopener noreferrer"
            >{{ filing.label }} ↗</a
          ></template
        >
      </footer>
    </section>
  </main>
  <Drawer :open="!!section" :title="title" @close="closeDrawer">
    <nav aria-label="切换资料类别" class="flex flex-wrap gap-2">
      <button
        v-for="(label, key) in {
          overview: '简介',
          experience: '经历',
          projects: '项目',
          contact: '联系',
        }"
        :key="key"
        class="btn !py-1.5"
        :class="section === key ? '!bg-zinc-950 !text-white' : ''"
        @click="router.replace({ query: { section: key } })"
      >
        {{ label }}
      </button>
    </nav>
    <template v-if="section === 'overview'"
      ><p class="eyebrow">01 · ABOUT & CAPABILITIES</p>
      <h3 class="text-2xl font-semibold">
        {{ profile.displayName || "个人介绍正在准备中" }}
      </h3>
      <p class="prose-text text-sm text-slate-600">
        {{ profile.bio || "本人尚未发布介绍。" }}
      </p>
      <article
        v-for="skill in profile.skills"
        :key="skill.name"
        class="panel !p-4 space-y-2"
      >
        <div class="flex justify-between gap-3">
          <h4 class="text-sm font-medium">{{ skill.name }}</h4>
          <span class="tag">{{ skill.level }}</span>
        </div>
        <p class="muted">{{ skill.summary }}</p>
      </article></template
    >
    <template v-if="section === 'experience'"
      ><article
        v-for="experience in profile.experiences"
        :key="experience.title + experience.period"
        class="border-l border-slate-200 pl-5 space-y-2"
      >
        <p class="eyebrow">{{ experience.period }}</p>
        <h3 class="section-title">{{ experience.title }}</h3>
        <p class="muted">{{ experience.organization }}</p>
        <p class="prose-text text-sm text-slate-600">
          {{ experience.summary }}
        </p>
        <button
          class="btn"
          @click="
            chooseQuestion(
              '请介绍你在' + experience.title + '这段经历中亲自完成的工作。',
            )
          "
        >
          问问这段经历 →
        </button>
      </article>
      <p v-if="!profile.experiences.length" class="empty">
        经历资料尚未发布。
      </p></template
    >
    <template v-if="section === 'projects'"
      ><article
        v-for="project in profile.projects"
        :key="project.id || project.name"
        class="panel !p-5 space-y-3"
      >
        <p class="eyebrow">{{ project.role }}</p>
        <h3 class="section-title">{{ project.name }}</h3>
        <p class="prose-text text-sm text-slate-600">{{ project.summary }}</p>
        <div class="flex flex-wrap gap-1">
          <span v-for="tag in project.stack" :key="tag" class="tag">{{
            tag
          }}</span>
        </div>
        <div class="flex flex-wrap gap-3">
          <template v-for="link in project.links" :key="link.url"
            ><a
              v-if="safeUrl(link.url)"
              :href="safeUrl(link.url)"
              target="_blank"
              rel="noopener noreferrer"
              class="text-xs underline underline-offset-4"
              >{{ link.label }} ↗</a
            ></template
          >
        </div>
        <button
          class="btn"
          @click="
            chooseQuestion(
              '请介绍「' + project.name + '」中你个人负责的部分和技术取舍。',
            )
          "
        >
          问问这个项目 →
        </button>
      </article>
      <p v-if="!profile.projects.length" class="empty">
        项目资料尚未发布。
      </p></template
    >
    <template v-if="section === 'contact'"
      ><p class="muted">以下为本人选择公开的联系方式。</p>
      <template v-for="link in profile.contacts" :key="link.url"
        ><a
          v-if="safeUrl(link.url)"
          :href="safeUrl(link.url)"
          target="_blank"
          rel="noopener noreferrer"
          class="flex justify-between items-center p-4 border-b border-slate-200 text-sm"
          >{{ link.label }} <span>↗</span></a
        ></template
      >
      <p v-if="!profile.contacts.length" class="empty">
        暂未提供公开联系方式。
      </p></template
    >
    <p class="muted pt-4 border-t border-slate-200">
      这是 AI 分身，当前内容由本人确认提供。
    </p>
  </Drawer>
  <Drawer
    :open="clearOpen"
    title="清除当前聊天记录？"
    center
    @close="clearOpen = false"
    ><p class="text-sm text-slate-600 leading-relaxed">
      会清除当前浏览器这段聊天，之后的提问会开始新会话。
    </p>
    <div class="flex justify-end gap-2">
      <button class="btn" @click="clearOpen = false">取消</button
      ><button class="btn-primary" :disabled="busy" @click="clear">
        确认清除
      </button>
    </div></Drawer
  >
</template>
