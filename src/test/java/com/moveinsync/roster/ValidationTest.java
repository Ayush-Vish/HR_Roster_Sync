package com.moveinsync.roster;

import static org.assertj.core.api.Assertions.*;

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
}
