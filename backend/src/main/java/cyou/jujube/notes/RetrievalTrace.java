package cyou.jujube.notes;

import cyou.jujube.retrieval.HybridRetriever.Passage;
import java.util.*;

/** 显式传入才采集，离线评测使用；不落入访客响应、日志或生产数据库。 */
public final class RetrievalTrace {
  public record Hit(
      String id,
      String noteId,
      long revision,
      String headingPath,
      String content,
      double score,
      long ordinal) {}

  private final Map<String, List<Hit>> stages = new LinkedHashMap<>();
  private final Map<String, Long> timings = new LinkedHashMap<>();
  private String query = "";
  private boolean semanticUsed;
  private String fallbackReason = "";
  private long checkpoint = System.nanoTime();

  public void query(String value) {
    query = value;
  }

  public String query() {
    return query;
  }

  public void result(boolean semantic, String fallback) {
    semanticUsed = semantic;
    fallbackReason = fallback;
  }

  public boolean semanticUsed() {
    return semanticUsed;
  }

  public String fallbackReason() {
    return fallbackReason;
  }

  public Map<String, List<Hit>> stages() {
    return Collections.unmodifiableMap(stages);
  }

  public Map<String, Long> timings() {
    return Collections.unmodifiableMap(timings);
  }

  public void record(String stage, List<Hit> hits) {
    stages.put(stage, List.copyOf(hits));
    long now = System.nanoTime();
    timings.put(stage, (now - checkpoint) / 1_000_000);
    checkpoint = now;
  }

  public void context(String stage, List<Passage> passages) {
    var byId = new HashMap<String, Hit>();
    stages.getOrDefault("selected", List.of()).forEach(hit -> byId.put(hit.id(), hit));
    record(
        stage,
        passages.stream()
            .map(
                p -> {
                  var hit = byId.get(p.id());
                  if (hit == null)
                    throw new IllegalArgumentException("Evidence missing from retrieval trace");
                  return new Hit(
                      hit.id(),
                      hit.noteId(),
                      hit.revision(),
                      hit.headingPath(),
                      p.content(),
                      p.score(),
                      hit.ordinal());
                })
            .toList());
  }
}
