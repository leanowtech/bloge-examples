package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.databind.ObjectMapper;

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
                "/root/node#PRIMARY", "/root", "", -1, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new TestRunEvidence.AttemptTrace(
                -1, "SUCCESS", "REAL", null, null, "", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new TestRunEvidence.AttemptTrace(
                1, "SUCCESS", "REAL", null, null, "", -1));
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
}
