package cyou.jujube.conversation;

import cyou.jujube.common.ApiException;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.Database;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 管理匿名访客会话，浏览器持有随机令牌，数据库只存令牌哈希。 所有历史读取都先校验令牌，所有消息写入都带 session_id；有效问答后续期五天，单纯浏览不续期。
 * 会话锁保证同一访客的问答按顺序处理；清除会话依靠外键级联删除消息。
 */
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

  public long nextExpiry() {
    return clock.millis() + LIFE;
  }

  public void verified(String id, String ip) {
    db.sessions()
        .update(
            "UPDATE visitor_sessions SET verified_ip=?,verified_until=? WHERE id=?",
            ip,
            clock.millis() + 60_000,
            id);
  }

  public boolean hasVerification(String token, String ip) {
    var session = require(token);
    return db.sessions()
            .queryForObject(
                "SELECT COUNT(*) FROM visitor_sessions WHERE id=? AND verified_ip=? AND verified_until>?",
                Long.class,
                session.get("id"),
                ip,
                clock.millis())
        == 1;
  }

  /** 生成不可预测的浏览器令牌，原文只返回给当前浏览器，数据库保留哈希和内部会话编号。 */
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
    // token 是浏览器持有的秘密，id 是服务端内部关联编号。知道 id 不能代替持有 token。
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

  /** 校验令牌后只读取该会话最近一百条消息，按顺序返回，并恢复回复状态、引用及请求编号。 */
  public List<Map<String, Object>> history(String token) {
    var session = require(token);
    var rows =
        db.sessions()
            .queryForList(
                "SELECT role,content,created_at,metadata_json FROM (SELECT sequence,role,content,created_at,metadata_json FROM messages WHERE session_id=? ORDER BY sequence DESC LIMIT 100) ORDER BY sequence",
                session.get("id"));
    for (var row : rows) {
      var metadata = cyou.jujube.common.Json.map((String) row.remove("metadata_json"));
      for (String key : List.of("status", "sources", "requestId"))
        if (metadata.containsKey(key)) row.put(key, metadata.get(key));
    }
    return rows;
  }

  /** 仅取当前会话最近六条消息解释追问，限制模型上下文规模；这些消息不能成为个人事实证据。 */
  public List<Map<String, Object>> context(String id) {
    // 数据库先倒序取最近六条，再反转成时间正序，交给模型时才是正常对话顺序。
    var rows =
        db.sessions()
            .queryForList(
                "SELECT role,content FROM messages WHERE session_id=? ORDER BY sequence DESC LIMIT 6",
                id);
    Collections.reverse(rows);
    return rows;
  }

  /** 条件更新实现单会话互斥。三分钟租期防止进程异常后永久占用；并发请求立即返回冲突。 */
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

  /** 按请求编号释放自己的锁，避免过期请求误释放后续请求已取得的锁。 */
  public void unlock(String id, String requestId) {
    db.sessions()
        .update(
            "UPDATE visitor_sessions SET lock_id=NULL,lock_until=0 WHERE id=? AND lock_id=?",
            id,
            requestId);
  }

  /** 在一个会话库事务中保存问题、回复和元数据，再续期五天；会话已清除或过期时拒绝写入。 */
  public void save(String id, String question, String answer) {
    save(id, question, answer, Map.of());
  }

  public void save(String id, String question, String answer, Map<String, ?> metadata) {
    save(id, question, answer, metadata, null);
  }

  public void save(
      String id,
      String question,
      String answer,
      Map<String, ?> metadata,
      ChatService.Answer result) {
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
              "INSERT INTO messages(id,session_id,role,content,created_at,metadata_json) VALUES(?,?,'assistant',?,?,?)",
              UUID.randomUUID().toString(),
              id,
              answer,
              now,
              cyou.jujube.common.Json.write(metadata));
          sql.update("UPDATE visitor_sessions SET expires_at=? WHERE id=?", now + LIFE, id);
          if (result != null)
            // 问答消息与请求结果在同一事务内提交；断网后 GET 结果不会看到只写了一半的问答。
            sql.update(
                "UPDATE chat_requests SET status='COMPLETED',response_json=? WHERE session_id=? AND request_id=? AND status='RUNNING'",
                cyou.jujube.common.Json.write(result),
                id,
                result.requestId());
          return null;
        });
  }

  public ChatService.Answer result(String token, String requestKey) {
    var session = require(token);
    var row =
        Database.required(
            db.sessions(),
            "SELECT status,response_json FROM chat_requests WHERE session_id=? AND request_key=?",
            session.get("id"),
            requestKey);
    if (!"COMPLETED".equals(row.get("status")))
      throw new ApiException(409, "REQUEST_UNCERTAIN", "请求仍在处理或结果不确定，请刷新聊天记录核对，勿重复付费提交");
    return cyou.jujube.common.Json.decode(
        Database.text(row, "response_json"), ChatService.Answer.class);
  }

  /** 在会话锁内注册请求键；仅确定失败且问题相同的请求可重新登记，其他已有状态禁止重复执行。 */
  public void register(String sessionId, String key, String question, String requestId) {
    // 主键为 (session_id, request_key)，所以不同访客的键不会互相访问。
    // FAILED 表示上层未发现付费预留；UNCERTAIN 仍可能收费，不能套用同一重试规则。
    String hash = Crypto.hash(question);
    int added =
        db.sessions()
            .update(
                "INSERT INTO chat_requests(session_id,request_key,question_hash,request_id,status,created_at) VALUES(?,?,?,?,'RUNNING',?) ON CONFLICT(session_id,request_key) DO UPDATE SET request_id=excluded.request_id,status='RUNNING',created_at=excluded.created_at WHERE chat_requests.status='FAILED' AND chat_requests.question_hash=excluded.question_hash",
                sessionId,
                key,
                hash,
                requestId,
                clock.millis());
    if (added == 0) throw new ApiException(409, "REQUEST_ALREADY_EXISTS", "该请求已提交，请先查询结果，勿重复提交");
  }

  public ChatService.Answer replay(String token, String key, String question) {
    var session = require(token);
    var rows =
        db.sessions()
            .queryForList(
                "SELECT question_hash,status FROM chat_requests WHERE session_id=? AND request_key=?",
                session.get("id"),
                key);
    if (rows.isEmpty()) return null;
    if (!Crypto.hash(question).equals(rows.getFirst().get("question_hash")))
      throw new ApiException(409, "REQUEST_KEY_REUSED", "请求编号已用于其他问题，请刷新后重试");
    if ("FAILED".equals(rows.getFirst().get("status"))) return null;
    return result(token, key);
  }

  /** 删除令牌对应会话，消息通过外键级联删除；不会触及其他访客或本人资料。 */
  public void clear(String token) {
    if (token != null)
      db.sessions().update("DELETE FROM visitor_sessions WHERE token_hash=?", Crypto.hash(token));
  }
}
