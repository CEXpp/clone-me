-- 修订号用于人工编辑的条件写入；任务与调用关联仍使用 request_id=jobId。
ALTER TABLE candidates ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_calls ADD COLUMN pricing_json TEXT NOT NULL DEFAULT '{}';
-- 接管旧任务表时先保守保留最早活动任务，避免历史重复任务使唯一索引升级失败。
UPDATE jobs SET status='FAILED',error_code='DUPLICATE_JOB'
WHERE id IN (
  SELECT id FROM (
    SELECT id,ROW_NUMBER() OVER(PARTITION BY type,subject_id ORDER BY created_at,id) AS rank
    FROM jobs WHERE status IN ('QUEUED','RUNNING','PAUSED')
  ) WHERE rank>1
);
CREATE UNIQUE INDEX idx_jobs_active_subject ON jobs(type,subject_id) WHERE status IN ('QUEUED','RUNNING','PAUSED');
CREATE INDEX idx_jobs_queue ON jobs(status,created_at,id);
CREATE INDEX idx_jobs_subject ON jobs(type,subject_id,status);
CREATE INDEX idx_calls_state ON ai_calls(status,created_at,id);
CREATE INDEX idx_calls_request ON ai_calls(request_id,status);
CREATE INDEX idx_sources_created ON sources(created_at,id);
CREATE INDEX idx_segments_source ON segments(source_id,ordinal);
CREATE INDEX idx_sessions_expiry ON admin_sessions(expires_at);
CREATE INDEX idx_notifications_pending ON notification_events(status,attempts,last_attempt);
CREATE INDEX idx_diagnostics_created ON diagnostic_events(created_at);
