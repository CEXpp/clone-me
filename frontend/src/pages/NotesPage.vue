<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { onBeforeRouteLeave, useRoute } from "vue-router";
import { EditorContent, useEditor } from "@tiptap/vue-3";
import StarterKit from "@tiptap/starter-kit";
import { TableKit } from "@tiptap/extension-table";
import Image from "@tiptap/extension-image";
import { TextStyleKit } from "@tiptap/extension-text-style";
import Highlight from "@tiptap/extension-highlight";
import TaskList from "@tiptap/extension-task-list";
import TaskItem from "@tiptap/extension-task-item";
import type { JSONContent } from "@tiptap/core";
import { api } from "../api";

interface Category {
  id: string;
  name: string;
  notebook_id?: string;
}
interface Summary {
  id: string;
  section_id: string;
  title: string;
  revision: number;
  knowledge: number;
  favorite: number;
  deleted: number;
  indexed_revision: number;
  index_error: string;
  excerpt: string;
  updated_at: number;
}
interface Asset {
  id: string;
  name: string;
  mime: string;
}
interface Note extends Summary {
  body: JSONContent;
  assets: Asset[];
}
interface Library {
  notebooks: Category[];
  sections: Category[];
  notes: Summary[];
}
const route = useRoute();
const knowledgeMode = computed(() => route.path.endsWith("/knowledge"));
const library = ref<Library>({ notebooks: [], sections: [], notes: [] });
const current = ref<Note>();
const section = ref("default");
const view = ref(knowledgeMode.value ? "all" : "section");
const notebook = ref("default");
const ribbon = ref("home");
const mobilePane = ref("pages");
const pageMenu = ref<HTMLDetailsElement>();
const categoryDraft = ref<{ notebookId?: string; name: string } | null>(null);
const categoryInput = ref<HTMLInputElement>();
const categorySaving = ref(false);
const categoryError = ref("");
let categoryReturnFocus: HTMLElement | undefined;
const sections = computed(() =>
  library.value.sections.filter((s) => s.notebook_id === notebook.value),
);
const query = ref("");
const error = ref("");
const status = ref("");
const dirty = ref(false);
const working = ref(false);
const history = ref<{ revision: number; title: string; created_at: number }[]>(
  [],
);
const showHistory = ref(false);
const previewQuestion = ref("");
const results = ref<
  { id: string; sourceName: string; label: string; content: string }[]
>([]);
let timer: ReturnType<typeof setTimeout> | undefined;
let poll: ReturnType<typeof setInterval> | undefined;
let saving: Promise<void> | undefined;
let reloadSequence = 0;
let openSequence = 0;
let loading = false;
const editor = useEditor({
  extensions: [
    StarterKit,
    TableKit,
    Image,
    TextStyleKit,
    Highlight.configure({ multicolor: true }),
    TaskList,
    TaskItem.configure({ nested: true }),
  ],
  content: "",
  editorProps: {
    handlePaste: (_view, event) => {
      const file = Array.from(event.clipboardData?.files || []).find((f) =>
        f.type.startsWith("image/"),
      );
      if (!file) return false;
      void addAsset(file);
      return true;
    },
    handleDrop: (_view, event) => {
      const file = event.dataTransfer?.files[0];
      if (!file) return false;
      event.preventDefault();
      void addAsset(file);
      return true;
    },
    attributes: {
      "aria-label": "笔记正文",
      role: "textbox",
      "aria-multiline": "true",
    },
  },
  onUpdate: changed,
});
watch(query, () => void reload().catch(fail));
const listed = computed(() =>
  library.value.notes.filter((n) => {
    if (view.value === "trash") return !!n.deleted;
    return (
      !n.deleted &&
      (!knowledgeMode.value || n.knowledge) &&
      (view.value !== "section" || n.section_id === section.value)
    );
  }),
);
async function reload() {
  const sequence = ++reloadSequence;
  const loaded = await api<Library>(
    "/api/admin/notes?query=" + encodeURIComponent(query.value),
  );
  if (sequence === reloadSequence) library.value = loaded;
}
function draftKey(id: string) {
  return "note-draft:" + id;
}
function changed() {
  if (loading || !current.value) return;
  dirty.value = true;
  status.value = "尚未保存";
  try {
    localStorage.setItem(
      draftKey(current.value.id),
      JSON.stringify({
        revision: current.value.revision,
        title: current.value.title,
        body: editor.value?.getJSON(),
      }),
    );
  } catch {
    error.value = "本机草稿暂存不可用，仍会尝试保存到服务器";
  }
  clearTimeout(timer);
  timer = setTimeout(() => void save().catch(fail), 1200);
}
function fail(e: unknown) {
  error.value = e instanceof Error ? e.message : "操作失败";
  status.value = "保存或操作失败，请重试";
}
async function save() {
  if (saving) {
    await saving;
    if (dirty.value) return save();
    return;
  }
  if (!current.value || !dirty.value) return;
  const note = current.value;
  const payload = {
    sectionId: note.section_id,
    title: note.title,
    body: editor.value?.getJSON(),
    revision: note.revision,
    knowledge: !!note.knowledge,
    favorite: !!note.favorite,
    deleted: !!note.deleted,
  };
  const snapshot = JSON.stringify(payload);
  status.value = "正在保存…";
  saving = (async () => {
    const saved = await api<Note>(
      "/api/admin/notes/" + note.id,
      "PUT",
      payload,
    );
    note.revision = saved.revision;
    dirty.value =
      JSON.stringify({
        ...payload,
        title: note.title,
        body: editor.value?.getJSON(),
        knowledge: !!note.knowledge,
        favorite: !!note.favorite,
        deleted: !!note.deleted,
        sectionId: note.section_id,
      }) !== snapshot;
    if (!dirty.value) localStorage.removeItem(draftKey(note.id));
    status.value = dirty.value ? "还有修改待保存" : "已保存";
    error.value = "";
    await reload();
  })();
  try {
    await saving;
  } finally {
    saving = undefined;
  }
  if (dirty.value) await save();
}
async function open(id: string) {
  const sequence = ++openSequence;
  try {
    await save();
    const note = await api<Note>("/api/admin/notes/" + id);
    if (sequence !== openSequence) return;
    loading = true;
    current.value = note;
    mobilePane.value = "editor";
    section.value = note.section_id;
    notebook.value =
      library.value.sections.find((s) => s.id === note.section_id)
        ?.notebook_id || "default";
    editor.value?.commands.setContent(note.body, { emitUpdate: false });
    editor.value?.setEditable(!note.deleted);
    dirty.value = false;
    status.value = "已保存";
    showHistory.value = false;
    const draft = localStorage.getItem(draftKey(id));
    if (draft && window.confirm("发现本机未保存草稿，要恢复到编辑器中吗？")) {
      const value = JSON.parse(draft);
      note.title = value.title;
      editor.value?.commands.setContent(value.body, { emitUpdate: false });
      dirty.value = true;
      status.value = "已恢复本地草稿，请核对后保存";
    }
  } catch (e) {
    fail(e);
  } finally {
    loading = false;
  }
}
async function create() {
  try {
    await save();
    const note = await api<Note>("/api/admin/notes", "POST", {
      sectionId: section.value,
      title: "未命名笔记",
    });
    await reload();
    await open(note.id);
  } catch (e) {
    fail(e);
  }
}
async function category(notebookId?: string) {
  categoryReturnFocus =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : undefined;
  categoryDraft.value = { notebookId, name: "" };
  categoryError.value = "";
  mobilePane.value = "sections";
  await nextTick();
  categoryInput.value?.focus();
}
async function cancelCategory() {
  if (!categorySaving.value) {
    categoryDraft.value = null;
    categoryError.value = "";
    await nextTick();
    categoryReturnFocus?.focus();
  }
}
async function submitCategory() {
  const draft = categoryDraft.value;
  if (!draft || categorySaving.value) return;
  if (!draft.name.trim()) {
    categoryError.value = "请输入名称";
    categoryInput.value?.focus();
    return;
  }
  categorySaving.value = true;
  categoryError.value = "";
  try {
    const created = await api<{ id: string }>(
      "/api/admin/note-categories",
      "POST",
      { notebookId: draft.notebookId, name: draft.name.trim() },
    );
    categoryDraft.value = null;
    await reload();
    if (draft.notebookId) {
      notebook.value = draft.notebookId;
      await chooseSection(created.id);
    } else {
      notebook.value = created.id;
      view.value = "section";
      section.value = "";
    }
  } catch (e) {
    categoryError.value = e instanceof Error ? e.message : "创建失败，请重试";
  } finally {
    categorySaving.value = false;
  }
}
async function toggle(key: "knowledge" | "deleted") {
  if (!current.value) return;
  current.value[key] = current.value[key] ? 0 : 1;
  changed();
  try {
    await save();
    if (pageMenu.value) pageMenu.value.open = false;
    editor.value?.setEditable(!current.value.deleted);
  } catch (e) {
    fail(e);
  }
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = input.files;
  if (!files?.length) return;
  working.value = true;
  try {
    await save();
    for (const file of Array.from(files)) {
      const data = new FormData();
      data.append("file", file);
      data.append("sectionId", section.value);
      data.append("knowledge", String(knowledgeMode.value));
      const note = await api<Note>("/api/admin/notes/import", "POST", data);
      await reload();
      await open(note.id);
    }
  } catch (e) {
    fail(e);
  } finally {
    working.value = false;
    input.value = "";
  }
}
async function addAsset(file: File) {
  if (!current.value) return;
  const noteId = current.value.id;
  try {
    const data = new FormData();
    data.append("file", file);
    const asset = await api<Asset & { url: string }>(
      "/api/admin/notes/" + noteId + "/assets",
      "POST",
      data,
    );
    if (current.value?.id !== noteId) return;
    current.value.assets.push(asset);
    if (asset.mime.startsWith("image/"))
      editor.value
        ?.chain()
        .focus()
        .setImage({ src: asset.url, alt: asset.name })
        .run();
  } catch (e) {
    fail(e);
  }
}
async function uploadAsset(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file) await addAsset(file);
  input.value = "";
}
async function loadHistory() {
  if (!current.value) return;
  try {
    history.value = await api(
      "/api/admin/notes/" + current.value.id + "/history",
    );
    showHistory.value = !showHistory.value;
    if (pageMenu.value) pageMenu.value.open = false;
  } catch (e) {
    fail(e);
  }
}
async function restore(version: number) {
  if (!current.value || !window.confirm("将历史内容恢复为一个新修订？")) return;
  try {
    await save();
    const note = await api<Note>(
      "/api/admin/notes/" + current.value.id + "/restore",
      "POST",
      { version, revision: current.value.revision },
    );
    await reload();
    await open(note.id);
  } catch (e) {
    fail(e);
  }
}
async function retry() {
  if (!current.value) return;
  try {
    await api("/api/admin/notes/" + current.value.id + "/reindex", "POST", {});
    await reload();
  } catch (e) {
    fail(e);
  }
}
async function testSearch() {
  working.value = true;
  try {
    const value = await api<{ passages: typeof results.value }>(
      "/api/admin/notes/search",
      "POST",
      { question: previewQuestion.value },
    );
    results.value = value.passages;
  } catch (e) {
    fail(e);
  } finally {
    working.value = false;
  }
}
function setLink() {
  const url = window.prompt("链接地址（https://…）");
  if (url && /^https?:\/\//i.test(url))
    editor.value?.chain().focus().setLink({ href: url }).run();
}
async function chooseSection(id: string) {
  try {
    await save();
    section.value = id;
    view.value = "section";
    mobilePane.value = "pages";
  } catch (e) {
    fail(e);
  }
}
async function chooseNotebook() {
  const first = sections.value[0];
  if (first) await chooseSection(first.id);
}
function closeMenus(event: KeyboardEvent) {
  if (event.key === "Escape") {
    if (pageMenu.value) pageMenu.value.open = false;
    showHistory.value = false;
  }
}
function beforeUnload(e: BeforeUnloadEvent) {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
onBeforeRouteLeave(async () => {
  try {
    await save();
    return true;
  } catch (e) {
    fail(e);
    return false;
  }
});
watch(
  () => route.path,
  () => {
    view.value = knowledgeMode.value ? "all" : "section";
  },
);
onMounted(async () => {
  try {
    await reload();
    poll = setInterval(() => void reload().catch(fail), 10000);
  } catch (e) {
    fail(e);
  }
  window.addEventListener("beforeunload", beforeUnload);
});
onBeforeUnmount(() => {
  clearTimeout(timer);
  clearInterval(poll);
  window.removeEventListener("beforeunload", beforeUnload);
});
</script>

<template>
  <section
    class="notes-app"
    :class="'show-' + mobilePane"
    @keydown="closeMenus"
  >
    <div class="ribbon-tabs">
      <button
        class="mobile-nav"
        aria-label="显示页面列表"
        @click="mobilePane = mobilePane === 'editor' ? 'pages' : 'sections'"
      >
        <svg viewBox="0 0 20 20" width="17" height="17" aria-hidden="true">
          <path
            d="M3 5h14M3 10h14M3 15h14"
            fill="none"
            stroke="currentColor"
            stroke-width="1.4"
          />
        </svg>
      </button>
      <button :class="{ active: ribbon === 'home' }" @click="ribbon = 'home'">
        开始
      </button>
      <button
        :class="{ active: ribbon === 'insert' }"
        @click="ribbon = 'insert'"
      >
        插入
      </button>
      <RouterLink
        :to="knowledgeMode ? '/admin/notes' : '/admin/knowledge'"
        class="knowledge-link"
        :class="{ active: knowledgeMode }"
        >{{ knowledgeMode ? "返回笔记" : "访客知识库" }}</RouterLink
      >
      <span v-if="current" class="save-state" role="status">{{ status }}</span>
    </div>
    <div class="editor-toolbar" aria-label="正文格式">
      <template v-if="editor && ribbon === 'home'">
        <button
          aria-label="加粗"
          :aria-pressed="editor.isActive('bold')"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleBold().run()"
        >
          <b>B</b>
        </button>
        <button
          aria-label="斜体"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleItalic().run()"
        >
          <i>I</i>
        </button>
        <button
          aria-label="下划线"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleUnderline().run()"
        >
          <u>U</u>
        </button>
        <span class="toolbar-divider"></span>
        <button
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleHeading({ level: 2 }).run()"
        >
          标题
        </button>
        <button
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleBulletList().run()"
        >
          列表
        </button>
        <button
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleTaskList().run()"
        >
          待办
        </button>
        <button
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleHighlight().run()"
        >
          高亮
        </button>
        <label class="color-control"
          >文字颜色<input
            type="color"
            aria-label="文字颜色"
            :disabled="!current || !!current.deleted"
            @input="
              editor
                .chain()
                .focus()
                .setColor(($event.target as HTMLInputElement).value)
                .run()
            "
        /></label>
      </template>
      <template v-else-if="editor">
        <label class="file-button"
          >图片 / 附件<input
            type="file"
            :disabled="!current || !!current.deleted"
            @change="uploadAsset"
        /></label>
        <button :disabled="!current || !!current.deleted" @click="setLink">
          链接
        </button>
        <button
          :disabled="!current || !!current.deleted"
          @click="
            editor
              .chain()
              .focus()
              .insertTable({ rows: 3, cols: 3, withHeaderRow: true })
              .run()
          "
        >
          表格
        </button>
        <button
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleCodeBlock().run()"
        >
          代码块
        </button>
        <button
          v-if="editor.isActive('table')"
          @click="editor.chain().focus().addRowAfter().run()"
        >
          添加行
        </button>
        <button
          v-if="editor.isActive('table')"
          @click="editor.chain().focus().deleteTable().run()"
        >
          删除表格
        </button>
      </template>
      <span class="toolbar-spacer"></span>
      <details class="import-menu">
        <summary>{{ working ? "正在导入…" : "导入" }}</summary>
        <div class="popup">
          <label class="file-button"
            >{{ knowledgeMode ? "上传并加入知识库" : "导入笔记"
            }}<input
              type="file"
              accept=".md,.txt,.pdf,.docx"
              multiple
              :disabled="working"
              @change="importFile"
          /></label>
        </div>
      </details>
    </div>
    <div v-if="error" class="note-error" role="alert">
      {{ error }} <button @click="save().catch(fail)">重试保存</button>
    </div>
    <div class="note-workspace">
      <aside class="note-tree" aria-label="笔记分类">
        <div class="notebook-picker">
          <select
            v-model="notebook"
            aria-label="笔记本"
            @change="chooseNotebook"
          >
            <option
              v-for="book in library.notebooks"
              :key="book.id"
              :value="book.id"
            >
              {{ book.name }}
            </option></select
          ><button
            aria-label="新建笔记本"
            title="新建笔记本"
            @click="category()"
          >
            ＋
          </button>
        </div>
        <button
          class="all-notes"
          :class="{ selected: view === 'all' }"
          @click="
            view = 'all';
            mobilePane = 'pages';
          "
        >
          {{ knowledgeMode ? "已加入的笔记" : "全部笔记" }}
        </button>
        <button
          v-for="item in sections"
          :key="item.id"
          class="section-item"
          :class="{ selected: view === 'section' && section === item.id }"
          @click="chooseSection(item.id)"
        >
          <span
            class="section-mark"
            :style="{
              background: '#b973d3',
            }"
            aria-hidden="true"
          ></span
          >{{ item.name }}
        </button>
        <form
          v-if="categoryDraft"
          class="category-create"
          @submit.prevent="submitCategory"
          @keydown.esc.prevent.stop="cancelCategory"
        >
          <label :for="'category-name'">{{
            categoryDraft.notebookId ? "新建分区" : "新建笔记本"
          }}</label>
          <div class="category-input-row">
            <input
              id="category-name"
              ref="categoryInput"
              v-model="categoryDraft.name"
              @input="categoryError = ''"
              :aria-label="categoryDraft.notebookId ? '分区名称' : '笔记本名称'"
              :aria-invalid="!!categoryError"
              :aria-describedby="categoryError ? 'category-error' : undefined"
              maxlength="200"
              autocomplete="off"
              :disabled="categorySaving"
            /><button
              type="submit"
              aria-label="确认创建"
              :disabled="categorySaving"
            >
              <svg
                viewBox="0 0 20 20"
                width="16"
                height="16"
                aria-hidden="true"
              >
                <path
                  d="m4 10 4 4 8-8"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
              </svg></button
            ><button
              type="button"
              aria-label="取消创建"
              :disabled="categorySaving"
              @click="cancelCategory"
            >
              ×
            </button>
          </div>
          <p v-if="categoryError" id="category-error" role="alert">
            {{ categoryError }}
          </p>
        </form>
        <button
          v-show="!categoryDraft"
          class="add-section"
          @click="category(notebook)"
        >
          ＋ 新建分区
        </button>
        <div class="tree-bottom">
          <button
            :class="{ selected: view === 'trash' }"
            @click="
              view = 'trash';
              mobilePane = 'pages';
            "
          >
            回收站
          </button>
        </div>
      </aside>
      <aside class="note-list" aria-label="笔记列表">
        <div class="page-search">
          <svg viewBox="0 0 20 20" width="15" height="15" aria-hidden="true">
            <circle cx="8" cy="8" r="5" fill="none" stroke="currentColor" />
            <path d="m12 12 5 5" stroke="currentColor" /></svg
          ><input
            v-model="query"
            aria-label="筛选笔记"
            placeholder="搜索笔记"
            maxlength="200"
          />
        </div>
        <button class="add-page" aria-label="新建笔记" @click="create">
          ＋ 新建笔记
        </button>
        <div class="pages-scroll">
          <button
            v-for="n in listed"
            :key="n.id"
            class="note-card"
            :aria-current="current?.id === n.id ? 'page' : undefined"
            :class="{ selected: current?.id === n.id }"
            @click="open(n.id)"
          >
            <strong>{{ n.title }}</strong
            ><span v-if="knowledgeMode" class="knowledge-state">{{
              n.index_error
                ? "同步失败"
                : n.indexed_revision === n.revision
                  ? "知识库已同步"
                  : "等待同步"
            }}</span>
          </button>
          <p v-if="!listed.length" class="list-empty">
            {{ query ? "没有找到笔记" : "暂无页面" }}
          </p>
        </div>
      </aside>
      <article class="note-document">
        <template v-if="current">
          <div class="document-meta">
            <span>{{
              library.sections.find((s) => s.id === current?.section_id)?.name
            }}</span
            ><span v-if="current.knowledge" class="shared-label"
              >用于访客问答</span
            >
            <details ref="pageMenu" class="page-menu">
              <summary aria-label="页面选项" title="页面选项">···</summary>
              <div class="popup">
                <label class="share-option"
                  ><input
                    type="checkbox"
                    :checked="!!current.knowledge"
                    :disabled="!!current.deleted"
                    @change="toggle('knowledge')"
                  />
                  用于访客问答</label
                >
                <button @click="loadHistory">历史</button>
                <label class="move-option"
                  >移动到<select
                    v-model="current.section_id"
                    aria-label="移动到分区"
                    @change="changed"
                  >
                    <option
                      v-for="s in library.sections"
                      :key="s.id"
                      :value="s.id"
                    >
                      {{ s.name }}
                    </option>
                  </select></label
                >
                <button v-if="current.knowledge" @click="retry">
                  重新同步知识库
                </button>
                <button @click="toggle('deleted')">
                  {{ current.deleted ? "恢复笔记" : "移入回收站" }}
                </button>
              </div>
            </details>
          </div>
          <div class="page-content">
            <input
              v-model="current.title"
              class="note-title"
              aria-label="笔记标题"
              :disabled="!!current.deleted"
              @input="changed"
            />
            <div class="page-date">
              {{
                new Date(current.updated_at).toLocaleDateString("zh-CN", {
                  year: "numeric",
                  month: "long",
                  day: "numeric",
                })
              }}
            </div>
            <EditorContent :editor="editor" class="note-editor" />
            <div v-if="current.assets.length" class="note-attachments">
              <a
                v-for="a in current.assets"
                :key="a.id"
                :href="'/api/admin/note-assets/' + a.id"
                target="_blank"
                rel="noopener"
                >{{ a.name }}</a
              >
            </div>
          </div>
          <aside v-if="showHistory" class="history-panel">
            <div class="history-heading">
              <strong>页面历史</strong
              ><button aria-label="关闭历史" @click="showHistory = false">
                ×
              </button>
            </div>
            <div class="history-list">
              <p v-if="!history.length">暂无历史修订</p>
              <button
                v-for="h in history"
                :key="h.revision"
                @click="restore(h.revision)"
              >
                {{ h.title
                }}<small
                  >修订 {{ h.revision }} ·
                  {{ new Date(h.created_at).toLocaleString("zh-CN") }}</small
                >
              </button>
            </div>
          </aside>
        </template>
        <p v-else class="editor-empty">选择页面开始书写</p>
      </article>
    </div>
    <details v-if="knowledgeMode" class="retrieval-test">
      <summary>测试知识库检索</summary>
      <div class="retrieval-content">
        <form @submit.prevent="testSearch">
          <input
            v-model="previewQuestion"
            aria-label="测试问题"
            placeholder="输入访客可能提出的问题"
            maxlength="2000"
            required
          /><button :disabled="working">检索</button>
        </form>
        <div v-for="r in results" :key="r.id" class="search-passage">
          <strong>{{ r.sourceName }}</strong
          ><small>{{ r.label }}</small>
          <p>{{ r.content }}</p>
        </div>
      </div>
    </details>
  </section>
</template>

<style scoped>
.notes-app {
  --paper: #201f1e;
  --ink: #e6e6e6;
  --muted: #b0b0b0;
  --line: #3b3b3b;
  --surface: #090909;
  --list: #292929;
  --selected: #3b3b3b;
  --purple: #b973d3;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--paper);
  color: var(--ink);
  color-scheme: dark;
  font:
    14px "Segoe UI",
    "Microsoft YaHei",
    sans-serif;
}
.notes-app button,
.notes-app summary {
  cursor: pointer;
  font: inherit;
}
.notes-app button:disabled {
  opacity: 0.4;
  cursor: default;
}
.notes-app button:focus-visible,
.notes-app summary:focus-visible,
.notes-app a:focus-visible {
  outline: 2px solid var(--purple);
  outline-offset: -2px;
}
.notes-app input,
.notes-app select {
  color: var(--ink);
  background: transparent;
}
.notes-app input:focus-visible,
.notes-app select:focus-visible {
  outline: 1px solid var(--purple);
  outline-offset: 0;
}
.ribbon-tabs {
  height: 37px;
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 0 18px;
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
  background: #090909;
}
.ribbon-tabs > button,
.ribbon-tabs > a {
  padding: 0 16px;
  display: flex;
  align-items: center;
  text-decoration: none;
  color: var(--ink);
  border-bottom: 2px solid transparent;
  font-size: 13px;
}
.ribbon-tabs .active {
  border-bottom-color: var(--purple);
  color: var(--ink);
}
.ribbon-tabs .knowledge-link {
  margin-left: 20px;
}
.save-state {
  margin-left: auto;
  display: flex;
  align-items: center;
  color: var(--muted);
  font-size: 12px;
  white-space: nowrap;
}
.editor-toolbar {
  min-height: 42px;
  display: flex;
  align-items: center;
  padding: 3px 18px;
  gap: 3px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
  background: var(--surface);
}
.editor-toolbar > button {
  min-width: 30px;
  height: 32px;
  padding: 0 10px;
  border-radius: 2px;
}
.editor-toolbar > button:hover,
.editor-toolbar [aria-pressed="true"] {
  background: var(--selected);
}
.toolbar-divider {
  height: 21px;
  border-left: 1px solid var(--line);
  margin: 0 8px;
}
.toolbar-spacer {
  flex: 1;
}
.color-control {
  position: relative;
  width: 30px;
  overflow: hidden;
  text-indent: -999px;
  display: inline-flex;
  align-items: center;
}
.color-control input {
  width: 25px;
  height: 25px;
  text-indent: 0;
  position: absolute;
  left: 0;
}
.file-button {
  position: relative;
  display: flex;
  align-items: center;
  cursor: pointer;
  padding: 8px 12px;
}
.file-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.file-button:focus-within {
  outline: 1px solid var(--purple);
}
.import-menu,
.page-menu {
  position: relative;
}
.import-menu summary {
  padding: 6px 12px;
  list-style: none;
}
.popup {
  position: absolute;
  right: 0;
  top: 100%;
  min-width: 210px;
  background: var(--paper);
  color: var(--ink);
  border: 1px solid var(--line);
  box-shadow: 0 4px 12px #0002;
  z-index: 30;
  padding: 5px;
}
.popup button,
.popup > label {
  width: 100%;
  display: flex;
  gap: 8px;
  align-items: center;
  min-height: 38px;
  padding: 8px 12px;
  font-size: 13px;
  text-align: left;
}
.popup button:hover,
.popup > label:hover {
  background: var(--surface);
}
.popup select {
  min-width: 0;
  max-width: 130px;
  border: 1px solid var(--line);
  padding: 3px;
}
.popup .move-option {
  justify-content: space-between;
}
.note-error {
  padding: 9px 18px;
  background: #fff1dd;
  color: #783f13;
  font-size: 13px;
}
.note-error button {
  text-decoration: underline;
  margin-left: 10px;
}
.note-workspace {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 190px 220px minmax(0, 1fr);
  background: var(--paper);
}
.note-tree {
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-right: 1px solid var(--line);
  overflow: auto;
  padding-top: 12px;
}
.notebook-picker {
  display: flex;
  align-items: center;
  padding: 2px 12px 14px;
  gap: 4px;
}
.notebook-picker select {
  width: 100%;
  min-width: 0;
  font-weight: 600;
  font-size: 14px;
  border: 0;
}
.notebook-picker button {
  width: 30px;
  flex-shrink: 0;
  font-size: 19px;
  color: var(--muted);
}
.note-tree > button {
  min-height: 40px;
  text-align: left;
  padding: 8px 18px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}
.note-tree > button:hover {
  background: var(--line);
}
.note-tree .selected {
  background: #181818;
  color: var(--ink);
}
.all-notes {
  margin-bottom: 8px;
}
.section-mark {
  width: 5px;
  height: 20px;
  flex-shrink: 0;
  border-radius: 1px;
}
.note-tree .add-section {
  color: var(--muted);
  margin-top: 10px;
}
.tree-bottom {
  margin-top: auto;
  padding: 20px 18px 14px;
  font-size: 12px;
  color: var(--muted);
}
.tree-bottom button {
  min-height: 32px;
}
.note-list {
  display: flex;
  flex-direction: column;
  background: var(--list);
  border-right: 1px solid var(--line);
  min-height: 0;
  min-width: 0;
}
.page-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin: 12px 12px 8px;
  border: 1px solid var(--line);
  background: var(--paper);
  color: var(--muted);
}
.page-search input {
  width: 100%;
  min-width: 0;
  font-size: 12px;
  border: 0;
  outline: none;
}
.add-page {
  min-height: 40px;
  text-align: left;
  margin: 0 10px 5px;
  padding: 8px 10px;
  color: var(--purple);
  font-size: 13px !important;
}
.pages-scroll {
  overflow: auto;
  flex: 1;
}
.note-card {
  width: 100%;
  text-align: left;
  display: block;
  border-left: 3px solid transparent;
  min-height: 43px;
  padding: 12px 18px;
}
.note-card strong {
  font-weight: 400;
  font-size: 13px;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.note-card:hover {
  background: var(--surface);
}
.note-card.selected {
  border-left-color: var(--purple);
  background: var(--selected);
}
.knowledge-state {
  display: block;
  font-size: 11px;
  color: var(--muted);
  margin-top: 5px;
}
.list-empty {
  padding: 20px;
  font-size: 12px;
  color: var(--muted);
}
.note-document {
  min-width: 0;
  overflow: auto;
  position: relative;
  background: var(--paper);
}
.document-meta {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 15px 30px 0;
  font-size: 11px;
  color: var(--muted);
  height: 44px;
}
.shared-label {
  color: var(--purple);
}
.page-menu {
  margin-left: auto;
}
.page-menu summary {
  list-style: none;
  font-size: 24px;
  line-height: 24px;
  padding: 2px 8px;
  letter-spacing: 2px;
}
.page-content {
  padding: 12px 50px 70px;
  max-width: 1100px;
}
.note-title {
  width: 100%;
  border: 0;
  border-bottom: 1px solid var(--line);
  padding: 0 0 12px;
  font-size: 28px;
  line-height: 1.5;
  font-weight: 400;
  outline: none !important;
}
.page-date {
  font-size: 11px;
  color: var(--muted);
  padding: 11px 0 16px;
}
.note-editor :deep(.tiptap) {
  outline: none;
  min-height: calc(100dvh - 320px);
  line-height: 1.8;
  font-size: 15px;
  overflow-wrap: anywhere;
  padding-top: 8px;
}
.note-editor :deep(p) {
  margin: 7px 0;
}
.note-editor :deep(h2) {
  font-size: 23px;
  font-weight: 600;
  margin: 20px 0 10px;
}
.note-editor :deep(ul) {
  list-style: disc;
  padding-left: 24px;
}
.note-editor :deep(ol) {
  list-style: decimal;
  padding-left: 24px;
}
.note-editor :deep(li[data-type="taskItem"]) {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.note-editor :deep(pre) {
  white-space: pre-wrap;
  background: var(--surface);
  padding: 14px;
  font-size: 13px;
}
.note-editor :deep(blockquote) {
  border-left: 3px solid var(--purple);
  padding-left: 15px;
}
.note-editor :deep(table) {
  border-collapse: collapse;
  width: 100%;
  table-layout: fixed;
}
.note-editor :deep(td),
.note-editor :deep(th) {
  border: 1px solid var(--muted);
  padding: 8px;
  vertical-align: top;
}
.note-editor :deep(img) {
  max-width: 100%;
  height: auto;
}
.note-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 15px;
  font-size: 12px;
  padding-top: 20px;
}
.note-attachments a {
  color: var(--purple);
  text-decoration: underline;
}
.editor-empty {
  color: var(--muted);
  font-size: 14px;
  padding: 65px 50px;
}
.history-panel {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 290px;
  max-width: 100%;
  border-left: 1px solid var(--line);
  background: var(--list);
  z-index: 20;
  overflow: auto;
}
.history-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
}
.history-heading button {
  font-size: 23px;
  width: 32px;
  height: 32px;
}
.history-list button {
  padding: 15px;
  width: 100%;
  text-align: left;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
}
.history-list small {
  display: block;
  color: var(--muted);
  font-size: 11px;
  margin-top: 6px;
}
.history-list > p {
  padding: 20px;
  color: var(--muted);
}
.retrieval-test {
  flex-shrink: 0;
  border-top: 1px solid var(--line);
  font-size: 12px;
  background: var(--surface);
  max-height: 35%;
  overflow: auto;
}
.retrieval-test > summary {
  padding: 10px 20px;
  color: var(--muted);
}
.retrieval-content {
  padding: 8px 20px 20px;
}
.retrieval-content form {
  display: flex;
  gap: 10px;
}
.retrieval-content input {
  border: 1px solid var(--line);
  background: var(--paper);
  padding: 8px;
  flex: 1;
  min-width: 0;
}
.retrieval-content form button {
  padding: 8px 18px;
  background: #181818;
  color: var(--ink);
}
.search-passage {
  padding: 15px 0;
  border-bottom: 1px solid var(--line);
}
.search-passage small {
  display: block;
  color: var(--muted);
  margin: 5px 0;
}
.search-passage p {
  white-space: pre-wrap;
  line-height: 1.7;
}
.mobile-nav {
  display: none !important;
}
.category-create {
  padding: 12px 14px;
}
.category-create > label {
  display: block;
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 7px;
}
.category-input-row {
  display: flex;
  align-items: center;
  border: 1px solid var(--purple);
  background: #1f1f1f;
  min-width: 0;
}
.category-input-row input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: none !important;
  padding: 7px 5px;
  font-size: 13px;
}
.category-input-row button {
  flex-shrink: 0;
  width: 25px;
  min-height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink);
}
.category-input-row button:hover {
  background: #3b3b3b;
}
.category-create > p {
  color: #f0b6a8;
  font-size: 12px;
  margin-top: 6px;
}
.category-input-row button:disabled {
  opacity: 0.4;
}
@media (max-width: 1000px) {
  .note-workspace {
    grid-template-columns: 160px 185px minmax(0, 1fr);
  }
  .page-content {
    padding: 12px 28px 50px;
  }
  .document-meta {
    padding-inline: 22px;
  }
}
@media (max-width: 700px) {
  .mobile-nav {
    display: flex !important;
  }
  .ribbon-tabs {
    padding: 0 6px;
  }
  .ribbon-tabs > button,
  .ribbon-tabs > a {
    padding: 0 11px;
  }
  .ribbon-tabs .knowledge-link {
    margin-left: 0;
  }
  .save-state {
    font-size: 10px;
  }
  .editor-toolbar {
    padding: 3px 8px;
    gap: 0;
    overflow: visible;
  }
  .editor-toolbar > button {
    padding: 0 8px;
    min-height: 38px;
  }
  .toolbar-divider {
    margin: 0 3px;
  }
  .color-control {
    display: none;
  }
  .note-workspace {
    display: flex;
  }
  .note-tree,
  .note-list,
  .note-document {
    display: none;
    width: 100%;
    border-right: 0;
  }
  .show-pages .note-list,
  .show-sections .note-tree {
    display: flex;
  }
  .show-editor .note-document {
    display: block;
  }
  .note-card {
    min-height: 48px;
  }
  .note-tree > button {
    min-height: 46px;
  }
  .page-content {
    padding: 15px 24px 50px;
  }
  .document-meta {
    padding-inline: 20px;
  }
  .note-title {
    font-size: 25px;
  }
  .note-editor :deep(.tiptap) {
    font-size: 16px;
  }
  .import-menu summary {
    padding: 8px;
  }
  .editor-empty {
    padding: 45px 24px;
  }
  .popup button,
  .popup > label {
    min-height: 44px;
  }
  .note-list .page-search {
    margin-top: 14px;
  }
  .ribbon-tabs .knowledge-link {
    font-size: 12px;
  }
}
</style>
