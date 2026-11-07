package cyou.jujube.notes;

import cyou.jujube.common.Json;
import cyou.jujube.ingestion.DocumentParser;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** 导入时保留可确定的结构：Markdown 块和 PDF 页码；不猜测 PDF 的版式标题。 */
final class ImportedNotes {
  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

  private ImportedNotes() {}

  static JsonNode body(String format, String markdown, List<DocumentParser.Segment> segments) {
    var nodes = new ArrayList<Object>();
    if ("md".equals(format)) markdown(markdown, nodes);
    else {
      String previousPage = "";
      for (var segment : segments) {
        if ("pdf".equals(format)) {
          String page = segment.label().split(" · ")[0];
          if (!page.equals(previousPage)) {
            nodes.add(heading(page, 1));
            previousPage = page;
          }
        }
        for (String p : segment.content().split("\\n\\s*\\n"))
          if (!p.isBlank()) nodes.add(text("paragraph", p));
      }
    }
    return Json.read(Json.write(Map.of("type", "doc", "content", nodes)));
  }

  private static void markdown(String source, List<Object> nodes) {
    var paragraph = new ArrayList<String>();
    var code = new ArrayList<String>();
    String fence = "", language = "";
    for (String line : source.replace("\r\n", "\n").split("\n", -1)) {
      String stripped = line.strip();
      if (!fence.isEmpty()) {
        if (stripped.matches(
            Pattern.quote(fence.charAt(0) + "") + "{" + fence.length() + ",}\\s*")) {
          nodes.add(
              Map.of(
                  "type",
                  "codeBlock",
                  "attrs",
                  Map.of("language", language),
                  "content",
                  List.of(Map.of("type", "text", "text", String.join("\n", code)))));
          fence = "";
          code.clear();
        } else code.add(line);
        continue;
      }
      if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
        flush(paragraph, nodes);
        char marker = stripped.charAt(0);
        int length = 0;
        while (length < stripped.length() && stripped.charAt(length) == marker) length++;
        fence = stripped.substring(0, length);
        language = stripped.substring(length).strip();
        continue;
      }
      var heading = HEADING.matcher(stripped);
      if (heading.matches()) {
        flush(paragraph, nodes);
        nodes.add(heading(heading.group(2), heading.group(1).length()));
      } else if (stripped.isEmpty()) flush(paragraph, nodes);
      else if (stripped.matches("(?:[-+*]|\\d{1,9}[.)])\\s+.*")) {
        flush(paragraph, nodes);
        boolean ordered = Character.isDigit(stripped.charAt(0));
        String value = stripped.replaceFirst("^(?:[-+*]|\\d{1,9}[.)])\\s+", "");
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("type", ordered ? "orderedList" : "bulletList");
        if (ordered)
          list.put("attrs", Map.of("start", Integer.parseInt(stripped.split("[.)]")[0])));
        list.put(
            "content",
            List.of(Map.of("type", "listItem", "content", List.of(text("paragraph", value)))));
        nodes.add(list);
      } else paragraph.add(line);
    }
    flush(paragraph, nodes);
    // 未闭合围栏仍保留全部原文，不静默丢弃尾部。
    if (!fence.isEmpty()) nodes.add(text("codeBlock", String.join("\n", code)));
  }

  private static void flush(List<String> lines, List<Object> nodes) {
    if (!lines.isEmpty()) nodes.add(text("paragraph", String.join("\n", lines)));
    lines.clear();
  }

  private static Map<String, Object> text(String type, String value) {
    return Map.of("type", type, "content", List.of(Map.of("type", "text", "text", value)));
  }

  private static Map<String, Object> heading(String value, int level) {
    return Map.of(
        "type",
        "heading",
        "attrs",
        Map.of("level", level),
        "content",
        List.of(Map.of("type", "text", "text", value)));
  }
}
