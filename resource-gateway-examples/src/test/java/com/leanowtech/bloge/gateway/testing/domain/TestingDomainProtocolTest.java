package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestingDomainProtocolTest {

    @Test
    void occurrenceAndAttemptCoordinatesRejectNegativeProtocolValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TestRunEvidence.NodeTrace(
                "node", "operator", "SUCCESS", "REAL", null, null, "", 0,
                "/root/node#PRIMARY", "/root", "", -1, 1, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new TestRunEvidence.NodeTrace(
                "node", "operator", "SUCCESS", "REAL", null, null, "", 0,
                "/root/node#PRIMARY", "/root", "", 1, -1, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new TestRunEvidence.AttemptTrace(
                -1, "SUCCESS", "REAL", null, null, "", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new TestRunEvidence.AttemptTrace(
                1, "SUCCESS", "REAL", null, null, "", -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new TestRunEvidence.EdgeTrace(
                "a->b", "TRANSFERRED", null, "/root", "", -1,
                "/root/a#PRIMARY", "/root/b#PRIMARY"));
    }

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void fixtureBundleRoundTripsActiveAndReservedControlFields() throws Exception {
        FixtureRule rule = new FixtureRule(
                "",
                "policy-timeout-then-success",
                new FixtureRule.Selector(
                        "/root/foreach:orders",
                        "fetchPolicy",
                        "resource:policy.get",
                        "policy.get",
                        "",
                        List.of("external-read"),
                        List.of("risk"),
                        InvocationSite.InvocationKind.RESOURCE,
                        List.of(1, 2),
                        List.of(1),
                        "order-1001",
                        new FixtureRule.Match(
                                Map.of("orderId", "order-1001"),
                                Map.of("/customer/tier", "GOLD"),
                                List.of("/customer/id"),
                                List.of("/deletedAt"),
                                Map.of("type", "object"),
                                "order-1001",
                                Map.of("/customer/id", "^C-[0-9]{4}$"))),
                new FixtureRule.Behavior(
                        FixtureRule.BehaviorKind.TIMEOUT,
                        FixtureRule.DoubleBoundary.TRANSPORT,
                        Map.of("decision", "REVIEW"),
                        "{\"code\":\"00000\",\"data\":{\"decision\":\"REVIEW\"}}",
                        200,
                        Map.of("Content-Type", "application/json"),
                        "RG.TEST.UPSTREAM_TIMEOUT",
                        "UPSTREAM_TIMEOUT",
                        "policy service timed out",
                        Duration.ofSeconds(2),
                        List.of(new FixtureRule.BehaviorStep("RETURN", Duration.ZERO,
                                Map.of("decision", "REVIEW"), "")),
                        "run-previous/resource-policy"),
                new FixtureRule.Consumption(true, 1, 2,
                        FixtureRule.ExhaustedAction.FAIL, FixtureRule.UnmatchedAction.FAIL),
                new FixtureRule.SchemaCheck(FixtureRule.SchemaCheckMode.STRICT, ""));
        FixtureBundle bundle = new FixtureBundle(
                "",
                "fixture-order-risk",
                9,
                "sha256:target",
                "INTERNAL",
                Instant.parse("2026-07-15T09:00:00Z"),
                314159L,
                List.of(rule),
                List.of(new FixtureBundle.Assertion("OUTPUT_PATH", "fetchPolicy", "/decision",
                        "EQUALS", "REVIEW", 0.01)),
                Map.of("owner", "risk-team"));

        FixtureBundle restored = mapper.readValue(mapper.writeValueAsBytes(bundle), FixtureBundle.class);

        assertThat(restored).isEqualTo(bundle);
        assertThat(restored.schemaVersion()).isEqualTo(FixtureBundle.SCHEMA_VERSION);
        assertThat(restored.rules().getFirst().selector().attempts()).containsExactly(1, 2);
        assertThat(restored.rules().getFirst().behavior().kind())
                .isEqualTo(FixtureRule.BehaviorKind.TIMEOUT);
        assertThatThrownBy(() -> restored.rules().add(rule))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invocationSiteCarriesStableIdentityAndReservedRuntimeCoordinates() {
        InvocationSite site = new InvocationSite(
                "",
                "sha256:artifact",
                "/root/compensation",
                "refund",
                "payment:refund",
                "payments.refund",
                "",
                "sha256:binding",
                InvocationSite.InvocationKind.COMPENSATION,
                2,
                "payment-42",
                1);

        assertThat(site.schemaVersion()).isEqualTo(InvocationSite.SCHEMA_VERSION);
        assertThat(site.invocationSiteId()).isEqualTo("/root/compensation/refund#COMPENSATION");
        assertThat(site.attempt()).isEqualTo(2);
        assertThat(site.occurrence()).isEqualTo(1);
    }

    @Test
    void executionPlanAndEvidenceFreezeFingerprintChainAndObservedFacts() throws Exception {
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                "",
                "plan-1",
                "sha256:plan",
                "GRAPH_CONTRACT_TEST",
                "sha256:target",
                "sha256:fixture",
                List.of(new EffectiveExecutionPlan.ResolvedSite(
                        "/root/fetchPolicy#RESOURCE",
                        EffectiveExecutionPlan.Resolution.TEST_DOUBLE,
                        FixtureRule.BehaviorKind.RETURN,
                        FixtureRule.DoubleBoundary.TRANSPORT,
                        List.of("policy-success"),
                        "PROTOCOL_DERIVED")),
                Map.of("externalRead", "DENY_UNMATCHED"),
                List.of());
        TestRunEvidence evidence = new TestRunEvidence(
                "",
                "test-run-1",
                TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE,
                "GRAPH_CONTRACT_TEST",
                "sha256:target",
                "sha256:fixture",
                plan.planFingerprint(),
                Instant.parse("2026-07-15T09:00:00Z"),
                Instant.parse("2026-07-15T09:00:01Z"),
                List.of(new TestRunEvidence.NodeTrace("fetchPolicy", "resource:policy.get",
                        "MOCKED", "PROTOCOL_DERIVED", Map.of("id", "C-1"),
                        Map.of("decision", "REVIEW"), "", 18L)),
                List.of(new TestRunEvidence.EdgeTrace("input->fetchPolicy", "TRANSFERRED",
                        Map.of("id", "C-1"))),
                List.of(new TestRunEvidence.FixtureConsumption("policy-success", 1, true, "CONSUMED")),
                List.of(new TestRunEvidence.AssertionResult("OUTPUT_PATH", "/decision", true,
                        "REVIEW", "REVIEW", "")),
                List.of(),
                Map.of("suiteId", "order-risk"));

        TestRunEvidence restored = mapper.readValue(mapper.writeValueAsBytes(evidence), TestRunEvidence.class);

        assertThat(restored).isEqualTo(evidence);
        assertThat(restored.planFingerprint()).isEqualTo(plan.planFingerprint());
        assertThat(restored.nodeTrace()).singleElement()
                .satisfies(trace -> {
                    assertThat(trace.fidelity()).isEqualTo("PROTOCOL_DERIVED");
                    assertThat(trace.invocationSiteId()).isEmpty();
                    assertThat(trace.occurrence()).isZero();
                    assertThat(trace.attempts()).isEmpty();
                });
    }

    @Test
    void suiteRunEvidenceRoundTripsCanonicalCoverageWithoutEmbeddingChildPayloads() throws Exception {
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "risk-regression", 2, "sha256:" + "a".repeat(64));
        TestSuite.Target target = new TestSuite.Target(
                "GRAPH", "riskGraph", "sha256:" + "b".repeat(64));
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "risk-golden", 4, "sha256:" + "c".repeat(64));
        TestSuiteRunEvidence.CoverageVerdict coverage = new TestSuiteRunEvidence.CoverageVerdict(
                TestSuiteRunEvidence.CoverageStatus.SATISFIED, 1, 1,
                List.of(TestSuite.CaseType.REGRESSION, TestSuite.CaseType.GOLDEN),
                List.of(TestSuite.CaseType.GOLDEN, TestSuite.CaseType.REGRESSION), List.of(),
                List.of("/root/z#PRIMARY", "/root/a#PRIMARY"),
                List.of("/root/a#PRIMARY", "/root/z#PRIMARY"), List.of(),
                List.of(new TestSuite.EdgeTransferRef("/root/z#PRIMARY", "/root/out#PRIMARY"),
                        new TestSuite.EdgeTransferRef("/root/a#PRIMARY", "/root/z#PRIMARY")),
                List.of(new TestSuite.EdgeTransferRef("/root/a#PRIMARY", "/root/z#PRIMARY"),
                        new TestSuite.EdgeTransferRef("/root/z#PRIMARY", "/root/out#PRIMARY")),
                List.of(), 1, List.of(), List.of(), true);
        TestSuiteRunEvidence evidence = new TestSuiteRunEvidence("", "suite-run-1", "build-42",
                TestSuiteRunEvidence.Status.PASSED, "TEST_SUITE_EXECUTION", suiteRef, target,
                Instant.parse("2026-07-15T09:00:00Z"), Instant.parse("2026-07-15T09:00:02Z"),
                List.of(new TestSuiteRunEvidence.CaseResult("golden", TestSuite.CaseType.GOLDEN,
                        fixture, TestSuiteRunEvidence.CaseStatus.PASSED, "child-run-1",
                        TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                        2, 2, "", "")), coverage,
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(), true,
                        1, 1, true, true, true), List.of(), Map.of("pipeline", "release"));

        TestSuiteRunEvidence restored = mapper.readValue(
                mapper.writeValueAsBytes(evidence), TestSuiteRunEvidence.class);

        assertThat(restored).isEqualTo(evidence);
        assertThat(restored.coverage().requiredInvocationSiteIds())
                .containsExactly("/root/a#PRIMARY", "/root/z#PRIMARY");
        assertThat(restored.caseResults().getFirst().runId()).isEqualTo("child-run-1");
        assertThat(mapper.valueToTree(restored).toString())
                .doesNotContain("nodeTrace", "input", "output");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TestSuiteRunEvidence.CaseResult("broken", TestSuite.CaseType.NEGATIVE,
                        fixture, TestSuiteRunEvidence.CaseStatus.FAILED, "", null, null,
                        1, 2, "", ""));
    }
}
