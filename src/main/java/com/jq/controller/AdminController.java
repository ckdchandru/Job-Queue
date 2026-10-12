package com.jq.controller;

import com.jq.dto.DeadJobsPageResponse;
import com.jq.dto.RequeueResponse;
import com.jq.service.AdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/jobs")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/dead")
    public DeadJobsPageResponse listDeadJobs(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return DeadJobsPageResponse.from(adminService.getDeadJobs(page, size));
    }

    @PostMapping("/{jobId}/requeue")
    public RequeueResponse requeue(@PathVariable UUID jobId) {
        adminService.requeue(jobId);
        return new RequeueResponse(jobId, "PENDING");
    }
}
