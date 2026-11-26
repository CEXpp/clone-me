package cyou.jujube;

import cyou.jujube.storage.Database;
import java.util.List;

/** 构造真实的无迁移记录旧库形状，而不是篡改一个声称已经升级的新库。 */
final class LegacyFixtures {
  static void app(Database db) {
    for (String table :
        List.of(
            "notes_fts",
            "note_chunks",
            "note_chunk_parents",
            "note_assets",
            "note_history",
            "notes",
            "note_sections",
            "notebooks")) db.app().execute("DROP TABLE IF EXISTS " + table);
    for (String index :
        List.of(
            "idx_jobs_active_subject",
            "idx_jobs_queue",
            "idx_jobs_subject",
            "idx_calls_state",
            "idx_calls_request",
            "idx_sources_created",
            "idx_segments_source",
            "idx_sessions_expiry",
            "idx_notifications_pending",
            "idx_diagnostics_created")) db.app().execute("DROP INDEX IF EXISTS " + index);
    db.app().execute("ALTER TABLE candidates DROP COLUMN revision");
    db.app().execute("ALTER TABLE ai_calls DROP COLUMN pricing_json");
    db.app().execute("DROP TABLE schema_migrations");
  }

  static void sessions(Database db) {
    db.sessions().execute("DROP TABLE chat_requests");
    db.sessions().execute("DROP INDEX idx_visitor_expiry");
    db.sessions().execute("DROP INDEX idx_messages_sequence");
    db.sessions().execute("ALTER TABLE visitor_sessions DROP COLUMN verified_ip");
    db.sessions().execute("ALTER TABLE visitor_sessions DROP COLUMN verified_until");
    db.sessions().execute("DROP TABLE schema_migrations");
  }
}
