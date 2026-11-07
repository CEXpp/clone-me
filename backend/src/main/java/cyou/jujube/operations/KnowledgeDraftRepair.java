package cyou.jujube.operations;

import cyou.jujube.config.*;
import cyou.jujube.ingestion.SourceService;
import cyou.jujube.storage.Database;
import java.nio.file.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 停服时修复旧知识草稿：独占目录、先备份，再复用线上同一整理逻辑；不启动模型和后台任务。 */
public final class KnowledgeDraftRepair {
  private KnowledgeDraftRepair() {}

  public static Map<String, Object> rebuild(Path directory, String sourceId) throws Exception {
    Path root = directory.toAbsolutePath().normalize();
    if (!Files.isRegularFile(root.resolve("app.db")) || !sourceId.matches("[a-f0-9-]{36}"))
      throw new IllegalArgumentException("Existing data directory and source UUID are required");
    try (var lock = new DataDirectoryLock(root)) {
      var dataSource = RuntimeConfig.sqlite(root.resolve("app.db").toString());
      var sql = new JdbcTemplate(dataSource);
      Database.required(sql, "SELECT id FROM sources WHERE id=?", sourceId);
      Path backup =
          root.resolve("review-backups")
              .resolve("before-knowledge-drafts-" + java.util.UUID.randomUUID() + ".db");
      Files.createDirectories(backup.getParent());
      sql.update("VACUUM INTO ?", backup.toString());
      var props =
          new Binder(new MapConfigurationPropertySource(Map.of("clone.data-dir", root.toString())))
              .bind("clone", Bindable.of(CloneProperties.class))
              .get();
      var db = new Database(dataSource, props);
      var result =
          new java.util.LinkedHashMap<String, Object>(
              new SourceService(db, props, Clock.systemUTC()).rebuildKnowledge(sourceId));
      result.put("backup", backup.toString());
      return result;
    }
  }
}
