package com.moveinsync.roster;

import com.moveinsync.roster.Models.*;
import java.util.*;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Employee persistence; the calling service owns the transaction. */
@Repository
public class EmployeeRepository {
    public record SnapshotCursor(long value, String kind) {}

    public record StoredHrState(
            long value,
            String kind,
            boolean active,
            String name,
            String email,
            String phone,
            String manager,
            String office,
            String shift) {
        boolean matches(HrEvent event, boolean expectedActive) {
            if (active != expectedActive) return false;
            if (!expectedActive) return true;
            return Objects.equals(name, event.name())
                    && Objects.equals(email, event.email())
                    && Objects.equals(phone, event.phone())
                    && Objects.equals(manager, event.manager())
                    && Objects.equals(office, event.office())
                    && Objects.equals(shift, event.shift());
        }
    }

    private final JdbcTemplate db;

    public EmployeeRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** Hold this lock through the version check, employee write, and outbox insert. */
    public SnapshotCursor lockTenantAndGetWatermark(String tenant) {
        return db.queryForObject(
                "SELECT snapshot_watermark,source_order_kind FROM tenants WHERE id=? FOR UPDATE",
                (rs, row) ->
                        new SnapshotCursor(
                                rs.getLong("snapshot_watermark"),
                                rs.getString("source_order_kind")),
                tenant);
    }

    public Optional<StoredHrState> findHrState(String tenant, String id) {
        return db
                .query(
                        "SELECT source_version,source_order_kind,active,name,email,phone,manager,office,shift FROM employees WHERE tenant_id=? AND employee_id=?",
                        (rs, row) ->
                                new StoredHrState(
                                        rs.getLong("source_version"),
                                        rs.getString("source_order_kind"),
                                        rs.getBoolean("active"),
                                        rs.getString("name"),
                                        rs.getString("email"),
                                        rs.getString("phone"),
                                        rs.getString("manager"),
                                        rs.getString("office"),
                                        rs.getString("shift")),
                        tenant,
                        id)
                .stream()
                .findFirst();
    }

    public void setTenantOrderKind(String tenant, String kind) {
        db.update(
                "UPDATE tenants SET source_order_kind=? WHERE id=? AND source_order_kind IS NULL",
                kind,
                tenant);
    }

    @Cacheable("offices")
    public boolean officeExists(String tenant, String office) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM offices WHERE tenant_id=? AND code=?)",
                        Boolean.class,
                        tenant,
                        office));
    }

    public void applyHrUpdate(String tenant, HrEvent e, SourceOrder order, boolean active) {
        db.update(
                """
   INSERT INTO employees(tenant_id,employee_id,source_version,source_order_kind,source_timestamp,name,email,phone,manager,office,shift,active)
   VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,employee_id) DO UPDATE SET
   source_version=excluded.source_version,
   source_order_kind=excluded.source_order_kind,
   source_timestamp=excluded.source_timestamp,
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
                order.value(),
                order.kind().name(),
                order.timestamp(),
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
