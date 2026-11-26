-- 隔离访客身份；令牌只存哈希，五天过期，lock_id 和 lock_until 维护单会话问答租约。
CREATE TABLE IF NOT EXISTS visitor_sessions (id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, lock_id TEXT, lock_until INTEGER NOT NULL DEFAULT 0);
-- 消息必须归属一个会话；删除会话时级联清除。回复引用等元数据由 Database 兼容迁移补列。
CREATE TABLE IF NOT EXISTS messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES visitor_sessions(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id,created_at);
