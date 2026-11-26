<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import { prepareProfileEdit, profileToEdit } from "../profileForm";
import {
  normalizeProfile,
  notify,
  profile,
  useAction,
  useUnsaved,
} from "../shared";
import type { ProfileEdit } from "../types";

const { busy, error, run } = useAction();
const loaded = ref(false);
const validationError = ref("");
const newQuestion = ref("");
const savedSnapshot = ref("");

const form = ref<ProfileEdit>(profileToEdit(profile.value));

const isDirty = computed(
  () => loaded.value && JSON.stringify(form.value) !== savedSnapshot.value,
);
useUnsaved(() => isDirty.value);

async function load() {
  await run(async () => {
    const raw = await api<Record<string, unknown>>("/api/admin/profile");
    const normalized = normalizeProfile(raw);
    form.value = profileToEdit(normalized);
    savedSnapshot.value = JSON.stringify(form.value);
    loaded.value = true;
    validationError.value = "";
  });
}

function addSkill() {
  if (form.value.skills.length >= 30) return;
  form.value.skills.push({ name: "", level: "", summary: "" });
}

function addExperience() {
  if (form.value.experiences.length >= 30) return;
  form.value.experiences.push({
    title: "",
    organization: "",
    period: "",
    summary: "",
  });
}

function addProject() {
  if (form.value.projects.length >= 30) return;
  form.value.projects.push({
    id: crypto.randomUUID(),
    name: "",
    role: "",
    summary: "",
    stack: [],
    links: [],
  });
}

function projectStackText(idx: number) {
  return form.value.projects[idx].stack.join(", ");
}

function setProjectStackText(idx: number, raw: string) {
  form.value.projects[idx].stack = raw
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .slice(0, 30);
}

function addQuickQuestion() {
  const q = newQuestion.value.trim();
  if (!q || form.value.quickQuestions.length >= 10) return;
  form.value.quickQuestions.push(q.slice(0, 200));
  newQuestion.value = "";
}

async function save() {
  validationError.value = "";
  const prepared = prepareProfileEdit(form.value);
  if (prepared.error || !prepared.payload) {
    validationError.value = prepared.error || "请检查填写内容";
    return;
  }
  await run(async () => {
    const res = await api<{ published: boolean; revision: number }>(
      "/api/admin/profile",
      "PUT",
      prepared.payload,
    );
    form.value = { ...prepared.payload!, revision: res.revision };
    savedSnapshot.value = JSON.stringify(form.value);
    profile.value = normalizeProfile(await api("/api/public/profile"));
    notify(`公开名片已发布（修订 ${res.revision}）`);
  });
}

onMounted(load);
</script>

<template>
  <section class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="flex items-center gap-2.5">
        <h1 class="text-lg font-semibold">主页名片</h1>
        <span v-if="loaded" class="tag">修订 {{ form.revision }}</span>
      </div>
      <div class="flex items-center gap-2">
        <button type="button" class="btn" :disabled="busy" @click="load">
          重载
        </button>
        <button
          type="button"
          class="btn-primary"
          :disabled="busy || !loaded"
          @click="save"
        >
          {{ busy ? "发布中…" : "保存并发布名片" }}
        </button>
      </div>
    </div>

    <p v-if="error || validationError" role="alert" class="error">
      {{ validationError || error }}
    </p>

    <form v-if="loaded" class="space-y-3" @submit.prevent="save">
      <!-- 01 基础身份 -->
      <details class="fold-panel" open>
        <summary>
          <span>01 · 基础身份</span>
          <span class="fold-chevron">▸</span>
        </summary>
        <div class="fold-body space-y-3">
          <div class="grid sm:grid-cols-2 gap-3">
            <label class="block">
              <span class="field-label">署名 / 显示名称</span>
              <input
                v-model="form.displayName"
                class="field"
                maxlength="80"
                placeholder="例如：林深"
              />
            </label>
            <label class="block">
              <span class="field-label">副标题 / 定位</span>
              <input
                v-model="form.headline"
                class="field"
                maxlength="200"
                placeholder="一句话定位"
              />
            </label>
          </div>
          <label class="block">
            <span class="field-label">个人小传</span>
            <textarea
              v-model="form.bio"
              rows="3"
              maxlength="3000"
              class="field"
              placeholder="简短背景介绍（仅展示于档案弹窗）"
            ></textarea>
          </label>
        </div>
      </details>

      <!-- 02 快捷提问 -->
      <details class="fold-panel">
        <summary>
          <div class="flex items-center gap-2">
            <span>02 · 快捷提问</span>
            <span class="tag">{{ form.quickQuestions.length }} / 10</span>
          </div>
          <span class="fold-chevron">▸</span>
        </summary>
        <div class="fold-body space-y-3">
          <div class="flex gap-2">
            <input
              v-model="newQuestion"
              aria-label="新增快捷提问"
              class="field"
              maxlength="200"
              placeholder="添加访客灵感提问…"
              @keydown.enter.prevent="addQuickQuestion"
            />
            <button
              type="button"
              class="btn"
              :disabled="form.quickQuestions.length >= 10"
              @click="addQuickQuestion"
            >
              添加提问
            </button>
          </div>
          <ul v-if="form.quickQuestions.length" class="space-y-2">
            <li
              v-for="(_q, idx) in form.quickQuestions"
              :key="idx"
              class="flex items-center gap-2"
            >
              <input
                v-model="form.quickQuestions[idx]"
                :aria-label="'快捷提问 ' + (idx + 1)"
                class="field"
                maxlength="200"
              />
              <button
                type="button"
                class="btn"
                @click="form.quickQuestions.splice(idx, 1)"
              >
                移除
              </button>
            </li>
          </ul>
        </div>
      </details>

      <!-- 03 代表项目 -->
      <details class="fold-panel">
        <summary>
          <div class="flex items-center gap-2">
            <span>03 · 代表项目</span>
            <span class="tag">{{ form.projects.length }}</span>
          </div>
          <span class="fold-chevron">▸</span>
        </summary>
        <div class="fold-body space-y-3">
          <div class="flex justify-end">
            <button
              type="button"
              class="btn"
              :disabled="form.projects.length >= 30"
              @click="addProject"
            >
              ＋ 添加项目
            </button>
          </div>
          <article
            v-for="(proj, idx) in form.projects"
            :key="proj.id || idx"
            class="p-3.5 rounded-xl border border-[var(--border-hair)] bg-[var(--bg-subtle)] space-y-2.5"
          >
            <div class="flex items-center justify-between">
              <span class="mono-label">PROJECT {{ idx + 1 }}</span>
              <button
                type="button"
                class="btn-ghost text-[var(--danger)]"
                @click="form.projects.splice(idx, 1)"
              >
                删除
              </button>
            </div>
            <div class="grid sm:grid-cols-2 gap-2.5">
              <label class="block">
                <span class="field-label">项目名称 *</span>
                <input
                  v-model="proj.name"
                  class="field"
                  maxlength="120"
                  required
                />
              </label>
              <label class="block">
                <span class="field-label">担任角色</span>
                <input v-model="proj.role" class="field" maxlength="120" />
              </label>
            </div>
            <textarea
              v-model="proj.summary"
              rows="2"
              class="field"
              placeholder="项目摘要"
              maxlength="2000"
            ></textarea>
            <input
              :value="projectStackText(idx)"
              class="field font-mono text-xs"
              placeholder="技术栈（逗号分隔）"
              @change="
                setProjectStackText(
                  idx,
                  ($event.target as HTMLInputElement).value,
                )
              "
            />
            <div class="space-y-1.5">
              <button
                type="button"
                class="btn-ghost"
                :disabled="proj.links.length >= 10"
                @click="proj.links.push({ label: '', url: '' })"
              >
                ＋ 添加外链
              </button>
              <div
                v-for="(link, lIdx) in proj.links"
                :key="lIdx"
                class="grid sm:grid-cols-[1fr_2fr_auto] gap-2"
              >
                <input
                  v-model="link.label"
                  class="field"
                  placeholder="名称"
                  maxlength="100"
                />
                <input
                  v-model="link.url"
                  class="field font-mono text-xs"
                  placeholder="https://..."
                  maxlength="1000"
                />
                <button
                  type="button"
                  class="btn"
                  @click="proj.links.splice(lIdx, 1)"
                >
                  删
                </button>
              </div>
            </div>
          </article>
        </div>
      </details>

      <!-- 04 经历与技能 -->
      <details class="fold-panel">
        <summary>
          <div class="flex items-center gap-2">
            <span>04 · 经历与技能</span>
            <span class="tag">{{
              form.experiences.length + form.skills.length
            }}</span>
          </div>
          <span class="fold-chevron">▸</span>
        </summary>
        <div class="fold-body space-y-5">
          <div class="space-y-2.5">
            <div class="flex items-center justify-between">
              <span class="mono-label">履历</span>
              <button
                type="button"
                class="btn"
                :disabled="form.experiences.length >= 30"
                @click="addExperience"
              >
                ＋ 添加经历
              </button>
            </div>
            <div
              v-for="(exp, idx) in form.experiences"
              :key="idx"
              class="p-3 rounded-xl border border-[var(--border-hair)] bg-[var(--bg-subtle)] space-y-2"
            >
              <div class="flex justify-between items-center">
                <span class="mono-label">EXP {{ idx + 1 }}</span>
                <button
                  type="button"
                  class="btn-ghost text-[var(--danger)]"
                  @click="form.experiences.splice(idx, 1)"
                >
                  移除
                </button>
              </div>
              <input
                v-model="exp.title"
                class="field"
                placeholder="职务 *"
                maxlength="120"
              />
              <div class="grid grid-cols-2 gap-2">
                <input
                  v-model="exp.organization"
                  class="field"
                  placeholder="机构"
                  maxlength="120"
                />
                <input
                  v-model="exp.period"
                  class="field font-mono"
                  placeholder="时期"
                  maxlength="100"
                />
              </div>
              <textarea
                v-model="exp.summary"
                rows="2"
                class="field"
                placeholder="摘要"
                maxlength="2000"
              ></textarea>
            </div>
          </div>

          <div class="space-y-2.5 pt-3 border-t border-[var(--border-hair)]">
            <div class="flex items-center justify-between">
              <span class="mono-label">技能</span>
              <button
                type="button"
                class="btn"
                :disabled="form.skills.length >= 30"
                @click="addSkill"
              >
                ＋ 添加技能
              </button>
            </div>
            <div
              v-for="(skill, idx) in form.skills"
              :key="idx"
              class="p-3 rounded-xl border border-[var(--border-hair)] bg-[var(--bg-subtle)] space-y-2"
            >
              <div class="flex justify-between items-center">
                <span class="mono-label">SKILL {{ idx + 1 }}</span>
                <button
                  type="button"
                  class="btn-ghost text-[var(--danger)]"
                  @click="form.skills.splice(idx, 1)"
                >
                  移除
                </button>
              </div>
              <div class="grid grid-cols-2 gap-2">
                <input
                  v-model="skill.name"
                  class="field"
                  placeholder="技能名 *"
                  maxlength="100"
                />
                <input
                  v-model="skill.level"
                  class="field"
                  placeholder="定位"
                  maxlength="200"
                />
              </div>
              <textarea
                v-model="skill.summary"
                rows="2"
                class="field"
                placeholder="说明"
                maxlength="1000"
              ></textarea>
            </div>
          </div>
        </div>
      </details>

      <!-- 05 联络与备案 -->
      <details class="fold-panel">
        <summary>
          <div class="flex items-center gap-2">
            <span>05 · 联络与备案</span>
            <span class="tag">{{
              form.contacts.length + form.filings.length
            }}</span>
          </div>
          <span class="fold-chevron">▸</span>
        </summary>
        <div class="fold-body space-y-4">
          <div class="space-y-2">
            <div class="flex items-center justify-between">
              <span class="mono-label">联系方式</span>
              <button
                type="button"
                class="btn"
                :disabled="form.contacts.length >= 20"
                @click="form.contacts.push({ label: '', url: '' })"
              >
                ＋ 添加
              </button>
            </div>
            <div
              v-for="(c, idx) in form.contacts"
              :key="idx"
              class="grid grid-cols-[1fr_1.6fr_auto] gap-2"
            >
              <input
                v-model="c.label"
                class="field"
                placeholder="名称"
                maxlength="100"
              />
              <input
                v-model="c.url"
                class="field font-mono text-xs"
                placeholder="https:// 或 mailto:"
                maxlength="1000"
              />
              <button
                type="button"
                class="btn"
                @click="form.contacts.splice(idx, 1)"
              >
                删
              </button>
            </div>
          </div>

          <div class="space-y-2 pt-3 border-t border-[var(--border-hair)]">
            <div class="flex items-center justify-between">
              <span class="mono-label">备案（HTTPS）</span>
              <button
                type="button"
                class="btn"
                :disabled="form.filings.length >= 5"
                @click="form.filings.push({ label: '', url: '' })"
              >
                ＋ 添加
              </button>
            </div>
            <div
              v-for="(f, idx) in form.filings"
              :key="idx"
              class="grid grid-cols-[1fr_1.6fr_auto] gap-2"
            >
              <input
                v-model="f.label"
                class="field"
                placeholder="备案号"
                maxlength="100"
              />
              <input
                v-model="f.url"
                class="field font-mono text-xs"
                placeholder="https://..."
                maxlength="1000"
              />
              <button
                type="button"
                class="btn"
                @click="form.filings.splice(idx, 1)"
              >
                删
              </button>
            </div>
          </div>
        </div>
      </details>
    </form>
  </section>
</template>
