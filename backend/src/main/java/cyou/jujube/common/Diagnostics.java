package cyou.jujube.common;

import cyou.jujube.storage.Database;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class Diagnostics {
  private static final Logger LOG = LoggerFactory.getLogger(Diagnostics.class);
  private final Database db;
  private final Clock clock;

  public Diagnostics(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

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
