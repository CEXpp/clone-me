<script setup lang="ts">
defineProps<{ name: string; size?: number }>();
</script>

<template>
  <svg
    :width="size || 16"
    :height="size || 16"
    viewBox="0 0 20 20"
    fill="none"
    stroke="currentColor"
    stroke-width="1.5"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    class="shrink-0"
  >
    <template v-if="name === 'arrow'">
      <path d="M4 10h12M11 5l5 5-5 5" />
    </template>
    <template v-else-if="name === 'back'">
      <path d="M16 10H4M9 5l-5 5 5 5" />
    </template>
    <template v-else-if="name === 'book'">
      <path d="M3.5 4.5h5.5a2 2 0 0 1 2 2v10a1.5 1.5 0 0 0-1.5-1.5H3.5z" />
      <path d="M16.5 4.5H11a2 2 0 0 0-2 2v10a1.5 1.5 0 0 1 1.5-1.5h6z" />
    </template>
    <template v-else-if="name === 'chat'">
      <path d="M3.5 4.5h13v9.5H8l-3.5 2.8V14H3.5z" />
    </template>
    <template v-else-if="name === 'shield'">
      <path d="M10 2.5 16 5v4.8c0 3.7-2.6 6.7-6 7.7-3.4-1-6-4-6-7.7V5z" />
      <path d="m7.5 10 1.8 1.8 3.4-3.6" />
    </template>
    <template v-else-if="name === 'sun'">
      <circle cx="10" cy="10" r="3.5" />
      <path
        d="M10 2.5v1.8M10 15.7v1.8M2.5 10h1.8M15.7 10h1.8M4.7 4.7l1.3 1.3M14 14l1.3 1.3M15.3 4.7 14 6M6 14l-1.3 1.3"
      />
    </template>
    <template v-else-if="name === 'moon'">
      <path d="M14.5 12.2A5.5 5.5 0 0 1 7.8 5.5a5.5 5.5 0 1 0 6.7 6.7z" />
    </template>
    <template v-else-if="name === 'pause'">
      <path d="M7.5 5v10M12.5 5v10" />
    </template>
    <template v-else-if="name === 'play'">
      <path d="m6.5 4.5 9 5.5-9 5.5z" />
    </template>
    <template v-else-if="name === 'copy'">
      <rect x="7" y="7" width="9" height="9" rx="1.5" />
      <path d="M13 7V4.5a1 1 0 0 0-1-1H4.5a1 1 0 0 0-1 1V12a1 1 0 0 0 1 1H7" />
    </template>
    <template v-else-if="name === 'link'">
      <path
        d="M8.5 11.5 11.5 8.5M7 14l-1.5 1.5a2.5 2.5 0 0 1-3.5-3.5L4.5 9.5M13 6l1.5-1.5a2.5 2.5 0 0 1 3.5 3.5L15.5 10.5"
      />
    </template>
    <template v-else-if="name === 'plus'">
      <path d="M10 4v12M4 10h12" />
    </template>
    <template v-else-if="name === 'close'">
      <path d="m5 5 10 10M15 5 5 15" />
    </template>
    <template v-else-if="name === 'send'">
      <path d="M10 15.5V4.5M5.5 9 10 4.5 14.5 9" />
    </template>
    <template v-else>
      <circle cx="10" cy="10" r="5" />
    </template>
  </svg>
</template>
