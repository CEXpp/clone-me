package cyou.jujube.operations;

import cyou.jujube.common.ApiException;
import cyou.jujube.config.*;
import cyou.jujube.storage.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 生产必需配置在启动时检查，本地就绪不依赖外部供应商，避免网络故障触发重启循环。 */
@Component
public class RuntimeStatus implements ApplicationRunner {
  private final Database db;
  private final CloneProperties props;
  private final OperationsProperties options;
  private final Environment environment;
  private volatile boolean started;

  public RuntimeStatus(
      Database db, CloneProperties props, OperationsProperties options, Environment environment) {
    this.db = db;
    this.props = props;
    this.options = options;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (options.maxQueuedJobs() < 1
        || options.maxSources() < 1
        || options.maxSourceMb() < 10
        || options.maxActiveRecords() < 1
        || options.maxReleases() < 2
        || options.minFreeDiskMb() < 0
        || options.maxDatabaseMb() < 10)
      throw new IllegalStateException("Invalid operational capacity limits");
    for (String name : List.of("sources", "backups"))
      Files.createDirectories(props.root().resolve(name));
    recoverTrash();
    if (!options.production()) return;
    byte[] master;
    try {
      master = Base64.getDecoder().decode(props.masterKey());
    } catch (Exception ex) {
      throw new IllegalStateException("Production requires a valid administrator master key");
    }
    if (master.length != 32 || !props.secureCookies())
      throw new IllegalStateException(
          "Production requires a 32-byte master key and Secure cookies");
    if (environment.getProperty("server.forward-headers-strategy", "NONE").equalsIgnoreCase("NONE"))
      throw new IllegalStateException("Production requires an explicitly configured trusted proxy");
    if (!args.containsOption("initialize-admin")
        && !args.containsOption("reset-admin")
        && db.app().queryForObject("SELECT COUNT(*) FROM admin_account", Long.class) != 1)
      throw new IllegalStateException(
          "Initialize the administrator offline before starting production");
    if (!args.containsOption("initialize-admin") && !args.containsOption("reset-admin")) {
      String encrypted =
          db.app()
              .queryForObject("SELECT totp_encrypted FROM admin_account WHERE id=1", String.class);
      cyou.jujube.security.Crypto.decrypt(encrypted, props.masterKey());
    }
    if (options.chatEnabled()
        && (props.deepseekKey() == null
            || props.deepseekKey().isBlank()
            || props.deepseekInputCnyPerMillion() == null
            || props.deepseekInputCnyPerMillion().signum() < 0
            || props.deepseekOutputCnyPerMillion() == null
            || props.deepseekOutputCnyPerMillion().signum() < 0
            || environment.getProperty("spring.mail.host", "").isBlank()
            || props.mailTo() == null
            || props.mailTo().isBlank()
            || props.mailFrom() == null
            || props.mailFrom().isBlank()))
      throw new IllegalStateException(
          "Production chat requires generation credentials, pricing and notification mail configuration");
    if (props.turnstileKey() != null
        && !props.turnstileKey().isBlank()
        && (props.turnstileSiteKey() == null || props.turnstileSiteKey().isBlank()))
      throw new IllegalStateException("Turnstile requires both secret and site key");
    checkLocal();
  }

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  @org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
  public void started() {
    started = true;
  }

  private void recoverTrash() throws java.io.IOException {
    Path trash = props.root().resolve("source-trash");
    if (!Files.isDirectory(trash)) return;
    try (var files = Files.list(trash)) {
      for (Path file :
          files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
        String name = file.getFileName().toString();
        if (!name.matches("[a-f0-9-]{36}\\.(md|txt|pdf|docx|json)")) continue;
        Path original = props.root().resolve("sources").resolve(name);
        if (db.app()
                .queryForObject("SELECT COUNT(*) FROM sources WHERE file_name=?", Long.class, name)
            > 0) {
          if (!Files.exists(original)) Files.move(file, original);
        } else Files.delete(file);
      }
    }
  }

  public void diskSpace() {
    try {
      if (Files.getFileStore(props.root()).getUsableSpace() < options.minFreeDiskMb() * 1024 * 1024)
        throw ApiException.unavailable("DISK_SPACE_LOW", "存储可用空间不足，已暂停新增资料和模型处理，请管理员检查");
    } catch (java.io.IOException ex) {
      throw ApiException.unavailable("STORAGE_UNAVAILABLE", "存储暂时不可用");
    }
  }

  public Map<String, Object> ready() {
    if (!started) throw ApiException.unavailable("NOT_READY", "服务正在完成启动恢复，请稍后重试");
    return checkLocal();
  }

  private Map<String, Object> checkLocal() {
    db.app().queryForObject("SELECT 1", Integer.class);
    db.sessions().queryForObject("SELECT 1", Integer.class);
    if (SchemaMigrations.version(db.app()) != SchemaMigrations.VERSION
        || SchemaMigrations.version(db.sessions()) != SchemaMigrations.VERSION
        || !Files.isWritable(props.root())) throw ApiException.unavailable("NOT_READY", "服务尚未准备就绪");
    return Map.of("status", "UP", "chatEnabled", options.chatEnabled());
  }

  public Map<String, Object> overview() throws java.io.IOException {
    return Map.of(
        "ready",
        ready(),
        "freeDiskBytes",
        Files.getFileStore(props.root()).getUsableSpace(),
        "lastCompletedJobAt",
        db.app()
            .queryForObject(
                "SELECT COALESCE(MAX(updated_at),0) FROM jobs WHERE status='COMPLETED'",
                Long.class),
        "stalledJobs",
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE status='RUNNING' AND updated_at<?",
                Long.class,
                System.currentTimeMillis() - 180_000),
        "uncertainCalls",
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM ai_calls WHERE status IN ('RESERVED','UNCERTAIN')",
                Long.class),
        "failedNotifications",
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM notification_events WHERE status='FAILED'", Long.class),
        "schemaVersion",
        SchemaMigrations.VERSION,
        "tlsExpiresAt",
        tlsExpiry());
  }

  public long tlsExpiry() {
    Path metric = props.root().resolve("monitoring/tls-expiry.epoch");
    try {
      if (!Files.isRegularFile(metric) || Files.size(metric) > 40) return 0;
      return Long.parseLong(Files.readString(metric).strip()) * 1000;
    } catch (Exception ex) {
      return 0;
    }
  }
}
