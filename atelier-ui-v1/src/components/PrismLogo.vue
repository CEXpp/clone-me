<script setup lang="ts">
defineProps<{ size?: number }>();
</script>

<template>
  <svg
    :width="size || 24"
    :height="size || 24"
    viewBox="0 0 32 32"
    fill="none"
    aria-hidden="true"
    class="shrink-0"
  >
    <polygon
      points="16,3 28,16 16,29 4,16"
      stroke="var(--accent)"
      stroke-width="1.6"
      fill="var(--accent)"
      fill-opacity="0.12"
    />
    <polygon
      points="16,9 22.5,16 16,23 9.5,16"
      stroke="var(--accent-violet)"
      stroke-width="1.2"
    />
    <circle cx="16" cy="16" r="2.1" fill="var(--text-main)" />
  </svg>
</template>
