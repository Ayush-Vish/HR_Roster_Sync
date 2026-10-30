package com.moveinsync.roster;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxRepository outbox;
    private final BookingRepository bookings;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final MeterRegistry metrics;
    private final Counter delivered;
    private final Counter failures;
    private final Counter retries;

    public OutboxWorker(
            OutboxRepository outbox,
            BookingRepository bookings,
            TransactionTemplate tx,
            @Value("${roster.worker-enabled}") boolean enabled,
            MeterRegistry metrics) {
        this.outbox = outbox;
        this.bookings = bookings;
        this.tx = tx;
        this.enabled = enabled;
        this.metrics = metrics;
        delivered = metrics.counter("roster.outbox.delivery.attempts", "result", "SUCCESS");
        failures = metrics.counter("roster.outbox.delivery.attempts", "result", "FAILURE");
        retries = metrics.counter("roster.outbox.retries");
        metrics.gauge("roster.outbox.pending", outbox, OutboxRepository::pendingCount);
        metrics.gauge("roster.outbox.exhausted", outbox, OutboxRepository::exhaustedCount);
        metrics.gauge(
                "roster.outbox.oldest.seconds", outbox, OutboxRepository::oldestPendingSeconds);
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
                                        var next = outbox.lockNextDue();
                                        if (next.isEmpty()) return false;
                                        var event = next.get();
                                        long id = ((Number) event.get("id")).longValue();
                                        // Retry state commits, but partial consumer effects roll
                                        // back to this savepoint.
                                        Object savepoint = s.createSavepoint();
                                        try {
                                            boolean firstDelivery = outbox.recordConsumption(id);
                                            if (firstDelivery
                                                    && Boolean.FALSE.equals(event.get("active")))
                                                bookings.cancelFutureBookings(
                                                        (String) event.get("tenant_id"),
                                                        (String) event.get("employee_id"),
                                                        (String) event.get("source_order_kind"),
                                                        ((Number) event.get("source_version"))
                                                                .longValue(),
                                                        (java.sql.Timestamp)
                                                                event.get("created_at"));
                                            outbox.markDelivered(id);
                                            s.releaseSavepoint(savepoint);
                                            delivered.increment();
                                            metrics.counter("roster.outbox.delivered").increment();
                                        } catch (RuntimeException ex) {
                                            s.rollbackToSavepoint(savepoint);
                                            outbox.scheduleRetry(id);
                                            failures.increment();
                                            retries.increment();
                                            metrics.counter("roster.outbox.failures").increment();
                                            LOG.atWarn()
                                                    .addKeyValue("tenant", event.get("tenant_id"))
                                                    .addKeyValue("event_id", id)
                                                    .addKeyValue(
                                                            "error_type",
                                                            ex.getClass().getSimpleName())
                                                    .log("outbox_delivery_failed");
                                        }
                                        return true;
                                    }));
            if (!processed) break;
            count++;
        }
        return count;
    }
}
