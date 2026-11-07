<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { api } from "../api";
import { profile, normalizeProfile, errorText, safeUrl } from "../shared";
import Icon from "../components/Icon.vue";
const route = useRoute();
const error = ref(""),
  loading = ref(false);
const sections = [
  ["overview", "关于我"],
  ["projects", "项目档案"],
  ["experience", "经历与能力"],
  ["contact", "与我联系"],
];
const section = computed(() => String(route.params.section));
const heading = computed(
  () => sections.find((s) => s[0] === section.value)?.[1] || "个人档案",
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    profile.value = normalizeProfile(await api("/api/public/profile"));
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <main class="inner-page">
    <RouterLink to="/" class="back-link"
      ><Icon name="back" />返回概览</RouterLink
    >
    <div class="inner-heading">
      <div>
        <div class="eyeline">THE HUMAN BEHIND THE AI</div>
        <h1>{{ heading }}<span>.</span></h1>
      </div>
      <RouterLink to="/chat" class="outline-button"
        ><Icon name="chat" />问问我的分身<Icon name="arrow"
      /></RouterLink>
    </div>
    <nav class="tab-nav" aria-label="资料类别">
      <RouterLink v-for="s in sections" :key="s[0]" :to="'/explore/' + s[0]">{{
        s[1]
      }}</RouterLink>
    </nav>
    <p v-if="error" class="error-banner" role="alert">
      {{ error }} <button @click="load">重新加载</button>
    </p>
    <p v-if="loading" class="loading-text" role="status">正在读取已发布资料…</p>
    <section v-else class="explore-content">
      <template v-if="section === 'overview'"
        ><div class="profile-intro">
          <span class="profile-monogram"><Icon name="user" /></span>
          <div>
            <h2>{{ profile.displayName || "关于真实的我" }}</h2>
            <p>{{ profile.headline || "个人介绍尚未发布。" }}</p>
          </div>
        </div>
        <p class="bio-copy">
          {{
            profile.bio ||
            "这里将呈现本人确认的介绍。你也可以浏览项目，或向数字分身提问。"
          }}
        </p>
        <div class="detail-note">
          <Icon name="shield" />
          <p>
            {{
              profile.aiDisclosure ||
              "这是 AI 文字分身，依据本人确认资料回答，不代表本人作出新的承诺。"
            }}
          </p>
        </div>
        <div v-if="profile.skills.length" class="detail-grid">
          <article
            v-for="skill in profile.skills"
            :key="skill.name"
            class="detail-card"
          >
            <span class="small-tag">{{ skill.level }}</span>
            <h3>{{ skill.name }}</h3>
            <p>{{ skill.summary }}</p>
          </article>
        </div></template
      >
      <template v-else-if="section === 'projects'"
        ><div v-if="profile.projects.length" class="detail-grid">
          <article
            v-for="(project, index) in profile.projects"
            :key="project.id || project.name"
            class="detail-card"
          >
            <div class="detail-card-top">
              <span class="eyeline"
                >PROJECT / {{ String(index + 1).padStart(2, "0") }}</span
              ><Icon name="code" />
            </div>
            <h3>{{ project.name }}</h3>
            <span class="small-tag">{{ project.role }}</span>
            <p>{{ project.summary }}</p>
            <div class="skill-tags">
              <span v-for="tag in project.stack" :key="tag">{{ tag }}</span>
            </div>
            <div class="detail-links">
              <template v-for="link in project.links" :key="link.url"
                ><a
                  v-if="safeUrl(link.url)"
                  :href="safeUrl(link.url)"
                  target="_blank"
                  rel="noopener noreferrer"
                  >{{ link.label }} ↗</a
                ></template
              >
            </div>
            <RouterLink
              class="card-question"
              :to="{
                path: '/chat',
                query: {
                  q:
                    '请介绍「' +
                    project.name +
                    '」中你个人负责的部分和技术取舍。',
                },
              }"
              >聊聊这个项目<Icon name="arrow"
            /></RouterLink>
          </article>
        </div>
        <div v-else class="empty-panel">
          <Icon name="code" />
          <h2>项目故事，准备中。</h2>
          <p>本人发布项目资料后，会在这里展示。</p>
        </div></template
      >
      <template v-else-if="section === 'experience'"
        ><div v-if="profile.experiences.length" class="timeline">
          <article
            v-for="item in profile.experiences"
            :key="item.title + item.period"
          >
            <span class="eyeline">{{ item.period }}</span>
            <h3>{{ item.title }}</h3>
            <span class="small-tag">{{ item.organization }}</span>
            <p>{{ item.summary }}</p>
          </article>
        </div>
        <div v-else class="empty-panel">
          <Icon name="stack" />
          <h2>每一段经历，都有故事。</h2>
          <p>经历资料尚未发布。</p>
        </div>
        <div v-if="profile.skills.length" class="detail-grid">
          <article
            v-for="skill in profile.skills"
            :key="skill.name"
            class="detail-card"
          >
            <span class="small-tag">{{ skill.level }}</span>
            <h3>{{ skill.name }}</h3>
            <p>{{ skill.summary }}</p>
          </article>
        </div></template
      >
      <template v-else
        ><div class="contact-intro">
          <h2>让连接，从这里开始。</h2>
          <p>以下为本人选择公开的联系方式。</p>
        </div>
        <div v-if="profile.contacts.length" class="contact-links">
          <template v-for="link in profile.contacts" :key="link.url"
            ><a
              v-if="safeUrl(link.url)"
              :href="safeUrl(link.url)"
              target="_blank"
              rel="noopener noreferrer"
              ><Icon name="mail" /><span>{{ link.label }}</span
              ><Icon name="arrow" /></a
          ></template>
        </div>
        <div v-else class="detail-note">
          <Icon name="mail" />
          <p>暂未提供公开联系方式。</p>
        </div></template
      >
    </section>
  </main>
</template>
