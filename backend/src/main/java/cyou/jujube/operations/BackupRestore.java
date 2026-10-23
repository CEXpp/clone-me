package cyou.jujube.operations;

import cyou.jujube.common.Json;
import cyou.jujube.config.RuntimeConfig;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.SchemaMigrations;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.springframework.jdbc.core.JdbcTemplate;

/** 离线恢复到新目录；先校验全部内容，再原子交付，不覆盖已有运行数据。 */
public final class BackupRestore {
  private static final long MAX_BYTES = 500L * 1024 * 1024;

  private BackupRestore() {}

  public static void restore(Path archive, Path destination) throws Exception {
    destination = destination.toAbsolutePath().normalize();
    if (Files.exists(destination)) throw new IOException("Restore destination must not exist");
    Path parent = destination.getParent();
    if (parent == null || !Files.isDirectory(parent))
      throw new IOException("Restore parent directory must exist");
    Path staging = Files.createTempDirectory(parent, ".clone-restore-");
    boolean published = false;
    try (var zip = new ZipFile(archive.toFile())) {
      var manifestEntry = zip.getEntry("manifest.json");
      if (manifestEntry == null || manifestEntry.getSize() > 100_000)
        throw new IOException("Missing or oversized manifest");
      byte[] manifestBytes;
      try (var input = zip.getInputStream(manifestEntry)) {
        manifestBytes = input.readNBytes(100_001);
      }
      if (manifestBytes.length > 100_000) throw new IOException("Manifest too large");
      var manifest = Json.read(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8));
      if (manifest.path("formatVersion").asInt() != 2
          || manifest.path("schemaVersion").asInt() > SchemaMigrations.VERSION
          || manifest.path("schemaVersion").asInt() < 1
          || !manifest.path("sha256").isObject())
        throw new IOException("Unsupported or invalid backup version");
      var expected = manifest.path("sha256");
      Set<String> seen = new HashSet<>();
      long total = 0;
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        String name = entry.getName();
        if (!seen.add(name)) throw new IOException("Duplicate archive entry");
        if ("manifest.json".equals(name)) continue;
        if (entry.isDirectory()
            || !("app.db".equals(name)
                || name.matches("sources/[a-f0-9-]{36}\\.(md|txt|pdf|docx|json)"))
            || !expected.path(name).isTextual()) throw new IOException("Unexpected archive entry");
        Path file = staging.resolve(name).normalize();
        if (!file.startsWith(staging)) throw new IOException("Archive path escapes destination");
        Files.createDirectories(file.getParent());
        try (var input = zip.getInputStream(entry);
            var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) throw new IOException("Backup exceeds restore limit");
            output.write(buffer, 0, read);
          }
        }
        if (!Crypto.hash(file).equals(expected.path(name).asText()))
          throw new IOException("Backup checksum mismatch");
      }
      if (seen.size() != expected.size() + 1 || !seen.contains("app.db"))
        throw new IOException("Backup files are missing");
      var db = new JdbcTemplate(RuntimeConfig.sqlite(staging.resolve("app.db").toString()));
      if (!"ok".equals(db.queryForObject("PRAGMA integrity_check", String.class))
          || !db.queryForList("PRAGMA foreign_key_check").isEmpty())
        throw new IOException("Backup database integrity check failed");
      if (SchemaMigrations.version(db) != manifest.path("schemaVersion").asInt())
        throw new IOException("Database version differs from manifest");
      for (String table : List.of("admin_account", "admin_sessions", "recovery_codes"))
        if (db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class) != 0)
          throw new IOException("Backup contains authentication records");
      for (var row : db.queryForList("SELECT file_name,hash FROM sources")) {
        String name = "sources/" + row.get("file_name");
        if (!seen.contains(name) || !expected.path(name).asText().equals(row.get("hash")))
          throw new IOException("Source reference is missing or damaged");
      }
      String active = manifest.path("knowledgeVersion").asText();
      var versions =
          db.queryForList("SELECT value FROM settings WHERE key='knowledge.active'", String.class);
      if (!active.equals(versions.isEmpty() ? "" : versions.getFirst())
          || (!active.isBlank()
              && db.queryForObject(
                      "SELECT COUNT(*) FROM releases WHERE id=? AND status='READY'",
                      Long.class,
                      active)
                  != 1)) throw new IOException("Invalid active knowledge version");
      // 恢复只交付离线数据。供应商状态不确定的调用由启动恢复保留额度并等待对账。
      db.execute("PRAGMA wal_checkpoint(TRUNCATE)");
      Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
      published = true;
    } finally {
      if (!published) deleteStaging(staging);
    }
  }

  private static void deleteStaging(Path staging) throws IOException {
    // 只清理本方法创建的临时目录，绝不接受外部清理目标。
    try (var paths = Files.walk(staging)) {
      for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
    }
  }
}
