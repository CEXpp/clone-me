package cyou.jujube.config;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

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
