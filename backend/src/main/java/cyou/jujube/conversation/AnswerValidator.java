package cyou.jujube.conversation;

import cyou.jujube.retrieval.HybridRetriever.Passage;
import java.util.*;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** 确定性结构与引用校验；语义是否受支持仍必须独立调用核验。 */
public final class AnswerValidator {
  private AnswerValidator() {}

  public static Set<String> citations(JsonNode draft, List<Passage> evidence) {
    // 空集合统一表示结构校验未通过；不是“可以展示但不附来源”。上层会拦下这份草稿。
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
    // evidenceIds 是整份答案的引用，claims 是模型列出的具体陈述及其引用。
    // 这里只能检查编号关系；是否漏列事实、是否夸大经历，仍交给后续独立语义核验。
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
