/** 与后端显式契约对应的类型。数据库投影的既有 snake_case 字段保留兼容。 */
export interface Page<T> {
  items: T[];
  nextCursor: string;
}
export interface Candidate {
  id: string;
  source_id: string;
  segment_id: string;
  kind: string;
  content: string;
  mastery: string;
  approved: number;
  withdrawn: number;
  manually_edited: number;
  revision: number;
  updated_at: number;
}
export interface SourceSummary {
  id: string;
  name: string;
  format: string;
  status: string;
  created_at: number;
  error_code: string | null;
}
export interface Segment {
  id: string;
  ordinal: number;
  label: string;
  speaker: string;
  content: string;
}
export interface SourceDetail {
  source: SourceSummary;
  candidates: Candidate[];
  segments: Segment[];
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
export interface Release {
  id: string;
  label: string;
  status: string;
  keyword_only: number;
  fingerprint: string;
  created_at: number;
}
export interface Call {
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
