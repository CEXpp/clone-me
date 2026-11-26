package cyou.jujube.storage;

import cyou.jujube.security.Crypto;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 每个库分别记录迁移版本和校验和；拒绝被修改的已执行脚本和未来版本，不做自动降级。 */
public final class SchemaMigrations {
  public static final int VERSION = 4;

  private SchemaMigrations() {}

  public static void migrate(DataSource source, String database) throws Exception {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var statement = connection.createStatement()) {
          statement.execute(
              "CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT NOT NULL,applied_at INTEGER NOT NULL)");
        }
        var applied = new java.util.LinkedHashMap<Integer, String>();
        try (var statement = connection.createStatement();
            var rows =
                statement.executeQuery(
                    "SELECT version,checksum FROM schema_migrations ORDER BY version")) {
          while (rows.next()) applied.put(rows.getInt(1), rows.getString(2));
        }
        if (applied.keySet().stream().anyMatch(v -> v < 1 || v > VERSION))
          throw new IllegalStateException(
              "Unsupported database schema; use a compatible application version");
        for (int version = 1; version <= VERSION; version++) {
          // 初始建表是版本 1，后续变更按 V2、V3……依次执行，各库独立登记进度。
          String name =
              version == 1
                  ? "schema-" + database + ".sql"
                  : "migrations/" + database + "/V" + version + ".sql";
          var resource = new ClassPathResource(name);
          String script = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
          String checksum = Crypto.hash(script + (version == 1 ? "\nlegacy-adoption-v1" : ""));
          // 校验和包含 SQL 注释，所以已执行脚本连注释也不能直接改；新变更要新增迁移版本。
          if (applied.containsKey(version)) {
            if (!checksum.equals(applied.get(version)))
              throw new IllegalStateException("Database migration checksum mismatch: " + name);
            continue;
          }
          if (applied.keySet().stream().anyMatch(v -> v > applied.size()))
            throw new IllegalStateException("Database migration history has a gap");
          ScriptUtils.executeSqlScript(
              connection, new EncodedResource(resource, StandardCharsets.UTF_8));
          if (version == 1) adoptLegacy(connection, database);
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO schema_migrations(version,checksum,applied_at) VALUES(?,?,?)")) {
            insert.setInt(1, version);
            insert.setString(2, checksum);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
          }
          applied.put(version, checksum);
        }
        connection.commit();
      } catch (Exception ex) {
        connection.rollback();
        throw ex;
      }
    }
  }

  private static void adoptLegacy(Connection connection, String database) throws Exception {
    // 接管旧库时只补缺列。旧候选视为人工编辑过，防止新提取任务覆盖历史整理成果。
    String table = "app".equals(database) ? "candidates" : "messages";
    String column = "app".equals(database) ? "manually_edited" : "metadata_json";
    var columns = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) columns.add(rows.getString("name"));
    }
    if (columns.contains(column)) return;
    try (var statement = connection.createStatement()) {
      if ("app".equals(database)) {
        statement.execute(
            "ALTER TABLE candidates ADD COLUMN manually_edited INTEGER NOT NULL DEFAULT 0");
        statement.executeUpdate("UPDATE candidates SET manually_edited=1");
      } else
        statement.execute(
            "ALTER TABLE messages ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'");
    }
  }

  public static int version(JdbcTemplate database) {
    return database.queryForObject(
        "SELECT COALESCE(MAX(version),0) FROM schema_migrations", Integer.class);
  }
}
