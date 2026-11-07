package cyou.jujube.web;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.ai.GenerationProvider;
import cyou.jujube.ai.SystemOneDecisions;
import cyou.jujube.common.ApiException;
import cyou.jujube.ingestion.SourceService;
import cyou.jujube.quota.BudgetService;
import cyou.jujube.storage.Database;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 受管理员认证保护的业务入口，连接公开展示、费用和运行诊断。 控制器只组织参数和响应；资料边界、预算与状态迁移由对应服务再次校验，不能只依赖前端限制。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
  // 类上的路径与方法路径相加，例如 /usage 实际是 /api/admin/usage。
  // 进入这些方法前，SecurityConfig 已要求 ADMIN 身份及写操作的 CSRF 校验。
  private final SourceService sources;
  private final Database db;
  private final BudgetService budget;
  private final SystemOneDecisions decisions;
  private final GenerationProvider generation;
  private final EmbeddingProvider embedding;
  private final cyou.jujube.ai.RerankProvider reranker;
  private final cyou.jujube.knowledge.ProfileService profile;
  private final cyou.jujube.operations.AdminQueries queries;
  private final cyou.jujube.operations.RuntimeStatus runtime;

  public AdminController(
      SourceService sources,
      Database db,
      BudgetService budget,
      SystemOneDecisions decisions,
      GenerationProvider generation,
      EmbeddingProvider embedding,
      cyou.jujube.ai.RerankProvider reranker,
      cyou.jujube.knowledge.ProfileService profile,
      cyou.jujube.operations.AdminQueries queries,
      cyou.jujube.operations.RuntimeStatus runtime) {
    this.sources = sources;
    this.db = db;
    this.budget = budget;
    this.decisions = decisions;
    this.generation = generation;
    this.embedding = embedding;
    this.reranker = reranker;
    this.profile = profile;
    this.queries = queries;
    this.runtime = runtime;
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
        "rerank",
        reranker.configuration(),
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
