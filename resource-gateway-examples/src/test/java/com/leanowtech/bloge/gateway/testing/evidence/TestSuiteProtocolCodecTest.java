package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteProtocolCodecTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TestSuiteProtocolCodec codec = new TestSuiteProtocolCodec(mapper);

    @Test
    void v1RoundTripRetainsConcreteJsonAndHistoricalFingerprintAlgorithm() {
        TestSuite suite = v1();
        String json = codec.write(suite);

        assertThat(codec.read(json)).isInstanceOf(TestSuite.class).isEqualTo(suite);
        assertThat(codec.write(codec.read(json))).isEqualTo(json);
        assertThat(codec.fingerprint(suite)).isEqualTo(ProtocolFingerprint.of(mapper, suite));
        assertThat(json).doesNotContain("semanticCoveragePolicy");
    }

    @Test
    void v2RoundTripRetainsSemanticGenerationAndRejectsClassVersionMismatch() {
        TestSuite base = v1();
        TestSuiteV2 suite = new TestSuiteV2("", base.suiteId(), base.revision(), base.target(),
                base.classification(), base.cases(), base.coveragePolicy(), new SemanticCoveragePolicy(
                List.of(new SemanticCoveragePolicy.RetryRequirement("retry",
                        SemanticCoveragePolicy.Kind.RETRY, "/root/remote#PRIMARY", 2))),
                base.promotionPolicy(), base.metadata());

        assertThat(codec.read(codec.write(suite))).isInstanceOf(TestSuiteV2.class).isEqualTo(suite);
        assertThat(codec.fingerprint(suite)).isNotEqualTo(codec.fingerprint(base));
        assertThatThrownBy(() -> codec.fingerprint(new TestSuite(TestSuiteV2.SCHEMA_VERSION,
                base.suiteId(), base.revision(), base.target(), base.classification(), base.cases(),
                base.coveragePolicy(), base.promotionPolicy(), base.metadata())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("class and schemaVersion");
        assertThatThrownBy(() -> codec.read("{\"schemaVersion\":\"bloge.testSuite.v99\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported schemaVersion");
    }

    @Test
    void v3RoundTripRetainsAdmissionExpectationsWithoutChangingOlderGenerations() {
        TestSuite base = v1();
        TestSuiteV3 suite = new TestSuiteV3("", "schema-boundary", 2, base.target(),
                base.classification(), base.cases(),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.GOLDEN),
                        List.of(), List.of(), 0, false), SemanticCoveragePolicy.empty(),
                new TestSuite.PromotionPolicy(true, 0, false),
                TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                FINGERPRINT, "sha256:" + "b".repeat(64),
                Map.of("golden", new TestSuiteV3.AdmissionExpectation(
                        TestSuiteV3.ExpectedOutcome.ACCEPTED, List.of())),
                Map.of("source", "boundary-plan"));

        String json = codec.write(suite);

        assertThat(codec.read(json)).isInstanceOf(TestSuiteV3.class).isEqualTo(suite);
        assertThat(json).contains("SCHEMA_ADMISSION", "admissionExpectations")
                .doesNotContain("\"schemaVersion\":\"bloge.testSuite.v2\"");
        assertThat(codec.fingerprint(v1())).isEqualTo(ProtocolFingerprint.of(mapper, v1()));
        assertThatThrownBy(() -> new TestSuiteV3("", "invalid", 1, base.target(),
                base.classification(), base.cases(), suite.coveragePolicy(),
                SemanticCoveragePolicy.empty(), suite.promotionPolicy(), suite.evaluationMode(),
                FINGERPRINT, suite.inputSchemaFingerprint(),
                Map.of("golden", new TestSuiteV3.AdmissionExpectation(
                        TestSuiteV3.ExpectedOutcome.ACCEPTED, List.of("unexpected"))), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Accepted admission expectation");
        assertThatThrownBy(() -> new TestSuiteV3.AdmissionExpectation(
                TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED,
                List.of("visual.context.typeMismatch", "visual.context.typeMismatch")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique non-empty");
    }

    private static TestSuite v1() {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef("fixture", 1, FINGERPRINT);
        return new TestSuite("", "suite", 1,
                new TestSuite.Target("GRAPH", "graph", FINGERPRINT), "INTERNAL",
                List.of(new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                        Map.of("input", "hello"), fixture, List.of("ci"), Map.of())),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.GOLDEN),
                        List.of(), List.of(), 1, true),
                new TestSuite.PromotionPolicy(true, 1, true), Map.of("owner", "quality"));
    }
}
