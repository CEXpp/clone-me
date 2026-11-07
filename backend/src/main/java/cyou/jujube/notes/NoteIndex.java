package cyou.jujube.notes;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.ai.RerankProvider;
import cyou.jujube.common.ApiException;
import cyou.jujube.common.RequestDeadline;
import cyou.jujube.retrieval.HybridRetriever;
import cyou.jujube.retrieval.TextIndex;
import cyou.jujube.storage.Database;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 网络调用在事务外完成，提交时核对修订号；旧任务不能覆盖较新的正文。 */
@Service
public class NoteIndex {
  private final Database db;
  private final EmbeddingProvider embedding;
  private final RerankProvider reranker;

  public NoteIndex(Database db, EmbeddingProvider embedding) {
    this(db, embedding, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public NoteIndex(Database db, EmbeddingProvider embedding, RerankProvider reranker) {
    this.db = db;
    this.embedding = embedding;
    this.reranker = reranker;
  }

  @org.springframework.beans.factory.annotation.Value("${clone.jobs-enabled:true}")
  private boolean enabled = true;

  @Scheduled(fixedDelay = 10000)
  public void scheduled() {
    if (enabled) tick();
  }

  public synchronized void tick() {

    String fingerprint = fingerprint();
    for (var note :
        db.app()
            .queryForList(
                "SELECT n.*,b.name AS notebook,s.name AS section FROM notes n JOIN note_sections s ON s.id=n.section_id JOIN notebooks b ON b.id=s.notebook_id WHERE n.knowledge=1 AND n.deleted=0 AND (n.revision!=n.indexed_revision OR n.index_fingerprint!=?) AND n.index_error='' LIMIT 2",
                fingerprint)) {
      String id = Database.text(note, "id");
      long revision = Database.number(note, "revision");
      try {
        var parents = StructuredChunks.split(Database.text(note, "body"));
        var prepared = new ArrayList<IndexedChild>();
        var parentIds = new ArrayList<String>();
        for (var parent : parents) {
          String parentId = UUID.randomUUID().toString();
          parentIds.add(parentId);
          String context =
              StructuredChunks.context(
                  Database.text(note, "notebook"),
                  Database.text(note, "section"),
                  Database.text(note, "title"),
                  parent.headingPath());
          for (var child : parent.children()) {
            String enriched = context + "\n\n" + child.content();
            prepared.add(
                new IndexedChild(
                    parentId,
                    child.kind(),
                    child.content(),
                    enriched,
                    embedding.configured()
                        ? TextIndex.encode(embedding.embed("note-" + id, enriched))
                        : null));
          }
        }
        db.transaction(
            sql -> {
              if (sql.queryForObject(
                      "SELECT COUNT(*) FROM notes WHERE id=? AND revision=? AND knowledge=1 AND deleted=0",
                      Integer.class,
                      id,
                      revision)
                  != 1) return null;
              sql.update(
                  "DELETE FROM notes_fts WHERE chunk_id IN (SELECT id FROM note_chunks WHERE note_id=?)",
                  id);
              sql.update("DELETE FROM note_chunks WHERE note_id=?", id);
              sql.update("DELETE FROM note_chunk_parents WHERE note_id=?", id);
              for (int i = 0; i < parents.size(); i++)
                sql.update(
                    "INSERT INTO note_chunk_parents VALUES(?,?,?,?,?)",
                    parentIds.get(i),
                    id,
                    revision,
                    parents.get(i).headingPath(),
                    parents.get(i).content());
              for (int i = 0; i < prepared.size(); i++) {
                var child = prepared.get(i);
                String chunkId = UUID.randomUUID().toString(),
                    terms = TextIndex.terms(child.context());
                sql.update(
                    "INSERT INTO note_chunks(id,note_id,revision,ordinal,content,terms,embedding,fingerprint,parent_id,kind,context_text) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    chunkId,
                    id,
                    revision,
                    i,
                    child.content(),
                    terms,
                    child.vector(),
                    embedding.configured() ? embedding.fingerprint() : "",
                    child.parent(),
                    child.kind(),
                    child.context());
                sql.update("INSERT INTO notes_fts(chunk_id,terms) VALUES(?,?)", chunkId, terms);
              }
              sql.update(
                  "UPDATE notes SET indexed_revision=?,index_fingerprint=?,index_error='' WHERE id=?",
                  revision,
                  fingerprint,
                  id);
              return null;
            });
      } catch (RuntimeException ex) {
        db.app()
            .update(
                "UPDATE notes SET index_error=? WHERE id=? AND revision=?",
                ex instanceof ApiException api ? api.code() : "INDEX_FAILED",
                id,
                revision);
      }
    }
  }

  private record IndexedChild(
      String parent, String kind, String content, String context, byte[] vector) {}

  private String fingerprint() {
    return StructuredChunks.VERSION
        + "|"
        + (embedding.configured() ? embedding.fingerprint() : "lexical");
  }

  public boolean available(String chunk) {
    return db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM note_chunks c JOIN notes n ON n.id=c.note_id WHERE c.id=? AND n.knowledge=1 AND n.deleted=0",
                Integer.class,
                chunk)
        > 0;
  }

  public HybridRetriever.Retrieved search(String request, String question) {
    RequestDeadline.check();
    var rows =
        db.app()
            .queryForList(
                "SELECT c.*,n.title,p.heading_path,p.content AS parent_content,b.name AS notebook,s.name AS section FROM note_chunks c JOIN notes n ON n.id=c.note_id LEFT JOIN note_chunk_parents p ON p.id=c.parent_id JOIN note_sections s ON s.id=n.section_id JOIN notebooks b ON b.id=s.notebook_id WHERE n.knowledge=1 AND n.deleted=0");
    var ranks = new HashMap<String, Double>();
    String query = TextIndex.query(question);
    if (!query.isBlank()) {
      var matches =
          db.app()
              .queryForList(
                  "SELECT f.chunk_id FROM notes_fts f JOIN note_chunks c ON c.id=f.chunk_id JOIN notes n ON n.id=c.note_id WHERE notes_fts MATCH ? AND n.knowledge=1 AND n.deleted=0 ORDER BY bm25(notes_fts) LIMIT 40",
                  query);
      for (int i = 0; i < matches.size(); i++)
        ranks.merge(Database.text(matches.get(i), "chunk_id"), 1.0 / (61 + i), Double::sum);
    }
    boolean semantic = false;
    String fallback = "";
    if (embedding.configured())
      try {
        float[] vector = embedding.embed(request, question);
        var scored =
            rows.stream()
                .filter(
                    r ->
                        r.get("embedding") != null
                            && r.get("parent_id") != null
                            && embedding.fingerprint().equals(r.get("fingerprint")))
                .map(
                    r ->
                        Map.entry(
                            Database.text(r, "id"),
                            TextIndex.similarity(
                                vector, TextIndex.decode((byte[]) r.get("embedding")))))
                .filter(e -> e.getValue() >= 0.30)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(40)
                .toList();
        for (int i = 0; i < scored.size(); i++)
          ranks.merge(scored.get(i).getKey(), 1.0 / (61 + i), Double::sum);
        semantic = true;
      } catch (ApiException ex) {
        if (Set.of("DAILY_BUDGET_REACHED", "REQUEST_TIMEOUT").contains(ex.code())) throw ex;
        fallback = ex.code();
      }
    var byId = new HashMap<String, Map<String, Object>>();
    rows.forEach(r -> byId.put(Database.text(r, "id"), r));
    var selected =
        ranks.entrySet().stream()
            .sorted(
                Map.Entry.<String, Double>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .filter(byId::containsKey)
            .limit(30)
            .toList();
    var relevance = new HashMap<String, Double>();
    if (!selected.isEmpty()) {
      if (reranker == null || !reranker.configured())
        fallback = reason(fallback, "RERANK_NOT_CONFIGURED");
      else
        try {
          var documents =
              selected.stream()
                  .map(
                      id -> {
                        var row = byId.get(id);
                        String context = Database.text(row, "context_text");
                        return context.isBlank() ? Database.text(row, "content") : context;
                      })
                  .toList();
          var ordered = reranker.rank(request, question, documents);
          var validated = new ArrayList<String>();
          // 防御接口替身或其他实现返回越界/重复下标；校验成功之前不改变融合顺序。
          if (ordered.size() != selected.size())
            throw new IllegalArgumentException("Missing candidates");
          for (var score : ordered) {
            if (score.index() < 0
                || score.index() >= selected.size()
                || !Double.isFinite(score.relevance()))
              throw new IllegalArgumentException("Invalid ranking");
            String id = selected.get(score.index());
            if (validated.contains(id)) throw new IllegalArgumentException("Duplicate ranking");
            validated.add(id);
            relevance.put(id, score.relevance());
          }
          selected = List.copyOf(validated);
        } catch (ApiException ex) {
          if (Set.of("DAILY_BUDGET_REACHED", "REQUEST_TIMEOUT").contains(ex.code())) throw ex;
          relevance.clear();
          fallback = reason(fallback, ex.code());
        } catch (IllegalArgumentException ex) {
          relevance.clear();
          fallback = reason(fallback, "RERANK_INVALID_RESPONSE");
        }
    }
    var passages = new ArrayList<HybridRetriever.Passage>();
    var covered = new HashSet<String>();
    for (String id : selected) {
      if (passages.size() >= 8) break;
      if (!available(id)) continue;
      var r = byId.get(id);
      long ordinal = Database.number(r, "ordinal");
      String note = Database.text(r, "note_id");
      String parentId = Database.text(r, "parent_id");
      String key = parentId.isBlank() ? id : parentId;
      if (!covered.add(key)) continue;
      // 父块在索引阶段已限定为同一小节、最多 1800 字符，无需跨小节拼接邻居。
      String content = Database.text(r, "parent_content");
      if (content.isBlank()) content = Database.text(r, "content");
      passages.add(
          new HybridRetriever.Passage(
              id,
              note,
              Database.text(r, "title"),
              r.get("notebook")
                  + " / "
                  + r.get("section")
                  + (Database.text(r, "heading_path").isBlank()
                      ? ""
                      : " / " + r.get("heading_path"))
                  + " · 片段 "
                  + (ordinal + 1),
              "KNOWLEDGE",
              "",
              content,
              relevance.getOrDefault(id, ranks.get(id))));
    }
    RequestDeadline.check();
    return new HybridRetriever.Retrieved("notes", passages, semantic, fallback);
  }

  private static String reason(String previous, String next) {
    return previous.isBlank() ? next : previous + ";" + next;
  }
}
