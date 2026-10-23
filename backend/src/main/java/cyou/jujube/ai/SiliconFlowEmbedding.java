package cyou.jujube.ai;

import cyou.jujube.common.ApiException;
import cyou.jujube.common.Diagnostics;
import cyou.jujube.common.Json;
import cyou.jujube.common.RequestDeadline;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.quota.BudgetService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 调用硅基流动的免费 BAAI/bge-m3，并严格校验一条 1024 维的有限非零向量。 返回前做 L2 归一化，保证建索引和查询使用同一数学口径；即使免费也保留调用账目和耗时。 */
@Service
public class SiliconFlowEmbedding implements EmbeddingProvider {
  private final CloneProperties props;
  private final BudgetService budget;
  private final Diagnostics diagnostics;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public SiliconFlowEmbedding(
      CloneProperties props, BudgetService budget, Diagnostics diagnostics) {
    this.props = props;
    this.budget = budget;
    this.diagnostics = diagnostics;
  }

  public boolean configured() {
    return props.embeddingKey() != null && !props.embeddingKey().isBlank();
  }

  /** 将端点、模型、维度及归一化版本组成指纹，防止配置改变后错误复用缓存或索引。 */
  public String fingerprint() {
    return "siliconflow|" + props.embeddingUrl() + "|" + props.embeddingModel() + "|1024|l2|v1";
  }

  /** 只允许已选定免费模型；校验响应数量、维度和有限数值后归一化，不能把无效向量写入索引。 */
  @Override
  public float[] embed(String requestId, String text) {
    RequestDeadline.check();
    if (!configured()) throw ApiException.unavailable("EMBEDDING_NOT_CONFIGURED", "尚未配置向量模型");
    if (!"BAAI/bge-m3".equals(props.embeddingModel()))
      throw ApiException.unavailable("EMBEDDING_MODEL_NOT_APPROVED", "当前只允许已确认的免费 Embedding 模型");
    var reservation =
        budget.require(requestId, "EMBEDDING", "siliconflow", props.embeddingModel(), 0);
    long started = System.nanoTime();
    try {
      var request =
          HttpRequest.newBuilder(
                  URI.create(props.embeddingUrl().replaceAll("/$", "") + "/embeddings"))
              .timeout(RequestDeadline.remaining(Duration.ofSeconds(12)))
              .header("Authorization", "Bearer " + props.embeddingKey())
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      Json.write(
                          Map.of(
                              "model",
                              props.embeddingModel(),
                              "input",
                              text,
                              "encoding_format",
                              "float"))))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw ApiException.unavailable("EMBEDDING_HTTP_" + response.statusCode(), "向量服务暂时不可用");
      var root = Json.model(response.body());
      var data = root.path("data");
      if (!data.isArray() || data.size() != 1 || data.get(0).path("index").asInt(0) != 0)
        throw new IllegalArgumentException("Invalid embedding envelope");
      var vector = data.get(0).path("embedding");
      if (!vector.isArray() || vector.size() != 1024)
        throw new IllegalArgumentException("Invalid embedding dimensions");
      float[] values = new float[1024];
      double norm = 0;
      for (int i = 0; i < values.length; i++) {
        values[i] = (float) vector.get(i).asDouble(Double.NaN);
        if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Nonfinite embedding");
        norm += (double) values[i] * values[i];
      }
      if (norm == 0) throw new IllegalArgumentException("Zero embedding");
      double scale = Math.sqrt(norm);
      for (int i = 0; i < values.length; i++) values[i] /= (float) scale;
      long elapsed = (System.nanoTime() - started) / 1_000_000;
      long tokens = root.path("usage").path("total_tokens").asLong(0);
      budget.settle(reservation, 0, tokens, 0, elapsed);
      diagnostics.event(
          requestId,
          "EMBEDDING",
          "MODEL_COMPLETED",
          Map.of(
              "provider",
              "siliconflow",
              "model",
              props.embeddingModel(),
              "dimensions",
              values.length,
              "durationMs",
              elapsed));
      return values;
    } catch (Exception ex) {
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      budget.uncertain(
          reservation, ex.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
      throw ex instanceof ApiException api
          ? api
          : ApiException.unavailable("EMBEDDING_UNAVAILABLE", "向量服务暂时不可用");
    }
  }
}
