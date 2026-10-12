package com.jq.service;

import com.jq.model.StuckJob;
import com.jq.repository.QueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ReclaimService {

    private static final Logger log = LoggerFactory.getLogger(ReclaimService.class);

    public static final String STUCK_REASON = "worker timed out, job reclaimed";

    private final QueueRepository queueRepository;
    private final RetryBackoff retryBackoff;
    private final long visibilityTimeoutMs;
    private final int batchSize;

    public ReclaimService(QueueRepository queueRepository, RetryBackoff retryBackoff,
                          @Value("${jobqueue.reclaim.visibility-timeout-ms:300000}") long visibilityTimeoutMs,
                          @Value("${jobqueue.reclaim.batch-size:50}") int batchSize) {
        this.queueRepository = queueRepository;
        this.retryBackoff = retryBackoff;
        this.visibilityTimeoutMs = visibilityTimeoutMs;
        this.batchSize = batchSize;
    }

    // one transaction holds locks
    @Transactional
    public int reclaimStuckJobs() {
        Instant cutoff = Instant.now().minusMillis(visibilityTimeoutMs);
        List<StuckJob> stuckJobs = queueRepository.findStuckJobs(cutoff, batchSize);

        for (StuckJob job : stuckJobs) {
            int attempts = job.attempts() + 1;
            if (attempts < job.maxAttempts()) {
                Instant nextRunAt = Instant.now().plusMillis(retryBackoff.getDelayMillis(attempts));
                queueRepository.releaseStuckJob(job.id(), attempts, nextRunAt, STUCK_REASON);
                log.warn("Reclaimed stuck job {} -> PENDING, attempts={}", job.id(), attempts);
            } else {
                // exhausted
                queueRepository.killStuckJob(job.id(), attempts, STUCK_REASON);
                log.warn("Reclaimed stuck job {} -> DEAD, attempts={}", job.id(), attempts);
            }
        }
        return stuckJobs.size();
    }
}
