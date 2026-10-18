package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import cyou.jujube.ai.*;
import cyou.jujube.common.*;
import cyou.jujube.config.*;
import cyou.jujube.quota.BudgetService;
import cyou.jujube.storage.Database;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 使用本机 HTTP 测试服务调用真实供应商适配代码，检查请求结构、用量和失败处理。 不使用外部密钥或付费请求；协议通过不代表真实账户可用或模型回答效果达标。 */
class ProviderProtocolTest {
  @TempDir Path temp;
  HttpServer server;
  String base, requestPath, authorization, requestBody;
  String responseBody = "{}";
  int responseStatus = 200;
  final AtomicInteger requests = new AtomicInteger();
  CloneProperties props;
  Database db;
  BudgetService budget;
  Diagnostics diagnostics;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          requestPath = exchange.getRequestURI().getPath();
          authorization = exchange.getRequestHeaders().getFirst("Authorization");
          requestBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(responseStatus, response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    props = properties(Map.of());
    db = new Database(RuntimeConfig.sqlite(temp.resolve("app.db").toString()), props);
    budget = new BudgetService(db, Clock.systemUTC(), props);
    diagnostics = new Diagnostics(db, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  CloneProperties properties(Map<String, Object> overrides) {
    Map<String, Object> values = new HashMap<>();
    values.put("clone.data-dir", temp.toString());
    values.put("clone.daily-budget-cny", "2");
    values.put("clone.lifetime-notify-cny", "5");
    values.put("clone.deepseek-key", "fixture-key");
    values.put("clone.deepseek-model", "deepseek-flash");
    values.put("clone.deepseek-url", base + "/v1");
    values.put("clone.deepseek-input-cny-per-million", "1");
    values.put("clone.deepseek-output-cny-per-million", "2");
    values.put("clone.embedding-key", "fixture-key");
    values.put("clone.embedding-url", base + "/v1");
    values.put("clone.embedding-model", "BAAI/bge-m3");
    values.put("clone.decision-provider", "typesafe");
    values.put("clone.typesafe-key", "fixture-key");
    values.put("clone.typesafe-url", base + "/v1/systemone");
    values.put("clone.typesafe-model", "jev-latest");
    values.put("clone.typesafe-input-cny-per-million", "1");
    values.put("clone.bailian-key", "fixture-key");
    values.put("clone.bailian-url", base + "/compatible-mode/v1/systemone");
    values.put("clone.bailian-input-cny-per-million", "1");
    values.putAll(overrides);
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("clone", Bindable.of(CloneProperties.class))
        .get();
  }

  Map<String, Object> lastCall() {
    return db.app().queryForMap("SELECT * FROM ai_calls ORDER BY created_at DESC LIMIT 1");
  }

  String chatResponse(boolean usage) {
    return "{\"id\":\"chat-fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"supported\\\":true}\"},\"finish_reason\":\"stop\"}]"
        + (usage
            ? ",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120}"
            : "")
        + "}";
  }

  @Test
  void springAiUsesChatCompletionsAndCountsReportedUsage() {
    responseBody = chatResponse(true);
    String answer =
        new DeepSeekProvider(props, budget, diagnostics)
            .generate("fixture", "ANSWER_CHECK", "JSON only", "check facts");
    assertTrue(Json.read(answer).path("supported").asBoolean());
    assertEquals("/v1/chat/completions", requestPath);
    assertEquals("Bearer fixture-key", authorization);
    var request = Json.read(requestBody);
    assertEquals("disabled", request.path("thinking").path("type").asText());
    assertEquals("deepseek-flash", request.path("model").asText());
    assertEquals(140L, Database.number(lastCall(), "actual"));
    assertEquals("SETTLED", lastCall().get("status"));
  }

  @Test
  void missingGenerationUsageSettlesConservativeReservation() {
    responseBody = chatResponse(false);
    new DeepSeekProvider(props, budget, diagnostics)
        .generate("fixture", "GENERATION", "JSON only", "question");
    var call = lastCall();
    assertTrue(Database.number(call, "actual") > 0);
    assertEquals(Database.number(call, "reserved"), Database.number(call, "actual"));
  }

  @Test
  void paidHttpFailureIsNotRetriedAndKeepsReservation() {
    responseStatus = 503;
    responseBody = "{\"error\":{\"message\":\"fixture unavailable\",\"type\":\"server_error\"}}";
    assertThrows(
        ApiException.class,
        () ->
            new DeepSeekProvider(props, budget, diagnostics)
                .generate("fixture", "GENERATION", "JSON only", "question"));
    assertEquals(1, requests.get());
    assertEquals("UNCERTAIN", lastCall().get("status"));
    assertTrue(Database.number(lastCall(), "reserved") > 0);
  }

  @Test
  void embeddingHasExactDimensionsNormalizationAndNoPaidFallback() {
    var vector = new ArrayList<Double>(Collections.nCopies(1024, 0.0));
    vector.set(0, 3.0);
    vector.set(1, 4.0);
    responseBody =
        Json.write(
            Map.of(
                "data",
                List.of(Map.of("index", 0, "embedding", vector)),
                "usage",
                Map.of("total_tokens", 8)));
    float[] result = new SiliconFlowEmbedding(props, budget, diagnostics).embed("fixture", "Java");
    assertEquals(1024, result.length);
    assertEquals(.6, result[0], .00001);
    assertEquals(.8, result[1], .00001);
    assertEquals("/v1/embeddings", requestPath);
    assertEquals("BAAI/bge-m3", Json.read(requestBody).path("model").asText());
    assertEquals(0, Database.number(lastCall(), "actual"));
    var paid = properties(Map.of("clone.embedding-model", "Pro/BAAI/bge-m3"));
    assertEquals(
        "EMBEDDING_MODEL_NOT_APPROVED",
        assertThrows(
                ApiException.class,
                () -> new SiliconFlowEmbedding(paid, budget, diagnostics).embed("fixture", "Java"))
            .code());
    assertEquals(1, requests.get());
  }

  @Test
  void malformedEmbeddingIsRejected() {
    responseBody = "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}";
    assertThrows(
        ApiException.class,
        () -> new SiliconFlowEmbedding(props, budget, diagnostics).embed("fixture", "Java"));
    assertEquals("UNCERTAIN", lastCall().get("status"));
  }

  List<DecisionProvider.Evidence> evidence() {
    return List.of(
        new DecisionProvider.Evidence("one", "EXPERIENCE", "USED", "我使用 Java"),
        new DecisionProvider.Evidence("two", "KNOWLEDGE", "STUDIED", "我学习容器"));
  }

  @Test
  void typesafeUsesNoulProbabilityAndRecordsPolicy() {
    responseBody =
        "{\"answers\":{\"p0\":{\"type\":\"noul\",\"noul\":0.92},\"p1\":{\"type\":\"noul\",\"noul\":0.2}},\"usage\":{\"input_tokens\":300}}";
    var result =
        new SystemOneDecisions(props, db, budget, diagnostics)
            .evaluate("fixture", "用过 Java 吗", evidence());
    assertEquals(
        List.of("one"), result.accepted().stream().map(DecisionProvider.Evidence::id).toList());
    assertFalse(result.insufficient());
    assertEquals("typesafe", result.provider());
    assertEquals("/v1/systemone", requestPath);
    assertEquals("noul", Json.read(requestBody).path("questions").path("p0").path("type").asText());
    assertEquals(300, Database.number(lastCall(), "actual"));
    assertTrue(
        db.app()
            .queryForObject(
                "SELECT detail_json FROM diagnostic_events WHERE stage='DECISION'", String.class)
            .contains("0.92"));
  }

  @Test
  void bailianSwitchUsesOwnEndpointModelAndThreshold() {
    responseBody =
        "{\"answers\":{\"p0\":{\"noul\":0.87},\"p1\":{\"noul\":0.1}},\"usage\":{\"input_tokens\":200}}";
    var provider = new SystemOneDecisions(props, db, budget, diagnostics);
    provider.select("bailian");
    assertTrue(provider.evaluate("fixture", "Java", evidence()).insufficient());
    assertEquals("/compatible-mode/v1/systemone", requestPath);
    assertEquals("decision-model-preview", Json.read(requestBody).path("model").asText());
    provider.threshold("bailian", .86);
    assertEquals(1, provider.evaluate("fixture-2", "Java", evidence()).accepted().size());
  }

  @Test
  void invalidProbabilityCannotProduceApproval() {
    responseBody = "{\"answers\":{\"p0\":{\"noul\":1.2},\"p1\":{\"noul\":0.99}}}";
    assertThrows(
        ApiException.class,
        () ->
            new SystemOneDecisions(props, db, budget, diagnostics)
                .evaluate("fixture", "Java", evidence()));
    assertEquals("UNCERTAIN", lastCall().get("status"));
  }

  @Test
  void zeroDecisionUsageDoesNotEraseReservedCost() {
    responseBody =
        "{\"answers\":{\"p0\":{\"noul\":0.99},\"p1\":{\"noul\":0.1}},\"usage\":{\"input_tokens\":0}}";
    new SystemOneDecisions(props, db, budget, diagnostics).evaluate("fixture", "Java", evidence());
    assertTrue(Database.number(lastCall(), "actual") > 0);
    assertEquals(Database.number(lastCall(), "reserved"), Database.number(lastCall(), "actual"));
  }

  @Test
  void unconfiguredPriceBlocksBeforeNetworkCall() {
    var unpriced = properties(Map.of("clone.deepseek-input-cny-per-million", "-1"));
    assertEquals(
        "PRICING_REQUIRED",
        assertThrows(
                ApiException.class,
                () ->
                    new DeepSeekProvider(unpriced, budget, diagnostics)
                        .generate("fixture", "GENERATION", "JSON only", "question"))
            .code());
    assertEquals(0, requests.get());
  }
}
