package com.jq.controller;

import com.jq.dto.JobResponse;
import com.jq.dto.SubmitJobRequest;
import com.jq.dto.SubmitJobResponse;
import com.jq.model.Job;
import com.jq.service.JobService;
import com.jq.service.SubmitResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<SubmitJobResponse> submitJob(@RequestBody SubmitJobRequest request) {
        SubmitResult result = jobService.submit(request);
        Job job = result.job();
        SubmitJobResponse body = new SubmitJobResponse(job.getId(), job.getStatus().name(), job.getCreatedAt());

        // 200 for replay
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/{jobId}")
    public JobResponse getJob(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.getJob(jobId));
    }
}
