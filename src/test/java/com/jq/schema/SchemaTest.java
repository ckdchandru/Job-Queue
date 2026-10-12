package com.jq.schema;

import com.jq.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaTest extends BaseIntegrationTest {

    @Test
    void flywayMigrationWasApplied() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void jobsTableHasExpectedColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'jobs'", String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "job_type", "payload", "status", "attempts", "max_attempts", "available_at",
                "locked_by", "locked_at", "claim_token", "idempotency_key", "last_error",
                "created_at", "completed_at", "updated_at");
    }

    @Test
    void indexesExist() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'jobs'", String.class);
        assertThat(indexes).contains("idx_jobs_status_available", "idx_jobs_status_locked", "idx_jobs_dead");
    }

    @Test
    void validRowsAreAccepted() {
        insertRow("PENDING", 0, 5, false, false);
        insertRow("PROCESSING", 1, 5, false, true);
        insertRow("COMPLETED", 2, 5, true, false);
        insertRow("DEAD", 5, 5, false, false);
        assertThat(countAllJobs()).isEqualTo(4);
    }

    @Test
    void unknownStatusIsRejected() {
        assertThatThrownBy(() -> insertRow("RUNNING", 0, 5, false, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void zeroMaxAttemptsIsRejected() {
        assertThatThrownBy(() -> insertRow("PENDING", 0, 0, false, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void negativeAttemptsAreRejected() {
        assertThatThrownBy(() -> insertRow("PENDING", -1, 5, false, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptsAboveMaxAreRejected() {
        assertThatThrownBy(() -> insertRow("DEAD", 6, 5, false, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completedJobNeedsCompletedAt() {
        assertThatThrownBy(() -> insertRow("COMPLETED", 1, 5, false, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void processingJobNeedsClaimToken() {
        assertThatThrownBy(() -> insertRow("PROCESSING", 0, 5, false, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pendingJobCannotHaveClaimToken() {
        assertThatThrownBy(() -> insertRow("PENDING", 0, 5, false, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {
        String sql = """
                INSERT INTO jobs (id, job_type, payload, status, max_attempts, idempotency_key)
                VALUES (?, 'SEND_EMAIL', '{}'::jsonb, 'PENDING', 5, 'same-key')
                """;
        jdbcTemplate.update(sql, UUID.randomUUID());
        assertThatThrownBy(() -> jdbcTemplate.update(sql, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRow(String status, int attempts, int maxAttempts, boolean completed, boolean withToken) {
        jdbcTemplate.update("""
                        INSERT INTO jobs (id, job_type, payload, status, attempts, max_attempts, completed_at, claim_token)
                        VALUES (?, 'SEND_EMAIL', '{}'::jsonb, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), status, attempts, maxAttempts,
                completed ? Timestamp.from(Instant.now()) : null,
                withToken ? UUID.randomUUID() : null);
    }
}
