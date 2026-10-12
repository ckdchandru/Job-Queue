package com.jq.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record SubmitJobRequest(
        String type,
        JsonNode payload,
        String idempotencyKey,
        Integer maxAttempts,
        Long delaySeconds
) {
}
