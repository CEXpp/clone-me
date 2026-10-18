package cyou.jujube.ingestion;

import cyou.jujube.common.ApiException;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.security.Crypto;
import cyou.jujube.storage.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理资料上传、后台任务入队、候选记录审阅和紧急撤回。 上传文件保存为随机名称，数据库保留展示名及原文关系；文件和数据库提交失败时清理本次孤立文件。
 * 候选内容只有本人确认且归属、类型、掌握程度合规后才有资格进入新发布版本。
 */
@Service
public class SourceService {
  private final Database db;
  private final CloneProperties props;
  private final Clock clock;

  public SourceService(Database db, CloneProperties props, Clock clock) {
    this.db = db;
    this.props = props;
    this.clock = clock;
  }

  /** 校验文件和字段映射后以随机名称落盘，再创建资料记录和解析任务；数据库失败时清理本次文件。 */
  public Map<String, Object> upload(MultipartFile file, String mapping) throws IOException {
    String name = file.getOriginalFilename();
    if (name == null || name.length() > 200 || file.isEmpty())
      throw ApiException.badRequest("INVALID_FILE", "文件为空或名称无效");
    name = name.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    String format =
        name.contains(".")
            ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "";
    if (!Set.of("md", "txt", "pdf", "docx", "json").contains(format))
      throw ApiException.badRequest("UNSUPPORTED_FORMAT", "仅支持 md、txt、文字 pdf、docx 和 json");
    if (file.getSize() > 10 * 1024 * 1024)
      throw ApiException.badRequest("FILE_TOO_LARGE", "文件超过 10MB");
    String config = mapping == null ? "{}" : mapping;
    DocumentParser.Mapping.from(config);
    byte[] content = file.getBytes();
    String id = UUID.randomUUID().toString(), stored = id + "." + format;
    Path directory = props.root().resolve("sources");
    Files.createDirectories(directory);
    Files.write(directory.resolve(stored), content);
    String original = name;
    String job;
    try {
      job =
          db.transaction(
              sql -> {
                sql.update(
                    "INSERT INTO sources(id,name,file_name,hash,format,mapping_json,status,created_at) VALUES(?,?,?,?,?,?,'QUEUED',?)",
                    id,
                    original,
                    stored,
                    Crypto.hash(content),
                    format,
                    config,
                    clock.millis());
                return enqueue("IMPORT", id);
              });
    } catch (RuntimeException ex) {
      Files.deleteIfExists(directory.resolve(stored));
      throw ex;
    }
    return Map.of("sourceId", id, "jobId", job, "status", "QUEUED");
  }

  /** 把任务写入数据库队列；被外层应用库事务调用时与业务提交一起成功或回滚。 */
  public String enqueue(String type, String subject) {
    String id = UUID.randomUUID().toString();
    db.app()
        .update(
            "INSERT INTO jobs(id,type,subject_id,status,stage,created_at,updated_at) VALUES(?,?,?,'QUEUED','QUEUED',?,?)",
            id,
            type,
            subject,
            clock.millis(),
            clock.millis());
    return id;
  }

  /** 只允许失败或暂停的任务重新入队，保留已有游标；不自动重试可能产生费用的操作。 */
  public void retry(String id) {
    int count =
        db.app()
            .update(
                "UPDATE jobs SET status='QUEUED',error_code=NULL,updated_at=? WHERE id=? AND status IN ('FAILED','PAUSED')",
                clock.millis(),
                id);
    if (count != 1) throw ApiException.badRequest("JOB_NOT_RETRYABLE", "任务不存在或当前不能重试");
  }

  public record CandidateUpdate(String kind, String content, String mastery, boolean approved) {}

  /** 校验记录类型、掌握深度、正文及本人归属，再保存人工审阅结果；批准不等于立即发布。 */
  public void updateCandidate(String id, CandidateUpdate update) {
    if (update.kind() == null
        || update.mastery() == null
        || !Set.of("REFERENCE", "KNOWLEDGE", "EXPERIENCE", "VIEWPOINT", "STYLE")
            .contains(update.kind())
        || !Set.of("UNCONFIRMED", "CONFIRMED", "STUDIED", "UNDERSTOOD", "USED")
            .contains(update.mastery())
        || update.content() == null
        || update.content().isBlank()
        || update.content().length() > 1800)
      throw ApiException.badRequest("INVALID_CANDIDATE", "请检查记录类型、掌握程度与正文长度");
    var record =
        Database.required(
            db.app(),
            "SELECT c.*,s.speaker FROM candidates c JOIN segments s ON s.id=c.segment_id WHERE c.id=?",
            id);
    if (update.approved()
        && ("OTHER".equals(record.get("speaker"))
            || "REFERENCE".equals(update.kind())
            || "UNCONFIRMED".equals(update.mastery())))
      throw ApiException.badRequest("CONFIRMATION_REQUIRED", "只能批准本人归属明确、类型与掌握范围已确认的记录");
    db.app()
        .update(
            "UPDATE candidates SET kind=?,content=?,mastery=?,approved=?,updated_at=? WHERE id=?",
            update.kind(),
            update.content(),
            update.mastery(),
            update.approved() ? 1 : 0,
            clock.millis(),
            id);
  }

  /** 标记为永久撤回并取消批准，所有版本检索及答案返回前检查都会排除它。 */
  public void withdraw(String id) {
    if (db.app()
            .update(
                "UPDATE candidates SET withdrawn=1,approved=0,updated_at=? WHERE id=?",
                clock.millis(),
                id)
        != 1) throw ApiException.missing();
  }

  public List<Map<String, Object>> sources() {
    return db.app()
        .queryForList(
            "SELECT id,name,hash,format,status,created_at,error_code FROM sources ORDER BY created_at DESC LIMIT 200");
  }

  public Map<String, Object> source(String id) {
    var source =
        Database.required(
            db.app(),
            "SELECT id,name,format,status,mapping_json,error_code FROM sources WHERE id=?",
            id);
    return Map.of(
        "source",
        source,
        "segments",
        db.app()
            .queryForList(
                "SELECT id,ordinal,label,speaker,content FROM segments WHERE source_id=? ORDER BY ordinal",
                id),
        "candidates",
        db.app()
            .queryForList("SELECT * FROM candidates WHERE source_id=? ORDER BY updated_at,id", id));
  }
}
