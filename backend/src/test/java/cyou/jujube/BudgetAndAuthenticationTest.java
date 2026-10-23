package cyou.jujube;

import static org.junit.jupiter.api.Assertions.*;

import cyou.jujube.common.ApiException;
import cyou.jujube.config.*;
import cyou.jujube.quota.*;
import cyou.jujube.security.*;
import cyou.jujube.storage.Database;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 验证费用预留与人工对账、管理员认证及旧会话数据库兼容。 可控时钟用于检查过期和验证码防重放，所有认证资料只属于本次测试。 */
class BudgetAndAuthenticationTest {
  @TempDir Path temp;
  CloneProperties props;
  Database db;
  final Instant instant = Instant.parse("2026-10-18T15:59:59Z");
  Clock clock = Clock.fixed(instant, ZoneOffset.UTC);

  @BeforeEach
  void initialize() throws Exception {
    props =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "clone.data-dir",
                        temp.toString(),
                        "clone.daily-budget-cny",
                        "2",
                        "clone.lifetime-notify-cny",
                        "5",
                        "clone.master-key",
                        Base64.getEncoder().encodeToString(new byte[32]))))
            .bind("clone", Bindable.of(CloneProperties.class))
            .get();
    db = new Database(RuntimeConfig.sqlite(temp.resolve("app.db").toString()), props);
  }

  @Test
  void legacyConversationSchemaIsUpgradedWithoutLosingMessages() throws Exception {
    var visitors = new cyou.jujube.conversation.VisitorSessions(db, clock);
    var visitor = visitors.create();
    visitors.save(visitor.id(), "fixture question", "fixture answer");
    LegacyFixtures.sessions(db);
    db.sessions().execute("ALTER TABLE messages DROP COLUMN metadata_json");
    var upgraded = new Database(RuntimeConfig.sqlite(temp.resolve("app.db").toString()), props);
    var restored = new cyou.jujube.conversation.VisitorSessions(upgraded, clock);
    assertEquals("fixture answer", restored.history(visitor.token()).getLast().get("content"));
    assertDoesNotThrow(
        () -> new Database(RuntimeConfig.sqlite(temp.resolve("app.db").toString()), props));
  }

  @Test
  void paidSwitchBlocksNewPaidCallsButKeepsFreeEmbeddingAvailable() {
    db.putSetting("ai.paid-enabled", "false");
    var budget = new BudgetService(db, clock, props);
    assertEquals(
        "PAID_CALLS_PAUSED",
        assertThrows(
                ApiException.class, () -> budget.require("paused", "TEST", "fixture", "fixture", 1))
            .code());
    assertNotNull(budget.reserve("free", "EMBEDDING", "fixture", "fixture", 0));
  }

  @Test
  void budgetDayChangesAtShanghaiMidnight() {
    var before = new BudgetService(db, clock, props);
    var first = before.require("first", "TEST", "fixture", "fixture", 2_000_000);
    before.settle(first, 2_000_000, 1, 0, 1);
    assertNull(before.reserve("blocked", "TEST", "fixture", "fixture", 1));
    var after = new BudgetService(db, Clock.fixed(instant.plusSeconds(2), ZoneOffset.UTC), props);
    assertNotNull(after.reserve("next-day", "TEST", "fixture", "fixture", 2_000_000));
    assertEquals(
        List.of("2026-10-18", "2026-10-19"),
        db.app().queryForList("SELECT day FROM budget_days ORDER BY day", String.class));
  }

  @Test
  void lifetimeEmailIsCreatedOnceOnlyAfterMoreThanFiveYuan() {
    for (int day = 0; day < 3; day++) {
      var budget =
          new BudgetService(
              db, Clock.fixed(instant.plus(Duration.ofDays(day)), ZoneOffset.UTC), props);
      long amount = day < 2 ? 2_000_000 : 1_000_000;
      var call = budget.require("day-" + day, "TEST", "fixture", "fixture", amount);
      budget.settle(call, amount, 1, 0, 1);
    }
    assertEquals(
        0L,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM notification_events WHERE kind='LIFETIME_COST'", Long.class));
    var budget =
        new BudgetService(db, Clock.fixed(instant.plus(Duration.ofDays(2)), ZoneOffset.UTC), props);
    for (int i = 0; i < 2; i++) {
      var call = budget.require("over-" + i, "TEST", "fixture", "fixture", 10_000);
      budget.settle(call, 10_000, 1, 0, 1);
    }
    assertEquals(
        1L,
        db.app()
            .queryForObject(
                "SELECT COUNT(*) FROM notification_events WHERE kind='LIFETIME_COST'", Long.class));
  }

  @Test
  void concurrentRateLimitDoesNotAllowMoreThanConfigured() throws Exception {
    var limits = new RateLimits(db, clock);
    int allowed = 0;
    try (var workers = Executors.newFixedThreadPool(8)) {
      var attempts = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 20; i++)
        attempts.add(
            workers.submit(
                () -> {
                  try {
                    limits.require("chat", "visitor", 6, 60_000);
                    return true;
                  } catch (ApiException ex) {
                    assertEquals("RATE_LIMITED", ex.code());
                    return false;
                  }
                }));
      for (var attempt : attempts) if (attempt.get()) allowed++;
    }
    assertEquals(6, allowed);
  }

  @Test
  void totpAndRecoveryCodesCannotBeReplayed() {
    var auth = new AdminAuth(db, props, clock, new RateLimits(db, clock));
    var enrolled = auth.initialize("owner", "long-test-fixture-password");
    String code = Totp.code(enrolled.secret(), clock.millis() / 30_000);
    String token = auth.login("owner", "long-test-fixture-password", code, "ip");
    assertTrue(auth.authenticated(token));
    assertEquals(
        "INVALID_CREDENTIALS",
        assertThrows(
                ApiException.class,
                () -> auth.login("owner", "long-test-fixture-password", code, "ip"))
            .code());
    String recovery = enrolled.recoveryCodes().getFirst();
    String recovered = auth.login("owner", "long-test-fixture-password", recovery, "ip");
    assertTrue(auth.authenticated(recovered));
    assertThrows(
        ApiException.class,
        () -> auth.login("owner", "long-test-fixture-password", recovery, "ip"));
    auth.logout(token);
    assertFalse(auth.authenticated(token));
  }
}
