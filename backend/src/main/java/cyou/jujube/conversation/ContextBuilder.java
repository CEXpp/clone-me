package cyou.jujube.conversation;

import cyou.jujube.common.Json;
import cyou.jujube.retrieval.HybridRetriever.Passage;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 按完整证据条目分配上下文，历史和风格优先让位，不裁剪事实正文以免改变原意。 */
public final class ContextBuilder {
  private static final int EVIDENCE_BYTES = 16_000;
  private static final int INPUT_BYTES = 28_000;

  private ContextBuilder() {}

  public static List<Passage> evidence(List<Passage> ranked) {
    // 输入已经按相关性排序；逐条试装，序列化后的 UTF-8 字节数才是这里采用的大小口径。
    // 中文字符通常占多个字节，所以不能直接用 String.length() 代替字节预算。
    var kept = new ArrayList<Passage>();
    for (var passage : ranked) {
      var candidate = new ArrayList<>(kept);
      candidate.add(passage);
      if (bytes(Json.write(candidate)) <= EVIDENCE_BYTES) kept.add(passage);
    }
    return List.copyOf(kept);
  }

  public static String generation(
      String question,
      List<Map<String, Object>> context,
      List<Passage> evidence,
      List<Map<String, Object>> style) {
    var history = new ArrayList<>(context);
    var samples = new ArrayList<>(style);
    while (true) {
      String input =
          Json.write(
              Map.of(
                  "question",
                  question,
                  "context",
                  history,
                  "evidence",
                  evidence,
                  "style",
                  samples));
      if (bytes(input) <= INPUT_BYTES) return input;
      // 超限时先删末尾风格样例，再删最早历史；当前问题与事实证据始终保留。
      if (!samples.isEmpty()) samples.removeLast();
      else if (!history.isEmpty()) history.removeFirst();
      else throw new IllegalStateException("Question and evidence exceed validated input budget");
    }
  }

  private static int bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
