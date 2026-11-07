package cyou.jujube.notes;

import cyou.jujube.storage.Database;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class NoteController {
  private final NoteService notes;
  private final NoteIndex index;
  private final Database db;

  public NoteController(NoteService notes, NoteIndex index, Database db) {
    this.notes = notes;
    this.index = index;
    this.db = db;
  }

  @GetMapping("/notes")
  Object library(@RequestParam(defaultValue = "") String query) {
    return notes.library(query);
  }

  public record Category(String notebookId, String name) {}

  @PostMapping("/note-categories")
  Object category(@RequestBody Category edit) {
    return notes.category(edit.notebookId(), edit.name());
  }

  public record Create(String sectionId, String title) {}

  @PostMapping("/notes")
  Object create(@RequestBody Create edit) {
    return notes.create(edit.sectionId(), edit.title());
  }

  @GetMapping("/notes/{id}")
  Object get(@PathVariable String id) {
    return notes.get(id);
  }

  @PutMapping("/notes/{id}")
  Object save(@PathVariable String id, @RequestBody NoteService.Edit edit) {
    return notes.save(id, edit);
  }

  @GetMapping("/notes/{id}/history")
  Object history(@PathVariable String id) {
    return notes.history(id);
  }

  public record Restore(long version, long revision) {}

  @PostMapping("/notes/{id}/restore")
  Object restore(@PathVariable String id, @RequestBody Restore edit) {
    return notes.restore(id, edit.version(), edit.revision());
  }

  @PostMapping("/notes/{id}/reindex")
  Object retry(@PathVariable String id) {
    notes.get(id);
    db.app().update("UPDATE notes SET indexed_revision=0,index_error='' WHERE id=?", id);
    return Map.of("queued", true);
  }

  @PostMapping(value = "/notes/import", consumes = "multipart/form-data")
  Object upload(
      @RequestParam(defaultValue = "imports") String sectionId,
      @RequestParam(defaultValue = "false") boolean knowledge,
      @RequestPart("file") MultipartFile file)
      throws Exception {
    return notes.importFile(sectionId, knowledge, file);
  }

  @PostMapping(value = "/notes/{id}/assets", consumes = "multipart/form-data")
  Object asset(@PathVariable String id, @RequestPart("file") MultipartFile file) throws Exception {
    return notes.uploadAsset(id, file);
  }

  @GetMapping("/note-assets/{id}")
  ResponseEntity<FileSystemResource> download(@PathVariable String id) {
    var a = notes.asset(id);
    String mime = Database.text(a, "mime");
    return ResponseEntity.ok()
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "no-store")
        .header(
            "Content-Disposition",
            (mime.startsWith("image/")
                    ? ContentDisposition.inline()
                    : ContentDisposition.attachment())
                .filename(Database.text(a, "name"), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType(mime))
        .body(new FileSystemResource(notes.assetPath(id)));
  }

  public record Query(String question) {}

  @PostMapping("/notes/search")
  Object search(@RequestBody Query q) {
    if (q.question() == null || q.question().isBlank() || q.question().length() > 2000)
      throw cyou.jujube.common.ApiException.badRequest("INVALID_QUESTION", "请输入 1–2000 字符的问题");
    return index.search("note-preview-" + UUID.randomUUID(), q.question());
  }
}
