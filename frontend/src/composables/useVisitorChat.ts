import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, ApiError } from "../api";
import { profile, normalizeProfile, notify, errorText } from "../shared";
import type { Message, VisitorSession, Answer } from "../types";

/** 会话恢复、唯一请求键与错误恢复；只自动查询结果，不自动重放付费写请求。 */
export function useVisitorChat() {
  const route = useRoute(),
    router = useRouter();
  const input = ref(""),
    messages = ref<Message[]>([]),
    session = ref<VisitorSession | null>(null);
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
  const submission = ref<{ question: string; key: string } | null>(null);
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
  /** 只恢复当前浏览器 Cookie 对应会话；401 表示无有效会话，网络故障则保留可重试提示。 */
  async function recover() {
    recovering.value = true;
    error.value = "";
    try {
      const value = await api<VisitorSession>("/api/public/session");
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
    session.value = await api<VisitorSession>("/api/public/session", "POST", {
      challengeToken: challengeToken.value || undefined,
    });
    challengeToken.value = "";
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
  /** 一次只提交一个问题；失败保留输入，等待用户决定是否重试，不自动重发可能已计费的问答。 */
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
      if (submission.value?.question !== question)
        submission.value = { question, key: crypto.randomUUID() };
      let answer: Answer;
      try {
        answer = await api<Answer>("/api/public/chat", "POST", {
          message: question,
          requestKey: submission.value.key,
          challengeToken: challengeToken.value || undefined,
        });
      } catch (error) {
        if (
          !(error instanceof ApiError) ||
          ![
            "NETWORK_ERROR",
            "INTERNAL_ERROR",
            "REQUEST_UNCERTAIN",
            "REQUEST_ALREADY_EXISTS",
          ].includes(error.code)
        )
          throw error;
        try {
          answer = await api<Answer>(
            "/api/public/requests/" + encodeURIComponent(submission.value.key),
          );
        } catch {
          throw error;
        }
      }
      submission.value = null;
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
      submission.value = null;
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
  /** 中文输入法确认候选词时也会触发 Enter，必须排除组合输入，避免误发送。 */
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
      api<{ siteKey: string }>("/api/public/challenge").then((value) => {
        siteKey.value = value.siteKey || "";
      }),
    ]);
  });
  watch(() => messages.value.length, scroll);

  return {
    input,
    messages,
    session,
    busy,
    recovering,
    pending,
    error,
    profileError,
    checking,
    showQuick,
    clearOpen,
    challengeNeeded,
    challengeKey,
    challengeToken,
    siteKey,
    composer,
    bottom,
    section,
    title,
    router,
    refreshProfile,
    recover,
    send,
    clear,
    chooseQuestion,
    closeDrawer,
    copy,
    keydown,
  };
}
