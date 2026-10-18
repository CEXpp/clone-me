package cyou.jujube.ingestion;

import cyou.jujube.common.ApiException;
import cyou.jujube.common.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {
  public record Segment(String id, int ordinal, String label, String speaker, String content) {}

  public record Mapping(String textField, String speakerField, String selfSpeaker) {
    public static Mapping from(String json) {
      var value = Json.read(json);
      return new Mapping(
          value.path("textField").asText("content"),
          value.path("speakerField").asText("speaker"),
          value.path("selfSpeaker").asText(""));
    }
  }

  public List<Segment> parse(Path path, String format, Mapping mapping) throws Exception {
    if (Files.size(path) > 10 * 1024 * 1024)
      throw ApiException.badRequest("FILE_TOO_LARGE", "文件超过 10MB，请拆分后上传");
    List<Segment> result = new ArrayList<>();
    switch (format) {
      case "md", "txt" ->
          split(Files.readString(path, StandardCharsets.UTF_8), "正文", "SELF", result);
      case "pdf" -> {
        try (var pdf = Loader.loadPDF(path.toFile())) {
          if (pdf.isEncrypted()) throw ApiException.badRequest("ENCRYPTED_PDF", "请上传未加密的文字 PDF");
          if (pdf.getNumberOfPages() > 150)
            throw ApiException.badRequest("TOO_MANY_PAGES", "PDF 超过 150 页，请拆分");
          var text = new PDFTextStripper();
          for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
            text.setStartPage(page);
            text.setEndPage(page);
            split(text.getText(pdf), "第 " + page + " 页", "SELF", result);
          }
        }
      }
      case "docx" -> split(docx(path), "DOCX 正文", "SELF", result);
      case "json" -> json(path, mapping, result);
      default ->
          throw ApiException.badRequest("UNSUPPORTED_FORMAT", "仅支持 md、txt、文字 pdf、docx 和 json");
    }
    if (result.isEmpty()) throw ApiException.badRequest("NO_TEXT", "未提取到文字；首版不支持扫描件或图片识别");
    return List.copyOf(result);
  }

  private void json(Path file, Mapping mapping, List<Segment> out) throws IOException {
    var root = Json.read(Files.readString(file, StandardCharsets.UTF_8));
    var messages = root.isArray() ? root : root.path("messages");
    if (!messages.isArray()) messages = Json.read("[" + Json.write(root) + "]");
    if (messages.size() > 3000)
      throw ApiException.badRequest("TOO_MANY_MESSAGES", "JSON 消息过多，请拆分导入");
    int count = 0;
    for (var message : messages) {
      count++;
      var speaker = message.path(mapping.speakerField());
      boolean hasSpeaker = !speaker.isMissingNode() && !speaker.isNull();
      if (hasSpeaker && mapping.selfSpeaker().isBlank())
        throw ApiException.badRequest(
            "SPEAKER_MAPPING_REQUIRED", "聊天 JSON 需要指定 selfSpeaker，确认哪位说话者是本人");
      String ownership =
          hasSpeaker ? (mapping.selfSpeaker().equals(speaker.asText()) ? "SELF" : "OTHER") : "SELF";
      var text = message.path(mapping.textField());
      if (!text.isTextual())
        throw ApiException.badRequest("JSON_MAPPING_REQUIRED", "内容字段需为字符串，请检查 textField 字段映射");
      split(text.asText(), "消息 " + count, ownership, out);
    }
  }

  private String docx(Path file) throws Exception {
    try (var zip = new ZipInputStream(Files.newInputStream(file))) {
      ZipEntry entry;
      int count = 0;
      while ((entry = zip.getNextEntry()) != null) {
        if (++count > 1000) throw ApiException.badRequest("ARCHIVE_TOO_COMPLEX", "DOCX 结构超过解析限制");
        if (!"word/document.xml".equals(entry.getName())) continue;
        byte[] xml = zip.readNBytes(2_000_001);
        if (xml.length > 2_000_000)
          throw ApiException.badRequest("DOCUMENT_TOO_LARGE", "DOCX 正文超过解析限制");
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml), "UTF-8");
        StringBuilder text = new StringBuilder();
        boolean inText = false;
        try {
          while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
              String local = reader.getLocalName();
              if ("t".equals(local)) inText = true;
              if ("tab".equals(local)) text.append('\t');
            }
            if (event == XMLStreamConstants.CHARACTERS && inText) text.append(reader.getText());
            if (event == XMLStreamConstants.END_ELEMENT) {
              String local = reader.getLocalName();
              if ("t".equals(local)) inText = false;
              if ("p".equals(local) || "tr".equals(local)) text.append('\n');
            }
          }
        } finally {
          reader.close();
        }
        return text.toString();
      }
    }
    throw ApiException.badRequest("INVALID_DOCX", "DOCX 缺少正文结构");
  }

  private void split(String raw, String label, String speaker, List<Segment> result) {
    if (raw.indexOf('\u0000') >= 0) throw ApiException.badRequest("BINARY_TEXT", "文本包含二进制内容");
    String text = raw.replace("\r\n", "\n").strip();
    int offset = 0;
    while (offset < text.length()) {
      int end = Math.min(text.length(), offset + 1200);
      if (end < text.length()) {
        int boundary = text.lastIndexOf('\n', end);
        if (boundary > offset + 400) end = boundary;
      }
      String part = text.substring(offset, end).strip();
      if (!part.isEmpty())
        result.add(
            new Segment(
                UUID.randomUUID().toString(),
                result.size(),
                label + " · " + (result.size() + 1),
                speaker,
                part));
      offset = end;
      if (result.size() > 600)
        throw ApiException.badRequest("TOO_MANY_SEGMENTS", "资料超过单次解析限制，请拆分上传");
    }
  }
}
