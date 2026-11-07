package cyou.jujube.ai;

import cyou.jujube.common.*;
import cyou.jujube.config.RerankProperties;
import cyou.jujube.quota.BudgetService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/** 独立重排接口；复用全局预算与请求截止时间，失败不自动重试。 */
@Service
public class SiliconFlowRerank implements RerankProvider {
  private final RerankProperties props;
  private final BudgetService budget;
  private final Diagnostics diagnostics;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public SiliconFlowRerank(RerankProperties props, BudgetService budget, Diagnostics diagnostics) {
    this.props = props;
    this.budget = budget;
    this.diagnostics = diagnostics;
  }

  public boolean configured() {
    return props.key() != null && !props.key().isBlank();
  }

  public Map<String, Object> configuration() {
    return Map.of("configured", configured(), "model", props.model(), "provider", "siliconflow");
  }

  public List<Score> rank(String requestId, String question, List<String> documents) {
    RequestDeadline.check();
    if (!configured()) throw ApiException.unavailable("RERANK_NOT_CONFIGURED", "尚未配置重排模型");
    if (documents.isEmpty()) return List.of();
    // 默认免费模型沿用现有 Embedding 密钥；更换模型必须显式填写价格。
    BigDecimal price = props.inputCnyPerMillion();
    if (price == null || price.signum() < 0) {
      if (!"BAAI/bge-reranker-v2-m3".equals(props.model()))
        throw ApiException.unavailable("PRICING_REQUIRED", "请先配置重排模型的计费单价");
      price = BigDecimal.ZERO;
    }
    // 每个候选都配对一次问题。UTF-8 字节数用作保守预留，绝不声称是精确 token 数。
    long estimate = 0;
    for (String document : documents)
      estimate +=
          64L
              + question.getBytes(StandardCharsets.UTF_8).length
              + document.getBytes(StandardCharsets.UTF_8).length;
    var reservation =
        budget.require(
            requestId,
            "RETRIEVAL_RERANK",
            "siliconflow",
            props.model(),
            BudgetService.estimate(price, BigDecimal.ZERO, estimate, 0));
    budget.pricing(reservation, Map.of("inputCnyPerMillion", price, "model", props.model()));
    long started = System.nanoTime();
    try {
      var request =
          HttpRequest.newBuilder(URI.create(props.url().replaceAll("/$", "") + "/rerank"))
              .timeout(RequestDeadline.remaining(Duration.ofSeconds(8)))
              .header("Authorization", "Bearer " + props.key())
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      Json.write(
                          Map.of(
                              "model",
                              props.model(),
                              "query",
                              question,
                              "documents",
                              documents,
                              "top_n",
                              documents.size(),
                              "return_documents",
                              false))))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw ApiException.unavailable("RERANK_HTTP_" + response.statusCode(), "重排服务暂时不可用");
      var root = Json.model(response.body());
      var results = root.path("results");
      // 请求全部候选，防止缺项/重复下标悄悄丢掉证据。严格校验后自行按分数排序。
      if (!results.isArray() || results.size() != documents.size())
        throw new IllegalArgumentException("Invalid rerank count");
      var scores = new ArrayList<Score>();
      var seen = new HashSet<Integer>();
      for (var item : results) {
        var index = item.path("index");
        double score = item.path("relevance_score").asDouble(Double.NaN);
        if (!index.isIntegralNumber()
            || !index.canConvertToInt()
            || index.asInt() < 0
            || index.asInt() >= documents.size()
            || !seen.add(index.asInt())
            || !Double.isFinite(score)) throw new IllegalArgumentException("Invalid rerank result");
        scores.add(new Score(index.asInt(), score));
      }
      scores.sort(
          Comparator.comparingDouble(Score::relevance).reversed().thenComparingInt(Score::index));
      // 同时兼容当前 meta.tokens 和旧版 tokens/usage 的用量封装。
      var usage = root.path("meta").path("tokens");
      if (usage.isMissingNode()) usage = root.path("tokens");
      long tokens = usage.path("input_tokens").asLong(-1);
      if (tokens < 0) tokens = root.path("usage").path("prompt_tokens").asLong(-1);
      long duration = (System.nanoTime() - started) / 1_000_000;
      if (tokens < 0 && price.signum() > 0)
        budget.uncertain(reservation, "MISSING_USAGE", duration);
      else
        budget.settle(
            reservation,
            BudgetService.estimate(price, BigDecimal.ZERO, Math.max(0, tokens), 0),
            Math.max(0, tokens),
            0,
            duration);
      diagnostics.event(
          requestId,
          "RETRIEVAL_RERANK",
          "MODEL_COMPLETED",
          Map.of("model", props.model(), "candidates", documents.size(), "durationMs", duration));
      return List.copyOf(scores);
    } catch (Exception ex) {
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      budget.uncertain(
          reservation, ex.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
      throw ex instanceof ApiException api
          ? api
          : ApiException.unavailable("RERANK_INVALID_RESPONSE", "重排失败，使用融合排序");
    }
  }
}
