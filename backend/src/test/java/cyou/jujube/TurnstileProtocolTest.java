package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import cyou.jujube.common.*;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.security.TurnstileClient;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TurnstileProtocolTest {
  @Test
  void validationIsSingleUseAndChecksHostnameAndAction() throws Exception {
    var used = new AtomicInteger();
    var props =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "clone.turnstile-key",
                        "fixture-secret",
                        "clone.turnstile-hostname",
                        "fixture.test")))
            .bind("clone", Bindable.of(CloneProperties.class))
            .get();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
          assertTrue(body.contains("response=fixture-token"));
          byte[] response =
              Json.write(
                      Map.of(
                          "success",
                          used.incrementAndGet() == 1,
                          "hostname",
                          "fixture.test",
                          "action",
                          "chat"))
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    server.start();
    try {
      var client =
          new TurnstileClient(
              props, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
      assertDoesNotThrow(() -> client.verify("fixture-token"));
      assertEquals(
          "CHALLENGE_INVALID",
          assertThrows(ApiException.class, () -> client.verify("fixture-token")).code());
    } finally {
      server.stop(0);
    }
  }
}
