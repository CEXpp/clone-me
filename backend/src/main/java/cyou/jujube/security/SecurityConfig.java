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
        .addFilterBefore(
            new RequestFilter(auth, diagnostics),
            org.springframework.security.web.csrf.CsrfFilter.class);
    return http.build();
  }
}
