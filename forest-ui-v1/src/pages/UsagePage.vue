<script setup lang="ts">
/**
 * 展示预算与调用账本，管理付费总开关、决策供应商和不确定费用对账。
 * 后端金额单位为微元，展示时才换算成人民币；供应商概率阈值需要分别校准。
 */
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import { useAction, money, date, notify } from "../shared";
import Drawer from "../components/Drawer.vue";
import type { Usage, Providers, Call, Page } from "../types";
import { usePaged } from "../composables/usePaged";
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
    notify("付费调用开关已更新，已发出的调用不受影响");
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
    notify("决策供应商配置已更新");
  });
}
async function reconcile() {
  // 这里填写“人民币元”，后端将 actualCny 转为微元；接口返回的账本金额则已经是微元。
  await run(async () => {
    const value = Number(actual.value);
    if (
      actual.value.trim() === "" ||
      !Number.isFinite(value) ||
      value < 0 ||
      value > 100
    )
      throw new Error("请填入 0–100 元之间的实际账单金额");
    await api(
      "/api/admin/calls/" + encodeURIComponent(callId.value) + "/reconcile",
      "POST",
      { actualCny: value },
    );
    callId.value = "";
    await load();
    notify("已按供应商账单核对本次调用费用");
  });
}
const metrics = computed(() =>
  usage.value
    ? [
        ["今日已结算", usage.value.today.spent],
        ["今日预留", usage.value.today.reserved],
        ["每日预算上限", usage.value.dailyLimit],
        ["累计估算", usage.value.lifetimeEstimated],
      ]
    : [],
);
onMounted(() => run(load));
</script>
<template>
  <section class="space-y-6">
    <div class="flex justify-between gap-3 items-center">
      <h2 class="section-title">费用与模型状态</h2>
      <button class="btn" :disabled="busy" @click="run(load)">刷新账本</button>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <template v-if="usage && providers"
      ><div class="grid sm:grid-cols-2 lg:grid-cols-4 gap-3">
        <article
          v-for="metric in metrics"
          :key="metric[0]"
          class="panel space-y-3"
        >
          <p class="eyebrow">{{ metric[0] }}</p>
          <p class="text-2xl font-light font-mono">{{ money(metric[1]) }}</p>
        </article>
      </div>
      <p class="notice">
        费用按配置单价估算。预留额不等于实际消费；不确定调用需要核对供应商账单。北京时间自然日预算
        {{ money(usage.dailyLimit) }}，累计首次超过 5
        元产生邮件通知。当前未确定或执行中调用：{{ usage.uncertainCalls }}。
      </p>
      <div class="grid lg:grid-cols-2 gap-5">
        <section class="panel space-y-4">
          <p class="eyebrow">PAID CALLS</p>
          <h3 class="section-title">
            付费调用 {{ providers.paidCallsEnabled ? "已开启" : "已暂停" }}
          </h3>
          <p class="muted">
            暂停新的付费模型请求，个人介绍浏览继续可用；不取消已发出的请求。
          </p>
          <button class="btn" :disabled="busy" @click="paidConfirm = true">
            {{
              providers.paidCallsEnabled ? "暂停新的付费调用" : "恢复付费调用"
            }}
          </button>
        </section>
        <section class="panel space-y-4">
          <p class="eyebrow">PROVIDER STATUS</p>
          <p class="text-sm">
            DeepSeek
            <span class="tag">{{
              providers.generation.configured ? "已配置" : "未配置"
            }}</span>
          </p>
          <p class="text-sm">
            Embedding
            <span class="tag">{{
              providers.embedding.configured ? "已配置" : "未配置"
            }}</span>
          </p>
          <p class="eyebrow !tracking-normal break-all">
            指纹：{{ providers.embedding.fingerprint }}
          </p>
          <p class="text-sm">
            专用重排 · {{ providers.rerank.model }}
            <span class="tag">{{
              providers.rerank.configured ? "已配置" : "未配置"
            }}</span>
          </p>
          <p class="muted">
            凭据由服务器环境变量配置，管理页面不保存 API 密钥。
          </p>
        </section>
      </div>
      <form class="panel space-y-4" @submit.prevent="saveDecision">
        <p class="eyebrow">EVIDENCE DECISION · EXPERIMENTAL</p>
        <h3 class="section-title">可替换的证据筛选组件</h3>
        <p class="notice">
          概率阈值尚未通过真实资料评测，不代表准确率。供应商故障回退基础问答路径，仍保留最终核验；正常依据不足不会绕过。
        </p>
        <div class="flex flex-wrap items-end gap-3">
          <label class="flex-1 min-w-40"
            ><span class="field-label">供应商</span
            ><select
              v-model="selectedProvider"
              class="field"
              @change="setThreshold"
            >
              <option value="disabled">关闭（基础 RAG）</option>
              <option value="typesafe">TypeSafe Jev</option>
              <option value="bailian">阿里百炼</option>
            </select></label
          ><label v-if="selectedProvider !== 'disabled'"
            ><span class="field-label">独立概率阈值</span
            ><input
              v-model.number="threshold"
              type="number"
              min="0.5"
              max="1"
              step="0.01"
              required
              class="field !w-28" /></label
          ><button class="btn-primary !py-3" :disabled="busy">保存配置</button>
        </div>
        <p class="muted">
          TypeSafe：{{
            providers.decision.typesafeConfigured ? "已配置" : "未配置"
          }}
          · 百炼：{{
            providers.decision.bailianConfigured ? "已配置" : "未配置"
          }}
          · 策略 {{ providers.decision.policyVersion }}
        </p>
      </form>
    </template>
    <section class="panel space-y-4">
      <h3 class="section-title">模型调用账本</h3>
      <label class="muted"
        >筛选状态
        <select
          v-model="callStatus"
          class="field !w-auto"
          :disabled="busy"
          @change="run(pages.reset)"
        >
          <option value="">全部</option>
          <option value="UNCERTAIN">待对账</option>
          <option value="RESERVED">执行中</option>
          <option value="SETTLED">已结算</option>
        </select>
      </label>
      <article
        v-for="call in calls"
        :key="call.id"
        class="border-t border-slate-200 pt-4 space-y-2"
      >
        <div class="flex flex-wrap justify-between gap-2">
          <p class="text-sm">
            {{ call.provider }} / {{ call.model }}
            <span class="tag">{{ call.stage }}</span>
          </p>
          <span class="tag">{{ call.status }}</span>
        </div>
        <p class="muted">
          {{ date(call.created_at) }} · {{ call.duration_ms ?? "—" }} ms · 输入
          {{ call.input_tokens ?? "—" }} / 输出
          {{ call.output_tokens ?? "—" }} tokens
        </p>
        <p class="text-xs text-slate-600">
          预留 {{ money(call.reserved) }} · 已结算
          {{ call.actual === null ? "待核对" : money(call.actual) }}
        </p>
        <p class="eyebrow !tracking-normal break-all">
          调用 {{ call.id }} · 请求 {{ call.request_id }}
        </p>
        <p v-if="call.error_code" class="muted">{{ call.error_code }}</p>
        <button
          v-if="call.status === 'UNCERTAIN'"
          class="btn"
          :disabled="busy"
          @click="
            callId = call.id;
            actual = '';
          "
        >
          按供应商账单人工对账
        </button>
      </article>
      <p v-if="!calls.length" class="empty">暂无模型调用。</p>
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
      title="确认更改付费调用状态"
      center
      @close="paidConfirm = false"
      ><p class="text-sm">
        {{
          providers?.paidCallsEnabled
            ? "将暂停新的付费调用。已在途请求仍可能结算。"
            : "将恢复新的付费调用，每日预算保护继续生效。"
        }}
      </p>
      <p v-if="error" class="error">{{ error }}</p>
      <button class="btn-primary" :disabled="busy" @click="togglePaid">
        确认更改
      </button></Drawer
    >
    <Drawer
      :open="!!callId"
      title="人工核对调用费用"
      center
      @close="callId = ''"
      ><p class="muted">
        请先在供应商后台确认此调用实际金额，再填写人民币元。不要用预留额当作账单。
      </p>
      <p class="eyebrow !tracking-normal break-all">{{ callId }}</p>
      <form class="space-y-4" @submit.prevent="reconcile">
        <label class="block"
          ><span class="field-label">实际账单金额（元）</span
          ><input
            v-model="actual"
            class="field font-mono"
            type="number"
            min="0"
            max="100"
            step="0.000001"
            required
        /></label>
        <p v-if="error" class="error">{{ error }}</p>
        <button class="btn-primary" :disabled="busy">确认账单并提交</button>
      </form></Drawer
    >
  </section>
</template>
