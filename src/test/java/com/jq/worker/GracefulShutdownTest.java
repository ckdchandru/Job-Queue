package com.jq.worker;

import com.jq.BaseIntegrationTest;
import com.jq.FakeHandler;
import com.jq.handler.HandlerRegistry;
import com.jq.repository.QueueRepository;
import com.jq.service.RetryBackoff;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulShutdownTest extends BaseIntegrationTest {

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private HandlerRegistry handlerRegistry;

    private final RetryBackoff fastBackoff = new RetryBackoff(10, 100, 0);

    @Test
    void stopWaitsForRunningJobToFinish() throws Exception {
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("SLOW",
                (payload, key, attempt) -> Thread.sleep(1000)));
        UUID id = insertPendingJob("SLOW");

        WorkerManager manager = new WorkerManager(queueRepository, registry, fastBackoff, 1, 20, 10000);
        manager.start();
        waitUntil(() -> statusOf(id).equals("PROCESSING"), 5000);

        manager.stop();

        // finished, not abandoned
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
    }

    @Test
    void noNewJobsAreClaimedAfterStop() throws Exception {
        WorkerManager manager = new WorkerManager(queueRepository, handlerRegistry, fastBackoff, 2, 20, 5000);
        manager.start();
        manager.stop();

        UUID id = insertPendingJob("SEND_EMAIL");
        Thread.sleep(500);

        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void jobStaysProcessingIfGracePeriodRunsOut() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HandlerRegistry registry = FakeHandler.registryOf(new FakeHandler("STUCK",
                (payload, key, attempt) -> release.await()));
        UUID id = insertPendingJob("STUCK");

        WorkerManager manager = new WorkerManager(queueRepository, registry, fastBackoff, 1, 20, 300);
        try {
            manager.start();
            waitUntil(() -> statusOf(id).equals("PROCESSING"), 5000);

            long startedAt = System.currentTimeMillis();
            manager.stop();
            long tookMs = System.currentTimeMillis() - startedAt;

            // reclaimer handles it later
            assertThat(tookMs).isLessThan(3000);
            assertThat(statusOf(id)).isEqualTo("PROCESSING");
        } finally {
            release.countDown();
        }
    }
}
