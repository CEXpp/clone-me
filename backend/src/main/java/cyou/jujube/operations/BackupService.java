package cyou.jujube.operations;

import cyou.jujube.common.ApiException;
import cyou.jujube.common.Json;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.config.RuntimeConfig;
import cyou.jujube.storage.Database;
import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 创建本人资料的一致性备份，排除访客聊天库、管理员认证记录和环境密钥。 先用 SQLite 生成一致性数据库快照，在副本中脱敏，再和原始资料一起压缩。 备份最多保留七份且总量不超过
 * 500MB；下载名称受白名单限制，防止任意路径读取。
 */
@Service
public class BackupService {
  private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
  private static final long MAX_TOTAL = 500L * 1024 * 1024;
  private final Database db;
  private final CloneProperties props;
  private final Clock clock;

  public BackupService(Database db, CloneProperties props, Clock clock) {
    this.db = db;
    this.props = props;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
  public void scheduled() {
    try {
      create();
    } catch (Exception ex) {
      String day = java.time.LocalDate.now(clock).toString();
      db.app()
          .update(
              "INSERT OR IGNORE INTO notification_events(id,kind,subject,body,created_at) VALUES(?,?,?,?,?)",
              "backup:" + day,
              "BACKUP_FAILED",
              "每日备份失败",
              "请在管理后台检查备份与磁盘状态。",
              clock.millis());
      LOG.error("stage=BACKUP event=BACKUP_FAILED errorType={}", ex.getClass().getSimpleName());
    }
  }

  /** 生成一致性副本、删除副本里的认证信息、压缩本人资料，再轮转旧备份；失败时清理本次半成品。 */
  public synchronized Map<String, Object> create() throws IOException {
    Path directory = props.root().resolve("backups");
    Files.createDirectories(directory);
    String id = "backup-" + clock.millis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    Path snapshot = directory.resolve(id + ".db"), archive = directory.resolve(id + ".zip");
    try {
      db.app().execute("VACUUM INTO '" + snapshot.toString().replace("'", "''") + "'");
      var backupDb = new JdbcTemplate(RuntimeConfig.sqlite(snapshot.toString()));
      backupDb.update("DELETE FROM admin_sessions");
      backupDb.update("DELETE FROM recovery_codes");
      backupDb.update("DELETE FROM admin_account");
      backupDb.execute("PRAGMA wal_checkpoint(TRUNCATE)");
      backupDb.execute("PRAGMA journal_mode=DELETE");
      List<Path> originals;
      Path sources = props.root().resolve("sources");
      if (Files.isDirectory(sources)) {
        try (var paths = Files.list(sources)) {
          originals = paths.filter(Files::isRegularFile).toList();
        }
      } else originals = List.of();
      long planned = Files.size(snapshot);
      for (Path path : originals) planned += Files.size(path);
      if (planned > MAX_TOTAL)
        throw ApiException.badRequest("BACKUP_TOO_LARGE", "资料超过备份容量限制，请先调整容量或清理资料");
      try (var zip =
          new ZipOutputStream(Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW))) {
        zip.putNextEntry(new ZipEntry("app.db"));
        Files.copy(snapshot, zip);
        zip.closeEntry();
        for (Path path : originals) {
          zip.putNextEntry(new ZipEntry("sources/" + path.getFileName()));
          Files.copy(path, zip);
          zip.closeEntry();
        }
        var versions =
            backupDb.queryForList(
                "SELECT value FROM settings WHERE key='knowledge.active'", String.class);
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(
            Json.write(
                    Map.of(
                        "schemaVersion",
                        1,
                        "createdAt",
                        clock.millis(),
                        "knowledgeVersion",
                        versions.isEmpty() ? "" : versions.getFirst(),
                        "excluded",
                        List.of(
                            "visitor sessions", "administrator credentials", "API keys", "logs")))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      rotate(directory);
      LOG.info("stage=BACKUP event=BACKUP_CREATED backupId={}", id);
      return Map.of(
          "name",
          archive.getFileName().toString(),
          "bytes",
          Files.size(archive),
          "createdAt",
          clock.millis());
    } catch (Exception ex) {
      Files.deleteIfExists(archive);
      if (ex instanceof IOException io) throw io;
      if (ex instanceof RuntimeException runtime) throw runtime;
      throw new IOException(ex);
    } finally {
      Files.deleteIfExists(snapshot);
      Files.deleteIfExists(directory.resolve(id + ".db-wal"));
      Files.deleteIfExists(directory.resolve(id + ".db-shm"));
    }
  }

  /** 按创建时间删除最早备份，直到份数与总容量同时满足限制；只处理固定命名格式的文件。 */
  private void rotate(Path directory) throws IOException {
    List<Path> files;
    try (var stream = Files.list(directory)) {
      files =
          stream
              .filter(p -> p.getFileName().toString().matches("backup-[0-9]+-[a-f0-9]{8}\\.zip"))
              .sorted(Comparator.comparing(p -> p.getFileName().toString()))
              .toList();
    }
    long total = 0;
    for (Path file : files) total += Files.size(file);
    int remaining = files.size();
    for (Path file : files) {
      if (remaining <= 7 && total <= MAX_TOTAL) break;
      long size = Files.size(file);
      Files.delete(file);
      remaining--;
      total -= size;
    }
  }

  public List<Map<String, Object>> list() throws IOException {
    Path directory = props.root().resolve("backups");
    if (!Files.isDirectory(directory)) return List.of();
    try (var stream = Files.list(directory)) {
      List<Map<String, Object>> result = new ArrayList<>();
      for (Path file :
          stream
              .filter(p -> p.getFileName().toString().matches("backup-[0-9]+-[a-f0-9]{8}\\.zip"))
              .sorted(Comparator.reverseOrder())
              .toList())
        result.add(Map.of("name", file.getFileName().toString(), "bytes", Files.size(file)));
      return result;
    }
  }

  /** 只接受备份文件名白名单，不能利用上级目录片段下载其他文件。 */
  public Path archive(String name) {
    if (!name.matches("backup-[0-9]+-[a-f0-9]{8}\\.zip")) throw ApiException.missing();
    Path path = props.root().resolve("backups").resolve(name);
    if (!Files.isRegularFile(path)) throw ApiException.missing();
    return path;
  }
}
