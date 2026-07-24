package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioHandlingAssertionEvaluatorTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T06:00:00Z");
    private static final String INPUT = fingerprint('1');
    private static final String OUTPUT = fingerprint('2');
    private static final String OTHER = fingerprint('3');
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Clock clock =
            Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC);
    private final VisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(clock);
    private final MirrorEvidenceIntegrityService integrity =
            new MirrorEvidenceIntegrityService(mapper, signer, clock);
    private final ScenarioHandlingAssertionEvaluator evaluator =
            new ScenarioHandlingAssertionEvaluator(mapper);
    private final CapabilitySnapshot.Scope scope =
            MirrorPersistenceTestFixtures.scope("org-a");
    private final MirrorPlan plan =
            MirrorPersistenceTestFixtures.plan(
                    mapper, scope, "scenario-plan", '8');

    @Test
    void evaluatesObservedStatusInputErrorAndBudgetsWithoutPayloads() {
        MirrorEvidenceIntegrityService.VerifiedBundle evidence =
                verifiedBundle(MirrorRunEvidence.Status.PASSED);

        ScenarioHandlingAssertionResult nodePass = evaluate(
                evidence, assertion(
                        "node-pass",
                        CaseHandlingAssertion.Observation.NODE_STATUS,
                        selector("loadCustomer", "", "", null),
                        expectation(List.of("MOCKED"), "", "",
                                null, null, null, null)));
        ScenarioHandlingAssertionResult edgePass = evaluate(
                evidence, assertion(
                        "edge-pass",
                        CaseHandlingAssertion.Observation.EDGE_STATUS,
                        selector("", "loadCustomer->format", "", null),
                        expectation(List.of("TRANSFERRED"), "", "",
                                null, null, null, null)));
        ScenarioHandlingAssertionResult inputPass = evaluate(
                evidence, assertion(
                        "input-pass",
                        CaseHandlingAssertion.Observation.INVOCATION_INPUT,
                        selector("", "", invocationSite(), null),
                        expectation(List.of(), "", INPUT,
                                null, null, null, null)));
        ScenarioHandlingAssertionResult errorPass = evaluate(
                evidence, assertion(
                        "error-pass",
                        CaseHandlingAssertion.Observation.ERROR,
                        selector("loadCustomer", "", "", null),
                        expectation(List.of(), "RG.TEMPORARY", "",
                                null, null, null, null)));
        ScenarioHandlingAssertionResult latencyFail = evaluate(
                evidence, assertion(
                        "latency-fail",
                        CaseHandlingAssertion.Observation.LATENCY_BUDGET,
                        selector("loadCustomer", "", "", null),
                        expectation(List.of(), "", "",
                                null, null, 24L, null)));
        ScenarioHandlingAssertionResult retryPass = evaluate(
                evidence, assertion(
                        "retry-pass",
                        CaseHandlingAssertion.Observation.RETRY_BUDGET,
                        selector("loadCustomer", "", "", null),
                        expectation(List.of(), "", "",
                                null, 1L, null, null)));
        ScenarioHandlingAssertionResult retryFail = evaluate(
                evidence, assertion(
                        "retry-fail",
                        CaseHandlingAssertion.Observation.RETRY_BUDGET,
                        selector("loadCustomer", "", "", null),
                        expectation(List.of(), "", "",
                                null, 0L, null, null)));
        ScenarioHandlingAssertionResult resourcePass = evaluate(
                evidence, assertion(
                        "resource-pass",
                        CaseHandlingAssertion.Observation.RESOURCE_BUDGET,
                        CaseHandlingAssertion.Selector.empty(),
                        expectation(List.of(), "", "",
                                null, 5L, null, null)));

        assertThat(List.of(
                nodePass.outcome(), edgePass.outcome(), inputPass.outcome(),
                errorPass.outcome(), retryPass.outcome(),
                resourcePass.outcome()))
                .containsOnly(ScenarioHandlingAssertionResult.Outcome.PASS);
        assertThat(latencyFail.outcome())
                .isEqualTo(ScenarioHandlingAssertionResult.Outcome.FAIL);
        assertThat(latencyFail.observed().durationMillis()).isEqualTo(25);
        assertThat(retryPass.observed().occurrenceCount()).isEqualTo(1);
        assertThat(retryFail.outcome())
                .isEqualTo(ScenarioHandlingAssertionResult.Outcome.FAIL);
        assertThat(resourcePass.observed().occurrenceCount()).isEqualTo(5);
        assertThat(mapper.valueToTree(nodePass).toString())
                .doesNotContain("customer", "request", "response");
    }

    @Test
    void countsOneCapabilityOccurrenceAcrossTwoResolverAttempts() {
        MirrorEvidenceIntegrityService.VerifiedBundle evidence =
                verifiedBundle(MirrorRunEvidence.Status.PASSED);
        CaseHandlingAssertion assertion = assertion(
                "one-capability-occurrence",
                CaseHandlingAssertion.Observation.CAPABILITY_OCCURRENCE,
                selector("", "", "", capabilityRef()),
                expectation(List.of(), "", "",
                        1L, 1L, null, null));

        ScenarioHandlingAssertionResult result =
                evaluator.evaluate(assertion, evidence);

        assertThat(result.outcome())
                .isEqualTo(ScenarioHandlingAssertionResult.Outcome.PASS);
        assertThat(result.observed().occurrenceCount()).isEqualTo(1);
    }

    @Test
    void failsWhenObservedFactsAreAbsentOrDoNotMatch() {
        MirrorEvidenceIntegrityService.VerifiedBundle evidence =
                verifiedBundle(MirrorRunEvidence.Status.PASSED);
        CaseHandlingAssertion wrongStatus = assertion(
                "wrong-status",
                CaseHandlingAssertion.Observation.NODE_STATUS,
                selector("loadCustomer", "", "", null),
                expectation(List.of("SUCCESS"), "", "",
                        null, null, null, null));
        CaseHandlingAssertion absentNode = assertion(
                "absent-node",
                CaseHandlingAssertion.Observation.NODE_STATUS,
                selector("missing", "", "", null),
                expectation(List.of("SUCCESS"), "", "",
                        null, null, null, null));

        ScenarioHandlingAssertionResult mismatch =
                evaluator.evaluate(wrongStatus, evidence);
        ScenarioHandlingAssertionResult absent =
                evaluator.evaluate(absentNode, evidence);

        assertThat(mismatch.outcome())
                .isEqualTo(ScenarioHandlingAssertionResult.Outcome.FAIL);
        assertThat(mismatch.reasonCode())
                .isEqualTo(
                        ScenarioHandlingAssertionResult.ReasonCode
                                .ASSERTION_MISMATCH);
        assertThat(absent.outcome())
                .isEqualTo(ScenarioHandlingAssertionResult.Outcome.FAIL);
        assertThat(absent.reasonCode())
                .isEqualTo(
                        ScenarioHandlingAssertionResult.ReasonCode
                                .ASSERTION_OBSERVATION_ABSENT);
    }

    @Test
    void keepsUnavailableAndIncompleteEvidenceIndeterminate() {
        CaseHandlingAssertion outputAssertion = assertion(
                "graph-output",
                CaseHandlingAssertion.Observation.GRAPH_OUTPUT_VALUE,
                new CaseHandlingAssertion.Selector(
                        "", "", "", null, "/customer/id"),
                expectation(List.of(), "", OUTPUT,
                        null, null, null, null));
        ScenarioHandlingAssertionResult unavailable = evaluator.evaluate(
                outputAssertion,
                verifiedBundle(MirrorRunEvidence.Status.PASSED));
        ScenarioHandlingAssertionResult incomplete = evaluator.evaluate(
                outputAssertion,
                verifiedBundle(MirrorRunEvidence.Status.EVIDENCE_INCOMPLETE));

        assertThat(unavailable.outcome())
                .isEqualTo(
                        ScenarioHandlingAssertionResult.Outcome.INDETERMINATE);
        assertThat(unavailable.reasonCode())
                .isEqualTo(
                        ScenarioHandlingAssertionResult.ReasonCode
                                .ASSERTION_EVIDENCE_FACT_UNAVAILABLE);
        assertThat(unavailable.observed().limitations())
                .containsExactly("MISSING_GRAPH_OUTPUT_VALUE_FACT");
        assertThat(incomplete.outcome())
                .isEqualTo(
                        ScenarioHandlingAssertionResult.Outcome.INDETERMINATE);
        assertThat(incomplete.reasonCode())
                .isEqualTo(
                        ScenarioHandlingAssertionResult.ReasonCode
                                .ASSERTION_EVIDENCE_INCOMPLETE);
    }

    @Test
    void evaluatesGovernanceFromEvidenceClassAndSignedLimitations() {
        CaseHandlingAssertion assertion = assertion(
                "certifiable",
                CaseHandlingAssertion.Observation.GOVERNANCE_EXPECTATION,
                CaseHandlingAssertion.Selector.empty(),
                expectation(List.of("CERTIFIABLE"), "", "",
                        null, null, null, true));

        ScenarioHandlingAssertionResult exploratory = evaluator.evaluate(
                assertion,
                verifiedBundle(MirrorRunEvidence.Status.PASSED));
        MirrorEvidenceBundle certifiableBundle =
                MirrorPersistenceTestFixtures.certifiableEvidence(
                        mapper, signer, plan, "run-certifiable", '9',
                        "request-certifiable", fingerprint('7'),
                        MirrorPersistenceTestFixtures.trustBinding(scope));
        ScenarioHandlingAssertionResult certifiable = evaluator.evaluate(
                assertion, integrity.requireVerified(certifiableBundle));

        assertThat(exploratory.outcome())
                .isEqualTo(ScenarioHandlingAssertionResult.Outcome.FAIL);
        assertThat(exploratory.observed().booleanValue()).isFalse();
        assertThat(exploratory.observed().limitations())
                .containsExactly("DEPLOYMENT_EGRESS_NOT_ATTESTED");
        assertThat(certifiable.outcome())
                .isEqualTo(ScenarioHandlingAssertionResult.Outcome.PASS);
        assertThat(certifiable.observed().booleanValue()).isTrue();
    }

    @Test
    void evaluatesStateTransitionAndReceiptFromWriteOutcomeEvidence() {
        MirrorEvidenceBundle bundle =
                MirrorPersistenceTestFixtures.writeOutcomeEvidence(
                        mapper, signer, plan, "run-state", '6');
        MirrorEvidenceIntegrityService.VerifiedBundle verified =
                integrity.requireVerified(bundle);
        CaseHandlingAssertion transition = assertion(
                "state-transition",
                CaseHandlingAssertion.Observation.STATE_TRANSITION,
                CaseHandlingAssertion.Selector.empty(),
                expectation(List.of("COMMITTED"), "", "",
                        null, null, null, true));
        CaseHandlingAssertion receipt = assertion(
                "effect-receipt",
                CaseHandlingAssertion.Observation.SIDE_EFFECT_RECEIPT,
                CaseHandlingAssertion.Selector.empty(),
                expectation(List.of("COMMITTED"), "", "",
                        null, null, null, true));

        ScenarioHandlingAssertionResult transitionResult =
                evaluator.evaluate(transition, verified);
        ScenarioHandlingAssertionResult receiptResult =
                evaluator.evaluate(receipt, verified);

        assertThat(transitionResult.outcome())
                .isEqualTo(ScenarioHandlingAssertionResult.Outcome.PASS);
        assertThat(receiptResult.outcome())
                .isEqualTo(ScenarioHandlingAssertionResult.Outcome.PASS);
        assertThat(receiptResult.observed().fingerprints()).hasSize(1);
    }

    @Test
    void requiresActiveSameScopeAssertions() {
        MirrorEvidenceIntegrityService.VerifiedBundle evidence =
                verifiedBundle(MirrorRunEvidence.Status.PASSED);
        CaseHandlingAssertion active = assertion(
                "active",
                CaseHandlingAssertion.Observation.NODE_STATUS,
                selector("loadCustomer", "", "", null),
                expectation(List.of("MOCKED"), "", "",
                        null, null, null, null));
        CaseHandlingAssertion draft = ScenarioPackIntegrity.sealAssertion(
                mapper, new CaseHandlingAssertion(
                        active.schemaVersion(), "draft", 1, "", scope,
                        active.observation(), active.selector(),
                        active.expectation(), active.severity(),
                        active.governanceCode(), provenance(),
                        CapabilitySnapshot.Lifecycle.DRAFT,
                        active.createdAt()));
        CapabilitySnapshot.Scope otherProject =
                new CapabilitySnapshot.Scope(
                        scope.tenantId(), scope.organizationId(),
                        "other-project", scope.environmentId(), scope.region());
        CaseHandlingAssertion crossScope =
                ScenarioPackIntegrity.sealAssertion(
                        mapper, new CaseHandlingAssertion(
                                "", "cross-scope", 1, "", otherProject,
                                active.observation(), active.selector(),
                                active.expectation(), active.severity(),
                                active.governanceCode(),
                                provenance(otherProject),
                                CapabilitySnapshot.Lifecycle.ACTIVE,
                                active.createdAt()));
        ArtifactProvenance expiredProvenance =
                new ArtifactProvenance(
                        "", ArtifactProvenance.SourceType.OWNER, List.of(),
                        scope.tenantId(), "scenario-rehearsal",
                        null, null, null, null, List.of(),
                        "support-owner", NOW, NOW.plusMillis(500), "");
        CaseHandlingAssertion expired =
                ScenarioPackIntegrity.sealAssertion(
                        mapper, new CaseHandlingAssertion(
                                "", "expired", 1, "", scope,
                                active.observation(), active.selector(),
                                active.expectation(), active.severity(),
                                active.governanceCode(), expiredProvenance,
                                CapabilitySnapshot.Lifecycle.ACTIVE, NOW));

        assertThatThrownBy(() -> evaluator.evaluate(draft, evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active");
        assertThatThrownBy(() -> evaluator.evaluate(crossScope, evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match the evidence scope");
        assertThatThrownBy(() -> evaluator.evaluate(expired, evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence window");
    }

    @Test
    void sealsDeterministicResultsAndRejectsTampering() {
        MirrorEvidenceIntegrityService.VerifiedBundle evidence =
                verifiedBundle(MirrorRunEvidence.Status.PASSED);
        CaseHandlingAssertion assertion = assertion(
                "deterministic",
                CaseHandlingAssertion.Observation.NODE_STATUS,
                selector("loadCustomer", "", "", null),
                expectation(List.of("MOCKED"), "", "",
                        null, null, null, null));

        ScenarioHandlingAssertionResult first =
                evaluator.evaluate(assertion, evidence);
        ScenarioHandlingAssertionResult second =
                evaluator.evaluate(assertion, evidence);

        assertThat(first).isEqualTo(second);
        ScenarioHandlingAssertionResultIntegrity.verify(mapper, first);
        assertThat(ScenarioHandlingAssertionResultIntegrity.reference(first).kind())
                .isEqualTo("SCENARIO_ASSERTION_RESULT");
        assertThatThrownBy(() ->
                ScenarioHandlingAssertionResultIntegrity.verify(
                        mapper, first.withFingerprint(OTHER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
        assertThatThrownBy(() -> new ScenarioHandlingAssertionResult(
                first.schemaVersion(), first.resultFingerprint(), first.runId(),
                first.evidenceBundleFingerprint(), first.planFingerprint(),
                first.assertionRef(), first.observation(),
                ScenarioHandlingAssertionResult.Outcome.PASS, first.severity(),
                first.governanceCode(),
                ScenarioHandlingAssertionResult.ReasonCode
                        .ASSERTION_EVIDENCE_FACT_UNAVAILABLE,
                first.observed()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
    }

    @Test
    void verificationCapabilityRejectsTamperedAndUnavailableBundles() {
        MirrorEvidenceBundle bundle =
                signedBundle(MirrorRunEvidence.Status.PASSED);
        MirrorRunEvidence source = bundle.evidence();
        MirrorRunEvidence tamperedEvidence = new MirrorRunEvidence(
                source.schemaVersion(), source.runId(), source.requestId(),
                source.requestContextFingerprint(), source.planId(),
                source.planFingerprint(),
                source.capabilityClosureFingerprint(),
                source.executionControlFingerprint(),
                source.rootCapability(), source.fixtureBundleRef(),
                source.externalBindings(), source.scope(),
                source.authorizedPurpose(), source.status(),
                source.evidenceClass(), OTHER, source.startedAt(),
                source.completedAt(), source.nodeTraces(),
                source.edgeTraces(), source.resolutions(),
                source.isolation(), source.limitations());
        MirrorEvidenceBundle tampered = new MirrorEvidenceBundle(
                bundle.schemaVersion(), bundle.bundleFingerprint(),
                bundle.payloadPolicy(), bundle.attestation(),
                tamperedEvidence);
        MirrorEvidenceIntegrityService unavailable =
                new MirrorEvidenceIntegrityService(
                        mapper, VisualEvidenceSigner.unavailable(), clock);

        assertThatThrownBy(() -> integrity.requireVerified(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
        assertThatThrownBy(() -> unavailable.requireVerified(bundle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority is unavailable");
    }

    private ScenarioHandlingAssertionResult evaluate(
            MirrorEvidenceIntegrityService.VerifiedBundle evidence,
            CaseHandlingAssertion assertion) {
        ScenarioHandlingAssertionResult result =
                evaluator.evaluate(assertion, evidence);
        ScenarioHandlingAssertionResultIntegrity.verify(mapper, result);
        return result;
    }

    private MirrorEvidenceIntegrityService.VerifiedBundle verifiedBundle(
            MirrorRunEvidence.Status status) {
        return integrity.requireVerified(signedBundle(status));
    }

    private MirrorEvidenceBundle signedBundle(
            MirrorRunEvidence.Status status) {
        Instant startedAt = NOW;
        MirrorArtifactRef capability = capabilityRef();
        List<MirrorRunEvidence.NodeTrace> nodes = List.of(
                new MirrorRunEvidence.NodeTrace(
                        "loadCustomer", "customer.lookup", "MOCKED",
                        "OUTPUT_LEVEL", INPUT, OUTPUT, "", 25,
                        invocationSite(), "/root", "C-1", 1, 1,
                        List.of(
                                new MirrorRunEvidence.AttemptTrace(
                                        1, "FAILED", "OUTPUT_LEVEL",
                                        INPUT, OUTPUT, "RG.TEMPORARY", 10),
                                new MirrorRunEvidence.AttemptTrace(
                                        2, "MOCKED", "OUTPUT_LEVEL",
                                        INPUT, OUTPUT, "", 15))),
                new MirrorRunEvidence.NodeTrace(
                        "format", "support.format", "SUCCESS",
                        "OPERATOR_LEVEL", OUTPUT, OTHER, "", 10,
                        "/root/format#PRIMARY", "/root", "C-1", 1, 1,
                        List.of()));
        List<MirrorRunEvidence.EdgeTrace> edges = List.of(
                new MirrorRunEvidence.EdgeTrace(
                        "loadCustomer->format", "TRANSFERRED", OUTPUT,
                        "/root", "C-1", 1, invocationSite(),
                        "/root/format#PRIMARY"));
        List<MirrorResolution> resolutions = List.of(
                resolution(capability, 1),
                resolution(capability, 2));
        MirrorRunEvidence evidence = new MirrorRunEvidence(
                "", "run-scenario", "request-scenario", INPUT,
                plan.planId(), plan.planFingerprint(),
                plan.capabilityClosureFingerprint(),
                plan.executionControlFingerprint(),
                plan.rootCapability(), plan.fixtureBundleRef(),
                List.of(new MirrorRunEvidence.ExternalBinding(
                        plan.rootCapability(), "loadCustomer", capability,
                        invocationSite(), "/root")),
                scope, MirrorPersistenceTestFixtures.PURPOSE, status,
                MirrorRunEvidence.EvidenceClass.EXPLORATORY,
                fingerprint('4'), startedAt, startedAt.plusSeconds(1),
                nodes, edges, resolutions,
                new MirrorRunEvidence.IsolationFacts(
                        MirrorRunEvidence.IsolationFacts.EngineMode
                                .INDEPENDENT_TEST_ENGINE,
                        List.of(), List.of("InvocationRecorder"),
                        false, false, false, false, false, false, false,
                        null, null,
                        List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED")),
                List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED"));
        MirrorEvidenceIntegrityService.SealResult sealed =
                integrity.seal(evidence);
        assertThat(sealed.failureCode()).isBlank();
        return sealed.bundle();
    }

    private MirrorResolution resolution(
            MirrorArtifactRef capability,
            int attempt) {
        return MirrorResolutionIntegrity.seal(
                mapper, new MirrorResolution(
                        "", "", "run-scenario", plan.planFingerprint(),
                        capability, invocationSite(), "/root", "C-1",
                        1, attempt, INPUT,
                        MirrorResolution.Status.RESOLVED,
                        MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                        MirrorResolution.PayloadVisibility.HASH_ONLY,
                        false, null, OUTPUT, null,
                        List.of(plan.fixtureBundleRef()),
                        List.of("customer-response"),
                        new ArtifactProvenance.Confidence(
                                1, 1, 1, "owner-rule-v1"),
                        1, List.of("PAYLOAD_HASH_ONLY")));
    }

    private CaseHandlingAssertion assertion(
            String id,
            CaseHandlingAssertion.Observation observation,
            CaseHandlingAssertion.Selector selector,
            CaseHandlingAssertion.Expectation expectation) {
        return ScenarioPackIntegrity.sealAssertion(
                mapper, new CaseHandlingAssertion(
                        "", id, 1, "", scope, observation, selector,
                        expectation,
                        CaseHandlingAssertion.Severity.BLOCKER,
                        "RG.MIRROR.SCENARIO." + id.toUpperCase()
                                .replace('-', '_'),
                        provenance(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        NOW.minus(Duration.ofDays(2))));
    }

    private ArtifactProvenance provenance() {
        return provenance(scope);
    }

    private static ArtifactProvenance provenance(
            CapabilitySnapshot.Scope scope) {
        return new ArtifactProvenance(
                "", ArtifactProvenance.SourceType.OWNER, List.of(),
                scope.tenantId(), "scenario-rehearsal",
                null, null, null, null, List.of(),
                "support-owner", NOW.minus(Duration.ofDays(2)),
                NOW.plus(Duration.ofDays(1)), "");
    }

    private static CaseHandlingAssertion.Selector selector(
            String nodeId,
            String edgeId,
            String invocationSiteId,
            MirrorArtifactRef capabilityRef) {
        return new CaseHandlingAssertion.Selector(
                nodeId, edgeId, invocationSiteId, capabilityRef, "");
    }

    private static CaseHandlingAssertion.Expectation expectation(
            List<String> statuses,
            String errorCode,
            String valueFingerprint,
            Long minimumOccurrences,
            Long maximumOccurrences,
            Long maximumDurationMillis,
            Boolean expectedBoolean) {
        return new CaseHandlingAssertion.Expectation(
                statuses, errorCode, "", valueFingerprint,
                minimumOccurrences, maximumOccurrences,
                maximumDurationMillis, expectedBoolean);
    }

    private MirrorArtifactRef capabilityRef() {
        return plan.externalBindings().getFirst().capabilityRef();
    }

    private String invocationSite() {
        return plan.externalBindings().getFirst().invocationSiteId();
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
