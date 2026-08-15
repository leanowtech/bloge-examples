package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.CompiledAssetSummary;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.Diagnostic;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.ExecutionRiskSummary;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrectnessCompilationProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void normalizesPayloadFreeReportCollections() throws Exception {
        ExactAssetRef fixture = ref("FIXTURE_BUNDLE", "fixture-b", 'b');
        ExactAssetRef suite = ref("TEST_SUITE", "suite-a", 'a');
        CorrectnessCompilationReport report = new CorrectnessCompilationReport(
                "", true, "correctness-compiler-1", coordinate(), fp('9'), List.of(),
                List.of(new CompiledAssetSummary(suite, 2),
                        new CompiledAssetSummary(fixture, 1)),
                List.of(Diagnostic.warning(
                        "RG.CORRECTNESS.REAL_CALL", null, "/scenarios/0",
                        "correctness.compilation.realCall")),
                new ExecutionRiskSummary(1, 1, 0, 0, 0, 0, false,
                        List.of("REAL_CALL", "REAL_CALL")));

        assertThat(report.compiledAssets()).extracting(value -> value.assetRef().kind())
                .containsExactly("FIXTURE_BUNDLE", "TEST_SUITE");
        assertThat(report.riskSummary().riskCodes()).containsExactly("REAL_CALL");
        String json = mapper.writeValueAsString(report);
        assertThat(json).doesNotContain("fixturePayload", "secret-value", "registrationRequest");
    }

    @Test
    void blockedReportCannotExposePartialCompiledAssets() {
        assertThatThrownBy(() -> new CorrectnessCompilationReport(
                "", false, "correctness-compiler-1", coordinate(), fp('9'), List.of(),
                List.of(new CompiledAssetSummary(
                        ref("FIXTURE_BUNDLE", "partial", 'b'), 1)),
                List.of(Diagnostic.error(
                        "RG.CORRECTNESS.BLOCKED", null, "", "correctness.blocked")),
                ExecutionRiskSummary.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partial compiled assets");
    }

    @Test
    void compilationCoordinateCanonicalizesSetLikeClosure() {
        CompilationCoordinate coordinate = new CompilationCoordinate(
                ref("DEFINITION", "definition", '1'),
                ref("INVENTORY", "inventory", '2'),
                ref("SCENARIO_DRAFT_SET", "scenarios", '3'),
                List.of(ref("ORACLE", "z-oracle", '4'), ref("ORACLE", "a-oracle", '5')),
                List.of(ref("ASSERTION_SET", "assertions", '6')),
                List.of(ref("FIXTURE_ASSET", "z-fixture", '7'),
                        ref("FIXTURE_ASSET", "a-fixture", '8')),
                target());

        assertThat(coordinate.oracleRefs()).extracting(ExactAssetRef::id)
                .containsExactly("a-oracle", "z-oracle");
        assertThat(coordinate.fixtureAssetRefs()).extracting(ExactAssetRef::id)
                .containsExactly("a-fixture", "z-fixture");
    }

    private CompilationCoordinate coordinate() {
        return new CompilationCoordinate(
                ref("DEFINITION", "definition", '1'),
                ref("INVENTORY", "inventory", '2'),
                ref("SCENARIO_DRAFT_SET", "scenarios", '3'),
                List.of(ref("ORACLE", "oracle", '4')),
                List.of(ref("ASSERTION_SET", "assertions", '5')),
                List.of(ref("FIXTURE_ASSET", "fixture", '6')),
                target());
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 1, fp('0'));
    }

    private ExactAssetRef ref(String kind, String id, char digit) {
        return new ExactAssetRef(kind, id, 1, fp(digit));
    }

    private String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
