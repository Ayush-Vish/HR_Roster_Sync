package com.moveinsync.roster;

import com.moveinsync.roster.Models.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Employee persistence; the calling service owns the transaction. */
@Repository
public class EmployeeRepository {
    private final JdbcTemplate db;

    public EmployeeRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** Hold this lock through the version check, employee write, and outbox insert. */
    public long lockTenantAndGetWatermark(String tenant) {
        return db.queryForObject(
                "SELECT snapshot_watermark FROM tenants WHERE id=? FOR UPDATE", Long.class, tenant);
    }

    public OptionalLong findVersion(String tenant, String id) {
        var rows =
                db.queryForList(
                        "SELECT source_version FROM employees WHERE tenant_id=? AND employee_id=?",
                        Long.class,
                        tenant,
                        id);
        return rows.isEmpty() ? OptionalLong.empty() : OptionalLong.of(rows.getFirst());
    }

    public boolean officeExists(String tenant, String office) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM offices WHERE tenant_id=? AND code=?)",
                        Boolean.class,
                        tenant,
                        office));
    }

    public void applyHrUpdate(String tenant, HrEvent e, boolean active) {
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
                e.employeeId(),
                e.version(),
                e.name(),
                e.email(),
                e.phone(),
                e.manager(),
                e.office(),
                e.shift(),
                active);
    }

    public List<Map<String, Object>> list(String tenant, String after, int limit) {
        return db.queryForList(
                "SELECT * FROM employees WHERE tenant_id=? AND employee_id>? ORDER BY employee_id LIMIT ?",
                tenant,
                after,
                limit);
    }

    public Optional<Map<String, Object>> find(String tenant, String id) {
        return db
                .queryForList(
                        "SELECT * FROM employees WHERE tenant_id=? AND employee_id=?", tenant, id)
                .stream()
                .findFirst();
    }

    public boolean updateTransport(String tenant, String id, TransportFields f) {
        return db.update(
                        "UPDATE employees SET pickup_point=?,assistance=?,updated_at=now() WHERE tenant_id=? AND employee_id=?",
                        f.pickupPoint(),
                        f.assistance(),
                        tenant,
                        id)
                > 0;
    }
}
