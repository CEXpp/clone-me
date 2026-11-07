package cyou.jujube.ingestion;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.ai.GenerationProvider;
import cyou.jujube.common.ApiException;
import cyou.jujube.common.Diagnostics;
import cyou.jujube.common.FlowLog;
import cyou.jujube.common.Json;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.retrieval.TextIndex;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.Database;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通过数据库任务表执行解析、草稿提取和索引发布，无需另行部署消息队列。 每次只领取一个任务，提取和向量构建分批推进；进度持久化，重启后可继续未完成的工作。
 * 缺配置或预算不足会暂停，需管理员修正后主动重试，避免后台反复产生付费请求。
 */
@Component
public class ImportWorker {
  private final Database db;
  private final CloneProperties props;
  private final DocumentParser parser;
  private final SourceService sources;
  private final GenerationProvider generation;
  private final EmbeddingProvider embedding;
  private final Diagnostics diagnostics;
  private final Clock clock;
  private final AtomicBoolean working = new AtomicBoolean();
  private volatile boolean recovered;

  public ImportWorker(
      Database db,
      CloneProperties props,
      DocumentParser parser,
      SourceService sources,
      GenerationProvider generation,
      EmbeddingProvider embedding,
      Diagnostics diagnostics,
      Clock clock) {
    this.db = db;
    this.props = props;
    this.parser = parser;
    this.sources = sources;
    this.generation = generation;
    this.embedding = embedding;
    this.diagnostics = diagnostics;
    this.clock = clock;
  }

  /** 进程重启后把未完成的运行中任务重新排队；已提交的游标和向量保留，避免从头重建。 */
  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    // 重启时无法知道旧模型请求是否收费：费用保留为 UNCERTAIN，付费提取任务暂停。
    // 本地解析和免费向量发布可重新排队；不能把所有 RUNNING 都无条件自动重试。
    db.transaction(
        sql -> {
          sql.update(
              "UPDATE ai_calls SET status='UNCERTAIN',error_code='PROCESS_RESTARTED' WHERE status='RESERVED'");
          sql.update(
              "UPDATE jobs SET status=CASE WHEN type='EXTRACT' THEN 'PAUSED' ELSE 'QUEUED' END,error_code='PROCESS_RESTARTED',updated_at=? WHERE status='RUNNING'",
              clock.millis());
          sql.update(
              "UPDATE jobs SET status='PAUSED',error_code='RECONCILIATION_REQUIRED',updated_at=? WHERE type='EXTRACT' AND status='QUEUED' AND EXISTS(SELECT 1 FROM ai_calls a WHERE a.request_id=jobs.id AND a.reserved>0 AND a.status='UNCERTAIN')",
              clock.millis());
          return null;
        });
    db.sessions().update("UPDATE chat_requests SET status='UNCERTAIN' WHERE status='RUNNING'");
    db.sessions().update("UPDATE visitor_sessions SET lock_id=NULL,lock_until=0");
    recovered = true;
    FlowLog.step("JOB", "启动恢复完成：本地任务可续跑，不确定的付费任务等待人工核对", Map.of());
  }

  @org.springframework.beans.factory.annotation.Value("${clone.jobs-enabled:true}")
  private boolean enabled;

  @Scheduled(fixedDelay = 1000)
  public void scheduledTick() {
    // fixedDelay 从本次方法结束后再计时；每次 tick 只推进一个任务或一个小批次。
    if (enabled && recovered) tick();
  }

  /** 原子标志避免调度重入，数据库状态条件更新确保只领取仍在排队的任务，统一记录失败原因。 */
  public void tick() {
    // compareAndSet 一次完成“检查空闲 + 标记忙碌”，避免两个线程同时看见空闲。
    if (!working.compareAndSet(false, true)) return;
    try {
      var jobs =
          db.app()
              .queryForList(
                  "SELECT * FROM jobs WHERE status='QUEUED' ORDER BY created_at,id LIMIT 1");
      if (jobs.isEmpty()) return;
      var job = jobs.getFirst();
      String id = Database.text(job, "id");
      if (db.app()
              .update(
                  "UPDATE jobs SET status='RUNNING',updated_at=? WHERE id=? AND status='QUEUED'",
                  clock.millis(),
                  id)
          != 1) return;
      MDC.put("requestId", id);
      // 后台没有 HTTP 请求，这里用任务编号串起解析、模型调用和发布的所有日志。
      FlowLog.step(
          "JOB",
          "已领取排队任务，开始检查存储并按任务类型分派",
          Map.of("jobType", Database.text(job, "type"), "cursor", Database.number(job, "cursor")));
      try {
        sources.checkStorage();
        switch (Database.text(job, "type")) {
          case "IMPORT" -> parse(job);
          case "EXTRACT" -> extract(job);
          case "PUBLISH" -> publish(job);
          default -> throw new IllegalArgumentException("Unknown job type");
        }
      } catch (Exception ex) {
        String code = ex instanceof ApiException api ? api.code() : ex.getClass().getSimpleName();
        boolean paused =
            Set.of(
                        "DAILY_BUDGET_REACHED",
                        "PRICING_REQUIRED",
                        "PAID_CALLS_PAUSED",
                        "EMBEDDING_NOT_CONFIGURED",
                        "GENERATION_NOT_CONFIGURED")
                    .contains(code)
                || Set.of("DISK_SPACE_LOW", "STORAGE_UNAVAILABLE", "DATABASE_CAPACITY_REACHED")
                    .contains(code);
        boolean unsettled =
            db.app()
                    .queryForObject(
                        "SELECT COUNT(*) FROM ai_calls WHERE request_id=? AND reserved>0 AND status IN ('RESERVED','UNCERTAIN')",
                        Long.class,
                        id)
                > 0;
        state(
            id,
            paused || unsettled ? "PAUSED" : "FAILED",
            "FAILED",
            unsettled ? "RECONCILIATION_REQUIRED" : code);
        diagnostics.event(id, "IMPORT", "JOB_FAILED", Map.of("errorCode", code, "paused", paused));
      } finally {
        FlowLog.step("JOB", "本轮任务处理结束，即将释放执行标志并等待下次调度", Map.of());
        MDC.remove("requestId");
      }
    } finally {
      working.set(false);
    }
  }

  /** 在同一事务保存片段与未确认草稿并完成解析任务；只有本人片段生成候选，模型配置后才入队提取。 */
  private void parse(Map<String, Object> job) throws Exception {
    String jobId = Database.text(job, "id"), sourceId = Database.text(job, "subject_id");
    var source = Database.required(db.app(), "SELECT * FROM sources WHERE id=?", sourceId);
    if ("AWAITING_REVIEW".equals(source.get("status"))) {
      state(jobId, "COMPLETED", "PARSED", null);
      return;
    }
    var mapping = Json.read(Database.text(source, "mapping_json"));
    String mode = mapping.path("processingMode").asText("LOCAL");
    var file = props.root().resolve("sources").resolve(Database.text(source, "file_name"));
    FlowLog.step(
        "IMPORT",
        "开始解析资料：根据处理模式选择普通切片或整理稿导入",
        Map.of("mode", mode, "format", Database.text(source, "format")));
    boolean structured = false;
    if ("PREPARED".equals(mode) && "json".equals(source.get("format"))) {
      var json = Json.read(java.nio.file.Files.readString(file));
      structured = json.has("schema") || json.has("records");
    }
    var prepared = structured ? PreparedKnowledge.read(file) : List.<PreparedKnowledge.Record>of();
    var segments =
        structured
            ? prepared.stream().map(PreparedKnowledge.Record::segment).toList()
            : parser.parse(
                file,
                Database.text(source, "format"),
                DocumentParser.Mapping.from(Database.text(source, "mapping_json")));
    FlowLog.step(
        "IMPORT",
        "资料解析完成，准备在事务内保存原文片段和待确认草稿",
        Map.of("segmentCount", segments.size(), "preparedCount", prepared.size()));
    boolean knowledge = "KNOWLEDGE".equals(mapping.path("documentType").asText());
    boolean automaticExtraction =
        "AI".equals(mode) || ("LOCAL".equals(mode) && !knowledge && generation.configured());
    db.transaction(
        sql -> {
          for (var segment : segments) {
            sql.update(
                "INSERT INTO segments(id,source_id,ordinal,label,speaker,content) VALUES(?,?,?,?,?,?)",
                segment.id(),
                sourceId,
                segment.ordinal(),
                segment.label(),
                segment.speaker(),
                segment.content());
          }
          if (!prepared.isEmpty()) {
            for (var record : prepared)
              sql.update(
                  "INSERT INTO candidates(id,source_id,segment_id,kind,content,mastery,manually_edited,updated_at) VALUES(?,?,?,'KNOWLEDGE',?,'UNCONFIRMED',1,?)",
                  UUID.randomUUID().toString(),
                  sourceId,
                  record.segment().id(),
                  record.content(),
                  clock.millis());
          } else sources.createDrafts(sourceId, segments, knowledge);
          sql.update(
              "UPDATE sources SET status='AWAITING_REVIEW',error_code=NULL WHERE id=?", sourceId);
          sql.update(
              "UPDATE jobs SET status='COMPLETED',stage='PARSED',updated_at=? WHERE id=?",
              clock.millis(),
              jobId);
          if (automaticExtraction) sources.enqueue("EXTRACT", sourceId);
          return null;
        });
    diagnostics.event(
        jobId,
        "IMPORT",
        "DOCUMENT_PARSED",
        Map.of(
            "segmentCount",
            segments.size(),
            "sourceId",
            sourceId,
            "automaticExtraction",
            automaticExtraction,
            "approvalRequired",
            true));
  }

  /** 对结构草稿按稳定顺序分批精炼；模型只能摘录输入中的内容，人工编辑通过修订号保护。 */
  private void extract(Map<String, Object> job) {
    String jobId = Database.text(job, "id"), sourceId = Database.text(job, "subject_id");
    sources.requireAiMode(sourceId);
    int cursor = (int) Database.number(job, "cursor");
    var scanned =
        db.app()
            .queryForList(
                "SELECT c.*,s.speaker FROM candidates c JOIN segments s ON s.id=c.segment_id WHERE c.source_id=? ORDER BY s.ordinal,c.rowid LIMIT 2 OFFSET ?",
                sourceId,
                cursor);
    if (scanned.isEmpty()) {
      state(jobId, "COMPLETED", "AWAITING_REVIEW", null);
      return;
    }
    FlowLog.step(
        "EXTRACTION",
        "读取当前批次草稿，跳过人工修改、已批准及已撤回项",
        Map.of("cursor", cursor, "scannedCount", scanned.size()));
    var pending =
        scanned.stream()
            .filter(
                c ->
                    Database.number(c, "manually_edited") == 0
                        && Database.number(c, "approved") == 0
                        && Database.number(c, "withdrawn") == 0
                        && "SELF".equals(c.get("speaker"))
                        && "UNCONFIRMED".equals(c.get("mastery"))
                        && Set.of("REFERENCE", "KNOWLEDGE").contains(Database.text(c, "kind"))
                        && !KnowledgeDrafts.contents(Database.text(c, "content")))
            .toList();
    var input =
        pending.stream()
            .map(c -> Map.of("id", c.get("id"), "kind", c.get("kind"), "content", c.get("content")))
            .toList();
    String system = ExtractionPrompts.INTERNAL;
    FlowLog.step("EXTRACTION", "筛选完成：没有待处理项时不调用模型", Map.of("pendingCount", pending.size()));
    var output =
        pending.isEmpty()
            ? Json.read("[]")
            : Json.model(
                    generation.generate(
                        jobId, "EXTRACTION", system, Json.write(Map.of("drafts", input))))
                .path("records");
    if (!output.isArray() || output.size() > pending.size())
      throw ApiException.unavailable("INVALID_EXTRACTION", "提取结果格式无效");
    Map<String, Map<String, Object>> allowed = new HashMap<>();
    pending.forEach(c -> allowed.put(Database.text(c, "id"), c));
    db.transaction(
        sql -> {
          Set<String> seen = new HashSet<>();
          for (var record : output) {
            String id = record.path("candidateId").asText(),
                kind = record.path("kind").asText(),
                content = record.path("content").asText();
            var original = allowed.get(id);
            if (original == null
                || !seen.add(id)
                || !Set.of("KNOWLEDGE", "EXPERIENCE", "VIEWPOINT", "STYLE", "REFERENCE")
                    .contains(kind)
                || ("KNOWLEDGE".equals(original.get("kind")) && !"KNOWLEDGE".equals(kind))
                || content.isBlank()
                || content.length() > 1200
                || !Database.text(original, "content")
                    .replaceAll("\\s+", "")
                    .contains(content.replaceAll("\\s+", "")))
              throw ApiException.unavailable("INVALID_EXTRACTION", "模型摘录缺少原文依据或格式无效");
            sql.update(
                "UPDATE candidates SET kind=?,content=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND approved=0 AND withdrawn=0 AND manually_edited=0 AND mastery='UNCONFIRMED'",
                kind,
                content,
                clock.millis(),
                id,
                original.get("revision"));
          }
          sql.update(
              "UPDATE jobs SET cursor=?,status='QUEUED',stage='EXTRACTING',updated_at=? WHERE id=?",
              cursor + scanned.size(),
              clock.millis(),
              jobId);
          return null;
        });
    diagnostics.event(
        jobId,
        "EXTRACTION",
        "DRAFTS_UPDATED",
        Map.of("processedDrafts", cursor + scanned.size(), "approvalRequired", true));
  }

  /** 每批最多补四个向量并复用指纹缓存；所有向量齐备后一次事务写检索索引并切换版本指针。 */
  private void publish(Map<String, Object> job) {
    String jobId = Database.text(job, "id"), releaseId = Database.text(job, "subject_id");
    var release = Database.required(db.app(), "SELECT * FROM releases WHERE id=?", releaseId);
    if ("READY".equals(release.get("status"))) {
      state(jobId, "COMPLETED", "PUBLISHED", null);
      return;
    }
    if (Database.number(release, "keyword_only") == 0) {
      if (!embedding.fingerprint().equals(release.get("fingerprint")))
        throw ApiException.badRequest("EMBEDDING_CHANGED", "Embedding 已改变，请重新创建发布版本");
      var missing =
          db.app()
              .queryForList(
                  "SELECT row_id,content FROM release_records WHERE release_id=? AND embedding IS NULL LIMIT 4",
                  releaseId);
      FlowLog.step("PUBLISH", "开始补齐当前批次向量；已有缓存可直接复用", Map.of("batchCount", missing.size()));
      for (var record : missing) {
        String text = Database.text(record, "content"),
            key = Crypto.hash(embedding.fingerprint() + "\n" + text);
        var cache = db.app().queryForList("SELECT vector FROM embedding_cache WHERE key=?", key);
        byte[] bytes;
        FlowLog.step("PUBLISH", "检查当前记录的向量缓存", Map.of("cacheHit", !cache.isEmpty()));
        if (cache.isEmpty()) {
          float[] vector = embedding.embed(jobId, text);
          bytes = TextIndex.encode(vector);
          db.app()
              .update(
                  "INSERT OR IGNORE INTO embedding_cache(key,fingerprint,dimensions,vector) VALUES(?,?,?,?)",
                  key,
                  embedding.fingerprint(),
                  vector.length,
                  bytes);
        } else bytes = (byte[]) cache.getFirst().get("vector");
        db.app()
            .update(
                "UPDATE release_records SET embedding=? WHERE row_id=? AND embedding IS NULL",
                bytes,
                record.get("row_id"));
      }
      long remaining =
          db.app()
              .queryForObject(
                  "SELECT COUNT(*) FROM release_records WHERE release_id=? AND embedding IS NULL",
                  Long.class,
                  releaseId);
      FlowLog.step("PUBLISH", "当前批次向量写入完成", Map.of("remainingCount", remaining));
      if (remaining > 0) {
        // 此轮只补最多四条向量；重新排队把控制权还给调度器，下轮接着处理缺失项。
        state(jobId, "QUEUED", "INDEXING", null);
        return;
      }
    }
    FlowLog.step("PUBLISH", "向量准备完成或采用关键词模式，开始原子切换发布版本", Map.of());
    // 发布最后一步必须原子提交：索引、READY 状态与当前版本指针一起生效。
    db.transaction(
        sql -> {
          sql.update(
              "INSERT INTO records_fts(rowid,terms) SELECT row_id,terms FROM release_records WHERE release_id=?",
              releaseId);
          sql.update("UPDATE releases SET status='READY' WHERE id=?", releaseId);
          sql.update(
              "INSERT INTO settings(key,value) VALUES('knowledge.active',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
              releaseId);
          sql.update(
              "UPDATE jobs SET status='COMPLETED',stage='PUBLISHED',updated_at=? WHERE id=?",
              clock.millis(),
              jobId);
          return null;
        });
    diagnostics.event(
        jobId,
        "PUBLISH",
        "RELEASE_ACTIVATED",
        Map.of(
            "knowledgeVersion",
            releaseId,
            "keywordOnly",
            Database.number(release, "keyword_only") == 1));
  }

  private void state(String id, String status, String stage, String error) {
    // 先写库再输出，日志中的状态才代表已经持久化的状态，而非仅仅打算修改。
    db.app()
        .update(
            "UPDATE jobs SET status=?,stage=?,error_code=?,updated_at=? WHERE id=?",
            status,
            stage,
            error,
            clock.millis(),
            id);
    FlowLog.step(
        "JOB",
        "任务状态已保存",
        Map.of(
            "jobId",
            id,
            "status",
            status,
            "stage",
            stage,
            "errorCode",
            error == null ? "" : error));
  }
}
