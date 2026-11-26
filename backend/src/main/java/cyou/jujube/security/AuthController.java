package cyou.jujube.security;

import cyou.jujube.config.CloneProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 提供防伪令牌、管理员登录、身份检查和退出接口。 认证令牌放入 HttpOnly Cookie，前端脚本不能读取；前端可读取的 CSRF 令牌用于防止跨站伪造写请求。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AdminAuth auth;
  private final CloneProperties props;

  public AuthController(AdminAuth auth, CloneProperties props) {
    this.auth = auth;
    this.props = props;
  }

  public record Login(String username, String password, String code) {}

  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/login")
  Map<String, Object> login(
      @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
    String token =
        auth.login(body.username(), body.password(), body.code(), RequestFilter.ipHash(request));
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("CLONE_ADMIN", token)
            .httpOnly(true)
            .secure(props.secureCookies())
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ofHours(8))
            .build()
            .toString());
    return Map.of("authenticated", true);
  }

  @GetMapping("/me")
  Map<String, Object> me() {
    // 此方法不再验证密码：到达这里说明过滤器已恢复身份，安全路径规则已允许管理员访问。
    return Map.of("authenticated", true, "role", "ADMIN");
  }

  @PostMapping("/logout")
  Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
    auth.logout(RequestFilter.cookie(request, "CLONE_ADMIN"));
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("CLONE_ADMIN", "")
            .httpOnly(true)
            .secure(props.secureCookies())
            .sameSite("Lax")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
    return Map.of("authenticated", false);
  }
}
