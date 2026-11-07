package cyou.jujube.ingestion;

import cyou.jujube.common.ApiException;
import cyou.jujube.common.Json;
import java.nio.file.*;
import java.util.*;

/** 外部整理稿的版本化交换格式。检查结构并保留引文，掌握程度和批准状态一律由站内人工确认。 */
public final class PreparedKnowledge {
  public static final String SCHEMA = "clone-me.knowledge.v1";

  public record Record(DocumentParser.Segment segment, String content) {}

  private PreparedKnowledge() {}

  public static List<Record> read(Path file) throws Exception {
    if (Files.size(file) > 10 * 1024 * 1024)
      throw ApiException.badRequest("FILE_TOO_LARGE", "文件超过10MB，请拆分");
    var root = Json.read(Files.readString(file));
    if (!SCHEMA.equals(root.path("schema").asText())
        || !root.path("records").isArray()
        || root.path("records").isEmpty()
        || root.path("records").size() > 1000)
      throw invalid("请使用知识整理模板，schema 需为 " + SCHEMA + "，每批1–1000条");
    var result = new ArrayList<Record>();
    Set<String> seen = new HashSet<>();
    for (var record : root.path("records")) {
      String title = field(record, "title", 120), content = field(record, "content", 1600);
      var source = record.path("source");
      String name = field(source, "name", 120),
          location = field(source, "location", 160),
          excerpt = field(source, "excerpt", 8000);
      if (KnowledgeDrafts.contents(content)) throw invalid("目录不能作为知识正文，请保留概念解释");
      // 外部整理稿本身是用户提供的资料；这里只验证结构，不声称已核实外部模型的语义正确性。
      String key = Json.write(List.of(title, content));
      if (!seen.add(key)) continue;
      var segment =
          new DocumentParser.Segment(
              UUID.randomUUID().toString(),
              result.size(),
              "外部整理 · " + name + " · " + location,
              "SELF",
              excerpt);
      result.add(new Record(segment, title + "\n" + content));
    }
    return result;
  }

  private static String field(tools.jackson.databind.JsonNode node, String name, int maximum) {
    var value = node.path(name);
    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maximum)
      throw invalid("整理稿的 " + name + " 需为1–" + maximum + "字符的文本");
    return value.asText().strip();
  }

  private static ApiException invalid(String message) {
    return ApiException.badRequest("INVALID_PREPARED_KNOWLEDGE", message);
  }
}
