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
import { api, ApiError } from "../api";
import { authNavigation } from "../auth";

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
const sidebarCollapsed = ref(false);
const mobilePane = ref<"pages" | "sections" | "editor">("pages");
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
const staleDraftNoteId = ref("");
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

function hasPendingSync() {
  return library.value.notes.some(
    (n) =>
      n.knowledge &&
      !n.deleted &&
      n.indexed_revision !== n.revision &&
      !n.index_error,
  );
}

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
    error.value = "本机草稿暂存不可用";
  }
  clearTimeout(timer);
  timer = setTimeout(() => void save().catch(fail), 1200);
}

function fail(e: unknown) {
  error.value = e instanceof Error ? e.message : "操作失败";
  status.value = "保存失败";
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
  status.value = "保存中…";
  saving = (async () => {
    const saved = await api<Note>(
      "/api/admin/notes/" + note.id,
      "PUT",
      payload,
    );
    note.revision = saved.revision;
    note.updated_at = saved.updated_at;
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
    if (!dirty.value) {
      localStorage.removeItem(draftKey(note.id));
      staleDraftNoteId.value = "";
    }
    status.value = dirty.value ? "待保存" : "已保存";
    error.value = "";
    await reload();
  })();
  try {
    await saving;
  } catch (e) {
    if (e instanceof ApiError && e.code === "NOTE_CONFLICT") {
      staleDraftNoteId.value = note.id;
    }
    throw e;
  } finally {
    saving = undefined;
  }
  if (dirty.value) await save();
}

async function discardDraftAndReload() {
  if (!current.value) return;
  const id = current.value.id;
  localStorage.removeItem(draftKey(id));
  staleDraftNoteId.value = "";
  dirty.value = false;
  error.value = "";
  await open(id);
}

async function open(id: string) {
  const sequence = ++openSequence;
  try {
    await save();
    const note = await api<Note>("/api/admin/notes/" + id);
    if (sequence !== openSequence) return;
    loading = true;
    try {
      current.value = note;
      mobilePane.value = "editor";
      section.value = note.section_id;
      notebook.value =
        library.value.sections.find((s) => s.id === note.section_id)
          ?.notebook_id || "default";
      editor.value?.commands.setContent(note.body, { emitUpdate: false });
      editor.value?.setEditable(!note.deleted);
      dirty.value = false;
      error.value = "";
      staleDraftNoteId.value = "";
      status.value = "已保存";
      showHistory.value = false;

      const draft = localStorage.getItem(draftKey(id));
      if (draft) {
        try {
          const value = JSON.parse(draft) as {
            revision?: number;
            title?: string;
            body?: JSONContent;
          };
          if (value.revision !== note.revision) {
            staleDraftNoteId.value = id;
            error.value = `本地草稿基于旧修订 ${value.revision ?? "?"}，服务端已为修订 ${note.revision}。`;
          } else if (
            window.confirm("发现本机未保存草稿，要恢复到编辑器中吗？")
          ) {
            if (typeof value.title === "string") note.title = value.title;
            if (value.body) {
              editor.value?.commands.setContent(value.body, {
                emitUpdate: false,
              });
            }
            dirty.value = true;
            status.value = "已恢复草稿";
          }
        } catch {
          localStorage.removeItem(draftKey(id));
        }
      }
    } finally {
      loading = false;
    }
  } catch (e) {
    if (sequence === openSequence) fail(e);
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
    categoryError.value = e instanceof Error ? e.message : "创建失败";
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
    if (asset.mime.startsWith("image/")) {
      editor.value
        ?.chain()
        .focus()
        .setImage({ src: asset.url, alt: asset.name })
        .run();
    }
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
  if (url && /^https?:\/\//i.test(url)) {
    editor.value?.chain().focus().setLink({ href: url }).run();
  }
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

function onVisibilityChange() {
  if (!document.hidden && hasPendingSync()) {
    void reload().catch(fail);
  }
}

onBeforeRouteLeave(async () => {
  if (authNavigation.expired) return true;
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
    poll = setInterval(() => {
      if (!document.hidden && hasPendingSync()) {
        void reload().catch(fail);
      }
    }, 10000);
  } catch (e) {
    fail(e);
  }
  window.addEventListener("beforeunload", beforeUnload);
  document.addEventListener("visibilitychange", onVisibilityChange);
});

onBeforeUnmount(() => {
  clearTimeout(timer);
  clearInterval(poll);
  window.removeEventListener("beforeunload", beforeUnload);
  document.removeEventListener("visibilitychange", onVisibilityChange);
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
        type="button"
        class="mobile-nav"
        aria-label="显示页面列表"
        @click="
          mobilePane =
            mobilePane === 'editor'
              ? 'pages'
              : mobilePane === 'pages'
                ? 'sections'
                : 'pages'
        "
      >
        <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
          <path
            d="M3 5h14M3 10h14M3 15h14"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
          />
        </svg>
      </button>

      <button
        type="button"
        class="desktop-sidebar-toggle"
        @click="sidebarCollapsed = !sidebarCollapsed"
      >
        {{ sidebarCollapsed ? "展开目录" : "收起目录" }}
      </button>

      <button
        type="button"
        :class="{ active: ribbon === 'home' }"
        @click="ribbon = 'home'"
      >
        格式
      </button>
      <button
        type="button"
        :class="{ active: ribbon === 'insert' }"
        @click="ribbon = 'insert'"
      >
        插入
      </button>

      <span v-if="current" class="save-state" role="status">{{ status }}</span>
    </div>

    <div class="editor-toolbar" aria-label="正文工具">
      <template v-if="editor && ribbon === 'home'">
        <button
          type="button"
          aria-label="加粗"
          :aria-pressed="editor.isActive('bold')"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleBold().run()"
        >
          <b>B</b>
        </button>
        <button
          type="button"
          aria-label="斜体"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleItalic().run()"
        >
          <i>I</i>
        </button>
        <button
          type="button"
          aria-label="下划线"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleUnderline().run()"
        >
          <u>U</u>
        </button>
        <span class="toolbar-divider"></span>
        <button
          type="button"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleHeading({ level: 2 }).run()"
        >
          标题
        </button>
        <button
          type="button"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleBulletList().run()"
        >
          列表
        </button>
        <button
          type="button"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleTaskList().run()"
        >
          待办
        </button>
        <button
          type="button"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleHighlight().run()"
        >
          高亮
        </button>
        <label class="color-control">
          文字颜色
          <input
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
          />
        </label>
      </template>

      <template v-else-if="editor">
        <label class="file-button">
          图片 / 附件
          <input
            type="file"
            :disabled="!current || !!current.deleted"
            @change="uploadAsset"
          />
        </label>
        <button
          type="button"
          :disabled="!current || !!current.deleted"
          @click="setLink"
        >
          链接
        </button>
        <button
          type="button"
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
          type="button"
          :disabled="!current || !!current.deleted"
          @click="editor.chain().focus().toggleCodeBlock().run()"
        >
          代码块
        </button>
        <button
          v-if="editor.isActive('table')"
          type="button"
          @click="editor.chain().focus().addRowAfter().run()"
        >
          加行
        </button>
        <button
          v-if="editor.isActive('table')"
          type="button"
          @click="editor.chain().focus().deleteTable().run()"
        >
          删表
        </button>
      </template>

      <span class="toolbar-spacer"></span>

      <details class="import-menu">
        <summary>{{ working ? "导入中…" : "导入 ▾" }}</summary>
        <div class="popup">
          <label class="file-button">
            {{ knowledgeMode ? "导入并共享" : "导入笔记" }}
            <input
              type="file"
              accept=".md,.txt,.pdf,.docx"
              multiple
              :disabled="working"
              @change="importFile"
            />
          </label>
        </div>
      </details>
    </div>

    <div v-if="error" class="note-error" role="alert">
      <span>{{ error }}</span>
      <button v-if="dirty && current" type="button" @click="save().catch(fail)">
        重试
      </button>
      <button
        v-if="staleDraftNoteId && current?.id === staleDraftNoteId"
        type="button"
        @click="discardDraftAndReload().catch(fail)"
      >
        放弃本机旧草稿
      </button>
    </div>

    <div
      class="note-workspace"
      :class="{ 'sidebar-collapsed': sidebarCollapsed }"
    >
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
            </option>
          </select>
          <button
            type="button"
            aria-label="新建笔记本"
            title="新建笔记本"
            @click="category()"
          >
            ＋
          </button>
        </div>

        <button
          type="button"
          class="all-notes"
          :class="{ selected: view === 'all' }"
          @click="
            view = 'all';
            mobilePane = 'pages';
          "
        >
          {{ knowledgeMode ? "已共享" : "全部" }}
        </button>

        <button
          v-for="item in sections"
          :key="item.id"
          type="button"
          class="section-item"
          :class="{ selected: view === 'section' && section === item.id }"
          @click="chooseSection(item.id)"
        >
          <span class="section-mark" aria-hidden="true"></span>
          {{ item.name }}
        </button>

        <form
          v-if="categoryDraft"
          class="category-create"
          @submit.prevent="submitCategory"
          @keydown.esc.prevent.stop="cancelCategory"
        >
          <label for="category-name">{{
            categoryDraft.notebookId ? "新建分区" : "新建笔记本"
          }}</label>
          <div class="category-input-row">
            <input
              id="category-name"
              ref="categoryInput"
              v-model="categoryDraft.name"
              :aria-label="categoryDraft.notebookId ? '分区名称' : '笔记本名称'"
              :aria-invalid="!!categoryError"
              :aria-describedby="categoryError ? 'category-error' : undefined"
              maxlength="200"
              autocomplete="off"
              :disabled="categorySaving"
              @input="categoryError = ''"
            />
            <button
              type="submit"
              aria-label="确认创建"
              :disabled="categorySaving"
            >
              ✓
            </button>
            <button
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
          type="button"
          class="add-section"
          @click="category(notebook)"
        >
          ＋ 新建分区
        </button>

        <div class="tree-bottom">
          <button
            type="button"
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
          <input
            v-model="query"
            aria-label="筛选笔记"
            placeholder="搜索…"
            maxlength="200"
          />
        </div>

        <button
          type="button"
          class="add-page"
          aria-label="新建笔记"
          @click="create"
        >
          ＋ 新建笔记
        </button>

        <div class="pages-scroll">
          <button
            v-for="n in listed"
            :key="n.id"
            type="button"
            class="note-card"
            :aria-current="current?.id === n.id ? 'page' : undefined"
            :class="{ selected: current?.id === n.id }"
            @click="open(n.id)"
          >
            <strong>{{ n.title }}</strong>
            <span v-if="knowledgeMode" class="knowledge-state">{{
              n.index_error
                ? "同步失败"
                : n.indexed_revision === n.revision
                  ? "知识库已同步"
                  : "等待同步"
            }}</span>
          </button>
          <p v-if="!listed.length" class="list-empty">
            {{ query ? "无匹配" : "暂无笔记" }}
          </p>
        </div>
      </aside>

      <article class="note-document">
        <template v-if="current">
          <div class="document-meta">
            <span>{{
              library.sections.find((s) => s.id === current?.section_id)?.name
            }}</span>
            <span v-if="current.knowledge" class="tag tag-ok">已共享</span>
            <details ref="pageMenu" class="page-menu">
              <summary aria-label="页面选项" title="页面选项">···</summary>
              <div class="popup">
                <label class="share-option">
                  <input
                    type="checkbox"
                    :checked="!!current.knowledge"
                    :disabled="!!current.deleted"
                    @change="toggle('knowledge')"
                  />
                  用于访客问答
                </label>
                <button type="button" @click="loadHistory">历史</button>
                <label class="move-option">
                  移动到
                  <select
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
                  </select>
                </label>
                <button v-if="current.knowledge" type="button" @click="retry">
                  重新同步
                </button>
                <button type="button" @click="toggle('deleted')">
                  {{ current.deleted ? "恢复" : "移入回收站" }}
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
                  month: "numeric",
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
              <strong>版本历史</strong>
              <button
                type="button"
                aria-label="关闭历史"
                @click="showHistory = false"
              >
                ×
              </button>
            </div>
            <div class="history-list">
              <p v-if="!history.length">暂无历史</p>
              <button
                v-for="h in history"
                :key="h.revision"
                type="button"
                @click="restore(h.revision)"
              >
                {{ h.title }}
                <small
                  >R{{ h.revision }} ·
                  {{ new Date(h.created_at).toLocaleString("zh-CN") }}</small
                >
              </button>
            </div>
          </aside>
        </template>
        <p v-else class="editor-empty">选择或新建笔记</p>
      </article>
    </div>

    <details v-if="knowledgeMode" class="retrieval-test">
      <summary>测试知识库检索</summary>
      <div class="retrieval-content">
        <form @submit.prevent="testSearch">
          <input
            v-model="previewQuestion"
            aria-label="测试问题"
            placeholder="输入测试问题…"
            maxlength="2000"
            required
          />
          <button type="submit" class="btn-primary" :disabled="working">
            检索
          </button>
        </form>
        <div v-for="r in results" :key="r.id" class="search-passage">
          <strong>{{ r.sourceName }}</strong>
          <small>{{ r.label }}</small>
          <p>{{ r.content }}</p>
        </div>
      </div>
    </details>
  </section>
</template>

<style scoped>
.notes-app {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--bg-void);
  color: var(--text-main);
  font-size: 13px;
}

.notes-app button,
.notes-app summary {
  cursor: pointer;
  font: inherit;
}

.notes-app input,
.notes-app select {
  color: var(--text-main);
  background: transparent;
}

.ribbon-tabs {
  height: 36px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 14px;
  border-bottom: 1px solid var(--border-hair);
  flex-shrink: 0;
  background: var(--bg-panel);
}

.ribbon-tabs > button {
  padding: 4px 10px;
  border-radius: 9999px;
  color: var(--text-sub);
  font-size: 12px;
}

.ribbon-tabs > button.active {
  background: var(--bg-hover);
  color: var(--accent);
  font-weight: 600;
}

.desktop-sidebar-toggle {
  margin-right: 6px;
  border: 1px solid var(--border-hair);
}

.save-state {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-dim);
}

.editor-toolbar {
  min-height: 38px;
  display: flex;
  align-items: center;
  padding: 3px 14px;
  gap: 3px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--border-hair);
  background: var(--bg-subtle);
}

.editor-toolbar > button {
  min-width: 28px;
  height: 28px;
  padding: 0 9px;
  border-radius: 6px;
  color: var(--text-sub);
}

.editor-toolbar > button:hover,
.editor-toolbar [aria-pressed="true"] {
  background: var(--bg-hover);
  color: var(--text-main);
}

.toolbar-divider {
  height: 18px;
  border-left: 1px solid var(--border-hair);
  margin: 0 5px;
}

.toolbar-spacer {
  flex: 1;
}

.color-control {
  position: relative;
  width: 26px;
  overflow: hidden;
  text-indent: -999px;
  display: inline-flex;
  align-items: center;
}

.color-control input {
  width: 22px;
  height: 22px;
  text-indent: 0;
  position: absolute;
  left: 0;
}

.file-button {
  position: relative;
  display: flex;
  align-items: center;
  cursor: pointer;
  padding: 6px 10px;
}

.file-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.import-menu,
.page-menu {
  position: relative;
}

.import-menu summary {
  padding: 5px 10px;
  list-style: none;
  color: var(--text-sub);
}

.popup {
  position: absolute;
  right: 0;
  top: calc(100% + 4px);
  min-width: 205px;
  background: var(--bg-panel);
  color: var(--text-main);
  border: 1px solid var(--border-hair);
  border-radius: 12px;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.45);
  z-index: 30;
  padding: 6px;
}

.popup button,
.popup > label {
  width: 100%;
  display: flex;
  gap: 8px;
  align-items: center;
  min-height: 34px;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 13px;
  text-align: left;
}

.popup button:hover,
.popup > label:hover {
  background: var(--bg-subtle);
}

.popup select {
  min-width: 0;
  max-width: 120px;
  border: 1px solid var(--border-hair);
  background: var(--bg-void);
  border-radius: 6px;
  padding: 3px;
}

.popup .move-option {
  justify-content: space-between;
}

.note-error {
  padding: 8px 14px;
  background: var(--danger-soft);
  color: var(--danger);
  border-bottom: 1px solid color-mix(in srgb, var(--danger) 35%, transparent);
  font-size: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.note-error button {
  text-decoration: underline;
  font-weight: 600;
}

.note-workspace {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 184px 216px minmax(0, 1fr);
}

.note-workspace.sidebar-collapsed {
  grid-template-columns: minmax(0, 1fr);
}

.note-workspace.sidebar-collapsed .note-tree,
.note-workspace.sidebar-collapsed .note-list {
  display: none;
}

.note-tree {
  display: flex;
  flex-direction: column;
  background: var(--bg-panel);
  border-right: 1px solid var(--border-hair);
  overflow: auto;
  padding-top: 10px;
}

.notebook-picker {
  display: flex;
  align-items: center;
  padding: 2px 10px 10px;
  gap: 4px;
}

.notebook-picker select {
  width: 100%;
  min-width: 0;
  font-weight: 600;
  font-size: 13px;
  border: 0;
}

.notebook-picker button {
  width: 26px;
  flex-shrink: 0;
  font-size: 16px;
  color: var(--text-dim);
}

.note-tree > button {
  min-height: 36px;
  text-align: left;
  padding: 7px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-sub);
}

.note-tree > button:hover {
  background: var(--bg-subtle);
  color: var(--text-main);
}

.note-tree .selected {
  background: var(--bg-hover);
  color: var(--text-main);
  font-weight: 600;
}

.section-mark {
  width: 4px;
  height: 14px;
  border-radius: 2px;
  background: var(--accent);
}

.note-tree .add-section {
  color: var(--text-dim);
  margin-top: 6px;
}

.tree-bottom {
  margin-top: auto;
  padding: 14px;
  font-size: 12px;
  color: var(--text-dim);
}

.note-list {
  display: flex;
  flex-direction: column;
  background: var(--bg-subtle);
  border-right: 1px solid var(--border-hair);
  min-height: 0;
  min-width: 0;
}

.page-search {
  margin: 10px 10px 6px;
  padding: 6px 10px;
  border: 1px solid var(--border-hair);
  border-radius: 8px;
  background: var(--bg-panel);
}

.page-search input {
  width: 100%;
  font-size: 12px;
  border: 0;
  outline: none;
}

.add-page {
  min-height: 34px;
  text-align: left;
  margin: 0 10px 4px;
  padding: 6px 8px;
  color: var(--accent);
  font-weight: 600;
  font-size: 12px;
}

.pages-scroll {
  overflow: auto;
  flex: 1;
}

.note-card {
  width: 100%;
  text-align: left;
  display: block;
  border-left: 2px solid transparent;
  border-bottom: 1px solid var(--border-hair);
  min-height: 42px;
  padding: 10px 14px;
}

.note-card strong {
  font-weight: 500;
  font-size: 13px;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.note-card:hover {
  background: var(--bg-subtle);
}

.note-card.selected {
  border-left-color: var(--accent);
  background: var(--bg-panel);
}

.knowledge-state {
  display: block;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ok);
  margin-top: 3px;
}

.list-empty {
  padding: 18px;
  font-size: 12px;
  color: var(--text-dim);
}

.note-document {
  min-width: 0;
  overflow: auto;
  position: relative;
  background: var(--bg-void);
}

.document-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 32px 0;
  font-size: 12px;
  color: var(--text-dim);
}

.page-menu {
  margin-left: auto;
}

.page-menu summary {
  list-style: none;
  font-size: 20px;
  line-height: 20px;
  padding: 2px 8px;
}

.page-content {
  padding: 14px 40px 64px;
  max-width: 860px;
  margin: 0 auto;
}

.note-title {
  width: 100%;
  border: 0;
  border-bottom: 1px solid var(--border-hair);
  padding: 0 0 10px;
  font-size: 26px;
  line-height: 1.4;
  font-weight: 700;
  letter-spacing: -0.02em;
  outline: none;
}

.page-date {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-dim);
  padding: 8px 0 14px;
}

.note-editor :deep(.tiptap) {
  outline: none;
  min-height: calc(100dvh - 300px);
  line-height: 1.8;
  font-size: 15px;
  overflow-wrap: anywhere;
  padding-top: 6px;
}

.note-editor :deep(p) {
  margin: 7px 0;
}

.note-editor :deep(h2) {
  font-size: 20px;
  font-weight: 600;
  margin: 18px 0 8px;
}

.note-editor :deep(ul) {
  list-style: disc;
  padding-left: 22px;
}

.note-editor :deep(ol) {
  list-style: decimal;
  padding-left: 22px;
}

.note-editor :deep(li[data-type="taskItem"]) {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.note-editor :deep(pre) {
  white-space: pre-wrap;
  background: var(--bg-panel);
  border: 1px solid var(--border-hair);
  border-radius: 8px;
  padding: 12px;
  font-family: var(--font-mono);
  font-size: 12px;
}

.note-editor :deep(blockquote) {
  border-left: 2px solid var(--accent);
  padding-left: 14px;
  color: var(--text-sub);
}

.note-editor :deep(table) {
  border-collapse: collapse;
  width: 100%;
  table-layout: fixed;
}

.note-editor :deep(td),
.note-editor :deep(th) {
  border: 1px solid var(--border-hair);
  padding: 7px;
  vertical-align: top;
}

.note-editor :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
}

.note-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  padding-top: 16px;
}

.note-attachments a {
  color: var(--accent);
  text-decoration: underline;
}

.editor-empty {
  color: var(--text-dim);
  padding: 64px 40px;
  text-align: center;
}

.history-panel {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 280px;
  max-width: 100%;
  border-left: 1px solid var(--border-hair);
  background: var(--bg-panel);
  z-index: 20;
  overflow: auto;
}

.history-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-hair);
}

.history-list button {
  padding: 12px 14px;
  width: 100%;
  text-align: left;
  border-bottom: 1px solid var(--border-hair);
}

.history-list small {
  display: block;
  color: var(--text-dim);
  font-family: var(--font-mono);
  font-size: 11px;
  margin-top: 4px;
}

.history-list > p {
  padding: 16px;
  color: var(--text-dim);
}

.retrieval-test {
  flex-shrink: 0;
  border-top: 1px solid var(--border-hair);
  background: var(--bg-panel);
  max-height: 36%;
  overflow: auto;
}

.retrieval-test > summary {
  padding: 9px 16px;
  color: var(--text-sub);
  font-weight: 600;
}

.retrieval-content {
  padding: 6px 16px 16px;
}

.retrieval-content form {
  display: flex;
  gap: 8px;
}

.retrieval-content input {
  border: 1px solid var(--border-hair);
  background: var(--bg-void);
  border-radius: 9999px;
  padding: 7px 14px;
  flex: 1;
  min-width: 0;
}

.search-passage {
  padding: 12px 0;
  border-bottom: 1px solid var(--border-hair);
}

.search-passage small {
  display: block;
  color: var(--text-dim);
  margin: 3px 0;
}

.search-passage p {
  white-space: pre-wrap;
}

.mobile-nav {
  display: none !important;
}

.category-create {
  padding: 10px 12px;
}

.category-create > label {
  display: block;
  font-size: 11px;
  color: var(--text-dim);
  margin-bottom: 5px;
}

.category-input-row {
  display: flex;
  align-items: center;
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: var(--bg-void);
}

.category-input-row input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: none;
  padding: 6px 8px;
  font-size: 12px;
}

.category-input-row button {
  flex-shrink: 0;
  width: 24px;
  min-height: 28px;
  color: var(--text-main);
}

.category-create > p {
  color: var(--danger);
  font-size: 11px;
  margin-top: 5px;
}

@media (max-width: 700px) {
  .mobile-nav {
    display: flex !important;
  }
  .desktop-sidebar-toggle {
    display: none !important;
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
  .page-content {
    padding: 14px 18px 48px;
  }
  .document-meta {
    padding-inline: 18px;
  }
}
</style>
