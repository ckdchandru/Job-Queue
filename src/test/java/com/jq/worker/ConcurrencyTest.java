package com.jq.worker;

import com.jq.BaseIntegrationTest;
import com.jq.FakeHandler;
import com.jq.handler.HandlerRegistry;
import com.jq.model.ClaimedJob;
import com.jq.repository.QueueRepository;
import com.jq.service.ReclaimService;
import com.jq.service.RetryBackoff;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private ReclaimService reclaimService;

    private final RetryBackoff fastBackoff = new RetryBackoff(10, 100, 0);

    @Test
    void parallelClaimsNeverReturnSameJob() throws Exception {
        int jobCount = 200;
        for (int i = 0; i < jobCount; i++) {
            insertPendingJob("SEND_EMAIL");
        }

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String workerId = "racer-" + i;
            futures.add(pool.submit(() -> {
                startGate.await();
                while (true) {
                    ClaimedJob job = queueRepository.claimNextJob(workerId);
                    if (job == null) {
                        break;
                    }
                    if (!claimed.add(job.id())) {
                        duplicates.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(duplicates.get()).isZero();
        assertThat(claimed).hasSize(jobCount);
        assertThat(countByStatus("PROCESSING")).isEqualTo(jobCount);
    }

    @Test
    void manyWorkersRunEachJobExactlyOnce() throws Exception {
        int jobCount = 300;
        for (int i = 0; i < jobCount; i++) {
            jdbcTemplate.update("""
                    INSERT INTO jobs (id, job_type, payload, status, max_attempts, idempotency_key, available_at)
                    VALUES (?, 'COUNT', '{}'::jsonb, 'PENDING', 5, ?, now() - interval '5 seconds')
                    """, UUID.randomUUID(), "job-" + i);
        }

        Map<String, AtomicInteger> runsPerJob = new ConcurrentHashMap<>();
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("COUNT", (payload, key, attempt) ->
                runsPerJob.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet()));

        List<Worker> workers = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            Worker worker = new Worker("worker-" + i, queueRepository, registry, fastBackoff, 5);
            workers.add(worker);
            pool.submit(worker);
        }

        waitUntil(() -> countByStatus("COMPLETED") == jobCount, 30000);
        workers.forEach(Worker::stop);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(runsPerJob).hasSize(jobCount);
        assertThat(runsPerJob.values()).allMatch(count -> count.get() == 1);
    }

    @Test
    void reclaimerAndSlowWorkerDoNotBothFinishJob() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("SLOW", (payload, key, attempt) -> {
            if (runs.incrementAndGet() == 1) {
                handlerStarted.countDown();
                releaseHandler.await();
            }
        }));
        UUID id = insertPendingJob("SLOW");

        Worker slowWorker = new Worker("slow", queueRepository, registry, fastBackoff, 10);
        Thread thread = new Thread(slowWorker::processNextJob);
        thread.start();
        assertThat(handlerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // pretend it hung
        jdbcTemplate.update("UPDATE jobs SET locked_at = now() - interval '10 minutes' WHERE id = ?", id);
        assertThat(reclaimService.reclaimStuckJobs()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("PENDING");

        releaseHandler.countDown();
        thread.join(5000);

        // late result ignored
        assertThat(statusOf(id)).isEqualTo("PENDING");

        jdbcTemplate.update("UPDATE jobs SET available_at = now() - interval '1 second' WHERE id = ?", id);
        Worker freshWorker = new Worker("fresh", queueRepository, registry, fastBackoff, 10);
        assertThat(freshWorker.processNextJob()).isTrue();

        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        assertThat(attemptsOf(id)).isEqualTo(1);
        assertThat(runs.get()).isEqualTo(2);
    }
}
