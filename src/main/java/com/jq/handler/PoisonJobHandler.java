package com.jq.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PoisonJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PoisonJobHandler.class);

    @Override
    public String getType() {
        return "POISON_JOB";
    }

    @Override
    public void handle(String payload, String idempotencyKey, int attempt) {
        // always fails
        log.info("Poison job failing, attempt={}", attempt);
        throw new RetryableException("poison job always fails (attempt " + attempt + ")");
    }
}
