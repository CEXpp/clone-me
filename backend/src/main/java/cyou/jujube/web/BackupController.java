package cyou.jujube.web;

import cyou.jujube.operations.BackupService;
import java.io.IOException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 受管理员认证保护的备份列表、创建和下载接口。 文件路径由 BackupService 按固定格式校验，接口不能接收任意磁盘路径。 */
@RestController
@RequestMapping("/api/admin/backups")
public class BackupController {
  private final BackupService backups;

  public BackupController(BackupService backups) {
    this.backups = backups;
  }

  @GetMapping
  Object list() throws IOException {
    return backups.list();
  }

  @PostMapping
  Object create() throws IOException {
    return backups.create();
  }

  @GetMapping("/{name}")
  ResponseEntity<FileSystemResource> download(@PathVariable String name) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .body(new FileSystemResource(backups.archive(name)));
  }
}
