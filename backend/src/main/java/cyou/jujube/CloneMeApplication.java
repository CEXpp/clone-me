package cyou.jujube;

import cyou.jujube.config.CloneProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 应用入口，统一处理正常服务启动和两个本地维护命令。 生成主密钥时不启动 Spring；初始化管理员时启动无 Web 容器的上下文，完成后关闭。
 * 普通启动才对外提供接口，避免把管理员注册能力暴露成远程接口。
 */
@SpringBootApplication
@EnableConfigurationProperties({
  CloneProperties.class,
  cyou.jujube.config.OperationsProperties.class
})
public class CloneMeApplication {
  /** 根据命令行选择运行模式。主密钥命令立即返回；初始化模式不监听端口，完成后关闭上下文。 */
  public static void main(String[] args) {
    if ((java.util.Arrays.asList(args).contains("--healthcheck") && args.length != 1)
        || (java.util.Arrays.asList(args).contains("--check-data-schema")
            && (args.length != 2 || !args[0].equals("--check-data-schema")))
        || (java.util.Arrays.asList(args).contains("--restore-backup")
            && (args.length != 3 || !args[0].equals("--restore-backup"))))
      throw new IllegalArgumentException(
          "Invalid offline command arguments; specify all required paths");
    if ((args.length == 1 && "--healthcheck".equals(args[0]))
        || (args.length == 2 && "--check-data-schema".equals(args[0]))) {
      try {
        if (args.length == 1) cyou.jujube.operations.OfflineChecks.health();
        else cyou.jujube.operations.OfflineChecks.schema(java.nio.file.Path.of(args[1]));
        return;
      } catch (Exception ex) {
        throw new IllegalStateException(
            "Offline check failed: " + ex.getClass().getSimpleName(), ex);
      }
    }
    if (args.length == 3 && "--restore-backup".equals(args[0])) {
      try {
        cyou.jujube.operations.BackupRestore.restore(
            java.nio.file.Path.of(args[1]), java.nio.file.Path.of(args[2]));
        System.out.println("备份校验和离线恢复完成；请配置新主密钥并初始化管理员后再启动服务。");
        return;
      } catch (Exception ex) {
        throw new IllegalStateException(
            "Backup restore failed: " + ex.getClass().getSimpleName(), ex);
      }
    }
    if (java.util.Arrays.asList(args).contains("--generate-master-key")) {
      System.out.println(
          java.util.Base64.getEncoder()
              .encodeToString(
                  java.util.Base64.getUrlDecoder().decode(cyou.jujube.security.Crypto.token())));
      return;
    }
    var application = new SpringApplication(CloneMeApplication.class);
    boolean backup = java.util.Arrays.asList(args).contains("--backup-data");
    boolean initialize =
        java.util.Arrays.asList(args).contains("--initialize-admin")
            || java.util.Arrays.asList(args).contains("--reset-admin")
            || backup;
    if (initialize) {
      System.setProperty("clone.scheduler-enabled", "false");
      application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    }
    var context = application.run(args);
    if (backup) {
      try {
        System.out.println(
            "BACKUP_FILE="
                + context.getBean(cyou.jujube.operations.BackupService.class).create().get("name"));
      } catch (java.io.IOException ex) {
        throw new IllegalStateException("Offline backup failed", ex);
      } finally {
        context.close();
      }
      return;
    }
    if (initialize) context.close();
  }
}
