package cyou.jujube.storage;

import cyou.jujube.common.ApiException;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.config.RuntimeConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 封装两个 SQLite 数据库及各自的事务边界。 app.db 保存本人资料、索引、认证和费用；sessions.db 单独保存访客会话，便于到期清理及排除备份。
 * 两个库没有跨库原子事务，调用方应分别完成各自职责，不把跨库操作当作一次提交。
 */
@Component
public class Database {
  private final JdbcTemplate app;
  private final JdbcTemplate sessions;
  private final TransactionTemplate transactions;
  private final TransactionTemplate visitorTransactions;

  public Database(DataSource dataSource, CloneProperties props) throws Exception {
    DataSource visitorSource = RuntimeConfig.sqlite(props.root().resolve("sessions.db").toString());
    initialize(dataSource, "schema-app.sql");
    initialize(visitorSource, "schema-sessions.sql");
    app = new JdbcTemplate(dataSource);
    sessions = new JdbcTemplate(visitorSource);
    // 兼容旧版本会话库：先检查列是否存在，再补充回复元数据，保留已有聊天记录。
    if (sessions.queryForList("PRAGMA table_info(messages)").stream()
        .noneMatch(column -> "metadata_json".equals(column.get("name"))))
      sessions.execute("ALTER TABLE messages ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'");
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    visitorTransactions = new TransactionTemplate(new DataSourceTransactionManager(visitorSource));
    // 旧记录无法可靠区分人工草稿和机器草稿，升级时保守保护全部旧候选；迁移可重复执行。
    transaction(
        sql -> {
          if (sql.queryForList("PRAGMA table_info(candidates)").stream()
              .noneMatch(column -> "manually_edited".equals(column.get("name")))) {
            sql.execute(
                "ALTER TABLE candidates ADD COLUMN manually_edited INTEGER NOT NULL DEFAULT 0");
            sql.update("UPDATE candidates SET manually_edited=1");
          }
          return null;
        });
  }

  private void initialize(DataSource source, String schema) throws Exception {
    try (Connection connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection,
          new org.springframework.core.io.support.EncodedResource(
              new ClassPathResource(schema), StandardCharsets.UTF_8));
    }
  }

  public JdbcTemplate app() {
    return app;
  }

  public JdbcTemplate sessions() {
    return sessions;
  }

  /** 在应用库事务内执行工作；异常触发回滚。只放短时数据库操作，不在持锁期间等待模型或邮件。 */
  public <T> T transaction(Function<JdbcTemplate, T> work) {
    return transactions.execute(status -> work.apply(app));
  }

  /** 只操作访客会话库的独立事务，用于成对保存问答和续期。 */
  public <T> T sessionTransaction(Function<JdbcTemplate, T> work) {
    return visitorTransactions.execute(status -> work.apply(sessions));
  }

  public String setting(String key, String fallback) {
    List<String> values =
        app.query("SELECT value FROM settings WHERE key=?", (r, n) -> r.getString(1), key);
    return values.isEmpty() ? fallback : values.getFirst();
  }

  /** 按键更新或插入运行配置，让管理台切换设置无需重启进程。 */
  public void putSetting(String key, String value) {
    app.update(
        "INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        key,
        value);
  }

  public static Map<String, Object> required(JdbcTemplate sql, String query, Object... args) {
    var rows = sql.queryForList(query, args);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  public static String text(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? "" : value.toString();
  }

  public static long number(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? 0 : ((Number) value).longValue();
  }
}
