package cyou.jujube.knowledge;

import cyou.jujube.common.*;
import cyou.jujube.storage.Database;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;

/** 公开展示的明确契约；嵌套结构、链接与修订号在服务端校验。 */
@Service
public class ProfileService {
  public record Link(
      @NotBlank @Size(max = 100) String label, @NotBlank @Size(max = 1000) String url) {}

  public record Skill(
      @NotBlank @Size(max = 100) String name,
      @NotNull @Size(max = 200) String level,
      @NotNull @Size(max = 1000) String summary) {}

  public record Experience(
      @NotBlank @Size(max = 120) String title,
      @NotNull @Size(max = 120) String organization,
      @NotNull @Size(max = 100) String period,
      @NotNull @Size(max = 2000) String summary) {}

  public record Project(
      @NotBlank @Size(max = 120) String id,
      @NotBlank @Size(max = 120) String name,
      @NotNull @Size(max = 120) String role,
      @NotNull @Size(max = 2000) String summary,
      @NotNull @Size(max = 30) List<@NotBlank @Size(max = 80) String> stack,
      @NotNull @Size(max = 10) List<@NotNull @Valid Link> links) {}

  public record Edit(
      @NotNull @Size(max = 80) String displayName,
      @NotNull @Size(max = 200) String headline,
      @NotNull @Size(max = 3000) String bio,
      @NotNull @Size(max = 30) List<@NotNull @Valid Skill> skills,
      @NotNull @Size(max = 30) List<@NotNull @Valid Experience> experiences,
      @NotNull @Size(max = 30) List<@NotNull @Valid Project> projects,
      @NotNull @Size(max = 20) List<@NotNull @Valid Link> contacts,
      @NotNull @Size(max = 5) List<@NotNull @Valid Link> filings,
      @NotNull @Size(max = 10) List<@NotBlank @Size(max = 200) String> quickQuestions,
      @NotNull @Min(0) Long revision) {}

  private final Database db;

  public ProfileService(Database db) {
    this.db = db;
  }

  public Map<String, Object> get() {
    return db.transaction(
        sql -> {
          var result = new LinkedHashMap<>(Json.map(db.setting("profile.public", "{}")));
          result.put("revision", Long.parseLong(db.setting("profile.revision", "0")));
          return result;
        });
  }

  public long save(Edit edit) {
    // 公开资料用于页面展示，写入 settings；不会自动创建 candidates 或问答用的发布快照。
    for (Link link : edit.contacts()) checkLink(link, false);
    for (Link link : edit.filings()) checkLink(link, true);
    for (Project project : edit.projects())
      for (Link link : project.links()) checkLink(link, false);
    var value = new LinkedHashMap<>(Json.map(Json.write(edit)));
    value.remove("revision");
    value.put("configured", true);
    value.put("aiDisclosure", "这是 AI 分身，回答依据为本人确认的资料。");
    String encoded = Json.write(value);
    if (encoded.length() > 30_000) throw ApiException.badRequest("PROFILE_TOO_LARGE", "个人展示内容过长");
    return db.transaction(
        sql -> {
          sql.update("INSERT OR IGNORE INTO settings(key,value) VALUES('profile.revision','0')");
          long next = edit.revision() + 1;
          // 修订号与公开正文在同一事务里更新，防止两个编辑页面互相静默覆盖。
          if (sql.update(
                  "UPDATE settings SET value=? WHERE key='profile.revision' AND value=?",
                  Long.toString(next),
                  edit.revision().toString())
              != 1) throw new ApiException(409, "EDIT_CONFLICT", "公开资料已被其他页面修改，请保留当前编辑并刷新核对");
          db.putSetting("profile.public", encoded);
          return next;
        });
  }

  private static void checkLink(Link link, boolean httpsOnly) {
    try {
      URI uri = URI.create(link.url());
      String scheme = uri.getScheme();
      if (scheme == null
          || !(httpsOnly
              ? "https".equalsIgnoreCase(scheme)
              : Set.of("http", "https", "mailto").contains(scheme.toLowerCase(Locale.ROOT)))
          || (Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
              && (uri.getHost() == null || uri.getUserInfo() != null)))
        throw new IllegalArgumentException();
    } catch (Exception ex) {
      throw ApiException.badRequest("INVALID_PROFILE_LINK", "请填写有效的公开链接，备案链接必须使用 HTTPS");
    }
  }
}
