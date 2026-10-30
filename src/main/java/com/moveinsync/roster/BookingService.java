package com.moveinsync.roster;

import static com.moveinsync.roster.Models.Invalid;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class BookingService {
    private final EmployeeRepository employees;
    private final BookingRepository bookings;
    private final TransactionTemplate tx;

    public BookingService(
            EmployeeRepository employees, BookingRepository bookings, TransactionTemplate tx) {
        this.employees = employees;
        this.bookings = bookings;
        this.tx = tx;
    }

    public Map<String, Object> create(String tenant, String employeeId, OffsetDateTime startsAt) {
        if (startsAt == null || !startsAt.isAfter(OffsetDateTime.now()))
            throw new Invalid("FUTURE_START_REQUIRED");
        return tx.execute(
                ignored -> {
                    employees.lockTenantAndGetWatermark(tenant);
                    var employee =
                            employees
                                    .find(tenant, employeeId)
                                    .orElseThrow(() -> new Invalid("NOT_FOUND"));
                    if (!Boolean.TRUE.equals(employee.get("active")))
                        throw new Invalid("EMPLOYEE_INACTIVE");
                    UUID id =
                            bookings.create(
                                    tenant,
                                    employeeId,
                                    startsAt,
                                    ((Number) employee.get("source_version")).longValue(),
                                    employee.get("source_order_kind").toString());
                    return Map.of("id", id);
                });
    }

    public List<Map<String, Object>> list(String tenant) {
        return bookings.list(tenant);
    }
}
