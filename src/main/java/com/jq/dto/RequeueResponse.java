package com.jq.dto;

import java.util.UUID;

public record RequeueResponse(UUID jobId, String status) {
}
