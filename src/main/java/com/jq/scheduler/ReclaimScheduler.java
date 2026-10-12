package com.jq.scheduler;

import com.jq.service.ReclaimService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jobqueue.reclaim.enabled", havingValue = "true", matchIfMissing = true)
public class ReclaimScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReclaimScheduler.class);

    private final ReclaimService reclaimService;

    public ReclaimScheduler(ReclaimService reclaimService) {
        this.reclaimService = reclaimService;
    }

    @Scheduled(fixedDelayString = "${jobqueue.reclaim.scan-interval-ms:30000}")
    public void run() {
        try {
            int count = reclaimService.reclaimStuckJobs();
            if (count > 0) {
                log.info("Reclaimer fixed {} stuck job(s)", count);
            }
        } catch (Exception e) {
            // retry next run
            log.error("Reclaimer run failed", e);
        }
    }
}
