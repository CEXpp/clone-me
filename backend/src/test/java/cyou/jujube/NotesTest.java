package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.common.*;
import cyou.jujube.config.*;
import cyou.jujube.ingestion.DocumentParser;
import cyou.jujube.notes.*;
import cyou.jujube.operations.*;
import cyou.jujube.storage.Database;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockMultipartFile;

class NotesTest {
  @TempDir Path root;
  Database db;
  NoteService notes;
  NoteIndex index;
  CloneProperties props;

  @BeforeEach
  void setup() throws Exception {
    props =
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
                return "test";
              }

              public float[] embed(String id, String text) {
                throw new AssertionError("Private/local note must not invoke embedding");
              }
            });
  }

  Map<String, Object> create() {
    return (Map<String, Object>) notes.create("default", "Java 笔记");
  }

  NoteService.Edit edit(long revision, boolean knowledge, boolean deleted, String text) {
    return new NoteService.Edit(
        "default",
        "Java 笔记",
        Json.read(
            Json.write(
                Map.of(
                    "type",
                    "doc",
                    "content",
                    List.of(
                        Map.of(
                            "type",
                            "paragraph",
                            "content",
                            List.of(Map.of("type", "text", "text", text))))))),
        revision,
        knowledge,
        false,
        deleted);
  }

  @Test
  void savesHistoryAndRejectsStaleWrites() {
    String id = create().get("id").toString();
    notes.save(id, edit(1, false, false, "Java 索引示例"));
    assertEquals(
        "NOTE_CONFLICT",
        assertThrows(ApiException.class, () -> notes.save(id, edit(1, false, false, "旧标签页内容")))
            .code());
    assertTrue(notes.get(id).get("plain_text").toString().contains("索引示例"));
    notes.restore(id, 1, 2);
    assertEquals(3, Database.number(notes.get(id), "revision"));
    assertEquals("", notes.get(id).get("plain_text").toString().strip());
  }

  @Test
  void onlyEnabledUndeletedNotesAreSearchableAndRevocationIsImmediate() {
    String id = create().get("id").toString();
    notes.save(id, edit(1, false, false, "Java 数据库索引查询"));
    index.tick();
    assertTrue(index.search("private", "Java 索引").passages().isEmpty());
    notes.save(id, edit(2, true, false, "Java 数据库索引查询"));
    index.tick();
    var results = index.search("shared", "Java 索引").passages();
    assertFalse(results.isEmpty());
    String chunk = results.getFirst().id();
    notes.save(id, edit(3, false, false, "Java 数据库索引查询"));
    assertFalse(index.available(chunk));
    assertTrue(index.search("revoked", "Java 索引").passages().isEmpty());
    notes.save(id, edit(4, true, true, "Java 数据库索引查询"));
    index.tick();
    assertTrue(index.search("deleted", "Java 索引").passages().isEmpty());
  }

  @Test
  void importKeepsOriginalAndBackupRestoresAssets() throws Exception {
    var imported =
        (Map<?, ?>)
            notes.importFile(
                "default",
                false,
                new MockMultipartFile(
                    "file",
                    "笔记.txt",
                    "text/plain",
                    "数据库索引笔记".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(1, db.app().queryForObject("SELECT COUNT(*) FROM note_assets", Integer.class));
    assertEquals(0, ((Number) imported.get("knowledge")).intValue());
    var backup = new BackupService(db, props, Clock.systemUTC()).create();
    Path restored = root.resolve("restored");
    BackupRestore.restore(root.resolve("backups").resolve(backup.get("name").toString()), restored);
    assertTrue(Files.isDirectory(restored.resolve("assets")));
    var copy =
        new Database(
            RuntimeConfig.sqlite(restored.resolve("app.db").toString()),
            new Binder(
                    new MapConfigurationPropertySource(
                        Map.of("clone.data-dir", restored.toString())))
                .bind("clone", Bindable.of(CloneProperties.class))
                .get());
    assertEquals(1, copy.app().queryForObject("SELECT COUNT(*) FROM notes", Integer.class));
  }

  @Test
  void reindexReplacesFullTextEntriesWithoutLeavingStaleRows() {
    String id = create().get("id").toString();
    notes.save(id, edit(1, true, false, "Java oldunique 索引"));
    index.tick();
    notes.save(id, edit(2, true, false, "Java newunique 查询"));
    index.tick();
    assertTrue(index.search("old", "oldunique").passages().isEmpty());
    assertFalse(index.search("new", "newunique").passages().isEmpty());
    assertEquals(
        db.app().queryForObject("SELECT COUNT(*) FROM note_chunks", Long.class),
        db.app().queryForObject("SELECT COUNT(*) FROM notes_fts", Long.class));
  }

  @Test
  void slowEmbeddingCannotPublishAnObsoleteRevision() {
    String id = create().get("id").toString();
    notes.save(id, edit(1, true, false, "Java 旧正文"));
    var slow =
        new NoteIndex(
            db,
            new EmbeddingProvider() {
              public boolean configured() {
                return true;
              }

              public String fingerprint() {
                return "fixture";
              }

              public float[] embed(String request, String text) {
                notes.save(id, edit(2, true, false, "Java 新正文"));
                return new float[] {1, 0};
              }
            });
    slow.tick();
    assertEquals(0, db.app().queryForObject("SELECT COUNT(*) FROM note_chunks", Integer.class));
    assertEquals(3, Database.number(notes.get(id), "revision"));
    index.tick();
    assertFalse(index.search("latest", "Java 新正文").passages().isEmpty());
  }

  @Test
  void rerankerCannotIntroduceUnknownEvidence() {
    String id = create().get("id").toString();
    notes.save(id, edit(1, true, false, "Java 索引笔记"));
    index.tick();
    var local =
        new EmbeddingProvider() {
          public boolean configured() {
            return false;
          }

          public String fingerprint() {
            return "local";
          }

          public float[] embed(String request, String text) {
            throw new AssertionError();
          }
        };
    var ranking =
        new cyou.jujube.ai.RerankProvider() {
          public boolean configured() {
            return true;
          }

          public java.util.List<cyou.jujube.ai.RerankProvider.Score> rank(
              String request, String question, java.util.List<String> documents) {
            return java.util.List.of(new cyou.jujube.ai.RerankProvider.Score(99, 1));
          }
        };
    var result = new NoteIndex(db, local, ranking).search("ranking-test", "Java 索引");
    assertEquals("RERANK_INVALID_RESPONSE", result.fallbackReason());
    assertFalse(result.passages().isEmpty());
    assertTrue(result.passages().stream().noneMatch(p -> p.id().equals("unknown-source")));
  }

  @Test
  void dedicatedRerankingChangesOrderAndReceivesContext() {
    String first = create().get("id").toString(), second = create().get("id").toString();
    notes.save(first, edit(1, true, false, "索引与内存管理的区别"));
    notes.save(second, edit(1, true, false, "索引通过 B 树加速数据库查询"));
    index.tick();
    var local =
        new EmbeddingProvider() {
          public boolean configured() {
            return false;
          }

          public String fingerprint() {
            return "local";
          }

          public float[] embed(String id, String text) {
            throw new AssertionError();
          }
        };
    var ranker =
        new cyou.jujube.ai.RerankProvider() {
          public boolean configured() {
            return true;
          }

          public List<Score> rank(String request, String question, List<String> documents) {
            assertEquals(2, documents.size());
            assertTrue(
                documents.stream()
                    .allMatch(t -> t.contains("笔记位置：我的笔记 / 随手记") && t.contains("笔记标题：Java 笔记")));
            var scores = new ArrayList<Score>();
            for (int j = 0; j < documents.size(); j++)
              scores.add(new Score(j, documents.get(j).contains("B 树") ? 0.99 : 0.1));
            scores.sort(java.util.Comparator.comparingDouble(Score::relevance).reversed());
            return scores;
          }
        };
    var result = new NoteIndex(db, local, ranker).search("order", "索引");
    assertEquals(second, result.passages().getFirst().sourceId());
    assertEquals(0.99, result.passages().getFirst().score());
    assertEquals("", result.fallbackReason());
  }

  @Test
  void embeddingConfigurationChangesAutomaticallyRebuildTheIndex() {
    String id = create().get("id").toString();
    notes.save(id, edit(1, true, false, "数据库索引查询正文"));
    index.tick();
    var inputs = new ArrayList<String>();
    var vector =
        new EmbeddingProvider() {
          public boolean configured() {
            return true;
          }

          public String fingerprint() {
            return "new-model";
          }

          public float[] embed(String request, String text) {
            inputs.add(text);
            return new float[] {1, 0};
          }
        };
    var rebuilt = new NoteIndex(db, vector);
    rebuilt.tick();
    assertEquals(1, inputs.size());
    assertTrue(inputs.getFirst().contains("笔记标题：Java 笔记"));
    assertTrue(inputs.getFirst().contains("数据库索引查询正文"));
    rebuilt.tick();
    assertEquals(1, inputs.size());
    assertEquals(
        1, db.app().queryForObject("SELECT COUNT(*) FROM note_chunk_parents", Integer.class));
    assertEquals(2, Database.number(notes.get(id), "indexed_revision"));
  }

  @Test
  void sameSectionCandidatesExpandOnceWithoutPullingAnotherHeading() {
    String id = create().get("id").toString();
    var body =
        Json.read(
            "{\"type\":\"doc\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":1},\"content\":[{\"type\":\"text\",\"text\":\"数据库\"}]},{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"索引说明。\"}]},{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"覆盖索引的上下文。\"}]},{\"type\":\"heading\",\"attrs\":{\"level\":1},\"content\":[{\"type\":\"text\",\"text\":\"食谱\"}]},{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"炒菜加入食盐。\"}]}]}");
    notes.save(id, new NoteService.Edit("default", "技术", body, 1, true, false, false));
    index.tick();
    var passages = index.search("parent", "覆盖索引").passages();
    assertEquals(1, passages.size());
    assertTrue(passages.getFirst().content().contains("索引说明"));
    assertFalse(passages.getFirst().content().contains("食盐"));
    assertTrue(passages.getFirst().label().contains("数据库"));
  }
}
