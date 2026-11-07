package cyou.jujube.storage;

import cyou.jujube.common.ApiException;
import cyou.jujube.common.FlowLog;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.config.RuntimeConfig;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
    // DataSource 负责提供连接，JdbcTemplate 负责执行 SQL、绑定参数和把结果转换成 Java 数据。
    // 先迁移表结构再开放查询；完整结构要同时看 schema-*.sql 和 migrations 目录。
    DataSource visitorSource = RuntimeConfig.sqlite(props.root().resolve("sessions.db").toString());
    FlowLog.step("DATABASE", "开始检查并迁移应用库和访客库表结构", Map.of());
    SchemaMigrations.migrate(dataSource, "app");
    SchemaMigrations.migrate(visitorSource, "sessions");
    app = new JdbcTemplate(dataSource);
    sessions = new JdbcTemplate(visitorSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    visitorTransactions = new TransactionTemplate(new DataSourceTransactionManager(visitorSource));
    FlowLog.step("DATABASE", "两个数据库和独立事务管理器已就绪", Map.of());
  }

  public JdbcTemplate app() {
    return app;
  }

  public JdbcTemplate sessions() {
    return sessions;
  }

  /** 在应用库事务内执行工作；异常触发回滚。只放短时数据库操作，不在持锁期间等待模型或邮件。 */
  public <T> T transaction(Function<JdbcTemplate, T> work) {
    // 调用处 sql -> { ... } 是回调：这里将 app 传给它，并把其返回值作为事务结果。
    // T 是泛型占位符，既可返回任务编号，也可返回 Map；只执行写入时通常返回 null。
    return executeTransaction("app", transactions, app, work);
  }

  /** 只操作访客会话库的独立事务，用于成对保存问答和续期。 */
  public <T> T sessionTransaction(Function<JdbcTemplate, T> work) {
    return executeTransaction("sessions", visitorTransactions, sessions, work);
  }

  /** execute 正常返回表示当前事务边界已完成；嵌套调用可能仍在等待外层事务最终提交。 */
  private <T> T executeTransaction(
      String database,
      TransactionTemplate template,
      JdbcTemplate sql,
      Function<JdbcTemplate, T> work) {
    long started = System.nanoTime();
    FlowLog.step("DATABASE", "进入事务边界", Map.of("database", database));
    try {
      T result = template.execute(status -> work.apply(sql));
      FlowLog.step(
          "DATABASE",
          "事务边界正常返回；嵌套事务以外层提交为准",
          Map.of("database", database, "durationMs", (System.nanoTime() - started) / 1_000_000));
      return result;
    } catch (RuntimeException ex) {
      // 不输出异常消息及 SQL，它们可能含业务原文；保留类型帮助定位。
      FlowLog.step(
          "DATABASE",
          "事务异常退出，已触发回滚或标记外层事务回滚",
          Map.of("database", database, "errorType", ex.getClass().getSimpleName()));
      throw ex;
    }
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
    // SQL 中的 ? 与 args 按顺序对应，值通过参数绑定传入，不拼接到 SQL 文本中。
    // 查询结果一行是一个 Map：键是列名，值可能为字符串、整数或二进制等不同类型。
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
