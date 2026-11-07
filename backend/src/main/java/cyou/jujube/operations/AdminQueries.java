package cyou.jujube.operations;

import cyou.jujube.common.ApiException;
import cyou.jujube.storage.Database;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.stereotype.Service;

/** 管理列表的只读投影和稳定游标；字段显式列出，不把未来数据库字段自动暴露到接口。 */
@Service
public class AdminQueries {
  public record Job(
      String id,
      String type,
      String subject_id,
      String status,
      String stage,
      String error_code,
      int cursor,
      long created_at,
      long updated_at) {}

  public record Call(
      String id,
      String request_id,
      String stage,
      String provider,
      String model,
      String day,
      long reserved,
      Long actual,
      String status,
      Long input_tokens,
      Long output_tokens,
      long created_at,
      Long duration_ms,
      String error_code,
      String pricing_json) {}

  public record Page<T>(List<T> items, String nextCursor) {}

  private final Database db;

  public AdminQueries(Database db) {
    this.db = db;
  }

  public Page<Job> jobs(String status, String cursor) {
    return page(
        "jobs",
        "id,type,subject_id,status,stage,error_code,cursor,created_at,updated_at",
        Job.class,
        status,
        cursor);
  }

  public Page<Call> calls(String status, String cursor) {
    return page(
        "ai_calls",
        "id,request_id,stage,provider,model,day,reserved,actual,status,input_tokens,output_tokens,created_at,duration_ms,error_code,pricing_json",
        Call.class,
        status,
        cursor);
  }

  private <T> Page<T> page(
      String table, String columns, Class<T> type, String status, String cursor) {
    String where = " WHERE 1=1";
    var args = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      if (!status.matches("[A-Z_]{1,30}"))
        throw ApiException.badRequest("INVALID_FILTER", "状态筛选无效");
      where += " AND status=?";
      args.add(status);
    }
    if (cursor != null && !cursor.isBlank()) {
      try {
        if (cursor.length() > 200) throw new IllegalArgumentException();
        String[] parts =
            new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
        long time = Long.parseLong(parts[0]);
        if (parts.length != 2 || !parts[1].matches("[a-f0-9-]{36}"))
          throw new IllegalArgumentException();
        where += " AND (created_at<? OR (created_at=? AND id<?))";
        args.add(time);
        args.add(time);
        args.add(parts[1]);
      } catch (Exception ex) {
        throw ApiException.badRequest("INVALID_CURSOR", "分页位置无效，请刷新列表");
      }
    }
    var rows =
        // 每页展示 50 项，多取第 51 项只是判断“还有下一页”，不把它包含在当前页。
        db.app()
            .query(
                "SELECT "
                    + columns
                    + " FROM "
                    + table
                    + where
                    + " ORDER BY created_at DESC,id DESC LIMIT 51",
                DataClassRowMapper.newInstance(type),
                args.toArray());
    String next = "";
    if (rows.size() > 50) {
      // 游标保存最后一条已展示记录的时间和 id，下一页从它之后读取，比 OFFSET 更稳定。
      // Base64 只是编码；访问权限仍依靠管理员认证，不依靠游标保密。
      T last = rows.get(49);
      String id = last instanceof Job job ? job.id() : ((Call) last).id();
      long created = last instanceof Job job ? job.created_at() : ((Call) last).created_at();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString((created + ":" + id).getBytes(StandardCharsets.UTF_8));
    }
    return new Page<>(List.copyOf(rows.subList(0, Math.min(rows.size(), 50))), next);
  }
}
