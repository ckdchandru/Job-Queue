package com.jq.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryBackoffTest {

    @Test
    void firstAttemptUsesBaseDelay() {
        RetryBackoff backoff = new RetryBackoff(1000, 300000, 0);
        assertThat(backoff.getDelayMillis(1)).isEqualTo(1000);
    }

    @Test
    void delayDoublesEveryAttempt() {
        RetryBackoff backoff = new RetryBackoff(1000, 300000, 0);
        assertThat(backoff.getDelayMillis(2)).isEqualTo(2000);
        assertThat(backoff.getDelayMillis(3)).isEqualTo(4000);
        assertThat(backoff.getDelayMillis(4)).isEqualTo(8000);
        assertThat(backoff.getDelayMillis(5)).isEqualTo(16000);
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        RetryBackoff backoff = new RetryBackoff(1000, 10000, 0);
        assertThat(backoff.getDelayMillis(10)).isEqualTo(10000);
    }

    @Test
    void hugeAttemptNumberDoesNotOverflow() {
        RetryBackoff backoff = new RetryBackoff(1000, 300000, 0.2);
        assertThat(backoff.getDelayMillis(1000)).isEqualTo(300000);
        assertThat(backoff.getDelayMillis(Integer.MAX_VALUE)).isEqualTo(300000);
    }

    @Test
    void jitterStaysInsideAllowedRange() {
        RetryBackoff backoff = new RetryBackoff(1000, 300000, 0.2);
        for (int i = 0; i < 1000; i++) {
            assertThat(backoff.getDelayMillis(1)).isBetween(1000L, 1200L);
            assertThat(backoff.getDelayMillis(3)).isBetween(4000L, 4800L);
        }
    }

    @Test
    void jitterGivesDifferentValues() {
        RetryBackoff backoff = new RetryBackoff(1000, 300000, 0.2);
        Set<Long> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            values.add(backoff.getDelayMillis(2));
        }
        assertThat(values.size()).isGreaterThan(10);
    }

    @Test
    void averageJitterIsAboutHalfOfMax() {
        RetryBackoff backoff = new RetryBackoff(1000, 300000, 0.2);
        long sum = 0;
        int samples = 10000;
        for (int i = 0; i < samples; i++) {
            sum += backoff.getDelayMillis(1);
        }
        double average = (double) sum / samples;
        assertThat(average).isBetween(1090.0, 1110.0);
    }

    @Test
    void jitterNeverPushesPastMaxDelay() {
        RetryBackoff backoff = new RetryBackoff(1000, 9000, 0.5);
        for (int i = 0; i < 500; i++) {
            assertThat(backoff.getDelayMillis(4)).isLessThanOrEqualTo(9000);
        }
    }

    @Test
    void attemptZeroIsRejected() {
        RetryBackoff backoff = new RetryBackoff(1000, 300000, 0.2);
        assertThatThrownBy(() -> backoff.getDelayMillis(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeAttemptIsRejected() {
        RetryBackoff backoff = new RetryBackoff(1000, 300000, 0.2);
        assertThatThrownBy(() -> backoff.getDelayMillis(-3)).isInstanceOf(IllegalArgumentException.class);
    }
}
