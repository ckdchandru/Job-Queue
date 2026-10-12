package com.jq.handler;

public interface JobHandler {

    // job type name
    String getType();

    void handle(String payload, String idempotencyKey, int attempt) throws Exception;
}
