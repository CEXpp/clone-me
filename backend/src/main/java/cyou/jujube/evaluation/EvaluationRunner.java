package cyou.jujube.evaluation;

import cyou.jujube.common.*;
import cyou.jujube.conversation.*;
import cyou.jujube.notes.*;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.Database;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** 复用生产链路，按题保存原始观测。指标与人工复核由 Python 处理，不让生成器给自己打分。 */
public final class EvaluationRunner {
  private final Database db;
  private final NoteIndex index;
  private final ChatService chat;
  private final VisitorSessions sessions;

  public EvaluationRunner(
      Database db, NoteIndex index, ChatService chat, VisitorSessions sessions) {
    this.db = db;
    this.index = index;
    this.chat = chat;
    this.sessions = sessions;
  }

  public void reindex() {
    db.app()
        .update(
            "UPDATE notes SET indexed_revision=0,index_error='' WHERE knowledge=1 AND deleted=0");
    while (db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM notes WHERE knowledge=1 AND deleted=0 AND revision!=indexed_revision",
                Integer.class)
        > 0) {
      index.tick();
      if (db.app()
              .queryForObject(
                  "SELECT COUNT(*) FROM notes WHERE knowledge=1 AND deleted=0 AND index_error!=''",
                  Integer.class)
          > 0)
        throw new IllegalArgumentException(
            "Snapshot indexing failed; inspect note index_error in the isolated workspace");
    }
  }

  public void validate(JsonNode cases, boolean lexicalOnly) {
    validateLabels(cases);
    var pending =
        db.app()
            .queryForList(
                "SELECT id,index_fingerprint FROM notes WHERE knowledge=1 AND deleted=0 AND (revision!=indexed_revision OR index_error!='')");
    if (!pending.isEmpty())
      throw new IllegalArgumentException(
          "Shared notes must finish indexing first, or use --reindex on the snapshot");
    if (!lexicalOnly
        && db.app()
                .queryForObject(
                    "SELECT COUNT(*) FROM notes WHERE knowledge=1 AND deleted=0 AND index_fingerprint!=?",
                    Integer.class,
                    index.fingerprint())
            > 0)
      throw new IllegalArgumentException(
          "Embedding/index fingerprint differs; synchronize first or use --reindex");
  }

  public void validateLabels(JsonNode cases) {
    for (var test : cases) {
      for (var group : test.path("evidence"))
        for (var gold : group) {
          var note =
              Database.required(
                  db.app(), "SELECT * FROM notes WHERE id=?", gold.path("noteId").asText());
          if (Database.number(note, "revision") != gold.path("revision").asLong()
              || Database.number(note, "knowledge") != 1
              || Database.number(note, "deleted") != 0)
            throw new IllegalArgumentException(
                "Stale or private gold evidence in case " + test.path("id").asText());
          String quote = normalize(gold.path("quote").asText());
          boolean exists =
              (!gold.has("headingPath")
                      && normalize(Database.text(note, "plain_text")).contains(quote))
                  || StructuredChunks.split(Database.text(note, "body")).stream()
                      .anyMatch(
                          p ->
                              (!gold.has("headingPath")
                                      || p.headingPath().equals(gold.path("headingPath").asText()))
                                  && normalize(p.content()).contains(quote));
          if (!exists)
            throw new IllegalArgumentException(
                "Gold quote missing from note in case " + test.path("id").asText());
        }
      for (var id : test.path("forbiddenNoteIds"))
        Database.required(db.app(), "SELECT id FROM notes WHERE id=?", id.asText());
    }
  }

  private static String normalize(String text) {
    return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        .replaceAll("(?U)\\s+", "");
  }

  public String corpusHash() {
    return Crypto.hash(
        Json.write(
            db.app()
                .queryForList(
                    "SELECT n.id,n.revision,n.title,n.body,n.knowledge,n.deleted,s.name AS section,b.name AS notebook FROM notes n JOIN note_sections s ON s.id=n.section_id JOIN notebooks b ON b.id=s.notebook_id ORDER BY n.id")));
  }

  public String indexHash() {
    return Crypto.hash(
        Json.write(
            db.app()
                .queryForList(
                    "SELECT note_id,revision,ordinal,content,context_text,fingerprint,embedding FROM note_chunks ORDER BY note_id,ordinal")));
  }

  public List<Map<String, Object>> usage(String prefix) {
    String where = prefix == null ? "" : " WHERE request_id LIKE ?";
    String sql =
        "SELECT stage,provider,model,status,SUM(COALESCE(actual,0)) AS actualMicros,SUM(CASE WHEN status!='SETTLED' THEN reserved ELSE 0 END) AS uncertainMicros,SUM(COALESCE(input_tokens,0)) AS inputTokens,SUM(COALESCE(output_tokens,0)) AS outputTokens,SUM(COALESCE(duration_ms,0)) AS durationMs,COUNT(*) AS calls FROM ai_calls"
            + where
            + " GROUP BY stage,provider,model,status";
    return prefix == null ? db.app().queryForList(sql) : db.app().queryForList(sql, prefix + "%");
  }

  public void run(JsonNode config, Path output) throws Exception {
    Files.createFile(output);
    String stopped = "";
    for (var variant : config.path("variants"))
      for (var test : config.path("cases")) {
        String name = variant.asText(), prefix = "eval-" + UUID.randomUUID() + "-";
        var options = new NoteIndex.SearchOptions(!name.equals("lexical"), name.equals("full"));
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", test.path("id").asText());
        entry.put("variant", name);
        var trace = new RetrievalTrace();
        long started = System.nanoTime();
        String token = null;
        try (var deadline =
            RequestDeadline.start(Duration.ofSeconds(75L * (test.path("dialogue").size() + 1)))) {
          if (!stopped.isEmpty()) throw ApiException.unavailable("NOT_RUN", stopped);
          var dialogue = new ArrayList<Object>();
          if (config.path("mode").asText().equals("answer")) {
            token = sessions.create().token();
            int turn = 0;
            for (var question : test.path("dialogue"))
              dialogue.add(
                  chat.ask(
                      prefix + turn++,
                      token,
                      prefix,
                      question.asText(),
                      UUID.randomUUID().toString(),
                      options,
                      null));
            // 新建轨迹，阶段耗时不包含准备轮次；整题耗时和费用仍包含全部轮次。
            trace = new RetrievalTrace();
            var answer =
                chat.ask(
                    prefix + "final",
                    token,
                    prefix,
                    test.path("question").asText(),
                    UUID.randomUUID().toString(),
                    options,
                    trace);
            entry.put("answer", answer);
            var cited =
                answer.sources().stream()
                    .map(ChatService.Source::id)
                    .collect(java.util.stream.Collectors.toSet());
            trace.record(
                "cited",
                trace.stages().getOrDefault("context", List.of()).stream()
                    .filter(h -> cited.contains(h.id()))
                    .toList());
          } else {
            var history = new ArrayList<Map<String, Object>>();
            for (var question : test.path("dialogue"))
              history.add(Map.of("role", "user", "content", question.asText()));
            var found =
                index.search(
                    prefix + "final",
                    ChatService.retrievalQuery(test.path("question").asText(), history),
                    options,
                    trace);
            var evidence = ContextBuilder.evidence(found.passages());
            trace.context("budget", evidence);
            trace.context("context", evidence);
          }
          entry.put("dialogue", dialogue);
        } catch (RuntimeException ex) {
          String code = ex instanceof ApiException api ? api.code() : "EVALUATION_FAILED";
          entry.put("error", code);
          if (Set.of(
                  "RUN_BUDGET_REACHED",
                  "DAILY_BUDGET_REACHED",
                  "PRICING_REQUIRED",
                  "PAID_CALLS_PAUSED")
              .contains(code)) stopped = code;
        } finally {
          if (token != null) sessions.clear(token);
        }
        entry.put("query", trace.query());
        entry.put("stages", trace.stages());
        entry.put("timingsMs", trace.timings());
        entry.put("semanticUsed", trace.semanticUsed());
        entry.put("fallbackReason", trace.fallbackReason());
        entry.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
        entry.put("usage", usage(prefix));
        Files.writeString(output, Json.write(entry) + "\n", StandardOpenOption.APPEND);
      }
  }
}
