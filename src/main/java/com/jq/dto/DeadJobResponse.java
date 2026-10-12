package com.jq.dto;

import com.jq.model.Job;

import java.time.Instant;
import java.util.UUID;

public record DeadJobResponse(
        UUID jobId,
        String type,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt
) {

    public static DeadJobResponse from(Job job) {
        return new DeadJobResponse(job.getId(), job.getJobType(), job.getAttempts(),
                job.getMaxAttempts(), job.getLastError(), job.getCreatedAt());
    }
}
