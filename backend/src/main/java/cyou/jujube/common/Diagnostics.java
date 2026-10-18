package cyou.jujube.common;

import cyou.jujube.storage.Database;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 把阶段诊断同时写入数据库和日志，后台可按请求编号还原处理路径。 调用方只传耗时、来源编号、供应商、错误码等诊断信息，不传聊天全文、口令或密钥。 */
@Service
public class Diagnostics {
  private static final Logger LOG = LoggerFactory.getLogger(Diagnostics.class);
  private final Database db;
  private final Clock clock;

  public Diagnostics(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** 保存可检索阶段事件，并临时关联日志请求编号；结束后恢复原有上下文，避免后台任务串号。 */
  public void event(String requestId, String stage, String event, Map<String, ?> details) {
    String detail = Json.write(details);
    db.app()
        .update(
            "INSERT INTO diagnostic_events(request_id,stage,event,detail_json,created_at) VALUES(?,?,?,?,?)",
            requestId,
            stage,
            event,
            detail,
            clock.millis());
    String previous = org.slf4j.MDC.get("requestId");
    org.slf4j.MDC.put("requestId", requestId);
    try {
      if (event.contains("FALLBACK") || event.contains("FAILED") || event.contains("UNAVAILABLE"))
        LOG.warn("stage={} event={} detail={}", stage, event, detail);
      else LOG.info("stage={} event={} detail={}", stage, event, detail);
    } finally {
      if (previous == null) org.slf4j.MDC.remove("requestId");
      else org.slf4j.MDC.put("requestId", previous);
    }
  }
}
