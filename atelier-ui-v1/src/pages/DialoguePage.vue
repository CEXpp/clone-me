<script setup lang="ts">
import { ref } from "vue";
import { date, profile } from "../shared";
import { useVisitorChat } from "../composables/useVisitorChat";
import type { Message } from "../types";
import Challenge from "../components/Challenge.vue";
import Drawer from "../components/Drawer.vue";
import Icon from "../components/Icon.vue";

const {
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
} = useVisitorChat();

const inspectedMessage = ref<Message | null>(null);
const promptMenu = ref<HTMLDetailsElement>();

function pickPrompt(q: string) {
  if (promptMenu.value) promptMenu.value.open = false;
  void chooseQuestion(q);
}
</script>

<template>
  <main class="spatial-page">
    <section class="glass-panel chat-stream-card" aria-label="数字分身对话">
      <header
        class="flex items-center justify-between gap-2 px-4 py-3 border-b border-[var(--border-hair)]"
      >
        <div class="flex items-center gap-2.5 min-w-0">
          <RouterLink
            to="/"
            class="btn-ghost !py-1 !px-2.5 text-xs"
            aria-label="返回首页"
          >
            <Icon name="back" :size="14" />
            <span>首页</span>
          </RouterLink>
          <span
            class="status-dot"
            :class="{ offline: profile.chatEnabled === false }"
          ></span>
          <h1 class="text-sm font-semibold truncate">分身对话</h1>
          <span
            v-if="session"
            class="mono-label hidden sm:inline"
            :title="'会话保留至 ' + date(session.expiresAt)"
          >
            · 5D SESSION
          </span>
        </div>

        <div class="flex items-center gap-1.5">
          <details
            v-if="profile.quickQuestions.length"
            ref="promptMenu"
            class="relative"
          >
            <summary class="btn !py-1 !px-3 text-xs list-none cursor-pointer">
              ✦ 灵感
            </summary>
            <div class="prompt-popover">
              <button
                v-for="q in profile.quickQuestions"
                :key="q"
                type="button"
                @click="pickPrompt(q)"
              >
                {{ q }}
              </button>
            </div>
          </details>

          <button
            v-if="session"
            type="button"
            class="btn !py-1 !px-3 text-xs"
            aria-label="新开对谈"
            :disabled="busy"
            @click="clearOpen = true"
          >
            新对话
          </button>
        </div>
      </header>

      <div class="chat-messages" aria-live="polite">
        <div
          v-if="!messages.length && !pending"
          class="my-auto text-center space-y-2 py-12"
        >
          <p class="mono-label">READY</p>
          <p class="muted">输入任意问题开始对话</p>
        </div>

        <article
          v-for="(message, index) in messages"
          :key="index"
          :class="message.role === 'user' ? 'bubble-user' : 'bubble-ai'"
        >
          <div class="prose-text">{{ message.content }}</div>

          <div
            v-if="message.role === 'assistant'"
            class="mt-3 pt-2 border-t border-[var(--border-hair)] flex flex-wrap items-center gap-2 text-xs"
          >
            <button
              v-if="message.sources?.length"
              type="button"
              class="tag tag-ok cursor-pointer"
              @click="inspectedMessage = message"
            >
              依据 · {{ message.sources.length }}
            </button>
            <span
              v-else-if="message.status && message.status !== 'ANSWERED'"
              class="tag tag-warn"
            >
              依据不足
            </span>

            <button
              type="button"
              class="btn-ghost !py-1 !px-2 text-xs ml-auto"
              @click="copy(message.content)"
            >
              <Icon name="copy" :size="12" />
              <span>复制</span>
            </button>
          </div>
        </article>

        <div v-if="pending" class="space-y-3 flex flex-col">
          <div class="bubble-user prose-text">{{ pending }}</div>
          <p role="status" class="mono-label pl-1">● 检索与核验中…</p>
        </div>

        <div ref="bottom"></div>
      </div>

      <footer
        class="p-3 sm:p-4 border-t border-[var(--border-hair)] space-y-2.5 safe-bottom"
      >
        <div v-if="profileError" role="alert" class="error">
          {{ profileError }}
          <button type="button" class="underline ml-2" @click="refreshProfile">
            重试
          </button>
        </div>

        <div v-if="error" role="alert" class="error flex flex-wrap gap-2">
          <span>{{ error }}</span>
          <button
            type="button"
            class="underline font-medium"
            :disabled="busy || recovering"
            @click="recover"
          >
            先恢复聊天记录
          </button>
        </div>

        <Challenge
          v-if="challengeNeeded && siteKey"
          :key="challengeKey"
          :site-key="siteKey"
          @token="(value) => (challengeToken = value)"
        />

        <p v-if="profile.chatEnabled === false" class="notice text-xs">
          对话功能已暂停
        </p>

        <form
          class="flex items-end gap-2 bg-[var(--bg-subtle)] border border-[var(--border-hair)] rounded-2xl p-2 focus-within:border-[var(--border-glow)]"
          @submit.prevent="send"
        >
          <label for="chat-input" class="sr-only">向文字分身提问</label>
          <textarea
            id="chat-input"
            ref="composer"
            v-model="input"
            rows="1"
            maxlength="2000"
            :disabled="checking || busy || profile.chatEnabled === false"
            class="flex-1 bg-transparent resize-none border-0 px-2 py-1.5 text-sm leading-relaxed text-[var(--text-main)] placeholder-[var(--text-dim)] focus:outline-none"
            placeholder="输入问题 (Enter 发送)…"
            @keydown="keydown"
          ></textarea>
          <button
            type="submit"
            class="btn-primary !py-2 !px-4"
            :disabled="
              busy ||
              checking ||
              profile.chatEnabled === false ||
              !input.trim() ||
              (challengeNeeded && !challengeToken)
            "
          >
            {{ checking ? "恢复中" : busy ? "核验中" : "发送 ↑" }}
          </button>
        </form>
      </footer>
    </section>

    <!-- 来源依据抽屉 -->
    <Drawer
      :open="!!inspectedMessage"
      title="引用依据"
      @close="inspectedMessage = null"
    >
      <template v-if="inspectedMessage">
        <div v-if="inspectedMessage.sources?.length" class="space-y-2.5">
          <div
            v-for="(s, idx) in inspectedMessage.sources"
            :key="s.id"
            class="solid-panel p-3.5 space-y-1"
          >
            <span class="mono-label">SOURCE {{ idx + 1 }}</span>
            <p class="text-sm font-semibold">{{ s.title }}</p>
            <p class="muted text-xs break-all">{{ s.location }}</p>
          </div>
        </div>
        <p v-if="inspectedMessage.requestId" class="mono-label break-all pt-2">
          ID: {{ inspectedMessage.requestId }}
        </p>
      </template>
    </Drawer>

    <!-- 清除会话确认 -->
    <Drawer
      :open="clearOpen"
      title="开始新对话？"
      center
      @close="clearOpen = false"
    >
      <p class="muted">将清除当前浏览器的对谈记录。</p>
      <div class="flex justify-end gap-2 pt-2">
        <button type="button" class="btn" @click="clearOpen = false">
          取消
        </button>
        <button
          type="button"
          class="btn-primary"
          :disabled="busy"
          @click="clear"
        >
          确认清除
        </button>
      </div>
    </Drawer>
  </main>
</template>

<style scoped>
.prompt-popover {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  width: 260px;
  max-width: 82vw;
  padding: 6px;
  border-radius: 14px;
  background: var(--bg-panel);
  border: 1px solid var(--border-hair);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.45);
  z-index: 30;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.prompt-popover button {
  text-align: left;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--text-sub);
  cursor: pointer;
}

.prompt-popover button:hover {
  background: var(--bg-subtle);
  color: var(--text-main);
}
</style>
