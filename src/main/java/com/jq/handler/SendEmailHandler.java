package com.jq.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SendEmailHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(SendEmailHandler.class);

    @Override
    public String getType() {
        return "SEND_EMAIL";
    }

    @Override
    public void handle(String payload, String idempotencyKey, int attempt) {
        // fake send
        log.info("Sending email (stub), attempt={}, payload={}", attempt, payload);
    }
}
