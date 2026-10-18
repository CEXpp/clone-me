package cyou.jujube.security;

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
    try {
      if (auth.authenticated(cookie(request, "CLONE_ADMIN")))
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    "owner", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
      chain.doFilter(request, response);
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
      MDC.clear();
    }
  }

  public static String cookie(HttpServletRequest request, String name) {
    if (request.getCookies() != null)
      for (Cookie cookie : request.getCookies())
        if (name.equals(cookie.getName())) return cookie.getValue();
    return null;
  }

  /**
   * Deliberately ignores caller-controlled forwarding headers; trusted-proxy support is explicit
   * deployment configuration.
   */
  public static String ipHash(HttpServletRequest request) {
    return Crypto.hash(request.getRemoteAddr());
  }
}
