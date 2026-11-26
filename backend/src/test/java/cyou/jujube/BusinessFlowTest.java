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

/** 通过临时数据库和可控模型替身验证完整业务流程，不调用外部付费服务。 覆盖导入确认发布、证据核验、访客隔离、撤回回退、并发、预算和真实 HTTP 安全边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BusinessFlowTest.Fakes.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessFlowTest {
  static final Path ROOT = Path.of("target", "test-data-" + UUID.randomUUID()).toAbsolutePath();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("clone.data-dir", () -> ROOT.toString());
    registry.add("clone.jobs-enabled", () -> false);
    // 仅用于临时测试数据库的加密密钥，不得复制到正式环境。
    registry.add("clone.master-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
  }

  @Autowired Database db;
  @Autowired SourceService sources;
  @Autowired ImportWorker worker;
  @Autowired KnowledgeService knowledge;
  @Autowired ChatService chat;
  @Autowired cyou.jujube.notes.NoteService notes;
  @Autowired cyou.jujube.notes.NoteIndex noteIndex;
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
    db.app().update("DELETE FROM notes_fts");
    for (String table :
        List.of("note_chunks", "note_chunk_parents", "note_assets", "note_history", "notes"))
      db.app().update("DELETE FROM " + table);
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

  String seedNote() throws Exception {
    var note =
        (Map<?, ?>)
            notes.importFile(
                "default",
                true,
                new MockMultipartFile(
                    "file",
                    "project.txt",
                    "text/plain",
                    "我在个人项目中使用 Java 实现了订单接口。".getBytes(StandardCharsets.UTF_8)));
    noteIndex.tick();
    return db.app()
        .queryForObject(
            "SELECT id FROM note_chunks WHERE note_id=? LIMIT 1", String.class, note.get("id"));
  }

  @Test
  void offlineAnswerEvaluationRunsDialogueAndRecordsCitedEvidence() throws Exception {
    String chunk = seedNote();
    var runner = new cyou.jujube.evaluation.EvaluationRunner(db, noteIndex, chat, sessions);
    var config =
        Json.read(
            Json.write(
                Map.of(
                    "mode",
                    "answer",
                    "variants",
                    List.of("no-rerank"),
                    "cases",
                    List.of(
                        Map.of(
                            "id",
                            "dialogue-fixture",
                            "question",
                            "那它用什么实现的？",
                            "dialogue",
                            List.of("Java 项目做了什么？"))))));
    Path output = ROOT.resolve("evaluation-" + UUID.randomUUID() + ".jsonl");
    runner.run(config, output);
    var row = Json.read(Files.readString(output));
    assertFalse(row.has("error"));
    assertEquals("ANSWERED", row.path("answer").path("status").asText());
    assertEquals(1, row.path("dialogue").size());
    assertTrue(row.path("query").asText().contains("Java 项目做了什么"));
    assertEquals(chunk, row.path("stages").path("cited").get(0).path("id").asText());
    assertEquals(
        0, db.sessions().queryForObject("SELECT COUNT(*) FROM visitor_sessions", Integer.class));
  }

  @Test
  void evaluationObservesTheSameEvidenceSentThroughProductionAnswerChecks() throws Exception {
    String chunk = seedNote();
    var visitor = sessions.create();
    var trace = new cyou.jujube.notes.RetrievalTrace();
    var answer =
        chat.ask(
            "evaluation-fixture",
            visitor.token(),
            "fixture-ip",
            "Java 项目",
            UUID.randomUUID().toString(),
            cyou.jujube.notes.NoteIndex.SearchOptions.DEFAULT,
            trace);
    assertEquals("ANSWERED", answer.status());
    assertTrue(trace.stages().get("context").stream().anyMatch(hit -> hit.id().equals(chunk)));
    assertFalse(Json.write(answer).contains("stages"));
    decision.insufficient = true;
    var denied = new cyou.jujube.notes.RetrievalTrace();
    assertEquals(
        "INSUFFICIENT_EVIDENCE",
        chat.ask(
                "evaluation-denied",
                sessions.create().token(),
                "fixture-ip2",
                "Java 项目",
                UUID.randomUUID().toString(),
                cyou.jujube.notes.NoteIndex.SearchOptions.DEFAULT,
                denied)
            .status());
    assertFalse(denied.stages().containsKey("context"));
  }

  @Test
  void uploadApprovePublishAnswerAndIsolateVisitors() throws Exception {
    String record = seedNote();
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
    seedNote();
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
    seedNote();
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
    seedNote();
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
  void backupPhysicallyRemovesCredentialsAndRestoresSearchableKnowledge() throws Exception {
    seed(true);
    var account =
        Database.required(db.app(), "SELECT password_hash,totp_encrypted FROM admin_account");
    String recoveryHash =
        db.app().queryForObject("SELECT hash FROM recovery_codes LIMIT 1", String.class);
    String sessionHash = Crypto.hash(UUID.randomUUID().toString());
    db.app()
        .update(
            "INSERT INTO admin_sessions(token_hash,expires_at) VALUES(?,?)",
            sessionHash,
            System.currentTimeMillis() + 60_000);
    var created = backups.create();
    Path archive = backups.archive(created.get("name").toString());
    try (var zip = new ZipFile(archive.toFile())) {
      byte[] data = zip.getInputStream(zip.getEntry("app.db")).readAllBytes();
      String raw = new String(data, StandardCharsets.ISO_8859_1);
      for (String marker :
          List.of(
              account.get("password_hash").toString(),
              account.get("totp_encrypted").toString(),
              recoveryHash,
              sessionHash))
        assertFalse(raw.contains(marker), "Authentication bytes must not remain in backup");
    }
    Path destination = ROOT.resolve("restored-" + UUID.randomUUID());
    BackupRestore.restore(archive, destination);
    var restoredProps =
        new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source
                    .MapConfigurationPropertySource(
                    Map.of("clone.data-dir", destination.toString())))
            .bind(
                "clone",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    cyou.jujube.config.CloneProperties.class))
            .get();
    var restored =
        new Database(
            cyou.jujube.config.RuntimeConfig.sqlite(destination.resolve("app.db").toString()),
            restoredProps);
    assertEquals(
        0L, restored.app().queryForObject("SELECT COUNT(*) FROM admin_account", Long.class));
    var search =
        new HybridRetriever(restored, embedding, new Diagnostics(restored, Clock.systemUTC()));
    assertFalse(
        search
            .search("restore-fixture", restored.setting("knowledge.active", ""), "Java 订单")
            .passages()
            .isEmpty());
    assertThrows(java.io.IOException.class, () -> BackupRestore.restore(archive, destination));
  }

  @Test
  void duplicateVisitorRequestReturnsStoredAnswerWithoutMoreModelCalls() throws Exception {
    seedNote();
    var visitor = sessions.create();
    var first =
        chat.ask(
            "request-original", visitor.token(), "ip-dedupe", "介绍 Java 项目", "client-request-key");
    int calls = generation.calls.get();
    var replay =
        chat.ask(
            "request-repeated", visitor.token(), "ip-dedupe", "介绍 Java 项目", "client-request-key");
    assertEquals(first, replay);
    assertEquals(calls, generation.calls.get());
    assertEquals(2, sessions.history(visitor.token()).size());
    assertThrows(
        ApiException.class,
        () ->
            chat.ask(
                "request-reused", visitor.token(), "ip-dedupe", "另一个问题", "client-request-key"));
  }

  @Test
  void staleCandidateRevisionCannotOverwriteAnotherSavedEdit() throws Exception {
    String candidate = seed(true);
    long revision =
        db.app()
            .queryForObject("SELECT revision FROM candidates WHERE id=?", Long.class, candidate);
    sources.updateCandidate(
        candidate,
        new SourceService.CandidateUpdate("EXPERIENCE", "新人工内容", "USED", false, revision));
    var conflict =
        assertThrows(
            ApiException.class,
            () ->
                sources.updateCandidate(
                    candidate,
                    new SourceService.CandidateUpdate(
                        "EXPERIENCE", "旧页面内容", "USED", false, revision)));
    assertEquals("EDIT_CONFLICT", conflict.code());
    assertEquals(
        "新人工内容",
        db.app()
            .queryForObject("SELECT content FROM candidates WHERE id=?", String.class, candidate));
  }

  @Test
  void deletingOldReleasesKeepsCurrentIndexAndAllowsUnreferencedSourceCleanup() throws Exception {
    String first = seed(true);
    String source =
        db.app().queryForObject("SELECT source_id FROM candidates WHERE id=?", String.class, first);
    seed(true);
    assertEquals(
        "RELEASE_ACTIVE",
        assertThrows(ApiException.class, () -> knowledge.delete(knowledge.active())).code());
    assertEquals(
        "SOURCE_REFERENCED", assertThrows(ApiException.class, () -> sources.delete(source)).code());
    sources.withdraw(first);
    knowledge.release("移除旧资料", true);
    worker.tick();
    String current = knowledge.active();
    for (var release : knowledge.releases())
      if (!current.equals(release.get("id"))) knowledge.delete(release.get("id").toString());
    sources.delete(source);
    assertEquals(
        0L, db.app().queryForObject("SELECT COUNT(*) FROM sources WHERE id=?", Long.class, source));
    assertFalse(retriever.search("after-cleanup", current, "Java 订单").passages().isEmpty());
  }

  @Test
  void releaseDeletionTracksBuildJobsRatherThanReleaseStatus() throws Exception {
    seed(true);
    String current = knowledge.active();
    var pending = knowledge.release("待构建版本", true);
    String id = pending.get("releaseId").toString();
    for (String status : List.of("QUEUED", "RUNNING", "PAUSED")) {
      db.app().update("UPDATE jobs SET status=? WHERE id=?", status, pending.get("jobId"));
      var release =
          knowledge.releases().stream()
              .filter(r -> id.equals(r.get("id")))
              .findFirst()
              .orElseThrow();
      assertEquals(1, ((Number) release.get("build_busy")).intValue());
      assertEquals(
          "RELEASE_BUSY", assertThrows(ApiException.class, () -> knowledge.delete(id)).code());
    }
    db.app().update("UPDATE jobs SET status='FAILED' WHERE id=?", pending.get("jobId"));
    var failed =
        knowledge.releases().stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
    assertEquals("BUILDING", failed.get("status"));
    assertEquals(0, ((Number) failed.get("build_busy")).intValue());
    knowledge.delete(id);
    assertEquals(current, knowledge.active());
    assertTrue(knowledge.releases().stream().noneMatch(r -> id.equals(r.get("id"))));
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
    assertEquals(401, browser.get("/api/admin/notes").statusCode());
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
    assertEquals(200, browser.get("/api/admin/notes").statusCode());
    browser.csrf(); // 登录成功后安全框架会轮换防伪状态，下一次写请求必须重新获取令牌。
    var created =
        browser.post(
            "/api/admin/notes", Json.write(Map.of("sectionId", "default", "title", "HTTP 笔记")));
    assertEquals(200, created.statusCode());
    assertTrue(Json.read(created.body()).path("body").isObject());
    browser.csrf();
    assertEquals(200, browser.post("/api/auth/logout", "{}").statusCode());
    assertEquals(401, browser.get("/api/admin/notes").statusCode());
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

    HttpResponse<String> delete(String path) throws Exception {
      csrf();
      return http.send(
          HttpRequest.newBuilder(URI.create(base() + path)).header(header, token).DELETE().build(),
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
