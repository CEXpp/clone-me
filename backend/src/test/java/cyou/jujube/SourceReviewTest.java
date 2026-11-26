package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import cyou.jujube.ai.*;
import cyou.jujube.common.*;
import cyou.jujube.config.*;
import cyou.jujube.ingestion.*;
import cyou.jujube.storage.Database;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockMultipartFile;

/** 使用真实临时数据库验证人工审阅与后台提取的竞争，不调用外部模型。 */
class SourceReviewTest {
  @TempDir Path root;
  CloneProperties props;
  Database db;
  SourceService sources;
  ImportWorker worker;
  final Clock clock = Clock.systemUTC();
  final Extraction generation = new Extraction();
  String sourceId, candidateId;

  @BeforeEach
  void initialize() throws Exception {
    props =
        new Binder(new MapConfigurationPropertySource(Map.of("clone.data-dir", root.toString())))
            .bind("clone", Bindable.of(CloneProperties.class))
            .get();
    db = new Database(RuntimeConfig.sqlite(root.resolve("app.db").toString()), props);
    sources = new SourceService(db, props, clock);
    worker =
        new ImportWorker(
            db,
            props,
            new DocumentParser(),
            sources,
            generation,
            null,
            new Diagnostics(db, clock),
            clock);
    var uploaded =
        sources.upload(
            new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "我使用 Java 编写了个人项目。".getBytes(StandardCharsets.UTF_8)),
            "{}");
    sourceId = uploaded.get("sourceId").toString();
    worker.tick();
    candidateId =
        db.app()
            .queryForObject("SELECT id FROM candidates WHERE source_id=?", String.class, sourceId);
  }

  void saveDraft() {
    sources.updateCandidate(
        candidateId,
        new SourceService.CandidateUpdate("REFERENCE", "本人保存的草稿", "UNCONFIRMED", false));
  }

  Map<String, Object> candidate() {
    return Database.required(db.app(), "SELECT * FROM candidates WHERE id=?", candidateId);
  }

  void extract() {
    sources.enqueue("EXTRACT", sourceId);
    worker.tick();
  }

  @Test
  void extractionStillUpdatesUntouchedDrafts() {
    extract();
    assertEquals("我使用 Java 编写了个人项目。", candidate().get("content"));
    assertEquals(1L, Database.number(candidate(), "revision"));
    assertEquals(1, generation.calls);
    assertEquals(0L, Database.number(candidate(), "approved"));
  }

  @Test
  void savedDraftIsSkippedWithoutPayingForExtraction() {
    saveDraft();
    extract();
    worker.tick();
    assertEquals("本人保存的草稿", candidate().get("content"));
    assertEquals(0, generation.calls);
    assertEquals(
        "COMPLETED",
        db.app().queryForObject("SELECT status FROM jobs WHERE type='EXTRACT'", String.class));
  }

  @Test
  void draftSavedWhileModelIsRunningWinsOverModelOutput() {
    generation.beforeReply = this::saveDraft;
    extract();
    assertEquals("本人保存的草稿", candidate().get("content"));
    assertEquals(1, generation.calls);
  }

  @Test
  void withdrawnDraftIsSkippedWithoutCallingModel() {
    sources.withdraw(candidateId);
    extract();
    assertEquals(0, generation.calls);
  }

  @Test
  void withdrawalWhileModelIsRunningPreventsModelWrite() {
    String original = Database.text(candidate(), "content");
    generation.beforeReply = () -> sources.withdraw(candidateId);
    extract();
    assertEquals(original, candidate().get("content"));
    assertEquals(1L, Database.number(candidate(), "withdrawn"));
  }

  @Test
  void withdrawnCandidateCannotBeEditedOrApprovedThroughApiService() {
    sources.withdraw(candidateId);
    for (boolean approved : List.of(false, true)) {
      var ex =
          assertThrows(
              ApiException.class,
              () ->
                  sources.updateCandidate(
                      candidateId,
                      new SourceService.CandidateUpdate("EXPERIENCE", "试图重新编辑", "USED", approved)));
      assertEquals(409, ex.status());
      assertEquals("CANDIDATE_WITHDRAWN", ex.code());
    }
    assertEquals(0L, Database.number(candidate(), "approved"));
  }

  @Test
  void legacyCandidatesArePreservedAndMigrationCanRunAgain() throws Exception {
    LegacyFixtures.app(db);
    db.app().execute("ALTER TABLE candidates DROP COLUMN manually_edited");
    var upgraded = new Database(RuntimeConfig.sqlite(root.resolve("app.db").toString()), props);
    assertEquals(1L, Database.number(candidate(), "manually_edited"));
    assertEquals("我使用 Java 编写了个人项目。", candidate().get("content"));
    var upgradedSources = new SourceService(upgraded, props, clock);
    var upgradedWorker =
        new ImportWorker(
            upgraded,
            props,
            new DocumentParser(),
            upgradedSources,
            generation,
            null,
            new Diagnostics(upgraded, clock),
            clock);
    upgradedSources.enqueue("EXTRACT", sourceId);
    upgradedWorker.tick();
    assertEquals(0, generation.calls);
    upgradedWorker.tick();
    var uploaded =
        upgradedSources.upload(
            new MockMultipartFile(
                "file", "new.txt", "text/plain", "新资料".getBytes(StandardCharsets.UTF_8)),
            "{}");
    upgradedWorker.tick();
    assertEquals(
        0,
        upgraded
            .app()
            .queryForObject(
                "SELECT manually_edited FROM candidates WHERE source_id=?",
                Integer.class,
                uploaded.get("sourceId")));
    assertDoesNotThrow(
        () -> new Database(RuntimeConfig.sqlite(root.resolve("app.db").toString()), props));
    assertEquals(
        0,
        upgraded
            .app()
            .queryForObject(
                "SELECT manually_edited FROM candidates WHERE source_id=?",
                Integer.class,
                uploaded.get("sourceId")));
  }

  @Test
  void protectedSegmentsDoNotShiftCursorOrSkipLaterDrafts() throws Exception {
    var uploaded =
        sources.upload(
            new MockMultipartFile(
                "file",
                "messages.json",
                "application/json",
                Json.write(
                        List.of(
                            Map.of("content", "片段一"),
                            Map.of("content", "片段二"),
                            Map.of("content", "片段三"),
                            Map.of("content", "片段四"),
                            Map.of("content", "片段五")))
                    .getBytes(StandardCharsets.UTF_8)),
            "{}");
    worker.tick();
    var ids =
        db.app()
            .queryForList(
                "SELECT c.id FROM candidates c JOIN segments s ON s.id=c.segment_id WHERE c.source_id=? ORDER BY s.ordinal",
                String.class,
                uploaded.get("sourceId"));
    sources.updateCandidate(
        ids.get(0), new SourceService.CandidateUpdate("REFERENCE", "保留草稿", "UNCONFIRMED", false));
    sources.withdraw(ids.get(2));
    String job = sources.enqueue("EXTRACT", uploaded.get("sourceId").toString());
    for (int i = 0; i < 4; i++) worker.tick();
    assertEquals(
        "COMPLETED",
        db.app().queryForObject("SELECT status FROM jobs WHERE id=?", String.class, job));
    assertEquals(
        5, db.app().queryForObject("SELECT cursor FROM jobs WHERE id=?", Integer.class, job));
    assertEquals(3, generation.calls);
    assertEquals(
        List.of("保留草稿", "片段二", "片段三", "片段四", "片段五"),
        db.app()
            .queryForList(
                "SELECT c.content FROM candidates c JOIN segments s ON s.id=c.segment_id WHERE c.source_id=? ORDER BY s.ordinal",
                String.class,
                uploaded.get("sourceId")));
  }

  String knowledgeSource() throws Exception {
    var uploaded =
        sources.upload(
            new MockMultipartFile(
                "file",
                "knowledge.md",
                "text/markdown",
                "1.1 字符串比较\n比较字符串内容使用 equals，比较引用地址使用 ==。\n1.2 接口与实现\n接口用于描述行为规范，一个类可以实现多个接口。"
                    .getBytes(StandardCharsets.UTF_8)),
            "{\"documentType\":\"KNOWLEDGE\"}");
    worker.tick();
    return uploaded.get("sourceId").toString();
  }

  @Test
  void knowledgeUploadProducesSeveralUnconfirmedTopicsWithoutAiCalls() throws Exception {
    generation.configured = true;
    String id = knowledgeSource();
    var records = db.app().queryForList("SELECT * FROM candidates WHERE source_id=?", id);
    assertEquals(2, records.size());
    assertTrue(
        records.stream()
            .allMatch(
                c ->
                    "KNOWLEDGE".equals(c.get("kind"))
                        && "UNCONFIRMED".equals(c.get("mastery"))
                        && Database.number(c, "approved") == 0));
    assertEquals(0, generation.calls);
    assertEquals(
        0,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE subject_id=? AND type='EXTRACT'",
                Integer.class,
                id));
  }

  @Test
  void rebuildingIsIdempotentAndPreservesAllDraftsOnAnEditedSegment() throws Exception {
    String id = knowledgeSource();
    assertEquals(2, sources.rebuildKnowledge(id).get("created"));
    assertEquals(2, sources.rebuildKnowledge(id).get("created"));
    var records = db.app().queryForList("SELECT * FROM candidates WHERE source_id=?", id);
    String edited = Database.text(records.getFirst(), "id");
    sources.updateCandidate(
        edited,
        new SourceService.CandidateUpdate("KNOWLEDGE", "我确认理解接口规范以及字符串比较方法。", "UNDERSTOOD", true));
    var result = sources.rebuildKnowledge(id);
    assertEquals(0, result.get("created"));
    assertEquals(1, result.get("preservedSegments"));
    assertEquals(
        2,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM candidates WHERE source_id=?", Integer.class, id));
    assertEquals(
        1,
        db.app()
            .queryForObject("SELECT approved FROM candidates WHERE id=?", Integer.class, edited));
  }

  @Test
  void sourceCannotBeRebuiltWhileModelIsRunning() {
    generation.beforeReply =
        () ->
            assertEquals(
                "SOURCE_BUSY",
                assertThrows(ApiException.class, () -> sources.rebuildKnowledge(sourceId)).code());
    extract();
    assertEquals(1, generation.calls);
  }

  @Test
  void hallucinatedOrForeignDraftsFailWithoutChangingContentOrCursor() {
    generation.transform =
        records -> {
          records.getFirst().put("content", "模型编造的生产实践");
          return records;
        };
    extract();
    assertEquals("我使用 Java 编写了个人项目。", candidate().get("content"));
    assertEquals(0L, Database.number(candidate(), "revision"));
    assertEquals(
        "INVALID_EXTRACTION",
        db.app().queryForObject("SELECT error_code FROM jobs WHERE type='EXTRACT'", String.class));
    assertEquals(
        0, db.app().queryForObject("SELECT cursor FROM jobs WHERE type='EXTRACT'", Integer.class));
  }

  @Test
  void knowledgeCannotBePromotedToPersonalExperienceByModel() throws Exception {
    String id = knowledgeSource();
    generation.transform =
        records -> {
          records.getFirst().put("kind", "EXPERIENCE");
          return records;
        };
    sources.enqueue("EXTRACT", id);
    worker.tick();
    assertEquals(
        "INVALID_EXTRACTION",
        db.app()
            .queryForObject(
                "SELECT error_code FROM jobs WHERE subject_id=? AND type='EXTRACT'",
                String.class,
                id));
    assertEquals(
        2,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM candidates WHERE source_id=? AND kind='KNOWLEDGE' AND revision=0",
                Integer.class,
                id));
  }

  @Test
  void offlineRepairBacksUpAndPreservesHumanWorkAndRejectsRunningInstance() throws Exception {
    saveDraft();
    try (var lock = new DataDirectoryLock(root)) {
      assertThrows(
          java.io.IOException.class,
          () -> cyou.jujube.operations.KnowledgeDraftRepair.rebuild(root, sourceId));
    }
    var result = cyou.jujube.operations.KnowledgeDraftRepair.rebuild(root, sourceId);
    assertTrue(java.nio.file.Files.isRegularFile(Path.of(result.get("backup").toString())));
    assertEquals("本人保存的草稿", candidate().get("content"));
    assertEquals(1, result.get("preservedSegments"));
  }

  @Test
  void explicitAiModeQueuesKnowledgeExtraction() throws Exception {
    var uploaded =
        sources.upload(
            new MockMultipartFile(
                "file",
                "ai.md",
                "text/markdown",
                "1.1 接口\n接口用于描述行为规范，一个类可以实现多个接口。".getBytes(StandardCharsets.UTF_8)),
            "{\"documentType\":\"KNOWLEDGE\",\"processingMode\":\"AI\"}");
    worker.tick();
    assertEquals(
        1,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE subject_id=? AND type='EXTRACT' AND status='QUEUED'",
                Integer.class,
                uploaded.get("sourceId")));
    worker.tick();
    assertEquals(1, generation.calls);
  }

  @Test
  void preparedJsonBypassesAiAndCannotImportApprovalOrMastery() throws Exception {
    generation.configured = true;
    String body =
        Json.write(
            Map.of(
                "schema",
                PreparedKnowledge.SCHEMA,
                "records",
                List.of(
                    Map.of(
                        "title",
                        "接口规范",
                        "content",
                        "接口用于描述行为规范，可以由类实现。",
                        "approved",
                        true,
                        "mastery",
                        "USED",
                        "source",
                        Map.of(
                            "name",
                            "学习笔记",
                            "location",
                            "接口章节",
                            "excerpt",
                            "接口用于描述行为规范，一个类可以实现多个接口。")))));
    var uploaded =
        sources.upload(
            new MockMultipartFile(
                "file", "prepared.json", "application/json", body.getBytes(StandardCharsets.UTF_8)),
            "{\"documentType\":\"KNOWLEDGE\",\"processingMode\":\"PREPARED\"}");
    worker.tick();
    String id = uploaded.get("sourceId").toString();
    var record = Database.required(db.app(), "SELECT * FROM candidates WHERE source_id=?", id);
    assertEquals("接口规范\n接口用于描述行为规范，可以由类实现。", record.get("content"));
    assertEquals("UNCONFIRMED", record.get("mastery"));
    assertEquals(0L, Database.number(record, "approved"));
    assertEquals(1L, Database.number(record, "manually_edited"));
    assertEquals(0, generation.calls);
    assertEquals(
        "PREPARED_IMPORT_NO_AI",
        assertThrows(ApiException.class, () -> sources.enqueue("EXTRACT", id)).code());
    assertEquals("PREPARED", ((Map<?, ?>) sources.source(id).get("source")).get("processingMode"));
  }

  @Test
  void preparedTextWithConfiguredModelDoesNotQueueExtraction() throws Exception {
    generation.configured = true;
    var uploaded =
        sources.upload(
            new MockMultipartFile(
                "file",
                "prepared.md",
                "text/markdown",
                "## 接口\n接口用于描述行为规范，一个类可以实现多个接口。".getBytes(StandardCharsets.UTF_8)),
            "{\"documentType\":\"KNOWLEDGE\",\"processingMode\":\"PREPARED\"}");
    worker.tick();
    assertEquals(
        0,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE subject_id=? AND type='EXTRACT'",
                Integer.class,
                uploaded.get("sourceId")));
    assertEquals(0, generation.calls);
  }

  @Test
  void invalidPreparedJsonLeavesNoPartialCandidatesOrSegments() throws Exception {
    String body =
        Json.write(
            Map.of(
                "schema",
                PreparedKnowledge.SCHEMA,
                "records",
                List.of(
                    Map.of(
                        "title",
                        "有效标题",
                        "content",
                        "有效内容",
                        "source",
                        Map.of("name", "教材", "location", "第1页", "excerpt", "原文依据")),
                    Map.of("title", "缺失原文依据", "content", "解释"))));
    var uploaded =
        sources.upload(
            new MockMultipartFile(
                "file", "broken.json", "application/json", body.getBytes(StandardCharsets.UTF_8)),
            "{\"documentType\":\"KNOWLEDGE\",\"processingMode\":\"PREPARED\"}");
    worker.tick();
    assertEquals(
        0,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM candidates WHERE source_id=?",
                Integer.class,
                uploaded.get("sourceId")));
    assertEquals(
        0,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM segments WHERE source_id=?",
                Integer.class,
                uploaded.get("sourceId")));
    assertEquals(
        "INVALID_PREPARED_KNOWLEDGE",
        db.app()
            .queryForObject(
                "SELECT error_code FROM jobs WHERE subject_id=?",
                String.class,
                uploaded.get("sourceId")));
  }

  static class Extraction implements GenerationProvider {
    int calls;
    boolean configured;
    Runnable beforeReply = () -> {};
    java.util.function.UnaryOperator<ArrayList<Map<String, String>>> transform = records -> records;

    public boolean configured() {
      return configured;
    }

    public String generate(String id, String stage, String system, String input) {
      calls++;
      beforeReply.run();
      var records = new ArrayList<Map<String, String>>();
      for (var segment : Json.read(input).path("drafts"))
        records.add(
            new HashMap<>(
                Map.of(
                    "candidateId",
                    segment.path("id").asText(),
                    "kind",
                    segment.path("kind").asText(),
                    "content",
                    segment.path("content").asText())));
      return Json.write(Map.of("records", transform.apply(records)));
    }
  }
}
