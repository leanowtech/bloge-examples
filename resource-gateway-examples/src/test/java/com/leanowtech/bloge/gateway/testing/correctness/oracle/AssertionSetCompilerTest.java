package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.AssertionLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EvaluationKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.GovernanceExpectation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.GovernanceOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.InvocationAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.InvocationOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.NodeAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.NodeOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionCompilationReport.DispositionStatus;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionSetCompilerTest {

    private final AssertionSetCompiler compiler = new AssertionSetCompiler(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void lowersSupportedRuntimeAssertionsAndRetainsGateSourceMap() {
        AssertionSet source = assertionSet(List.of(
                new OutputAssertion(
                        "output", EvaluationKind.RUNTIME, "/decision",
                        OutputOperator.EQUALS, "approve"),
                new NodeAssertion(
                        "node", EvaluationKind.RUNTIME, "manual-review",
                        NodeOperator.STATUS, "SKIPPED"),
                new GovernanceExpectation(
                        "basis", EvaluationKind.GATE,
                        GovernanceOperator.BASIS, "loan-policy@7")));

        AssertionCompilationReport result = compiler.compile(
                source, AssertionEvaluatorProfile.fixtureEvaluatorV1());

        assertThat(result.compatibility().supported()).isTrue();
        assertThat(result.runtimeAssertions()).hasSize(2);
        assertThat(result.gateExpectationCount()).isEqualTo(1);
        assertThat(result.dispositions())
                .extracting(value -> value.assertionId() + ":" + value.status())
                .containsExactly(
                        "basis:RETAINED_GATE",
                        "node:COMPILED_RUNTIME",
                        "output:COMPILED_RUNTIME");
        assertThat(result.runtimeAssertions())
                .extracting(value -> value.scope() + ":" + value.operator())
                .containsExactly("NODE_STATUS:EQUALS", "OUTPUT_PATH:EQUALS");
    }

    @Test
    void reportsEveryUnsupportedAssertionInsteadOfDroppingIt() {
        AssertionSet source = assertionSet(List.of(
                new OutputAssertion(
                        "output", EvaluationKind.RUNTIME, "/decision",
                        OutputOperator.EQUALS, "approve"),
                new InvocationAssertion(
                        "invocation", EvaluationKind.EVIDENCE, "manual-review",
                        InvocationOperator.NOT_USED, true)));

        AssertionCompilationReport result = compiler.compile(
                source, AssertionEvaluatorProfile.fixtureEvaluatorV1());

        assertThat(result.compatibility().supported()).isFalse();
        assertThat(result.compatibility().reasonCode())
                .isEqualTo("RG.CORRECTNESS.ASSERTION_UNSUPPORTED");
        assertThat(result.dispositions()).hasSameSizeAs(source.assertions());
        assertThat(result.dispositions())
                .filteredOn(value -> value.status() == DispositionStatus.UNSUPPORTED)
                .singleElement()
                .extracting(value -> value.assertionId())
                .isEqualTo("invocation");
        assertThat(result.runtimeAssertions()).hasSize(1);
    }

    @Test
    void advertisedCapabilityStillFailsWhenNoDeterministicLoweringExists() {
        AssertionSet source = assertionSet(List.of(
                new OutputAssertion(
                        "range", EvaluationKind.RUNTIME, "/score",
                        OutputOperator.RANGE, List.of(700, 850))));
        AssertionEvaluatorProfile misleading = new AssertionEvaluatorProfile(
                "future-evaluator", List.of("RUNTIME:OUTPUT:RANGE"));

        AssertionCompilationReport result = compiler.compile(source, misleading);

        assertThat(result.compatibility().supported()).isFalse();
        assertThat(result.dispositions().getFirst().reasonCode())
                .isEqualTo("RG.CORRECTNESS.RUNTIME_LOWERING_UNAVAILABLE");
    }

    @Test
    void gateOnlySetIsPreservedButNeverClaimedExecutable() {
        AssertionSet source = assertionSet(List.of(
                new GovernanceExpectation(
                        "owner", EvaluationKind.GATE,
                        GovernanceOperator.OWNER, "credit-owner")));

        AssertionCompilationReport result = compiler.compile(
                source, AssertionEvaluatorProfile.fixtureEvaluatorV1());

        assertThat(result.compatibility().supported()).isFalse();
        assertThat(result.compatibility().reasonCode())
                .isEqualTo("RG.CORRECTNESS.ASSERTION_NONE");
        assertThat(result.gateExpectationCount()).isEqualTo(1);
    }

    @Test
    void compilationSchemaTracksReportAndDispositionFields() throws Exception {
        AssertionCompilationReport result = compiler.compile(
                assertionSet(List.of(new OutputAssertion(
                        "output", EvaluationKind.RUNTIME, "/decision",
                        OutputOperator.EQUALS, "approve"))),
                AssertionEvaluatorProfile.fixtureEvaluatorV1());
        var schema = new ObjectMapper().readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-assertion-compilation-report-v1.schema.json")));

        assertFields(result, schema.path("properties"));
        assertFields(result.dispositions().getFirst(),
                schema.at("/$defs/assertionDisposition/properties"));
    }

    private AssertionSet assertionSet(
            List<AssertionSet.ExecutableAssertionSpec> assertions
    ) {
        PrincipalRef author = new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
        Instant now = Instant.parse("2026-08-15T09:00:00Z");
        return new AssertionSet(
                "", "loan-checks", 0,
                new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a')),
                new ExactAssetRef("ORACLE", "loan-approved", 2, fingerprint('b')),
                AssertionLifecycle.DRAFT, assertions,
                CompilationCompatibility.unsupported("RG.CORRECTNESS.NOT_VALIDATED"),
                new AuditMetadata(now, now, author, author));
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static void assertFields(Object value, com.fasterxml.jackson.databind.JsonNode schema) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Set<String> serialized = new HashSet<>();
        mapper.valueToTree(value).fieldNames().forEachRemaining(serialized::add);
        Set<String> documented = new HashSet<>();
        schema.fieldNames().forEachRemaining(documented::add);
        assertThat(documented).isEqualTo(serialized);
    }
}
