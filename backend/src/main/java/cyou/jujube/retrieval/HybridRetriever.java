package cyou.jujube.retrieval;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.common.ApiException;
import cyou.jujube.common.Diagnostics;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.Database;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 实现 Spring AI 检索接口，把中文关键词结果和独立向量召回按排名融合。 两条召回路径各取前 24 项，使用倒数排名融合选出最多 8 项；不直接相加不可比的原始分数。
 * 向量故障或指纹不符时保留关键词路径，并记录原因；所有路径均排除表达样例和已撤回记录。
 */
@Service
public class HybridRetriever {
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

  /** 固定版本检索，分别排名后融合；向量分支失败只降级召回方式，不放宽事实核验。 */
  public Retrieved search(String requestId, String version, String question) {
    long started = System.nanoTime();
    if (version.isBlank()) return new Retrieved(version, List.of(), false, "NO_RELEASE");
    var release =
        Database.required(
            db.app(), "SELECT * FROM releases WHERE id=? AND status='READY'", version);
    // 融合的是排名：不同检索器的原始分数不在同一尺度，不能直接相加。
    var ranks = new LinkedHashMap<Long, Double>();
    var rowsById = new HashMap<Long, Map<String, Object>>();
    String query = TextIndex.query(question);
    if (!query.isBlank()) {
      var matches =
          db.app()
              .queryForList(
                  "SELECT r.row_id,r.record_id,r.source_id,r.source_name,r.source_label,r.kind,r.mastery,r.content FROM records_fts JOIN release_records r ON r.row_id=records_fts.rowid JOIN candidates c ON c.id=r.record_id WHERE records_fts MATCH ? AND r.release_id=? AND r.kind!='STYLE' AND c.withdrawn=0 ORDER BY bm25(records_fts),r.row_id LIMIT 24",
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
        // 查询缓存最多一百二十八项、五分钟有效，键包含模型指纹，避免跨配置复用。
        String cacheKey = Crypto.hash(embedding.fingerprint() + "\n" + question);
        var cached = queryCache.get(cacheKey);
        float[] vector;
        if (cached == null || cached.expiresAt() < System.currentTimeMillis()) {
          vector = embedding.embed(requestId, question);
          queryCache.put(cacheKey, new CachedQuery(vector, System.currentTimeMillis() + 300_000));
        } else vector = cached.vector();
        record Scored(long id, double score) {}
        var best =
            new PriorityQueue<Scored>(
                Comparator.comparingDouble(Scored::score)
                    .thenComparing(Comparator.comparingLong(Scored::id).reversed()));
        float[] queryVector = vector;
        db.app()
            .query(
                "SELECT r.row_id,r.embedding FROM release_records r JOIN candidates c ON c.id=r.record_id WHERE r.release_id=? AND r.kind!='STYLE' AND c.withdrawn=0 AND r.embedding IS NOT NULL",
                (org.springframework.jdbc.core.RowCallbackHandler)
                    row -> {
                      cyou.jujube.common.RequestDeadline.check();
                      double similarity =
                          TextIndex.similarity(
                              queryVector, TextIndex.decode(row.getBytes("embedding")));
                      if (similarity >= 0.30) {
                        best.add(new Scored(row.getLong("row_id"), similarity));
                        if (best.size() > 24) best.poll();
                      }
                    },
                version);
        var scored =
            best.stream()
                .sorted(
                    Comparator.comparingDouble(Scored::score)
                        .reversed()
                        .thenComparingLong(Scored::id))
                .toList();
        if (!scored.isEmpty()) {
          String placeholders = String.join(",", Collections.nCopies(scored.size(), "?"));
          var selectedRows =
              db.app()
                  .queryForList(
                      "SELECT row_id,record_id,source_id,source_name,source_label,kind,mastery,content FROM release_records WHERE row_id IN ("
                          + placeholders
                          + ")",
                      scored.stream().map(Scored::id).toArray());
          for (var row : selectedRows) rowsById.put(Database.number(row, "row_id"), row);
          for (int i = 0; i < scored.size(); i++)
            ranks.merge(scored.get(i).id(), 1.0 / (60 + i + 1), Double::sum);
        }
        semantic = true;
      } catch (ApiException | IllegalArgumentException ex) {
        if (ex instanceof ApiException api && "REQUEST_TIMEOUT".equals(api.code())) throw api;
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
