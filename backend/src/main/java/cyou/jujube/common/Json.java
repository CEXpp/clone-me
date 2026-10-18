package cyou.jujube.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

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
