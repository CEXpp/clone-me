package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import cyou.jujube.ai.*;
import cyou.jujube.common.*;
import cyou.jujube.conversation.*;
import cyou.jujube.ingestion.*;
import cyou.jujube.knowledge.KnowledgeService;
import cyou.jujube.operations.*;
import cyou.jujube.quota.BudgetService;
import cyou.jujube.retrieval.HybridRetriever;
import cyou.jujube.security.*;
import cyou.jujube.storage.Database;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BusinessFlowTest.Fakes.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessFlowTest {
  static final Path ROOT = Path.of("target", "test-data-" + UUID.randomUUID()).toAbsolutePath();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("clone.data-dir", () -> ROOT.toString());
    registry.add("clone.jobs-enabled", () -> false);
    // Disposable test-only encryption key; never a production credential.
    registry.add("clone.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
  }

  @Autowired Database db;
  @Autowired SourceService sources;
  @Autowired ImportWorker worker;
  @Autowired KnowledgeService knowledge;
  @Autowired ChatService chat;
  @Autowired VisitorSessions sessions;
  @Autowired HybridRetriever retriever;
  @Autowired BudgetService budget;
  @Autowired AdminAuth auth;
  @Autowired Maintenance maintenance;
  @Autowired BackupService backups;
  @Autowired Environment environment;
  @Autowired FakeGeneration generation;
  @Autowired FakeEmbedding embedding;
  @Autowired FakeDecision decision;
  Iterator<String> recoveryCodes;

  @BeforeAll
  void enroll() {
    recoveryCodes =
        auth.initialize("owner", "test-password-for-local-fixtures").recoveryCodes().iterator();
  }

  @BeforeEach
  void reset() {
    db.app().execute("INSERT INTO records_fts(records_fts) VALUES('delete-all')");
    for (String table :
        List.of(
            "release_records",
            "releases",
            "jobs",
            "candidates",
            "segments",
            "sources",
            "embedding_cache",
            "diagnostic_events",
            "ai_calls",
            "budget_days",
            "notification_events",
            "rate_limits",
            "settings")) db.app().update("DELETE FROM " + table);
    db.sessions().update("DELETE FROM visitor_sessions");
    generation.calls.set(0);
    generation.checks.set(0);
    generation.reject = false;
    generation.invalidCitation = false;
    embedding.fail = false;
    decision.unavailable = false;
    decision.insufficient = false;
  }

  String seed(boolean keywordOnly) throws Exception {
    var imported =
        sources.upload(
            new MockMultipartFile(
                "file",
                "project.md",
                "text/markdown",
                "# 项目经历\n我在个人项目中使用 Java 实现了订单接口。".getBytes(StandardCharsets.UTF_8)),
            "{}");
    worker.tick();
    String id =
        db.app()
            .queryForObject(
                "SELECT id FROM candidates WHERE source_id=?",
                String.class,
                imported.get("sourceId"));
    sources.updateCandidate(
        id,
        new SourceService.CandidateUpdate("EXPERIENCE", "我在个人项目中使用 Java 实现了订单接口。", "USED", true));
    knowledge.release("测试发布", keywordOnly);
    worker.tick();
    assertFalse(knowledge.active().isBlank());
    return id;
  }

  @Test
  void uploadApprovePublishAnswerAndIsolateVisitors() throws Exception {
    String record = seed(false);
    var alice = sessions.create();
    var bob = sessions.create();
    var answer = chat.ask("test-flow", alice.token(), "ip-one", "介绍你的 Java 订单接口项目");
    assertEquals("ANSWERED", answer.status());
    assertEquals(record, answer.sources().getFirst().id());
    assertEquals(1, generation.checks.get());
    assertEquals(2, sessions.history(alice.token()).size());
    assertEquals("ANSWERED", sessions.history(alice.token()).getLast().get("status"));
    assertEquals("test-flow", sessions.history(alice.token()).getLast().get("requestId"));
    assertFalse(((List<?>) sessions.history(alice.token()).getLast().get("sources")).isEmpty());
    assertTrue(sessions.history(bob.token()).isEmpty());
    assertThrows(ApiException.class, () -> sessions.require("unrelated-token"));
    assertEquals(
        "我在个人项目中使用 Java 实现了订单接口。", sessions.history(alice.token()).getLast().get("content"));
    var details =
        db.app()
            .queryForList("SELECT detail_json FROM diagnostic_events WHERE request_id='test-flow'");
    assertTrue(
        details.stream().noneMatch(row -> row.get("detail_json").toString().contains("介绍你的")));
  }

  @Test
  void missingEvidenceNeverInvokesGeneration() {
    var visitor = sessions.create();
    var answer = chat.ask("no-evidence", visitor.token(), "ip-two", "你有 Kubernetes 生产集群经验吗");
    assertEquals("INSUFFICIENT_EVIDENCE", answer.status());
    assertEquals(0, generation.calls.get());
  }

  @Test
  void jevFailureFallsBackButStillWithholdsUnsupportedAnswer() throws Exception {
    seed(true);
    decision.unavailable = true;
    generation.reject = true;
    var visitor = sessions.create();
    var answer = chat.ask("jev-fallback", visitor.token(), "ip-three", "介绍 Java 项目");
    assertEquals("ANSWER_WITHHELD", answer.status());
    assertEquals(1, generation.checks.get());
    assertTrue(answer.sources().isEmpty());
    assertEquals(
        1L,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM diagnostic_events WHERE request_id='jev-fallback' AND event='PROVIDER_FALLBACK'",
                Long.class));
  }

  @Test
  void normalInsufficientDecisionCannotBypassTheGate() throws Exception {
    seed(true);
    decision.insufficient = true;
    var visitor = sessions.create();
    var answer = chat.ask("jev-insufficient", visitor.token(), "ip-four", "Java 项目");
    assertEquals("INSUFFICIENT_EVIDENCE", answer.status());
    assertEquals(0, generation.calls.get());
    assertEquals(
        0L,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM diagnostic_events WHERE event='PROVIDER_FALLBACK'",
                Long.class));
  }

  @Test
  void unknownCitationIsRejectedBeforeSemanticCheck() throws Exception {
    seed(true);
    generation.invalidCitation = true;
    var visitor = sessions.create();
    assertEquals(
        "ANSWER_WITHHELD",
        chat.ask("bad-citation", visitor.token(), "ip-five", "Java 订单项目").status());
    assertEquals(0, generation.checks.get());
  }

  @Test
  void withdrawalAppliesToOldReleasesAndRollback() throws Exception {
    String id = seed(true), old = knowledge.active();
    sources.withdraw(id);
    knowledge.activate(old);
    assertTrue(retriever.search("withdrawn", old, "Java 订单").passages().isEmpty());
  }

  @Test
  void failedIndexKeepsOldReleaseAndCanResume() throws Exception {
    seed(true);
    String old = knowledge.active();
    embedding.fail = true;
    var next = knowledge.release("新版本", false);
    worker.tick();
    assertEquals(old, knowledge.active());
    assertEquals(
        "FAILED",
        db.app()
            .queryForObject("SELECT status FROM jobs WHERE id=?", String.class, next.get("jobId")));
    embedding.fail = false;
    sources.retry(next.get("jobId").toString());
    worker.tick();
    assertEquals(next.get("releaseId"), knowledge.active());
  }

  @Test
  void perVisitorGenerationLockIsExclusive() {
    var visitor = sessions.create();
    sessions.lock(visitor.id(), "one");
    assertEquals(
        "CHAT_IN_PROGRESS",
        assertThrows(ApiException.class, () -> sessions.lock(visitor.id(), "two")).code());
    sessions.unlock(visitor.id(), "wrong");
    assertThrows(ApiException.class, () -> sessions.lock(visitor.id(), "two"));
    sessions.unlock(visitor.id(), "one");
    assertDoesNotThrow(() -> sessions.lock(visitor.id(), "two"));
  }

  @Test
  void expiryIsEnforcedBeforeScheduledCleanup() {
    var visitor = sessions.create();
    sessions.save(visitor.id(), "test", "reply");
    db.sessions()
        .update(
            "UPDATE visitor_sessions SET expires_at=? WHERE id=?",
            System.currentTimeMillis() - 1,
            visitor.id());
    assertThrows(ApiException.class, () -> sessions.require(visitor.token()));
    maintenance.clean();
    assertEquals(0L, db.sessions().queryForObject("SELECT COUNT(*) FROM messages", Long.class));
  }

  @Test
  void parallelReservationsCannotExceedDailyLimit() throws Exception {
    try (var executor = Executors.newFixedThreadPool(12)) {
      List<Future<BudgetService.Reservation>> futures = new ArrayList<>();
      for (int i = 0; i < 20; i++)
        futures.add(
            executor.submit(
                () -> budget.reserve("parallel", "TEST", "fixture", "fixture", 300_000)));
      int accepted = 0;
      for (var future : futures) if (future.get() != null) accepted++;
      assertEquals(6, accepted);
      assertEquals(
          1_800_000L, db.app().queryForObject("SELECT SUM(reserved) FROM budget_days", Long.class));
      assertEquals(
          1L,
          db.app()
              .queryForObject(
                  "SELECT COUNT(*) FROM notification_events WHERE kind='DAILY_BUDGET'",
                  Long.class));
    }
  }

  @Test
  void uncertainCallsKeepReservationUntilExplicitReconciliation() {
    var reservation = budget.require("uncertain", "TEST", "fixture", "fixture", 1_500_000);
    budget.uncertain(reservation, "TIMEOUT", 10);
    assertNull(budget.reserve("next", "TEST", "fixture", "fixture", 600_000));
    budget.reconcile(reservation.id(), new BigDecimal("0.2"));
    assertNotNull(budget.reserve("after", "TEST", "fixture", "fixture", 600_000));
    assertEquals(200_000L, db.app().queryForObject("SELECT SUM(actual) FROM ai_calls", Long.class));
  }

  @Test
  void backupExcludesChatAndAuthenticationAndIsReadable() throws Exception {
    seed(true);
    var visitor = sessions.create();
    sessions.save(visitor.id(), "private conversation fixture", "private reply fixture");
    var result = backups.create();
    Path archive = backups.archive(result.get("name").toString());
    try (var zip = new ZipFile(archive.toFile())) {
      assertNotNull(zip.getEntry("app.db"));
      assertNotNull(zip.getEntry("manifest.json"));
      assertNull(zip.getEntry("sessions.db"));
      Path snapshot = ROOT.resolve("backup-check-" + UUID.randomUUID() + ".db");
      Files.copy(zip.getInputStream(zip.getEntry("app.db")), snapshot);
      var sql =
          new org.springframework.jdbc.core.JdbcTemplate(
              cyou.jujube.config.RuntimeConfig.sqlite(snapshot.toString()));
      assertEquals(0L, sql.queryForObject("SELECT COUNT(*) FROM admin_account", Long.class));
      assertEquals(1L, sql.queryForObject("SELECT COUNT(*) FROM releases", Long.class));
    }
  }

  @Test
  void jsonOwnershipRequiresMappingAndOnlyOwnStatementsBecomeCandidates() throws Exception {
    String json =
        "[{\"speaker\":\"me\",\"content\":\"我了解 Java\"},{\"speaker\":\"them\",\"content\":\"我负责 Kubernetes 运维\"}]";
    var imported =
        sources.upload(
            new MockMultipartFile(
                "file", "chat.json", "application/json", json.getBytes(StandardCharsets.UTF_8)),
            "{\"selfSpeaker\":\"me\"}");
    worker.tick();
    assertEquals(
        2L,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM segments WHERE source_id=?",
                Long.class,
                imported.get("sourceId")));
    assertEquals(
        1L,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM candidates WHERE source_id=?",
                Long.class,
                imported.get("sourceId")));
  }

  @Test
  void httpRequiresAuthenticationAndCsrfAndAllowsValidatedAdmin() throws Exception {
    var browser = new Browser();
    var missing = browser.get("/not-present.fixture");
    assertEquals(404, missing.statusCode());
    assertEquals("RESOURCE_NOT_FOUND", Json.read(missing.body()).path("code").asText());
    assertEquals(401, browser.get("/api/admin/sources").statusCode());
    assertEquals(403, browser.postWithoutCsrf("/api/public/session", "{}").statusCode());
    browser.csrf();
    assertEquals(200, browser.post("/api/public/session", "{}").statusCode());
    assertEquals(
        200,
        browser
            .post(
                "/api/auth/login",
                Json.write(
                    Map.of(
                        "username",
                        "owner",
                        "password",
                        "test-password-for-local-fixtures",
                        "code",
                        recoveryCodes.next())))
            .statusCode());
    assertEquals(200, browser.get("/api/admin/sources").statusCode());
    browser
        .csrf(); // Spring Security rotates CSRF state when the administrator becomes authenticated.
    assertEquals(200, browser.post("/api/auth/logout", "{}").statusCode());
    assertEquals(401, browser.get("/api/admin/sources").statusCode());
  }

  class Browser {
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String header, token;

    String base() {
      return "http://127.0.0.1:" + environment.getProperty("local.server.port");
    }

    HttpResponse<String> get(String path) throws Exception {
      return http.send(
          HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    void csrf() throws Exception {
      var root = Json.read(get("/api/auth/csrf").body());
      header = root.path("headerName").asText();
      token = root.path("token").asText();
      assertFalse(token.isBlank());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
      return http.send(
          HttpRequest.newBuilder(URI.create(base() + path))
              .header("Content-Type", "application/json")
              .header(header, token)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postWithoutCsrf(String path, String body) throws Exception {
      return http.send(
          HttpRequest.newBuilder(URI.create(base() + path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    FakeGeneration fixtureGeneration() {
      return new FakeGeneration();
    }

    @Bean
    @Primary
    FakeEmbedding fixtureEmbedding() {
      return new FakeEmbedding();
    }

    @Bean
    @Primary
    FakeDecision fixtureDecision() {
      return new FakeDecision();
    }
  }

  static class FakeGeneration implements GenerationProvider {
    final AtomicInteger calls = new AtomicInteger(), checks = new AtomicInteger();
    boolean reject, invalidCitation;

    public boolean configured() {
      return false;
    }

    public String generate(String id, String stage, String system, String input) {
      calls.incrementAndGet();
      if (stage.equals("ANSWER_CHECK")) {
        checks.incrementAndGet();
        return Json.write(Map.of("supported", !reject));
      }
      var evidence = Json.read(input).path("evidence");
      String ref = invalidCitation ? "not-a-real-source" : evidence.get(0).path("id").asText();
      String answer = "我在个人项目中使用 Java 实现了订单接口。";
      return Json.write(
          Map.of(
              "answer",
              answer,
              "insufficient",
              false,
              "evidenceIds",
              List.of(ref),
              "claims",
              List.of(Map.of("text", answer, "evidenceIds", List.of(ref)))));
    }
  }

  static class FakeEmbedding implements EmbeddingProvider {
    boolean fail;

    public boolean configured() {
      return true;
    }

    public String fingerprint() {
      return "test-only|1024|v1";
    }

    public float[] embed(String id, String text) {
      if (fail) throw ApiException.unavailable("FIXTURE_UNAVAILABLE", "fixture");
      float[] values = new float[1024];
      values[0] = 1;
      return values;
    }
  }

  static class FakeDecision implements DecisionProvider {
    boolean unavailable, insufficient;

    public Judgment evaluate(String id, String question, List<Evidence> evidence) {
      if (unavailable) throw ApiException.unavailable("DECISION_TIMEOUT", "fixture");
      return new Judgment(
          insufficient ? List.of() : evidence, insufficient, Map.of(), "fixture", "fixture");
    }
  }
}
