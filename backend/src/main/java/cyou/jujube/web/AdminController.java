package cyou.jujube.web;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.ai.GenerationProvider;
import cyou.jujube.ai.SystemOneDecisions;
import cyou.jujube.common.ApiException;
import cyou.jujube.ingestion.SourceService;
import cyou.jujube.knowledge.KnowledgeService;
import cyou.jujube.quota.BudgetService;
import cyou.jujube.storage.Database;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 受管理员认证保护的业务入口，连接资料审阅、发布、公开展示、费用和运行诊断。 控制器只组织参数和响应；资料边界、预算与状态迁移由对应服务再次校验，不能只依赖前端限制。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final SourceService sources;
  private final KnowledgeService knowledge;
  private final Database db;
  private final BudgetService budget;
  private final SystemOneDecisions decisions;
  private final GenerationProvider generation;
  private final EmbeddingProvider embedding;
  private final cyou.jujube.knowledge.ProfileService profile;
  private final cyou.jujube.operations.AdminQueries queries;
  private final cyou.jujube.operations.RuntimeStatus runtime;

  public AdminController(
      SourceService sources,
      KnowledgeService knowledge,
      Database db,
      BudgetService budget,
      SystemOneDecisions decisions,
      GenerationProvider generation,
      EmbeddingProvider embedding,
      cyou.jujube.knowledge.ProfileService profile,
      cyou.jujube.operations.AdminQueries queries,
      cyou.jujube.operations.RuntimeStatus runtime) {
    this.sources = sources;
    this.knowledge = knowledge;
    this.db = db;
    this.budget = budget;
    this.decisions = decisions;
    this.generation = generation;
    this.embedding = embedding;
    this.profile = profile;
    this.queries = queries;
    this.runtime = runtime;
  }

  @GetMapping("/sources")
  Object sources() {
    return sources.sources();
  }

  @GetMapping("/sources/{id}")
  Object source(@PathVariable String id) {
    return sources.source(id);
  }

  @DeleteMapping("/sources/{id}")
  Object deleteSource(@PathVariable String id) throws IOException {
    sources.delete(id);
    return Map.of("deleted", true);
  }

  @DeleteMapping("/releases/{id}")
  Object deleteRelease(@PathVariable String id) {
    knowledge.delete(id);
    return Map.of("deleted", true);
  }

  @PostMapping("/jobs/{id}/cancel")
  Object cancelJob(@PathVariable String id) {
    sources.cancel(id);
    return Map.of("cancelled", true);
  }

  @PostMapping(value = "/sources", consumes = "multipart/form-data")
  Object upload(
      @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "{}") String mapping)
      throws IOException {
    return sources.upload(file, mapping);
  }

  @PostMapping("/sources/{id}/extract")
  Object extract(@PathVariable String id) {
    Database.required(
        db.app(), "SELECT id FROM sources WHERE id=? AND status='AWAITING_REVIEW'", id);
    if (!generation.configured())
      throw ApiException.unavailable("GENERATION_NOT_CONFIGURED", "尚未配置生成模型，可先手动确认解析结果");
    return Map.of("jobId", sources.enqueue("EXTRACT", id));
  }

  @PutMapping("/candidates/{id}")
  Object candidate(@PathVariable String id, @RequestBody SourceService.CandidateUpdate update) {
    if (update.revision() == null)
      throw ApiException.badRequest("REVISION_REQUIRED", "请获取当前修订号后保存");
    long revision = sources.updateCandidate(id, update);
    return Map.of("updated", true, "requiresNewRelease", true, "revision", revision);
  }

  public record CandidateEdit(
      String id, String kind, String content, String mastery, boolean approved, Long revision) {}

  @PostMapping("/candidates/confirm")
  Object confirm(@RequestBody List<CandidateEdit> edits) {
    if (edits.isEmpty() || edits.size() > 100)
      throw ApiException.badRequest("INVALID_BATCH", "每次确认 1–100 条记录");
    if (edits.stream().anyMatch(e -> e.revision() == null || e.id() == null)
        || edits.stream().map(CandidateEdit::id).distinct().count() != edits.size())
      throw ApiException.badRequest("INVALID_BATCH", "批量编辑需要修订号，且不能重复提交同一记录");
    db.transaction(
        sql -> {
          for (var edit : edits)
            sources.updateCandidate(
                edit.id(),
                new SourceService.CandidateUpdate(
                    edit.kind(), edit.content(), edit.mastery(), edit.approved(), edit.revision()));
          return null;
        });
    return Map.of("updated", edits.size(), "requiresNewRelease", true);
  }

  @DeleteMapping("/candidates/{id}")
  Object withdraw(@PathVariable String id) {
    sources.withdraw(id);
    return Map.of("withdrawn", true);
  }

  @GetMapping("/jobs")
  Object jobs() {
    return queries.jobs("", "").items();
  }

  @GetMapping("/jobs/page")
  Object jobsPage(
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String cursor) {
    return queries.jobs(status, cursor);
  }

  @GetMapping("/calls/page")
  Object callsPage(
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String cursor) {
    return queries.calls(status, cursor);
  }

  @GetMapping("/operations")
  Object operations() throws IOException {
    return runtime.overview();
  }

  @GetMapping("/jobs/{id}")
  Object job(@PathVariable String id) {
    return Database.required(db.app(), "SELECT * FROM jobs WHERE id=?", id);
  }

  @PostMapping("/jobs/{id}/retry")
  Object retry(@PathVariable String id) {
    sources.retry(id);
    return Map.of("queued", true);
  }

  public record Release(String label, boolean keywordOnly) {}

  @PostMapping("/releases")
  Object release(@RequestBody Release release) {
    return knowledge.release(release.label(), release.keywordOnly());
  }

  @GetMapping("/releases")
  Object releases() {
    return Map.of("activeVersion", knowledge.active(), "releases", knowledge.releases());
  }

  @PostMapping("/releases/{id}/activate")
  Object activate(@PathVariable String id) {
    knowledge.activate(id);
    return Map.of("activeVersion", id);
  }

  @GetMapping("/profile")
  Object profile() {
    return profile.get();
  }

  @PutMapping("/profile")
  Object profile(
      @jakarta.validation.Valid @RequestBody cyou.jujube.knowledge.ProfileService.Edit value) {
    return Map.of("published", true, "revision", profile.save(value));
  }

  @GetMapping("/usage")
  Object usage() {
    return budget.overview();
  }

  @GetMapping("/calls")
  Object calls() {
    return queries.calls("", "").items();
  }

  public record Reconcile(java.math.BigDecimal actualCny) {}

  @PostMapping("/calls/{id}/reconcile")
  Object reconcile(@PathVariable String id, @RequestBody Reconcile value) {
    budget.reconcile(id, value.actualCny());
    return Map.of("reconciled", true);
  }

  @GetMapping("/diagnostics")
  Object runs() {
    return db.app()
        .queryForList(
            "SELECT request_id,MIN(created_at) AS started_at,MAX(created_at) AS updated_at,COUNT(*) AS events FROM diagnostic_events GROUP BY request_id ORDER BY updated_at DESC LIMIT 100");
  }

  @GetMapping("/diagnostics/{id}")
  Object diagnostic(@PathVariable String id) {
    return db.app()
        .queryForList(
            "SELECT stage,event,detail_json,created_at FROM diagnostic_events WHERE request_id=? ORDER BY id",
            id);
  }

  @GetMapping("/providers")
  Object providers() {
    return Map.of(
        "paidCallsEnabled",
        Boolean.parseBoolean(db.setting("ai.paid-enabled", "true")),
        "generation",
        Map.of("provider", "deepseek", "configured", generation.configured()),
        "embedding",
        Map.of("configured", embedding.configured(), "fingerprint", embedding.fingerprint()),
        "decision",
        decisions.configuration());
  }

  public record ProviderSettings(String provider, Double threshold) {}

  public record PaidCalls(Boolean enabled) {}

  @PutMapping("/providers/paid")
  Object paid(@RequestBody PaidCalls value) {
    if (value.enabled() == null) throw ApiException.badRequest("INVALID_INPUT", "请指定 enabled");
    db.putSetting("ai.paid-enabled", value.enabled().toString());
    return Map.of("enabled", value.enabled());
  }

  @PutMapping("/providers/decision")
  Object decision(@RequestBody ProviderSettings settings) {
    if (settings.threshold() != null)
      decisions.threshold(settings.provider(), settings.threshold());
    decisions.select(settings.provider());
    return decisions.configuration();
  }

  @GetMapping("/notifications")
  Object notifications() {
    return db.app()
        .queryForList(
            "SELECT id,kind,subject,status,attempts,created_at,last_attempt FROM notification_events ORDER BY created_at DESC LIMIT 100");
  }

  @PostMapping("/notifications/{id}/retry")
  Object retryNotification(@PathVariable String id) {
    if (db.app()
            .update(
                "UPDATE notification_events SET status='PENDING',attempts=0,last_attempt=NULL WHERE id=? AND status='FAILED'",
                id)
        != 1) throw ApiException.badRequest("NOT_RETRYABLE", "该通知当前不能重发");
    return Map.of("queued", true);
  }
}
