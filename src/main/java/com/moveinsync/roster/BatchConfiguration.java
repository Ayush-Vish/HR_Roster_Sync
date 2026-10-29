package com.moveinsync.roster;

import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchConfiguration {
    @Bean
    Job reconciliationJob(
            JobRepository repository,
            PlatformTransactionManager manager,
            SnapshotService snapshots) {
        var step =
                new StepBuilder("reconcile-staged-file", repository)
                        .tasklet(
                                (contribution, context) -> {
                                    var params = context.getStepContext().getJobParameters();
                                    snapshots.apply(
                                            (String) params.get("tenant"),
                                            UUID.fromString((String) params.get("run")),
                                            false,
                                            null,
                                            null);
                                    return RepeatStatus.FINISHED;
                                },
                                manager)
                        .build();
        return new JobBuilder("reconciliation", repository).start(step).build();
    }
}
