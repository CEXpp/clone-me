package cyou.jujube.conversation;

import cyou.jujube.common.ApiException;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.Database;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class VisitorSessions {
  private static final long LIFE = Duration.ofDays(5).toMillis();
  private final Database db;
  private final Clock clock;

  public VisitorSessions(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public record Created(String token, String id, long expiresAt) {}

  public Created create() {
    String token = Crypto.token(), id = UUID.randomUUID().toString();
    long expiry = clock.millis() + LIFE;
    db.sessions()
        .update(
            "INSERT INTO visitor_sessions(id,token_hash,created_at,expires_at) VALUES(?,?,?,?)",
            id,
            Crypto.hash(token),
            clock.millis(),
            expiry);
    return new Created(token, id, expiry);
  }

  public Map<String, Object> require(String token) {
    if (token == null) throw new ApiException(401, "VISITOR_SESSION_REQUIRED", "请先开始一段新聊天");
    var rows =
        db.sessions()
            .queryForList(
                "SELECT id,expires_at FROM visitor_sessions WHERE token_hash=? AND expires_at>?",
                Crypto.hash(token),
                clock.millis());
    if (rows.isEmpty()) throw new ApiException(401, "VISITOR_SESSION_EXPIRED", "聊天已过期，请开始新聊天");
    return rows.getFirst();
  }

  public List<Map<String, Object>> history(String token) {
    var session = require(token);
    return db.sessions()
        .queryForList(
            "SELECT role,content,created_at FROM (SELECT sequence,role,content,created_at FROM messages WHERE session_id=? ORDER BY sequence DESC LIMIT 100) ORDER BY sequence",
            session.get("id"));
  }

  public List<Map<String, Object>> context(String id) {
    var rows =
        db.sessions()
            .queryForList(
                "SELECT role,content FROM messages WHERE session_id=? ORDER BY sequence DESC LIMIT 6",
                id);
    Collections.reverse(rows);
    return rows;
  }

  public void lock(String id, String requestId) {
    int updated =
        db.sessions()
            .update(
                "UPDATE visitor_sessions SET lock_id=?,lock_until=? WHERE id=? AND expires_at>? AND lock_until<=?",
                requestId,
                clock.millis() + Duration.ofMinutes(3).toMillis(),
                id,
                clock.millis(),
                clock.millis());
    if (updated != 1) throw new ApiException(409, "CHAT_IN_PROGRESS", "上一个回答仍在处理中");
  }

  public void unlock(String id, String requestId) {
    db.sessions()
        .update(
            "UPDATE visitor_sessions SET lock_id=NULL,lock_until=0 WHERE id=? AND lock_id=?",
            id,
            requestId);
  }

  public void save(String id, String question, String answer) {
    long now = clock.millis();
    db.sessionTransaction(
        sql -> {
          if (sql.queryForObject(
                  "SELECT COUNT(*) FROM visitor_sessions WHERE id=? AND expires_at>?",
                  Long.class,
                  id,
                  now)
              == 0) throw new ApiException(401, "VISITOR_SESSION_EXPIRED", "聊天已过期或被清除，请开始新聊天");
          sql.update(
              "INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,'user',?,?)",
              UUID.randomUUID().toString(),
              id,
              question,
              now);
          sql.update(
              "INSERT INTO messages(id,session_id,role,content,created_at) VALUES(?,?,'assistant',?,?)",
              UUID.randomUUID().toString(),
              id,
              answer,
              now);
          sql.update("UPDATE visitor_sessions SET expires_at=? WHERE id=?", now + LIFE, id);
          return null;
        });
  }

  public void clear(String token) {
    if (token != null)
      db.sessions().update("DELETE FROM visitor_sessions WHERE token_hash=?", Crypto.hash(token));
  }
}
