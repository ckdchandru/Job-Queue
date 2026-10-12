package com.jq.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GenerateReportHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerateReportHandler.class);

    @Override
    public String getType() {
        return "GENERATE_REPORT";
    }

    @Override
    public void handle(String payload, String idempotencyKey, int attempt) {
        // succeeds on third try
        if (attempt < 3) {
            log.info("Report generation failed on purpose, attempt={}", attempt);
            throw new RetryableException("temporary report failure on attempt " + attempt);
        }
        log.info("Report generated (stub), attempt={}", attempt);
    }
}
