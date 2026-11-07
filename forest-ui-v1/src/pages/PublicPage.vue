<script setup lang="ts">
import VisitorWelcome from "../components/VisitorWelcome.vue";
import { profile } from "../shared";
import Icon from "../components/Icon.vue";
import NeuralCore from "../components/NeuralCore.vue";
defineProps<{ motion: boolean }>();
const entries = [
  {
    number: "01",
    icon: "user",
    title: "关于我",
    subtitle: "身份、经历与能力边界",
    to: "/explore/overview",
  },
  {
    number: "02",
    icon: "code",
    title: "项目档案",
    subtitle: "走进实践与技术取舍",
    to: "/explore/projects",
  },
  {
    number: "03",
    icon: "stack",
    title: "经历与能力",
    subtitle: "沿着足迹，了解更多",
    to: "/explore/experience",
  },
  {
    number: "04",
    icon: "mail",
    title: "与我联系",
    subtitle: "从一次交流开始连接",
    to: "/explore/contact",
  },
];
</script>
<template>
  <main class="home-stage">
    <section class="home-hero">
      <div class="hero-text">
        <div class="eyeline">
          <span class="status-dot"></span> HUMAN KNOWLEDGE. DIGITAL PRESENCE.
        </div>
        <h1>我的另一面。<br /><span>此刻，与你对话。</span></h1>
        <p>
          {{ profile.headline || "把经历、思考与创造，连接成一个可对话的我。"
          }}<br /><span class="hero-subline">{{
            profile.displayName
              ? profile.displayName + " 的 AI 数字分身"
              : "基于本人确认资料的 AI 数字分身"
          }}</span>
        </p>
        <div class="hero-cta">
          <RouterLink to="/chat" class="neon-button"
            ><Icon name="chat" />开启分身对话<Icon name="arrow" /></RouterLink
          ><span class="cta-note">一个问题，<br />开启更深入的了解。</span>
        </div>
        <div class="trust-row">
          <span><Icon name="shield" />本人资料驱动</span
          ><span><Icon name="link" />回答来源可追溯</span>
        </div>
      </div>
      <NeuralCore :motion="motion" />
      <VisitorWelcome dark class="home-welcome" />
      <div class="hero-side-label">PERSONAL DIGITAL TWIN / V.02</div>
    </section>
    <section class="entry-section" aria-label="探索个人档案">
      <div class="entry-heading">
        <span>EXPLORE MY WORLD</span
        ><span>选择一个入口，继续探索 <Icon name="arrow" /></span>
      </div>
      <div class="entry-grid">
        <RouterLink
          v-for="entry in entries"
          :key="entry.number"
          :to="entry.to"
          class="entry-card"
          ><div class="entry-card-top">
            <Icon :name="entry.icon" /><span>{{ entry.number }} /</span>
          </div>
          <div class="entry-card-bottom">
            <div>
              <h2>{{ entry.title }}</h2>
              <p>{{ entry.subtitle }}</p>
            </div>
            <span class="entry-arrow"><Icon name="arrow" /></span></div
        ></RouterLink>
      </div>
    </section>
    <footer class="home-footer">
      <span>一颗心 © 2026</span
      ><span>AI 文字分身 · 以真实资料为基础，回答可能存在偏差</span
      ><span>MADE OF MEMORIES. BUILT FOR CONNECTION.</span>
    </footer>
  </main>
</template>

<style scoped>
.home-welcome {
  position: absolute;
  right: 0;
  bottom: 6px;
  z-index: 3;
  width: 250px;
}
@media (max-width: 1100px) and (min-width: 761px) {
  .home-welcome {
    width: 215px;
    right: -3%;
    bottom: 0;
  }
}
@media (max-width: 760px) {
  :deep(.core-visual) {
    top: 345px;
    bottom: auto;
    height: 180px;
  }
  .hero-text {
    padding-bottom: 210px;
  }
  .home-welcome {
    position: relative;
    right: auto;
    bottom: auto;
    width: min(300px, 100%);
    margin: -8px 0 24px;
    justify-self: start;
  }
}
</style>
