package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Certifies the stable diagnostic vocabulary and its payload-free fallback boundary. */
class DslSafeDiagnosticRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DslSafeDiagnosticRegistry registry = new DslSafeDiagnosticRegistry(mapper);
    private final DslAuthoringContext context = new DslAuthoringContext(
            "rg.dslAuthoringContext.v1", "test", "test", Set.of("graph"),
            List.of(), Map.of(), Map.of(), "sha256:reference", "sha256:context");

    @Test
    void mapsEveryVisualDiagnosticFamilyToOneStablePhaseAndResolutionClass() {
        assertMapped("visual.dslImport.parseFailed", "DSL_PARSE_EXPECTED_CONSTRUCT", "PARSE",
                "AGENT_CAN_REVISE", true);
        assertMapped("visual.dslImport.rootUnsupported", "DSL_ROOT_UNSUPPORTED", "PARSE",
                "AGENT_CAN_REVISE", true);
        assertMapped("visual.dslImport.operatorMissing", "DSL_OPERATOR_NOT_FOUND", "RESOLVE",
                "AGENT_CAN_REVISE", true);
        assertMapped("visual.dslImport.functionMissing", "DSL_FUNCTION_NOT_FOUND", "RESOLVE",
                "AGENT_CAN_REVISE", true);
        assertMapped("visual.binding.typeMismatch", "DSL_TYPE_MISMATCH", "TYPE_CHECK",
                "AGENT_CAN_REVISE", true);
        assertMapped("visual.binding.unknownTargetPort", "DSL_INPUT_PORT_UNKNOWN", "TYPE_CHECK",
                "AGENT_CAN_REVISE", true);
        assertMapped("visual.binding.unknownSourcePort", "DSL_OUTPUT_PORT_UNKNOWN", "TYPE_CHECK",
                "AGENT_CAN_REVISE", true);
        assertMapped("visual.input.required", "DSL_REQUIRED_INPUT_MISSING", "TYPE_CHECK",
                "AGENT_CAN_REVISE", true);
        assertMapped("visual.policyDenied", "DSL_EFFECT_NOT_ALLOWED", "SEMANTIC_COMPILE",
                "HUMAN_OR_PLATFORM_REQUIRED", true);
        assertMapped("visual.dslImport.importUnsupported", "DSL_PROJECTION_UNSUPPORTED", "PROJECT",
                "PLATFORM_MAINTAINER", true);
        assertMapped("new.lower.layer.code", "DSL_DIAGNOSTIC_UNCLASSIFIED", "SEMANTIC_COMPILE",
                "PLATFORM_MAINTAINER", true);
    }

    @Test
    void neverCopiesLowerMessageMetadataOrBusinessTargetSegments() throws Exception {
        DslSafeDiagnosticRegistry.MappedDiagnostic mapped = registry.visual(new VisualDiagnostic(
                "ERROR", "new.lower.layer.code", "provider payload customer-secret",
                "/nodes/customer-secret/config/token-value", 7, 9,
                Map.of("url", "https://customer-secret.invalid", "token", "customer-secret")), context);

        String serialized = mapper.writeValueAsString(mapped.diagnostic());

        assertThat(serialized).doesNotContain("customer-secret", "token-value", "https://");
        assertThat(mapped.diagnostic().target()).isEqualTo("/nodes/*/config/*");
        assertThat(mapped.diagnostic().span().known()).isTrue();
        assertThat(mapped.diagnostic().diagnosticFingerprint()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void ownsExplicitReservedRoundTripAndPlatformFallbackEntries() {
        assertThat(registry.identifierReserved(2, 7).diagnostic().code())
                .isEqualTo("DSL_IDENTIFIER_RESERVED");
        assertThat(registry.roundTripDrift().diagnostic().resolutionClass())
                .isEqualTo("PLATFORM_MAINTAINER");
        assertThat(registry.designOnlyRoundTripDeferred().blocking()).isFalse();
        assertThat(registry.platformDefect("LINT").diagnostic().code())
                .isEqualTo("DSL_DIAGNOSTIC_UNCLASSIFIED");
    }

    private void assertMapped(String lowerCode,
                              String code,
                              String phase,
                              String resolution,
                              boolean blocking) {
        DslSafeDiagnosticRegistry.MappedDiagnostic mapped = registry.visual(new VisualDiagnostic(
                "ERROR", lowerCode, "customer-secret", "/nodes/customer-secret", 0, 0,
                Map.of("payload", "customer-secret")), context);

        assertThat(mapped.diagnostic().code()).isEqualTo(code);
        assertThat(mapped.diagnostic().phase()).isEqualTo(phase);
        assertThat(mapped.diagnostic().resolutionClass()).isEqualTo(resolution);
        assertThat(mapped.blocking()).isEqualTo(blocking);
    }
}
