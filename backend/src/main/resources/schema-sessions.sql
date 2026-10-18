CREATE TABLE IF NOT EXISTS visitor_sessions (id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, lock_id TEXT, lock_until INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES visitor_sessions(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id,created_at);
