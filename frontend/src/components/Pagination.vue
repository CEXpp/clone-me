<script setup lang="ts">
// props 由父页传入，emit 通知父页翻页；这个组件只显示按钮，不自行请求 API 或保存游标。
defineProps<{ page: number; hasNext: boolean; busy: boolean }>();
defineEmits<{ next: []; back: [] }>();
</script>
<template>
  <nav class="flex justify-end gap-3 items-center" aria-label="列表分页">
    <span class="muted">第 {{ page }} 页</span>
    <button class="btn" :disabled="busy || page === 1" @click="$emit('back')">
      上一页
    </button>
    <button class="btn" :disabled="busy || !hasNext" @click="$emit('next')">
      下一页
    </button>
  </nav>
</template>
