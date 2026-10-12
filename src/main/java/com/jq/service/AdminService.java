package com.jq.service;

import com.jq.exception.BadRequestException;
import com.jq.exception.JobNotDeadException;
import com.jq.exception.JobNotFoundException;
import com.jq.model.Job;
import com.jq.model.JobStatus;
import com.jq.repository.JobRepository;
import com.jq.repository.QueueRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AdminService {

    private final JobRepository jobRepository;
    private final QueueRepository queueRepository;

    public AdminService(JobRepository jobRepository, QueueRepository queueRepository) {
        this.jobRepository = jobRepository;
        this.queueRepository = queueRepository;
    }

    public Page<Job> getDeadJobs(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be >= 0");
        }
        if (size < 1 || size > 100) {
            throw new BadRequestException("size must be between 1 and 100");
        }
        // newest first
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return jobRepository.findByStatus(JobStatus.DEAD, pageRequest);
    }

    public void requeue(UUID jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        if (job.getStatus() != JobStatus.DEAD) {
            throw new JobNotDeadException(jobId, job.getStatus().name());
        }

        // status changed meanwhile
        if (!queueRepository.requeueDeadJob(jobId)) {
            throw new JobNotDeadException(jobId, null);
        }
    }
}
