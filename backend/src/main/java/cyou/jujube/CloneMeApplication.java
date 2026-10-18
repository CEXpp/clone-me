package cyou.jujube;

import cyou.jujube.config.CloneProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口，统一处理正常服务启动和两个本地维护命令。 生成主密钥时不启动 Spring；初始化管理员时启动无 Web 容器的上下文，完成后关闭。
 * 普通启动才对外提供接口，避免把管理员注册能力暴露成远程接口。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CloneProperties.class)
public class CloneMeApplication {
  /** 根据命令行选择运行模式。主密钥命令立即返回；初始化模式不监听端口，完成后关闭上下文。 */
  public static void main(String[] args) {
    if (java.util.Arrays.asList(args).contains("--generate-master-key")) {
      System.out.println(
          java.util.Base64.getEncoder()
              .encodeToString(
                  java.util.Base64.getUrlDecoder().decode(cyou.jujube.security.Crypto.token())));
      return;
    }
    var application = new SpringApplication(CloneMeApplication.class);
    boolean initialize = java.util.Arrays.asList(args).contains("--initialize-admin");
    if (initialize)
      application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    var context = application.run(args);
    if (initialize) context.close();
  }
}
