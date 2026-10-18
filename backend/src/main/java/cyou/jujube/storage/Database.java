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
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    visitorTransactions = new TransactionTemplate(new DataSourceTransactionManager(visitorSource));
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

  public <T> T transaction(Function<JdbcTemplate, T> work) {
    return transactions.execute(status -> work.apply(app));
  }

  public <T> T sessionTransaction(Function<JdbcTemplate, T> work) {
    return visitorTransactions.execute(status -> work.apply(sessions));
  }

  public String setting(String key, String fallback) {
    List<String> values =
        app.query("SELECT value FROM settings WHERE key=?", (r, n) -> r.getString(1), key);
    return values.isEmpty() ? fallback : values.getFirst();
  }

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
