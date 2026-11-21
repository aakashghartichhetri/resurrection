package dev.resurrection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
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
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatedTheHistoryAndOperationalIndexes() {
        Integer applied = jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE success = true AND version IN ('1', '2', '3', '4')
                """, Integer.class);
        assertEquals(4, applied);
        Integer indexes = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE tablename = 'workflow_events' AND indexname = 'idx_workflow_events_recent'
                """, Integer.class);
        assertEquals(1, indexes);
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/workflows")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void requestIdsAreReturnedAndInvalidIdentifiersAreReplaced() throws Exception {
        mvc.perform(get("/api/workflows").with(httpBasic("resurrection", "dev-only-change-me"))
                        .header("X-Request-Id", "operator-investigation-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "operator-investigation-123"));
        mvc.perform(get("/api/workflows").header("X-Request-Id", "unsafe request"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void validationErrorsIncludeAReferenceForSupport() throws Exception {
        mvc.perform(post("/api/workflows")
                        .with(httpBasic("resurrection", "dev-only-change-me"))
                        .header("X-Request-Id", "validation-test-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "validation-test-123"))
                .andExpect(jsonPath("$.requestId").value("validation-test-123"));
    }

    @Test
    void metricsAreProtectedByOperatorCredentials() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(status().isOk());
    }

    @Test
    void aViewerCannotReadOrChangeWorkflows() throws Exception {
        mvc.perform(get("/api/workflows")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Unauthorized write\"}")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
    }

    @Test
    void rejectsMalformedRecoveryIdentifiersAndInvalidFaultTypes() throws Exception {
        String id = create("Invalid operator input");
        authorizedPost("/api/workflows/" + id + "/faults", "{\"type\":\"SOMETHING_ELSE\"}")
                .andExpect(status().isBadRequest());
        authorizedPost("/api/workflows/" + id + "/recover", """
                {"action":"REBUILD_PROJECTION","expectedSequence":1,
                 "expectedHash":"wrong","projectionFingerprint":"wrong"}
                """).andExpect(status().isBadRequest());
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
        recover(id, "RESUME_AFTER_FAILURE", body)
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        authorizedPost("/api/workflows/" + id + "/advance", null)
                .andExpect(jsonPath("$.step").value("PACKED"));
        mvc.perform(get("/api/workflows/" + id).with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(jsonPath("$.events.length()").value(6))
                .andExpect(jsonPath("$.checkpointCount").value(3))
                .andExpect(jsonPath("$.approvals[0].actor").value("resurrection"))
                .andExpect(jsonPath("$.approvals[0].action").value("RESUME_AFTER_FAILURE"))
                .andExpect(jsonPath("$.faultAudits[0].actor").value("resurrection"));
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
        recover(id, "REBUILD_PROJECTION", proposal)
                .andExpect(jsonPath("$.step").value("VALIDATED"));
        recover(id, "REBUILD_PROJECTION", proposal)
                .andExpect(status().isConflict());
        mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(jsonPath("$.projectionMatches").value(true))
                .andExpect(jsonPath("$.action").value("NONE"));
    }

    @Test
    void rebuildingAnArmedWorkflowPreservesItsPendingFault() throws Exception {
        String id = create("Pending fault replay demo");
        authorizedPost("/api/workflows/" + id + "/faults", "{\"type\":\"FAIL_NEXT_STEP\"}")
                .andExpect(jsonPath("$.failNextStep").value(true));
        authorizedPost("/api/workflows/" + id + "/faults", "{\"type\":\"CORRUPT_PROJECTION\"}")
                .andExpect(status().isOk());
        String preview = mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(jsonPath("$.expectedFaultArmed").value(true))
                .andExpect(jsonPath("$.verifiedCheckpoints").value(1))
                .andReturn().getResponse().getContentAsString();
        recover(id, "REBUILD_PROJECTION", preview)
                .andExpect(jsonPath("$.failNextStep").value(true));
        authorizedPost("/api/workflows/" + id + "/advance", null)
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void requestIdempotencyProtectsAgainstDuplicateNetworkDelivery() throws Exception {
        String id = create("Retry protection demo");
        String key = UUID.randomUUID().toString();
        String first = authorizedPost("/api/workflows/" + id + "/advance", null, key)
                .andExpect(jsonPath("$.lastSequence").value(2))
                .andReturn().getResponse().getContentAsString();
        String second = authorizedPost("/api/workflows/" + id + "/advance", null, key)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        mvc.perform(get("/api/workflows/" + id).with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(jsonPath("$.events.length()").value(2));
        String anotherId = create("Another workflow");
        authorizedPost("/api/workflows/" + anotherId + "/advance", null, key)
                .andExpect(status().isConflict());
    }

    @Test
    void staleProjectionFingerprintBlocksRecoveryApproval() throws Exception {
        String id = create("Stale approval demo");
        authorizedPost("/api/workflows/" + id + "/faults", "{\"type\":\"FAIL_NEXT_STEP\"}")
                .andExpect(status().isOk());
        authorizedPost("/api/workflows/" + id + "/advance", null).andExpect(status().isOk());
        String preview = mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andReturn().getResponse().getContentAsString();
        // This direct database mutation changes projection without appending a new event.
        jdbc.update("UPDATE workflows SET step = 2 WHERE id = ?", UUID.fromString(id));
        recover(id, "RESUME_AFTER_FAILURE", preview).andExpect(status().isConflict());
        mvc.perform(get("/api/workflows/" + id).with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(jsonPath("$.approvals.length()").value(0));
    }

    @Test
    void refusesTamperedHistoryInsteadOfGuessingARepair() throws Exception {
        String id = create("Tamper protection demo");
        jdbc.update("UPDATE workflow_events SET message = 'forged' WHERE workflow_id = ? AND sequence_number = 1",
                UUID.fromString(id));
        mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("integrity")));
    }

    @Test
    void missingCheckpointBlocksRecoveryAndFlagsTheWorkflow() throws Exception {
        String id = create("Missing checkpoint investigation");
        authorizedPost("/api/workflows/" + id + "/advance", null).andExpect(status().isOk());
        jdbc.update("DELETE FROM workflow_checkpoints WHERE workflow_id = ? AND sequence_number = 2",
                UUID.fromString(id));
        mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("checkpoint")));
        String raw = mvc.perform(get("/api/operations")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode entries = mapper.readTree(raw).get("workflows");
        for (JsonNode entry : entries) {
            if (id.equals(entry.get("id").asText())) {
                assertEquals("HISTORY_INVALID", entry.get("state").asText());
                return;
            }
        }
        fail("Missing-checkpoint workflow was absent from operations");
    }

    @Test
    void operationsOverviewFindsRecoverableFailuresAndProjectionDrift() throws Exception {
        String failedId = create("Fleet failed shipment");
        String driftedId = create("Fleet inconsistent shipment");
        authorizedPost("/api/workflows/" + failedId + "/faults", "{\"type\":\"FAIL_NEXT_STEP\"}")
                .andExpect(status().isOk());
        authorizedPost("/api/workflows/" + failedId + "/advance", null)
                .andExpect(jsonPath("$.status").value("FAILED"));
        authorizedPost("/api/workflows/" + driftedId + "/faults", "{\"type\":\"CORRUPT_PROJECTION\"}")
                .andExpect(status().isOk());

        String raw = mvc.perform(get("/api/operations")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode overview = mapper.readTree(raw);
        assertTrue(overview.get("attention").asLong() >= 2);
        Map<String, String> states = new java.util.HashMap<>();
        overview.get("workflows").forEach(item -> states.put(item.get("id").asText(),
                item.get("state").asText()));
        assertEquals("FAILED", states.get(failedId));
        assertEquals("DRIFTED", states.get(driftedId));
    }

    @Test
    void operationsShowsHistoryIntegrityErrorsWithoutProposingRecovery() throws Exception {
        String id = create("Fleet altered event history");
        jdbc.update("UPDATE workflow_events SET message = 'edited' WHERE workflow_id = ? AND sequence_number = 1",
                UUID.fromString(id));
        String raw = mvc.perform(get("/api/operations")
                        .with(httpBasic("resurrection", "dev-only-change-me")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode entries = mapper.readTree(raw).get("workflows");
        boolean found = false;
        for (JsonNode entry : entries) {
            if (id.equals(entry.get("id").asText())) {
                assertEquals("HISTORY_INVALID", entry.get("state").asText());
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void aFailedApprovalAuditInsertRollsBackTheRecoveryEventAndProjection() throws Exception {
        String id = create("Rollback when audit write fails");
        authorizedPost("/api/workflows/" + id + "/faults", "{\"type\":\"FAIL_NEXT_STEP\"}");
        authorizedPost("/api/workflows/" + id + "/advance", null);
        String preview = mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andReturn().getResponse().getContentAsString();
        long before = jdbc.queryForObject("SELECT last_sequence FROM workflows WHERE id = ?",
                Long.class, UUID.fromString(id));
        jdbc.execute("""
                CREATE FUNCTION reject_recovery_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated audit store outage'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_recovery_test BEFORE INSERT ON recovery_approvals
                FOR EACH ROW EXECUTE FUNCTION reject_recovery_test()
                """);
        try {
            assertThrows(Exception.class, () -> recover(id, "RESUME_AFTER_FAILURE", preview));
            assertEquals(before, jdbc.queryForObject("SELECT last_sequence FROM workflows WHERE id = ?",
                    Long.class, UUID.fromString(id)));
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM workflows WHERE id = ?",
                    String.class, UUID.fromString(id)));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recovery_approvals WHERE workflow_id = ?",
                    Integer.class, UUID.fromString(id)));
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_events WHERE workflow_id = ?",
                    Long.class, UUID.fromString(id)));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_recovery_test ON recovery_approvals");
            jdbc.execute("DROP FUNCTION IF EXISTS reject_recovery_test()");
        }
    }

    @Test
    void serializesTwoConcurrentAdvancesWithoutLosingSequenceNumbers() throws Exception {
        String id = create("Concurrent command demo");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> authorizedPost("/api/workflows/" + id + "/advance", null,
                    UUID.randomUUID().toString()).andReturn().getResponse().getStatus());
            var second = executor.submit(() -> authorizedPost("/api/workflows/" + id + "/advance", null,
                    UUID.randomUUID().toString()).andReturn().getResponse().getStatus());
            assertEquals(200, first.get(20, TimeUnit.SECONDS).intValue());
            assertEquals(200, second.get(20, TimeUnit.SECONDS).intValue());
            mvc.perform(get("/api/workflows/" + id).with(httpBasic("resurrection", "dev-only-change-me")))
                    .andExpect(jsonPath("$.workflow.lastSequence").value(3))
                    .andExpect(jsonPath("$.events.length()").value(3))
                    .andExpect(jsonPath("$.workflow.step").value("PACKED"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameKeyOnDifferentWorkflowsCannotRaceIntoA500() throws Exception {
        String one = create("Shared key first workflow");
        String two = create("Shared key second workflow");
        String key = UUID.randomUUID().toString();
        var executor = Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> first = () -> {
                ready.countDown();
                release.await();
                return authorizedPost("/api/workflows/" + one + "/advance", null, key)
                        .andReturn().getResponse().getStatus();
            };
            java.util.concurrent.Callable<Integer> second = () -> {
                ready.countDown();
                release.await();
                return authorizedPost("/api/workflows/" + two + "/advance", null, key)
                        .andReturn().getResponse().getStatus();
            };
            var a = executor.submit(first);
            var b = executor.submit(second);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            var responses = java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertTrue(responses.contains(200), "One request must win the key");
            assertTrue(responses.contains(409), "The other must report key reuse, not HTTP 500");
            Integer firstEvents = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_events WHERE workflow_id = ?",
                    Integer.class, UUID.fromString(one));
            Integer secondEvents = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_events WHERE workflow_id = ?",
                    Integer.class, UUID.fromString(two));
            assertEquals(3, firstEvents + secondEvents); // Two CREATED events plus one ADVANCED.
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void twoSimultaneousRecoveryApprovalsCannotBothCommit() throws Exception {
        String id = create("Concurrent recovery approval");
        authorizedPost("/api/workflows/" + id + "/faults", "{\"type\":\"FAIL_NEXT_STEP\"}");
        authorizedPost("/api/workflows/" + id + "/advance", null);
        String preview = mvc.perform(get("/api/workflows/" + id + "/recovery-preview")
                .with(httpBasic("resurrection", "dev-only-change-me")))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = mapper.readTree(preview);
        String payload = mapper.writeValueAsString(Map.of(
                "action", "RESUME_AFTER_FAILURE",
                "expectedSequence", data.get("expectedSequence").asLong(),
                "expectedHash", data.get("expectedHash").asText(),
                "projectionFingerprint", data.get("projectionFingerprint").asText()));
        var executor = Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> approve = () -> {
                ready.countDown();
                release.await();
                return authorizedPost("/api/workflows/" + id + "/recover", payload)
                        .andReturn().getResponse().getStatus();
            };
            var a = executor.submit(approve);
            var b = executor.submit(approve);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            var responses = java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertTrue(responses.contains(200));
            assertTrue(responses.contains(409));
            Integer approvals = jdbc.queryForObject("SELECT COUNT(*) FROM recovery_approvals WHERE workflow_id = ?",
                    Integer.class, UUID.fromString(id));
            assertEquals(1, approvals);
        } finally {
            executor.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions recover(String id, String action, String preview)
            throws Exception {
        JsonNode data = mapper.readTree(preview);
        String payload = mapper.writeValueAsString(Map.of(
                "action", action,
                "expectedSequence", data.get("expectedSequence").asLong(),
                "expectedHash", data.get("expectedHash").asText(),
                "projectionFingerprint", data.get("projectionFingerprint").asText()));
        return authorizedPost("/api/workflows/" + id + "/recover", payload);
    }

    private String create(String name) throws Exception {
        String response = authorizedPost("/api/workflows", "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(response);
        return json.get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions authorizedPost(String path, String body) throws Exception {
        return authorizedPost(path, body, null);
    }

    private org.springframework.test.web.servlet.ResultActions authorizedPost(String path, String body, String key)
            throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(httpBasic("resurrection", "dev-only-change-me"));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }
}
