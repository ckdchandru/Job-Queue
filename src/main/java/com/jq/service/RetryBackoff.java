package com.jq.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryBackoff {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterFraction;

    public RetryBackoff(@Value("${jobqueue.retry.base-delay-ms:1000}") long baseDelayMs,
                        @Value("${jobqueue.retry.max-delay-ms:300000}") long maxDelayMs,
                        @Value("${jobqueue.retry.jitter-fraction:0.2}") double jitterFraction) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitterFraction = jitterFraction;
    }

    public long getDelayMillis(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1 but was " + attempt);
        }

        // 1x, 2x, 4x, 8x...
        int power = Math.min(attempt - 1, 30);
        long delay = baseDelayMs * (1L << power);

        // random extra wait
        long maxJitter = (long) (delay * jitterFraction);
        long jitter = 0;
        if (maxJitter > 0) {
            jitter = ThreadLocalRandom.current().nextLong(maxJitter + 1);
        }

        long total = delay + jitter;
        if (total < 0 || total > maxDelayMs) {
            total = maxDelayMs;
        }
        return total;
    }
}
