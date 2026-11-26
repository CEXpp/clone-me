package cyou.jujube.notes;

import cyou.jujube.common.*;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.ingestion.DocumentParser;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.Database;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/** 正文是唯一编辑源；历史、全文搜索和 RAG 均从已保存正文派生。 */
@Service
public class NoteService {
  private final Database db;
  private final CloneProperties props;
  private final DocumentParser parser;

  public NoteService(Database db, CloneProperties props, DocumentParser parser) {
    this.db = db;
    this.props = props;
    this.parser = parser;
  }

  public record Edit(
      String sectionId,
      String title,
      JsonNode body,
      long revision,
      boolean knowledge,
      boolean favorite,
      boolean deleted) {}

  public Object library(String query) {
    if (query == null || query.length() > 200)
      throw ApiException.badRequest("INVALID_QUERY", "搜索最多 200 字符");
    return Map.of(
        "notebooks",
        db.app().queryForList("SELECT * FROM notebooks ORDER BY position,name"),
        "sections",
        db.app().queryForList("SELECT * FROM note_sections ORDER BY position,name"),
        "notes",
        db.app()
            .queryForList(
                "SELECT id,section_id,title,revision,knowledge,favorite,deleted,indexed_revision,index_error,updated_at,substr(plain_text,1,100) AS excerpt FROM notes WHERE instr(lower(title||char(10)||plain_text),lower(?))>0 ORDER BY updated_at DESC",
                query));
  }

  public Map<String, Object> get(String id) {
    var row =
        new LinkedHashMap<>(Database.required(db.app(), "SELECT * FROM notes WHERE id=?", id));
    row.put("body", Json.read(Database.text(row, "body")));
    row.put(
        "assets",
        db.app().queryForList("SELECT id,name,mime,bytes FROM note_assets WHERE note_id=?", id));
    return row;
  }

  public Object category(String notebookId, String name) {
    name = title(name);
    String id = UUID.randomUUID().toString();
    if (notebookId == null) db.app().update("INSERT INTO notebooks(id,name) VALUES(?,?)", id, name);
    else {
      Database.required(db.app(), "SELECT id FROM notebooks WHERE id=?", notebookId);
      db.app()
          .update(
              "INSERT INTO note_sections(id,notebook_id,name) VALUES(?,?,?)", id, notebookId, name);
    }
    return Map.of("id", id);
  }

  public Object create(String sectionId, String name) {
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    Database.required(db.app(), "SELECT id FROM note_sections WHERE id=?", sectionId);
    db.app()
        .update(
            "INSERT INTO notes(id,section_id,title,body,created_at,updated_at) VALUES(?,?,?,'{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}',?,?)",
            id,
            sectionId,
            title(name),
            now,
            now);
    return get(id);
  }

  public Object save(String id, Edit edit) {
    if (edit.body() == null
        || !"doc".equals(edit.body().path("type").asText())
        || Json.write(edit.body()).length() > 1_000_000)
      throw ApiException.badRequest("INVALID_NOTE", "笔记正文格式无效或超过容量");
    validate(edit.body(), 0);
    String body = Json.write(edit.body()), text = plain(edit.body());
    db.transaction(
        sql -> {
          var old = Database.required(sql, "SELECT * FROM notes WHERE id=?", id);
          Database.required(sql, "SELECT id FROM note_sections WHERE id=?", edit.sectionId());
          if (Database.number(old, "revision") != edit.revision())
            throw new ApiException(409, "NOTE_CONFLICT", "笔记已在其他页面修改；本地草稿已保留，请重新打开后合并");
          sql.update(
              "INSERT INTO note_history VALUES(?,?,?,?,?)",
              id,
              edit.revision(),
              old.get("title"),
              old.get("body"),
              System.currentTimeMillis());
          sql.update(
              "UPDATE notes SET section_id=?,title=?,body=?,plain_text=?,knowledge=?,favorite=?,deleted=?,revision=revision+1,index_error='',updated_at=? WHERE id=?",
              edit.sectionId(),
              title(edit.title()),
              body,
              text,
              edit.knowledge() ? 1 : 0,
              edit.favorite() ? 1 : 0,
              edit.deleted() ? 1 : 0,
              System.currentTimeMillis(),
              id);
          return null;
        });
    return get(id);
  }

  public Object history(String id) {
    get(id);
    return db.app()
        .queryForList(
            "SELECT revision,title,created_at FROM note_history WHERE note_id=? ORDER BY revision DESC",
            id);
  }

  public Object restore(String id, long version, long expected) {
    var old =
        Database.required(
            db.app(), "SELECT * FROM note_history WHERE note_id=? AND revision=?", id, version);
    var current = get(id);
    return save(
        id,
        new Edit(
            Database.text(current, "section_id"),
            Database.text(old, "title"),
            Json.read(Database.text(old, "body")),
            expected,
            Database.number(current, "knowledge") == 1,
            Database.number(current, "favorite") == 1,
            Database.number(current, "deleted") == 1));
  }

  public Path assetPath(String id) {
    var asset = asset(id);
    return props.root().resolve("assets").resolve(Database.text(asset, "file_name"));
  }

  public Map<String, Object> asset(String id) {
    return Database.required(db.app(), "SELECT * FROM note_assets WHERE id=?", id);
  }

  public Object uploadAsset(String note, MultipartFile file) throws Exception {
    get(note);
    if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
      throw ApiException.badRequest("FILE_TOO_LARGE", "文件须为 1 字节至 10MB");
    String id = UUID.randomUUID().toString();
    Path dir = props.root().resolve("assets");
    Files.createDirectories(dir);
    Path target = dir.resolve(id);
    file.transferTo(target);
    String mime = Files.probeContentType(target);
    // 图片通过文件头检测；SVG/HTML 等主动内容始终作为下载附件。
    byte[] head;
    try (var input = Files.newInputStream(target)) {
      head = input.readNBytes(12);
    }
    if (head.length >= 8
        && head[0] == (byte) 0x89
        && head[1] == 80
        && head[2] == 78
        && head[3] == 71) mime = "image/png";
    else if (head.length >= 3
        && head[0] == (byte) 0xff
        && head[1] == (byte) 0xd8
        && head[2] == (byte) 0xff) mime = "image/jpeg";
    else mime = "application/octet-stream";
    try {
      db.app()
          .update(
              "INSERT INTO note_assets VALUES(?,?,?,?,?,?,?)",
              id,
              note,
              Objects.toString(file.getOriginalFilename(), "附件"),
              id,
              Crypto.hash(target),
              mime,
              file.getSize());
    } catch (RuntimeException ex) {
      Files.deleteIfExists(target);
      throw ex;
    }
    return Map.of(
        "id",
        id,
        "name",
        Objects.toString(file.getOriginalFilename(), "附件"),
        "url",
        "/api/admin/note-assets/" + id,
        "mime",
        mime);
  }

  public Object importFile(String section, boolean knowledge, MultipartFile file) throws Exception {
    String name = Objects.toString(file.getOriginalFilename(), "笔记.txt");
    String format = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (!Set.of("md", "txt", "pdf", "docx").contains(format))
      throw ApiException.badRequest("UNSUPPORTED_FORMAT", "支持 Markdown、TXT、文字 PDF、DOCX");
    if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
      throw ApiException.badRequest("FILE_TOO_LARGE", "文件最大 10MB");
    Path temp = Files.createTempFile(props.root(), "note-import-", "." + format);
    try {
      file.transferTo(temp);
      var segments =
          parser.parse(temp, format, new DocumentParser.Mapping("content", "speaker", ""));
      var body =
          ImportedNotes.body(
              format,
              "md".equals(format)
                  ? Files.readString(temp, java.nio.charset.StandardCharsets.UTF_8)
                  : "",
              segments);
      var note = (Map<?, ?>) create(section, name);
      String id = note.get("id").toString();
      try {
        save(id, new Edit(section, name, body, 1, knowledge, false, false));
        uploadAsset(id, file);
        return get(id);
      } catch (Exception ex) {
        db.transaction(
            sql -> {
              sql.update("DELETE FROM note_history WHERE note_id=?", id);
              sql.update("DELETE FROM notes WHERE id=?", id);
              return null;
            });
        throw ex;
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static String title(String s) {
    if (s == null || s.isBlank() || s.length() > 200)
      throw ApiException.badRequest("INVALID_TITLE", "名称需为 1–200 个字符");
    return s.strip();
  }

  private static void validate(JsonNode node, int depth) {
    if (depth > 40) throw ApiException.badRequest("INVALID_NOTE", "笔记层级过深");
    if (node.path("type").asText().equals("image")
        && !node.path("attrs").path("src").asText().matches("/api/admin/note-assets/[a-f0-9-]{36}"))
      throw ApiException.badRequest("INVALID_IMAGE", "请上传图片后插入");
    for (var child : node.path("content")) validate(child, depth + 1);
  }

  public static String plain(JsonNode node) {
    var out = new StringBuilder();
    if (node.path("text").isTextual()) out.append(node.path("text").asText());
    for (var child : node.path("content")) out.append(plain(child));
    if (Set.of("paragraph", "heading", "tableRow", "codeBlock", "listItem", "hardBreak")
        .contains(node.path("type").asText())) out.append('\n');
    if ("tableCell".equals(node.path("type").asText())) out.append(" | ");
    return out.toString();
  }
}
