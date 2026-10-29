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

    public RosterService(
            EmployeeRepository employees,
            OutboxRepository outbox,
            TransactionTemplate tx,
            MeterRegistry metrics) {
        this.employees = employees;
        this.outbox = outbox;
        this.tx = tx;
        this.metrics = metrics;
    }

    public Result ingest(String tenant, HrEvent event) {
        String error = validate(event);
        if (error != null) return new Result(safeId(event), "REJECTED", error);
        Result result =
                tx.execute(
                        s -> {
                            // One service-owned transaction spans both repositories.
                            long watermark = employees.lockTenantAndGetWatermark(tenant);
                            var version = employees.findVersion(tenant, event.employeeId());
                            if (version.isPresent() && version.getAsLong() >= event.version())
                                return new Result(
                                        event.employeeId(), "IGNORED", "STALE_OR_DUPLICATE");
                            if (event.version() <= watermark)
                                return new Result(
                                        event.employeeId(), "IGNORED", "BEFORE_SNAPSHOT_WATERMARK");
                            boolean active = !"DEACTIVATE".equals(event.type());
                            if (active && !employees.officeExists(tenant, event.office()))
                                return new Result(event.employeeId(), "REJECTED", "UNKNOWN_OFFICE");
                            employees.applyHrUpdate(tenant, event, active);
                            outbox.appendEmployeeChange(
                                    tenant, event.employeeId(), event.version(), active);
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
        if (fields.pickupPoint() != null && fields.pickupPoint().length() > 500
                || fields.assistance() != null && fields.assistance().length() > 500)
            throw new Invalid("FIELD_TOO_LONG");
        if (!employees.updateTransport(tenant, id, fields)) throw new Invalid("NOT_FOUND");
    }
}
