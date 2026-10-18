package cyou.jujube.ai;

import cyou.jujube.common.ApiException;
import cyou.jujube.common.Diagnostics;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.quota.BudgetService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

@Service
public class DeepSeekProvider implements GenerationProvider {
  private final CloneProperties props;
  private final BudgetService budget;
  private final Diagnostics diagnostics;
  private volatile ChatClient client;

  public DeepSeekProvider(CloneProperties props, BudgetService budget, Diagnostics diagnostics) {
    this.props = props;
    this.budget = budget;
    this.diagnostics = diagnostics;
  }

  public boolean configured() {
    return props.deepseekKey() != null && !props.deepseekKey().isBlank();
  }

  private ChatClient client() {
    if (client == null)
      synchronized (this) {
        if (client == null)
          client =
              ChatClient.builder(
                      OpenAiChatModel.builder()
                          .options(
                              OpenAiChatOptions.builder()
                                  .apiKey(props.deepseekKey())
                                  .baseUrl(props.deepseekUrl())
                                  .model(props.deepseekModel())
                                  .maxTokens(1600)
                                  .maxRetries(0)
                                  .timeout(Duration.ofSeconds(25))
                                  .temperature(0.1)
                                  .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                                  .build())
                          .build())
                  .build();
      }
    return client;
  }

  @Override
  public String generate(String requestId, String stage, String system, String user) {
    if (!configured()) throw ApiException.unavailable("GENERATION_NOT_CONFIGURED", "尚未配置生成模型");
    long inputBound = (system + user).getBytes(StandardCharsets.UTF_8).length + 4096L;
    if (inputBound > 40_000)
      throw ApiException.badRequest("CONTEXT_TOO_LARGE", "本次处理内容超过上限，请拆分资料或缩小问题范围");
    long maximum =
        BudgetService.estimate(
            props.deepseekInputCnyPerMillion(),
            props.deepseekOutputCnyPerMillion(),
            inputBound,
            1600);
    var reservation = budget.require(requestId, stage, "deepseek", props.deepseekModel(), maximum);
    long started = System.nanoTime();
    try {
      var response = client().prompt().system(system).user(user).call().chatResponse();
      if (response == null
          || response.getResult() == null
          || response.getResult().getOutput() == null)
        throw ApiException.unavailable("EMPTY_MODEL_RESPONSE", "模型暂未返回有效回复");
      var usage = response.getMetadata().getUsage();
      boolean measured =
          usage != null
              && usage.getPromptTokens() != null
              && usage.getPromptTokens() > 0
              && usage.getCompletionTokens() != null
              && usage.getCompletionTokens() >= 0;
      long in = measured ? usage.getPromptTokens() : inputBound,
          out = measured ? usage.getCompletionTokens() : 1600;
      long actual =
          measured
              ? BudgetService.estimate(
                  props.deepseekInputCnyPerMillion(), props.deepseekOutputCnyPerMillion(), in, out)
              : maximum;
      long elapsed = (System.nanoTime() - started) / 1_000_000;
      budget.settle(reservation, actual, in, out, elapsed);
      diagnostics.event(
          requestId,
          stage,
          "MODEL_COMPLETED",
          Map.of(
              "provider",
              "deepseek",
              "model",
              props.deepseekModel(),
              "durationMs",
              elapsed,
              "inputTokens",
              in,
              "outputTokens",
              out,
              "usageReported",
              measured,
              "estimatedCostMicros",
              actual));
      return response.getResult().getOutput().getText();
    } catch (Exception ex) {
      budget.uncertain(
          reservation, ex.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
      diagnostics.event(
          requestId,
          stage,
          "MODEL_UNAVAILABLE",
          Map.of("provider", "deepseek", "errorType", ex.getClass().getSimpleName()));
      if (ex instanceof ApiException api) throw api;
      throw ApiException.unavailable("GENERATION_UNAVAILABLE", "模型服务暂时不可用，请稍后再试");
    }
  }
}
