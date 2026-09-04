package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.dsl.compiler.CompilationDiagnostic;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
            List.of(), Map.of(), Map.of(), "sha256:reference", "sha256:context",
            new DslAuthoringContext.AuthoringScope("tenant-a", "project-a", "test"));

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

    @Test
    void normalizesCompilerHintsToTheStrictPublicInfoLevel() {
        DslSafeDiagnosticRegistry.MappedDiagnostic mapped = registry.compiler(
                new CompilationDiagnostic(CompilationDiagnostic.Level.HINT,
                        "provider payload customer-secret", "customer-secret", "token-value",
                        3, 4, "future-structured-hint", "semantic",
                        Map.of("payload", "customer-secret")));

        assertThat(mapped.diagnostic().level()).isEqualTo("INFO");
        assertThat(mapper.valueToTree(mapped.diagnostic()).toString())
                .doesNotContain("customer-secret", "token-value", "provider payload");
    }

    @Test
    void classifiesDisallowedEffectsAsGovernedStopsWithoutSuggestingBypass() {
        DslSafeDiagnosticRegistry.MappedDiagnostic mapped = registry.visual(new VisualDiagnostic(
                "ERROR", "visual.operator.policyDenied", "sensitive policy details",
                "/nodes/private-write/operatorRef", 4, 7, Map.of("secret", "not-public")), context);

        assertThat(mapped.blocking()).isTrue();
        assertThat(mapped.diagnostic().code()).isEqualTo("DSL_EFFECT_NOT_ALLOWED");
        assertThat(mapped.diagnostic().phase()).isEqualTo("SEMANTIC_COMPILE");
        assertThat(mapped.diagnostic().resolutionClass()).isEqualTo("HUMAN_OR_PLATFORM_REQUIRED");
        assertThat(mapped.diagnostic().blocking()).isTrue();
        assertThat(mapped.diagnostic().fixHints()).isEmpty();
        assertThat(mapper.valueToTree(mapped.diagnostic()).toString())
                .doesNotContain("sensitive", "private-write", "not-public");
    }

    @Test
    void truncationKeepsLateBlockingErrorsAndReportsTheUntruncatedTotals() {
        List<DslSafeDiagnosticRegistry.MappedDiagnostic> diagnostics = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            diagnostics.add(mapped("INFO", "LINT", "INFO_" + index, index + 1, false));
        }
        diagnostics.add(mapped("ERROR", "TYPE_CHECK", "LATE_BLOCKING_ERROR", 101, true));

        List<DslAuthoringDiagnostic> bounded = DslAuthoringCompiler.bounded(diagnostics);
        DslPreviewReceipt.DiagnosticSummary summary = DslAuthoringCompiler.summary(diagnostics, true);

        assertThat(bounded).hasSize(26);
        assertThat(bounded.getFirst().code()).isEqualTo("LATE_BLOCKING_ERROR");
        assertThat(summary.total()).isEqualTo(101);
        assertThat(summary.byPhase()).extracting(DslPreviewReceipt.PhaseCount::phase,
                        DslPreviewReceipt.PhaseCount::count)
                .contains(org.assertj.core.groups.Tuple.tuple("TYPE_CHECK", 1),
                        org.assertj.core.groups.Tuple.tuple("LINT", 100));
    }

    private static DslSafeDiagnosticRegistry.MappedDiagnostic mapped(
            String level, String phase, String code, int line, boolean blocking) {
        return new DslSafeDiagnosticRegistry.MappedDiagnostic(new DslAuthoringDiagnostic(
                level, phase, code, "", new DslAuthoringDiagnostic.Span(true, line, 1, line, 1),
                "Safe summary", List.of(), List.of(), List.of(), "AGENT_CAN_REVISE", blocking, false,
                "sha256:" + "a".repeat(64)), blocking);
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
        assertThat(mapped.diagnostic().blocking()).isEqualTo(blocking);
    }
}
