package cyou.jujube.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.common.*;
import cyou.jujube.config.*;
import cyou.jujube.ingestion.DocumentParser;
import cyou.jujube.notes.*;
import cyou.jujube.storage.Database;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.JsonNode;

class EvaluationRunnerTest {
  @TempDir Path root;
  Database db;
  NoteService notes;
  NoteIndex index;
  EvaluationRunner runner;
  String noteId;

  @BeforeEach
  void setup() throws Exception {
    var props =
        new Binder(new MapConfigurationPropertySource(Map.of("clone.data-dir", root.toString())))
            .bind("clone", Bindable.of(CloneProperties.class))
            .get();
    db = new Database(RuntimeConfig.sqlite(root.resolve("app.db").toString()), props);
    notes = new NoteService(db, props, new DocumentParser());
    index =
        new NoteIndex(
            db,
            new EmbeddingProvider() {
              public boolean configured() {
                return false;
              }

              public String fingerprint() {
                return "fixture";
              }

              public float[] embed(String id, String text) {
                throw new AssertionError("No model calls");
              }
            });
    runner = new EvaluationRunner(db, index, null, null);
    noteId = ((Map<?, ?>) notes.create("default", "索引测试")).get("id").toString();
    var body =
        Json.read(
            "{\"type\":\"doc\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":1},\"content\":[{\"type\":\"text\",\"text\":\"索引\"}]},{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"索引增加写入成本。\"}]}]}");
    notes.save(noteId, new NoteService.Edit("default", "索引测试", body, 1, true, false, false));
    index.tick();
  }

  JsonNode cases(long revision, String quote, String heading) {
    return Json.read(
        Json.write(
            List.of(
                Map.of(
                    "id",
                    "fixture",
                    "question",
                    "索引有什么代价？",
                    "evidence",
                    List.of(
                        List.of(
                            Map.of(
                                "noteId",
                                noteId,
                                "revision",
                                revision,
                                "quote",
                                quote,
                                "headingPath",
                                heading)))))));
  }

  @Test
  void validatesOriginalRevisionQuoteAndHeadingBeforeAnyCalls() {
    assertDoesNotThrow(() -> runner.validate(cases(2, "写入成本", "索引"), true));
    assertThrows(
        IllegalArgumentException.class, () -> runner.validate(cases(1, "写入成本", "索引"), true));
    assertThrows(
        IllegalArgumentException.class, () -> runner.validate(cases(2, "编造内容", "索引"), true));
    assertThrows(
        IllegalArgumentException.class, () -> runner.validate(cases(2, "写入成本", "错误小节"), true));
    db.app().update("UPDATE notes SET knowledge=0 WHERE id=?", noteId);
    assertThrows(
        IllegalArgumentException.class, () -> runner.validate(cases(2, "写入成本", "索引"), true));
  }

  @Test
  void capturesAllStagesAndUsesStableSourceIdentityAcrossReindex() {
    var trace = new RetrievalTrace();
    index.search("trace", "索引", new NoteIndex.SearchOptions(false, false), trace);
    assertEquals(
        Set.of("lexical", "semantic", "fused", "reranked", "selected"), trace.stages().keySet());
    var before = trace.stages().get("selected").getFirst();
    assertEquals(noteId, before.noteId());
    assertEquals(2, before.revision());
    assertEquals("索引", before.headingPath());
    String corpus = runner.corpusHash();
    runner.reindex();
    var after = new RetrievalTrace();
    index.search("trace2", "索引", new NoteIndex.SearchOptions(false, false), after);
    assertNotEquals(before.id(), after.stages().get("selected").getFirst().id());
    assertEquals(corpus, runner.corpusHash());
    assertEquals(before.content(), after.stages().get("selected").getFirst().content());
  }

  @Test
  void retrievalRunWritesResultsWithoutInvokingChatOrModels() throws Exception {
    var config =
        Json.read(
            Json.write(
                Map.of(
                    "mode",
                    "retrieval",
                    "variants",
                    List.of("lexical"),
                    "cases",
                    cases(2, "写入成本", "索引"))));
    Path output = root.resolve("results.jsonl");
    runner.run(config, output);
    var row = Json.read(Files.readString(output));
    assertFalse(row.has("error"));
    assertEquals(noteId, row.path("stages").path("context").get(0).path("noteId").asText());
    assertEquals(0, row.path("usage").size());
    assertEquals(
        0, db.sessions().queryForObject("SELECT COUNT(*) FROM visitor_sessions", Integer.class));
  }

  @Test
  void usageIncludesSettledAndUncertainCostsOnlyForTheCurrentCase() {
    db.app()
        .update(
            "INSERT INTO ai_calls(id,request_id,stage,provider,model,day,reserved,actual,status,input_tokens,duration_ms,created_at) VALUES('one','evaluation-prefix-final','GENERATION','fixture','fixture','2026-11-08',100,75,'SETTLED',30,9,0)");
    db.app()
        .update(
            "INSERT INTO ai_calls(id,request_id,stage,provider,model,day,reserved,status,duration_ms,created_at) VALUES('two','evaluation-prefix-final','ANSWER_CHECK','fixture','fixture','2026-11-08',90,'UNCERTAIN',20,0)");
    db.app()
        .update(
            "INSERT INTO ai_calls(id,request_id,stage,provider,model,day,reserved,status,created_at) VALUES('other','unrelated','EMBEDDING','fixture','fixture','2026-11-08',0,'SETTLED',0)");
    var usage = runner.usage("evaluation-prefix-");
    assertEquals(2, usage.size());
    assertEquals(75, usage.stream().mapToLong(r -> Database.number(r, "actualMicros")).sum());
    assertEquals(90, usage.stream().mapToLong(r -> Database.number(r, "uncertainMicros")).sum());
    assertEquals(29, usage.stream().mapToLong(r -> Database.number(r, "durationMs")).sum());
    assertEquals(3, runner.usage(null).size());
  }

  @Test
  void commandRejectsOrdinaryDataDirectoryWithoutWorkspaceMarker() throws Exception {
    Path file = root.resolve("run.json");
    Files.writeString(file, Json.write(Map.of("workspace", root.toString())));
    assertThrows(java.io.IOException.class, () -> EvaluationCommand.run(file));
    assertEquals(1, db.app().queryForObject("SELECT COUNT(*) FROM notes", Integer.class));
  }
}
