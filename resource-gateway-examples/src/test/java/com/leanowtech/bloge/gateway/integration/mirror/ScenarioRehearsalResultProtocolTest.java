package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalResultProtocolTest {
    private static final Instant STARTED =
            Instant.parse("2026-07-24T08:00:00Z");
    private static final String EVIDENCE = fingerprint('a');
    private static final String PLAN = fingerprint('b');
    private static final CapabilitySnapshot.Scope SCOPE =
            MirrorPersistenceTestFixtures.scope("org-a");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void executionRequestAdmitsOnlyOneExactCompiledPlan() {
        MirrorArtifactRef planRef = ref(
                "COMPILED_REHEARSAL_PLAN", "support-rehearsal", PLAN);
        ScenarioRehearsalExecutionRequest request =
                new ScenarioRehearsalExecutionRequest(
                        "", "rehearsal-request-1", planRef);

        assertThat(request.schemaVersion())
                .isEqualTo(ScenarioRehearsalExecutionRequest.SCHEMA_VERSION);
        assertThat(mapper.valueToTree(request).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "schemaVersion", "requestId", "compiledPlanRef");
        assertThatThrownBy(() -> new ScenarioRehearsalExecutionRequest(
                "", "request/with/override", planRef))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioRehearsalExecutionRequest(
                "", "request-1", ref("MIRROR_PLAN", "wrong", PLAN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivesCaseOutcomeWithoutLettingWarningsBecomeBlockers() {
        ScenarioHandlingAssertionResult blockerPass = assertionResult(
                "blocker-pass", CaseHandlingAssertion.Severity.BLOCKER,
                ScenarioHandlingAssertionResult.Outcome.PASS);
        ScenarioHandlingAssertionResult warningFail = assertionResult(
                "warning-fail", CaseHandlingAssertion.Severity.WARNING,
                ScenarioHandlingAssertionResult.Outcome.FAIL);
        List<ScenarioHandlingAssertionResult> assertions =
                List.of(blockerPass, warningFail);

        ScenarioCaseRehearsalResult result =
                caseResult(
                        0, MirrorRunEvidence.Status.PASSED,
                        assertions,
                        ScenarioCaseRehearsalResult.Outcome.PASS, "");

        assertThat(result.warningFailures()).isEqualTo(1);
        assertThat(result.blockerFailures()).isZero();
        assertThat(result.outcome())
                .isEqualTo(ScenarioCaseRehearsalResult.Outcome.PASS);
    }

    @Test
    void keepsExecutionFailureAndMissingEvidenceFailClosed() {
        ScenarioHandlingAssertionResult pass = assertionResult(
                "blocker-pass", CaseHandlingAssertion.Severity.BLOCKER,
                ScenarioHandlingAssertionResult.Outcome.PASS);
        ScenarioCaseRehearsalResult executionFailure =
                caseResult(
                        0, MirrorRunEvidence.Status.EXECUTION_FAILED,
                        List.of(pass),
                        ScenarioCaseRehearsalResult.Outcome.FAIL,
                        "RG.MIRROR.REHEARSAL.CASE_EXECUTION_FAILED");
        ScenarioCaseRehearsalResult unavailable =
                preEvidenceResult(
                        1, ScenarioCaseRehearsalResult.Outcome.INDETERMINATE,
                        "RG.MIRROR.REHEARSAL.RUNTIME_UNAVAILABLE");

        assertThat(executionFailure.outcome())
                .isEqualTo(ScenarioCaseRehearsalResult.Outcome.FAIL);
        assertThat(unavailable.outcome())
                .isEqualTo(
                        ScenarioCaseRehearsalResult.Outcome.INDETERMINATE);
        assertThatThrownBy(() ->
                preEvidenceResult(
                        2, ScenarioCaseRehearsalResult.Outcome.PASS, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fail closed");
    }

    @Test
    void rejectsFalseCaseOutcomesAndCrossEvidenceAssertions() {
        ScenarioHandlingAssertionResult blockerFail = assertionResult(
                "blocker-fail", CaseHandlingAssertion.Severity.BLOCKER,
                ScenarioHandlingAssertionResult.Outcome.FAIL);
        ScenarioHandlingAssertionResult otherRun =
                assertionResult(
                        "other-run", "run-other",
                        CaseHandlingAssertion.Severity.BLOCKER,
                        ScenarioHandlingAssertionResult.Outcome.PASS);

        assertThatThrownBy(() -> caseResult(
                0, MirrorRunEvidence.Status.PASSED,
                List.of(blockerFail),
                ScenarioCaseRehearsalResult.Outcome.PASS, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derived from evidence");
        assertThatThrownBy(() -> caseResult(
                0, MirrorRunEvidence.Status.PASSED,
                List.of(otherRun),
                ScenarioCaseRehearsalResult.Outcome.PASS, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact child evidence");
    }

    @Test
    void sealsAndVerifiesAggregatePrecedenceCountersAndNestedResults() {
        ScenarioCaseRehearsalResult passed =
                ScenarioRehearsalResultIntegrity.sealCase(
                        mapper,
                        caseResult(
                                0, MirrorRunEvidence.Status.PASSED,
                                List.of(
                                        assertionResult(
                                                "pass",
                                                CaseHandlingAssertion.Severity
                                                        .BLOCKER,
                                                ScenarioHandlingAssertionResult
                                                        .Outcome.PASS),
                                        assertionResult(
                                                "warning",
                                                CaseHandlingAssertion.Severity
                                                        .WARNING,
                                                ScenarioHandlingAssertionResult
                                                        .Outcome.FAIL)),
                                ScenarioCaseRehearsalResult.Outcome.PASS, ""));
        ScenarioCaseRehearsalResult indeterminate =
                ScenarioRehearsalResultIntegrity.sealCase(
                        mapper,
                        preEvidenceResult(
                                1,
                                ScenarioCaseRehearsalResult.Outcome
                                        .INDETERMINATE,
                                "RG.MIRROR.REHEARSAL.RUNTIME_UNAVAILABLE"));
        List<ScenarioCaseRehearsalResult> cases =
                List.of(passed, indeterminate);
        ScenarioRehearsalResult material =
                new ScenarioRehearsalResult(
                        "", "", "rehearsal-request-1",
                        ref(
                                "COMPILED_REHEARSAL_PLAN",
                                "support-rehearsal",
                                fingerprint('c')),
                        SCOPE,
                        ref("CAPABILITY", "support", fingerprint('d')),
                        ScenarioRehearsalResult.deriveOutcome(cases),
                        cases,
                        ScenarioRehearsalResult.Summary.from(cases),
                        STARTED, STARTED.plusSeconds(2));
        ScenarioRehearsalResult sealed =
                ScenarioRehearsalResultIntegrity.seal(mapper, material);

        ScenarioRehearsalResultIntegrity.verify(mapper, sealed);
        assertThat(sealed.outcome())
                .isEqualTo(
                        ScenarioCaseRehearsalResult.Outcome.INDETERMINATE);
        assertThat(sealed.summary())
                .isEqualTo(new ScenarioRehearsalResult.Summary(
                        2, 1, 0, 1, 2, 0, 0, 1, 0));
        assertThat(ScenarioRehearsalResultIntegrity.reference(sealed).kind())
                .isEqualTo("SCENARIO_REHEARSAL_RESULT");
        assertThat(mapper.valueToTree(sealed).toString())
                .doesNotContain(
                        "\"input\"", "\"output\"", "\"context\"",
                        "\"fixturePayload\"", "\"entity\"");
        assertThatThrownBy(() ->
                ScenarioRehearsalResultIntegrity.verify(
                        mapper, sealed.withFingerprint(fingerprint('f'))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    private ScenarioCaseRehearsalResult caseResult(
            int index,
            MirrorRunEvidence.Status status,
            List<ScenarioHandlingAssertionResult> assertions,
            ScenarioCaseRehearsalResult.Outcome outcome,
            String diagnosticCode) {
        return new ScenarioCaseRehearsalResult(
                "", "", index,
                ref("SCENARIO_CASE", "case-" + index, fingerprint('1')),
                ScenarioCase.CaseType.GOLDEN,
                ref("TEST_SUITE", "suite", fingerprint('2')),
                "test-case-" + index,
                ref("MIRROR_PLAN", "mirror-plan", PLAN),
                ref("FIXTURE_BUNDLE", "fixture", fingerprint('3')),
                null, "rehearsal-request-1:case:" + index,
                outcome, "run-1", EVIDENCE, status,
                MirrorRunEvidence.EvidenceClass.EXPLORATORY,
                assertions, diagnosticCode,
                STARTED.plusMillis(index),
                STARTED.plusSeconds(1));
    }

    private ScenarioCaseRehearsalResult preEvidenceResult(
            int index,
            ScenarioCaseRehearsalResult.Outcome outcome,
            String diagnosticCode) {
        return new ScenarioCaseRehearsalResult(
                "", "", index,
                ref("SCENARIO_CASE", "case-" + index, fingerprint('1')),
                ScenarioCase.CaseType.GOLDEN,
                ref("TEST_SUITE", "suite", fingerprint('2')),
                "test-case-" + index,
                ref("MIRROR_PLAN", "mirror-plan", PLAN),
                ref("FIXTURE_BUNDLE", "fixture", fingerprint('3')),
                null, "rehearsal-request-1:case:" + index,
                outcome, "", "", null, null, List.of(), diagnosticCode,
                STARTED.plusMillis(index),
                STARTED.plusSeconds(1));
    }

    private ScenarioHandlingAssertionResult assertionResult(
            String id,
            CaseHandlingAssertion.Severity severity,
            ScenarioHandlingAssertionResult.Outcome outcome) {
        return assertionResult(id, "run-1", severity, outcome);
    }

    private ScenarioHandlingAssertionResult assertionResult(
            String id,
            String runId,
            CaseHandlingAssertion.Severity severity,
            ScenarioHandlingAssertionResult.Outcome outcome) {
        ScenarioHandlingAssertionResult.ReasonCode reason = switch (outcome) {
            case PASS ->
                    ScenarioHandlingAssertionResult.ReasonCode
                            .ASSERTION_MATCHED;
            case FAIL ->
                    ScenarioHandlingAssertionResult.ReasonCode
                            .ASSERTION_MISMATCH;
            case INDETERMINATE ->
                    ScenarioHandlingAssertionResult.ReasonCode
                            .ASSERTION_EVIDENCE_FACT_UNAVAILABLE;
        };
        return ScenarioHandlingAssertionResultIntegrity.seal(
                mapper,
                new ScenarioHandlingAssertionResult(
                        "", "", runId, EVIDENCE, PLAN,
                        ref(
                                "CASE_HANDLING_ASSERTION",
                                id,
                                fingerprint('4')),
                        CaseHandlingAssertion.Observation.NODE_STATUS,
                        outcome, severity,
                        "RG.MIRROR.SCENARIO." + id.toUpperCase()
                                .replace('-', '_'),
                        reason,
                        ScenarioHandlingAssertionResult.ObservedFacts.empty()));
    }

    private static MirrorArtifactRef ref(
            String kind, String id, String fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
