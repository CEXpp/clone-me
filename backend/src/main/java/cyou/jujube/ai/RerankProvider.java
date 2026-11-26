package cyou.jujube.ai;

import java.util.List;
import java.util.Map;

/** 专用相关性模型只返回候选下标和分数，不能创造新的证据。 */
public interface RerankProvider {
  record Score(int index, double relevance) {}

  boolean configured();

  List<Score> rank(String requestId, String question, List<String> documents);

  default Map<String, Object> configuration() {
    return Map.of("configured", configured());
  }
}
