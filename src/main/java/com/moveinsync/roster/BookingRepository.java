package com.moveinsync.roster;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {
    private final JdbcTemplate db;

    public BookingRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void cancelFutureBookings(
            String tenant,
            String employeeId,
            String eventOrderKind,
            long eventVersion,
            Timestamp eventTime) {
        db.update(
                "UPDATE bookings SET status='CANCELLED',seat_reserved=false WHERE tenant_id=? AND employee_id=? AND starts_at>now() AND status='CONFIRMED' AND ((employee_version IS NOT NULL AND employee_order_kind=? AND employee_version<?) OR (employee_version IS NULL AND created_at<=?))",
                tenant,
                employeeId,
                eventOrderKind,
                eventVersion,
                eventTime);
    }

    public UUID create(
            String tenant,
            String employeeId,
            OffsetDateTime startsAt,
            long employeeVersion,
            String employeeOrderKind) {
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO bookings(id,tenant_id,employee_id,starts_at,employee_version,employee_order_kind) VALUES (?,?,?,?,?,?)",
                id,
                tenant,
                employeeId,
                startsAt,
                employeeVersion,
                employeeOrderKind);
        return id;
    }

    public List<Map<String, Object>> list(String tenant) {
        return db.queryForList(
                "SELECT * FROM bookings WHERE tenant_id=? ORDER BY starts_at DESC LIMIT 200",
                tenant);
    }
}
