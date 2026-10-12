package com.jq.repository;

import com.jq.model.ClaimedJob;
import com.jq.model.StuckJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// raw SQL queue queries
@Repository
public class QueueRepository {

    private final JdbcTemplate jdbcTemplate;

    public QueueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // returns null when empty
    public ClaimedJob claimNextJob(String workerId) {
        String sql = """
                WITH next_job AS (
                    SELECT id
                    FROM jobs
                    WHERE status = 'PENDING'
                      AND available_at <= now()
                    ORDER BY available_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE jobs
                SET status = 'PROCESSING',
                    locked_by = ?,
                    locked_at = now(),
                    claim_token = gen_random_uuid(),
                    updated_at = now()
                FROM next_job
                WHERE jobs.id = next_job.id
                RETURNING jobs.id, jobs.job_type, jobs.payload, jobs.attempts,
                          jobs.max_attempts, jobs.claim_token, jobs.idempotency_key
                """;

        List<ClaimedJob> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new ClaimedJob(
                rs.getObject("id", UUID.class),
                rs.getString("job_type"),
                rs.getString("payload"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getObject("claim_token", UUID.class),
                rs.getString("idempotency_key")
        ), workerId);

        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    // token must match
    public boolean markCompleted(UUID jobId, UUID claimToken) {
        String sql = """
                UPDATE jobs
                SET status = 'COMPLETED',
                    completed_at = now(),
                    locked_by = NULL,
                    locked_at = NULL,
                    claim_token = NULL,
                    updated_at = now()
                WHERE id = ? AND claim_token = ?
                """;
        return jdbcTemplate.update(sql, jobId, claimToken) == 1;
    }

    public boolean markForRetry(UUID jobId, UUID claimToken, int attempts, Instant nextRunAt, String error) {
        String sql = """
                UPDATE jobs
                SET status = 'PENDING',
                    attempts = ?,
                    available_at = ?,
                    last_error = ?,
                    locked_by = NULL,
                    locked_at = NULL,
                    claim_token = NULL,
                    updated_at = now()
                WHERE id = ? AND claim_token = ?
                """;
        return jdbcTemplate.update(sql, attempts, Timestamp.from(nextRunAt), error, jobId, claimToken) == 1;
    }

    public boolean markDead(UUID jobId, UUID claimToken, int attempts, String error) {
        String sql = """
                UPDATE jobs
                SET status = 'DEAD',
                    attempts = ?,
                    last_error = ?,
                    locked_by = NULL,
                    locked_at = NULL,
                    claim_token = NULL,
                    updated_at = now()
                WHERE id = ? AND claim_token = ?
                """;
        return jdbcTemplate.update(sql, attempts, error, jobId, claimToken) == 1;
    }

    // locks rows until commit
    public List<StuckJob> findStuckJobs(Instant lockedBefore, int limit) {
        String sql = """
                SELECT id, attempts, max_attempts
                FROM jobs
                WHERE status = 'PROCESSING'
                  AND locked_at < ?
                ORDER BY locked_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new StuckJob(
                rs.getObject("id", UUID.class),
                rs.getInt("attempts"),
                rs.getInt("max_attempts")
        ), Timestamp.from(lockedBefore), limit);
    }

    public void releaseStuckJob(UUID jobId, int attempts, Instant nextRunAt, String error) {
        String sql = """
                UPDATE jobs
                SET status = 'PENDING',
                    attempts = ?,
                    available_at = ?,
                    last_error = ?,
                    locked_by = NULL,
                    locked_at = NULL,
                    claim_token = NULL,
                    updated_at = now()
                WHERE id = ? AND status = 'PROCESSING'
                """;
        jdbcTemplate.update(sql, attempts, Timestamp.from(nextRunAt), error, jobId);
    }

    public void killStuckJob(UUID jobId, int attempts, String error) {
        String sql = """
                UPDATE jobs
                SET status = 'DEAD',
                    attempts = ?,
                    last_error = ?,
                    locked_by = NULL,
                    locked_at = NULL,
                    claim_token = NULL,
                    updated_at = now()
                WHERE id = ? AND status = 'PROCESSING'
                """;
        jdbcTemplate.update(sql, attempts, error, jobId);
    }

    public boolean requeueDeadJob(UUID jobId) {
        String sql = """
                UPDATE jobs
                SET status = 'PENDING',
                    attempts = 0,
                    available_at = now(),
                    locked_by = NULL,
                    locked_at = NULL,
                    claim_token = NULL,
                    updated_at = now()
                WHERE id = ? AND status = 'DEAD'
                """;
        return jdbcTemplate.update(sql, jobId) == 1;
    }
}
