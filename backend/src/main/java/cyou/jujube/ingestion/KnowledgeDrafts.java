package cyou.jujube.ingestion;

import java.util.*;
import java.util.regex.Pattern;

/** 无模型的知识草稿基线：保留原文，过滤目录及页码，按标题和段落切分，不推断掌握程度。 */
public final class KnowledgeDrafts {
  private static final Pattern HEADING =
      Pattern.compile(
          "^(?:#{1,6}\\s+.+|\\d+(?:\\.\\d+){1,3}[.、]?\\s+.+|[一二三四五六七八九十]+、.+|第\\d+[篇章节].*)$");

  private KnowledgeDrafts() {}

  public static boolean heading(String line) {
    return line.length() <= 100 && HEADING.matcher(line.strip()).matches();
  }

  /** 目录判断同时要求大量短标题、标题密度与缺少正文，避免仅凭“目录”二字误删正文。 */
  public static boolean contents(String text) {
    var lines = clean(text).lines().filter(s -> !s.isBlank()).toList();
    long headings = lines.stream().filter(KnowledgeDrafts::heading).count();
    long prose = lines.stream().filter(s -> s.length() > 85 || s.matches(".*[。；;{}].*")).count();
    return headings >= 8 && headings >= lines.size() * 0.48 && prose <= 2;
  }

  public static String clean(String raw) {
    var lines =
        new ArrayList<>(
            raw.replace("\r\n", "\n")
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .lines()
                .map(s -> s.replaceFirst("^\\s*[●•]\\s*", "").stripTrailing())
                .toList());
    var numbered = Pattern.compile("^\\s*(\\d{1,4})(?: (.*))?$");
    for (int i = 0; i < lines.size(); i++) {
      var first = numbered.matcher(lines.get(i));
      if (!first.matches()) continue;
      int expected = Integer.parseInt(first.group(1)), end = i, count = 0;
      boolean code = false;
      while (end < lines.size()) {
        if (lines.get(end).isBlank()) {
          end++;
          continue;
        }
        var match = numbered.matcher(lines.get(end));
        if (!match.matches() || Integer.parseInt(match.group(1)) != expected) break;
        String body = match.group(2) == null ? "" : match.group(2);
        code |= body.matches(".*[;{}].*") || body.contains("//");
        expected++;
        count++;
        end++;
      }
      if (count >= 4 && code) {
        for (int j = i; j < end; j++) {
          var match = numbered.matcher(lines.get(j));
          if (match.matches()) lines.set(j, match.group(2) == null ? "" : match.group(2));
        }
        i = end - 1;
      }
    }
    var out = new ArrayList<String>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.matches("[●•]+")) continue;
      // PDF 代码块的行号常被单独提取到末尾。只去掉连续递增的行号串，保留独立数值。
      if (line.matches("\\d{1,4}")) {
        int end = i + 1, value = Integer.parseInt(line);
        while (end < lines.size()
            && lines.get(end).strip().equals(Integer.toString(value + end - i))) end++;
        if (end - i >= 4) {
          i = end - 1;
          continue;
        }
      }
      out.add(lines.get(i));
    }
    return String.join("\n", out).strip().replaceAll("\\n{3,}", "\n\n");
  }

  public static List<String> build(String raw, String label) {
    if (contents(raw)) return List.of();
    String text = clean(raw);
    var page = Pattern.compile("第 (\\d+) 页").matcher(label);
    if (page.find()) {
      String number = page.group(1);
      text =
          text.replaceFirst("^" + number + "\\s*\\n", "")
              .replaceFirst("\\n" + number + "$", "")
              .strip();
    }
    var blocks = new ArrayList<String>();
    var current = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      if (heading(line) && current.length() > 0) {
        blocks.add(current.toString().strip());
        current.setLength(0);
      }
      current.append(line).append('\n');
    }
    if (!current.isEmpty()) blocks.add(current.toString().strip());
    var result = new ArrayList<String>();
    for (String block : blocks) {
      // 只有标题没有解释的部分不生成候选；保留在左侧原文中供核对。
      if (block.isBlank() || (heading(block) && !block.contains("\n"))) continue;
      if (block.lines().findFirst().orElse("").matches("第\\d+[篇章节].*")
          && block.length() < 180
          && !block.matches("(?s).*[。；;{}].*")) continue;
      int offset = 0;
      while (offset < block.length()) {
        int end = Math.min(block.length(), offset + 1200);
        if (end < block.length()) {
          int boundary = block.lastIndexOf('\n', end);
          if (boundary > offset + 400) end = boundary;
        }
        String part = block.substring(offset, end).strip();
        if (part.length() >= 20) result.add(part);
        offset = end;
      }
    }
    return result.stream().distinct().toList();
  }
}
