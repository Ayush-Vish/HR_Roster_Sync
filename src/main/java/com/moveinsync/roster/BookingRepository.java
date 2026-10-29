package com.moveinsync.roster;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {
    private final JdbcTemplate db;

    public BookingRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void cancelFutureBookings(String tenant, String employeeId, Timestamp eventTime) {
        db.update(
                "UPDATE bookings SET status='CANCELLED',seat_reserved=false WHERE tenant_id=? AND employee_id=? AND starts_at>now() AND status='CONFIRMED' AND created_at<=?",
                tenant,
                employeeId,
                eventTime);
    }
}
