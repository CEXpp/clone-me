package cyou.jujube.operations;

import cyou.jujube.security.AdminAuth;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
  private final AdminAuth auth;

  public AdminBootstrap(AdminAuth auth) {
    this.auth = auth;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!args.containsOption("initialize-admin")) return;
    String username = System.getenv().getOrDefault("CLONE_ADMIN_USERNAME", "owner"),
        password = System.getenv("CLONE_ADMIN_PASSWORD");
    var result = auth.initialize(username, password);
    // This is an explicit local enrollment command, never an HTTP endpoint or normal application
    // log.
    System.out.println("管理员已创建。以下信息仅显示一次，请在私人终端中保存。不要粘贴到聊天、工单或日志平台。");
    System.out.println("TOTP secret: " + result.secret());
    System.out.println(
        "otpauth://totp/CloneMe:"
            + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "?secret="
            + result.secret()
            + "&issuer=CloneMe");
    System.out.println("Recovery codes:");
    result.recoveryCodes().forEach(System.out::println);
  }
}
