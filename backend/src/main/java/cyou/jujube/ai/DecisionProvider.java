package cyou.jujube.ai;

import java.util.List;
import java.util.Map;

public interface DecisionProvider {
  record Evidence(String id, String kind, String mastery, String content) {}

  record Judgment(
      List<Evidence> accepted,
      boolean insufficient,
      Map<String, Double> supportProbabilities,
      String provider,
      String model) {}

  Judgment evaluate(String requestId, String question, List<Evidence> evidence);
}
