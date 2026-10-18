package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import cyou.jujube.common.ApiException;
import cyou.jujube.ingestion.DocumentParser;
import cyou.jujube.retrieval.TextIndex;
import cyou.jujube.security.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证资料解析、说话者归属及密码学工具的边界，使用临时文件和固定测试输入。 重点防止他人聊天被归入本人，以及非法文档或篡改密文被误接受。 */
class ParserAndSecurityTest {
  @TempDir Path temp;
  final DocumentParser parser = new DocumentParser();

  @Test
  void totpMatchesRfc6238AndRejectsMalformedInput() {
    String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    assertEquals("287082", Totp.code(secret, 1));
    assertEquals(1, Totp.matchingStep(secret, "287082", 59_000));
    assertEquals(-1, Totp.matchingStep(secret, "00000", 59_000));
  }

  @Test
  void protectedSecretRequiresCorrectKey() {
    String first = Base64.getEncoder().encodeToString(new byte[32]);
    byte[] different = new byte[32];
    different[0] = 1;
    String protectedValue = Crypto.encrypt("fixture-secret", first);
    assertFalse(protectedValue.contains("fixture-secret"));
    assertEquals("fixture-secret", Crypto.decrypt(protectedValue, first));
    assertThrows(
        ApiException.class,
        () -> Crypto.decrypt(protectedValue, Base64.getEncoder().encodeToString(different)));
  }

  @Test
  void chineseShortWordsAndTechnicalIdentifiersHaveSearchTokens() {
    var tokens = TextIndex.tokens("我使用订单幂等与 ConcurrentHashMap、Java、agent_api");
    assertTrue(tokens.contains("幂等"));
    assertTrue(tokens.contains("订单"));
    assertTrue(tokens.contains("concurrenthashmap"));
    assertTrue(tokens.contains("agent"));
    assertTrue(tokens.contains("api"));
  }

  @Test
  void vectorRoundTripAndDimensionGuard() {
    float[] vector = {1, 2, 3};
    assertArrayEquals(vector, TextIndex.decode(TextIndex.encode(vector)));
    assertEquals(1, TextIndex.similarity(vector, vector), 0.00001);
    assertThrows(
        IllegalArgumentException.class, () -> TextIndex.similarity(vector, new float[] {1}));
  }

  @Test
  void markdownAndTextAreParsedWithoutOcr() throws Exception {
    for (String format : new String[] {"md", "txt"}) {
      Path path = temp.resolve("note." + format);
      Files.writeString(path, "# Java 学习\n我了解线程池。", StandardCharsets.UTF_8);
      var segments = parser.parse(path, format, DocumentParser.Mapping.from("{}"));
      assertEquals("SELF", segments.getFirst().speaker());
      assertTrue(segments.getFirst().content().contains("线程池"));
    }
  }

  @Test
  void textPdfRetainsPageReference() throws Exception {
    Path path = temp.resolve("sample.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      try (var stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(40, 700);
        stream.showText("Java order API project");
        stream.endText();
      }
      document.save(path.toFile());
    }
    var segments = parser.parse(path, "pdf", DocumentParser.Mapping.from("{}"));
    assertTrue(segments.getFirst().label().contains("第 1 页"));
    assertTrue(segments.getFirst().content().contains("Java"));
  }

  @Test
  void docxTextIsParsedWithoutExtractingArbitraryZipPaths() throws Exception {
    Path path = temp.resolve("sample.docx");
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write(
          "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Java项目经历</w:t></w:r></w:p></w:body></w:document>"
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    assertTrue(
        parser
            .parse(path, "docx", DocumentParser.Mapping.from("{}"))
            .getFirst()
            .content()
            .contains("Java项目"));
  }

  @Test
  void jsonChatCannotGuessOwner() throws Exception {
    Path path = temp.resolve("chat.json");
    Files.writeString(path, "[{\"speaker\":\"other\",\"content\":\"我做过运维\"}]");
    assertEquals(
        "SPEAKER_MAPPING_REQUIRED",
        assertThrows(
                ApiException.class,
                () -> parser.parse(path, "json", DocumentParser.Mapping.from("{}")))
            .code());
    assertEquals(
        "OTHER",
        parser
            .parse(path, "json", DocumentParser.Mapping.from("{\"selfSpeaker\":\"me\"}"))
            .getFirst()
            .speaker());
  }

  @Test
  void emptyPdfDoesNotPretendOcrSucceeded() throws Exception {
    Path path = temp.resolve("empty.pdf");
    try (var document = new PDDocument()) {
      document.addPage(new PDPage());
      document.save(path.toFile());
    }
    assertEquals(
        "NO_TEXT",
        assertThrows(
                ApiException.class,
                () -> parser.parse(path, "pdf", DocumentParser.Mapping.from("{}")))
            .code());
  }
}
