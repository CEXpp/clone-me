/** 与后端显式契约对应的类型。数据库投影的既有 snake_case 字段保留兼容。 */
export interface Page<T> {
  // T 是列表条目的类型，例如 Page<Job>；nextCursor 为空字符串表示没有下一页。
  items: T[];
  nextCursor: string;
}
export interface Job {
  id: string;
  type: string;
  subject_id: string;
  status: string;
  stage: string;
  error_code: string | null;
  cursor: number;
  created_at: number;
  updated_at: number;
}
export interface Call {
  // reserved 和 actual 都是微元；actual=null 表示尚无确定结算额，不能当成费用为 0。
  id: string;
  request_id: string;
  stage: string;
  provider: string;
  model: string;
  reserved: number;
  actual: number | null;
  status: string;
  input_tokens: number | null;
  output_tokens: number | null;
  created_at: number;
  duration_ms: number | null;
  error_code: string | null;
  pricing_json: string;
}
export interface Usage {
  dailyLimit: number;
  today: { spent: number; reserved: number };
  lifetimeEstimated: number;
  uncertainCalls: number;
}
export interface Providers {
  paidCallsEnabled: boolean;
  generation: { configured: boolean };
  embedding: { configured: boolean; fingerprint: string };
  rerank: { configured: boolean; model: string; provider: string };
  decision: {
    provider: string;
    typesafeThreshold: string;
    bailianThreshold: string;
    typesafeConfigured: boolean;
    bailianConfigured: boolean;
    policyVersion: string;
  };
}
export interface Diagnostic {
  request_id: string;
  started_at: number;
  updated_at: number;
  events: number;
}
export interface DiagnosticEvent {
  stage: string;
  event: string;
  detail_json: string;
  created_at: number;
}
export interface Backup {
  name: string;
  bytes: number;
}
export interface Notification {
  id: string;
  kind: string;
  subject: string;
  status: string;
  attempts: number;
  created_at: number;
  last_attempt: number | null;
}
export interface AnswerSource {
  id: string;
  title: string;
  location: string;
}
export interface Message {
  role: string;
  content: string;
  status?: string;
  sources?: AnswerSource[];
  requestId?: string;
  created_at?: number;
}
export interface VisitorSession {
  sessionId: string;
  expiresAt: number;
  messages?: Message[];
}
export interface Answer {
  // status 解释回答结果；answer 也可能是证据不足的固定说明，需结合 status 和 sources 阅读。
  requestId: string;
  answer: string;
  status: string;
  sources: AnswerSource[];
  expiresAt: number;
}
export interface Queued {
  jobId: string;
}
export type FormRow = Record<string, string>;
