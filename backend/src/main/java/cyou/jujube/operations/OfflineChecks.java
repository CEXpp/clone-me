package cyou.jujube.operations;

import cyou.jujube.common.Json;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.SchemaMigrations;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.*;

/** 不启动 Spring、不运行任务的容器探针与部署前 schema 兼容检查。 */
public final class OfflineChecks {
  private OfflineChecks() {}

  public static void health() throws Exception {
    String port = System.getenv().getOrDefault("PORT", "8080");
    if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid healthcheck port");
    try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health/ready"))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200
          || !"UP".equals(Json.read(response.body()).path("status").asText()))
        throw new IllegalStateException("Application is not ready");
    }
    System.out.println("UP");
  }

  public static void schema(Path root) throws Exception {
    for (String name : java.util.List.of("app", "sessions")) {
      Path file = root.resolve(name + ".db");
      if (!Files.exists(file)) continue;
      SQLiteConfig config = new SQLiteConfig();
      config.setReadOnly(true);
      var source = new SQLiteDataSource(config);
      source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
      var sql = new JdbcTemplate(source);
      if (sql.queryForObject(
              "SELECT COUNT(*) FROM sqlite_master WHERE name='schema_migrations'", Long.class)
          == 0) continue;
      for (var row :
          sql.queryForList("SELECT version,checksum FROM schema_migrations ORDER BY version")) {
        int version = ((Number) row.get("version")).intValue();
        if (version < 1 || version > SchemaMigrations.VERSION)
          throw new IllegalStateException(
              "Database requires a newer application; restore a compatible backup before downgrading");
        String resource =
            version == 1
                ? "schema-" + name + ".sql"
                : "migrations/" + name + "/V" + version + ".sql";
        String script =
            new ClassPathResource(resource)
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        if (!Crypto.hash(script + (version == 1 ? "\nlegacy-adoption-v1" : ""))
            .equals(row.get("checksum")))
          throw new IllegalStateException(
              "Database migration checksum differs from target application");
      }
    }
    System.out.println("Database schemas are compatible with this application");
  }
}
