-- 运行期设置：当前知识版本、公开介绍和供应商选择等，不存环境密钥。
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- 上传资料元数据；随机磁盘文件名与访客看到的资料名分开，mapping_json 保存聊天字段映射。
CREATE TABLE IF NOT EXISTS sources (id TEXT PRIMARY KEY, name TEXT NOT NULL, file_name TEXT NOT NULL, hash TEXT NOT NULL, format TEXT NOT NULL, mapping_json TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL, created_at INTEGER NOT NULL, error_code TEXT);
-- 带来源位置的原文片段；speaker 区分本人和他人，供人工审阅及归属检查。
CREATE TABLE IF NOT EXISTS segments (id TEXT PRIMARY KEY, source_id TEXT NOT NULL REFERENCES sources(id), ordinal INTEGER NOT NULL, label TEXT NOT NULL, speaker TEXT NOT NULL, content TEXT NOT NULL);
-- 待确认候选；manually_edited 保护人工草稿，后台提取不能覆盖本人已经保存的内容。
CREATE TABLE IF NOT EXISTS candidates (id TEXT PRIMARY KEY, source_id TEXT NOT NULL REFERENCES sources(id), segment_id TEXT NOT NULL REFERENCES segments(id), kind TEXT NOT NULL DEFAULT 'REFERENCE', content TEXT NOT NULL, mastery TEXT NOT NULL DEFAULT 'UNCONFIRMED', approved INTEGER NOT NULL DEFAULT 0, withdrawn INTEGER NOT NULL DEFAULT 0, manually_edited INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS idx_candidates_segment ON candidates(segment_id);
-- 持久化后台队列；type 是任务种类，status 是生命周期，stage 是当前步骤，cursor 保存分批进度。
CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, type TEXT NOT NULL, subject_id TEXT NOT NULL, status TEXT NOT NULL, stage TEXT NOT NULL, error_code TEXT, cursor INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
-- 知识版本元数据；先 BUILDING，索引完成后 READY，失败不会切换当前生效版本。
CREATE TABLE IF NOT EXISTS releases (id TEXT PRIMARY KEY, label TEXT NOT NULL, status TEXT NOT NULL, keyword_only INTEGER NOT NULL DEFAULT 0, fingerprint TEXT NOT NULL, created_at INTEGER NOT NULL);
-- 发布时复制的正文快照；record_id 关联实时撤回标记，terms 与 embedding 分别用于两种检索。
CREATE TABLE IF NOT EXISTS release_records (row_id INTEGER PRIMARY KEY AUTOINCREMENT, release_id TEXT NOT NULL REFERENCES releases(id), record_id TEXT NOT NULL REFERENCES candidates(id), source_id TEXT NOT NULL, source_name TEXT NOT NULL, source_label TEXT NOT NULL, kind TEXT NOT NULL, mastery TEXT NOT NULL, content TEXT NOT NULL, terms TEXT NOT NULL, embedding BLOB);
CREATE INDEX IF NOT EXISTS idx_release_records ON release_records(release_id);
-- 外部内容全文索引，rowid 对应 release_records.row_id；词元由 TextIndex 提供。
CREATE VIRTUAL TABLE IF NOT EXISTS records_fts USING fts5(terms, content='release_records', content_rowid='row_id');
-- 内容哈希与模型指纹共同确定缓存键；跨版本重复正文可复用，模型配置变化不可复用。
CREATE TABLE IF NOT EXISTS embedding_cache (key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, dimensions INTEGER NOT NULL, vector BLOB NOT NULL);
-- 唯一管理员；密码仅存哈希，动态口令秘密存密文，last_totp_step 防止验证码重放。
CREATE TABLE IF NOT EXISTS admin_account (id INTEGER PRIMARY KEY CHECK(id=1), username TEXT NOT NULL, password_hash TEXT NOT NULL, totp_encrypted TEXT NOT NULL, last_totp_step INTEGER NOT NULL DEFAULT -1);
-- 恢复码只保存哈希，used 通过条件更新实现一次性消费。
CREATE TABLE IF NOT EXISTS recovery_codes (hash TEXT PRIMARY KEY, used INTEGER NOT NULL DEFAULT 0);
-- 管理员会话令牌哈希及过期时间；与访客会话完全独立，退出即撤销。
CREATE TABLE IF NOT EXISTS admin_sessions (token_hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL);
-- 固定时间窗的限流桶；key 包含作用域、身份和窗口，过期后可清理。
CREATE TABLE IF NOT EXISTS rate_limits (key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires_at INTEGER NOT NULL);
-- 按北京时间自然日汇总微元：spent 为已结算额，reserved 包含在途和待对账预留。
CREATE TABLE IF NOT EXISTS budget_days (day TEXT PRIMARY KEY, spent INTEGER NOT NULL DEFAULT 0, reserved INTEGER NOT NULL DEFAULT 0);
-- 模型调用账本；RESERVED 先占额度，SETTLED 完成结算，UNCERTAIN 等待人工核对供应商账单。
CREATE TABLE IF NOT EXISTS ai_calls (id TEXT PRIMARY KEY, request_id TEXT NOT NULL, stage TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, day TEXT NOT NULL, reserved INTEGER NOT NULL, actual INTEGER, status TEXT NOT NULL, input_tokens INTEGER, output_tokens INTEGER, created_at INTEGER NOT NULL, duration_ms INTEGER, error_code TEXT);
-- 持久化邮件事件，唯一编号去重；通知发送独立重试，不能影响已完成的业务事务。
CREATE TABLE IF NOT EXISTS notification_events (id TEXT PRIMARY KEY, kind TEXT NOT NULL, subject TEXT NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, last_attempt INTEGER);
-- 脱敏阶段诊断，按请求编号串联检索、决策、生成、核验及故障回退。
CREATE TABLE IF NOT EXISTS diagnostic_events (id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL, stage TEXT NOT NULL, event TEXT NOT NULL, detail_json TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS idx_diagnostic_request ON diagnostic_events(request_id,id);
