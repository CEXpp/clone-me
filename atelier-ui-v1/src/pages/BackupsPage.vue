<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, downloadBackup } from "../api";
import { date, notify, useAction } from "../shared";
import type { Backup, Notification } from "../types";

const { busy, error, run } = useAction();
const backups = ref<Backup[]>([]),
  notifications = ref<Notification[]>([]);
const operations = ref<{
  freeDiskBytes: number;
  schemaVersion: number;
  stalledJobs: number;
  uncertainCalls: number;
  failedNotifications: number;
  tlsExpiresAt: number;
} | null>(null);

async function load() {
  [backups.value, notifications.value] = await Promise.all([
    api<Backup[]>("/api/admin/backups"),
    api<Notification[]>("/api/admin/notifications"),
  ]);
  operations.value = await api("/api/admin/operations");
}

async function retryNotification(id: string) {
  await run(async () => {
    await api(
      "/api/admin/notifications/" + encodeURIComponent(id) + "/retry",
      "POST",
      {},
    );
    await load();
    notify("已重新排队");
  });
}

async function create() {
  await run(async () => {
    const result = await api<Backup>("/api/admin/backups", "POST", {});
    await load();
    notify("备份完成：" + result.name);
  });
}

onMounted(() => run(load));
</script>

<template>
  <section class="space-y-4">
    <div class="flex flex-wrap justify-between items-center gap-3">
      <h1 class="text-lg font-semibold">备份与系统状态</h1>
      <div class="flex gap-2">
        <button type="button" class="btn" :disabled="busy" @click="run(load)">
          刷新
        </button>
        <button
          type="button"
          class="btn-primary"
          :disabled="busy"
          @click="create"
        >
          {{ busy ? "打包中…" : "＋ 创建备份" }}
        </button>
      </div>
    </div>

    <p v-if="error" role="alert" class="error">{{ error }}</p>

    <div v-if="operations" class="grid grid-cols-2 sm:grid-cols-4 gap-3">
      <div class="solid-panel p-3.5 space-y-1">
        <span class="mono-label">可用磁盘</span>
        <p class="font-mono text-lg font-semibold">
          {{ (operations.freeDiskBytes / 1024 / 1024).toFixed(0) }} MB
        </p>
      </div>
      <div class="solid-panel p-3.5 space-y-1">
        <span class="mono-label">SCHEMA</span>
        <p class="font-mono text-lg font-semibold">
          V{{ operations.schemaVersion }}
        </p>
      </div>
      <div class="solid-panel p-3.5 space-y-1">
        <span class="mono-label">停滞任务</span>
        <p class="font-mono text-lg font-semibold">
          {{ operations.stalledJobs }}
        </p>
      </div>
      <div class="solid-panel p-3.5 space-y-1">
        <span class="mono-label">待对账</span>
        <p class="font-mono text-lg font-semibold">
          {{ operations.uncertainCalls }}
        </p>
      </div>
    </div>

    <section class="solid-panel p-4 space-y-3">
      <h2 class="text-sm font-semibold">一致性归档（{{ backups.length }}）</h2>
      <article
        v-for="backup in backups"
        :key="backup.name"
        class="flex flex-wrap justify-between items-center gap-3 border-t border-[var(--border-hair)] pt-3"
      >
        <div class="min-w-0">
          <h3 class="font-mono text-xs break-all">{{ backup.name }}</h3>
          <p class="muted text-xs">
            {{ (backup.bytes / 1024 / 1024).toFixed(2) }} MB
          </p>
        </div>
        <button
          type="button"
          class="btn"
          :disabled="busy"
          @click="run(() => downloadBackup(backup.name))"
        >
          下载 ZIP ↓
        </button>
      </article>
      <p v-if="!backups.length" class="empty">暂无备份</p>
    </section>

    <details class="fold-panel">
      <summary>
        <div class="flex items-center gap-2">
          <span>邮件通知记录</span>
          <span class="tag">{{ notifications.length }}</span>
        </div>
        <span class="fold-chevron">▸</span>
      </summary>
      <div class="fold-body space-y-3">
        <article
          v-for="item in notifications"
          :key="item.id"
          class="border-t border-[var(--border-hair)] first:border-t-0 first:pt-0 pt-3 space-y-1.5"
        >
          <div class="flex justify-between gap-2 text-sm">
            <span>{{ item.subject }}</span>
            <span class="tag">{{ item.status }}</span>
          </div>
          <p class="muted font-mono text-xs">
            {{ item.kind }} · {{ item.attempts }} 次 ·
            {{ date(item.created_at) }}
          </p>
          <button
            v-if="item.status === 'FAILED'"
            type="button"
            class="btn"
            :disabled="busy"
            @click="retryNotification(item.id)"
          >
            重发
          </button>
        </article>
        <p v-if="!notifications.length" class="empty">暂无通知事件</p>
      </div>
    </details>
  </section>
</template>
