package com.jq.model;

import java.util.UUID;

public record StuckJob(UUID id, int attempts, int maxAttempts) {
}
