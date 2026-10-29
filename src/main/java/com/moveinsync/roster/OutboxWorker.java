package com.moveinsync.roster;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxWorker {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final MeterRegistry metrics;

    public OutboxWorker(
            JdbcTemplate db,
            TransactionTemplate tx,
            @Value("${roster.worker-enabled}") boolean enabled,
            MeterRegistry metrics) {
        this.db = db;
        this.tx = tx;
        this.enabled = enabled;
        this.metrics = metrics;
        metrics.gauge(
                "roster.outbox.pending",
                this,
                w ->
                        w.db.queryForObject(
                                "SELECT count(*) FROM outbox WHERE delivered_at IS NULL",
                                Long.class));
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (enabled) drain(100);
    }

    public int drain(int limit) {
        int count = 0;
        for (int i = 0; i < limit; i++) {
            boolean processed =
                    Boolean.TRUE.equals(
                            tx.execute(
                                    s -> {
                                        var rows =
                                                db.queryForList(
                                                        "SELECT * FROM outbox WHERE delivered_at IS NULL AND attempts<10 AND next_attempt<=now() ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1");
                                        if (rows.isEmpty()) return false;
                                        Map<String, Object> event = rows.getFirst();
                                        long id = ((Number) event.get("id")).longValue();
                                        // Savepoint keeps retries durable without committing
                                        // partially performed effects.
                                        Object savepoint = s.createSavepoint();
                                        try {
                                            int inserted =
                                                    db.update(
                                                            "INSERT INTO consumed_events(event_id) VALUES (?) ON CONFLICT DO NOTHING",
                                                            id);
                                            if (inserted == 1
                                                    && Boolean.FALSE.equals(event.get("active"))) {
                                                db.update(
                                                        "UPDATE bookings SET status='CANCELLED',seat_reserved=false WHERE tenant_id=? AND employee_id=? AND starts_at>now() AND status='CONFIRMED' AND created_at<=?",
                                                        event.get("tenant_id"),
                                                        event.get("employee_id"),
                                                        event.get("created_at"));
                                            }
                                            db.update(
                                                    "UPDATE outbox SET delivered_at=now() WHERE id=?",
                                                    id);
                                            s.releaseSavepoint(savepoint);
                                            metrics.counter("roster.outbox.delivered").increment();
                                        } catch (RuntimeException ex) {
                                            s.rollbackToSavepoint(savepoint);
                                            db.update(
                                                    "UPDATE outbox SET attempts=attempts+1,next_attempt=now()+(power(2,least(attempts,8))+random())*interval '1 second' WHERE id=?",
                                                    id);
                                            metrics.counter("roster.outbox.failures").increment();
                                        }
                                        return true;
                                    }));
            if (!processed) break;
            count++;
        }
        return count;
    }
}
