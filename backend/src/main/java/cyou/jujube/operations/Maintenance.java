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

  public Maintenance(
      Database db, CloneProperties props, Clock clock, ObjectProvider<JavaMailSender> mail) {
    this.db = db;
    this.props = props;
    this.clock = clock;
    this.mail = mail;
  }

  @Scheduled(fixedDelay = 60_000)
  public void clean() {
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
        LOG.warn(
            "stage=NOTIFICATION event=MAIL_FAILED notificationId={} errorType={}",
            id,
            ex.getClass().getSimpleName());
      }
    }
  }
}
