package cyou.jujube.quota;

import cyou.jujube.common.ApiException;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.storage.Database;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 模型费用账本，所有金额使用整数微元，即一元等于一百万微元，避免浮点金额误差。 按北京时间自然日原子预留预算；网络请求放在事务外，结束后结算或保留不确定费用等待对账。
 * 累计费用通知通过唯一事件编号去重，邮件失败不会回滚已完成的费用结算。
 */
@Service
public class BudgetService {
  private final Database db;
  private final Clock clock;
  private final CloneProperties props;

  public BudgetService(Database db, Clock clock, CloneProperties props) {
    this.db = db;
    this.clock = clock;
    this.props = props;
  }

  public record Reservation(String id, long maximum) {}

  public static long micros(BigDecimal cny) {
    return cny.multiply(BigDecimal.valueOf(1_000_000))
        .setScale(0, RoundingMode.CEILING)
        .longValueExact();
  }

  /** 单价按每百万词元的人民币金额传入，相乘后得到微元；向上取整，避免低估费用。 */
  public static long estimate(
      BigDecimal inputPrice, BigDecimal outputPrice, long input, long output) {
    if (inputPrice == null
        || outputPrice == null
        || inputPrice.signum() < 0
        || outputPrice.signum() < 0)
      throw ApiException.unavailable("PRICING_REQUIRED", "调用前需要配置有效的人民币计费口径");
    return inputPrice
        .multiply(BigDecimal.valueOf(input))
        .add(outputPrice.multiply(BigDecimal.valueOf(output)))
        .setScale(0, RoundingMode.CEILING)
        .longValueExact();
  }

  /** 通过带预算条件的 UPDATE 原子增加预留额，再写调用记录；事务期间不执行远程请求。 */
  public synchronized Reservation reserve(
      String requestId, String stage, String provider, String model, long maximum) {
    if (maximum < 0) throw new IllegalArgumentException("Negative reservation");
    if (maximum > 0 && !Boolean.parseBoolean(db.setting("ai.paid-enabled", "true")))
      throw ApiException.unavailable("PAID_CALLS_PAUSED", "管理员暂时暂停了 AI 付费调用，可以继续浏览个人介绍");
    String id = UUID.randomUUID().toString(),
        day = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toString();
    return db.transaction(
        sql -> {
          sql.update("INSERT OR IGNORE INTO budget_days(day) VALUES(?)", day);
          int updated =
              sql.update(
                  "UPDATE budget_days SET reserved=reserved+? WHERE day=? AND (?=0 OR spent+reserved+?<=?)",
                  maximum,
                  day,
                  maximum,
                  maximum,
                  micros(props.dailyBudgetCny()));
          if (updated == 0) {
            sql.update(
                "INSERT OR IGNORE INTO notification_events(id,kind,subject,body,created_at) VALUES(?,?,?,?,?)",
                "budget:" + day,
                "DAILY_BUDGET",
                "每日模型预算已触及上限",
                "新的付费调用已暂停，个人介绍和项目浏览继续服务。",
                clock.millis());
            return null;
          }
          sql.update(
              "INSERT INTO ai_calls(id,request_id,stage,provider,model,day,reserved,status,created_at) VALUES(?,?,?,?,?,?,?,'RESERVED',?)",
              id,
              requestId,
              stage,
              provider,
              model,
              day,
              maximum,
              clock.millis());
          return new Reservation(id, maximum);
        });
  }

  public Reservation require(
      String requestId, String stage, String provider, String model, long maximum) {
    Reservation result = reserve(requestId, stage, provider, model, maximum);
    if (result == null)
      throw new ApiException(429, "DAILY_BUDGET_REACHED", "今天的 AI 问答额度暂时用尽，可以继续浏览项目介绍");
    return result;
  }

  /** 只结算仍为预留状态的调用，扣回预留并累加实际估算额；重复结算直接跳过。 */
  public synchronized void settle(
      Reservation reservation, long actual, long input, long output, long duration) {
    if (actual < 0 || input < 0 || output < 0 || duration < 0)
      throw new IllegalArgumentException("Negative usage");
    db.transaction(
        sql -> {
          var call = Database.required(sql, "SELECT * FROM ai_calls WHERE id=?", reservation.id());
          if (!"RESERVED".equals(call.get("status"))) return null;
          sql.update(
              "UPDATE ai_calls SET actual=?,status='SETTLED',input_tokens=?,output_tokens=?,duration_ms=? WHERE id=?",
              actual,
              input,
              output,
              duration,
              reservation.id());
          sql.update(
              "UPDATE budget_days SET reserved=reserved-?,spent=spent+? WHERE day=?",
              reservation.maximum(),
              actual,
              call.get("day"));
          Long lifetime =
              sql.queryForObject(
                  "SELECT COALESCE(SUM(actual),0) FROM ai_calls WHERE status='SETTLED'",
                  Long.class);
          if (lifetime != null && lifetime > micros(props.lifetimeNotifyCny()))
            sql.update(
                "INSERT OR IGNORE INTO notification_events(id,kind,subject,body,created_at) VALUES('lifetime:5','LIFETIME_COST',?,?,?)",
                "累计模型费用超过 5 元",
                "这是首次累计费用通知；每日预算保护仍然生效。费用为按用量计算的人民币估算。",
                clock.millis());
          return null;
        });
  }

  /** 超时或异常时不能断言供应商未收费，标为待对账并继续占用预留额度。 */
  public void uncertain(Reservation reservation, String code, long duration) {
    db.app()
        .update(
            "UPDATE ai_calls SET status='UNCERTAIN',error_code=?,duration_ms=? WHERE id=? AND status='RESERVED'",
            code,
            duration,
            reservation.id());
  }

  /** 管理员核对账单后把不确定记录恢复为可结算状态，在同一事务内完成实际费用入账。 */
  public synchronized void reconcile(String id, BigDecimal actualCny) {
    if (actualCny == null
        || actualCny.signum() < 0
        || actualCny.compareTo(BigDecimal.valueOf(100)) > 0)
      throw ApiException.badRequest("INVALID_COST", "核对金额需在 0 到 100 元之间");
    db.transaction(
        sql -> {
          var call =
              Database.required(
                  sql, "SELECT * FROM ai_calls WHERE id=? AND status='UNCERTAIN'", id);
          sql.update("UPDATE ai_calls SET status='RESERVED' WHERE id=? AND status='UNCERTAIN'", id);
          settle(
              new Reservation(id, Database.number(call, "reserved")),
              micros(actualCny),
              Database.number(call, "input_tokens"),
              Database.number(call, "output_tokens"),
              Database.number(call, "duration_ms"));
          return null;
        });
  }

  public Map<String, Object> overview() {
    String day = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toString();
    var today =
        db.app().queryForList("SELECT day,spent,reserved FROM budget_days WHERE day=?", day);
    long total =
        db.app()
            .queryForObject(
                "SELECT COALESCE(SUM(actual),0) FROM ai_calls WHERE status='SETTLED'", Long.class);
    return Map.of(
        "day",
        day,
        "unit",
        "micro-CNY",
        "dailyLimit",
        micros(props.dailyBudgetCny()),
        "today",
        today.isEmpty() ? Map.of("spent", 0, "reserved", 0) : today.getFirst(),
        "lifetimeEstimated",
        total,
        "estimated",
        true,
        "uncertainCalls",
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM ai_calls WHERE status IN ('UNCERTAIN','RESERVED')",
                Long.class));
  }
}
