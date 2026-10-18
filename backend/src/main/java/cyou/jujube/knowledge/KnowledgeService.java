package cyou.jujube.knowledge;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.common.ApiException;
import cyou.jujube.ingestion.SourceService;
import cyou.jujube.retrieval.TextIndex;
import cyou.jujube.storage.Database;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {
  private final Database db;
  private final SourceService sources;
  private final EmbeddingProvider embedding;
  private final Clock clock;

  public KnowledgeService(
      Database db, SourceService sources, EmbeddingProvider embedding, Clock clock) {
    this.db = db;
    this.sources = sources;
    this.embedding = embedding;
    this.clock = clock;
  }

  public Map<String, Object> release(String label, boolean keywordOnly) {
    if (label == null || label.isBlank() || label.length() > 100)
      throw ApiException.badRequest("INVALID_LABEL", "发布版本名称需为 1–100 个字符");
    if (!keywordOnly && !embedding.configured())
      throw ApiException.unavailable("EMBEDDING_NOT_CONFIGURED", "请先配置 Embedding，或明确选择仅关键词发布");
    String id = UUID.randomUUID().toString();
    String job =
        db.transaction(
            sql -> {
              var records =
                  sql.queryForList(
                      "SELECT c.*,s.name AS source_name,g.label AS source_label FROM candidates c JOIN sources s ON s.id=c.source_id JOIN segments g ON g.id=c.segment_id WHERE c.approved=1 AND c.withdrawn=0 AND c.kind!='REFERENCE' AND c.mastery!='UNCONFIRMED' AND g.speaker='SELF'");
              if (records.isEmpty())
                throw ApiException.badRequest("NO_APPROVED_RECORDS", "没有可发布的本人确认资料");
              sql.update(
                  "INSERT INTO releases(id,label,status,keyword_only,fingerprint,created_at) VALUES(?,?,'BUILDING',?,?,?)",
                  id,
                  label,
                  keywordOnly ? 1 : 0,
                  embedding.fingerprint(),
                  clock.millis());
              for (var record : records)
                sql.update(
                    "INSERT INTO release_records(release_id,record_id,source_id,source_name,source_label,kind,mastery,content,terms) VALUES(?,?,?,?,?,?,?,?,?)",
                    id,
                    record.get("id"),
                    record.get("source_id"),
                    record.get("source_name"),
                    record.get("source_label"),
                    record.get("kind"),
                    record.get("mastery"),
                    record.get("content"),
                    TextIndex.terms(Database.text(record, "content")));
              return sources.enqueue("PUBLISH", id);
            });
    return Map.of("releaseId", id, "jobId", job, "status", "BUILDING");
  }

  public void activate(String id) {
    db.transaction(
        sql -> {
          var release = Database.required(sql, "SELECT status FROM releases WHERE id=?", id);
          if (!"READY".equals(release.get("status")))
            throw ApiException.badRequest("RELEASE_NOT_READY", "版本尚未准备完成");
          sql.update(
              "INSERT INTO settings(key,value) VALUES('knowledge.active',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
              id);
          return null;
        });
  }

  public String active() {
    return db.setting("knowledge.active", "");
  }

  public List<Map<String, Object>> releases() {
    return db.app()
        .queryForList(
            "SELECT id,label,status,keyword_only,fingerprint,created_at FROM releases ORDER BY created_at DESC LIMIT 100");
  }
}
