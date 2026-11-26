/**
 * 全站共享状态与通用工具：公开资料、暗曜/钛银主题、空间动效开关、提示、动作锁与链接安全校验。
 */
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { ApiError } from "./api";
import { authNavigation } from "./auth";
import { debugFlow } from "./debug";
import type { Profile } from "./types";

export type PrismTheme = "obsidian" | "titanium";

function readStorage(key: string, fallback: string): string {
  try {
    return typeof localStorage !== "undefined"
      ? localStorage.getItem(key) || fallback
      : fallback;
  } catch {
    return fallback;
  }
}

function writeStorage(key: string, value: string) {
  try {
    if (typeof localStorage !== "undefined") localStorage.setItem(key, value);
  } catch {
    /* 无存储权限时保留内存状态。 */
  }
}

const initialTheme = readStorage("prism-theme", "obsidian");
export const theme = ref<PrismTheme>(
  initialTheme === "titanium" ? "titanium" : "obsidian",
);
export const motion = ref<boolean>(readStorage("prism-motion", "on") !== "off");

export function toggleTheme() {
  theme.value = theme.value === "obsidian" ? "titanium" : "obsidian";
}

watch(
  theme,
  (value) => {
    writeStorage("prism-theme", value);
    if (typeof document !== "undefined") {
      document.documentElement.dataset.theme = value;
    }
  },
  { immediate: true },
);

watch(motion, (value) => {
  writeStorage("prism-motion", value ? "on" : "off");
});

export const toast = ref("");
let toastTimer: ReturnType<typeof setTimeout> | undefined;

export function notify(message: string) {
  toast.value = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toast.value = "";
  }, 4000);
}

export function errorText(error: unknown) {
  return error instanceof ApiError
    ? `${error.message}${error.requestId ? " · 请求编号 " + error.requestId : ""}`
    : error instanceof Error
      ? error.message
      : "操作失败，请稍后重试";
}

export function useAction() {
  const busy = ref(false),
    error = ref("");
  async function run<T>(work: () => Promise<T>): Promise<T | undefined> {
    if (busy.value) {
      debugFlow("ACTION", "当前操作尚未结束，忽略重复点击");
      return;
    }
    busy.value = true;
    error.value = "";
    try {
      return await work();
    } catch (e) {
      error.value = errorText(e);
    } finally {
      busy.value = false;
    }
  }
  return { busy, error, run };
}

export function useUnsaved(isDirty: () => boolean) {
  onBeforeRouteLeave(
    () =>
      authNavigation.expired ||
      authNavigation.logoutConfirmed ||
      !isDirty() ||
      window.confirm("有尚未保存的修改，确定离开吗？"),
  );
  const prevent = (event: BeforeUnloadEvent) => {
    if (isDirty()) {
      event.preventDefault();
      event.returnValue = "";
    }
  };
  onMounted(() => window.addEventListener("beforeunload", prevent));
  onBeforeUnmount(() => window.removeEventListener("beforeunload", prevent));
}

export function money(n: unknown) {
  return "¥" + (Number(n || 0) / 1_000_000).toFixed(4);
}

export function date(n: unknown) {
  return n
    ? new Date(Number(n)).toLocaleString("zh-CN", { hour12: false })
    : "—";
}

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

export const profile = ref<Profile>({
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
    configured: raw.configured === true,
    displayName: text(raw.displayName),
    headline: text(raw.headline),
    bio: text(raw.bio),
    aiDisclosure: text(raw.aiDisclosure),
    skills: rows(raw.skills).map((r) => ({
      name: text(r.name),
      level: text(r.level),
      summary: text(r.summary),
    })),
    experiences: rows(raw.experiences).map((r) => ({
      title: text(r.title),
      organization: text(r.organization),
      period: text(r.period),
      summary: text(r.summary),
    })),
    projects: rows(raw.projects).map((r) => ({
      id: text(r.id),
      name: text(r.name),
      role: text(r.role),
      summary: text(r.summary),
      stack: Array.isArray(r.stack)
        ? r.stack.filter((s: unknown) => typeof s === "string")
        : [],
      links: links(r.links),
    })),
    contacts: links(raw.contacts),
    filings: links(raw.filings),
    quickQuestions: Array.isArray(raw.quickQuestions)
      ? raw.quickQuestions.filter((q: unknown) => typeof q === "string")
      : [],
  };
}
