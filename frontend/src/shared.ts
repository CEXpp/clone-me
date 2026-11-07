/**
 * 页面共享状态和交互工具：公开资料、提示、互斥提交、未保存提醒和数据展示。
 * 公开资料先归一化再渲染，外链校验协议；后端仍需独立校验，前端检查只改善操作体验。
 */
import { onBeforeUnmount, onMounted, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { ApiError } from "./api";
import { authNavigation } from "./auth";
import { debugFlow } from "./debug";
export const toast = ref("");
let toastTimer: ReturnType<typeof setTimeout>;
export function notify(message: string) {
  toast.value = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (toast.value = ""), 4500);
}
export function errorText(error: unknown) {
  return error instanceof ApiError
    ? `${error.message}${error.requestId ? " · 请求编号 " + error.requestId : ""}`
    : error instanceof Error
      ? error.message
      : "操作失败，请稍后重试";
}
/** 把按钮忙碌态、异常提示和 finally 复位统一封装，防止用户连点导致重复提交。 */
export function useAction() {
  // 每次调用都创建本页面自己的 busy/error；ref.value 改变后，依赖它的模板自动更新。
  const busy = ref(false),
    error = ref("");
  async function run<T>(work: () => Promise<T>): Promise<T | undefined> {
    if (busy.value) {
      debugFlow("ACTION", "当前操作尚未结束，忽略重复点击");
      return;
    }
    debugFlow("ACTION", "进入页面操作，设置忙碌状态并清除上次错误");
    busy.value = true;
    error.value = "";
    try {
      // work 是调用页面传来的异步函数；await 等待保存及刷新流程真正结束。
      const result = await work();
      debugFlow("ACTION", "页面操作正常完成");
      return result;
    } catch (e) {
      error.value = errorText(e);
      debugFlow("ACTION", "页面操作失败，已设置错误提示");
    } finally {
      busy.value = false;
      debugFlow("ACTION", "解除忙碌状态，允许用户继续操作");
    }
  }
  return { busy, error, run };
}
/** 同时处理路由离开和浏览器关闭；认证过期及确认退出时允许跳转，避免困在无效管理页。 */
export function useUnsaved(isDirty: () => boolean) {
  onBeforeRouteLeave(
    () =>
      authNavigation.expired ||
      authNavigation.logoutConfirmed ||
      !isDirty() ||
      window.confirm("有尚未保存的修改，确定离开吗？"),
  );
  const prevent = (event: BeforeUnloadEvent) => {
    if (isDirty()) event.preventDefault();
  };
  onMounted(() => window.addEventListener("beforeunload", prevent));
  onBeforeUnmount(() => window.removeEventListener("beforeunload", prevent));
}
export function money(n: unknown) {
  // 接口数字单位是“微元”，1_000_000 微元才是 1 元；这里只格式化展示，不做账本计算。
  return "¥" + (Number(n || 0) / 1_000_000).toFixed(4);
}
export function date(n: unknown) {
  return n
    ? new Date(Number(n)).toLocaleString("zh-CN", { hour12: false })
    : "—";
}
/** 只允许明确的网页或邮件协议，防止把可执行脚本地址绑定到链接。 */
export function safeUrl(value: unknown) {
  if (typeof value !== "string") return undefined;
  try {
    const url = new URL(value);
    return ["https:", "http:", "mailto:"].includes(url.protocol)
      ? url.href
      : undefined;
  } catch {
    return undefined;
  }
}
export interface Link {
  label: string;
  url: string;
}
export interface Profile {
  revision?: number;
  chatEnabled?: boolean;
  configured?: boolean;
  displayName: string;
  headline: string;
  bio: string;
  skills: { name: string; level: string; summary: string }[];
  experiences: {
    title: string;
    organization: string;
    period: string;
    summary: string;
  }[];
  projects: {
    id: string;
    name: string;
    role: string;
    summary: string;
    stack: string[];
    links: Link[];
  }[];
  contacts: Link[];
  filings: Link[];
  quickQuestions: string[];
  aiDisclosure?: string;
}
export const profile = ref<Profile>({
  // 模块级 ref 在当前页面应用内共享；编辑表单另建副本，避免未保存草稿立刻影响展示区。
  displayName: "",
  headline: "",
  bio: "",
  skills: [],
  experiences: [],
  projects: [],
  contacts: [],
  filings: [],
  quickQuestions: [],
});
/** 对每层字段检查类型并提供空值，兼容未配置资料与旧数据，绝不填入虚构履历。 */
export function normalizeProfile(input: unknown): Profile {
  const raw: Record<string, unknown> =
    input && typeof input === "object"
      ? (input as Record<string, unknown>)
      : {};
  const text = (v: unknown) => (typeof v === "string" ? v : "");
  const rows = (v: unknown) =>
    Array.isArray(v)
      ? v.filter(
          (r): r is Record<string, unknown> => !!r && typeof r === "object",
        )
      : [];
  const links = (v: unknown) =>
    rows(v).map((r) => ({ label: text(r.label), url: text(r.url) }));
  return {
    revision: typeof raw.revision === "number" ? raw.revision : 0,
    chatEnabled: raw.chatEnabled !== false,
    configured: raw?.configured === true,
    displayName: text(raw?.displayName),
    headline: text(raw?.headline),
    bio: text(raw?.bio),
    aiDisclosure: text(raw?.aiDisclosure),
    skills: rows(raw?.skills).map((r) => ({
      name: text(r.name),
      level: text(r.level),
      summary: text(r.summary),
    })),
    experiences: rows(raw?.experiences).map((r) => ({
      title: text(r.title),
      organization: text(r.organization),
      period: text(r.period),
      summary: text(r.summary),
    })),
    projects: rows(raw?.projects).map((r) => ({
      id: text(r.id),
      name: text(r.name),
      role: text(r.role),
      summary: text(r.summary),
      stack: Array.isArray(r.stack)
        ? r.stack.filter((s: unknown) => typeof s === "string")
        : [],
      links: links(r.links),
    })),
    contacts: links(raw?.contacts),
    filings: links(raw?.filings),
    quickQuestions: Array.isArray(raw?.quickQuestions)
      ? raw.quickQuestions.filter((q: unknown) => typeof q === "string")
      : [],
  };
}
