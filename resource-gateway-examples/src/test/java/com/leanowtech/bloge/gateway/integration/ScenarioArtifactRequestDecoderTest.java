package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.CaseHandlingAssertion;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioPackIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalCompileRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalExecutionRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalLegalHoldCommand;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalPurgeCommand;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioArtifactRequestDecoderTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T02:00:00Z");
    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final ScenarioArtifactRequestDecoder decoder =
            new ScenarioArtifactRequestDecoder(mapper);

    @Test
    void decodesExactCompileAndAssertionProtocols() throws Exception {
        ScenarioRehearsalCompileRequest compileRequest =
                new ScenarioRehearsalCompileRequest("", 1, SHA_A);
        ScenarioRehearsalExecutionRequest executionRequest =
                new ScenarioRehearsalExecutionRequest(
                        "", "request-1",
                        new MirrorArtifactRef(
                                "COMPILED_REHEARSAL_PLAN",
                                "support@compiled-v1", 1, SHA_A));
        ScenarioRehearsalLegalHoldCommand holdCommand =
                new ScenarioRehearsalLegalHoldCommand(
                        "", "hold-command-1", "legal-a",
                        "RG.MIRROR.REHEARSAL.LITIGATION");
        ScenarioRehearsalPurgeCommand purgeCommand =
                new ScenarioRehearsalPurgeCommand(
                        "", "purge-command-1",
                        "RG.MIRROR.REHEARSAL.RETENTION_EXPIRED");
        CaseHandlingAssertion assertion = assertion();

        assertThat(decoder.decodeCompileRequest(
                mapper.writeValueAsBytes(compileRequest), identity()))
                .isEqualTo(compileRequest);
        assertThat(decoder.decodeAssertion(
                mapper.writeValueAsBytes(assertion), identity()))
                .isEqualTo(assertion);
        assertThat(decoder.decodeExecutionRequest(
                mapper.writeValueAsBytes(executionRequest), identity()))
                .isEqualTo(executionRequest);
        assertThat(decoder.decodeLegalHoldCommand(
                mapper.writeValueAsBytes(holdCommand), identity()))
                .isEqualTo(holdCommand);
        assertThat(decoder.decodePurgeCommand(
                mapper.writeValueAsBytes(purgeCommand), identity()))
                .isEqualTo(purgeCommand);
    }

    @Test
    void rejectsDuplicateAndUnknownTopLevelFieldsBeforeConstruction() {
        String duplicated = """
                {
                  "schemaVersion":"resourceGateway.scenarioRehearsalCompileRequest.v1",
                  "schemaVersion":"resourceGateway.scenarioRehearsalCompileRequest.v1",
                  "revision":1,
                  "fingerprint":"%s"
                }
                """.formatted(SHA_A);
        String unknown = """
                {
                  "schemaVersion":"resourceGateway.scenarioRehearsalCompileRequest.v1",
                  "revision":1,
                  "fingerprint":"%s",
                  "latest":true
                }
                """.formatted(SHA_A);

        assertMalformed(duplicated.getBytes(StandardCharsets.UTF_8));
        assertMalformed(unknown.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnknownNestedFieldsAndBusinessPayloadSmuggling()
            throws Exception {
        ObjectNode value = mapper.valueToTree(assertion());
        ((ObjectNode) value.path("selector"))
                .put("request", "hidden");

        assertThatThrownBy(() -> decoder.decodeAssertion(
                mapper.writeValueAsBytes(value), identity()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo(
                                "RG.MIRROR.SCENARIO_REQUEST_MALFORMED"));
    }

    @Test
    void rejectsRuntimeContextOverrides() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put(
                "schemaVersion",
                ScenarioRehearsalExecutionRequest.SCHEMA_VERSION);
        request.put("requestId", "request-1");
        request.set(
                "compiledPlanRef",
                mapper.valueToTree(new MirrorArtifactRef(
                        "COMPILED_REHEARSAL_PLAN",
                        "support@compiled-v1", 1, SHA_A)));
        request.putObject("context").put("customerId", "smuggled");

        assertThatThrownBy(() -> decoder.decodeExecutionRequest(
                mapper.writeValueAsBytes(request), identity()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.SCENARIO_REQUEST_MALFORMED"));
    }

    @Test
    void rejectsOversizedRawBodiesBeforeJsonParsing() {
        byte[] oversized =
                new byte[ScenarioArtifactRequestDecoder
                        .MAXIMUM_REQUEST_BYTES + 1];

        assertThatThrownBy(() -> decoder.decodePack(
                oversized, identity()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().details())
                                .containsEntry(
                                        "maximumBytes",
                                        ScenarioArtifactRequestDecoder
                                                .MAXIMUM_REQUEST_BYTES));
    }

    @Test
    void rejectsUnknownOrDuplicateRetentionCommandFields() {
        String unknown = """
                {
                  "schemaVersion":"resourceGateway.scenarioRehearsalLegalHoldCommand.v1",
                  "commandId":"hold-command-1",
                  "holdId":"legal-a",
                  "reasonCode":"RG.MIRROR.REHEARSAL.LITIGATION",
                  "payload":{"customer":"must-not-cross"}
                }
                """;
        String duplicated = """
                {
                  "schemaVersion":"resourceGateway.scenarioRehearsalPurgeCommand.v1",
                  "commandId":"purge-command-1",
                  "commandId":"purge-command-2",
                  "reasonCode":"RG.MIRROR.REHEARSAL.RETENTION_EXPIRED"
                }
                """;

        assertThatThrownBy(() -> decoder.decodeLegalHoldCommand(
                unknown.getBytes(StandardCharsets.UTF_8), identity()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.SCENARIO_REQUEST_MALFORMED"));
        assertThatThrownBy(() -> decoder.decodePurgeCommand(
                duplicated.getBytes(StandardCharsets.UTF_8), identity()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.SCENARIO_REQUEST_MALFORMED"));
    }

    private CaseHandlingAssertion assertion() {
        CapabilitySnapshot.Scope scope = new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "support", "test", "sg");
        return ScenarioPackIntegrity.sealAssertion(
                mapper,
                new CaseHandlingAssertion(
                        "", "customer-node-status", 1, "", scope,
                        CaseHandlingAssertion.Observation.NODE_STATUS,
                        new CaseHandlingAssertion.Selector(
                                "loadCustomer", "", "", null, ""),
                        new CaseHandlingAssertion.Expectation(
                                List.of("SUCCESS"), "", "", "",
                                null, null, null, null),
                        CaseHandlingAssertion.Severity.BLOCKER,
                        "RG.MIRROR.SCENARIO.NODE_FAILED",
                        new ArtifactProvenance(
                                "",
                                ArtifactProvenance.SourceType.OWNER,
                                List.of(),
                                scope.tenantId(),
                                "MIRROR_REHEARSAL",
                                null, null, null, null,
                                List.of(),
                                "support-owner",
                                NOW,
                                NOW.plus(Duration.ofDays(1)),
                                ""),
                        CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW));
    }

    private void assertMalformed(byte[] value) {
        assertThatThrownBy(() -> decoder.decodeCompileRequest(
                value, identity()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.SCENARIO_REQUEST_MALFORMED"));
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "support", "test", "sg",
                "SERVICE", "scenario-client", "",
                "MIRROR_REHEARSAL", "corr-scenario",
                Set.of(), "CONFIDENTIAL", "");
    }
}
