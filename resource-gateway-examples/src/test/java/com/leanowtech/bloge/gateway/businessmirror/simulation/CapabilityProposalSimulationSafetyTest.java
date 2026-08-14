package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityProposalSimulationSafetyTest {
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant", "customer-service", "refund", "test", "sg");

    @Test
    void rejectsRealBehaviorAndRealFallbackBeforePlanning() {
        FixtureRule real = new FixtureRule("", "real-rule", FixtureRule.Selector.node("lookup"),
                FixtureRule.Behavior.real(), FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
        FixtureRule fallback = new FixtureRule("", "fallback-rule",
                FixtureRule.Selector.node("lookup"), FixtureRule.Behavior.returning(Map.of()),
                new FixtureRule.Consumption(true, 1, 1,
                        FixtureRule.ExhaustedAction.FALLBACK_TO_REAL,
                        FixtureRule.UnmatchedAction.ALLOW_REAL),
                FixtureRule.SchemaCheck.strict());

        assertForbidden(fixture(real));
        assertForbidden(fixture(fallback));
    }

    @Test
    void refusesToSealPassedEvidenceWhenProposalWasNeverExercised() {
        MirrorArtifactRef suite = ref("TEST_SUITE", "suite", '1');
        CapabilityProposalSimulationEvidence.CaseEvidence oneCase =
                new CapabilityProposalSimulationEvidence.CaseEvidence(
                        "case-1", TestSuite.CaseType.GOLDEN, suite,
                        ref("FIXTURE_BUNDLE", "fixture", '2'),
                        ref("MIRROR_PLAN", "plan", '3'),
                        ref("MIRROR_EVIDENCE_BUNDLE", "run", '4'),
                        "PASSED", List.of(), List.of(), 0, List.of());

        assertThatThrownBy(() -> new CapabilityProposalSimulationEvidence("", "simulation-1", "",
                SCOPE, ref("CAPABILITY_PROPOSAL_DRAFT", "proposal", '5'),
                ref("DOMAIN_CAPABILITY_PACKAGE", "package", '6'),
                ref("GRAPH_DRAFT", "built-in:graph", '7'),
                ref("CAPABILITY_CLOSURE", "base", '8'),
                ref("CAPABILITY_CLOSURE", "simulated", '9'),
                ref("CAPABILITY", "target", 'a'),
                ref("CAPABILITY", "temporary", 'b'), List.of(suite),
                CapabilityProposalSimulationEvidence.Status.PASSED, List.of(oneCase),
                Instant.parse("2026-08-14T10:00:00Z"),
                Instant.parse("2026-08-14T10:00:01Z"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failed case");
    }

    private static void assertForbidden(FixtureBundle fixture) {
        assertThatThrownBy(() -> CapabilityProposalSimulationService.requireIsolatedFixture(
                fixture, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.PROPOSAL.REAL_EXECUTION_FORBIDDEN"));
    }

    private static FixtureBundle fixture(FixtureRule rule) {
        return new FixtureBundle("", "fixture", 1, fingerprint('c'), "INTERNAL",
                Instant.parse("2026-08-14T10:00:00Z"), 1L,
                List.of(rule), List.of(), Map.of());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(SCOPE.tenantId(), SCOPE.organizationId(),
                SCOPE.projectId(), SCOPE.environmentId(), SCOPE.region(), "WORKLOAD", "tester",
                "", "MIRROR_REHEARSAL", "correlation-1", Set.of("mirror-operators"),
                "RESTRICTED", "");
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
