package cyou.jujube.evaluation;

import cyou.jujube.CloneMeApplication;
import cyou.jujube.ai.*;
import cyou.jujube.common.*;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.conversation.*;
import cyou.jujube.notes.*;
import cyou.jujube.quota.BudgetService;
import cyou.jujube.storage.Database;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.*;
import tools.jackson.databind.JsonNode;

/** 本地命令不监听端口；仅允许在 Python 创建并清理认证数据的评测工作区内执行。 */
public final class EvaluationCommand {
  private EvaluationCommand() {}

  public static void run(Path file) throws Exception {
    var config = Json.read(Files.readString(file));
    Path workspace = Path.of(config.path("workspace").asText()).toRealPath();
    if (!Files.readString(workspace.resolve(".evaluation-workspace"))
        .equals("clone-me-evaluation-v1"))
      throw new IllegalArgumentException("Expected an isolated evaluation workspace");
    Path output = file.toAbsolutePath().getParent();
    var args =
        new ArrayList<>(
            List.of(
                "--clone.data-dir=" + workspace,
                "--clone.scheduler-enabled=false",
                "--clone.jobs-enabled=false",
                "--clone.operations.production=false",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--logging.level.cyou.jujube=ERROR",
                "--logging.level.cyou.jujube.common.FlowLog=OFF",
                "--logging.file.name=" + output.resolve("application.jsonl"),
                "--clone.daily-budget-cny=" + config.path("maxCostCny").asText()));
    boolean demo = config.path("demo").asBoolean();
    boolean lexicalOnly =
        config.path("variants").size() == 1
            && "lexical".equals(config.path("variants").get(0).asText());
    if (demo || lexicalOnly) args.addAll(List.of("--clone.embedding-key=", "--clone.rerank.key="));
    if (demo) args.addAll(List.of("--clone.deepseek-key=", "--clone.decision-provider=disabled"));
    var app = new SpringApplication(CloneMeApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    try (var context = app.run(args.toArray(String[]::new))) {
      var runner =
          new EvaluationRunner(
              context.getBean(Database.class),
              context.getBean(NoteIndex.class),
              context.getBean(ChatService.class),
              context.getBean(VisitorSessions.class));
      var db = context.getBean(Database.class);
      long maximum = BudgetService.micros(new BigDecimal(config.path("maxCostCny").asText()));
      db.putSetting("ai.total-limit-micros", Long.toString(maximum));
      // 评测账本独立，但不通过清除暂停状态绕过管理员主动暂停的付费调用。
      if (demo) seed(db, config.path("seedNotes"));
      runner.validateLabels(config.path("cases"));
      var props = context.getBean(CloneProperties.class);
      var rerankProps = context.getBean(cyou.jujube.config.RerankProperties.class);
      for (var variant : config.path("variants")) {
        if (!variant.asText().equals("lexical")
            && !context.getBean(EmbeddingProvider.class).configured())
          throw new IllegalArgumentException(
              "Hybrid evaluation requires configured embeddings; use lexical for offline evaluation");
        if (variant.asText().equals("full")
            && !"BAAI/bge-reranker-v2-m3".equals(rerankProps.model()))
          BudgetService.estimate(rerankProps.inputCnyPerMillion(), BigDecimal.ZERO, 1, 0);
        if (variant.asText().equals("full") && !context.getBean(RerankProvider.class).configured())
          throw new IllegalArgumentException("Full evaluation requires a configured reranker");
      }
      if (config.path("mode").asText().equals("answer")) {
        if (!context.getBean(GenerationProvider.class).configured())
          throw new IllegalArgumentException("Answer evaluation requires a configured generator");
        BudgetService.estimate(
            props.deepseekInputCnyPerMillion(), props.deepseekOutputCnyPerMillion(), 1, 1);
      }
      if (demo || config.path("reindex").asBoolean()) runner.reindex();
      runner.validate(config.path("cases"), lexicalOnly);
      var manifest = new LinkedHashMap<String, Object>();
      manifest.put("corpusHash", runner.corpusHash());
      manifest.put("indexHash", runner.indexHash());
      manifest.put("requestedIndexFingerprint", context.getBean(NoteIndex.class).fingerprint());
      manifest.put(
          "storedIndexFingerprints",
          db.app()
              .queryForList(
                  "SELECT DISTINCT index_fingerprint FROM notes WHERE knowledge=1 AND deleted=0 ORDER BY index_fingerprint",
                  String.class));
      manifest.put(
          "generation",
          Map.of(
              "model",
              props.deepseekModel(),
              "url",
              props.deepseekUrl(),
              "inputPrice",
              props.deepseekInputCnyPerMillion(),
              "outputPrice",
              props.deepseekOutputCnyPerMillion()));
      manifest.put(
          "rerank",
          Map.of(
              "configuration",
              context.getBean(RerankProvider.class).configuration(),
              "url",
              rerankProps.url(),
              "inputPrice",
              rerankProps.inputCnyPerMillion()));
      manifest.put("decision", context.getBean(SystemOneDecisions.class).configuration());
      manifest.put("retrievalPolicy", "rrf61-top40-fused30-parent8-v1");
      manifest.put("answerPolicy", "grounded-answer-v1");
      manifest.put("setupUsage", runner.usage(null));
      Files.writeString(
          output.resolve("manifest.json"), Json.write(manifest), StandardOpenOption.CREATE_NEW);
      runner.run(config, output.resolve("results.jsonl"));
    }
  }

  private static void seed(Database db, JsonNode notes) {
    for (var note : notes) {
      db.app()
          .update(
              "INSERT INTO notes(id,section_id,title,body,plain_text,knowledge,deleted,created_at,updated_at) VALUES(?,'default',?,?,?,?,?,0,0)",
              note.path("id").asText(),
              note.path("title").asText(),
              Json.write(note.path("body")),
              NoteService.plain(note.path("body")),
              note.path("knowledge").asBoolean() ? 1 : 0,
              note.path("deleted").asBoolean() ? 1 : 0);
    }
  }
}
