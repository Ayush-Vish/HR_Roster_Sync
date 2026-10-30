package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = "roster.worker-enabled=false")
@AutoConfigureMockMvc
class RosterIntegrationTest {
    static PostgreSQLContainer<?> container;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            props.add("spring.datasource.url", container::getJdbcUrl);
            props.add("spring.datasource.username", container::getUsername);
            props.add("spring.datasource.password", container::getPassword);
        } else {
            props.add("spring.datasource.url", () -> url);
            props.add(
                    "spring.datasource.username",
                    () -> System.getenv().getOrDefault("TEST_DB_USER", "roster"));
            props.add(
                    "spring.datasource.password",
                    () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "roster-local"));
        }
    }

    @Autowired RosterService roster;
    @Autowired SnapshotService snapshots;
    @Autowired OutboxWorker worker;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        db.execute(
                "TRUNCATE bookings,consumed_events,outbox,sync_errors,sync_stage,sync_runs,employees RESTART IDENTITY CASCADE");
        db.update("UPDATE tenants SET snapshot_watermark=-1,source_order_kind=NULL");
    }

    HrEvent event(String id, long version) {
        return new HrEvent(
                id,
                version,
                "UPDATE",
                "Sample",
                "sample@example.test",
                "919000000001",
                "M1",
                "BLR",
                "DAY");
    }

    UUID snapshot(long watermark, String rows) throws Exception {
        return snapshots.stage(
                "acme",
                watermark,
                new ByteArrayInputStream(
                        ("employeeId,version,name,email,phone,manager,office,shift\n" + rows)
                                .getBytes(StandardCharsets.UTF_8)));
    }

    String row(String id, long version) {
        return id + "," + version + ",Sample,sample@example.test,919000000001,M1,BLR,DAY\n";
    }

    @Test
    void duplicateStaleAndOwnership() {
        assertThat(roster.ingest("acme", event("E1", 7)).status()).isEqualTo("APPLIED");
        roster.transport("acme", "E1", new TransportFields("Gate A", "Wheelchair"));
        assertThat(roster.ingest("acme", event("E1", 6)).status()).isEqualTo("IGNORED");
        assertThat(roster.ingest("acme", event("E1", 7)).status()).isEqualTo("IGNORED");
        roster.ingest("acme", event("E1", 8));
        assertThat(roster.get("acme", "E1"))
                .containsEntry("pickup_point", "Gate A")
                .containsEntry("assistance", "Wheelchair");
        assertThat(db.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentUpdatesKeepHighestVersion() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Result>> tasks = new ArrayList<>();
            for (int i = 1; i <= 40; i++) {
                int version = i;
                tasks.add(() -> roster.ingest("acme", event("E1", version)));
            }
            Collections.shuffle(tasks);
            for (var result : executor.invokeAll(tasks)) result.get();
        }
        assertThat(roster.get("acme", "E1").get("source_version")).isEqualTo(40L);
    }

    @Test
    void badRecordDoesNotFailBatchAndTenantIsolation() throws Exception {
        mvc.perform(
                        post("/api/events")
                                .with(httpBasic("acme-hr", "local-demo-password"))
                                .contentType("application/json")
                                .content(
                                        "[{\"employeeId\":\"E1\",\"version\":1,\"type\":\"UPDATE\",\"name\":\"A\",\"email\":\"a@example.test\",\"phone\":\"919000000001\",\"office\":\"BLR\"},{\"employeeId\":\"E2\",\"version\":1,\"type\":\"UPDATE\",\"name\":\"B\",\"email\":\"PRIVATE_BAD_VALUE\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPLIED"))
                .andExpect(jsonPath("$[1].code").value("INVALID_EMAIL"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "PRIVATE_BAD_VALUE"))));
        mvc.perform(get("/api/employees/E1").with(httpBasic("globex-hr", "local-demo-password")))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/events")
                                .with(httpBasic("acme-admin", "local-demo-password"))
                                .contentType("application/json")
                                .content("[]"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
    }

    @Test
    void safetyBrakeApprovalAndReplay() throws Exception {
        roster.ingest("acme", event("E1", 1));
        roster.ingest("acme", event("E2", 2));
        UUID run = snapshot(10, row("E1", 3));
        snapshots.apply("acme", run, false, null, null);
        assertThat(snapshots.get("acme", run).get("status")).isEqualTo("BLOCKED");
        assertThat(roster.get("acme", "E2").get("active")).isEqualTo(true);
        snapshots.apply("acme", run, true, "operator", "Confirmed complete export");
        assertThat(roster.get("acme", "E2").get("active")).isEqualTo(false);
        int before = db.queryForObject("SELECT count(*) FROM outbox", Integer.class);
        snapshots.apply("acme", run, true, "operator", "Replay");
        UUID replay = snapshot(10, row("E1", 3));
        snapshots.apply("acme", replay, false, null, null);
        assertThat(db.queryForObject("SELECT count(*) FROM outbox", Integer.class))
                .isEqualTo(before);
        assertThat(roster.ingest("acme", event("E3", 9)).status()).isEqualTo("IGNORED");
    }

    @Test
    void newerLiveUpdatesSurviveOldSnapshot() throws Exception {
        roster.ingest("acme", event("E1", 100));
        UUID run = snapshot(10, "");
        snapshots.apply("acme", run, false, null, null);
        assertThat(roster.get("acme", "E1"))
                .containsEntry("source_version", 100L)
                .containsEntry("active", true);
    }

    @Test
    void invalidSnapshotRowsNeverCauseDeactivation() throws Exception {
        roster.ingest("acme", event("E1", 1));
        roster.ingest("acme", event("E2", 2));
        UUID run = snapshot(10, row("E1", 3) + "E2,4,Sample,invalid,919000000001,M1,BLR,DAY\n");
        snapshots.apply("acme", run, true, "operator", "Checked source");
        assertThat(snapshots.get("acme", run).get("status")).isEqualTo("PARTIAL");
        assertThat(roster.get("acme", "E2").get("active")).isEqualTo(true);
    }

    @Test
    void unknownOfficeAndDuplicateRowsReported() throws Exception {
        assertThat(
                        roster.ingest(
                                        "acme",
                                        new HrEvent(
                                                "E1",
                                                1,
                                                "UPDATE",
                                                "A",
                                                "a@example.test",
                                                "919000000001",
                                                null,
                                                "UNKNOWN",
                                                null))
                                .code())
                .isEqualTo("UNKNOWN_OFFICE");
        UUID run = snapshot(10, row("E1", 1) + row("E1", 1));
        snapshots.apply("acme", run, false, null, null);
        assertThat(snapshots.get("acme", run).get("errors")).isEqualTo(1L);
    }

    @Test
    void outboxSurvivesPublisherAbsenceAndDeduplicates() {
        roster.ingest("acme", event("E1", 1));
        db.update(
                "INSERT INTO bookings(id,tenant_id,employee_id,starts_at) VALUES (?,'acme','E1',now()+interval '1 day')",
                UUID.randomUUID());
        roster.ingest(
                "acme", new HrEvent("E1", 2, "DEACTIVATE", null, null, null, null, null, null));
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM outbox WHERE delivered_at IS NULL",
                                Integer.class))
                .isEqualTo(2);
        // A fresh worker represents restart after commit but before any delivery.
        worker.drain(100);
        assertThat(db.queryForMap("SELECT status,seat_reserved FROM bookings"))
                .containsEntry("status", "CANCELLED")
                .containsEntry("seat_reserved", false);
        db.update("UPDATE outbox SET delivered_at=NULL");
        worker.drain(100);
        assertThat(db.queryForObject("SELECT count(*) FROM consumed_events", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void databaseFailureRollsBackEmployeeAndOutboxTogether() {
        db.execute(
                "ALTER TABLE outbox ADD CONSTRAINT injected_failure CHECK (employee_id <> 'FAIL')");
        try {
            assertThatThrownBy(() -> roster.ingest("acme", event("FAIL", 1)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM employees WHERE employee_id='FAIL'",
                                    Integer.class))
                    .isZero();
        } finally {
            db.execute("ALTER TABLE outbox DROP CONSTRAINT injected_failure");
        }
    }

    @Test
    void csvEndpointRunsBatchJob() throws Exception {
        var file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "employees.csv",
                        "text/csv",
                        ("employeeId,version,name,email,phone,manager,office,shift\n"
                                        + row("E1", 1))
                                .getBytes(StandardCharsets.UTF_8));
        mvc.perform(
                        multipart("/api/snapshots")
                                .file(file)
                                .param("watermark", "10")
                                .with(httpBasic("acme-hr", "local-demo-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void outboxRetriesWithoutPartialEffects() {
        roster.ingest("acme", event("E1", 1));
        worker.drain(10);
        db.update(
                "INSERT INTO bookings(id,tenant_id,employee_id,starts_at) VALUES (?,'acme','E1',now()+interval '1 day')",
                UUID.randomUUID());
        roster.ingest(
                "acme", new HrEvent("E1", 2, "DEACTIVATE", null, null, null, null, null, null));
        db.execute(
                "ALTER TABLE bookings ADD CONSTRAINT injected_cancel_failure CHECK(status <> 'CANCELLED')");
        try {
            worker.drain(10);
            assertThat(
                            db.queryForObject(
                                    "SELECT attempts FROM outbox WHERE source_version=2",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM consumed_events", Integer.class))
                    .isEqualTo(1);
            assertThat(db.queryForObject("SELECT seat_reserved FROM bookings", Boolean.class))
                    .isTrue();
        } finally {
            db.execute("ALTER TABLE bookings DROP CONSTRAINT injected_cancel_failure");
        }
        db.update("UPDATE outbox SET next_attempt=now()");
        worker.drain(10);
        assertThat(db.queryForObject("SELECT seat_reserved FROM bookings", Boolean.class))
                .isFalse();
    }

    @Test
    void missingAndWrongTypeVersionsArePerRecordErrors() throws Exception {
        mvc.perform(
                        post("/api/events")
                                .with(httpBasic("acme-hr", "local-demo-password"))
                                .contentType("application/json")
                                .content(
                                        "[{\"employeeId\":\"E1\",\"type\":\"DEACTIVATE\"},{\"employeeId\":\"E2\",\"version\":\"oops\",\"type\":\"DEACTIVATE\"},{\"employeeId\":\"E3\",\"version\":1,\"type\":\"DEACTIVATE\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ORDERING_VALUE_REQUIRED"))
                .andExpect(jsonPath("$[1].code").value("INVALID_VERSION"))
                .andExpect(jsonPath("$[2].status").value("APPLIED"));
    }

    @Test
    void timestampEventsOrderSafelyAndVersionWinsWhenBothArePresent() throws Exception {
        OffsetDateTime newer = OffsetDateTime.parse("2026-01-02T10:00:00Z");
        OffsetDateTime older = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        HrEvent staleTimestamp =
                new HrEvent(
                        "T1",
                        null,
                        older,
                        "UPDATE",
                        "Older",
                        "older@example.test",
                        "919000000001",
                        "M1",
                        "BLR",
                        "DAY");

        mvc.perform(
                        post("/api/events")
                                .with(httpBasic("acme-hr", "local-demo-password"))
                                .contentType("application/json")
                                .content(
                                        "[{\"employeeId\":\"T1\",\"sourceTimestamp\":\"2026-01-02T10:00:00Z\",\"type\":\"UPDATE\",\"name\":\"Newer\",\"email\":\"newer@example.test\",\"phone\":\"919000000001\",\"manager\":\"M1\",\"office\":\"BLR\",\"shift\":\"DAY\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPLIED"));
        assertThat(roster.ingest("acme", staleTimestamp).status()).isEqualTo("IGNORED");
        assertThat(roster.get("acme", "T1"))
                .containsEntry("name", "Newer")
                .containsEntry("source_order_kind", "TIMESTAMP");

        HrEvent both =
                new HrEvent(
                        "V1",
                        7L,
                        newer,
                        "UPDATE",
                        "Versioned",
                        "versioned@example.test",
                        "919000000002",
                        "M1",
                        "BLR",
                        "DAY");
        assertThat(roster.ingest("globex", both).status()).isEqualTo("APPLIED");
        assertThat(roster.get("globex", "V1"))
                .containsEntry("source_version", 7L)
                .containsEntry("source_order_kind", "VERSION");
    }

    @Test
    void timestampSnapshotUsesTimestampCutoff() throws Exception {
        OffsetDateTime rowTime = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        OffsetDateTime cutoffTime = OffsetDateTime.parse("2026-01-02T10:00:00Z");
        UUID run =
                snapshots.stage(
                        "acme",
                        new OrderingPolicy("VERSION_PREFERRED").resolve(null, cutoffTime),
                        new ByteArrayInputStream(
                                ("employeeId,sourceTimestamp,name,email,phone,manager,office,shift\n"
                                                + "T1,"
                                                + rowTime
                                                + ",Sample,sample@example.test,919000000001,M1,BLR,DAY\n")
                                        .getBytes(StandardCharsets.UTF_8)));
        snapshots.apply("acme", run, false, null, null);

        assertThat(snapshots.get("acme", run))
                .containsEntry("status", "COMPLETED")
                .containsEntry("source_order_kind", "TIMESTAMP");
        assertThat(roster.get("acme", "T1")).containsEntry("source_order_kind", "TIMESTAMP");
    }

    @Test
    void liveUpdateRacingSnapshotRemainsNewest() throws Exception {
        roster.ingest("acme", event("E1", 1));
        UUID run = snapshot(10, row("E1", 5));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var apply =
                    executor.submit(
                            () -> {
                                gate.await();
                                snapshots.apply("acme", run, false, null, null);
                                return true;
                            });
            var live =
                    executor.submit(
                            () -> {
                                gate.await();
                                return roster.ingest("acme", event("E1", 20));
                            });
            gate.countDown();
            apply.get();
            live.get();
        }
        assertThat(roster.get("acme", "E1"))
                .containsEntry("source_version", 20L)
                .containsEntry("active", true);
    }

    @Test
    void malformedCsvRowDoesNotAffectValidRows() throws Exception {
        UUID run = snapshot(10, row("E1", 1).stripTrailing() + ",unexpected\n" + row("E2", 2));
        snapshots.apply("acme", run, false, null, null);
        assertThat(snapshots.get("acme", run))
                .containsEntry("status", "PARTIAL")
                .containsEntry("errors", 1L);
        assertThat(roster.get("acme", "E2").get("active")).isEqualTo(true);
        assertThatThrownBy(() -> roster.get("acme", "E1")).isInstanceOf(Invalid.class);
    }

    @Test
    void duplicateCsvHeadersAreRejectedBeforeApplication() {
        String csv = "employeeId,version,name,email,phone,manager,office,shift,office\n";
        assertThatThrownBy(
                        () ->
                                snapshots.stage(
                                        "acme",
                                        10,
                                        new ByteArrayInputStream(
                                                csv.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(Invalid.class);
        assertThat(db.queryForObject("SELECT count(*) FROM employees", Integer.class)).isZero();
    }

    @Test
    void invalidDatabaseTextIsRejectedPerRecord() throws Exception {
        mvc.perform(
                        post("/api/events")
                                .with(httpBasic("acme-hr", "local-demo-password"))
                                .contentType("application/json")
                                .content(
                                        "[{\"employeeId\":\"E1\",\"version\":1,\"type\":\"DEACTIVATE\",\"name\":\"bad\\u0000text\"},{\"employeeId\":\"E2\",\"version\":2,\"type\":\"DEACTIVATE\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("INVALID_TEXT"))
                .andExpect(jsonPath("$[1].status").value("APPLIED"));
    }

    @Test
    void delayedDeactivationDoesNotCancelReactivatedEmployeesNewBooking() throws Exception {
        roster.ingest("acme", event("E1", 1));
        mvc.perform(
                        post("/api/bookings")
                                .with(httpBasic("acme-admin", "local-demo-password"))
                                .contentType("application/json")
                                .content(
                                        "{\"employeeId\":\"E1\",\"startsAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk());
        roster.ingest(
                "acme", new HrEvent("E1", 2, "DEACTIVATE", null, null, null, null, null, null));
        roster.ingest("acme", event("E1", 3));
        mvc.perform(
                        post("/api/bookings")
                                .with(httpBasic("acme-admin", "local-demo-password"))
                                .contentType("application/json")
                                .content(
                                        "{\"employeeId\":\"E1\",\"startsAt\":\"2099-01-02T00:00:00Z\"}"))
                .andExpect(status().isOk());
        // A transaction could have started before waiting on the tenant lock. Time is not order.
        db.update("UPDATE bookings SET created_at=now()-interval '1 day' WHERE employee_version=3");
        worker.drain(20);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM bookings WHERE employee_version=1",
                                String.class))
                .isEqualTo("CANCELLED");
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM bookings WHERE employee_version=3",
                                String.class))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void exactlyThirtyPercentMissingBlocksAndCrossTenantApprovalIsDenied() throws Exception {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            roster.ingest("acme", event("E" + i, i));
            if (i < 7) csv.append(row("E" + i, i));
        }
        UUID run = snapshot(20, csv.toString());
        snapshots.apply("acme", run, false, null, null);
        assertThat(snapshots.get("acme", run))
                .containsEntry("status", "BLOCKED")
                .containsEntry("missing", 3L);
        mvc.perform(
                        post("/api/snapshots/" + run + "/approve")
                                .with(httpBasic("globex-operator", "local-demo-password"))
                                .contentType("application/json")
                                .content("{\"reason\":\"test\"}"))
                .andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT count(*) FROM employees WHERE active", Integer.class))
                .isEqualTo(10);
    }

    @Test
    void employeeRoleCanReadOnlyItsOwnProfile() throws Exception {
        roster.ingest("acme", event("E001", 1));
        mvc.perform(
                        get("/api/me/employee")
                                .with(httpBasic("acme-employee-E001", "local-demo-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee_id").value("E001"));
        mvc.perform(
                        get("/api/employees/E001")
                                .with(httpBasic("acme-employee-E001", "local-demo-password")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/employees")
                                .with(httpBasic("acme-employee-E001", "local-demo-password")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me/employee").with(httpBasic("globex-hr", "local-demo-password")))
                .andExpect(status().isForbidden());
    }
}
