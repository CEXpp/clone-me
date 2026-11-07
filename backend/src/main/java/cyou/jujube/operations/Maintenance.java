package cyou.jujube.operations;

import cyou.jujube.config.CloneProperties;
import cyou.jujube.storage.Database;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 执行会话过期、限流记录及诊断记录清理，并重试待发送通知。 邮件是持久化事件的后续处理，失败时记录重试状态，不影响已经完成的问答和费用入账。 */
@Component
public class Maintenance {
  private static final Logger LOG = LoggerFactory.getLogger(Maintenance.class);
  private final Database db;
  private final CloneProperties props;
  private final Clock clock;
  private final ObjectProvider<JavaMailSender> mail;
  private final cyou.jujube.config.OperationsProperties options;
  private final RuntimeStatus runtime;

  public Maintenance(
      Database db,
      CloneProperties props,
      Clock clock,
      ObjectProvider<JavaMailSender> mail,
      cyou.jujube.config.OperationsProperties options,
      RuntimeStatus runtime) {
    this.db = db;
    this.props = props;
    this.clock = clock;
    this.mail = mail;
    this.options = options;
    this.runtime = runtime;
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void monitor() {
    if (!options.production()) return;
    long now = clock.millis();
    try {
      runtime.diskSpace();
    } catch (RuntimeException ex) {
      alert("DISK_SPACE_LOW", "磁盘空间不足", "已暂停新增处理，请清理空间并检查资料容量。");
    }
    if (db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE status IN ('QUEUED','RUNNING') AND updated_at<?",
                Long.class,
                now - Duration.ofMinutes(5).toMillis())
        > 0) alert("JOBS_STALLED", "后台任务长时间未推进", "请查看任务状态、供应商连接及暂停原因。");
    if (db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM ai_calls WHERE status='UNCERTAIN' AND created_at<?",
                Long.class,
                now - Duration.ofMinutes(15).toMillis())
        > 0) alert("RECONCILIATION_REQUIRED", "存在待核对模型费用", "请在后台筛选不确定调用，核对供应商账单后再恢复有关任务。");
    long expiry = runtime.tlsExpiry();
    if (expiry > 0 && expiry < now + Duration.ofDays(14).toMillis())
      alert("TLS_EXPIRING", "HTTPS 证书即将过期", "请检查主机上的证书续期任务和 Nginx 重载结果。");
    try {
      long latest = 0;
      try (var paths = java.nio.file.Files.list(props.root().resolve("backups"))) {
        for (var file :
            paths
                .filter(p -> p.getFileName().toString().matches("backup-[0-9]+-[a-f0-9]{8}\\.zip"))
                .toList())
          latest = Math.max(latest, java.nio.file.Files.getLastModifiedTime(file).toMillis());
      }
      if (latest < now - Duration.ofHours(26).toMillis())
        alert("BACKUP_OVERDUE", "缺少最近的有效备份", "请在后台创建备份并检查每日备份任务和异机副本。");
    } catch (java.io.IOException ex) {
      alert("BACKUP_UNAVAILABLE", "无法检查备份目录", "请检查持久目录权限与磁盘状态。");
    }
  }

  private void alert(String kind, String subject, String body) {
    String day =
        java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai"))).toString();
    db.app()
        .update(
            "INSERT OR IGNORE INTO notification_events(id,kind,subject,body,created_at) VALUES(?,?,?,?,?)",
            kind + ":" + day,
            kind,
            subject,
            body,
            clock.millis());
  }

  @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Shanghai")
  public void pruneUnusedEmbeddings() {
    var keep = new java.util.HashSet<String>();
    db.app()
        .query(
            "SELECT r.content,v.fingerprint FROM release_records r JOIN releases v ON v.id=r.release_id",
            (org.springframework.jdbc.core.RowCallbackHandler)
                row ->
                    keep.add(
                        cyou.jujube.security.Crypto.hash(
                            row.getString("fingerprint") + "\n" + row.getString("content"))));
    // 每次事务只清理小批，避免长时间持有写锁；活动版本和正在构建的快照均已纳入 keep。
    var unused =
        db.app().queryForList("SELECT key FROM embedding_cache", String.class).stream()
            .filter(key -> !keep.contains(key))
            .toList();
    for (String key : unused) db.app().update("DELETE FROM embedding_cache WHERE key=?", key);
  }

  @Scheduled(fixedDelay = 60_000)
  public void clean() {
    // 接口访问时已经检查 expires_at；定时删除负责释放空间，不是等清理后才禁止访问。
    long now = clock.millis();
    db.sessions().update("DELETE FROM visitor_sessions WHERE expires_at<=?", now);
    db.app().update("DELETE FROM admin_sessions WHERE expires_at<=?", now);
    db.app()
        .update("DELETE FROM rate_limits WHERE expires_at<?", now - Duration.ofDays(1).toMillis());
    db.app()
        .update(
            "DELETE FROM diagnostic_events WHERE created_at<?",
            now - Duration.ofDays(14).toMillis());
  }

  @Scheduled(fixedDelay = 30_000)
  public void sendNotifications() {
    db.app()
        .update(
            "UPDATE notification_events SET status='FAILED' WHERE status='PENDING' AND attempts>=3");
    JavaMailSender sender = mail.getIfAvailable();
    if (sender == null
        || props.mailTo() == null
        || props.mailTo().isBlank()
        || props.mailFrom() == null
        || props.mailFrom().isBlank()) return;
    long now = clock.millis();
    var events =
        db.app()
            .queryForList(
                "SELECT * FROM notification_events WHERE status='PENDING' AND attempts<3 AND (last_attempt IS NULL OR last_attempt<?) ORDER BY created_at LIMIT 3",
                now - Duration.ofMinutes(5).toMillis());
    for (var event : events) {
      // 邮件发送独立于问答事务；先登记尝试次数，失败后下一轮按间隔重试，最多三次。
      String id = Database.text(event, "id");
      db.app()
          .update(
              "UPDATE notification_events SET attempts=attempts+1,last_attempt=? WHERE id=?",
              now,
              id);
      try {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.mailFrom());
        message.setTo(props.mailTo());
        message.setSubject("[Clone Me] " + event.get("subject"));
        message.setText(Database.text(event, "body"));
        sender.send(message);
        db.app().update("UPDATE notification_events SET status='SENT' WHERE id=?", id);
        LOG.info("stage=NOTIFICATION event=MAIL_SENT notificationId={}", id);
      } catch (Exception ex) {
        db.app()
            .update(
                "UPDATE notification_events SET status='FAILED' WHERE id=? AND attempts>=3", id);
        LOG.warn(
            "stage=NOTIFICATION event=MAIL_FAILED notificationId={} errorType={}",
            id,
            ex.getClass().getSimpleName());
      }
    }
  }
}
