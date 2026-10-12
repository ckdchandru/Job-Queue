package com.jq.api;

import com.jq.BaseIntegrationTest;
import com.jq.handler.HandlerRegistry;
import com.jq.repository.QueueRepository;
import com.jq.service.RetryBackoff;
import com.jq.worker.Worker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminApiTest extends BaseIntegrationTest {

    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private HandlerRegistry handlerRegistry;

    @Test
    void listWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/jobs/dead"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void listWithWrongTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/jobs/dead").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requeueWithoutTokenReturns401() throws Exception {
        UUID id = insertDeadJob("SEND_EMAIL", Instant.now());
        mockMvc.perform(post("/api/v1/admin/jobs/" + id + "/requeue")).andExpect(status().isUnauthorized());
        assertThat(statusOf(id)).isEqualTo("DEAD");
    }

    @Test
    void listShowsOnlyDeadJobs() throws Exception {
        UUID dead = insertDeadJob("POISON_JOB", Instant.now());
        insertPendingJob("SEND_EMAIL");
        insertCompletedJob("SEND_EMAIL");

        mockMvc.perform(get("/api/v1/admin/jobs/dead").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs", hasSize(1)))
                .andExpect(jsonPath("$.jobs[0].jobId").value(dead.toString()))
                .andExpect(jsonPath("$.jobs[0].type").value("POISON_JOB"))
                .andExpect(jsonPath("$.jobs[0].lastError").value("boom"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listIsSortedNewestFirst() throws Exception {
        UUID older = insertDeadJob("SEND_EMAIL", Instant.now().minus(2, ChronoUnit.HOURS));
        UUID newer = insertDeadJob("SEND_EMAIL", Instant.now());

        mockMvc.perform(get("/api/v1/admin/jobs/dead").header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$.jobs[0].jobId").value(newer.toString()))
                .andExpect(jsonPath("$.jobs[1].jobId").value(older.toString()));
    }

    @Test
    void listSupportsPaging() throws Exception {
        for (int i = 0; i < 5; i++) {
            insertDeadJob("SEND_EMAIL", Instant.now().minusSeconds(i));
        }

        mockMvc.perform(get("/api/v1/admin/jobs/dead?page=2&size=2").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs", hasSize(1)))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void invalidPageSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/jobs/dead?size=0").header("X-Admin-Token", TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requeueMovesDeadJobBackToPending() throws Exception {
        UUID id = insertDeadJob("SEND_EMAIL", Instant.now());

        mockMvc.perform(post("/api/v1/admin/jobs/" + id + "/requeue").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(attemptsOf(id)).isZero();
    }

    @Test
    void requeueOfNonDeadJobReturns409() throws Exception {
        UUID id = insertPendingJob("SEND_EMAIL");

        mockMvc.perform(post("/api/v1/admin/jobs/" + id + "/requeue").header("X-Admin-Token", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_DEAD"));
    }

    @Test
    void requeueOfUnknownJobReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/admin/jobs/" + UUID.randomUUID() + "/requeue").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void requeuedJobGetsProcessedAgain() throws Exception {
        UUID id = insertDeadJob("SEND_EMAIL", Instant.now());
        mockMvc.perform(post("/api/v1/admin/jobs/" + id + "/requeue").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk());

        Worker worker = new Worker("w1", queueRepository, handlerRegistry, new RetryBackoff(10, 100, 0), 10);
        assertThat(worker.processNextJob()).isTrue();
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
    }
}
