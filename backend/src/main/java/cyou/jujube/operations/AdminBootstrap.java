package cyou.jujube.operations;

import cyou.jujube.security.AdminAuth;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 仅响应显式本地初始化参数，创建唯一管理员并一次性展示验证器注册信息。 普通服务启动直接跳过；秘密不写入常规日志，运行者须在私人终端保存后移除明文初始化密码。 */
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
    // 仅在管理员主动执行本地初始化命令时展示注册秘密，便于导入验证器和离线保存恢复码。
    // 正常启动和 HTTP 请求不会进入此分支；这些秘密不能写入常规日志。
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
