package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cyou.jujube.common.FlowLog;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** 检查日志的请求关联、关闭开关及路径脱敏，不连接数据库或外部模型。 */
class FlowLogTest {
  @Test
  void pathOmitsPrivateQueryAndDynamicNames() {
    assertEquals(
        "/api/public/requests/:id", FlowLog.path("/api/public/requests/private-key?q=secret"));
    assertEquals("/api/admin/backups/:id", FlowLog.path("/api/admin/backups/private-name.zip"));
    assertEquals("/api/health?", FlowLog.path("/api/health\n"));
  }

  @Test
  void debugSwitchAndRequestContextWorkWithoutDatabase() {
    Logger logger = (Logger) LoggerFactory.getLogger(FlowLog.class);
    Level previousLevel = logger.getLevel();
    String previousRequest = MDC.get("requestId");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      MDC.put("requestId", "flow-test-request");
      logger.setLevel(Level.DEBUG);
      FlowLog.step("CHAT", "开始检索", Map.of("evidenceCount", 2));
      assertEquals(1, appender.list.size());
      ILoggingEvent event = appender.list.getFirst();
      assertTrue(event.getFormattedMessage().contains("开始检索"));
      assertEquals("flow-test-request", event.getMDCPropertyMap().get("requestId"));
      logger.setLevel(Level.INFO);
      FlowLog.step("CHAT", "关闭详细流程日志后不输出", Map.of());
      assertEquals(1, appender.list.size());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(previousLevel);
      if (previousRequest == null) MDC.remove("requestId");
      else MDC.put("requestId", previousRequest);
    }
  }
}
