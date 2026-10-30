package com.moveinsync.roster;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.OffsetDateTime;

public final class Models {
    private Models() {}

    public record HrEvent(
            String employeeId,
            Long version,
            @JsonAlias({"timestamp", "updatedAt"}) OffsetDateTime sourceTimestamp,
            String type,
            String name,
            String email,
            String phone,
            String manager,
            String office,
            String shift) {
        public HrEvent(
                String employeeId,
                long version,
                String type,
                String name,
                String email,
                String phone,
                String manager,
                String office,
                String shift) {
            this(employeeId, version, null, type, name, email, phone, manager, office, shift);
        }
    }

    public enum OrderKind {
        VERSION,
        TIMESTAMP
    }

    public record SourceOrder(OrderKind kind, long value, OffsetDateTime timestamp) {
        public static SourceOrder version(long value) {
            return new SourceOrder(OrderKind.VERSION, value, null);
        }
    }

    public record Result(String employeeId, String status, String code) {}

    public record TransportFields(String pickupPoint, String assistance) {}

    public record Approval(String reason) {}

    public static class Invalid extends RuntimeException {
        public final String code;

        public Invalid(String code) {
            super(code);
            this.code = code;
        }
    }

    public static String validate(HrEvent e) {
        if (e == null || e.employeeId() == null || !e.employeeId().matches("[A-Za-z0-9_-]{1,80}"))
            return "INVALID_EMPLOYEE_ID";
        if (e.version() != null && e.version() < 0) return "INVALID_VERSION";
        if (!java.util.Set.of("CREATE", "UPDATE", "DEACTIVATE")
                .contains(e.type() == null ? "" : e.type())) return "INVALID_TYPE";
        for (String value :
                new String[] {e.name(), e.email(), e.phone(), e.manager(), e.office(), e.shift()}) {
            if (value != null && value.indexOf('\0') >= 0) return "INVALID_TEXT";
        }
        if ("DEACTIVATE".equals(e.type())) return null;
        if (e.name() == null || e.name().isBlank() || e.name().length() > 200)
            return "INVALID_NAME";
        if (e.email() == null
                || e.email().length() > 254
                || !e.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) return "INVALID_EMAIL";
        if (e.phone() == null || !e.phone().matches("\\+?[0-9]{8,15}")) return "INVALID_PHONE";
        if (e.office() == null || e.office().length() > 80) return "INVALID_OFFICE";
        if (e.manager() != null && e.manager().length() > 80) return "INVALID_MANAGER";
        if (e.shift() != null && e.shift().length() > 80) return "INVALID_SHIFT";
        return null;
    }
}
