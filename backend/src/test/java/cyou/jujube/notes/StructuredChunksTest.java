package cyou.jujube.notes;

import static org.junit.jupiter.api.Assertions.*;

import cyou.jujube.common.Json;
import cyou.jujube.ingestion.DocumentParser;
import java.util.*;
import org.junit.jupiter.api.Test;

class StructuredChunksTest {
  private Map<String, Object> node(String type, String value) {
    return Map.of("type", type, "content", List.of(Map.of("type", "text", "text", value)));
  }

  private Map<String, Object> heading(String value, int level) {
    return Map.of(
        "type",
        "heading",
        "attrs",
        Map.of("level", level),
        "content",
        List.of(Map.of("type", "text", "text", value)));
  }

  private List<StructuredChunks.Parent> split(Object... nodes) {
    return StructuredChunks.split(Json.write(Map.of("type", "doc", "content", List.of(nodes))));
  }

  @Test
  void headingHierarchyIsKeptAndParentsNeverCrossSections() {
    var parents =
        split(
            heading("Java", 1),
            heading("索引", 2),
            node("paragraph", "数据库索引正文"),
            heading("内存", 2),
            node("paragraph", "垃圾回收正文"),
            heading("Python", 1),
            node("paragraph", "解释器正文"));
    assertEquals(
        List.of("Java / 索引", "Java / 内存", "Python"),
        parents.stream().map(StructuredChunks.Parent::headingPath).toList());
    assertFalse(parents.getFirst().content().contains("垃圾回收"));
    assertTrue(
        StructuredChunks.context("学习", "数据库", "入门", parents.getFirst().headingPath())
            .contains("Java / 索引"));
  }

  @Test
  void longTextIsBoundedWithoutLosingUnicodeOrDuplicatingOverlap() {
    String raw = "数据库😀检索。".repeat(1000);
    var parents = split(node("paragraph", raw));
    assertEquals(
        raw,
        String.join(
            "",
            parents.stream()
                .flatMap(p -> p.children().stream())
                .map(StructuredChunks.Child::content)
                .toList()));
    assertTrue(
        parents.stream().allMatch(p -> p.content().length() <= StructuredChunks.PARENT_LIMIT));
    assertTrue(
        parents.stream()
            .flatMap(p -> p.children().stream())
            .allMatch(c -> c.content().length() <= StructuredChunks.CHILD_LIMIT));
    assertTrue(
        parents.stream()
            .flatMap(p -> p.children().stream())
            .allMatch(
                c -> !Character.isHighSurrogate(c.content().charAt(c.content().length() - 1))));
  }

  @Test
  void tablesRepeatHeadersAndCodeRetainsItsLanguage() {
    var rows = new ArrayList<Object>();
    rows.add(
        Map.of(
            "type",
            "tableRow",
            "content",
            List.of(node("tableHeader", "型号"), node("tableHeader", "容量"))));
    for (int i = 0; i < 100; i++)
      rows.add(
          Map.of(
              "type",
              "tableRow",
              "content",
              List.of(node("tableCell", "服务器" + i), node("tableCell", i + "GB"))));
    var code =
        Map.of(
            "type",
            "codeBlock",
            "attrs",
            Map.of("language", "java"),
            "content",
            List.of(Map.of("type", "text", "text", "System.out.println(1);\n".repeat(80))));
    var parents = split(Map.of("type", "table", "content", rows), heading("代码", 1), code);
    var children = parents.stream().flatMap(p -> p.children().stream()).toList();
    assertTrue(
        children.stream()
            .filter(c -> c.kind().equals("table"))
            .allMatch(c -> c.content().contains("型号 | 容量")));
    assertTrue(
        children.stream()
            .filter(c -> c.kind().equals("code"))
            .allMatch(c -> c.content().startsWith("```java\n") && c.content().endsWith("\n```")));
    assertTrue(parents.stream().anyMatch(p -> p.content().contains("服务器99")));
  }

  @Test
  void importRetainsMarkdownBlocksAndPdfPages() {
    String markdown = "# 数据库\n\n## 索引\n查询说明\n\n```sql\nselect 1;\n\nselect 2;\n```\n\n- 覆盖索引\n";
    var body = ImportedNotes.body("md", markdown, List.of());
    var parents = StructuredChunks.split(Json.write(body));
    assertEquals("数据库 / 索引", parents.getFirst().headingPath());
    assertTrue(
        parents.getFirst().children().stream()
            .anyMatch(c -> c.kind().equals("code") && c.content().contains("select 2;")));
    assertTrue(parents.getFirst().children().stream().anyMatch(c -> c.kind().equals("list")));
    var pdf =
        ImportedNotes.body(
            "pdf",
            "",
            List.of(
                new DocumentParser.Segment("1", 0, "第 1 页 · 1", "SELF", "第一页正文"),
                new DocumentParser.Segment("2", 1, "第 2 页 · 2", "SELF", "第二页正文")));
    assertEquals(
        List.of("第 1 页", "第 2 页"),
        StructuredChunks.split(Json.write(pdf)).stream()
            .map(StructuredChunks.Parent::headingPath)
            .toList());
  }
}
