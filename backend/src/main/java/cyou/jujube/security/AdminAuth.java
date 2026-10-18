package cyou.jujube.security;

import cyou.jujube.common.ApiException;
import cyou.jujube.config.CloneProperties;
import cyou.jujube.quota.RateLimits;
import cyou.jujube.storage.Database;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 唯一管理员的认证服务：密码哈希、加密保存的动态口令秘密、一次性恢复码和八小时会话。 初始化只能由本地命令触发；动态验证码时间步和恢复码通过条件更新防止并发重放。
 * 登录接口同时受地址限流保护，数据库只保存会话令牌和恢复码的哈希。
 */
@Service
public class AdminAuth {
  private final Database db;
  private final CloneProperties props;
  private final Clock clock;
  private final RateLimits limits;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

  public AdminAuth(Database db, CloneProperties props, Clock clock, RateLimits limits) {
    this.db = db;
    this.props = props;
    this.clock = clock;
    this.limits = limits;
  }

  public record Bootstrap(String secret, List<String> recoveryCodes) {}

  /** 只允许创建一次管理员；密码用 BCrypt，动态口令秘密加密，恢复码仅保存哈希。 */
  public synchronized Bootstrap initialize(String username, String password) {
    if (username == null
        || !username.matches("[A-Za-z0-9_-]{3,40}")
        || password == null
        || password.length() < 12
        || password.length() > 72)
      throw ApiException.badRequest("WEAK_CREDENTIALS", "用户名需 3–40 个字符，密码需 12–72 个字符");
    if (db.app().queryForObject("SELECT COUNT(*) FROM admin_account", Long.class) > 0)
      throw new ApiException(409, "ADMIN_EXISTS", "管理员已初始化");
    String secret = Totp.newSecret(), encrypted = Crypto.encrypt(secret, props.masterKey());
    List<String> recovery = new ArrayList<>();
    for (int i = 0; i < 8; i++) recovery.add(Crypto.token().substring(0, 20));
    db.transaction(
        sql -> {
          sql.update(
              "INSERT INTO admin_account(id,username,password_hash,totp_encrypted) VALUES(1,?,?,?)",
              username,
              passwords.encode(password),
              encrypted);
          recovery.forEach(
              code -> sql.update("INSERT INTO recovery_codes(hash) VALUES(?)", Crypto.hash(code)));
          return null;
        });
    return new Bootstrap(secret, List.copyOf(recovery));
  }

  /** 先限流再验证密码和第二因素；验证码时间步或恢复码只能成功消费一次，再签发随机会话。 */
  public synchronized String login(String username, String password, String code, String ipHash) {
    limits.require("login", ipHash, 8, Duration.ofMinutes(15).toMillis());
    var rows = db.app().queryForList("SELECT * FROM admin_account WHERE id=1");
    if (rows.isEmpty()) throw ApiException.unavailable("ADMIN_NOT_INITIALIZED", "管理员尚未初始化");
    Map<String, Object> account = rows.getFirst();
    if (!Database.text(account, "username").equals(username)
        || password == null
        || !passwords.matches(password, Database.text(account, "password_hash"))) throw denied();
    boolean valid = false;
    if (code != null && code.matches("\\d{6}")) {
      long step =
          Totp.matchingStep(
              Crypto.decrypt(Database.text(account, "totp_encrypted"), props.masterKey()),
              code,
              clock.millis());
      // 条件更新既验证时间步递增，也消费本次验证码；并发使用同一码只会成功一次。
      if (step >= 0)
        valid =
            db.app()
                    .update(
                        "UPDATE admin_account SET last_totp_step=? WHERE id=1 AND last_totp_step<?",
                        step,
                        step)
                == 1;
    } else if (code != null)
      valid =
          db.app()
                  .update(
                      "UPDATE recovery_codes SET used=1 WHERE hash=? AND used=0", Crypto.hash(code))
              == 1;
    if (!valid) throw denied();
    String token = Crypto.token();
    db.app()
        .update(
            "INSERT INTO admin_sessions(token_hash,expires_at) VALUES(?,?)",
            Crypto.hash(token),
            clock.millis() + Duration.ofHours(8).toMillis());
    return token;
  }

  /** 按令牌哈希及有效期验证会话，不接受前端自行声明的登录标志。 */
  public boolean authenticated(String token) {
    return token != null
        && db.app()
                .queryForObject(
                    "SELECT COUNT(*) FROM admin_sessions WHERE token_hash=? AND expires_at>?",
                    Long.class,
                    Crypto.hash(token),
                    clock.millis())
            > 0;
  }

  public void logout(String token) {
    if (token != null)
      db.app().update("DELETE FROM admin_sessions WHERE token_hash=?", Crypto.hash(token));
  }

  private ApiException denied() {
    return new ApiException(401, "INVALID_CREDENTIALS", "登录信息或动态验证码无效");
  }
}
