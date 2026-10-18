package cyou.jujube.web;

import cyou.jujube.common.Json;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.conversation.ChatService;
import cyou.jujube.conversation.VisitorSessions;
import cyou.jujube.quota.RateLimits;
import cyou.jujube.security.HumanVerification;
import cyou.jujube.security.RequestFilter;
import cyou.jujube.storage.Database;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

/** 访客接口层，提供公开介绍、风险验证配置、隔离会话和问答。 负责从 Cookie 取会话令牌并转交服务层；续期 Cookie 与数据库过期时间随有效问答同步。 */
@RestController
public class PublicController {
  private final VisitorSessions sessions;
  private final ChatService chat;
  private final CloneProperties props;
  private final Database db;
  private final RateLimits limits;
  private final HumanVerification human;

  public PublicController(
      VisitorSessions sessions,
      ChatService chat,
      CloneProperties props,
      Database db,
      RateLimits limits,
      HumanVerification human) {
    this.sessions = sessions;
    this.chat = chat;
    this.props = props;
    this.db = db;
    this.limits = limits;
    this.human = human;
  }

  @GetMapping("/api/health")
  Map<String, Object> health() {
    db.app().queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "UP");
  }

  @GetMapping("/api/public/profile")
  Object profile() {
    return Json.map(
        db.setting(
            "profile.public",
            "{\"configured\":false,\"displayName\":\"\",\"headline\":\"\",\"bio\":\"\",\"skills\":[],\"projects\":[],\"experiences\":[],\"quickQuestions\":[],\"aiDisclosure\":\"这是 AI 分身，回答依据为本人确认的资料。\"}"));
  }

  @GetMapping("/api/public/challenge")
  Map<String, Object> challenge() {
    return human.publicConfig();
  }

  public record Begin(String challengeToken) {}

  @PostMapping("/api/public/session")
  Map<String, Object> begin(
      @RequestBody(required = false) Begin body,
      HttpServletRequest request,
      HttpServletResponse response) {
    String ip = RequestFilter.ipHash(request);
    limits.require("session-create", ip, 10, Duration.ofMinutes(1).toMillis());
    human.check(ip, body == null ? null : body.challengeToken());
    var created = sessions.create();
    cookie(response, created.token(), Duration.ofDays(5));
    return Map.of("sessionId", created.id(), "expiresAt", created.expiresAt());
  }

  @GetMapping("/api/public/session")
  Map<String, Object> session(HttpServletRequest request) {
    var current = sessions.require(token(request));
    return Map.of(
        "sessionId",
        current.get("id"),
        "expiresAt",
        current.get("expires_at"),
        "messages",
        sessions.history(token(request)));
  }

  @DeleteMapping("/api/public/session")
  Map<String, Object> clear(HttpServletRequest request, HttpServletResponse response) {
    sessions.clear(token(request));
    cookie(response, "", Duration.ZERO);
    return Map.of("cleared", true);
  }

  public record Question(String message, String challengeToken) {}

  @PostMapping("/api/public/chat")
  ChatService.Answer ask(
      @RequestBody Question body, HttpServletRequest request, HttpServletResponse response) {
    String ip = RequestFilter.ipHash(request);
    human.check(ip, body.challengeToken());
    var result = chat.ask(MDC.get("requestId"), token(request), ip, body.message());
    cookie(response, token(request), Duration.ofDays(5));
    return result;
  }

  private String token(HttpServletRequest request) {
    return RequestFilter.cookie(request, "CLONE_VISITOR");
  }

  private void cookie(HttpServletResponse response, String value, Duration age) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("CLONE_VISITOR", value)
            .httpOnly(true)
            .secure(props.secureCookies())
            .sameSite("Lax")
            .path("/")
            .maxAge(age)
            .build()
            .toString());
  }
}
