package com.jq.model;

import java.util.UUID;

// job picked by worker
public record ClaimedJob(
        UUID id,
        String jobType,
        String payload,
        int attempts,
        int maxAttempts,
        UUID claimToken,
        String idempotencyKey
) {
}
