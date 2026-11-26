import { nextTick, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, ApiError } from "../api";
import { debugFlow } from "../debug";
import { profile, normalizeProfile, notify, errorText } from "../shared";
import type { Message, VisitorSession, Answer } from "../types";

/**
 * 访客对话状态管理：支持 ?ask= 直发、?q= 预填、会话恢复、唯一请求键与无重复扣费回退查询。
 */
export function useVisitorChat() {
  const route = useRoute();
  const router = useRouter();
  const input = ref(""),
    messages = ref<Message[]>([]),
    session = ref<VisitorSession | null>(null);
  const busy = ref(false),
    recovering = ref(false),
    pending = ref(""),
    error = ref(""),
    profileError = ref(""),
    checking = ref(true),
    clearOpen = ref(false),
    challengeNeeded = ref(false),
    challengeKey = ref(0),
    challengeToken = ref(""),
    siteKey = ref("");
  const submission = ref<{ question: string; key: string } | null>(null);
  const composer = ref<HTMLTextAreaElement>(),
    bottom = ref<HTMLElement>();

  async function refreshProfile() {
    profileError.value = "";
    try {
      profile.value = normalizeProfile(await api("/api/public/profile"));
    } catch (e) {
      profileError.value = errorText(e);
    }
  }

  async function recover() {
    debugFlow("CHAT", "尝试恢复访客会话");
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
      } else {
        error.value = errorText(e);
      }
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
      if (submission.value?.question !== question) {
        submission.value = { question, key: crypto.randomUUID() };
      }
      let answer: Answer;
      try {
        answer = await api<Answer>("/api/public/chat", "POST", {
          message: question,
          requestKey: submission.value.key,
          challengeToken: challengeToken.value || undefined,
        });
      } catch (err) {
        if (
          !(err instanceof ApiError) ||
          ![
            "NETWORK_ERROR",
            "INTERNAL_ERROR",
            "REQUEST_UNCERTAIN",
            "REQUEST_ALREADY_EXISTS",
          ].includes(err.code)
        ) {
          throw err;
        }
        try {
          answer = await api<Answer>(
            "/api/public/requests/" + encodeURIComponent(submission.value.key),
          );
        } catch {
          throw err;
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
        ) {
          session.value = null;
        }
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
      notify("对话已重置");
    } catch (e) {
      error.value = errorText(e);
    }
  }

  async function chooseQuestion(question: string) {
    input.value = question.slice(0, 2000);
    await nextTick();
    composer.value?.focus();
  }

  async function copy(value: string) {
    try {
      await navigator.clipboard.writeText(value);
      notify("已复制");
    } catch {
      notify("复制不可用");
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

  async function consumeAskParam() {
    if (typeof route.query.ask === "string" && route.query.ask.trim()) {
      const q = route.query.ask.trim().slice(0, 2000);
      input.value = q;
      await router.replace({ path: "/chat", query: {} });
      await send();
    } else if (typeof route.query.q === "string" && route.query.q.trim()) {
      input.value = route.query.q.slice(0, 2000);
      await nextTick();
      composer.value?.focus();
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
    await consumeAskParam();
  });

  watch(
    () => [route.query.ask, route.query.q],
    async () => {
      if (!checking.value && !busy.value) {
        await consumeAskParam();
      }
    },
  );

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
    clearOpen,
    challengeNeeded,
    challengeKey,
    challengeToken,
    siteKey,
    composer,
    bottom,
    refreshProfile,
    recover,
    send,
    clear,
    chooseQuestion,
    copy,
    keydown,
  };
}
