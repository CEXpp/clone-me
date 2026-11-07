<script setup lang="ts">
/**
 * 展示备份和通知状态，提供主动备份与受保护下载。
 * 下载通过携带认证 Cookie 的请求获取文件，不生成公开可访问的备份地址。
 */
import { onMounted, ref } from "vue";
import { api, downloadBackup } from "../api";
import type { Backup, Notification } from "../types";
import { useAction, date, notify } from "../shared";
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
    notify("通知已重新排队");
  });
}
async function create() {
  // 手动点击才创建新备份；页面 load 只读列表和状态，刷新页面不会重复创建备份。
  await run(async () => {
    const result = await api<Backup>("/api/admin/backups", "POST", {});
    await load();
    notify("一致性备份已创建 " + result.name);
  });
}
onMounted(() => run(load));
</script>
<template>
  <section class="space-y-6">
    <div class="flex justify-between items-center gap-3">
      <h2 class="section-title">通知与备份</h2>
      <button class="btn" :disabled="busy" @click="run(load)">刷新状态</button>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <section v-if="operations" class="panel space-y-2">
      <h3 class="section-title">运行状态</h3>
      <p class="muted">
        可用磁盘 {{ (operations.freeDiskBytes / 1024 / 1024).toFixed(0) }} MB ·
        数据库版本 {{ operations.schemaVersion }} · 停滞任务
        {{ operations.stalledJobs }} · 待核对调用
        {{ operations.uncertainCalls }}
      </p>
      <p class="muted">
        通知失败 {{ operations.failedNotifications }} · 证书到期
        {{
          operations.tlsExpiresAt
            ? date(operations.tlsExpiresAt)
            : "尚未由主机上报，请检查续期任务"
        }}
      </p>
    </section>
    <section class="panel space-y-4">
      <div class="flex justify-between items-center flex-wrap gap-3">
        <h3 class="section-title">一致性备份</h3>
        <button class="btn-primary" :disabled="busy" @click="create">
          {{ busy ? "处理中…" : "＋ 创建备份" }}
        </button>
      </div>
      <p class="notice">
        每日自动备份，最多保留 7 份且总量不超过
        500MB。包含知识、原始资料与必要账本，不包含访客聊天、管理员凭据、API
        密钥和日志。下载后请存放在私人位置。
      </p>
      <article
        v-for="backup in backups"
        :key="backup.name"
        class="flex flex-wrap justify-between gap-3 border-t border-slate-200 pt-4"
      >
        <div class="min-w-0">
          <h4 class="font-mono text-xs break-all">{{ backup.name }}</h4>
          <p class="muted mt-1">
            {{ (backup.bytes / 1024 / 1024).toFixed(2) }} MB
          </p>
        </div>
        <button
          class="btn"
          :disabled="busy"
          @click="run(() => downloadBackup(backup.name))"
        >
          下载 ZIP ↓
        </button>
      </article>
      <p v-if="!backups.length" class="empty">还没有备份。</p>
    </section>
    <section class="panel space-y-4">
      <h3 class="section-title">邮件通知记录</h3>
      <p class="muted">
        需先配置 SMTP 与收件人；PENDING 表示尚未送达，自动最多尝试 3 次，FAILED
        表示自动尝试已结束，可以修复配置后主动重发。
      </p>
      <article
        v-for="item in notifications"
        :key="item.id"
        class="border-t border-slate-200 pt-4 space-y-2"
      >
        <div class="flex justify-between gap-3">
          <h4 class="text-sm font-medium">{{ item.subject }}</h4>
          <span class="tag">{{ item.status }}</span>
        </div>
        <p class="muted">
          {{ item.kind }} · 已尝试 {{ item.attempts }} 次 ·
          {{ date(item.created_at) }}
        </p>
        <p v-if="item.last_attempt" class="muted">
          最近尝试：{{ date(item.last_attempt) }}
        </p>
        <button
          v-if="item.status === 'FAILED'"
          class="btn"
          :disabled="busy"
          @click="retryNotification(item.id)"
        >
          重新发送通知
        </button>
      </article>
      <p v-if="!notifications.length" class="empty">暂无通知事件。</p>
    </section>
  </section>
</template>
