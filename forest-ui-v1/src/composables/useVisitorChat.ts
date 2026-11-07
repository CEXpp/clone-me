import { debugFlow } from "../debug";
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, ApiError } from "../api";
import { profile, normalizeProfile, notify, errorText } from "../shared";
import type { Message, VisitorSession, Answer } from "../types";

/** 会话恢复、唯一请求键与错误恢复；只自动查询结果，不自动重放付费写请求。 */
export function useVisitorChat() {
  // composable 是把一组响应式状态和操作封装成函数；PublicPage 取返回值负责渲染。
  // ref 在脚本中用 .value，Vue 模板会自动解包，因此模板里通常直接写 input、busy。
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
  // submission 保存同一问题的请求键；requestId 则由后端为每次 HTTP 请求新建。
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
    debugFlow("CHAT", "尝试恢复当前浏览器的访客会话");
    recovering.value = true;
    error.value = "";
    try {
      const value = await api<VisitorSession>("/api/public/session");
      session.value = value;
      messages.value = Array.isArray(value.messages) ? value.messages : [];
      debugFlow("CHAT", "会话恢复成功", {
        messageCount: messages.value.length,
      });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        debugFlow("CHAT", "没有有效访客会话，首次提问时再创建");
        session.value = null;
        messages.value = [];
      } else error.value = errorText(e);
    } finally {
      recovering.value = false;
      checking.value = false;
    }
  }
  async function begin() {
    // 首次发送前才创建访客会话；凭证由 Set-Cookie 保存，前端只拿到非秘密的会话信息。
    if (session.value) return;
    debugFlow("CHAT", "首次提问，开始创建访客会话");
    session.value = await api<VisitorSession>("/api/public/session", "POST", {
      challengeToken: challengeToken.value || undefined,
    });
    challengeToken.value = "";
  }
  async function scroll() {
    // nextTick 等待 Vue 把状态变化更新到 DOM，之后滚动才会对准刚出现的新消息。
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
    debugFlow("CHAT", "问题通过本地检查，进入等待状态；不打印问题内容");
    busy.value = true;
    pending.value = question;
    error.value = "";
    await scroll();
    try {
      await begin();
      if (submission.value?.question !== question)
        submission.value = { question, key: crypto.randomUUID() };
      // 未确认结果前保留这个键。用户主动再提交相同问题时，后端可返回已完成结果。
      let answer: Answer;
      try {
        debugFlow("CHAT", "发送问答请求，等待后端完成生成和核验");
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
          debugFlow("CHAT", "提交结果不确定，发起一次只读结果查询");
          answer = await api<Answer>(
            // 写请求异常后这里只做一次只读查询；没有循环重发 POST，也没有定时轮询结果。
            "/api/public/requests/" + encodeURIComponent(submission.value.key),
          );
        } catch {
          throw error;
        }
      }
      debugFlow("CHAT", "已取得最终结果，准备插入消息并更新界面", {
        requestId: answer.requestId,
        status: answer.status,
        sourceCount: answer.sources.length,
      });
      submission.value = null;
      messages.value.push(
        // 后端返回最终核验结果后再成对插入消息，pending 只负责等待期间的临时问题显示。
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
      debugFlow("CHAT", "本次发送流程结束，解除等待状态并清理临时验证码");
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
      debugFlow("CHAT", "开始清除当前浏览器会话");
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
    await router.replace({ path: "/chat", query: {} });
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
    // 三个读取互不依赖；allSettled 允许某项失败后其他项仍完成，各自处理自己的提示。
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
