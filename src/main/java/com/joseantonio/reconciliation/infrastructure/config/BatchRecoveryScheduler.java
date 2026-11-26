package com.joseantonio.reconciliation.infrastructure.config;

import com.joseantonio.reconciliation.application.port.in.RecoverBatchesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Failure recovery trigger: once at startup (resumes what a restart interrupted) and then periodically
 * (releases crashed batches, retries batches that were waiting for their source to be free).
 */
@Component
@ConditionalOnProperty(name = "reconciliation.processing.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class BatchRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchRecoveryScheduler.class);

    private final RecoverBatchesUseCase recovery;

    public BatchRecoveryScheduler(RecoverBatchesUseCase recovery) {
        this.recovery = recovery;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run();
    }

    @Scheduled(fixedDelayString = "${reconciliation.processing.recovery-interval}",
            initialDelayString = "${reconciliation.processing.recovery-interval}")
    public void run() {
        try {
            var report = recovery.recover();
            if (report.releasedStale() > 0 || report.dispatched() > 0) {
                log.info("Recovery: released {} stale batches, dispatched {} pending batches",
                        report.releasedStale(), report.dispatched());
            }
        } catch (RuntimeException e) {
            log.error("Batch recovery run failed; it will be retried on the next tick", e);
        }
    }
}
