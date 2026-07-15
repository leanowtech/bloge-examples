package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteBuilderTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void buildsExactDependencyClosedSuiteWithConservativePromotionDefaults() {
        GraphTargetDescriptor target = new GraphTargetDescriptor("loanDecision", FINGERPRINT,
                Map.of(), "CONSERVATIVE_ALL_REGISTERED", true, List.of(), null);
        FixtureBundleRevision golden = fixture("fixture-golden");
        FixtureBundleRevision boundary = fixture("fixture-boundary");
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        mutableInput.put("amount", 1000);

        TestSuiteBuilder builder = TestSuiteBuilder.graph(target)
                .id("loan-policy")
                .revision(4)
                .addCase("golden", TestSuiteBuilder.CaseType.GOLDEN,
                        mutableInput, golden, List.of("smoke", "loan"),
                        Map.of("owner", "risk"))
                .addCase("boundary", TestSuiteBuilder.CaseType.BOUNDARY,
                        Map.of("amount", 0), boundary)
                .requireCaseTypes(TestSuiteBuilder.CaseType.GOLDEN, TestSuiteBuilder.CaseType.BOUNDARY)
                .requireInvocationSite("/root/credit#primary")
                .requireEdgeTransfer("/root/input#primary", "/root/credit#primary")
                .metadata(Map.of("source", "ci"));
        mutableInput.put("amount", 9999);
        ObjectNode request = builder.registrationRequest();

        assertThat(request.path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_REGISTRATION_REQUEST_V1);
        assertThat(request.at("/testSuite/target/kind").asText()).isEqualTo("GRAPH");
        assertThat(request.at("/testSuite/target/fingerprint").asText()).isEqualTo(FINGERPRINT);
        assertThat(request.at("/testSuite/cases/0/fixtureBundleRef/fixtureBundleId").asText())
                .isEqualTo("fixture-golden");
        assertThat(request.at("/testSuite/cases/0/input/amount").asInt()).isEqualTo(1000);
        assertThat(request.at("/testSuite/cases/0/tags")).extracting(node -> node.asText())
                .containsExactly("loan", "smoke");
        assertThat(request.at("/testSuite/coveragePolicy/minimumCases").asInt()).isEqualTo(2);
        assertThat(request.at("/testSuite/coveragePolicy/requiredCaseTypes"))
                .extracting(node -> node.asText()).containsExactly("BOUNDARY", "GOLDEN");
        assertThat(request.at("/testSuite/coveragePolicy/minimumAssertionsPerCase").asInt()).isEqualTo(1);
        assertThat(request.at("/testSuite/coveragePolicy/requireAllFixtureRulesConsumed").asBoolean()).isTrue();
        assertThat(request.at("/testSuite/promotionPolicy/requireAllCasesPassed").asBoolean()).isTrue();
        assertThat(request.at("/testSuite/promotionPolicy/minimumCertifiableCases").asInt()).isEqualTo(2);
        assertThat(request.at("/testSuite/promotionPolicy/requireTargetCertificationEligible").asBoolean())
                .isTrue();
    }

    @Test
    void rejectsDuplicateCasesAndImpossibleCoverageBeforeRegistration() {
        GraphTargetDescriptor target = new GraphTargetDescriptor("loanDecision", FINGERPRINT,
                Map.of(), "CONSERVATIVE_ALL_REGISTERED", true, List.of(), null);
        TestSuiteBuilder builder = TestSuiteBuilder.graph(target).id("loan-policy")
                .addCase("golden", TestSuiteBuilder.CaseType.GOLDEN, Map.of(), fixture("fixture-1"));

        assertThatThrownBy(() -> builder.addCase("golden", TestSuiteBuilder.CaseType.REGRESSION,
                Map.of(), fixture("fixture-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> builder.minimumCases(2).registrationRequest())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimumCases");

        TestSuiteBuilder unrepresentedType = TestSuiteBuilder.graph(target).id("missing-case-type")
                .addCase("golden", TestSuiteBuilder.CaseType.GOLDEN, Map.of(), fixture("fixture-3"))
                .requireCaseTypes(TestSuiteBuilder.CaseType.GOLDEN, TestSuiteBuilder.CaseType.NEGATIVE);
        assertThatThrownBy(unrepresentedType::registrationRequest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requiredCaseTypes")
                .hasMessageContaining("NEGATIVE");

        assertThatThrownBy(() -> TestSuiteBuilder.graph(target).id("scalar-graph-input")
                .addCase("golden", TestSuiteBuilder.CaseType.GOLDEN, "not-an-object", fixture("fixture-4")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Graph suite case input");

        FixtureBundleRevision invalidRevision = new FixtureBundleRevision("fixture-invalid", 0,
                FINGERPRINT, "tenant", "test", Instant.parse("2026-07-15T10:15:30Z"), "ci", null);
        assertThatThrownBy(() -> TestSuiteBuilder.graph(target).id("invalid-fixture-suite")
                .addCase("golden", TestSuiteBuilder.CaseType.GOLDEN, Map.of(), invalidRevision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixture revision");
    }

    @Test
    void semanticRequirementsUpgradeSuiteToV2AndRemainDeterministicallyOrdered() {
        GraphTargetDescriptor target = new GraphTargetDescriptor("loanDecision", FINGERPRINT,
                Map.of(), "CONSERVATIVE_ALL_REGISTERED", true, List.of(), null);

        ObjectNode request = TestSuiteBuilder.graph(target).id("semantic-loan-policy")
                .addCase("golden", TestSuiteBuilder.CaseType.GOLDEN, Map.of(), fixture("fixture-v2"))
                .requireTimeout("timeout", "/root/remote#PRIMARY", "UPSTREAM_TIMEOUT")
                .requireDecisionRule("decision", "/root/decision#PRIMARY", "/rule", "APPROVE")
                .requireBranchTransferred("branch", "/root/input#PRIMARY", "/root/decision#PRIMARY")
                .requireRetry("retry", "/root/remote#PRIMARY", 3)
                .requireFallback("fallback", "/root/remote#PRIMARY")
                .requireCompensation("compensation", "/root/refund#COMPENSATION")
                .registrationRequest();

        assertThat(request.at("/testSuite/schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_V2);
        assertThat(request.at("/testSuite/semanticCoveragePolicy/requirements"))
                .extracting(item -> item.path("requirementId").asText())
                .containsExactly("branch", "compensation", "decision", "fallback", "retry", "timeout");
        assertThat(request.at("/testSuite/semanticCoveragePolicy/requirements/2/expectedScalar").asText())
                .isEqualTo("APPROVE");
    }

    @Test
    void semanticBuilderRejectsDuplicateIdentityAndInvalidCoordinates() {
        GraphTargetDescriptor target = new GraphTargetDescriptor("loanDecision", FINGERPRINT,
                Map.of(), "CONSERVATIVE_ALL_REGISTERED", true, List.of(), null);
        TestSuiteBuilder builder = TestSuiteBuilder.graph(target).id("semantic-invalid")
                .addCase("golden", TestSuiteBuilder.CaseType.GOLDEN, Map.of(), fixture("fixture-v2"))
                .requireRetry("retry", "/root/remote#PRIMARY", 2);

        assertThatThrownBy(() -> builder.requireFallback("retry", "/root/remote#PRIMARY"))
                .hasMessageContaining("Duplicate semantic requirement");
        assertThatThrownBy(() -> builder.requireDecisionRule(
                "decision", "/root/decision#PRIMARY", "not-a-pointer", Map.of()))
                .hasMessageContaining("JSON Pointer and scalar");
        assertThatThrownBy(() -> builder.requireCompensation(
                "compensation", "/root/refund#PRIMARY"))
                .hasMessageContaining("#COMPENSATION");
    }

    private static FixtureBundleRevision fixture(String id) {
        return new FixtureBundleRevision(id, 1, FINGERPRINT, "tenant", "test",
                Instant.parse("2026-07-15T10:15:30Z"), "ci", null);
    }
}
