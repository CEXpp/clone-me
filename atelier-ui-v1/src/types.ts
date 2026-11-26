/** 与后端显式契约对应的类型。数据库投影的既有 snake_case 字段保留兼容。 */
export interface Page<T> {
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

export interface ProfileLink {
  label: string;
  url: string;
}

export interface ProfileSkill {
  name: string;
  level: string;
  summary: string;
}

export interface ProfileExperience {
  title: string;
  organization: string;
  period: string;
  summary: string;
}

export interface ProfileProject {
  id: string;
  name: string;
  role: string;
  summary: string;
  stack: string[];
  links: ProfileLink[];
}

export interface Profile {
  revision?: number;
  chatEnabled?: boolean;
  configured?: boolean;
  displayName: string;
  headline: string;
  bio: string;
  skills: ProfileSkill[];
  experiences: ProfileExperience[];
  projects: ProfileProject[];
  contacts: ProfileLink[];
  filings: ProfileLink[];
  quickQuestions: string[];
  aiDisclosure?: string;
}

export interface ProfileEdit {
  displayName: string;
  headline: string;
  bio: string;
  skills: ProfileSkill[];
  experiences: ProfileExperience[];
  projects: ProfileProject[];
  contacts: ProfileLink[];
  filings: ProfileLink[];
  quickQuestions: string[];
  revision: number;
}
