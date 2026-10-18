package cyou.jujube;

import cyou.jujube.config.CloneProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CloneProperties.class)
public class CloneMeApplication {
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
