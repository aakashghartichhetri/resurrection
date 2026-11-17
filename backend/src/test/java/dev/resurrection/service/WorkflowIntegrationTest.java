package dev.resurrection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.seed-demo=false")
@AutoConfigureMockMvc
@Testcontainers
class WorkflowIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("resurrection")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/workflows")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void inputIsValidated() throws Exception {
        authorizedPost("/api/workflows", "{\"name\":\"   \"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void failureReplayAndApprovedResumeAreDurable() throws Exception {
        String id = create("Integration failure experiment");
        authorizedPost("/api/workflows/" + id + "/advance", null).andExpect(status().isOk());
        authorizedPost("/api/workflows/" + id + "/faults", "{\"type\":\"FAIL_NEXT_STEP\"}")
                .andExpect(status().isOk());
        authorizedPost("/api/workflows/" + id + "/advance", null)
                .andExpect(jsonPath("$.status").value("FAILED"));
        String body = mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RESUME_AFTER_FAILURE"))
                .andExpect(jsonPath("$.simulatedStatus").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        long seq = mapper.readTree(body).get("expectedSequence").asLong();
        authorizedPost("/api/workflows/" + id + "/recover",
                "{\"action\":\"RESUME_AFTER_FAILURE\",\"expectedSequence\":" + seq + "}")
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        authorizedPost("/api/workflows/" + id + "/advance", null)
                .andExpect(jsonPath("$.step").value("PACKED"));
        mvc.perform(get("/api/workflows/" + id).with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(jsonPath("$.events.length()").value(6))
                .andExpect(jsonPath("$.checkpointCount").value(3));
    }

    @Test
    void detectsProjectionCorruptionBlocksWritesAndRestoresWithApproval() throws Exception {
        String id = create("Integration projection experiment");
        authorizedPost("/api/workflows/" + id + "/advance", null).andExpect(status().isOk());
        authorizedPost("/api/workflows/" + id + "/faults", "{\"type\":\"CORRUPT_PROJECTION\"}")
                .andExpect(status().isOk());
        authorizedPost("/api/workflows/" + id + "/advance", null).andExpect(status().isConflict());
        var proposal = mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(jsonPath("$.projectionMatches").value(false))
                .andExpect(jsonPath("$.action").value("REBUILD_PROJECTION"))
                .andReturn().getResponse().getContentAsString();
        long seq = mapper.readTree(proposal).get("expectedSequence").asLong();
        authorizedPost("/api/workflows/" + id + "/recover",
                "{\"action\":\"REBUILD_PROJECTION\",\"expectedSequence\":" + seq + "}")
                .andExpect(jsonPath("$.step").value("VALIDATED"));
        authorizedPost("/api/workflows/" + id + "/recover",
                "{\"action\":\"REBUILD_PROJECTION\",\"expectedSequence\":" + seq + "}")
                .andExpect(status().isConflict());
        mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(jsonPath("$.projectionMatches").value(true))
                .andExpect(jsonPath("$.action").value("NONE"));
    }

    private String create(String name) throws Exception {
        String response = authorizedPost("/api/workflows", "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(response);
        return json.get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions authorizedPost(String path, String body) throws Exception {
        var request = post(path).with(httpBasic("resurrection", "dev-only-change-me"));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
}
