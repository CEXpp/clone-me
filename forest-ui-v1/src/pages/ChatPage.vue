<script setup lang="ts">
import { onMounted } from "vue";
import { useRoute } from "vue-router";
import { profile } from "../shared";
import { useVisitorChat } from "../composables/useVisitorChat";
import Icon from "../components/Icon.vue";
import Drawer from "../components/Drawer.vue";
import Challenge from "../components/Challenge.vue";
const route = useRoute();
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
onMounted(() => {
  if (typeof route.query.q === "string")
    input.value = route.query.q.slice(0, 2000);
});
</script>
<template>
  <main class="chat-page">
    <aside class="chat-sidebar">
      <RouterLink to="/" class="back-link"
        ><Icon name="back" />返回概览</RouterLink
      >
      <div class="eyeline">MEET MY DIGITAL SELF</div>
      <h1>从一个问题，<br /><span>走近真实的我。</span></h1>
      <p>关于我的经历、项目与技术取舍，<br />都可以从这里开始。</p>
      <div class="chat-sidebar-note">
        <Icon name="shield" />
        <div>
          <strong>有依据，才回答。</strong>
          <p>回答基于本人确认资料；资料不足时，会明确说明。</p>
        </div>
      </div>
      <RouterLink to="/explore/projects" class="outline-button"
        >先看看项目<Icon name="arrow"
      /></RouterLink>
    </aside>
    <section class="chat-panel" aria-label="数字分身对话">
      <header class="chat-panel-header">
        <div class="assistant-avatar"><Icon name="bolt" /></div>
        <div>
          <strong>{{ profile.displayName || "一颗心" }} <span>AI</span></strong
          ><small>{{
            checking ? "正在恢复会话" : "个人资料驱动 · 来源可追溯"
          }}</small>
        </div>
        <button
          :disabled="busy || !session"
          class="new-chat-button"
          @click="clearOpen = true"
        >
          <Icon name="plus" /><span>新对话</span>
        </button>
      </header>
      <div class="chat-transcript" aria-live="polite">
        <div v-if="!messages.length && !pending" class="chat-empty">
          <div class="welcome-symbol"><Icon name="bolt" /></div>
          <span class="eyeline">YOUR NEXT QUESTION STARTS HERE</span>
          <h2>你好，想了解什么？</h2>
          <p>从我的经历、项目，或一次技术选择开始。</p>
          <div class="quick-questions">
            <button
              v-for="q in profile.quickQuestions.slice(0, 3)"
              :key="q"
              @click="chooseQuestion(q)"
            >
              {{ q }}<Icon name="arrow" />
            </button>
          </div>
        </div>
        <article
          v-for="(message, index) in messages"
          :key="index"
          class="chat-message"
          :class="message.role"
        >
          <span class="message-role">{{
            message.role === "user" ? "YOU" : "一颗心 / AI"
          }}</span>
          <p>{{ message.content }}</p>
          <p
            v-if="message.status && message.status !== 'ANSWERED'"
            class="answer-note"
          >
            当前依据不足以进一步展开。
          </p>
          <details v-if="message.sources?.length">
            <summary>
              <Icon name="link" />查看依据 · {{ message.sources.length }}
            </summary>
            <p v-for="source in message.sources" :key="source.id">
              {{ source.title }} / {{ source.location }}
            </p>
          </details>
          <button
            v-if="message.role === 'assistant'"
            class="copy-control"
            @click="copy(message.content)"
          >
            <Icon name="copy" />复制回答
          </button>
        </article>
        <article v-if="pending" class="chat-message user">
          <span class="message-role">YOU</span>
          <p>{{ pending }}</p>
        </article>
        <div v-if="busy" role="status" class="thinking-indicator">
          <i></i><i></i><i></i><span>正在生成并核验回答…</span>
        </div>
        <div ref="bottom"></div>
      </div>
      <div v-if="error" role="alert" class="error-banner">
        {{ error
        }}<button :disabled="busy || recovering" @click="recover">
          恢复记录
        </button>
      </div>
      <div v-if="profileError" role="alert" class="error-banner">
        资料加载失败 <button @click="refreshProfile">重试</button>
      </div>
      <Challenge
        v-if="challengeNeeded && siteKey"
        :key="challengeKey"
        :site-key="siteKey"
        @token="(value) => (challengeToken = value)"
      />
      <p v-if="profile.chatEnabled === false" class="error-banner">
        聊天暂未开放，你仍可浏览个人档案。
      </p>
      <form class="neural-composer" @submit.prevent="send">
        <label for="chat-input" class="sr-only">向数字分身提问</label
        ><textarea
          id="chat-input"
          ref="composer"
          v-model="input"
          maxlength="2000"
          rows="2"
          :disabled="checking || busy || profile.chatEnabled === false"
          placeholder="写下你的问题，开始了解我…"
          @keydown="keydown"
        ></textarea>
        <div>
          <span
            >{{ input.length }} / 2000
            <span class="keyboard-hint"> · Enter 发送</span></span
          ><button
            class="send-button"
            :disabled="
              busy ||
              checking ||
              !input.trim() ||
              profile.chatEnabled === false ||
              (challengeNeeded && !challengeToken)
            "
          >
            {{ busy ? "准备中" : "发送" }}<Icon name="send" />
          </button>
        </div>
      </form>
      <p class="chat-footnote">AI 回答可能存在偏差 · 同一浏览器会话保留 5 天</p>
    </section>
    <Drawer
      :open="clearOpen"
      title="开始新的对话？"
      center
      @close="clearOpen = false"
      ><p>将清除当前浏览器的这段聊天记录。</p>
      <div class="flex justify-end gap-3">
        <button class="btn" @click="clearOpen = false">取消</button
        ><button class="btn-primary" :disabled="busy" @click="clear">
          确认清除
        </button>
      </div></Drawer
    >
  </main>
</template>
