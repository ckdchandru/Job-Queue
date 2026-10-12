package com.jq.worker;

import com.jq.handler.HandlerRegistry;
import com.jq.repository.QueueRepository;
import com.jq.service.RetryBackoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerManager.class);

    private final QueueRepository queueRepository;
    private final HandlerRegistry handlerRegistry;
    private final RetryBackoff retryBackoff;
    private final int threadCount;
    private final long pollIntervalMs;
    private final long gracePeriodMs;

    private final List<Worker> workers = new ArrayList<>();
    private final AtomicInteger threadNumber = new AtomicInteger();
    private ExecutorService executor;
    private volatile boolean running = false;

    public WorkerManager(QueueRepository queueRepository, HandlerRegistry handlerRegistry, RetryBackoff retryBackoff,
                         @Value("${jobqueue.worker.thread-count:1}") int threadCount,
                         @Value("${jobqueue.worker.poll-interval-ms:200}") long pollIntervalMs,
                         @Value("${jobqueue.worker.shutdown-grace-period-ms:30000}") long gracePeriodMs) {
        this.queueRepository = queueRepository;
        this.handlerRegistry = handlerRegistry;
        this.retryBackoff = retryBackoff;
        this.threadCount = threadCount;
        this.pollIntervalMs = pollIntervalMs;
        this.gracePeriodMs = gracePeriodMs;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newFixedThreadPool(threadCount, task -> {
            Thread thread = new Thread(task, "jq-worker-" + threadNumber.incrementAndGet());
            // daemon so JVM can exit
            thread.setDaemon(true);
            return thread;
        });

        workers.clear();
        for (int i = 1; i <= threadCount; i++) {
            Worker worker = new Worker("worker-" + i, queueRepository, handlerRegistry, retryBackoff, pollIntervalMs);
            workers.add(worker);
            executor.submit(worker);
        }
        running = true;
        log.info("Started {} worker thread(s)", threadCount);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;

        // no new claims
        for (Worker worker : workers) {
            worker.stop();
        }
        executor.shutdown();

        try {
            boolean finished = executor.awaitTermination(gracePeriodMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                log.warn("Workers still busy after {} ms, reclaimer will recover their jobs", gracePeriodMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("All workers stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // stop before database
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public int getWorkerCount() {
        return workers.size();
    }
}
