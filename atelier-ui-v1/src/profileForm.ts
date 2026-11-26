/**
 * 公开名片编辑表单的纯校验与序列化预处理工具，严格对齐后端 ProfileService.Edit 的契约约束。
 */
import type { Profile, ProfileEdit } from "./types";

function checkUrl(value: string, httpsOnly: boolean): boolean {
  try {
    const uri = new URL(value);
    const scheme = uri.protocol.replace(":", "").toLowerCase();
    if (httpsOnly) {
      return scheme === "https" && !!uri.host && !uri.username && !uri.password;
    }
    if (!["http", "https", "mailto"].includes(scheme)) return false;
    if (scheme === "http" || scheme === "https") {
      return !!uri.host && !uri.username && !uri.password;
    }
    return value.length > "mailto:".length;
  } catch {
    return false;
  }
}

export function profileToEdit(profile: Profile): ProfileEdit {
  return {
    displayName: profile.displayName || "",
    headline: profile.headline || "",
    bio: profile.bio || "",
    skills: (profile.skills || []).map((s) => ({
      name: s.name || "",
      level: s.level || "",
      summary: s.summary || "",
    })),
    experiences: (profile.experiences || []).map((e) => ({
      title: e.title || "",
      organization: e.organization || "",
      period: e.period || "",
      summary: e.summary || "",
    })),
    projects: (profile.projects || []).map((p) => ({
      id: p.id || "",
      name: p.name || "",
      role: p.role || "",
      summary: p.summary || "",
      stack: [...(p.stack || [])],
      links: (p.links || []).map((l) => ({
        label: l.label || "",
        url: l.url || "",
      })),
    })),
    contacts: (profile.contacts || []).map((c) => ({
      label: c.label || "",
      url: c.url || "",
    })),
    filings: (profile.filings || []).map((f) => ({
      label: f.label || "",
      url: f.url || "",
    })),
    quickQuestions: [...(profile.quickQuestions || [])],
    revision: typeof profile.revision === "number" ? profile.revision : 0,
  };
}

export function prepareProfileEdit(draft: ProfileEdit): {
  payload?: ProfileEdit;
  error?: string;
} {
  const displayName = draft.displayName.trim();
  const headline = draft.headline.trim();
  const bio = draft.bio.trim();

  if (displayName.length > 80) return { error: "署名长度不能超过 80 字符" };
  if (headline.length > 200) return { error: "副标题长度不能超过 200 字符" };
  if (bio.length > 3000) return { error: "个人简介不能超过 3000 字符" };

  if (draft.skills.length > 30) return { error: "能力条目最多 30 项" };
  const skills = draft.skills.map((s) => ({
    name: s.name.trim(),
    level: s.level.trim(),
    summary: s.summary.trim(),
  }));
  for (const s of skills) {
    if (!s.name || s.name.length > 100)
      return { error: "每项能力均须填写名称（1–100 字符）" };
    if (s.level.length > 200 || s.summary.length > 1000)
      return { error: "能力熟练度或说明超出字数限制" };
  }

  if (draft.experiences.length > 30) return { error: "履历条目最多 30 项" };
  const experiences = draft.experiences.map((e) => ({
    title: e.title.trim(),
    organization: e.organization.trim(),
    period: e.period.trim(),
    summary: e.summary.trim(),
  }));
  for (const e of experiences) {
    if (!e.title || e.title.length > 120)
      return { error: "每段履历均须填写职务或标题（1–120 字符）" };
    if (
      e.organization.length > 120 ||
      e.period.length > 100 ||
      e.summary.length > 2000
    ) {
      return { error: "履历字段超出字数限制" };
    }
  }

  if (draft.projects.length > 30) return { error: "项目条目最多 30 项" };
  const projects = draft.projects.map((p) => {
    const id = (p.id || "").trim() || crypto.randomUUID();
    return {
      id,
      name: p.name.trim(),
      role: p.role.trim(),
      summary: p.summary.trim(),
      stack: p.stack.map((t) => t.trim()).filter(Boolean),
      links: p.links.map((l) => ({
        label: l.label.trim(),
        url: l.url.trim(),
      })),
    };
  });
  for (const p of projects) {
    if (!p.id || p.id.length > 120) return { error: "项目编号无效" };
    if (!p.name || p.name.length > 120)
      return { error: "每个项目均须填写名称（1–120 字符）" };
    if (p.role.length > 120 || p.summary.length > 2000)
      return { error: "项目角色或简介超出字数限制" };
    if (p.stack.length > 30 || p.stack.some((t) => t.length > 80))
      return { error: "每个项目的技术栈标签最多 30 个，单个不超过 80 字符" };
    if (p.links.length > 10) return { error: "单个项目最多添加 10 个外链" };
    for (const l of p.links) {
      if (!l.label || l.label.length > 100 || l.url.length > 1000)
        return { error: "项目外链名称与地址须完整且不超长" };
      if (!checkUrl(l.url, false))
        return { error: "项目外链必须使用有效的 http、https 或 mailto 地址" };
    }
  }

  if (draft.contacts.length > 20) return { error: "公开联系方式最多 20 项" };
  const contacts = draft.contacts.map((c) => ({
    label: c.label.trim(),
    url: c.url.trim(),
  }));
  for (const c of contacts) {
    if (!c.label || c.label.length > 100 || c.url.length > 1000)
      return { error: "联系方式名称与地址须完整填写" };
    if (!checkUrl(c.url, false))
      return { error: "联系方式须使用有效的 http、https 或 mailto 地址" };
  }

  if (draft.filings.length > 5) return { error: "备案铭文最多 5 项" };
  const filings = draft.filings.map((f) => ({
    label: f.label.trim(),
    url: f.url.trim(),
  }));
  for (const f of filings) {
    if (!f.label || f.label.length > 100 || f.url.length > 1000)
      return { error: "备案名称与地址须完整填写" };
    if (!checkUrl(f.url, true))
      return { error: "备案链接必须使用有效的 https:// 地址" };
  }

  const quickQuestions = draft.quickQuestions
    .map((q) => q.trim())
    .filter(Boolean);
  if (quickQuestions.length > 10) return { error: "索书快捷提问最多 10 条" };
  if (quickQuestions.some((q) => q.length > 200))
    return { error: "单条快捷提问不能超过 200 字符" };

  const payload: ProfileEdit = {
    displayName,
    headline,
    bio,
    skills,
    experiences,
    projects,
    contacts,
    filings,
    quickQuestions,
    revision: Math.max(0, Math.floor(draft.revision || 0)),
  };

  if (JSON.stringify(payload).length > 30_000) {
    return { error: "公开名片总内容长度超过服务端限制，请适当精简" };
  }

  return { payload };
}
