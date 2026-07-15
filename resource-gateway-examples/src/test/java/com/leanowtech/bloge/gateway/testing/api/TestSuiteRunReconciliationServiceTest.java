package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteRunReconciliationServiceTest {

    @Test
    void expiredRunBecomesFailClosedEvidenceWhileCompletedCaseFactsArePreserved() {
        Instant sweepAt = Instant.parse("2026-07-16T10:00:00Z");
        TestSuiteRunRepository repository = mock(TestSuiteRunRepository.class);
        TestSuiteRunRecord running = runningRecord();
        AbandonedTestSuiteRun abandoned = new AbandonedTestSuiteRun(
                running, 7, "instance-dead", sweepAt.minusSeconds(5));
        when(repository.findAbandoned(sweepAt, 25)).thenReturn(List.of(abandoned));
        when(repository.reconcileAbandoned(eq(abandoned), any(), eq(sweepAt))).thenReturn(true);
        TestSuiteRunReconciliationService service = new TestSuiteRunReconciliationService(
                repository, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(sweepAt, ZoneOffset.UTC));

        TestSuiteRunReconciliationResult result = service.reconcileExpired(25);

        assertThat(result).extracting(TestSuiteRunReconciliationResult::scanned,
                        TestSuiteRunReconciliationResult::reconciled,
                        TestSuiteRunReconciliationResult::raced,
                        TestSuiteRunReconciliationResult::failed)
                .containsExactly(1, 1, 0, 0);
        var terminalCaptor = org.mockito.ArgumentCaptor.forClass(TestSuiteRunRecord.class);
        verify(repository).reconcileAbandoned(eq(abandoned), terminalCaptor.capture(), eq(sweepAt));
        TestSuiteRunRecord terminal = terminalCaptor.getValue();
        assertThat(terminal.evidenceFingerprint()).startsWith("sha256:");
        assertThat(terminal.evidence().status())
                .isEqualTo(TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
        assertThat(terminal.evidence().completedAt()).isEqualTo(sweepAt);
        assertThat(terminal.evidence().caseResults())
                .extracting(TestSuiteRunEvidence.CaseResult::status)
                .containsExactly(TestSuiteRunEvidence.CaseStatus.PASSED,
                        TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE,
                        TestSuiteRunEvidence.CaseStatus.FAILED);
        assertThat(terminal.evidence().caseResults().getFirst().runId()).isEqualTo("child-run-1");
        assertThat(terminal.evidence().caseResults().get(1).diagnosticCode())
                .isEqualTo("ABANDONED_RUN_RECONCILED");
        assertThat(terminal.evidence().coverage().status())
                .isEqualTo(TestSuiteRunEvidence.CoverageStatus.INCOMPLETE);
        assertThat(terminal.evidence().coverage().completedCases()).isEqualTo(2);
        assertThat(terminal.evidence().promotion().status())
                .isEqualTo(TestSuiteRunEvidence.PromotionStatus.BLOCKED);
        assertThat(terminal.evidence().promotion().reasons())
                .contains("ABANDONED_RUN_RECONCILED", "EVIDENCE_INCOMPLETE");
        assertThat(terminal.evidence().diagnostics()).contains("ABANDONED_RUN_RECONCILED");
        assertThat(terminal.evidence().metadata())
                .containsEntry("reconciliationMode", "LEASE_EXPIRY_TERMINALIZATION")
                .containsKey("expiredLeaseOwnerFingerprint")
                .containsEntry("expiredCheckpointVersion", 7L);
        assertThat(terminal.evidence().metadata().get("expiredLeaseOwnerFingerprint").toString())
                .startsWith("sha256:")
                .doesNotContain("instance-dead");
    }

    @Test
    void reconciliationReportsCasRacesAndCandidateFailuresWithoutStoppingTheBatch() {
        Instant sweepAt = Instant.parse("2026-07-16T11:00:00Z");
        TestSuiteRunRepository repository = mock(TestSuiteRunRepository.class);
        AbandonedTestSuiteRun raced = new AbandonedTestSuiteRun(
                runningRecord(), 1, "instance-a", sweepAt.minusSeconds(2));
        TestSuiteRunRecord secondRecord = withId(runningRecord(), "suite-run-2", "request-2");
        AbandonedTestSuiteRun failed = new AbandonedTestSuiteRun(
                secondRecord, 2, "instance-b", sweepAt.minusSeconds(1));
        when(repository.findAbandoned(sweepAt, 10)).thenReturn(List.of(raced, failed));
        when(repository.reconcileAbandoned(eq(raced), any(), eq(sweepAt))).thenReturn(false);
        when(repository.reconcileAbandoned(eq(failed), any(), eq(sweepAt)))
                .thenThrow(new IllegalStateException("store unavailable"));
        TestSuiteRunReconciliationService service = new TestSuiteRunReconciliationService(
                repository, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(sweepAt, ZoneOffset.UTC));

        TestSuiteRunReconciliationResult result = service.reconcileExpired(10);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.reconciled()).isZero();
        assertThat(result.raced()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.reconciledSuiteRunIds()).isEmpty();
    }

    private static TestSuiteRunRecord runningRecord() {
        Instant started = Instant.parse("2026-07-16T09:55:00Z");
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "suite-a", 3, "sha256:" + "a".repeat(64));
        TestSuite.Target target = new TestSuite.Target(
                "GRAPH", "graph-a", "sha256:" + "b".repeat(64));
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "fixture-a", 1, "sha256:" + "c".repeat(64));
        List<TestSuiteRunEvidence.CaseResult> cases = List.of(
                new TestSuiteRunEvidence.CaseResult("golden", TestSuite.CaseType.GOLDEN, fixture,
                        TestSuiteRunEvidence.CaseStatus.PASSED, "child-run-1",
                        TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                        2, 2, "", ""),
                new TestSuiteRunEvidence.CaseResult("boundary", TestSuite.CaseType.BOUNDARY, fixture,
                        TestSuiteRunEvidence.CaseStatus.PENDING, "", null, null, 0, 0, "", ""),
                new TestSuiteRunEvidence.CaseResult("negative", TestSuite.CaseType.NEGATIVE, fixture,
                        TestSuiteRunEvidence.CaseStatus.FAILED, "child-run-3",
                        TestRunEvidence.Status.ASSERTION_FAILED,
                        TestRunEvidence.EvidenceClass.CERTIFIABLE, 1, 0,
                        "ASSERTION_FAILED", "expected failure mismatch"));
        TestSuiteRunEvidence evidence = new TestSuiteRunEvidence("", "suite-run-1", "request-1",
                TestSuiteRunEvidence.Status.RUNNING, "TEST_SUITE_EXECUTION", suiteRef, target,
                started, null, cases, TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(), List.of(),
                Map.of("tenantId", "tenant-a"));
        return new TestSuiteRunRecord("suite-run-1", "request-1", "sha256:" + "d".repeat(64),
                "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL", "",
                evidence, started, started.plusSeconds(3600));
    }

    private static TestSuiteRunRecord withId(TestSuiteRunRecord source, String runId, String requestId) {
        TestSuiteRunEvidence evidence = source.evidence();
        TestSuiteRunEvidence renamed = new TestSuiteRunEvidence("", runId, requestId,
                evidence.status(), evidence.executionPurpose(), evidence.suiteRef(), evidence.target(),
                evidence.startedAt(), evidence.completedAt(), evidence.caseResults(), evidence.coverage(),
                evidence.promotion(), evidence.diagnostics(), evidence.metadata());
        return new TestSuiteRunRecord(runId, requestId, source.requestFingerprint(), source.tenantId(),
                source.organizationId(), source.projectId(), source.environmentId(), source.actorId(),
                source.classification(), "", renamed, source.createdAt(), source.expiresAt());
    }
}
