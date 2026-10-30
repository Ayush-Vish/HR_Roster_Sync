package com.moveinsync.roster;

import static org.assertj.core.api.Assertions.*;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ValidationTest {
    @Test
    void validatesTombstoneWithoutPrivateFields() {
        assertThat(
                        Models.validate(
                                new Models.HrEvent(
                                        "E1", 1, "DEACTIVATE", null, null, null, null, null, null)))
                .isNull();
        assertThat(
                        Models.validate(
                                new Models.HrEvent(
                                        "E1",
                                        -1,
                                        "DEACTIVATE",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)))
                .isEqualTo("INVALID_VERSION");
        assertThat(Models.validate(null)).isEqualTo("INVALID_EMPLOYEE_ID");
    }

    @Test
    void orderingModeSelectsAndRequiresTheConfiguredSourceValue() {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        var preferred = new OrderingPolicy("VERSION_PREFERRED");
        var timestampOnly = new OrderingPolicy("TIMESTAMP_ONLY");

        assertThat(preferred.resolve(7L, timestamp).kind()).isEqualTo(Models.OrderKind.VERSION);
        assertThat(preferred.resolve(null, timestamp).kind()).isEqualTo(Models.OrderKind.TIMESTAMP);
        assertThat(timestampOnly.resolve(7L, timestamp).kind())
                .isEqualTo(Models.OrderKind.TIMESTAMP);
        assertThatThrownBy(() -> new OrderingPolicy("VERSION_ONLY").resolve(null, timestamp))
                .isInstanceOf(Models.Invalid.class)
                .hasMessage("VERSION_REQUIRED");
    }
}
