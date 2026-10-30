package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RosterService {
    private final EmployeeRepository employees;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final OrderingPolicy ordering;

    public RosterService(
            EmployeeRepository employees,
            OutboxRepository outbox,
            TransactionTemplate tx,
            MeterRegistry metrics,
            OrderingPolicy ordering) {
        this.employees = employees;
        this.outbox = outbox;
        this.tx = tx;
        this.metrics = metrics;
        this.ordering = ordering;
    }

    public Result ingest(String tenant, HrEvent event) {
        String error = validate(event);
        if (error != null) {
            metrics.counter("roster.events", "result", "REJECTED").increment();
            return new Result(safeId(event), "REJECTED", error);
        }
        SourceOrder order;
        try {
            order = ordering.resolve(event);
        } catch (Invalid invalid) {
            metrics.counter("roster.events", "result", "REJECTED").increment();
            return new Result(safeId(event), "REJECTED", invalid.code);
        }
        Result result =
                tx.execute(
                        s -> {
                            // One service-owned transaction spans both repositories.
                            var watermark = employees.lockTenantAndGetWatermark(tenant);
                            var stored = employees.findOrder(tenant, event.employeeId());
                            if (stored.isPresent()
                                    && !stored.get().kind().equals(order.kind().name()))
                                return new Result(
                                        event.employeeId(), "REJECTED", "ORDERING_KIND_MISMATCH");
                            if (stored.isPresent() && stored.get().value() >= order.value())
                                return new Result(
                                        event.employeeId(), "IGNORED", "STALE_OR_DUPLICATE");
                            if (watermark.kind() != null
                                    && !order.kind().name().equals(watermark.kind()))
                                return new Result(
                                        event.employeeId(), "REJECTED", "ORDERING_KIND_MISMATCH");
                            if (watermark.value() >= 0 && order.value() <= watermark.value())
                                return new Result(
                                        event.employeeId(), "IGNORED", "BEFORE_SNAPSHOT_WATERMARK");
                            boolean active = !"DEACTIVATE".equals(event.type());
                            if (active && !employees.officeExists(tenant, event.office()))
                                return new Result(event.employeeId(), "REJECTED", "UNKNOWN_OFFICE");
                            employees.setTenantOrderKind(tenant, order.kind().name());
                            employees.applyHrUpdate(tenant, event, order, active);
                            outbox.appendEmployeeChange(tenant, event.employeeId(), order, active);
                            return new Result(event.employeeId(), "APPLIED", null);
                        });
        metrics.counter("roster.events", "result", result.status()).increment();
        org.slf4j.LoggerFactory.getLogger(RosterService.class)
                .atInfo()
                .addKeyValue("tenant", tenant)
                .addKeyValue("employee_id", event.employeeId())
                .addKeyValue("result", result.status())
                .log("hr_event_processed");
        return result;
    }

    static String safeId(HrEvent e) {
        return e != null && e.employeeId() != null && e.employeeId().matches("[A-Za-z0-9_-]{1,80}")
                ? e.employeeId()
                : null;
    }

    public List<Map<String, Object>> list(String tenant, String after, int limit) {
        return employees.list(tenant, after, Math.clamp(limit, 1, 200));
    }

    public Map<String, Object> get(String tenant, String id) {
        return employees.find(tenant, id).orElseThrow(() -> new Invalid("NOT_FOUND"));
    }

    public void transport(String tenant, String id, TransportFields fields) {
        if ((fields.pickupPoint() != null && fields.pickupPoint().indexOf('\0') >= 0)
                || (fields.assistance() != null && fields.assistance().indexOf('\0') >= 0))
            throw new Invalid("INVALID_TEXT");
        if (fields.pickupPoint() != null && fields.pickupPoint().length() > 500
                || fields.assistance() != null && fields.assistance().length() > 500)
            throw new Invalid("FIELD_TOO_LONG");
        if (!employees.updateTransport(tenant, id, fields)) throw new Invalid("NOT_FOUND");
    }
}
