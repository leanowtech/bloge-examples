package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalWorkbookSeedTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private static final Instant STARTED =
            Instant.parse("2026-07-24T08:00:00Z");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void projectsOneGateReadyClosureDeterministically() {
        Fixture fixture = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE);

        ScenarioRehearsalWorkbookSeed first =
                ScenarioRehearsalWorkbookSeed.project(
                        mapper,
                        fixture.plan(),
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()));
        ScenarioRehearsalWorkbookSeed second =
                ScenarioRehearsalWorkbookSeed.project(
                        mapper,
                        fixture.plan(),
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()));

        assertThat(first).isEqualTo(second);
        assertThat(first.gateReady()).isTrue();
        assertThat(first.blockers()).isEmpty();
        assertThat(first.scenarioPackRef())
                .isEqualTo(fixture.plan().scenarioPackRef());
        assertThat(first.compiledPlanRef())
                .isEqualTo(
                        CompiledScenarioRehearsalPlanIntegrity
                                .reference(fixture.plan()));
        assertThat(first.retentionProof().eventFingerprint())
                .isEqualTo(fixture.registration().eventFingerprint());
        assertThat(first.cases()).singleElement()
                .satisfies(value -> {
                    assertThat(value.evidenceBacked()).isTrue();
                    assertThat(value.assertionResults())
                            .singleElement()
                            .extracting(
                                    ScenarioHandlingAssertionResult
                                            ::governanceCode)
                            .isEqualTo(
                                    "RG.MIRROR.SCENARIO.CUSTOMER_FOUND");
                });
        first.verify(mapper);
    }

    @Test
    void blocksExploratoryChildEvidenceWithoutChangingBusinessOutcome() {
        Fixture fixture = fixture(
                MirrorRunEvidence.EvidenceClass.EXPLORATORY);

        ScenarioRehearsalWorkbookSeed seed =
                ScenarioRehearsalWorkbookSeed.project(
                        mapper,
                        fixture.plan(),
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()));

        assertThat(seed.outcome())
                .isEqualTo(
                        ScenarioCaseRehearsalResult.Outcome.PASS);
        assertThat(seed.gateReady()).isFalse();
        assertThat(seed.blockers())
                .containsExactly(
                        "CHILD_EVIDENCE_NOT_CERTIFIABLE");
    }

    @Test
    void rejectsCompiledCaseDriftAndTamperedSelfFingerprint() {
        Fixture fixture = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE);
        CompiledScenarioRehearsalPlan plan =
                fixture.plan();
        CompiledScenarioRehearsalPlan drifted =
                CompiledScenarioRehearsalPlanIntegrity.seal(
                        mapper,
                        new CompiledScenarioRehearsalPlan(
                                "",
                                plan.planId(),
                                plan.revision(),
                                "",
                                plan.scope(),
                                plan.scenarioPackRef(),
                                plan.targetCapabilityRef(),
                                List.of(
                                        new CompiledScenarioRehearsalPlan
                                                .CaseBinding(
                                                plan.cases().getFirst()
                                                        .scenarioCaseRef(),
                                                plan.cases().getFirst()
                                                        .caseType(),
                                                plan.cases().getFirst()
                                                        .testSuiteRef(),
                                                "different-test-case",
                                                plan.cases().getFirst()
                                                        .mirrorPlanRef(),
                                                plan.cases().getFirst()
                                                        .fixtureBundleRef(),
                                                null,
                                                plan.cases().getFirst()
                                                        .executionServices(),
                                                plan.cases().getFirst()
                                                        .assertionRefs())),
                                plan.assertionRefs(),
                                plan.policy()));

        assertThatThrownBy(() ->
                ScenarioRehearsalWorkbookSeed.project(
                        mapper,
                        drifted,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source identities");

        ScenarioRehearsalWorkbookSeed seed =
                ScenarioRehearsalWorkbookSeed.project(
                        mapper,
                        plan,
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()));
        assertThatThrownBy(() ->
                seed.withFingerprint(fingerprint('f'))
                        .verify(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    @Test
    void rejectsUnsignedOrNonRegistrationRetentionProof() {
        Fixture fixture = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE);
        ScenarioRehearsalRetentionEvent unsigned =
                new ScenarioRehearsalRetentionEvent(
                        "",
                        "retention-event-1",
                        "retention-register-1",
                        SCOPE,
                        fixture.bundle().result().requestId(),
                        fixture.bundle().attestation().runId(),
                        1,
                        ScenarioRehearsalRetentionEvent.Type
                                .RETENTION_REGISTERED,
                        STARTED.plus(Duration.ofDays(30)),
                        STARTED.plusSeconds(3),
                        "scenario-runtime",
                        "RG.MIRROR.RETENTION_REGISTERED",
                        "",
                        fixture.bundle().bundleFingerprint(),
                        "",
                        0,
                        ScenarioRehearsalRetentionEvent
                                .ChildEvidenceDisposition
                                .NOT_APPLICABLE,
                        VisualRunEvidenceSeal.unsigned());

        assertThatThrownBy(() ->
                ScenarioRehearsalWorkbookSeed.project(
                        mapper,
                        fixture.plan(),
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(unsigned)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registration proof");
    }

    @Test
    void admitsTheEvidenceProtocolMaximumKeyIdentity() {
        Fixture fixture = fixture(
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE);
        ScenarioRehearsalWorkbookSeed seed =
                ScenarioRehearsalWorkbookSeed.project(
                        mapper,
                        fixture.plan(),
                        fixture.bundle(),
                        fixture.retentionState(),
                        List.of(fixture.registration()));

        ScenarioRehearsalWorkbookSeed maximum =
                new ScenarioRehearsalWorkbookSeed(
                        seed.schemaVersion(),
                        "",
                        seed.scope(),
                        seed.runId(),
                        seed.requestId(),
                        seed.scenarioPackRef(),
                        seed.compiledPlanRef(),
                        seed.targetCapabilityRef(),
                        seed.evidenceBundleFingerprint(),
                        seed.resultFingerprint(),
                        "k".repeat(1_024),
                        seed.retentionProof(),
                        seed.outcome(),
                        seed.summary(),
                        seed.cases(),
                        seed.gateReady(),
                        seed.blockers());

        assertThat(maximum.evidenceKeyId())
                .hasSize(1_024);
        assertThatThrownBy(() ->
                new ScenarioRehearsalWorkbookSeed(
                        seed.schemaVersion(),
                        "",
                        seed.scope(),
                        seed.runId(),
                        seed.requestId(),
                        seed.scenarioPackRef(),
                        seed.compiledPlanRef(),
                        seed.targetCapabilityRef(),
                        seed.evidenceBundleFingerprint(),
                        seed.resultFingerprint(),
                        "k".repeat(1_025),
                        seed.retentionProof(),
                        seed.outcome(),
                        seed.summary(),
                        seed.cases(),
                        seed.gateReady(),
                        seed.blockers()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1024");
    }

    private Fixture fixture(
            MirrorRunEvidence.EvidenceClass evidenceClass) {
        MirrorArtifactRef assertionRef =
                ref(
                        "CASE_HANDLING_ASSERTION",
                        "customer-found",
                        fingerprint('1'));
        CompiledScenarioRehearsalPlan plan =
                CompiledScenarioRehearsalPlanIntegrity.seal(
                        mapper,
                        new CompiledScenarioRehearsalPlan(
                                "",
                                "support-scenarios@compiled",
                                1,
                                "",
                                SCOPE,
                                ref(
                                        "SCENARIO_PACK",
                                        "support-scenarios",
                                        fingerprint('2')),
                                ref(
                                        "CAPABILITY",
                                        "customer-support",
                                        fingerprint('3')),
                                List.of(
                                        new CompiledScenarioRehearsalPlan
                                                .CaseBinding(
                                                ref(
                                                        "SCENARIO_CASE",
                                                        "customer-found",
                                                        fingerprint('4')),
                                                ScenarioCase.CaseType.GOLDEN,
                                                ref(
                                                        "TEST_SUITE",
                                                        "support-suite",
                                                        fingerprint('5')),
                                                "customer-found",
                                                ref(
                                                        "MIRROR_PLAN",
                                                        "support-plan",
                                                        fingerprint('6')),
                                                ref(
                                                        "FIXTURE_BUNDLE",
                                                        "support-fixture",
                                                        fingerprint('7')),
                                                null,
                                                new MirrorPlan
                                                        .ExecutionServices(
                                                        STARTED,
                                                        42L,
                                                        null,
                                                        null),
                                                List.of(assertionRef))),
                                List.of(assertionRef),
                                policy()));
        String childRunId = "mirror-child-run-1";
        String childEvidence = fingerprint('8');
        ScenarioHandlingAssertionResult assertion =
                ScenarioHandlingAssertionResultIntegrity.seal(
                        mapper,
                        new ScenarioHandlingAssertionResult(
                                "",
                                "",
                                childRunId,
                                childEvidence,
                                plan.cases().getFirst()
                                        .mirrorPlanRef().fingerprint(),
                                assertionRef,
                                CaseHandlingAssertion.Observation
                                        .NODE_STATUS,
                                ScenarioHandlingAssertionResult.Outcome.PASS,
                                CaseHandlingAssertion.Severity.BLOCKER,
                                "RG.MIRROR.SCENARIO.CUSTOMER_FOUND",
                                ScenarioHandlingAssertionResult.ReasonCode
                                        .ASSERTION_MATCHED,
                                ScenarioHandlingAssertionResult
                                        .ObservedFacts.empty()));
        ScenarioCaseRehearsalResult caseResult =
                ScenarioRehearsalResultIntegrity.sealCase(
                        mapper,
                        new ScenarioCaseRehearsalResult(
                                "",
                                "",
                                0,
                                plan.cases().getFirst().scenarioCaseRef(),
                                plan.cases().getFirst().caseType(),
                                plan.cases().getFirst().testSuiteRef(),
                                plan.cases().getFirst().testCaseId(),
                                plan.cases().getFirst().mirrorPlanRef(),
                                plan.cases().getFirst().fixtureBundleRef(),
                                null,
                                "scenario-request-1:case:000",
                                ScenarioCaseRehearsalResult.Outcome.PASS,
                                childRunId,
                                childEvidence,
                                MirrorRunEvidence.Status.PASSED,
                                evidenceClass,
                                List.of(assertion),
                                "",
                                STARTED,
                                STARTED.plusSeconds(1)));
        List<ScenarioCaseRehearsalResult> cases =
                List.of(caseResult);
        ScenarioRehearsalResult result =
                ScenarioRehearsalResultIntegrity.seal(
                        mapper,
                        new ScenarioRehearsalResult(
                                "",
                                "",
                                "scenario-request-1",
                                CompiledScenarioRehearsalPlanIntegrity
                                        .reference(plan),
                                SCOPE,
                                plan.targetCapabilityRef(),
                                ScenarioRehearsalResult.deriveOutcome(cases),
                                cases,
                                ScenarioRehearsalResult.Summary.from(cases),
                                STARTED,
                                STARTED.plusSeconds(1)));
        String runId = ScenarioRehearsalRunIdentity.derive(
                mapper, SCOPE, result.requestId());
        ScenarioRehearsalEvidenceAttestation attestation =
                new ScenarioRehearsalEvidenceAttestation(
                        "",
                        ScenarioRehearsalEvidenceAttestation
                                .SignatureStatus.VERIFIED,
                        runId,
                        result.requestId(),
                        plan.fingerprint(),
                        result.resultFingerprint(),
                        STARTED.plusSeconds(2),
                        "scenario-evidence-key",
                        "Ed25519",
                        "c2lnbmF0dXJl",
                        true);
        ScenarioRehearsalEvidenceBundle bundle =
                new ScenarioRehearsalEvidenceBundle(
                        "",
                        fingerprint('9'),
                        ScenarioRehearsalEvidenceBundle
                                .PayloadPolicy.HASH_ONLY,
                        attestation,
                        result);
        ScenarioRehearsalRetentionEvent registration =
                registration(bundle);
        ScenarioRehearsalRetentionState retentionState =
                new ScenarioRehearsalRetentionState(
                        "",
                        SCOPE,
                        runId,
                        result.requestId(),
                        bundle.bundleFingerprint(),
                        ScenarioRehearsalRetentionState.Status.RETAINED,
                        1,
                        registration.retainUntil(),
                        List.of(),
                        registration.occurredAt(),
                        registration);
        return new Fixture(
                plan, bundle, registration, retentionState);
    }

    private ScenarioRehearsalRetentionEvent registration(
            ScenarioRehearsalEvidenceBundle bundle) {
        ScenarioRehearsalRetentionEvent material =
                new ScenarioRehearsalRetentionEvent(
                        "",
                        "retention-event-1",
                        "retention-register-1",
                        SCOPE,
                        bundle.result().requestId(),
                        bundle.attestation().runId(),
                        1,
                        ScenarioRehearsalRetentionEvent.Type
                                .RETENTION_REGISTERED,
                        STARTED.plus(Duration.ofDays(30)),
                        STARTED.plusSeconds(3),
                        "scenario-runtime",
                        "RG.MIRROR.RETENTION_REGISTERED",
                        "",
                        bundle.bundleFingerprint(),
                        "",
                        0,
                        ScenarioRehearsalRetentionEvent
                                .ChildEvidenceDisposition
                                .NOT_APPLICABLE,
                        VisualRunEvidenceSeal.unsigned());
        String fingerprint = material.eventFingerprint();
        return material.withEvidenceSeal(
                new VisualRunEvidenceSeal(
                        "",
                        fingerprint,
                        "Ed25519",
                        "retention-key",
                        STARTED.plusSeconds(3),
                        "c2lnbmF0dXJl"));
    }

    private static ScenarioPack.RehearsalPolicy policy() {
        return new ScenarioPack.RehearsalPolicy(
                ScenarioPack.Scheduling.SEQUENTIAL,
                true,
                false,
                false,
                false,
                ScenarioPack.EvidenceMode.HASH_ONLY,
                10,
                100,
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                true,
                CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg"));
    }

    private static MirrorArtifactRef ref(
            String kind, String id, String fingerprint) {
        return new MirrorArtifactRef(
                kind, id, 1, fingerprint);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(
            CompiledScenarioRehearsalPlan plan,
            ScenarioRehearsalEvidenceBundle bundle,
            ScenarioRehearsalRetentionEvent registration,
            ScenarioRehearsalRetentionState retentionState
    ) {
    }
}
