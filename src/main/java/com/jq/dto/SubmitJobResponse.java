package com.jq.dto;

import java.time.Instant;
import java.util.UUID;

public record SubmitJobResponse(UUID jobId, String status, Instant createdAt) {
}
