package cyou.jujube.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("clone.rerank")
public record RerankProperties(
    String key, String url, String model, BigDecimal inputCnyPerMillion) {}
