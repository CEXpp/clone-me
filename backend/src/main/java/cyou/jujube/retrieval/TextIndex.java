package cyou.jujube.retrieval;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.regex.Pattern;

/** 提供中文检索词元、查询表达式以及向量二进制编码和相似度计算。 词元化把 Java 等技术标识符与汉字片段分开处理，供 SQLite FTS5 建索引和查询共用。 */
public final class TextIndex {
  private static final Pattern TOKENS = Pattern.compile("[A-Za-z0-9_+#.-]+|[\\p{IsHan}]+");

  private TextIndex() {}

  /** 把连续汉字切成一至三字词元，支持中文短词检索，无需额外部署分词服务。 */
  public static List<String> tokens(String input) {
    var values = new LinkedHashSet<String>();
    var matcher = TOKENS.matcher(input.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String token = matcher.group();
      if (token
          .codePoints()
          .allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
        int[] codepoints = token.codePoints().toArray();
        for (int n = 1; n <= 3; n++)
          for (int start = 0; start + n <= codepoints.length; start++)
            values.add(new String(codepoints, start, n));
      } else {
        values.add(token);
        for (String part : token.split("[_.+#-]+")) if (!part.isBlank()) values.add(part);
      }
    }
    return List.copyOf(values);
  }

  public static String terms(String input) {
    return String.join(" ", tokens(input));
  }

  /** 把词元转成带引号的 OR 查询，避免用户原文被当成 FTS 操作符直接执行。 */
  public static String query(String input) {
    return tokens(input).stream()
        .filter(t -> t.length() > 1)
        .limit(80)
        .map(t -> "\"" + t.replace("\"", "\"\"") + "\"")
        .collect(java.util.stream.Collectors.joining(" OR "));
  }

  /** 把浮点数组编码成定长二进制存入 SQLite；读取必须使用相同字节顺序。 */
  public static byte[] encode(float[] vector) {
    ByteBuffer data =
        ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (float value : vector) data.putFloat(value);
    return data.array();
  }

  public static float[] decode(byte[] bytes) {
    if (bytes.length % 4 != 0) throw new IllegalArgumentException("Invalid vector blob");
    ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    float[] values = new float[bytes.length / 4];
    for (int i = 0; i < values.length; i++) values[i] = data.getFloat();
    return values;
  }

  /** 检查向量维度后计算相似度；供应商已统一归一化，不能把不同模型向量放在一起比较。 */
  public static double similarity(float[] a, float[] b) {
    if (a.length != b.length) throw new IllegalArgumentException("Embedding fingerprint mismatch");
    double dot = 0, aa = 0, bb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      aa += (double) a[i] * a[i];
      bb += (double) b[i] * b[i];
    }
    return aa == 0 || bb == 0 ? 0 : dot / Math.sqrt(aa * bb);
  }
}
