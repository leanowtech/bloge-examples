package com.leanowtech.bloge.gateway.testing.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionControlBoundaryGuardFilterTest {

    @Test
    void productionRunWithNestedControlIsRejectedAndAuditedBeforeController() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit)).build();

        mvc.perform(post("/api/visual/drafts/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Environment-Id", "prod")
                        .content("""
                                {"context":{"orderId":"O-1","controlPlan":{"rules":[]}}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN"))
                .andExpect(jsonPath("$.details.field").value("controlPlan"));

        assertThat(endpoint.calls).isZero();
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo("PRODUCTION_RUN_CONTROL_GUARD");
            assertThat(record.outcome()).isEqualTo("DENIED");
            assertThat(record.reasonCode()).isEqualTo("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN");
            assertThat(record.tenantId()).isEqualTo("tenant-a");
            assertThat(record.environmentId()).isEqualTo("prod");
        });
    }

    @Test
    void ordinaryBusinessPayloadIsReplayedUnchangedToController() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit)).build();

        mvc.perform(post("/api/visual/drafts/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":{\"orderId\":\"O-1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.orderId").value("O-1"));

        assertThat(endpoint.calls).isEqualTo(1);
        assertThat(audit.records).isEmpty();
    }

    @Test
    void rejectionFailsClosedWhenSecurityAuditCannotCommit() throws Exception {
        IntegrationAccessAuditRepository unavailable = new IntegrationAccessAuditRepository() {
            @Override public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
                throw new IllegalStateException("unavailable");
            }
            @Override public List<IntegrationAccessAuditRecord> recent(int limit) { return List.of(); }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RunEndpoint())
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), unavailable)).build();

        mvc.perform(post("/api/gateway/resources/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"customer.get\",\"fixtureBundle\":{}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mirrorPlan", "mirrorRequest", "replayPayloads", "replacementRules",
            "resolverOverrides", "scenarioPackRef"
    })
    void productionRunRejectsEveryMirrorControlFamilyBeforeController(String field)
            throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit))
                .build();

        mvc.perform(post("/api/visual/drafts/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":{\"nested\":{\"" + field + "\":{}}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN"))
                .andExpect(jsonPath("$.details.field").value(field));

        assertThat(endpoint.calls).isZero();
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo("PRODUCTION_RUN_CONTROL_GUARD");
            assertThat(record.outcome()).isEqualTo("DENIED");
            assertThat(record.reasonCode())
                    .isEqualTo("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN");
        });
    }

    @Test
    void oversizedGuardedPayloadIsRejectedInsteadOfForwardingATruncatedBody() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RunEndpoint())
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), new RecordingAudit()))
                .build();
        String body = "{\"context\":{\"value\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}}";

        mvc.perform(post("/api/visual/drafts/run")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("RG.PRODUCTION.REQUEST_BODY_TOO_LARGE"));
    }

    @RestController
    private static final class RunEndpoint {
        private int calls;

        @PostMapping({"/api/visual/drafts/run", "/api/gateway/resources/execute"})
        Map<String, Object> run(@RequestBody Map<String, Object> body) {
            calls++;
            return body;
        }
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();
        @Override public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }
        @Override public List<IntegrationAccessAuditRecord> recent(int limit) { return List.copyOf(records); }
    }
}
