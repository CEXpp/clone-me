package cyou.jujube.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 离线管理员维护不启动模型任务、邮件和定时备份。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "clone.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SchedulingConfig {}
