package cyou.jujube.security;

import cyou.jujube.common.ApiException;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.quota.RateLimits;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 在请求风险升高时验证 Turnstile 令牌，服务端核对结果、主机名和用途。 前端拿到令牌不等于验证通过；密钥只留在后端，访客页面只能获取公开站点标识。 */
@Service
public class HumanVerification {
  private final CloneProperties props;
  private final RateLimits limits;
  private final TurnstileClient client;

  public HumanVerification(CloneProperties props, RateLimits limits, TurnstileClient client) {
    this.props = props;
    this.limits = limits;
    this.client = client;
  }

  /** 先评估当前请求频率，达到风险门槛后才要求令牌；最终结果必须由后端向验证服务确认。 */
  public boolean check(String ipHash, String token) {
    if (props.turnstileKey() == null || props.turnstileKey().isBlank()) return false;
    boolean suspicious = false;
    try {
      limits.require("risk", ipHash, 3, Duration.ofMinutes(1).toMillis());
    } catch (ApiException ex) {
      suspicious = true;
    }
    if (!suspicious) return false;
    if (token == null || token.isBlank() || token.length() > 2048)
      throw new ApiException(403, "CHALLENGE_REQUIRED", "请完成人机验证后继续");
    client.verify(token);
    return true;
  }

  public Map<String, Object> publicConfig() {
    return Map.of(
        "configured",
        props.turnstileKey() != null && !props.turnstileKey().isBlank(),
        "siteKey",
        props.turnstileSiteKey() == null ? "" : props.turnstileSiteKey(),
        "action",
        "chat");
  }
}
