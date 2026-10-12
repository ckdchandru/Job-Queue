package com.jq.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jq.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JobApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void submitReturns201AndPendingStatus() throws Exception {
        submit("{\"type\":\"SEND_EMAIL\",\"payload\":{\"to\":\"a@b.com\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void submitSavesJobWithDefaults() throws Exception {
        UUID id = submitAndGetId("{\"type\":\"SEND_EMAIL\",\"payload\":{\"to\":\"a@b.com\"}}");

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(attemptsOf(id)).isZero();
        Integer maxAttempts = jdbcTemplate.queryForObject("SELECT max_attempts FROM jobs WHERE id = ?", Integer.class, id);
        assertThat(maxAttempts).isEqualTo(5);
        String to = jdbcTemplate.queryForObject("SELECT payload->>'to' FROM jobs WHERE id = ?", String.class, id);
        assertThat(to).isEqualTo("a@b.com");
    }

    @Test
    void submitUsesCustomMaxAttempts() throws Exception {
        UUID id = submitAndGetId("{\"type\":\"SEND_EMAIL\",\"payload\":{},\"maxAttempts\":2}");
        Integer maxAttempts = jdbcTemplate.queryForObject("SELECT max_attempts FROM jobs WHERE id = ?", Integer.class, id);
        assertThat(maxAttempts).isEqualTo(2);
    }

    @Test
    void delaySecondsPushesAvailableAtIntoFuture() throws Exception {
        UUID id = submitAndGetId("{\"type\":\"SEND_EMAIL\",\"payload\":{},\"delaySeconds\":3600}");
        Boolean inFuture = jdbcTemplate.queryForObject(
                "SELECT available_at > now() + interval '50 minutes' FROM jobs WHERE id = ?", Boolean.class, id);
        assertThat(inFuture).isTrue();
    }

    @Test
    void missingTypeReturns400() throws Exception {
        submit("{\"payload\":{}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("type is required"));
    }

    @Test
    void blankTypeReturns400() throws Exception {
        submit("{\"type\":\"   \",\"payload\":{}}").andExpect(status().isBadRequest());
    }

    @Test
    void missingPayloadReturns400() throws Exception {
        submit("{\"type\":\"SEND_EMAIL\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("payload is required"));
    }

    @Test
    void nullPayloadReturns400() throws Exception {
        submit("{\"type\":\"SEND_EMAIL\",\"payload\":null}").andExpect(status().isBadRequest());
    }

    @Test
    void zeroMaxAttemptsReturns400() throws Exception {
        submit("{\"type\":\"SEND_EMAIL\",\"payload\":{},\"maxAttempts\":0}").andExpect(status().isBadRequest());
    }

    @Test
    void negativeDelayReturns400() throws Exception {
        submit("{\"type\":\"SEND_EMAIL\",\"payload\":{},\"delaySeconds\":-5}").andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        submit("{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("malformed request body"));
        assertThat(countAllJobs()).isZero();
    }

    @Test
    void sameIdempotencyKeyReturnsSameJob() throws Exception {
        String body = "{\"type\":\"SEND_EMAIL\",\"payload\":{},\"idempotencyKey\":\"order-42\"}";

        String first = submit(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = submit(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(idFrom(first)).isEqualTo(idFrom(second));
        assertThat(countAllJobs()).isEqualTo(1);
    }

    @Test
    void differentIdempotencyKeysCreateDifferentJobs() throws Exception {
        UUID first = submitAndGetId("{\"type\":\"SEND_EMAIL\",\"payload\":{},\"idempotencyKey\":\"a\"}");
        UUID second = submitAndGetId("{\"type\":\"SEND_EMAIL\",\"payload\":{},\"idempotencyKey\":\"b\"}");
        assertThat(first).isNotEqualTo(second);
        assertThat(countAllJobs()).isEqualTo(2);
    }

    @Test
    void getJobReturnsCurrentStatus() throws Exception {
        UUID id = submitAndGetId("{\"type\":\"SEND_EMAIL\",\"payload\":{},\"maxAttempts\":3}");

        mockMvc.perform(get("/api/v1/jobs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(id.toString()))
                .andExpect(jsonPath("$.type").value("SEND_EMAIL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(3));
    }

    @Test
    void getUnknownJobReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void getWithInvalidIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/not-a-uuid")).andExpect(status().isBadRequest());
    }

    private ResultActions submit(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID submitAndGetId(String json) throws Exception {
        String response = submit(json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(response);
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(objectMapper.readTree(json).get("jobId").asText());
    }
}
