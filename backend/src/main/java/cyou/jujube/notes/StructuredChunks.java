package cyou.jujube.notes;

import cyou.jujube.common.Json;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** 无网络的结构化切分：标题限定小节，块限定语义边界，小块召回、有限父块供回答。 */
public final class StructuredChunks {
  public static final String VERSION = "structure-context-v1";
  public static final int CHILD_LIMIT = 700, PARENT_LIMIT = 1800;

  public record Child(String kind, String content) {}

  public record Parent(String headingPath, String content, List<Child> children) {}

  private record Block(String kind, String text) {}

  private StructuredChunks() {}

  public static List<Parent> split(String body) {
    var result = new ArrayList<Parent>();
    var headings = new TreeMap<Integer, String>();
    var children = new ArrayList<Child>();
    int size = 0;
    String path = "";
    for (var node : Json.read(body).path("content")) {
      String kind = node.path("type").asText();
      if ("heading".equals(kind)) {
        flush(result, path, children);
        size = 0;
        int level = Math.max(1, Math.min(6, node.path("attrs").path("level").asInt(1)));
        headings.tailMap(level).clear();
        headings.put(level, inline(node).strip());
        path = String.join(" / ", headings.values());
        continue;
      }
      for (Block block : blocks(node)) {
        if (block.text().isBlank()) continue;
        for (String piece : pieces(block.text(), CHILD_LIMIT)) {
          if (size + piece.length() + 2 > PARENT_LIMIT) {
            flush(result, path, children);
            size = 0;
          }
          if (!children.isEmpty()
              && children.getLast().kind().equals(block.kind())
              && children.getLast().content().length() + piece.length() + 2 <= CHILD_LIMIT) {
            var previous = children.removeLast();
            children.add(new Child(block.kind(), previous.content() + "\n\n" + piece));
          } else children.add(new Child(block.kind(), piece));
          size += piece.length() + 2;
        }
      }
    }
    flush(result, path, children);
    return List.copyOf(result);
  }

  private static void flush(List<Parent> out, String heading, List<Child> children) {
    if (children.isEmpty()) return;
    out.add(
        new Parent(
            heading,
            String.join("\n\n", children.stream().map(Child::content).toList()),
            List.copyOf(children)));
    children.clear();
  }

  /** 上下文完全来自已有元数据；不让模型补写事实，也不覆盖原文。 */
  public static String context(String notebook, String section, String title, String heading) {
    return "笔记位置："
        + shortText(notebook, 80)
        + " / "
        + shortText(section, 80)
        + "\n笔记标题："
        + shortText(title, 120)
        + (heading.isBlank() ? "" : "\n小节：" + shortText(heading, 220));
  }

  private static List<Block> blocks(JsonNode node) {
    String type = node.path("type").asText();
    var result = new ArrayList<Block>();
    switch (type) {
      case "table" -> {
        String header = "";
        for (var row : node.path("content")) {
          var cells = new ArrayList<String>();
          boolean isHeader = false;
          for (var cell : row.path("content")) {
            cells.add(inline(cell).strip().replace('\n', ' '));
            isHeader |= "tableHeader".equals(cell.path("type").asText());
          }
          String text = String.join(" | ", cells);
          if (isHeader && header.isEmpty()) header = shortText(text, 200);
          else {
            // 长表按行切；每行保留表头，避免只剩下无法解释的数字。
            for (String part : pieces(text, CHILD_LIMIT - header.length() - 1))
              result.add(new Block("table", header.isEmpty() ? part : header + "\n" + part));
          }
        }
        if (result.isEmpty() && !header.isBlank()) result.add(new Block("table", header));
      }
      case "bulletList", "orderedList", "taskList" -> {
        int number = node.path("attrs").path("start").asInt(1);
        for (var item : node.path("content")) {
          String prefix = "orderedList".equals(type) ? number++ + ". " : "- ";
          result.add(new Block("list", prefix + inline(item).strip()));
        }
      }
      case "codeBlock" -> {
        String language = shortText(node.path("attrs").path("language").asText(""), 40);
        String open = "```" + language + "\n";
        for (String part : pieces(inline(node), CHILD_LIMIT - open.length() - 4))
          result.add(new Block("code", open + part + "\n```"));
      }
      case "image" -> {
        String alt = node.path("attrs").path("alt").asText("");
        if (!alt.isBlank()) result.add(new Block("image-caption", alt));
      }
      default -> result.add(new Block(type.isBlank() ? "paragraph" : type, inline(node).strip()));
    }
    return result;
  }

  private static String inline(JsonNode node) {
    var out = new StringBuilder(node.path("text").asText(""));
    for (var child : node.path("content")) {
      out.append(inline(child));
      if (Set.of("paragraph", "listItem", "taskItem", "hardBreak")
          .contains(child.path("type").asText())) out.append('\n');
    }
    if ("hardBreak".equals(node.path("type").asText())) out.append('\n');
    return out.toString();
  }

  /** 超长块先找换行或句末，再按字符兜底；不截断 UTF-16 代理对。无重叠复制。 */
  static List<String> pieces(String value, int limit) {
    var out = new ArrayList<String>();
    String text = value.strip();
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(text.length(), start + limit);
      if (end < text.length()) {
        int boundary = -1;
        for (int i = end - 1; i >= start + limit / 2; i--)
          if ("\n。！？；.!?;".indexOf(text.charAt(i)) >= 0) {
            boundary = i + 1;
            break;
          }
        if (boundary > start) end = boundary;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
      }
      String part = text.substring(start, end).strip();
      if (!part.isBlank()) out.add(part);
      start = end;
    }
    return out;
  }

  private static String shortText(String text, int maximum) {
    if (text.length() <= maximum) return text;
    int end = Character.isHighSurrogate(text.charAt(maximum - 1)) ? maximum - 1 : maximum;
    return text.substring(0, end);
  }
}
