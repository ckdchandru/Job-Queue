package com.jq.worker;

import com.jq.handler.HandlerRegistry;
import com.jq.handler.JobHandler;
import com.jq.handler.NonRetryableException;
import com.jq.model.ClaimedJob;
import com.jq.repository.QueueRepository;
import com.jq.service.RetryBackoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

public class Worker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final String workerId;
    private final QueueRepository queueRepository;
    private final HandlerRegistry handlerRegistry;
    private final RetryBackoff retryBackoff;
    private final long pollIntervalMs;

    private volatile boolean running = true;

    public Worker(String workerId, QueueRepository queueRepository, HandlerRegistry handlerRegistry,
                  RetryBackoff retryBackoff, long pollIntervalMs) {
        this.workerId = workerId;
        this.queueRepository = queueRepository;
        this.handlerRegistry = handlerRegistry;
        this.retryBackoff = retryBackoff;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public void run() {
        log.info("{} started", workerId);
        while (running) {
            try {
                boolean didWork = processNextJob();
                if (!didWork) {
                    // queue empty, wait a bit
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("{} hit an unexpected error", workerId, e);
                sleepQuietly();
            }
        }
        log.info("{} stopped", workerId);
    }

    // finish current job first
    public void stop() {
        running = false;
    }

    public String getWorkerId() {
        return workerId;
    }

    public boolean processNextJob() {
        ClaimedJob job = queueRepository.claimNextJob(workerId);
        if (job == null) {
            return false;
        }
        log.info("{} claimed job {} ({})", workerId, job.id(), job.jobType());

        JobHandler handler = handlerRegistry.find(job.jobType());
        if (handler == null) {
            sendToDead(job, job.attempts(), "no handler for job type " + job.jobType());
            return true;
        }

        int attempt = job.attempts() + 1;
        try {
            handler.handle(job.payload(), job.idempotencyKey(), attempt);
            if (queueRepository.markCompleted(job.id(), job.claimToken())) {
                log.info("{} completed job {} on attempt {}", workerId, job.id(), attempt);
            } else {
                logLostClaim(job);
            }
        } catch (NonRetryableException e) {
            sendToDead(job, job.attempts(), getMessage(e));
        } catch (Exception e) {
            handleFailure(job, attempt, e);
        }
        return true;
    }

    private void handleFailure(ClaimedJob job, int attempt, Exception error) {
        if (attempt >= job.maxAttempts()) {
            sendToDead(job, attempt, getMessage(error));
            return;
        }

        Instant nextRunAt = Instant.now().plusMillis(retryBackoff.getDelayMillis(attempt));
        boolean updated = queueRepository.markForRetry(job.id(), job.claimToken(), attempt, nextRunAt, getMessage(error));
        if (updated) {
            log.info("{} will retry job {} at {} (attempt {})", workerId, job.id(), nextRunAt, attempt);
        } else {
            logLostClaim(job);
        }
    }

    private void sendToDead(ClaimedJob job, int attempts, String reason) {
        if (queueRepository.markDead(job.id(), job.claimToken(), attempts, reason)) {
            log.warn("{} moved job {} to DEAD: {}", workerId, job.id(), reason);
        } else {
            logLostClaim(job);
        }
    }

    // reclaimer took it
    private void logLostClaim(ClaimedJob job) {
        log.warn("{} lost claim on job {}, result ignored", workerId, job.id());
    }

    private String getMessage(Exception e) {
        if (e.getMessage() != null) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
