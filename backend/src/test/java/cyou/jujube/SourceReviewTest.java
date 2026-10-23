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
    assertEquals("模型提取的草稿", candidate().get("content"));
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
        List.of("保留草稿", "模型提取的草稿", "片段三", "模型提取的草稿", "模型提取的草稿"),
        db.app()
            .queryForList(
                "SELECT c.content FROM candidates c JOIN segments s ON s.id=c.segment_id WHERE c.source_id=? ORDER BY s.ordinal",
                String.class,
                uploaded.get("sourceId")));
  }

  static class Extraction implements GenerationProvider {
    int calls;
    Runnable beforeReply = () -> {};

    public boolean configured() {
      return false;
    }

    public String generate(String id, String stage, String system, String input) {
      calls++;
      beforeReply.run();
      var records = new ArrayList<Map<String, String>>();
      for (var segment : Json.read(input).path("segments"))
        records.add(
            Map.of(
                "segmentId",
                segment.path("id").asText(),
                "kind",
                "REFERENCE",
                "content",
                "模型提取的草稿"));
      return Json.write(Map.of("records", records));
    }
  }
}
