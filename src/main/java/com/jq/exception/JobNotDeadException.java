package com.jq.exception;

import java.util.UUID;

public class JobNotDeadException extends RuntimeException {

    public JobNotDeadException(UUID jobId, String currentStatus) {
        super(buildMessage(jobId, currentStatus));
    }

    private static String buildMessage(UUID jobId, String currentStatus) {
        if (currentStatus == null) {
            return "Job " + jobId + " is not DEAD";
        }
        return "Job " + jobId + " is not DEAD (current status: " + currentStatus + ")";
    }
}
