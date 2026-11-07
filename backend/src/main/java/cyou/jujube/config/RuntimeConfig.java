package cyou.jujube.config;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** 提供全局时钟、轻量任务调度器和 SQLite 数据源。 通过注入 Clock 让过期和预算逻辑可在测试中控制时间；后台任务只使用两个调度线程。 */
@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(RerankProperties.class)
public class RuntimeConfig {
  @Bean
  Clock clock() {
    // @Bean 表示返回值交给 Spring 管理；构造器里需要 Clock 的服务会拿到此对象。
    // UTC 时钟提供统一时间点；“预算属于哪一天”在 BudgetService 中另按北京时间换算。
    return Clock.systemUTC();
  }

  @Bean
  org.springframework.scheduling.TaskScheduler taskScheduler() {
    var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("clone-jobs-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(30);
    return scheduler;
  }

  @Bean
  DataDirectoryLock dataDirectoryLock(CloneProperties props) throws IOException {
    return new DataDirectoryLock(props.root());
  }

  @Bean
  DataSource dataSource(CloneProperties props, DataDirectoryLock dataDirectoryLock)
      throws IOException {
    // 即使方法体不读取 dataDirectoryLock，参数依赖也保证先取得目录锁，再打开数据库。
    Files.createDirectories(props.root());
    return sqlite(props.root().resolve("app.db").toString());
  }

  /** 统一 SQLite 连接规则：启用外键，繁忙时最多等待五秒，使用 WAL 允许读写交错。 */
  public static DataSource sqlite(String file) {
    SQLiteConfig config = new SQLiteConfig();
    config.enforceForeignKeys(true);
    config.setBusyTimeout(5000);
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    SQLiteDataSource source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + file);
    return source;
  }
}
