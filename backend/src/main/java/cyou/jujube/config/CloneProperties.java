package cyou.jujube.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 把 application.yml 中 clone 前缀的配置绑定成只读对象，集中交给各业务组件。 模型密钥来自环境变量，配置对象不得整体写入日志；资料目录由 root 方法统一解析。 */
@ConfigurationProperties("clone")
public record CloneProperties(
    // 文件系统与浏览器安全设置：相对目录基于 Java 进程工作目录，主密钥保护 TOTP 秘密。
    String dataDir,
    boolean secureCookies,
    String masterKey,
    // 生成与语义核验共用 DeepSeek；价格单位为每百万词元的人民币金额，不能传美元原价。
    String deepseekKey,
    String deepseekModel,
    String deepseekUrl,
    BigDecimal deepseekInputCnyPerMillion,
    BigDecimal deepseekOutputCnyPerMillion,
    // 向量端点与模型共同进入索引指纹，修改后需要创建新的发布版本。
    String embeddingKey,
    String embeddingUrl,
    String embeddingModel,
    // 两家决策服务分别保存密钥、端点和价格；当前选择还可由数据库中的运行设置覆盖。
    String decisionProvider,
    String typesafeKey,
    String typesafeModel,
    String typesafeUrl,
    BigDecimal typesafeInputCnyPerMillion,
    String bailianKey,
    String bailianUrl,
    BigDecimal bailianInputCnyPerMillion,
    // 每日额度和累计通知阈值是人民币元，进入预算账本时统一换算成整数微元。
    BigDecimal dailyBudgetCny,
    BigDecimal lifetimeNotifyCny,
    String mailTo,
    String mailFrom,
    // 人机验证：后端秘密不可公开，站点标识可给浏览器，主机名用于核对验证用途。
    String turnstileKey,
    String turnstileSiteKey,
    String turnstileHostname,
    String cookieDomain) {
  public Path root() {
    // 先按字符串绑定配置，再自行转换文件路径，避免可执行 JAR 内的类路径资源转换干扰本地目录解析。
    return Path.of(dataDir).toAbsolutePath().normalize();
  }
}
