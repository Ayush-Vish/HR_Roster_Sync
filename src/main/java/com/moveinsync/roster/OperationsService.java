package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

@Service
public final class OperationsService {
    private final SnapshotService snapshots;
    private final OutboxRepository outbox;
    private final OrderingPolicy ordering;
    private final JobLauncher launcher;
    private final Job reconciliationJob;

    public OperationsService(
            SnapshotService snapshots,
            OutboxRepository outbox,
            OrderingPolicy ordering,
            JobLauncher launcher,
            Job reconciliationJob) {
        this.snapshots = snapshots;
        this.outbox = outbox;
        this.ordering = ordering;
        this.launcher = launcher;
        this.reconciliationJob = reconciliationJob;
    }

    public Map<String, Object> upload(
            String tenant, Long watermark, OffsetDateTime snapshotTimestamp, InputStream input)
            throws Exception {
        SourceOrder cutoff = ordering.resolve(watermark, snapshotTimestamp);
        UUID run = snapshots.stage(tenant, cutoff, input);
        JobExecution execution = launch(tenant, run, null);
        if (execution.getStatus() == BatchStatus.FAILED) throw new Invalid("RECONCILIATION_FAILED");
        return snapshots.get(tenant, run);
    }

    public Map<String, Object> resume(String tenant, UUID run) throws Exception {
        if (!snapshots.get(tenant, run).get("status").equals("STAGED"))
            throw new Invalid("RUN_NOT_READY");
        launch(tenant, run, System.currentTimeMillis());
        return snapshots.get(tenant, run);
    }

    public Map<String, Object> approve(String tenant, UUID run, String actor, String reason) {
        if (!snapshots.get(tenant, run).get("status").equals("BLOCKED"))
            throw new Invalid("RUN_NOT_BLOCKED");
        snapshots.apply(tenant, run, true, actor, reason);
        return snapshots.get(tenant, run);
    }

    public List<Map<String, Object>> runs(String tenant) {
        return snapshots.list(tenant);
    }

    public Map<String, Object> run(String tenant, UUID id) {
        return snapshots.get(tenant, id);
    }

    public List<Map<String, Object>> errors(String tenant, UUID id, long after) {
        return snapshots.errors(tenant, id, after);
    }

    public List<Map<String, Object>> outbox(String tenant) {
        return outbox.list(tenant);
    }

    public void retry(String tenant, long id) {
        if (!outbox.retry(tenant, id)) throw new Invalid("NOT_FOUND");
    }

    private JobExecution launch(String tenant, UUID run, Long retry) throws Exception {
        JobParametersBuilder params =
                new JobParametersBuilder()
                        .addString("tenant", tenant)
                        .addString("run", run.toString());
        if (retry != null) params.addLong("retry", retry);
        return launcher.run(reconciliationJob, params.toJobParameters());
    }
}
