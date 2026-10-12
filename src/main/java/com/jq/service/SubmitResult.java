package com.jq.service;

import com.jq.model.Job;

// duplicate = idempotent replay
public record SubmitResult(Job job, boolean duplicate) {
}
