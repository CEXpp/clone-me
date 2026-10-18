package cyou.jujube.quota;

import cyou.jujube.common.ApiException;
import cyou.jujube.storage.Database;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** 使用 SQLite 的条件更新维护固定时间窗计数，限制访客、地址及登录请求频率。 计数随进程重启保留；标识由调用方提供哈希或内部编号，避免存储原始 IP 地址。 */
@Service
public class RateLimits {
  private final Database db;
  private final Clock clock;

  public RateLimits(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** 在固定窗口内原子计数，超过限额抛出可识别业务错误；同一身份的并发请求不能绕过计数。 */
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
