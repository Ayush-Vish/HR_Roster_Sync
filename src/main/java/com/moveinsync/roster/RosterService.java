package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RosterService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;

    public RosterService(JdbcTemplate db, TransactionTemplate tx, MeterRegistry metrics) {
        this.db = db;
        this.tx = tx;
        this.metrics = metrics;
    }

    public Result ingest(String tenant, HrEvent event) {
        String error = validate(event);
        if (error != null) return new Result(safeId(event), "REJECTED", error);
        Result result =
                tx.execute(
                        s -> {
                            // All HR writers share this lock with reconciliation. No version
                            // check/write race.
                            long watermark =
                                    db.queryForObject(
                                            "SELECT snapshot_watermark FROM tenants WHERE id=? FOR UPDATE",
                                            Long.class,
                                            tenant);
                            var old =
                                    db.queryForList(
                                            "SELECT source_version FROM employees WHERE tenant_id=? AND employee_id=?",
                                            tenant,
                                            event.employeeId());
                            if (!old.isEmpty()
                                    && ((Number) old.getFirst().get("source_version")).longValue()
                                            >= event.version())
                                return new Result(
                                        event.employeeId(), "IGNORED", "STALE_OR_DUPLICATE");
                            if (event.version() <= watermark)
                                return new Result(
                                        event.employeeId(), "IGNORED", "BEFORE_SNAPSHOT_WATERMARK");
                            if (!"DEACTIVATE".equals(event.type())
                                    && !officeExists(tenant, event.office()))
                                return new Result(event.employeeId(), "REJECTED", "UNKNOWN_OFFICE");
                            db.update(
                                    """
                INSERT INTO employees(tenant_id,employee_id,source_version,name,email,phone,manager,office,shift,active)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,employee_id) DO UPDATE SET
                source_version=excluded.source_version,
                name=CASE WHEN excluded.active THEN excluded.name ELSE employees.name END,
                email=CASE WHEN excluded.active THEN excluded.email ELSE employees.email END,
                phone=CASE WHEN excluded.active THEN excluded.phone ELSE employees.phone END,
                manager=CASE WHEN excluded.active THEN excluded.manager ELSE employees.manager END,
                office=CASE WHEN excluded.active THEN excluded.office ELSE employees.office END,
                shift=CASE WHEN excluded.active THEN excluded.shift ELSE employees.shift END,
                active=excluded.active,updated_at=now()
                """,
                                    tenant,
                                    event.employeeId(),
                                    event.version(),
                                    event.name(),
                                    event.email(),
                                    event.phone(),
                                    event.manager(),
                                    event.office(),
                                    event.shift(),
                                    !"DEACTIVATE".equals(event.type()));
                            db.update(
                                    "INSERT INTO outbox(tenant_id,employee_id,source_version,active,kind) VALUES (?,?,?,?,'HR_CHANGE')",
                                    tenant,
                                    event.employeeId(),
                                    event.version(),
                                    !"DEACTIVATE".equals(event.type()));
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

    boolean officeExists(String tenant, String office) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM offices WHERE tenant_id=? AND code=?)",
                        Boolean.class,
                        tenant,
                        office));
    }

    public List<Map<String, Object>> list(String tenant, String after, int limit) {
        return db.queryForList(
                "SELECT * FROM employees WHERE tenant_id=? AND employee_id>? ORDER BY employee_id LIMIT ?",
                tenant,
                after,
                Math.clamp(limit, 1, 200));
    }

    public Map<String, Object> get(String tenant, String id) {
        var rows =
                db.queryForList(
                        "SELECT * FROM employees WHERE tenant_id=? AND employee_id=?", tenant, id);
        if (rows.isEmpty()) throw new Invalid("NOT_FOUND");
        return rows.getFirst();
    }

    public void transport(String tenant, String id, TransportFields fields) {
        if (fields.pickupPoint() != null && fields.pickupPoint().length() > 500
                || fields.assistance() != null && fields.assistance().length() > 500)
            throw new Invalid("FIELD_TOO_LONG");
        if (db.update(
                        "UPDATE employees SET pickup_point=?,assistance=?,updated_at=now() WHERE tenant_id=? AND employee_id=?",
                        fields.pickupPoint(),
                        fields.assistance(),
                        tenant,
                        id)
                == 0) throw new Invalid("NOT_FOUND");
    }
}
