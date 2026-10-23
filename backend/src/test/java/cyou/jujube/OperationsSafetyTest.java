package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import cyou.jujube.ai.GenerationProvider;
import cyou.jujube.common.*;
import cyou.jujube.config.*;
import cyou.jujube.conversation.*;
import cyou.jujube.ingestion.*;
import cyou.jujube.operations.*;
import cyou.jujube.quota.BudgetService;
import cyou.jujube.retrieval.HybridRetriever;
import cyou.jujube.storage.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 验证崩溃、备份损坏、迁移、分页和资源边界；所有文件和账本只在临时目录中。 */
class OperationsSafetyTest {
  @TempDir Path root;
  Database db;
  CloneProperties props;
  Clock clock = Clock.fixed(Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setup() throws Exception {
    props =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "clone.data-dir",
                        root.toString(),
                        "clone.daily-budget-cny",
                        "2",
                        "clone.lifetime-notify-cny",
                        "5",
                        "clone.master-key",
                        Base64.getEncoder().encodeToString(new byte[32]))))
            .bind("clone", Bindable.of(CloneProperties.class))
            .get();
    db = new Database(RuntimeConfig.sqlite(root.resolve("app.db").toString()), props);
  }

  @Test
  void reclaimedSqlitePagesAllowStorageAfterCleanup() {
    var limits = new OperationsProperties(false, true, 0, 200, 200, 32, 3000, 20, 10);
    var sources = new SourceService(db, props, clock, limits);
    db.app().execute("CREATE TABLE capacity_fixture(content BLOB)");
    db.app().update("INSERT INTO capacity_fixture VALUES(zeroblob(?))", 11 * 1024 * 1024);
    assertEquals(
        "DATABASE_CAPACITY_REACHED",
        assertThrows(ApiException.class, sources::checkStorage).code());
    db.app().update("DELETE FROM capacity_fixture");
    assertTrue(db.app().queryForObject("PRAGMA freelist_count", Long.class) > 0);
    assertDoesNotThrow(sources::checkStorage);
  }

  @Test
  void interruptedPaidJobKeepsReservationAndRequiresReconciliation() {
    var sources = new SourceService(db, props, clock);
    String subject = UUID.randomUUID().toString();
    db.app()
        .update(
            "INSERT INTO sources(id,name,file_name,hash,format,status,created_at) VALUES(?,?,?,?,?,'AWAITING_REVIEW',?)",
            subject,
            "fixture",
            subject + ".txt",
            "fixture",
            "txt",
            clock.millis());
    String job = sources.enqueue("EXTRACT", subject);
    db.app().update("UPDATE jobs SET status='RUNNING' WHERE id=?", job);
    var budget = new BudgetService(db, clock, props);
    var reservation = budget.require(job, "EXTRACTION", "fixture", "fixture", 100_000);
    var worker =
        new ImportWorker(
            db,
            props,
            new DocumentParser(),
            sources,
            noGeneration(),
            null,
            new Diagnostics(db, clock),
            clock);
    worker.recover();
    worker.recover();
    assertEquals(
        "UNCERTAIN",
        db.app()
            .queryForObject(
                "SELECT status FROM ai_calls WHERE id=?", String.class, reservation.id()));
    assertEquals(100_000L, db.app().queryForObject("SELECT reserved FROM budget_days", Long.class));
    assertEquals(
        "PAUSED", db.app().queryForObject("SELECT status FROM jobs WHERE id=?", String.class, job));
    assertEquals(
        "RECONCILIATION_REQUIRED",
        assertThrows(ApiException.class, () -> sources.retry(job)).code());
    budget.reconcile(reservation.id(), new BigDecimal("0.02"));
    sources.retry(job);
    assertEquals(
        "QUEUED", db.app().queryForObject("SELECT status FROM jobs WHERE id=?", String.class, job));
    assertEquals(0L, db.app().queryForObject("SELECT reserved FROM budget_days", Long.class));
    assertEquals(20_000L, db.app().queryForObject("SELECT spent FROM budget_days", Long.class));
  }

  @Test
  void recoveryRequeuesPureParsingButDoesNotReplayVisitorRequests() {
    var sources = new SourceService(db, props, clock);
    String job = sources.enqueue("IMPORT", "fixture");
    db.app().update("UPDATE jobs SET status='RUNNING' WHERE id=?", job);
    var visitors = new VisitorSessions(db, clock);
    var visitor = visitors.create();
    visitors.lock(visitor.id(), "fixture-request");
    visitors.register(visitor.id(), "fixture-key", "fixture question", "fixture-request");
    var worker =
        new ImportWorker(
            db,
            props,
            new DocumentParser(),
            sources,
            noGeneration(),
            null,
            new Diagnostics(db, clock),
            clock);
    worker.recover();
    assertEquals(
        "QUEUED", db.app().queryForObject("SELECT status FROM jobs WHERE id=?", String.class, job));
    assertEquals(
        "REQUEST_UNCERTAIN",
        assertThrows(
                ApiException.class,
                () -> visitors.replay(visitor.token(), "fixture-key", "fixture question"))
            .code());
    assertDoesNotThrow(() -> visitors.lock(visitor.id(), "another-request"));
  }

  @Test
  void duplicateJobsAndQueueCapacityAreEnforcedBeforeModels() {
    var limits = new OperationsProperties(false, true, 0, 200, 200, 1, 3000, 20, 256);
    var sources = new SourceService(db, props, clock, limits);
    String first = sources.enqueue("IMPORT", "one");
    assertEquals(first, sources.enqueue("IMPORT", "one"));
    assertEquals(
        "QUEUE_FULL",
        assertThrows(ApiException.class, () -> sources.enqueue("IMPORT", "two")).code());
    sources.cancel(first);
    assertNotNull(sources.enqueue("IMPORT", "two"));
  }

  @Test
  void migrationRejectsFutureVersionsAndChangedChecksums() throws Exception {
    var source = RuntimeConfig.sqlite(root.resolve("app.db").toString());
    db.app().update("UPDATE schema_migrations SET checksum='changed' WHERE version=2");
    assertThrows(IllegalStateException.class, () -> SchemaMigrations.migrate(source, "app"));
    db.app().update("DELETE FROM schema_migrations WHERE version=2");
    db.app().update("INSERT INTO schema_migrations VALUES(99,'future',0)");
    assertThrows(IllegalStateException.class, () -> SchemaMigrations.migrate(source, "app"));
  }

  @Test
  void migrationFailureRollsBackSchemaAndDoesNotMarkVersionApplied() throws Exception {
    var source = RuntimeConfig.sqlite(root.resolve("app.db").toString());
    db.app().update("DELETE FROM schema_migrations WHERE version=2");
    // V2 再次增加已有列会失败，必须保持原版本记录且不执行后面的迁移。
    assertThrows(Exception.class, () -> SchemaMigrations.migrate(source, "app"));
    assertEquals(1, SchemaMigrations.version(db.app()));
    assertEquals(
        0L,
        db.app()
            .queryForObject("SELECT COUNT(*) FROM schema_migrations WHERE version=2", Long.class));
  }

  @Test
  void diagnosticsFailureDoesNotTurnCompletedWorkIntoFailure() {
    db.app().execute("DROP TABLE diagnostic_events");
    assertDoesNotThrow(
        () ->
            new Diagnostics(db, clock)
                .event("fixture", "TEST", "MODEL_COMPLETED", Map.of("count", 1)));
  }

  @Test
  void dataDirectoryCannotBeOpenedByTwoProcesses() throws Exception {
    try (var first = new DataDirectoryLock(root)) {
      assertThrows(java.io.IOException.class, () -> new DataDirectoryLock(root));
    }
    try (var next = new DataDirectoryLock(root)) {
      assertNotNull(next);
    }
  }

  @Test
  void longEvidenceKeepsWholeRecordsAndTrimsHistoryFirst() {
    var passages = new ArrayList<HybridRetriever.Passage>();
    for (int i = 0; i < 8; i++)
      passages.add(
          new HybridRetriever.Passage(
              "id" + i, "source", "fixture", "line", "EXPERIENCE", "USED", "文".repeat(1800), 1));
    var evidence = ContextBuilder.evidence(passages);
    assertFalse(evidence.isEmpty());
    assertTrue(evidence.size() < 8);
    assertTrue(evidence.stream().allMatch(p -> p.content().length() == 1800));
    var history =
        List.<Map<String, Object>>of(Map.of("role", "assistant", "content", "文".repeat(5000)));
    String input =
        ContextBuilder.generation(
            "文".repeat(2000), history, evidence, List.of(Map.of("content", "文".repeat(1800))));
    assertTrue(input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 28_000);
    assertTrue(Json.read(input).path("style").isEmpty());
  }

  @Test
  void requestDeadlineIsSharedAndRestoredAfterScope() {
    try (var deadline = RequestDeadline.start(Duration.ofNanos(-1))) {
      assertEquals(
          "REQUEST_TIMEOUT", assertThrows(ApiException.class, RequestDeadline::check).code());
    }
    assertDoesNotThrow(RequestDeadline::check);
  }

  @Test
  void callsPaginationFindsOldUncertainRecordsWithoutDuplicates() {
    for (int i = 0; i < 61; i++)
      db.app()
          .update(
              "INSERT INTO ai_calls(id,request_id,stage,provider,model,day,reserved,status,created_at) VALUES(?,'fixture','TEST','fixture','fixture','2026-10-22',0,'UNCERTAIN',?)",
              UUID.randomUUID().toString(),
              clock.millis());
    var queries = new AdminQueries(db);
    var first = queries.calls("UNCERTAIN", "");
    var second = queries.calls("UNCERTAIN", first.nextCursor());
    assertEquals(50, first.items().size());
    assertEquals(11, second.items().size());
    assertTrue(second.nextCursor().isBlank());
    var ids = new HashSet<String>();
    first.items().forEach(c -> assertTrue(ids.add(c.id())));
    second.items().forEach(c -> assertTrue(ids.add(c.id())));
    assertEquals(61, ids.size());
  }

  @Test
  void verificationClearanceIsBoundToSessionIpAndTime() {
    var visitors = new VisitorSessions(db, clock);
    var first = visitors.create();
    var second = visitors.create();
    visitors.verified(first.id(), "trusted-ip");
    assertTrue(visitors.hasVerification(first.token(), "trusted-ip"));
    assertFalse(visitors.hasVerification(first.token(), "another-ip"));
    assertFalse(visitors.hasVerification(second.token(), "trusted-ip"));
    var later = new VisitorSessions(db, Clock.offset(clock, Duration.ofSeconds(61)));
    assertFalse(later.hasVerification(first.token(), "trusted-ip"));
  }

  @Test
  void incompleteArchivesAreNotListedOrDownloadable() throws Exception {
    Files.createDirectories(root.resolve("backups"));
    Files.writeString(root.resolve("backups/backup-123-abcdef12.partial"), "incomplete");
    var backups = new BackupService(db, props, clock);
    assertTrue(backups.list().isEmpty());
    assertThrows(ApiException.class, () -> backups.archive("backup-123-abcdef12.partial"));
  }

  @Test
  void restoreRejectsUnexpectedEntriesWithoutTouchingDestination() throws Exception {
    Path archive = root.resolve("invalid.zip"), destination = root.resolve("new-data");
    try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(
          Json.write(
                  Map.of(
                      "formatVersion",
                      2,
                      "schemaVersion",
                      2,
                      "sha256",
                      Map.of("app.db", "fixture")))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("../escape.txt"));
      zip.write(new byte[] {1});
      zip.closeEntry();
    }
    assertThrows(Exception.class, () -> BackupRestore.restore(archive, destination));
    assertFalse(Files.exists(destination));
    assertFalse(Files.exists(root.resolve("escape.txt")));
  }

  @Test
  void restoreRejectsTamperedChecksumAndKeepsOriginalArchive() throws Exception {
    var backups = new BackupService(db, props, clock);
    Path original = backups.archive(backups.create().get("name").toString());
    Path tampered = root.resolve("tampered.zip");
    try (var source = new ZipFile(original.toFile());
        var zip = new ZipOutputStream(Files.newOutputStream(tampered))) {
      var entries = source.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        zip.putNextEntry(new ZipEntry(entry.getName()));
        byte[] bytes = source.getInputStream(entry).readAllBytes();
        zip.write(bytes);
        if ("app.db".equals(entry.getName())) zip.write(1);
        zip.closeEntry();
      }
    }
    Path destination = root.resolve("should-not-exist");
    assertThrows(java.io.IOException.class, () -> BackupRestore.restore(tampered, destination));
    assertTrue(Files.isRegularFile(original));
    assertFalse(Files.exists(destination));
  }

  @Test
  void reservationOverrunKeepsActualCostAndPausesNewPaidCalls() {
    var budget = new BudgetService(db, clock, props);
    var reservation = budget.require("fixture-overrun", "TEST", "fixture", "fixture", 10);
    budget.settle(reservation, 20, 1, 0, 1);
    assertEquals(20L, db.app().queryForObject("SELECT spent FROM budget_days", Long.class));
    assertEquals("false", db.setting("ai.paid-enabled", "true"));
    assertEquals(
        "PAID_CALLS_PAUSED",
        assertThrows(
                ApiException.class, () -> budget.require("next", "TEST", "fixture", "fixture", 1))
            .code());
  }

  @Test
  void profileRevisionAndNestedLinkValidationAreEnforced() {
    var service = new cyou.jujube.knowledge.ProfileService(db);
    var initial =
        new cyou.jujube.knowledge.ProfileService.Edit(
            "测试", "", "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L);
    assertEquals(1L, service.save(initial));
    assertEquals(
        "EDIT_CONFLICT", assertThrows(ApiException.class, () -> service.save(initial)).code());
    var invalid =
        new cyou.jujube.knowledge.ProfileService.Edit(
            "测试",
            "",
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(new cyou.jujube.knowledge.ProfileService.Link("unsafe", "javascript:alert(1)")),
            List.of(),
            List.of(),
            1L);
    assertEquals(
        "INVALID_PROFILE_LINK",
        assertThrows(ApiException.class, () -> service.save(invalid)).code());
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      var badItem =
          new cyou.jujube.knowledge.ProfileService.Edit(
              "测试",
              "",
              "",
              Arrays.asList((cyou.jujube.knowledge.ProfileService.Skill) null),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              1L);
      assertFalse(factory.getValidator().validate(badItem).isEmpty());
    }
  }

  @Test
  void offlineAdministratorRotationIsAtomicAndRevokesSessions() {
    var auth =
        new cyou.jujube.security.AdminAuth(
            db, props, clock, new cyou.jujube.quota.RateLimits(db, clock));
    auth.initialize("fixture", "old-fixture-password");
    db.app()
        .update("INSERT INTO admin_sessions VALUES('fixture-session',?)", clock.millis() + 60_000);
    String before =
        db.app().queryForObject("SELECT password_hash FROM admin_account", String.class);
    assertThrows(ApiException.class, () -> auth.reset("fixture", "weak"));
    assertEquals(
        before, db.app().queryForObject("SELECT password_hash FROM admin_account", String.class));
    assertEquals(1L, db.app().queryForObject("SELECT COUNT(*) FROM admin_sessions", Long.class));
    var rotated = auth.reset("fixture", "new-fixture-password");
    assertEquals(8, rotated.recoveryCodes().size());
    assertEquals(0L, db.app().queryForObject("SELECT COUNT(*) FROM admin_sessions", Long.class));
    assertNotEquals(
        before, db.app().queryForObject("SELECT password_hash FROM admin_account", String.class));
  }

  @Test
  void documentArchiveLimitsNonBodyInflation() throws Exception {
    Path file = root.resolve("oversized.docx");
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      zip.putNextEntry(new ZipEntry("word/media/bomb.bin"));
      zip.write(new byte[4_000_001]);
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write("<document/>".getBytes());
      zip.closeEntry();
    }
    assertEquals(
        "ARCHIVE_TOO_LARGE",
        assertThrows(
                ApiException.class,
                () -> new DocumentParser().parse(file, "docx", DocumentParser.Mapping.from("{}")))
            .code());
  }

  @Test
  void modelJsonErrorsAreUnavailableInsteadOfClientInputErrors() {
    var error = assertThrows(ApiException.class, () -> Json.model("{broken}"));
    assertEquals(503, error.status());
    assertEquals("INVALID_MODEL_RESPONSE", error.code());
  }

  private GenerationProvider noGeneration() {
    return new GenerationProvider() {
      public boolean configured() {
        return false;
      }

      public String generate(String id, String stage, String system, String input) {
        throw new AssertionError("Unexpected model call");
      }
    };
  }
}
