package com.moveinsync.roster;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Outbox/inbox persistence; operations join the caller's transaction. */
@Repository
public class OutboxRepository {
    private final JdbcTemplate db;

    public OutboxRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void appendEmployeeChange(String tenant, String id, long version, boolean active) {
        db.update(
                "INSERT INTO outbox(tenant_id,employee_id,source_version,active,kind) VALUES (?,?,?,?,'HR_CHANGE')",
                tenant,
                id,
                version,
                active);
    }

    public long pendingCount() {
        return db.queryForObject(
                "SELECT count(*) FROM outbox WHERE delivered_at IS NULL", Long.class);
    }

    public long exhaustedCount() {
        return db.queryForObject(
                "SELECT count(*) FROM outbox WHERE delivered_at IS NULL AND attempts>=10",
                Long.class);
    }

    public double oldestPendingSeconds() {
        return db.queryForObject(
                "SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM outbox WHERE delivered_at IS NULL",
                Double.class);
    }

    /** Retain this lock until delivery or retry state commits. */
    public Optional<Map<String, Object>> lockNextDue() {
        return db
                .queryForList(
                        "SELECT * FROM outbox WHERE delivered_at IS NULL AND attempts<10 AND next_attempt<=now() ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1")
                .stream()
                .findFirst();
    }

    public boolean recordConsumption(long id) {
        return db.update(
                        "INSERT INTO consumed_events(event_id) VALUES (?) ON CONFLICT DO NOTHING",
                        id)
                == 1;
    }

    public void markDelivered(long id) {
        db.update("UPDATE outbox SET delivered_at=now() WHERE id=?", id);
    }

    public void scheduleRetry(long id) {
        db.update(
                "UPDATE outbox SET attempts=attempts+1,next_attempt=now()+(power(2,least(attempts,8))+random())*interval '1 second' WHERE id=?",
                id);
    }
}
