package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;
import static com.moveinsync.roster.Security.employeeId;
import static com.moveinsync.roster.Security.tenant;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class Api {
    private final RosterService roster;
    private final SnapshotService snapshots;
    private final JdbcTemplate db;
    private final JobLauncher launcher;
    private final Job job;
    private final TransactionTemplate tx;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public Api(
            RosterService roster,
            SnapshotService snapshots,
            JdbcTemplate db,
            JobLauncher launcher,
            Job reconciliationJob,
            TransactionTemplate tx,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.roster = roster;
        this.snapshots = snapshots;
        this.db = db;
        this.launcher = launcher;
        this.job = reconciliationJob;
        this.tx = tx;
        this.mapper = mapper;
    }

    @PostMapping("/events")
    public List<Result> events(
            Authentication a, @RequestBody List<com.fasterxml.jackson.databind.JsonNode> events) {
        if (events == null || events.isEmpty() || events.size() > 1000)
            throw new Invalid("BATCH_SIZE_1_TO_1000");
        return events.stream()
                .map(
                        node -> {
                            if (node == null
                                    || !node.isObject()
                                    || !node.has("version")
                                    || !node.get("version").isIntegralNumber()
                                    || !node.get("version").canConvertToLong())
                                return new Result(null, "REJECTED", "INVALID_VERSION");
                            HrEvent e;
                            try {
                                e = mapper.treeToValue(node, HrEvent.class);
                            } catch (Exception ex) {
                                return new Result(null, "REJECTED", "INVALID_RECORD");
                            }
                            return roster.ingest(tenant(a), e);
                        })
                .toList();
    }

    @GetMapping("/employees")
    public Object employees(
            Authentication a,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "100") int limit) {
        return roster.list(tenant(a), after, limit);
    }

    @GetMapping("/employees/{id}")
    public Object employee(Authentication a, @PathVariable String id) {
        return roster.get(tenant(a), id);
    }

    @GetMapping("/me/employee")
    public Object currentEmployee(Authentication authentication) {
        return roster.get(tenant(authentication), employeeId(authentication));
    }

    @PatchMapping("/employees/{id}/transport")
    public Object transport(
            Authentication a, @PathVariable String id, @RequestBody TransportFields fields) {
        roster.transport(tenant(a), id, fields);
        return Map.of("status", "UPDATED");
    }

    @PostMapping(value = "/snapshots", consumes = "multipart/form-data")
    public Object upload(
            Authentication a, @RequestParam long watermark, @RequestParam MultipartFile file)
            throws Exception {
        UUID run;
        try (var input = file.getInputStream()) {
            run = snapshots.stage(tenant(a), watermark, input);
        }
        var execution =
                launcher.run(
                        job,
                        new JobParametersBuilder()
                                .addString("tenant", tenant(a))
                                .addString("run", run.toString())
                                .toJobParameters());
        if (execution.getStatus() == BatchStatus.FAILED) throw new Invalid("RECONCILIATION_FAILED");
        return snapshots.get(tenant(a), run);
    }

    @GetMapping("/snapshots")
    public Object runs(Authentication a) {
        return db.queryForList(
                "SELECT * FROM sync_runs WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
                tenant(a));
    }

    @GetMapping("/snapshots/{id}")
    public Object run(Authentication a, @PathVariable UUID id) {
        return snapshots.get(tenant(a), id);
    }

    @PostMapping("/snapshots/{id}/resume")
    public Object resume(Authentication a, @PathVariable UUID id) throws Exception {
        var state = snapshots.get(tenant(a), id);
        if (!state.get("status").equals("STAGED")) throw new Invalid("RUN_NOT_READY");
        launcher.run(
                job,
                new JobParametersBuilder()
                        .addString("tenant", tenant(a))
                        .addString("run", id.toString())
                        .addLong("retry", System.currentTimeMillis())
                        .toJobParameters());
        return snapshots.get(tenant(a), id);
    }

    @GetMapping("/snapshots/{id}/errors")
    public Object errors(
            Authentication a, @PathVariable UUID id, @RequestParam(defaultValue = "0") long after) {
        snapshots.get(tenant(a), id);
        return db.queryForList(
                "SELECT id,row_number,employee_id,code FROM sync_errors WHERE run_id=? AND id>? ORDER BY id LIMIT 200",
                id,
                after);
    }

    @PostMapping("/snapshots/{id}/approve")
    public Object approve(Authentication a, @PathVariable UUID id, @RequestBody Approval approval) {
        if (!snapshots.get(tenant(a), id).get("status").equals("BLOCKED"))
            throw new Invalid("RUN_NOT_BLOCKED");
        snapshots.apply(tenant(a), id, true, a.getName(), approval.reason());
        return snapshots.get(tenant(a), id);
    }

    public record Booking(String employeeId, OffsetDateTime startsAt) {}

    @PostMapping("/bookings")
    public Object booking(Authentication a, @RequestBody Booking booking) {
        if (booking.startsAt() == null || !booking.startsAt().isAfter(OffsetDateTime.now()))
            throw new Invalid("FUTURE_START_REQUIRED");
        return tx.execute(
                s -> {
                    db.queryForObject(
                            "SELECT id FROM tenants WHERE id=? FOR UPDATE",
                            String.class,
                            tenant(a));
                    var employee = roster.get(tenant(a), booking.employeeId());
                    if (!Boolean.TRUE.equals(employee.get("active")))
                        throw new Invalid("EMPLOYEE_INACTIVE");
                    UUID id = UUID.randomUUID();
                    db.update(
                            "INSERT INTO bookings(id,tenant_id,employee_id,starts_at,employee_version) VALUES (?,?,?,?,?)",
                            id,
                            tenant(a),
                            booking.employeeId(),
                            booking.startsAt(),
                            employee.get("source_version"));
                    return Map.of("id", id);
                });
    }

    @GetMapping("/bookings")
    public Object bookings(Authentication a) {
        return db.queryForList(
                "SELECT * FROM bookings WHERE tenant_id=? ORDER BY starts_at DESC LIMIT 200",
                tenant(a));
    }

    @GetMapping("/outbox")
    public Object outbox(Authentication a) {
        return db.queryForList(
                "SELECT * FROM outbox WHERE tenant_id=? ORDER BY id DESC LIMIT 200", tenant(a));
    }

    @PostMapping("/outbox/{id}/retry")
    public Object retry(Authentication a, @PathVariable long id) {
        int n =
                db.update(
                        "UPDATE outbox SET attempts=0,next_attempt=now() WHERE id=? AND tenant_id=? AND delivered_at IS NULL",
                        id,
                        tenant(a));
        if (n == 0) throw new Invalid("NOT_FOUND");
        return Map.of("status", "QUEUED");
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(Invalid.class)
        @ResponseBody
        org.springframework.http.ResponseEntity<?> invalid(Invalid e) {
            return org.springframework.http.ResponseEntity.status(
                            e.code.equals("NOT_FOUND") ? 404 : 422)
                    .body(Map.of("code", e.code));
        }

        @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.multipart.MultipartException.class
        })
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        Object malformed(Exception e) {
            return Map.of("code", "INVALID_REQUEST");
        }

        @ExceptionHandler(Exception.class)
        @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        Object unexpected(Exception e) {
            return Map.of("code", "INTERNAL_ERROR");
        }
    }
}
