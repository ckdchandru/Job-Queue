package com.jq.benchmark;

import com.jq.BaseIntegrationTest;
import com.jq.handler.HandlerRegistry;
import com.jq.model.ClaimedJob;
import com.jq.repository.QueueRepository;
import com.jq.service.RetryBackoff;
import com.jq.worker.Worker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ThroughputBenchmarkTest extends BaseIntegrationTest {

    private static final int JOB_COUNT = 1000;

    @Autowired
    private HandlerRegistry handlerRegistry;

    @ParameterizedTest(name = "{0} worker(s)")
    @ValueSource(ints = {1, 5, 10, 20})
    void processesAllJobsExactlyOnce(int workerCount) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < JOB_COUNT; i++) {
            rows.add(new Object[]{UUID.randomUUID()});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO jobs (id, job_type, payload, status, max_attempts, available_at)
                VALUES (?, 'SEND_EMAIL', '{}'::jsonb, 'PENDING', 5, now() - interval '5 seconds')
                """, rows);

        CountingQueueRepository repository = new CountingQueueRepository(jdbcTemplate);
        RetryBackoff backoff = new RetryBackoff(10, 100, 0);

        List<Worker> workers = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        long startNanos = System.nanoTime();
        for (int i = 0; i < workerCount; i++) {
            Worker worker = new Worker("bench-" + i, repository, handlerRegistry, backoff, 5);
            workers.add(worker);
            pool.submit(worker);
        }

        waitUntil(() -> countByStatus("COMPLETED") == JOB_COUNT, 90000);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        workers.forEach(Worker::stop);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // each job claimed once
        assertThat(repository.claims).hasSize(JOB_COUNT);
        assertThat(repository.claims.values()).allMatch(count -> count.get() == 1);

        double jobsPerSecond = JOB_COUNT / (elapsedMs / 1000.0);
        System.out.printf("[benchmark] workers=%d jobs=%d time=%dms throughput=%.0f jobs/sec%n",
                workerCount, JOB_COUNT, elapsedMs, jobsPerSecond);
    }

    // counts claims per job
    private static class CountingQueueRepository extends QueueRepository {

        private final Map<UUID, AtomicInteger> claims = new ConcurrentHashMap<>();

        CountingQueueRepository(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        @Override
        public ClaimedJob claimNextJob(String workerId) {
            ClaimedJob job = super.claimNextJob(workerId);
            if (job != null) {
                claims.computeIfAbsent(job.id(), id -> new AtomicInteger()).incrementAndGet();
            }
            return job;
        }
    }
}
