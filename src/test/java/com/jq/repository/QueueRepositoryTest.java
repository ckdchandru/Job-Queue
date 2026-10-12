package com.jq.repository;

import com.jq.BaseIntegrationTest;
import com.jq.model.ClaimedJob;
import com.jq.model.StuckJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueueRepositoryTest extends BaseIntegrationTest {

    @Autowired
    private QueueRepository queueRepository;

    @Test
    void claimReturnsNullWhenQueueIsEmpty() {
        assertThat(queueRepository.claimNextJob("w1")).isNull();
    }

    @Test
    void claimMarksJobAsProcessing() {
        UUID id = insertPendingJob("SEND_EMAIL");

        ClaimedJob job = queueRepository.claimNextJob("w1");

        assertThat(job).isNotNull();
        assertThat(job.id()).isEqualTo(id);
        assertThat(job.jobType()).isEqualTo("SEND_EMAIL");
        assertThat(job.claimToken()).isNotNull();

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(row.get("locked_by")).isEqualTo("w1");
        assertThat(row.get("locked_at")).isNotNull();
        assertThat(row.get("claim_token")).isEqualTo(job.claimToken());
    }

    @Test
    void claimSkipsJobsScheduledForLater() {
        insertPendingJob("SEND_EMAIL", 5, Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(queueRepository.claimNextJob("w1")).isNull();
    }

    @Test
    void claimIgnoresJobsThatAreNotPending() {
        insertCompletedJob("SEND_EMAIL");
        insertDeadJob("SEND_EMAIL", Instant.now());
        insertProcessingJob("SEND_EMAIL", 0, 5, Instant.now());

        assertThat(queueRepository.claimNextJob("w1")).isNull();
    }

    @Test
    void claimPicksOldestJobFirst() {
        UUID newer = insertPendingJob("SEND_EMAIL", 5, Instant.now().minusSeconds(10));
        UUID older = insertPendingJob("SEND_EMAIL", 5, Instant.now().minusSeconds(60));

        assertThat(queueRepository.claimNextJob("w1").id()).isEqualTo(older);
        assertThat(queueRepository.claimNextJob("w1").id()).isEqualTo(newer);
    }

    @Test
    void sameJobIsNeverClaimedTwice() {
        insertPendingJob("SEND_EMAIL");

        assertThat(queueRepository.claimNextJob("w1")).isNotNull();
        assertThat(queueRepository.claimNextJob("w2")).isNull();
    }

    @Test
    void everyClaimGetsFreshToken() {
        UUID id = insertPendingJob("SEND_EMAIL");
        ClaimedJob first = queueRepository.claimNextJob("w1");
        queueRepository.markForRetry(id, first.claimToken(), 1, Instant.now().minusSeconds(5), "oops");

        ClaimedJob second = queueRepository.claimNextJob("w2");

        assertThat(second.id()).isEqualTo(id);
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
    }

    @Test
    void markCompletedWorksWithCorrectToken() {
        UUID id = insertPendingJob("SEND_EMAIL");
        ClaimedJob job = queueRepository.claimNextJob("w1");

        assertThat(queueRepository.markCompleted(id, job.claimToken())).isTrue();

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("locked_by")).isNull();
    }

    @Test
    void markCompletedFailsWithWrongToken() {
        UUID id = insertPendingJob("SEND_EMAIL");
        queueRepository.claimNextJob("w1");

        assertThat(queueRepository.markCompleted(id, UUID.randomUUID())).isFalse();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");
    }

    @Test
    void markForRetryPutsJobBackToPending() {
        UUID id = insertPendingJob("SEND_EMAIL");
        ClaimedJob job = queueRepository.claimNextJob("w1");
        Instant nextRun = Instant.now().plusSeconds(30);

        assertThat(queueRepository.markForRetry(id, job.claimToken(), 1, nextRun, "timeout")).isTrue();

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo("timeout");
        assertThat(row.get("claim_token")).isNull();
        Boolean inFuture = jdbcTemplate.queryForObject(
                "SELECT available_at > now() FROM jobs WHERE id = ?", Boolean.class, id);
        assertThat(inFuture).isTrue();
    }

    @Test
    void markDeadMovesJobToDead() {
        UUID id = insertPendingJob("SEND_EMAIL");
        ClaimedJob job = queueRepository.claimNextJob("w1");

        assertThat(queueRepository.markDead(id, job.claimToken(), 1, "fatal")).isTrue();

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("status")).isEqualTo("DEAD");
        assertThat(row.get("last_error")).isEqualTo("fatal");
        assertThat(row.get("claim_token")).isNull();
    }

    @Test
    void markDeadFailsWithWrongToken() {
        UUID id = insertPendingJob("SEND_EMAIL");
        queueRepository.claimNextJob("w1");

        assertThat(queueRepository.markDead(id, UUID.randomUUID(), 1, "fatal")).isFalse();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");
    }

    @Test
    void findStuckJobsReturnsOnlyOldProcessingJobs() {
        UUID stuck = insertProcessingJob("SEND_EMAIL", 0, 5, Instant.now().minus(10, ChronoUnit.MINUTES));
        insertProcessingJob("SEND_EMAIL", 0, 5, Instant.now());
        insertPendingJob("SEND_EMAIL");

        List<StuckJob> result = queueRepository.findStuckJobs(Instant.now().minus(5, ChronoUnit.MINUTES), 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(stuck);
    }

    @Test
    void requeueOnlyWorksForDeadJobs() {
        UUID dead = insertDeadJob("SEND_EMAIL", Instant.now());
        UUID pending = insertPendingJob("SEND_EMAIL");

        assertThat(queueRepository.requeueDeadJob(dead)).isTrue();
        assertThat(queueRepository.requeueDeadJob(pending)).isFalse();
        assertThat(statusOf(dead)).isEqualTo("PENDING");
    }
}
