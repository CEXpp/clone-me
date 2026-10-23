-- 请求键在单个访客会话内唯一；中断后保留状态，重放不重新产生付费调用。
ALTER TABLE visitor_sessions ADD COLUMN verified_until INTEGER NOT NULL DEFAULT 0;
ALTER TABLE visitor_sessions ADD COLUMN verified_ip TEXT;
CREATE TABLE chat_requests (
  session_id TEXT NOT NULL REFERENCES visitor_sessions(id) ON DELETE CASCADE,
  request_key TEXT NOT NULL,
  question_hash TEXT NOT NULL,
  request_id TEXT NOT NULL,
  status TEXT NOT NULL,
  response_json TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(session_id,request_key)
);
CREATE INDEX idx_visitor_expiry ON visitor_sessions(expires_at);
CREATE INDEX idx_messages_sequence ON messages(session_id,sequence);
