package com.jq;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("TRUNCATE TABLE jobs");
    }

    protected UUID insertPendingJob(String type) {
        return insertPendingJob(type, 5, Instant.now().minusSeconds(5));
    }

    protected UUID insertPendingJob(String type, int maxAttempts, Instant availableAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO jobs (id, job_type, payload, status, attempts, max_attempts, available_at)
                VALUES (?, ?, '{}'::jsonb, 'PENDING', 0, ?, ?)
                """, id, type, maxAttempts, Timestamp.from(availableAt));
        return id;
    }

    protected UUID insertProcessingJob(String type, int attempts, int maxAttempts, Instant lockedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO jobs (id, job_type, payload, status, attempts, max_attempts,
                                  locked_by, locked_at, claim_token)
                VALUES (?, ?, '{}'::jsonb, 'PROCESSING', ?, ?, 'old-worker', ?, gen_random_uuid())
                """, id, type, attempts, maxAttempts, Timestamp.from(lockedAt));
        return id;
    }

    protected UUID insertDeadJob(String type, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO jobs (id, job_type, payload, status, attempts, max_attempts, last_error, created_at)
                VALUES (?, ?, '{}'::jsonb, 'DEAD', 3, 3, 'boom', ?)
                """, id, type, Timestamp.from(createdAt));
        return id;
    }

    protected UUID insertCompletedJob(String type) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO jobs (id, job_type, payload, status, attempts, max_attempts, completed_at)
                VALUES (?, ?, '{}'::jsonb, 'COMPLETED', 0, 5, now())
                """, id, type);
        return id;
    }

    protected String statusOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, id);
    }

    protected int attemptsOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT attempts FROM jobs WHERE id = ?", Integer.class, id);
    }

    protected Map<String, Object> rowOf(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
    }

    protected int countByStatus(String status) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM jobs WHERE status = ?", Integer.class, status);
    }

    protected int countAllJobs() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class);
    }

    // simple polling helper
    protected void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition was not met within " + timeoutMs + " ms");
            }
            Thread.sleep(20);
        }
    }
}
