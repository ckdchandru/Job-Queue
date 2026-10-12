package com.jq.worker;

import com.jq.BaseIntegrationTest;
import com.jq.handler.HandlerRegistry;
import com.jq.repository.QueueRepository;
import com.jq.service.RetryBackoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerManagerTest extends BaseIntegrationTest {

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private HandlerRegistry handlerRegistry;

    private WorkerManager manager;

    @AfterEach
    void stopManager() {
        if (manager != null) {
            manager.stop();
        }
    }

    private WorkerManager newManager(int threads) {
        return new WorkerManager(queueRepository, handlerRegistry, new RetryBackoff(10, 100, 0), threads, 20, 5000);
    }

    @Test
    void startsConfiguredNumberOfWorkers() throws Exception {
        manager = newManager(3);
        manager.start();

        assertThat(manager.isRunning()).isTrue();
        assertThat(manager.getWorkerCount()).isEqualTo(3);
        waitUntil(() -> countWorkerThreads() >= 3, 2000);
    }

    @Test
    void processesJobsInBackground() throws Exception {
        for (int i = 0; i < 20; i++) {
            insertPendingJob("SEND_EMAIL");
        }

        manager = newManager(4);
        manager.start();

        waitUntil(() -> countByStatus("COMPLETED") == 20, 10000);
    }

    @Test
    void startingTwiceDoesNotAddWorkers() {
        manager = newManager(2);
        manager.start();
        manager.start();

        assertThat(manager.getWorkerCount()).isEqualTo(2);
    }

    @Test
    void isRunningIsFalseAfterStop() {
        manager = newManager(1);
        manager.start();
        manager.stop();

        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void stopBeforeStartIsSafe() {
        manager = newManager(2);
        manager.stop();

        assertThat(manager.isRunning()).isFalse();
    }

    private long countWorkerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("jq-worker-") && t.isAlive())
                .count();
    }
}
