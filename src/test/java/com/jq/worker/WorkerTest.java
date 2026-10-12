package com.jq.worker;

import com.jq.BaseIntegrationTest;
import com.jq.FakeHandler;
import com.jq.handler.HandlerRegistry;
import com.jq.handler.NonRetryableException;
import com.jq.repository.QueueRepository;
import com.jq.service.RetryBackoff;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTest extends BaseIntegrationTest {

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private HandlerRegistry realHandlers;

    private final RetryBackoff fastBackoff = new RetryBackoff(10, 100, 0);

    private Worker workerWith(HandlerRegistry registry) {
        return new Worker("test-worker", queueRepository, registry, fastBackoff, 10);
    }

    @Test
    void returnsFalseWhenNothingToDo() {
        assertThat(workerWith(realHandlers).processNextJob()).isFalse();
    }

    @Test
    void successfulJobIsCompleted() {
        UUID id = insertPendingJob("SEND_EMAIL");

        assertThat(workerWith(realHandlers).processNextJob()).isTrue();

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("claim_token")).isNull();
    }

    @Test
    void unknownJobTypeGoesToDead() {
        UUID id = insertPendingJob("NO_SUCH_TYPE");

        workerWith(realHandlers).processNextJob();

        assertThat(statusOf(id)).isEqualTo("DEAD");
        assertThat(rowOf(id).get("last_error").toString()).contains("no handler");
    }

    @Test
    void nonRetryableErrorGoesStraightToDead() {
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("BAD", (payload, key, attempt) -> {
            throw new NonRetryableException("invalid input");
        }));
        UUID id = insertPendingJob("BAD");

        workerWith(registry).processNextJob();

        assertThat(statusOf(id)).isEqualTo("DEAD");
        assertThat(attemptsOf(id)).isZero();
        assertThat(rowOf(id).get("last_error")).isEqualTo("invalid input");
    }

    @Test
    void retryableErrorSchedulesRetryWithBackoff() {
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("FLAKY", (payload, key, attempt) -> {
            throw new RuntimeException("connection timeout");
        }));
        UUID id = insertPendingJob("FLAKY");
        Worker worker = new Worker("w1", queueRepository, registry, new RetryBackoff(60000, 300000, 0), 10);

        worker.processNextJob();

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(attemptsOf(id)).isEqualTo(1);
        assertThat(rowOf(id).get("last_error")).isEqualTo("connection timeout");
        Boolean delayed = jdbcTemplate.queryForObject(
                "SELECT available_at > now() + interval '50 seconds' FROM jobs WHERE id = ?", Boolean.class, id);
        assertThat(delayed).isTrue();
    }

    @Test
    void failureOnLastAttemptGoesToDead() {
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("FLAKY", (payload, key, attempt) -> {
            throw new RuntimeException("still failing");
        }));
        UUID id = insertPendingJob("FLAKY", 1, Instant.now().minusSeconds(5));

        workerWith(registry).processNextJob();

        assertThat(statusOf(id)).isEqualTo("DEAD");
        assertThat(attemptsOf(id)).isEqualTo(1);
    }

    @Test
    void handlerGetsCorrectAttemptNumber() {
        AtomicInteger seenAttempt = new AtomicInteger();
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("ECHO",
                (payload, key, attempt) -> seenAttempt.set(attempt)));
        UUID id = insertPendingJob("ECHO");
        jdbcTemplate.update("UPDATE jobs SET attempts = 2 WHERE id = ?", id);

        workerWith(registry).processNextJob();

        assertThat(seenAttempt.get()).isEqualTo(3);
    }

    @Test
    void handlerGetsPayloadAndIdempotencyKey() {
        AtomicReference<String> seenPayload = new AtomicReference<>();
        AtomicReference<String> seenKey = new AtomicReference<>();
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("ECHO", (payload, key, attempt) -> {
            seenPayload.set(payload);
            seenKey.set(key);
        }));
        jdbcTemplate.update("""
                INSERT INTO jobs (id, job_type, payload, status, max_attempts, idempotency_key, available_at)
                VALUES (?, 'ECHO', '{"to": "bob@mail.com"}'::jsonb, 'PENDING', 5, 'key-1', now() - interval '5 seconds')
                """, UUID.randomUUID());

        workerWith(registry).processNextJob();

        assertThat(seenPayload.get()).contains("bob@mail.com");
        assertThat(seenKey.get()).isEqualTo("key-1");
    }

    @Test
    void errorWithoutMessageUsesClassName() {
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("NPE", (payload, key, attempt) -> {
            throw new IllegalStateException();
        }));
        UUID id = insertPendingJob("NPE");

        workerWith(registry).processNextJob();

        assertThat(rowOf(id).get("last_error")).isEqualTo("IllegalStateException");
    }

    @Test
    void lostClaimDoesNotOverwriteJob() {
        // simulate reclaimer mid-run
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("SLOW", (payload, key, attempt) ->
                jdbcTemplate.update("""
                        UPDATE jobs SET status = 'PENDING', attempts = 1, claim_token = NULL,
                                        locked_by = NULL, locked_at = NULL
                        WHERE job_type = 'SLOW'
                        """)));
        UUID id = insertPendingJob("SLOW");

        workerWith(registry).processNextJob();

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(rowOf(id).get("completed_at")).isNull();
    }

    @Test
    void generateReportSucceedsOnThirdAttempt() throws Exception {
        UUID id = insertPendingJob("GENERATE_REPORT");
        Worker worker = workerWith(realHandlers);

        waitUntil(() -> {
            worker.processNextJob();
            return statusOf(id).equals("COMPLETED");
        }, 5000);

        assertThat(attemptsOf(id)).isEqualTo(2);
    }

    @Test
    void poisonJobEndsUpDead() throws Exception {
        UUID id = insertPendingJob("POISON_JOB", 3, Instant.now().minusSeconds(5));
        Worker worker = workerWith(realHandlers);

        waitUntil(() -> {
            worker.processNextJob();
            return statusOf(id).equals("DEAD");
        }, 5000);

        assertThat(attemptsOf(id)).isEqualTo(3);
        assertThat(rowOf(id).get("last_error").toString()).contains("always fails");
    }
}
