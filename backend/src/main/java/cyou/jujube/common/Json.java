package cyou.jujube.common;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 内部 JSON 统一使用 Boot 管理的 Jackson 3，复用不可变 Mapper；HTTP 转换仍由 Boot 配置。 */
public final class Json {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

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

  public static JsonNode model(String value) {
    try {
      JsonNode result = read(objectText(value));
      if (result == null || !result.isObject()) throw new IllegalArgumentException();
      return result;
    } catch (Exception ex) {
      throw ApiException.unavailable("INVALID_MODEL_RESPONSE", "外部服务返回的结构无效，请稍后再试");
    }
  }

  public static <T> T decode(String value, Class<T> type) {
    try {
      return MAPPER.readValue(value, type);
    } catch (Exception ex) {
      throw new IllegalStateException("Stored JSON is invalid", ex);
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
