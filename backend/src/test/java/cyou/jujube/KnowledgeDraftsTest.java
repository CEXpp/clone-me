package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import cyou.jujube.ingestion.KnowledgeDrafts;
import org.junit.jupiter.api.Test;

class KnowledgeDraftsTest {
  @Test
  void contentsAreNotKnowledgeButShortExplainedListsAre() {
    String toc =
        "目录\n"
            + java.util.stream.IntStream.rangeClosed(1, 16)
                .mapToObj(i -> "1." + i + " Java 核心知识")
                .collect(java.util.stream.Collectors.joining("\n"));
    assertTrue(KnowledgeDrafts.build(toc, "第 1 页").isEmpty());
    assertFalse(KnowledgeDrafts.contents("1.1 缓存\n缓存用于减少重复计算。\n1.2 索引\n索引用于加快查找速度。"));
  }

  @Test
  void splitsTopicsAndRetainsCodeWithPageAnchor() {
    var drafts =
        KnowledgeDrafts.build(
            "11\n1.1 字符串比较\n使用 equals 比较字符串内容，比较引用使用 ==。\n1.2 接口\n接口可用于描述行为规范，支持多个接口实现。\n1\n2\n3\n4\n5",
            "第 11 页 · 11");
    assertEquals(2, drafts.size());
    assertTrue(drafts.getFirst().contains("equals"));
    assertFalse(drafts.getFirst().startsWith("11"));
    assertFalse(drafts.get(1).contains("\n1\n2"));
  }

  @Test
  void standaloneValuesAndProseArePreserved() {
    assertEquals("缓存上限\n127\n这是配置说明", KnowledgeDrafts.clean("缓存上限\n127\n这是配置说明"));
    assertEquals(1, KnowledgeDrafts.build("我使用 Java 编写了个人项目并实现了接口。", "正文").size());
    assertTrue(KnowledgeDrafts.build("1.1 只有标题", "正文").isEmpty());
    assertEquals(
        "public class Demo {\n    int value = 127;\n\n}",
        KnowledgeDrafts.clean("1 public class Demo {\n2     int value = 127;\n3\n4 }"));
  }
}
