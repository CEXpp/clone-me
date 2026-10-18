package cyou.jujube.retrieval;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.common.ApiException;
import cyou.jujube.common.Diagnostics;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.Database;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Service;

@Service
public class HybridRetriever implements DocumentRetriever {
  public record Passage(
      String id,
      String sourceId,
      String sourceName,
      String label,
      String kind,
      String mastery,
      String content,
      double score) {}

  public record Retrieved(
      String version, List<Passage> passages, boolean semanticUsed, String fallbackReason) {}

  private final Database db;
  private final EmbeddingProvider embedding;
  private final Diagnostics diagnostics;

  private record CachedQuery(float[] vector, long expiresAt) {}

  private final Map<String, CachedQuery> queryCache =
      Collections.synchronizedMap(
          new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedQuery> eldest) {
              return size() > 128;
            }
          });

  public HybridRetriever(Database db, EmbeddingProvider embedding, Diagnostics diagnostics) {
    this.db = db;
    this.embedding = embedding;
    this.diagnostics = diagnostics;
  }

  @Override
  public List<Document> retrieve(Query query) {
    return search(
            MDC.get("requestId") == null ? UUID.randomUUID().toString() : MDC.get("requestId"),
            db.setting("knowledge.active", ""),
            query.text())
        .passages()
        .stream()
        .map(
            p ->
                new Document(
                    p.content(),
                    Map.<String, Object>of(
                        "recordId", p.id(), "sourceId", p.sourceId(), "kind", p.kind())))
        .toList();
  }

  public Retrieved search(String requestId, String version, String question) {
    long started = System.nanoTime();
    if (version.isBlank()) return new Retrieved(version, List.of(), false, "NO_RELEASE");
    var release =
        Database.required(
            db.app(), "SELECT * FROM releases WHERE id=? AND status='READY'", version);
    var ranks = new LinkedHashMap<Long, Double>();
    var rowsById = new HashMap<Long, Map<String, Object>>();
    String query = TextIndex.query(question);
    if (!query.isBlank()) {
      var matches =
          db.app()
              .queryForList(
                  "SELECT r.* FROM records_fts JOIN release_records r ON r.row_id=records_fts.rowid JOIN candidates c ON c.id=r.record_id WHERE records_fts MATCH ? AND r.release_id=? AND r.kind!='STYLE' AND c.withdrawn=0 ORDER BY bm25(records_fts) LIMIT 24",
                  query,
                  version);
      for (int i = 0; i < matches.size(); i++) {
        var row = matches.get(i);
        long id = Database.number(row, "row_id");
        rowsById.put(id, row);
        ranks.merge(id, 1.0 / (60 + i + 1), Double::sum);
      }
    }
    boolean semantic = false;
    String fallback = "";
    if (Database.number(release, "keyword_only") == 0) {
      try {
        if (!embedding.fingerprint().equals(release.get("fingerprint")))
          throw ApiException.unavailable("EMBEDDING_FINGERPRINT_CHANGED", "索引模型与查询模型不匹配");
        String cacheKey = Crypto.hash(embedding.fingerprint() + "\n" + question);
        var cached = queryCache.get(cacheKey);
        float[] vector;
        if (cached == null || cached.expiresAt() < System.currentTimeMillis()) {
          vector = embedding.embed(requestId, question);
          queryCache.put(cacheKey, new CachedQuery(vector, System.currentTimeMillis() + 300_000));
        } else vector = cached.vector();
        var all =
            db.app()
                .queryForList(
                    "SELECT r.* FROM release_records r JOIN candidates c ON c.id=r.record_id WHERE r.release_id=? AND r.kind!='STYLE' AND c.withdrawn=0 AND r.embedding IS NOT NULL",
                    version);
        var scored = new ArrayList<Map.Entry<Map<String, Object>, Double>>();
        for (var row : all) {
          double similarity =
              TextIndex.similarity(vector, TextIndex.decode((byte[]) row.get("embedding")));
          if (similarity >= 0.30) scored.add(Map.entry(row, similarity));
        }
        scored.sort(Map.Entry.<Map<String, Object>, Double>comparingByValue().reversed());
        for (int i = 0; i < Math.min(24, scored.size()); i++) {
          var row = scored.get(i).getKey();
          long id = Database.number(row, "row_id");
          rowsById.put(id, row);
          ranks.merge(id, 1.0 / (60 + i + 1), Double::sum);
        }
        semantic = true;
      } catch (ApiException | IllegalArgumentException ex) {
        fallback = ex instanceof ApiException api ? api.code() : "INVALID_EMBEDDING";
        diagnostics.event(
            requestId,
            "RETRIEVE",
            "EMBEDDING_FALLBACK",
            Map.of(
                "reason", fallback, "fallbackTarget", "KEYWORD", "evidenceChecksRetained", true));
      }
    }
    var passages =
        ranks.entrySet().stream()
            .sorted(
                Map.Entry.<Long, Double>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry::getKey))
            .limit(8)
            .map(
                entry -> {
                  var row = rowsById.get(entry.getKey());
                  return new Passage(
                      Database.text(row, "record_id"),
                      Database.text(row, "source_id"),
                      Database.text(row, "source_name"),
                      Database.text(row, "source_label"),
                      Database.text(row, "kind"),
                      Database.text(row, "mastery"),
                      Database.text(row, "content"),
                      entry.getValue());
                })
            .toList();
    diagnostics.event(
        requestId,
        "RETRIEVE",
        "RETRIEVAL_COMPLETED",
        Map.of(
            "knowledgeVersion",
            version,
            "candidateCount",
            passages.size(),
            "sourceIds",
            passages.stream().map(Passage::id).toList(),
            "semanticUsed",
            semantic,
            "durationMs",
            (System.nanoTime() - started) / 1_000_000));
    return new Retrieved(version, passages, semantic, fallback);
  }
}
