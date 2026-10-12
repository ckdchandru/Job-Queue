package com.jq.dto;

import com.jq.model.Job;
import org.springframework.data.domain.Page;

import java.util.List;

public record DeadJobsPageResponse(
        List<DeadJobResponse> jobs,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static DeadJobsPageResponse from(Page<Job> page) {
        List<DeadJobResponse> jobs = page.getContent().stream().map(DeadJobResponse::from).toList();
        return new DeadJobsPageResponse(jobs, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
