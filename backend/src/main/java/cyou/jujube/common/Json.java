package cyou.jujube.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** 集中处理内部 JSON 序列化、解析和模型返回对象的提取。 这里使用 Jackson 2 的树结构；不要据此更改 Spring Boot 管理的 HTTP 消息转换器。 */
public final class Json {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Cannot encode JSON", ex);
    }
  }

  public static JsonNode read(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (Exception ex) {
      throw ApiException.badRequest("INVALID_JSON", "JSON 格式无效");
    }
  }

  public static Map<String, Object> map(String value) {
    try {
      return MAPPER.readValue(value, new TypeReference<>() {});
    } catch (Exception ex) {
      throw ApiException.badRequest("INVALID_JSON", "JSON 格式无效");
    }
  }

  /** 提取模型文本中的 JSON 对象候选；业务仍须检查字段、类型和引用，解析成功不代表内容可信。 */
  public static String objectText(String value) {
    String text = value == null ? "" : value.strip();
    if (text.startsWith("```")) {
      int start = text.indexOf('\n');
      int end = text.lastIndexOf("```");
      if (start >= 0 && end > start) text = text.substring(start + 1, end).strip();
    }
    if (!text.startsWith("{") || !text.endsWith("}"))
      throw ApiException.unavailable("INVALID_MODEL_RESPONSE", "模型返回的结构无效");
    return text;
  }
}
