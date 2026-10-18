package cyou.jujube.web;

import cyou.jujube.ai.EmbeddingProvider;
import cyou.jujube.ai.GenerationProvider;
import cyou.jujube.ai.SystemOneDecisions;
import cyou.jujube.common.ApiException;
import cyou.jujube.common.Json;
import cyou.jujube.ingestion.SourceService;
import cyou.jujube.knowledge.KnowledgeService;
import cyou.jujube.quota.BudgetService;
import cyou.jujube.storage.Database;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

  public AdminController(
      SourceService sources,
      KnowledgeService knowledge,
      Database db,
      BudgetService budget,
      SystemOneDecisions decisions,
      GenerationProvider generation,
      EmbeddingProvider embedding) {
    this.sources = sources;
    this.knowledge = knowledge;
    this.db = db;
    this.budget = budget;
    this.decisions = decisions;
    this.generation = generation;
    this.embedding = embedding;
  }

  @GetMapping("/sources")
  Object sources() {
    return sources.sources();
  }

  @GetMapping("/sources/{id}")
  Object source(@PathVariable String id) {
    return sources.source(id);
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
    sources.updateCandidate(id, update);
    return Map.of("updated", true, "requiresNewRelease", true);
  }

  public record CandidateEdit(
      String id, String kind, String content, String mastery, boolean approved) {}

  @PostMapping("/candidates/confirm")
  Object confirm(@RequestBody List<CandidateEdit> edits) {
    if (edits.isEmpty() || edits.size() > 100)
      throw ApiException.badRequest("INVALID_BATCH", "每次确认 1–100 条记录");
    db.transaction(
        sql -> {
          for (var edit : edits)
            sources.updateCandidate(
                edit.id(),
                new SourceService.CandidateUpdate(
                    edit.kind(), edit.content(), edit.mastery(), edit.approved()));
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
    return db.app().queryForList("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 100");
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
    return Json.map(db.setting("profile.public", "{}"));
  }

  @PutMapping("/profile")
  Object profile(@RequestBody Map<String, Object> value) {
    if (!Set.of(
            "displayName",
            "headline",
            "bio",
            "skills",
            "projects",
            "experiences",
            "contacts",
            "quickQuestions")
        .containsAll(value.keySet()))
      throw ApiException.badRequest("INVALID_PROFILE", "个人展示字段不受支持");
    String encoded = Json.write(value);
    if (encoded.length() > 30_000) throw ApiException.badRequest("PROFILE_TOO_LARGE", "个人展示内容过长");
    var publicValue = new LinkedHashMap<>(value);
    publicValue.put("configured", true);
    publicValue.put("aiDisclosure", "这是 AI 分身，回答依据为本人确认的资料。");
    db.putSetting("profile.public", Json.write(publicValue));
    return Map.of("published", true);
  }

  @GetMapping("/usage")
  Object usage() {
    return budget.overview();
  }

  @GetMapping("/calls")
  Object calls() {
    return db.app().queryForList("SELECT * FROM ai_calls ORDER BY created_at DESC LIMIT 200");
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
}
