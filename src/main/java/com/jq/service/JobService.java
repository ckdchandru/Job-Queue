package com.jq.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jq.dto.SubmitJobRequest;
import com.jq.exception.BadRequestException;
import com.jq.exception.JobNotFoundException;
import com.jq.model.Job;
import com.jq.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final int defaultMaxAttempts;

    public JobService(JobRepository jobRepository, ObjectMapper objectMapper,
                      @Value("${jobqueue.default-max-attempts:5}") int defaultMaxAttempts) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public SubmitResult submit(SubmitJobRequest request) {
        validate(request);

        int maxAttempts = request.maxAttempts() != null ? request.maxAttempts() : defaultMaxAttempts;
        long delaySeconds = request.delaySeconds() != null ? request.delaySeconds() : 0;
        Instant availableAt = Instant.now().plusSeconds(delaySeconds);
        String payload = toJson(request);

        String key = request.idempotencyKey();
        if (key == null || key.isBlank()) {
            Job job = new Job(request.type(), payload, maxAttempts, availableAt, null);
            jobRepository.saveAndFlush(job);
            return new SubmitResult(job, false);
        }

        // same key, same job
        Optional<Job> existing = jobRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return new SubmitResult(existing.get(), true);
        }

        Job job = new Job(request.type(), payload, maxAttempts, availableAt, key);
        try {
            jobRepository.saveAndFlush(job);
            return new SubmitResult(job, false);
        } catch (DataIntegrityViolationException e) {
            // lost the race
            Job winner = jobRepository.findByIdempotencyKey(key).orElseThrow(() -> e);
            return new SubmitResult(winner, true);
        }
    }

    public Job getJob(UUID jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    }

    private void validate(SubmitJobRequest request) {
        if (request.type() == null || request.type().isBlank()) {
            throw new BadRequestException("type is required");
        }
        if (request.payload() == null || request.payload().isNull()) {
            throw new BadRequestException("payload is required");
        }
        if (request.maxAttempts() != null && request.maxAttempts() < 1) {
            throw new BadRequestException("maxAttempts must be >= 1");
        }
        if (request.delaySeconds() != null && request.delaySeconds() < 0) {
            throw new BadRequestException("delaySeconds must be >= 0");
        }
    }

    private String toJson(SubmitJobRequest request) {
        try {
            return objectMapper.writeValueAsString(request.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize payload", e);
        }
    }
}
