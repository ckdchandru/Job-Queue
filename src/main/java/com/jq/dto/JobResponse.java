package com.jq.dto;

import com.jq.model.Job;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID jobId,
        String type,
        String status,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt,
        Instant completedAt
) {

    public static JobResponse from(Job job) {
        return new JobResponse(job.getId(), job.getJobType(), job.getStatus().name(), job.getAttempts(),
                job.getMaxAttempts(), job.getLastError(), job.getCreatedAt(), job.getCompletedAt());
    }
}
