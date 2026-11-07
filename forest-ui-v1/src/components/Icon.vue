<script setup lang="ts">
defineProps<{ name: string }>();
const paths: Record<string, string> = {
  heart:
    "M12 21C9 18 2 13.5 2 7.5A5.5 5.5 0 0 1 12 4.4 5.5 5.5 0 0 1 22 7.5C22 13.5 15 18 12 21Z",
  arrow: "M7 17 17 7M7 7h10v10",
  back: "m12 5-7 7 7 7M5 12h14",
  chat: "M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-2 2V11.5a9.5 9.5 0 0 1 19 0ZM7 9h10M7 13h6",
  grid: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  user: "M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM4 21v-2a8 8 0 0 1 16 0v2",
  code: "m8 5-7 7 7 7M16 5l7 7-7 7m-3-18-2 22",
  stack: "m12 2 10 5-10 5L2 7l10-5ZM2 12l10 5 10-5M2 17l10 5 10-5",
  mail: "M3 5h18v14H3zM3 5l9 8 9-8",
  send: "m12 4 7 7M12 4l-7 7M12 4v17",
  pause: "M9 5v14M15 5v14",
  play: "m8 4 12 8-12 8V4Z",
  plus: "M12 5v14M5 12h14",
  shield: "m12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6l9-4Zm-4 10 3 3 5-6",
  copy: "M8 8h13v13H8zM16 8V3H3v13h5",
  bolt: "m14 2-9 12h7l-2 8 9-12h-7l2-8Z",
  close: "m6 6 12 12M6 18 18 6",
  link: "m10 14 4-4M8 16l-2 2a3 3 0 0 1-4-4l5-5a3 3 0 0 1 4 0M16 8l2-2a3 3 0 0 1 4 4l-5 5a3 3 0 0 1-4 0",
};
</script>
<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.5"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.arrow" />
  </svg>
</template>
