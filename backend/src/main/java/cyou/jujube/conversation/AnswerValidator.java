package cyou.jujube.conversation;

import cyou.jujube.retrieval.HybridRetriever.Passage;
import java.util.*;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** 确定性结构与引用校验；语义是否受支持仍必须独立调用核验。 */
public final class AnswerValidator {
  private AnswerValidator() {}

  public static Set<String> citations(JsonNode draft, List<Passage> evidence) {
    String answer = draft.path("answer").asText();
    var ids = draft.path("evidenceIds");
    if (!draft.path("answer").isTextual()
        || answer.isBlank()
        || answer.length() > 5000
        || !ids.isArray()
        || ids.isEmpty()) return Set.of();
    Set<String> allowed = evidence.stream().map(Passage::id).collect(Collectors.toSet());
    Set<String> cited = new LinkedHashSet<>();
    for (var id : ids) {
      if (!id.isTextual() || !allowed.contains(id.asText())) return Set.of();
      cited.add(id.asText());
    }
    var claims = draft.path("claims");
    if (!claims.isArray() || claims.isEmpty()) return Set.of();
    for (var claim : claims) {
      var refs = claim.path("evidenceIds");
      if (!claim.path("text").isTextual()
          || claim.path("text").asText().isBlank()
          || !refs.isArray()
          || refs.isEmpty()) return Set.of();
      for (var ref : refs) if (!ref.isTextual() || !cited.contains(ref.asText())) return Set.of();
    }
    return Collections.unmodifiableSet(cited);
  }
}
