package cyou.jujube.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("clone")
public record CloneProperties(
    String dataDir,
    boolean secureCookies,
    String masterKey,
    String deepseekKey,
    String deepseekModel,
    String deepseekUrl,
    BigDecimal deepseekInputCnyPerMillion,
    BigDecimal deepseekOutputCnyPerMillion,
    String embeddingKey,
    String embeddingUrl,
    String embeddingModel,
    String decisionProvider,
    String typesafeKey,
    String typesafeModel,
    String typesafeUrl,
    BigDecimal typesafeInputCnyPerMillion,
    String bailianKey,
    String bailianUrl,
    BigDecimal bailianInputCnyPerMillion,
    BigDecimal dailyBudgetCny,
    BigDecimal lifetimeNotifyCny,
    String mailTo,
    String mailFrom,
    String turnstileKey,
    String turnstileSiteKey,
    String turnstileHostname,
    String cookieDomain) {
  public Path root() {
    // Keep filesystem paths out of Spring's classpath Resource-to-Path conversion in packaged JARs.
    return Path.of(dataDir).toAbsolutePath().normalize();
  }
}
