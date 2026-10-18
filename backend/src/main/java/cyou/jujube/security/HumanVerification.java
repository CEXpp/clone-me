package cyou.jujube.security;

import cyou.jujube.common.ApiException;
import cyou.jujube.common.Json;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.quota.RateLimits;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 在请求风险升高时验证 Turnstile 令牌，服务端核对结果、主机名和用途。 前端拿到令牌不等于验证通过；密钥只留在后端，访客页面只能获取公开站点标识。 */
@Service
public class HumanVerification {
  private final CloneProperties props;
  private final RateLimits limits;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public HumanVerification(CloneProperties props, RateLimits limits) {
    this.props = props;
    this.limits = limits;
  }

  /** 先评估当前请求频率，达到风险门槛后才要求令牌；最终结果必须由后端向验证服务确认。 */
  public void check(String ipHash, String token) {
    if (props.turnstileKey() == null || props.turnstileKey().isBlank()) return;
    boolean suspicious = false;
    try {
      limits.require("risk", ipHash, 3, Duration.ofMinutes(1).toMillis());
    } catch (ApiException ex) {
      suspicious = true;
    }
    if (!suspicious) return;
    if (token == null || token.isBlank() || token.length() > 2048)
      throw new ApiException(403, "CHALLENGE_REQUIRED", "请完成人机验证后继续");
    try {
      String form =
          "secret="
              + URLEncoder.encode(props.turnstileKey(), StandardCharsets.UTF_8)
              + "&response="
              + URLEncoder.encode(token, StandardCharsets.UTF_8);
      var request =
          HttpRequest.newBuilder(
                  URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
              .timeout(Duration.ofSeconds(6))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      var result = Json.read(response.body());
      if (response.statusCode() != 200
          || !result.path("success").asBoolean(false)
          || !props.turnstileHostname().equals(result.path("hostname").asText())
          || !"chat".equals(result.path("action").asText()))
        throw new ApiException(403, "CHALLENGE_INVALID", "人机验证未通过，请重新验证");
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      throw ApiException.unavailable("CHALLENGE_UNAVAILABLE", "验证服务暂时不可用，请稍后再试");
    }
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
