package cyou.jujube.quota;

import cyou.jujube.common.ApiException;
import cyou.jujube.storage.Database;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class RateLimits {
  private final Database db;
  private final Clock clock;

  public RateLimits(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public void require(String scope, String identity, int limit, long windowMillis) {
    long bucket = clock.millis() / windowMillis;
    String key = scope + ":" + identity + ":" + bucket;
    int updated =
        db.app()
            .update(
                "INSERT INTO rate_limits(key,count,expires_at) VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET count=count+1 WHERE count<?",
                key,
                (bucket + 1) * windowMillis,
                limit);
    if (updated == 0) throw new ApiException(429, "RATE_LIMITED", "请求较频繁，请稍后再试");
  }
}
