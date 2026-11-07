<script setup lang="ts">
import { useId } from "vue";
const id = useId();
const ribbon = `heart-ribbon-${id}`;
const fold = `heart-fold-${id}`;
</script>
<template>
  <span class="heart-emblem" aria-hidden="true">
    <svg viewBox="0 0 48 48" fill="none">
      <defs>
        <linearGradient
          :id="ribbon"
          x1="8"
          y1="8"
          x2="39"
          y2="41"
          gradientUnits="userSpaceOnUse"
        >
          <stop stop-color="#c9f8ff" />
          <stop offset=".4" stop-color="#7de3ff" />
          <stop offset="1" stop-color="#447fdc" />
        </linearGradient>
        <linearGradient
          :id="fold"
          x1="24"
          y1="14"
          x2="26"
          y2="40"
          gradientUnits="userSpaceOnUse"
        >
          <stop stop-color="#9beaff" />
          <stop offset="1" stop-color="#2f68b7" stop-opacity=".15" />
        </linearGradient>
      </defs>
      <g class="emblem-beat">
        <path
          class="emblem-aura"
          d="M24 40C19 35.8 5.5 26 5.5 16.5C5.5 7.6 16.7 5 24 14.2C31.3 5 42.5 7.6 42.5 16.5C42.5 26 29 35.8 24 40Z"
          :stroke="`url(#${ribbon})`"
          stroke-width="6"
        />
        <path
          d="M24 40C19 35.8 5.5 26 5.5 16.5C5.5 7.6 16.7 5 24 14.2C31.3 5 42.5 7.6 42.5 16.5C42.5 26 29 35.8 24 40Z"
          :stroke="`url(#${ribbon})`"
          stroke-width="2.5"
          stroke-linejoin="round"
          fill="#6ecaff08"
        />
        <path
          class="emblem-fold"
          d="M24 14.2C29.5 20.4 33 24.1 28.4 31.6L24 40"
          :stroke="`url(#${fold})`"
          stroke-width="2"
          stroke-linecap="round"
        />
        <path
          class="emblem-stream"
          d="M24 40C19 35.8 5.5 26 5.5 16.5C5.5 7.6 16.7 5 24 14.2C31.3 5 42.5 7.6 42.5 16.5C42.5 26 29 35.8 24 40Z"
          pathLength="100"
          stroke="#e4fcff"
          stroke-width="2.7"
          stroke-linecap="round"
        />
      </g>
    </svg>
  </span>
</template>
<style>
.heart-emblem {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  isolation: isolate;
}
.neural-app .heart-emblem svg {
  width: 100%;
  height: 100%;
  overflow: visible;
  filter: drop-shadow(0 0 6px #6ddcff38);
}
.emblem-aura {
  opacity: 0.12;
  filter: blur(2px);
}
.emblem-beat {
  transform-box: view-box;
  transform-origin: 24px 25px;
  animation: emblem-beat 4.4s ease-in-out infinite;
}
.emblem-stream {
  stroke-dasharray: 10 90;
  stroke-dashoffset: 0;
  opacity: 0.9;
  animation: emblem-stream 5.8s linear infinite;
}
.emblem-fold {
  opacity: 0.8;
}
@keyframes emblem-beat {
  0%,
  32%,
  100% {
    transform: scale(1);
  }
  12% {
    transform: scale(1.065);
  }
  19% {
    transform: scale(0.985);
  }
  25% {
    transform: scale(1.035);
  }
}
@keyframes emblem-stream {
  to {
    stroke-dashoffset: -100;
  }
}
@media (max-width: 400px) {
  .heart-emblem {
    width: 32px;
    height: 32px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .emblem-beat,
  .emblem-stream {
    animation: none !important;
  }
}
</style>
