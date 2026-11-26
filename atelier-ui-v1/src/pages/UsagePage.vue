<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import { usePaged } from "../composables/usePaged";
import { date, money, notify, useAction } from "../shared";
import type { Call, Providers, Usage } from "../types";
import Drawer from "../components/Drawer.vue";
import Pagination from "../components/Pagination.vue";

const { busy, error, run } = useAction();
const pages = usePaged<Call>("/api/admin/calls/page");
const calls = pages.items,
  callStatus = pages.status;
const usage = ref<Usage | null>(null),
  providers = ref<Providers | null>(null),
  selectedProvider = ref("disabled"),
  threshold = ref(0.85),
  callId = ref(""),
  actual = ref(""),
  paidConfirm = ref(false);

async function load() {
  [usage.value, providers.value] = await Promise.all([
    api<Usage>("/api/admin/usage"),
    api<Providers>("/api/admin/providers"),
  ]);
  await pages.load();
  selectedProvider.value = providers.value.decision.provider;
  setThreshold();
}

function setThreshold() {
  threshold.value =
    Number(
      selectedProvider.value === "bailian"
        ? providers.value?.decision.bailianThreshold
        : providers.value?.decision.typesafeThreshold,
    ) || 0.85;
}

async function togglePaid() {
  const current = providers.value;
  if (!current) return;
  await run(async () => {
    await api("/api/admin/providers/paid", "PUT", {
      enabled: !current.paidCallsEnabled,
    });
    paidConfirm.value = false;
    await load();
    notify("付费状态已更新");
  });
}

async function saveDecision() {
  await run(async () => {
    await api("/api/admin/providers/decision", "PUT", {
      provider: selectedProvider.value,
      ...(selectedProvider.value === "disabled"
        ? {}
        : { threshold: threshold.value }),
    });
    await load();
    notify("决策配置已保存");
  });
}

async function reconcile() {
  await run(async () => {
    const value = Number(actual.value);
    if (
      actual.value.trim() === "" ||
      !Number.isFinite(value) ||
      value < 0 ||
      value > 100
    ) {
      throw new Error("请填入 0–100 元之间的金额");
    }
    await api(
      "/api/admin/calls/" + encodeURIComponent(callId.value) + "/reconcile",
      "POST",
      { actualCny: value },
    );
    callId.value = "";
    await load();
    notify("已核对账单");
  });
}

const metrics = computed(() =>
  usage.value
    ? [
        ["今日已用", usage.value.today.spent],
        ["今日预留", usage.value.today.reserved],
        ["日上限", usage.value.dailyLimit],
        ["累计估算", usage.value.lifetimeEstimated],
      ]
    : [],
);

onMounted(() => run(load));
</script>

<template>
  <section class="space-y-4">
    <div class="flex flex-wrap justify-between gap-3 items-center">
      <h1 class="text-lg font-semibold">费用与模型</h1>
      <button type="button" class="btn" :disabled="busy" @click="run(load)">
        刷新
      </button>
    </div>

    <p v-if="error" role="alert" class="error">{{ error }}</p>

    <template v-if="usage && providers">
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <article
          v-for="metric in metrics"
          :key="metric[0]"
          class="solid-panel p-4 space-y-1"
        >
          <p class="mono-label">{{ metric[0] }}</p>
          <p class="font-mono text-xl font-semibold">{{ money(metric[1]) }}</p>
        </article>
      </div>

      <details class="fold-panel">
        <summary>
          <div class="flex items-center gap-2">
            <span>供应商与付费开关</span>
            <span
              class="tag"
              :class="providers.paidCallsEnabled ? 'tag-ok' : 'tag-warn'"
              >{{ providers.paidCallsEnabled ? "运行中" : "已暂停" }}</span
            >
          </div>
          <span class="fold-chevron">▸</span>
        </summary>
        <div class="fold-body space-y-4">
          <div class="grid sm:grid-cols-3 gap-3 text-sm">
            <div
              class="p-3 rounded-lg bg-[var(--bg-subtle)] flex justify-between"
            >
              <span>生成</span>
              <span class="tag">{{
                providers.generation.configured ? "已配置" : "未配置"
              }}</span>
            </div>
            <div
              class="p-3 rounded-lg bg-[var(--bg-subtle)] flex justify-between"
            >
              <span>向量</span>
              <span class="tag">{{
                providers.embedding.configured ? "已配置" : "未配置"
              }}</span>
            </div>
            <div
              class="p-3 rounded-lg bg-[var(--bg-subtle)] flex justify-between"
            >
              <span>重排</span>
              <span class="tag">{{
                providers.rerank.configured ? "已配置" : "未配置"
              }}</span>
            </div>
          </div>
          <div class="flex flex-wrap items-center justify-between gap-3">
            <span class="mono-label break-all">{{
              providers.embedding.fingerprint
            }}</span>
            <button
              type="button"
              class="btn"
              :disabled="busy"
              @click="paidConfirm = true"
            >
              {{ providers.paidCallsEnabled ? "暂停付费调用" : "恢复付费调用" }}
            </button>
          </div>
        </div>
      </details>

      <details class="fold-panel">
        <summary>
          <div class="flex items-center gap-2">
            <span>实验决策筛选</span>
            <span class="tag">{{ providers.decision.provider }}</span>
          </div>
          <span class="fold-chevron">▸</span>
        </summary>
        <form
          class="fold-body flex flex-wrap items-end gap-3"
          @submit.prevent="saveDecision"
        >
          <label class="flex-1 min-w-40">
            <span class="field-label">供应商</span>
            <select
              v-model="selectedProvider"
              class="field"
              @change="setThreshold"
            >
              <option value="disabled">关闭（基础 RAG）</option>
              <option value="typesafe">TypeSafe Jev</option>
              <option value="bailian">阿里百炼</option>
            </select>
          </label>
          <label v-if="selectedProvider !== 'disabled'">
            <span class="field-label">阈值</span>
            <input
              v-model.number="threshold"
              type="number"
              min="0.5"
              max="1"
              step="0.01"
              required
              class="field !w-24 font-mono"
            />
          </label>
          <button type="submit" class="btn-primary" :disabled="busy">
            保存
          </button>
        </form>
      </details>
    </template>

    <section class="solid-panel p-4 space-y-3">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h2 class="text-sm font-semibold">调用账本</h2>
        <select
          v-model="callStatus"
          aria-label="筛选调用状态"
          class="field !w-auto !py-1.5 text-xs"
          :disabled="busy"
          @change="run(pages.reset)"
        >
          <option value="">全部</option>
          <option value="UNCERTAIN">待对账</option>
          <option value="RESERVED">执行中</option>
          <option value="SETTLED">已结算</option>
        </select>
      </div>

      <article
        v-for="call in calls"
        :key="call.id"
        class="border-t border-[var(--border-hair)] pt-3 space-y-1.5"
      >
        <div class="flex flex-wrap justify-between gap-2 text-sm">
          <span>
            {{ call.provider }} / {{ call.model }}
            <span class="tag ml-1">{{ call.stage }}</span>
          </span>
          <span
            class="tag"
            :class="call.status === 'UNCERTAIN' ? 'tag-warn' : 'tag-ok'"
            >{{ call.status }}</span
          >
        </div>
        <p class="muted font-mono text-xs">
          {{ date(call.created_at) }} · {{ call.duration_ms ?? "—" }} ms · 预留
          {{ money(call.reserved) }} · 实际
          {{ call.actual === null ? "待核对" : money(call.actual) }}
        </p>
        <button
          v-if="call.status === 'UNCERTAIN'"
          type="button"
          class="btn"
          :disabled="busy"
          @click="
            callId = call.id;
            actual = '';
          "
        >
          人工对账
        </button>
      </article>
      <p v-if="!calls.length" class="empty">暂无调用记录</p>

      <Pagination
        :page="pages.previous.value.length + 1"
        :has-next="!!pages.nextCursor.value"
        :busy="busy"
        @next="run(pages.next)"
        @back="run(pages.back)"
      />
    </section>

    <Drawer
      :open="paidConfirm"
      title="更改付费调用状态？"
      center
      @close="paidConfirm = false"
    >
      <p class="muted">
        {{
          providers?.paidCallsEnabled
            ? "将暂停新的付费模型请求。"
            : "将恢复付费模型调用。"
        }}
      </p>
      <button
        type="button"
        class="btn-primary w-full"
        :disabled="busy"
        @click="togglePaid"
      >
        确认更改
      </button>
    </Drawer>

    <Drawer :open="!!callId" title="核对调用费用" center @close="callId = ''">
      <form class="space-y-4" @submit.prevent="reconcile">
        <label class="block">
          <span class="field-label">实际金额（元）</span>
          <input
            v-model="actual"
            class="field font-mono"
            type="number"
            min="0"
            max="100"
            step="0.000001"
            required
          />
        </label>
        <button type="submit" class="btn-primary w-full" :disabled="busy">
          提交对账
        </button>
      </form>
    </Drawer>
  </section>
</template>
