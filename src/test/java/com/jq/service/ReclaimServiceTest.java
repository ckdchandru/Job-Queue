package com.jq.service;

import com.jq.BaseIntegrationTest;
import com.jq.handler.HandlerRegistry;
import com.jq.repository.QueueRepository;
import com.jq.scheduler.ReclaimScheduler;
import com.jq.worker.Worker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReclaimServiceTest extends BaseIntegrationTest {

    @Autowired
    private ReclaimService reclaimService;

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private HandlerRegistry handlerRegistry;

    private Instant tenMinutesAgo() {
        return Instant.now().minus(10, ChronoUnit.MINUTES);
    }

    @Test
    void nothingToReclaimReturnsZero() {
        insertPendingJob("SEND_EMAIL");
        assertThat(reclaimService.reclaimStuckJobs()).isZero();
    }

    @Test
    void stuckJobGoesBackToPending() {
        UUID id = insertProcessingJob("SEND_EMAIL", 0, 5, tenMinutesAgo());

        assertThat(reclaimService.reclaimStuckJobs()).isEqualTo(1);

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo(ReclaimService.STUCK_REASON);
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("locked_at")).isNull();
    }

    @Test
    void stuckJobOnLastAttemptGoesToDead() {
        UUID id = insertProcessingJob("SEND_EMAIL", 4, 5, tenMinutesAgo());

        reclaimService.reclaimStuckJobs();

        assertThat(statusOf(id)).isEqualTo("DEAD");
        assertThat(attemptsOf(id)).isEqualTo(5);
    }

    @Test
    void recentlyClaimedJobIsLeftAlone() {
        UUID id = insertProcessingJob("SEND_EMAIL", 0, 5, Instant.now().minusSeconds(10));

        assertThat(reclaimService.reclaimStuckJobs()).isZero();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");
    }

    @Test
    void finishedJobsAreIgnored() {
        UUID completed = insertCompletedJob("SEND_EMAIL");
        UUID dead = insertDeadJob("SEND_EMAIL", tenMinutesAgo());

        assertThat(reclaimService.reclaimStuckJobs()).isZero();
        assertThat(statusOf(completed)).isEqualTo("COMPLETED");
        assertThat(statusOf(dead)).isEqualTo("DEAD");
    }

    @Test
    void reclaimWorksInBatches() {
        for (int i = 0; i < 60; i++) {
            insertProcessingJob("SEND_EMAIL", 0, 5, tenMinutesAgo());
        }

        assertThat(reclaimService.reclaimStuckJobs()).isEqualTo(50);
        assertThat(reclaimService.reclaimStuckJobs()).isEqualTo(10);
        assertThat(countByStatus("PENDING")).isEqualTo(60);
    }

    @Test
    void schedulerRunsTheReclaimer() {
        UUID id = insertProcessingJob("SEND_EMAIL", 0, 5, tenMinutesAgo());

        new ReclaimScheduler(reclaimService).run();

        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void reclaimedJobCanBeProcessedAgain() {
        UUID id = insertProcessingJob("SEND_EMAIL", 0, 5, tenMinutesAgo());
        reclaimService.reclaimStuckJobs();

        // skip the backoff
        jdbcTemplate.update("UPDATE jobs SET available_at = now() - interval '1 second' WHERE id = ?", id);

        Worker worker = new Worker("w1", queueRepository, handlerRegistry, new RetryBackoff(10, 100, 0), 10);
        assertThat(worker.processNextJob()).isTrue();
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
    }
}
