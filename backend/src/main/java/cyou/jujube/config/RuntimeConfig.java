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
public class RuntimeConfig {
  @Bean
  Clock clock() {
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
  DataSource dataSource(CloneProperties props) throws IOException {
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
