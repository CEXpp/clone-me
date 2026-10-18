package cyou.jujube.security;

import cyou.jujube.common.Json;
import cyou.jujube.config.CloneProperties;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/** 集中定义接口授权、无服务端 HTTP 会话的认证方式、Cookie 防伪和内容安全策略。 公开读取和访客聊天允许匿名访问；其余 API 必须具备管理员角色，前端路由不能替代这层授权。 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class SecurityConfig {
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noDefaultUser() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Use the administrator login endpoint");
    };
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      AdminAuth auth,
      CloneProperties props,
      cyou.jujube.common.Diagnostics diagnostics)
      throws Exception {
    // CSRF Cookie 允许脚本读取以回传请求头；它不是登录凭证，真正的管理员 Cookie 必须 HttpOnly。
    var csrf = new CookieCsrfTokenRepository();
    csrf.setCookieCustomizer(
        cookie -> cookie.httpOnly(false).secure(props.secureCookies()).sameSite("Lax").path("/"));
    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/public/**",
                        "/api/auth/csrf",
                        "/api/auth/login",
                        "/api/health",
                        "/error")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .permitAll())
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self' https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com; style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, error) -> {
                          response.setStatus(401);
                          response.setContentType("application/json;charset=UTF-8");
                          response
                              .getWriter()
                              .write(
                                  Json.write(
                                      Map.of("code", "AUTH_REQUIRED", "message", "请先登录管理员账号")));
                        })
                    .accessDeniedHandler(
                        (request, response, error) -> {
                          response.setStatus(403);
                          response.setContentType("application/json;charset=UTF-8");
                          response
                              .getWriter()
                              .write(
                                  Json.write(
                                      Map.of(
                                          "code", "ACCESS_DENIED", "message", "请求无权访问或缺少有效防伪令牌")));
                        }))
        // 在防伪检查前恢复身份并设置请求编号，拒绝请求也能关联到管理审计。
        .addFilterBefore(
            new RequestFilter(auth, diagnostics),
            org.springframework.security.web.csrf.CsrfFilter.class);
    return http.build();
  }
}
