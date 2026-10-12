package com.jq.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jq.BaseIntegrationTest;
import com.jq.handler.HandlerRegistry;
import com.jq.repository.QueueRepository;
import com.jq.service.RetryBackoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// full flow through API
class RetryFlowTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private HandlerRegistry handlerRegistry;

    private WorkerManager manager;

    @BeforeEach
    void startWorkers() {
        manager = new WorkerManager(queueRepository, handlerRegistry, new RetryBackoff(10, 100, 0.2), 2, 20, 5000);
        manager.start();
    }

    @AfterEach
    void stopWorkers() {
        manager.stop();
    }

    @Test
    void reportJobIsRetriedUntilItSucceeds() throws Exception {
        UUID id = submit("{\"type\":\"GENERATE_REPORT\",\"payload\":{\"month\":\"2026-09\"}}");

        waitUntil(() -> statusOf(id).equals("COMPLETED"), 10000);

        mockMvc.perform(get("/api/v1/jobs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void poisonJobEndsUpInDeadLetterList() throws Exception {
        UUID id = submit("{\"type\":\"POISON_JOB\",\"payload\":{},\"maxAttempts\":3}");

        waitUntil(() -> statusOf(id).equals("DEAD"), 10000);

        mockMvc.perform(get("/api/v1/admin/jobs/dead").header("X-Admin-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].jobId").value(id.toString()))
                .andExpect(jsonPath("$.jobs[0].attempts").value(3))
                .andExpect(jsonPath("$.jobs[0].maxAttempts").value(3));
    }

    private UUID submit(String json) throws Exception {
        String response = mockMvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("jobId").asText());
    }
}
