package cyou.jujube.security;

import cyou.jujube.common.*;
import cyou.jujube.config.CloneProperties;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** 服务端验证协议独立于风险计数，测试可注入本地 HTTP 端点。 */
@Component
public class TurnstileClient {
  private final CloneProperties props;
  private final URI endpoint;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @org.springframework.beans.factory.annotation.Autowired
  public TurnstileClient(CloneProperties props) {
    this(props, URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify"));
  }

  public TurnstileClient(CloneProperties props, URI endpoint) {
    this.props = props;
    this.endpoint = endpoint;
  }

  public void verify(String token) {
    try {
      String form =
          "secret="
              + URLEncoder.encode(props.turnstileKey(), StandardCharsets.UTF_8)
              + "&response="
              + URLEncoder.encode(token, StandardCharsets.UTF_8);
      var response =
          http.send(
              HttpRequest.newBuilder(endpoint)
                  .timeout(Duration.ofSeconds(6))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw ApiException.unavailable("CHALLENGE_UNAVAILABLE", "验证服务暂时不可用");
      var value = Json.model(response.body());
      if (!value.path("success").asBoolean(false)
          || !props.turnstileHostname().equals(value.path("hostname").asText())
          || !"chat".equals(value.path("action").asText()))
        throw new ApiException(403, "CHALLENGE_INVALID", "人机验证未通过，请重新验证");
    } catch (ApiException ex) {
      if (ex.code().startsWith("CHALLENGE_")) throw ex;
      throw ApiException.unavailable("CHALLENGE_UNAVAILABLE", "验证服务返回的数据无效，请稍后再试");
    } catch (Exception ex) {
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      throw ApiException.unavailable("CHALLENGE_UNAVAILABLE", "验证服务暂时不可用，请稍后再试");
    }
  }
}
