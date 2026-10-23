package cyou.jujube.ingestion;

import cyou.jujube.common.ApiException;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.config.OperationsProperties;
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
  private final OperationsProperties limits;

  public SourceService(Database db, CloneProperties props, Clock clock) {
    this(db, props, clock, OperationsProperties.defaults());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public SourceService(
      Database db, CloneProperties props, Clock clock, OperationsProperties limits) {
    this.db = db;
    this.props = props;
    this.clock = clock;
    this.limits = limits;
  }

  public void checkStorage() {
    long bytes =
        db.app().queryForObject("PRAGMA page_count", Long.class)
            * db.app().queryForObject("PRAGMA page_size", Long.class);
    if (bytes >= limits.maxDatabaseMb() * 1024 * 1024)
      throw ApiException.unavailable("DATABASE_CAPACITY_REACHED", "数据库达到当前容量上限，请先清理历史版本或调整经过验证的容量");
    try {
      if (Files.getFileStore(props.root()).getUsableSpace() < limits.minFreeDiskMb() * 1024 * 1024)
        throw ApiException.unavailable("DISK_SPACE_LOW", "存储空间不足，已暂停资料处理，请管理员检查");
    } catch (IOException ex) {
      throw ApiException.unavailable("STORAGE_UNAVAILABLE", "无法访问资料存储");
    }
  }

  /** 校验文件和字段映射后以随机名称落盘，再创建资料记录和解析任务；数据库失败时清理本次文件。 */
  public synchronized Map<String, Object> upload(MultipartFile file, String mapping)
      throws IOException {
    checkStorage();
    if (db.app().queryForObject("SELECT COUNT(*) FROM sources", Long.class) >= limits.maxSources())
      throw ApiException.badRequest("SOURCE_CAPACITY_REACHED", "资料数量达到上限，请先清理历史资料或调整容量");
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
    long bytes = 0;
    try (var paths = Files.list(directory)) {
      for (Path path :
          paths
              .filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
              .toList()) bytes += Files.size(path);
    }
    if (bytes + content.length > limits.maxSourceMb() * 1024 * 1024)
      throw ApiException.badRequest("SOURCE_CAPACITY_REACHED", "资料总大小达到上限，请先清理历史资料或调整容量");
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
    if (!Set.of("IMPORT", "EXTRACT", "PUBLISH").contains(type))
      throw new IllegalArgumentException("Unknown job type");
    var existing =
        db.app()
            .queryForList(
                "SELECT id FROM jobs WHERE type=? AND subject_id=? AND status IN ('QUEUED','RUNNING','PAUSED')",
                String.class,
                type,
                subject);
    if (!existing.isEmpty()) return existing.getFirst();
    String id = UUID.randomUUID().toString();
    int inserted =
        db.app()
            .update(
                "INSERT OR IGNORE INTO jobs(id,type,subject_id,status,stage,created_at,updated_at) SELECT ?,?,?,'QUEUED','QUEUED',?,? WHERE (SELECT COUNT(*) FROM jobs WHERE status IN ('QUEUED','RUNNING','PAUSED'))<?",
                id,
                type,
                subject,
                clock.millis(),
                clock.millis(),
                limits.maxQueuedJobs());
    if (inserted == 0) {
      var active =
          db.app()
              .queryForList(
                  "SELECT id FROM jobs WHERE type=? AND subject_id=? AND status IN ('QUEUED','RUNNING','PAUSED')",
                  String.class,
                  type,
                  subject);
      if (!active.isEmpty()) return active.getFirst();
      throw new ApiException(429, "QUEUE_FULL", "后台任务已达上限，请等待任务完成或处理暂停任务");
    }
    return id;
  }

  /** 只允许失败或暂停的任务重新入队，保留已有游标；不自动重试可能产生费用的操作。 */
  public void retry(String id) {
    if (db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM ai_calls WHERE request_id=? AND reserved>0 AND status IN ('RESERVED','UNCERTAIN')",
                Long.class,
                id)
        > 0) throw new ApiException(409, "RECONCILIATION_REQUIRED", "请先核对该任务的不确定费用，再主动重试");
    var job = Database.required(db.app(), "SELECT type,subject_id FROM jobs WHERE id=?", id);
    Database.required(
        db.app(),
        "SELECT id FROM "
            + ("PUBLISH".equals(job.get("type")) ? "releases" : "sources")
            + " WHERE id=?",
        job.get("subject_id"));
    if (db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE type=? AND subject_id=? AND id!=? AND status IN ('QUEUED','RUNNING','PAUSED')",
                Long.class,
                job.get("type"),
                job.get("subject_id"),
                id)
        > 0) throw new ApiException(409, "JOB_ALREADY_ACTIVE", "同一对象已有活动任务，请查看已有任务");
    int count =
        db.app()
            .update(
                "UPDATE jobs SET status='QUEUED',error_code=NULL,updated_at=? WHERE id=? AND status IN ('FAILED','PAUSED') AND (status='PAUSED' OR (SELECT COUNT(*) FROM jobs WHERE status IN ('QUEUED','RUNNING','PAUSED'))<?) AND NOT EXISTS(SELECT 1 FROM jobs active WHERE active.type=jobs.type AND active.subject_id=jobs.subject_id AND active.id!=jobs.id AND active.status IN ('QUEUED','RUNNING','PAUSED'))",
                clock.millis(),
                id,
                limits.maxQueuedJobs());
    if (count != 1) throw ApiException.badRequest("JOB_NOT_RETRYABLE", "任务不存在或当前不能重试");
  }

  public record CandidateUpdate(
      String kind, String content, String mastery, boolean approved, Long revision) {
    public CandidateUpdate(String kind, String content, String mastery, boolean approved) {
      this(kind, content, mastery, approved, null);
    }
  }

  public void cancel(String id) {
    if (db.app()
            .update(
                "UPDATE jobs SET status='CANCELLED',updated_at=? WHERE id=? AND status IN ('QUEUED','PAUSED')",
                clock.millis(),
                id)
        != 1) throw new ApiException(409, "JOB_NOT_CANCELLABLE", "只能取消等待或暂停的任务，已开始的调用不能取消");
  }

  /** 只有不再被任何版本引用的原件可删除；移动暂存后再提交数据库，失败时还原文件。 */
  public synchronized void delete(String id) throws IOException {
    var source = Database.required(db.app(), "SELECT file_name FROM sources WHERE id=?", id);
    String name = Database.text(source, "file_name");
    if (!name.matches("[a-f0-9-]{36}\\.(md|txt|pdf|docx|json)"))
      throw new IOException("Invalid stored source name");
    Path original = props.root().resolve("sources").resolve(name),
        trash = props.root().resolve("source-trash").resolve(name);
    Files.createDirectories(trash.getParent());
    boolean moved = Files.exists(original);
    if (moved) {
      Files.move(original, trash);
      Files.setLastModifiedTime(trash, java.nio.file.attribute.FileTime.fromMillis(clock.millis()));
    }
    try {
      db.transaction(
          sql -> {
            sql.update(
                "UPDATE jobs SET status='CANCELLED',error_code='SUBJECT_REMOVED',updated_at=? WHERE subject_id=? AND type IN ('IMPORT','EXTRACT') AND status IN ('QUEUED','PAUSED','FAILED')",
                clock.millis(),
                id);
            if (sql.queryForObject(
                    "SELECT COUNT(*) FROM release_records WHERE source_id=?", Long.class, id)
                > 0) throw new ApiException(409, "SOURCE_REFERENCED", "资料仍被发布版本引用，请先删除不再需要的历史版本");
            if (sql.queryForObject(
                    "SELECT COUNT(*) FROM jobs WHERE subject_id=? AND status='RUNNING'",
                    Long.class,
                    id)
                > 0) throw new ApiException(409, "SOURCE_BUSY", "资料任务正在运行，请等待完成后再删除");
            sql.update("DELETE FROM candidates WHERE source_id=?", id);
            sql.update("DELETE FROM segments WHERE source_id=?", id);
            sql.update("DELETE FROM sources WHERE id=?", id);
            return null;
          });
    } catch (RuntimeException ex) {
      if (moved) Files.move(trash, original);
      throw ex;
    }
    if (moved) {
      try {
        Files.delete(trash);
      } catch (IOException ex) {
        org.slf4j.LoggerFactory.getLogger(SourceService.class)
            .warn("stage=STORAGE event=TRASH_CLEANUP_REQUIRED");
      }
    }
  }

  /** 校验记录类型、掌握深度、正文及本人归属，再保存人工审阅结果；批准不等于立即发布。 */
  public long updateCandidate(String id, CandidateUpdate update) {
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
    long expected =
        update.revision() == null ? Database.number(record, "revision") : update.revision();
    // 条件写入把撤回校验与保存合并，防止校验之后发生撤回仍重新批准记录。
    if (db.app()
            .update(
                "UPDATE candidates SET kind=?,content=?,mastery=?,approved=?,manually_edited=1,revision=revision+1,updated_at=? WHERE id=? AND withdrawn=0 AND revision=?",
                update.kind(),
                update.content(),
                update.mastery(),
                update.approved() ? 1 : 0,
                clock.millis(),
                id,
                expected)
        != 1) {
      boolean withdrawn =
          db.app().queryForObject("SELECT withdrawn FROM candidates WHERE id=?", Integer.class, id)
              == 1;
      throw new ApiException(
          409,
          withdrawn ? "CANDIDATE_WITHDRAWN" : "EDIT_CONFLICT",
          withdrawn ? "该记录已永久撤回，不能再编辑或确认，请刷新资料" : "资料已被其他操作修改，请保留当前编辑并刷新核对");
    }
    return expected + 1;
  }

  /** 标记为永久撤回并取消批准，所有版本检索及答案返回前检查都会排除它。 */
  public void withdraw(String id) {
    if (db.app()
            .update(
                "UPDATE candidates SET withdrawn=1,approved=0,revision=revision+1,updated_at=? WHERE id=?",
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
