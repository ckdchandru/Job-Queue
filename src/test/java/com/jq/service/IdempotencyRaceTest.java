package com.jq.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jq.BaseIntegrationTest;
import com.jq.dto.SubmitJobRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyRaceTest extends BaseIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void concurrentSubmitsWithSameKeyCreateOnlyOneJob() throws Exception {
        SubmitJobRequest request = new SubmitJobRequest(
                "SEND_EMAIL", objectMapper.readTree("{\"to\":\"x@y.com\"}"), "race-key", null, null);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<SubmitResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return jobService.submit(request);
            }));
        }
        // release everyone together
        startGate.countDown();

        Set<UUID> ids = new HashSet<>();
        int newJobs = 0;
        for (Future<SubmitResult> future : futures) {
            SubmitResult result = future.get(30, TimeUnit.SECONDS);
            ids.add(result.job().getId());
            if (!result.duplicate()) {
                newJobs++;
            }
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(newJobs).isEqualTo(1);
        assertThat(countAllJobs()).isEqualTo(1);
    }
}
