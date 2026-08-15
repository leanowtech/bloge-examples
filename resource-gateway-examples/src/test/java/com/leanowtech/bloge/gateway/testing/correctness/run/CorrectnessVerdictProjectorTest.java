package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorrectnessVerdictProjectorTest {

    private final CorrectnessVerdictProjector projector = new CorrectnessVerdictProjector();

    @Test
    void zeroAssertionsRemainUnprovenEvenWhenExecutionAndCoverageSucceed() {
        CorrectnessVerdict verdict = project(
                TestSuiteRunEvidence.Status.PASSED,
                TestSuiteRunEvidence.CoverageStatus.SATISFIED,
                List.of(caseResult(0, 0, TestSuiteRunEvidence.CaseStatus.PASSED)),
                true, true);

        assertThat(verdict.execution()).isEqualTo(CorrectnessVerdict.ExecutionVerdict.SUCCESS);
        assertThat(verdict.assertions()).isEqualTo(CorrectnessVerdict.AssertionVerdict.NONE);
        assertThat(verdict.coverage()).isEqualTo(CorrectnessVerdict.CoverageVerdict.COMPLETE);
        assertThat(verdict.evidence()).isEqualTo(CorrectnessVerdict.EvidenceVerdict.EXPLORATORY);
        assertThat(verdict.gate()).isEqualTo(CorrectnessVerdict.GateVerdict.BLOCKED);
        assertThat(verdict.reasons()).extracting(CorrectnessVerdict.Reason::code)
                .contains("UNPROVEN");
    }

    @Test
    void acceptsOnlySuccessfulProvenCompleteCurrentAndAttestedEvidence() {
        CorrectnessVerdict verdict = project(
                TestSuiteRunEvidence.Status.PASSED,
                TestSuiteRunEvidence.CoverageStatus.SATISFIED,
                List.of(caseResult(3, 3, TestSuiteRunEvidence.CaseStatus.PASSED)),
                true, true);

        assertThat(verdict.execution()).isEqualTo(CorrectnessVerdict.ExecutionVerdict.SUCCESS);
        assertThat(verdict.assertions()).isEqualTo(CorrectnessVerdict.AssertionVerdict.PASSED);
        assertThat(verdict.coverage()).isEqualTo(CorrectnessVerdict.CoverageVerdict.COMPLETE);
        assertThat(verdict.evidence()).isEqualTo(CorrectnessVerdict.EvidenceVerdict.CURRENT);
        assertThat(verdict.gate()).isEqualTo(CorrectnessVerdict.GateVerdict.ACCEPTED);
        assertThat(verdict.reasons()).isEmpty();
    }

    @Test
    void selectedPassingCasesDoNotMasqueradeAsCompleteSuiteProof() {
        CorrectnessVerdict verdict = project(
                TestSuiteRunEvidence.Status.PARTIAL,
                TestSuiteRunEvidence.CoverageStatus.INCOMPLETE,
                List.of(caseResult(2, 2, TestSuiteRunEvidence.CaseStatus.PASSED)),
                true, true);

        assertThat(verdict.execution()).isEqualTo(CorrectnessVerdict.ExecutionVerdict.PARTIAL);
        assertThat(verdict.assertions()).isEqualTo(CorrectnessVerdict.AssertionVerdict.PASSED);
        assertThat(verdict.coverage()).isEqualTo(CorrectnessVerdict.CoverageVerdict.INCOMPLETE);
        assertThat(verdict.gate()).isEqualTo(CorrectnessVerdict.GateVerdict.BLOCKED);
        assertThat(verdict.reasons()).extracting(CorrectnessVerdict.Reason::code)
                .contains("EXECUTION_PARTIAL", "COVERAGE_INCOMPLETE");
    }

    @Test
    void stalePublicationBindingBlocksOtherwisePassingEvidence() {
        CorrectnessVerdict verdict = project(
                TestSuiteRunEvidence.Status.PASSED,
                TestSuiteRunEvidence.CoverageStatus.SATISFIED,
                List.of(caseResult(1, 1, TestSuiteRunEvidence.CaseStatus.PASSED)),
                false, true);

        assertThat(verdict.evidence()).isEqualTo(CorrectnessVerdict.EvidenceVerdict.STALE);
        assertThat(verdict.gate()).isEqualTo(CorrectnessVerdict.GateVerdict.BLOCKED);
        assertThat(verdict.reasons()).extracting(CorrectnessVerdict.Reason::code)
                .contains("EVIDENCE_STALE");
    }

    @Test
    void unavailableAttestationKeepsCurrentFactsExploratoryAndBlocked() {
        CorrectnessVerdict verdict = project(
                TestSuiteRunEvidence.Status.PASSED,
                TestSuiteRunEvidence.CoverageStatus.SATISFIED,
                List.of(caseResult(1, 1, TestSuiteRunEvidence.CaseStatus.PASSED)),
                true, false);

        assertThat(verdict.evidence()).isEqualTo(CorrectnessVerdict.EvidenceVerdict.EXPLORATORY);
        assertThat(verdict.gate()).isEqualTo(CorrectnessVerdict.GateVerdict.BLOCKED);
        assertThat(verdict.reasons()).extracting(CorrectnessVerdict.Reason::code)
                .contains("EVIDENCE_UNATTESTED");
    }

    @Test
    void assertionFailureDoesNotOverwriteIndependentExecutionAndEvidenceAxes() {
        CorrectnessVerdict verdict = project(
                TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES,
                TestSuiteRunEvidence.CoverageStatus.SATISFIED,
                List.of(caseResult(2, 1, TestSuiteRunEvidence.CaseStatus.FAILED)),
                true, true);

        assertThat(verdict.execution()).isEqualTo(CorrectnessVerdict.ExecutionVerdict.FAILED);
        assertThat(verdict.assertions()).isEqualTo(CorrectnessVerdict.AssertionVerdict.FAILED);
        assertThat(verdict.coverage()).isEqualTo(CorrectnessVerdict.CoverageVerdict.COMPLETE);
        assertThat(verdict.evidence()).isEqualTo(CorrectnessVerdict.EvidenceVerdict.CURRENT);
        assertThat(verdict.gate()).isEqualTo(CorrectnessVerdict.GateVerdict.BLOCKED);
    }

    private CorrectnessVerdict project(
            TestSuiteRunEvidence.Status status,
            TestSuiteRunEvidence.CoverageStatus coverageStatus,
            List<TestSuiteRunEvidence.CaseResult> cases,
            boolean publicationCurrent,
            boolean attested
    ) {
        TestSuiteRunEvidence evidence = mock(TestSuiteRunEvidence.class);
        TestSuiteRunEvidence.CoverageVerdict coverage =
                mock(TestSuiteRunEvidence.CoverageVerdict.class);
        TestSuiteRunAttestation attestation = mock(TestSuiteRunAttestation.class);
        TestSuiteExecutionResponse response = mock(TestSuiteExecutionResponse.class);
        when(evidence.status()).thenReturn(status);
        when(evidence.caseResults()).thenReturn(cases);
        when(evidence.coverage()).thenReturn(coverage);
        when(coverage.status()).thenReturn(coverageStatus);
        when(attestation.terminallyVerifiable()).thenReturn(attested);
        when(response.evidence()).thenReturn(evidence);
        when(response.evidenceFingerprint()).thenReturn(fp('e'));
        when(response.attestation()).thenReturn(attestation);
        return projector.project(response,
                CorrectnessPreflightReport.ProofLevel.SIMULATED_BUSINESS,
                publicationCurrent);
    }

    private TestSuiteRunEvidence.CaseResult caseResult(
            int evaluated,
            int passed,
            TestSuiteRunEvidence.CaseStatus status
    ) {
        TestSuiteRunEvidence.CaseResult result = mock(TestSuiteRunEvidence.CaseResult.class);
        when(result.assertionsEvaluated()).thenReturn(evaluated);
        when(result.assertionsPassed()).thenReturn(passed);
        when(result.status()).thenReturn(status);
        return result;
    }

    private String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
