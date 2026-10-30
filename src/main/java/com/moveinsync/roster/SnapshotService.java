package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SnapshotService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final double threshold;
    private final MeterRegistry metrics;

    public SnapshotService(
            JdbcTemplate db,
            TransactionTemplate tx,
            @Value("${roster.missing-threshold}") double threshold,
            MeterRegistry metrics) {
        if (!Double.isFinite(threshold) || threshold <= 0 || threshold > 1)
            throw new IllegalArgumentException("Invalid safety threshold");
        this.db = db;
        this.tx = tx;
        this.threshold = threshold;
        this.metrics = metrics;
    }

    public UUID stage(String tenant, long watermark, InputStream input) throws IOException {
        if (watermark < 0) throw new Invalid("INVALID_WATERMARK");
        UUID run = UUID.randomUUID();
        db.update(
                "INSERT INTO sync_runs(id,tenant_id,watermark,status) VALUES (?,?,?,'STAGING')",
                run,
                tenant,
                watermark);
        Set<String> offices =
                new HashSet<>(
                        db.queryForList(
                                "SELECT code FROM offices WHERE tenant_id=?",
                                String.class,
                                tenant));
        List<Object[]> chunk = new ArrayList<>(1000);
        List<Object[]> errorChunk = new ArrayList<>(1000);
        long rows = 0, errors = 0;
        try (var parser =
                CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                        .setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true)
                        .get()
                        .parse(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            if (!parser.getHeaderMap()
                    .keySet()
                    .containsAll(
                            List.of(
                                    "employeeId",
                                    "version",
                                    "name",
                                    "email",
                                    "phone",
                                    "manager",
                                    "office",
                                    "shift"))) throw new Invalid("INVALID_CSV_HEADERS");
            for (CSVRecord row : parser) {
                rows++;
                HrEvent e = null;
                String code = null;
                try {
                    if (!row.isConsistent()) throw new Invalid("INVALID_RECORD");
                    e =
                            new HrEvent(
                                    row.get("employeeId"),
                                    Long.parseLong(row.get("version")),
                                    "UPDATE",
                                    row.get("name"),
                                    row.get("email"),
                                    row.get("phone"),
                                    row.get("manager"),
                                    row.get("office"),
                                    row.get("shift"));
                    code = validate(e);
                    if (code == null && e.version() > watermark) code = "VERSION_AFTER_WATERMARK";
                    if (code == null && !offices.contains(e.office())) code = "UNKNOWN_OFFICE";
                } catch (RuntimeException ex) {
                    code = "INVALID_RECORD";
                }
                if (code != null) {
                    errors++;
                    errorChunk.add(new Object[] {run, rows, RosterService.safeId(e), code});
                    if (errorChunk.size() == 1000) {
                        flushErrors(errorChunk);
                        errorChunk.clear();
                    }
                } else {
                    chunk.add(
                            new Object[] {
                                run,
                                e.employeeId(),
                                e.version(),
                                e.name(),
                                e.email(),
                                e.phone(),
                                e.manager(),
                                e.office(),
                                e.shift()
                            });
                    if (chunk.size() == 1000) {
                        flush(chunk);
                        chunk.clear();
                    }
                }
            }
            flush(chunk);
            flushErrors(errorChunk);
            long staged =
                    db.queryForObject(
                            "SELECT count(*) FROM sync_stage WHERE run_id=?", Long.class, run);
            long duplicates = rows - errors - staged;
            if (duplicates > 0) {
                db.update(
                        "INSERT INTO sync_errors(run_id,row_number,code) VALUES (?,0,'DUPLICATE_EMPLOYEE_IDS')",
                        run);
                errors += duplicates;
            }
            db.update(
                    "UPDATE sync_runs SET rows_seen=?,errors=?,status='STAGED' WHERE id=?",
                    rows,
                    errors,
                    run);
            return run;
        } catch (Exception ex) {
            db.update(
                    "UPDATE sync_runs SET status='FAILED',rows_seen=?,errors=?,finished_at=now() WHERE id=?",
                    rows,
                    errors,
                    run);
            throw new Invalid("INVALID_CSV");
        }
    }

    private void flush(List<Object[]> chunk) {
        if (!chunk.isEmpty())
            db.batchUpdate(
                    "INSERT INTO sync_stage(run_id,employee_id,source_version,name,email,phone,manager,office,shift) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    chunk);
    }

    private void flushErrors(List<Object[]> errors) {
        if (!errors.isEmpty())
            db.batchUpdate(
                    "INSERT INTO sync_errors(run_id,row_number,employee_id,code) VALUES (?,?,?,?)",
                    errors);
    }

    public Map<String, Object> get(String tenant, UUID run) {
        var rows =
                db.queryForList("SELECT * FROM sync_runs WHERE id=? AND tenant_id=?", run, tenant);
        if (rows.isEmpty()) throw new Invalid("NOT_FOUND");
        return rows.getFirst();
    }

    public void apply(String tenant, UUID run, boolean approve, String actor, String reason) {
        long start = System.nanoTime();
        tx.executeWithoutResult(
                s -> {
                    long previous =
                            db.queryForObject(
                                    "SELECT snapshot_watermark FROM tenants WHERE id=? FOR UPDATE",
                                    Long.class,
                                    tenant);
                    Map<String, Object> state = get(tenant, run);
                    String status = state.get("status").toString();
                    if (status.equals("COMPLETED")) return;
                    if (!status.equals("STAGED") && !status.equals("BLOCKED"))
                        throw new Invalid("RUN_NOT_READY");
                    long watermark = ((Number) state.get("watermark")).longValue();
                    if (watermark <= previous) {
                        db.update(
                                "UPDATE sync_runs SET status='STALE',finished_at=now() WHERE id=?",
                                run);
                        return;
                    }
                    if (approve && (reason == null || reason.isBlank() || reason.length() > 500))
                        throw new Invalid("APPROVAL_REASON_REQUIRED");
                    if (approve && reason.indexOf('\0') >= 0) throw new Invalid("INVALID_TEXT");
                    long active =
                            db.queryForObject(
                                    "SELECT count(*) FROM employees WHERE tenant_id=? AND active",
                                    Long.class,
                                    tenant);
                    long missing =
                            db.queryForObject(
                                    "SELECT count(*) FROM employees e WHERE tenant_id=? AND active AND source_version<=? AND NOT EXISTS(SELECT 1 FROM sync_stage s WHERE s.run_id=? AND s.employee_id=e.employee_id)",
                                    Long.class,
                                    tenant,
                                    watermark,
                                    run);
                    long errors = ((Number) state.get("errors")).longValue();
                    db.update(
                            "UPDATE sync_runs SET active_before=?,missing=? WHERE id=?",
                            active,
                            missing,
                            run);
                    // Invalid rows always suppress absence-based deactivation, even with approval.
                    boolean safety = active > 0 && (double) missing / active >= threshold;
                    if (safety && !approve) {
                        db.update("UPDATE sync_runs SET status='BLOCKED' WHERE id=?", run);
                        metrics.counter("roster.snapshots.blocked").increment();
                        return;
                    }
                    int applied =
                            db.update(
                                    """
                WITH changed AS (
                  INSERT INTO employees(tenant_id,employee_id,source_version,name,email,phone,manager,office,shift,active)
                  SELECT ?,employee_id,source_version,name,email,phone,manager,office,shift,true FROM sync_stage WHERE run_id=?
                  ON CONFLICT(tenant_id,employee_id) DO UPDATE SET source_version=excluded.source_version,
                    name=excluded.name,email=excluded.email,phone=excluded.phone,manager=excluded.manager,
                    office=excluded.office,shift=excluded.shift,active=true,updated_at=now()
                  WHERE employees.source_version<excluded.source_version
                  RETURNING tenant_id,employee_id,source_version,active
                ) INSERT INTO outbox(tenant_id,employee_id,source_version,active,kind)
                  SELECT tenant_id,employee_id,source_version,active,'HR_CHANGE' FROM changed
                  ON CONFLICT DO NOTHING
                """,
                                    tenant,
                                    run);
                    if (errors == 0) {
                        applied +=
                                db.update(
                                        """
                    WITH changed AS (
                      UPDATE employees e SET active=false,source_version=?,updated_at=now()
                      WHERE tenant_id=? AND active AND source_version<=?
                        AND NOT EXISTS(SELECT 1 FROM sync_stage s WHERE s.run_id=? AND s.employee_id=e.employee_id)
                      RETURNING tenant_id,employee_id,source_version,active
                    ) INSERT INTO outbox(tenant_id,employee_id,source_version,active,kind)
                      SELECT tenant_id,employee_id,source_version,active,'RECONCILE_DEACTIVATE' FROM changed
                      ON CONFLICT DO NOTHING
                    """,
                                        watermark,
                                        tenant,
                                        watermark,
                                        run);
                        db.update(
                                "UPDATE tenants SET snapshot_watermark=? WHERE id=?",
                                watermark,
                                tenant);
                    }
                    db.update(
                            "UPDATE sync_runs SET status=?,applied=?,approved_by=?,approval_reason=?,finished_at=now() WHERE id=?",
                            errors == 0 ? "COMPLETED" : "PARTIAL",
                            applied,
                            approve ? actor : null,
                            approve ? reason : null,
                            run);
                });
        metrics.timer("roster.snapshot.apply")
                .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        org.slf4j.LoggerFactory.getLogger(SnapshotService.class)
                .atInfo()
                .addKeyValue("tenant", tenant)
                .addKeyValue("run_id", run)
                .addKeyValue("status", get(tenant, run).get("status"))
                .log("snapshot_processed");
    }
}
