package cyou.jujube.security;

import cyou.jujube.common.FlowLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 为请求分配追踪编号，恢复管理员身份，并记录管理写操作的脱敏审计。 必须先于 CSRF 过滤器执行，才能关联被安全层拒绝的请求；结束时清理线程诊断上下文。 */
public class RequestFilter extends OncePerRequestFilter {
  private final AdminAuth auth;
  private final cyou.jujube.common.Diagnostics diagnostics;
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(RequestFilter.class);

  public RequestFilter(AdminAuth auth, cyou.jujube.common.Diagnostics diagnostics) {
    this.auth = auth;
    this.diagnostics = diagnostics;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    MDC.put("requestId", requestId);
    response.setHeader("X-Request-Id", requestId);
    long started = System.nanoTime();
    // 只记录方法和接口路径，不记录查询参数、请求体、Cookie 或请求头。
    FlowLog.step(
        "HTTP",
        "收到请求，准备恢复身份并进入过滤器链",
        java.util.Map.of(
            "method", request.getMethod(), "path", FlowLog.path(request.getRequestURI())));
    boolean completed = false;
    try {
      if (auth.authenticated(cookie(request, "CLONE_ADMIN")))
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    "owner", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
      FlowLog.step(
          "AUTH",
          "身份检查完成，继续执行安全规则与控制器",
          java.util.Map.of(
              "admin", SecurityContextHolder.getContext().getAuthentication() != null));
      chain.doFilter(request, response);
      completed = true;
      // chain 表示继续执行后续过滤器及控制器；finally 在正常返回和异常时都会执行。
    } finally {
      if (!"GET".equals(request.getMethod())
          && (request.getRequestURI().startsWith("/api/admin/")
              || request.getRequestURI().startsWith("/api/auth/"))) {
        try {
          diagnostics.event(
              requestId,
              "ADMIN",
              "ADMIN_OPERATION",
              java.util.Map.of(
                  "method",
                  request.getMethod(),
                  "path",
                  request.getRequestURI(),
                  "status",
                  response.getStatus(),
                  "durationMs",
                  (System.nanoTime() - started) / 1_000_000));
        } catch (Exception ex) {
          LOG.warn(
              "stage=ADMIN event=AUDIT_WRITE_FAILED errorType={}", ex.getClass().getSimpleName());
        }
      }
      // 未捕获异常抛出时，容器可能尚未把状态码改为 500，因此额外记录链是否正常返回。
      FlowLog.step(
          "HTTP",
          "本次过滤器链结束",
          java.util.Map.of(
              "status",
              response.getStatus(),
              "completed",
              completed,
              "durationMs",
              (System.nanoTime() - started) / 1_000_000));
      MDC.clear();
      // 线程会被复用，必须清掉本次编号，防止下一次请求日志串到上一个访客的链路。
    }
  }

  public static String cookie(HttpServletRequest request, String name) {
    if (request.getCookies() != null)
      for (Cookie cookie : request.getCookies())
        if (name.equals(cookie.getName())) return cookie.getValue();
    return null;
  }

  /** 只使用连接对端地址计算限流标识，忽略访客可伪造的转发头。 部署 Nginx 后，需要明确配置可信代理和真实客户端地址，不能直接信任任意请求头。 */
  public static String ipHash(HttpServletRequest request) {
    return Crypto.hash(request.getRemoteAddr());
  }
}
