package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteRunReconciliationServiceTest {

    private ObjectMapper objectMapper;
    private TestSuiteRunAttestationService attestations;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        attestations = new TestSuiteRunAttestationService(
                objectMapper, new InMemoryVisualEvidenceSigner());
    }

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
                repository, objectMapper, attestations,
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
        assertThat(terminal.attestation().terminallyVerifiable()).isTrue();
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
                .containsKey("expiredLeaseOwnerFingerprint");
        assertThat((Number) terminal.evidence().metadata().get("expiredCheckpointVersion"))
                .extracting(Number::longValue).isEqualTo(7L);
        assertThat(terminal.evidence().metadata().get("expiredLeaseOwnerFingerprint").toString())
                .startsWith("sha256:")
                .doesNotContain("instance-dead");
    }

    @Test
    void expiredSchemaAdmissionCheckpointPreservesV3FactsWithoutBusinessChildren() {
        Instant sweepAt = Instant.parse("2026-07-16T10:30:00Z");
        TestSuiteRunRepository repository = mock(TestSuiteRunRepository.class);
        TestSuiteRunRecord running = runningAdmissionRecord();
        AbandonedTestSuiteRun abandoned = new AbandonedTestSuiteRun(
                running, 11, "instance-admission-dead", sweepAt.minusSeconds(3));
        when(repository.findAbandoned(sweepAt, 10)).thenReturn(List.of(abandoned));
        when(repository.reconcileAbandoned(eq(abandoned), any(), eq(sweepAt))).thenReturn(true);
        TestSuiteRunReconciliationService service = new TestSuiteRunReconciliationService(
                repository, objectMapper, attestations,
                Clock.fixed(sweepAt, ZoneOffset.UTC));

        TestSuiteRunReconciliationResult result = service.reconcileExpired(10);

        assertThat(result.reconciled()).isOne();
        var terminalCaptor = org.mockito.ArgumentCaptor.forClass(TestSuiteRunRecord.class);
        verify(repository).reconcileAbandoned(eq(abandoned), terminalCaptor.capture(), eq(sweepAt));
        TestSuiteRunRecord terminal = terminalCaptor.getValue();
        assertThat(terminal.evidence()).isInstanceOfSatisfying(TestSuiteRunEvidenceV3.class,
                evidence -> {
                    assertThat(evidence.status())
                            .isEqualTo(TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
                    assertThat(evidence.completedAt()).isEqualTo(sweepAt);
                    assertThat(evidence.caseResults())
                            .extracting(TestSuiteRunEvidence.CaseResult::status)
                            .containsExactly(TestSuiteRunEvidence.CaseStatus.PASSED,
                                    TestSuiteRunEvidence.CaseStatus.FAILED,
                                    TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE);
                    assertThat(evidence.caseResults())
                            .allSatisfy(caseResult -> assertThat(caseResult.runId()).isBlank());
                    assertThat(evidence.admissionResults())
                            .extracting(TestSuiteRunEvidenceV3.AdmissionCaseResult::status)
                            .containsExactly(TestSuiteRunEvidenceV3.AdmissionCaseStatus.MATCHED,
                                    TestSuiteRunEvidenceV3.AdmissionCaseStatus.EXPECTATION_MISMATCH,
                                    TestSuiteRunEvidenceV3.AdmissionCaseStatus.EVIDENCE_INCOMPLETE);
                    assertThat(evidence.admissionResults().get(1).observedValidationCodes())
                            .containsExactly("visual.context.required");
                    assertThat(evidence.admissionResults().get(2).diagnosticCode())
                            .isEqualTo(TestSuiteRunReconciliationService.ABANDONED_RUN_RECONCILED);
                    assertThat(evidence.admissionCoverage().status())
                            .isEqualTo(TestSuiteRunEvidenceV3.AdmissionCoverageStatus.INCOMPLETE);
                    assertThat(evidence.admissionCoverage().evaluatedCases()).isEqualTo(2);
                    assertThat(evidence.admissionCoverage().matchedCases()).isOne();
                    assertThat(evidence.admissionCoverage().expectationMismatchCaseIds())
                            .containsExactly("unexpected-rejection");
                    assertThat(evidence.admissionCoverage().incompleteCaseIds())
                            .containsExactly("required-name");
                    assertThat(evidence.coverage())
                            .isEqualTo(TestSuiteRunEvidence.CoverageVerdict.notEvaluated());
                    assertThat(evidence.promotion().status())
                            .isEqualTo(TestSuiteRunEvidence.PromotionStatus.BLOCKED);
                    assertThat(evidence.promotion().reasons()).contains(
                            TestSuiteRunEvidenceV3.SCHEMA_ADMISSION_ONLY,
                            TestSuiteRunEvidenceV3.BUSINESS_EXECUTION_NOT_PERFORMED,
                            TestSuiteRunReconciliationService.ABANDONED_RUN_RECONCILED,
                            "EVIDENCE_INCOMPLETE");
                    assertThat(evidence.boundaryPlanFingerprint())
                            .isEqualTo("sha256:" + "4".repeat(64));
                    assertThat(evidence.inputSchemaFingerprint())
                            .isEqualTo("sha256:" + "5".repeat(64));
                    assertThat(evidence.metadata())
                            .containsEntry("businessTargetInvoked", false)
                            .containsEntry("reconciliationMode", "LEASE_EXPIRY_TERMINALIZATION");
                    assertThat((Number) evidence.metadata().get("expiredCheckpointVersion"))
                            .extracting(Number::longValue).isEqualTo(11L);
                });
        assertThat(terminal.evidenceFingerprint()).startsWith("sha256:");
        assertThat(terminal.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V3);
        assertThat(terminal.attestation().terminallyVerifiable()).isTrue();
        assertThat(terminal.attestation().childEvidenceRefs()).isEmpty();
        assertThat(attestations.verify(terminal.evidence(), terminal.attestation()))
                .isEqualTo(TestSuiteRunAttestationService.Verification.VERIFIED);
    }

    @Test
    void expiredPropertyCheckpointPreservesCompletedRootAndTerminalizesPendingShrink() {
        Instant sweepAt = Instant.parse("2026-07-16T10:40:00Z");
        TestSuiteRunRepository repository = mock(TestSuiteRunRepository.class);
        TestSuiteRunRecord running = runningPropertyRecord();
        AbandonedTestSuiteRun abandoned = new AbandonedTestSuiteRun(
                running, 13, "instance-property-dead", sweepAt.minusSeconds(2));
        when(repository.findAbandoned(sweepAt, 10)).thenReturn(List.of(abandoned));
        when(repository.reconcileAbandoned(eq(abandoned), any(), eq(sweepAt))).thenReturn(true);
        TestSuiteRunReconciliationService service = new TestSuiteRunReconciliationService(
                repository, objectMapper, attestations,
                Clock.fixed(sweepAt, ZoneOffset.UTC));

        TestSuiteRunReconciliationResult result = service.reconcileExpired(10);

        assertThat(result.reconciled()).isOne();
        var terminalCaptor = org.mockito.ArgumentCaptor.forClass(TestSuiteRunRecord.class);
        verify(repository).reconcileAbandoned(eq(abandoned), terminalCaptor.capture(), eq(sweepAt));
        TestSuiteRunRecord terminal = terminalCaptor.getValue();
        assertThat(terminal.evidence()).isInstanceOfSatisfying(TestSuiteRunEvidenceV4.class,
                evidence -> {
                    assertThat(evidence.status())
                            .isEqualTo(TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
                    assertThat(evidence.propertyTrialResults().getFirst().rootResult())
                            .isEqualTo(((TestSuiteRunEvidenceV4) running.evidence())
                                    .propertyTrialResults().getFirst().rootResult());
                    assertThat(evidence.propertyTrialResults().getFirst().shrinkResults().getFirst())
                            .satisfies(shrink -> {
                                assertThat(shrink.status()).isEqualTo(
                                        TestSuiteRunEvidenceV4.PropertyCaseStatus.EVIDENCE_INCOMPLETE);
                                assertThat(shrink.diagnosticCode()).isEqualTo(
                                        TestSuiteRunReconciliationService.ABANDONED_RUN_RECONCILED);
                            });
                    assertThat(evidence.propertyCoverage().status()).isEqualTo(
                            TestSuiteRunEvidenceV4.PropertyCoverageStatus.INCOMPLETE);
                    assertThat(evidence.propertyCoverage().incompleteCaseIds())
                            .containsExactly("property-001-shrink-001");
                    assertThat(evidence.propertyCoverage().globallyMinimal()).isFalse();
                });
        assertThat(terminal.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V4);
        assertThat(terminal.attestation().childEvidenceRefs())
                .extracting(TestSuiteRunAttestation.ChildEvidenceRef::caseId)
                .containsExactly("property-001");
        assertThat(attestations.verify(terminal.evidence(), terminal.attestation()))
                .isEqualTo(TestSuiteRunAttestationService.Verification.VERIFIED);
    }

    @Test
    void expiredMutationCheckpointPreservesKillsAndTerminalizesEveryPendingCoordinate() {
        Instant sweepAt = Instant.parse("2026-07-16T10:42:00Z");
        TestSuiteRunRepository repository = mock(TestSuiteRunRepository.class);
        TestSuiteRunRecord running = runningMutationRecord();
        AbandonedTestSuiteRun abandoned = new AbandonedTestSuiteRun(
                running, 14, "instance-mutation-dead", sweepAt.minusSeconds(2));
        when(repository.findAbandoned(sweepAt, 10)).thenReturn(List.of(abandoned));
        when(repository.reconcileAbandoned(eq(abandoned), any(), eq(sweepAt))).thenReturn(true);
        TestSuiteRunReconciliationService service = new TestSuiteRunReconciliationService(
                repository, objectMapper, attestations,
                Clock.fixed(sweepAt, ZoneOffset.UTC));

        TestSuiteRunReconciliationResult result = service.reconcileExpired(10);

        assertThat(result.reconciled()).isOne();
        var terminalCaptor = org.mockito.ArgumentCaptor.forClass(TestSuiteRunRecord.class);
        verify(repository).reconcileAbandoned(eq(abandoned), terminalCaptor.capture(), eq(sweepAt));
        TestSuiteRunRecord terminal = terminalCaptor.getValue();
        assertThat(terminal.evidence()).isInstanceOfSatisfying(TestSuiteRunEvidenceV5.class,
                evidence -> {
                    assertThat(evidence.status())
                            .isEqualTo(TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
                    assertThat(evidence.completedAt()).isEqualTo(sweepAt);
                    assertThat(evidence.caseResults())
                            .extracting(TestSuiteRunEvidence.CaseResult::status)
                            .containsExactly(TestSuiteRunEvidence.CaseStatus.PASSED,
                                    TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE);
                    assertThat(evidence.caseResults().getFirst())
                            .isEqualTo(((TestSuiteRunEvidenceV5) running.evidence())
                                    .caseResults().getFirst());
                    assertThat(evidence.baselineStatus())
                            .isEqualTo(TestSuiteRunEvidenceV5.BaselineStatus.EVIDENCE_INCOMPLETE);
                    assertThat(evidence.mutantResults())
                            .extracting(TestSuiteRunEvidenceV5.MutantResult::status)
                            .containsExactly(TestSuiteRunEvidenceV5.MutantStatus.KILLED,
                                    TestSuiteRunEvidenceV5.MutantStatus.NOT_SCHEDULED);
                    assertThat(evidence.mutantResults().getFirst().caseResults().getFirst())
                            .isEqualTo(((TestSuiteRunEvidenceV5) running.evidence())
                                    .mutantResults().getFirst().caseResults().getFirst());
                    assertThat(evidence.mutantResults().getFirst().caseResults().get(1))
                            .satisfies(caseResult -> {
                                assertThat(caseResult.status()).isEqualTo(
                                        TestSuiteRunEvidenceV5.MutantCaseStatus.NOT_SCHEDULED);
                                assertThat(caseResult.diagnosticCode()).isEqualTo(
                                        TestSuiteRunReconciliationService.ABANDONED_RUN_RECONCILED);
                            });
                    assertThat(evidence.mutationScore())
                            .extracting(TestSuiteRunEvidenceV5.MutationScoreVerdict::status,
                                    TestSuiteRunEvidenceV5.MutationScoreVerdict::killedMutants,
                                    TestSuiteRunEvidenceV5.MutationScoreVerdict::unclassifiedMutants,
                                    TestSuiteRunEvidenceV5.MutationScoreVerdict::scoreBasisPoints)
                            .containsExactly(TestSuiteRunEvidenceV5.MutationScoreStatus.INCOMPLETE,
                                    1, 1, 0);
                    assertThat(evidence.promotion().status())
                            .isEqualTo(TestSuiteRunEvidence.PromotionStatus.BLOCKED);
                    assertThat(evidence.diagnostics())
                            .contains(TestSuiteRunReconciliationService.ABANDONED_RUN_RECONCILED);
                    assertThat(evidence.metadata())
                            .containsEntry("reconciliationMode", "LEASE_EXPIRY_TERMINALIZATION");
                    assertThat((Number) evidence.metadata().get("expiredCheckpointVersion"))
                            .extracting(Number::longValue).isEqualTo(14L);
                });
        assertThat(terminal.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V5);
        assertThat(terminal.attestation().terminallyVerifiable()).isTrue();
        assertThat(terminal.attestation().childEvidenceRefs())
                .extracting(TestSuiteRunAttestation.ChildEvidenceRef::caseId)
                .containsExactly("baseline/golden", "mutant-001/golden");
        assertThat(attestations.verify(terminal.evidence(), terminal.attestation()))
                .isEqualTo(TestSuiteRunAttestationService.Verification.VERIFIED);
    }

    @Test
    void mutationCheckpointWithValidSignatureButWrongClosureCoordinatesIsRejected() {
        Instant sweepAt = Instant.parse("2026-07-16T10:43:00Z");
        TestSuiteRunRepository repository = mock(TestSuiteRunRepository.class);
        TestSuiteRunRecord valid = runningMutationRecord();
        List<TestSuiteRunAttestation.ChildEvidenceRef> mislabeledChildren = List.of(
                new TestSuiteRunAttestation.ChildEvidenceRef(
                        "golden", "baseline-golden", fingerprint('b')),
                new TestSuiteRunAttestation.ChildEvidenceRef(
                        "golden", "mutant-1-golden", fingerprint('c')));
        TestSuiteRunAttestation mislabeled = attestations.seal(
                valid.evidence(), valid.requestFingerprint(), mislabeledChildren,
                TestSuiteRunAttestation.Scope.CHECKPOINT).attestation();
        TestSuiteRunRecord invalid = new TestSuiteRunRecord(
                valid.suiteRunId(), valid.clientRequestId(), valid.requestFingerprint(),
                valid.tenantId(), valid.organizationId(), valid.projectId(), valid.environmentId(),
                valid.actorId(), valid.classification(), "", valid.evidence(), mislabeled,
                valid.createdAt(), valid.expiresAt());
        AbandonedTestSuiteRun abandoned = new AbandonedTestSuiteRun(
                invalid, 15, "instance-mutation-dead", sweepAt.minusSeconds(2));
        when(repository.findAbandoned(sweepAt, 10)).thenReturn(List.of(abandoned));
        TestSuiteRunReconciliationService service = new TestSuiteRunReconciliationService(
                repository, objectMapper, attestations,
                Clock.fixed(sweepAt, ZoneOffset.UTC));

        TestSuiteRunReconciliationResult result = service.reconcileExpired(10);

        assertThat(result.failed()).isOne();
        assertThat(result.reconciled()).isZero();
        verify(repository, never()).reconcileAbandoned(any(), any(), any());
    }

    @Test
    void schemaAdmissionCheckpointIsNotReconciledWhenTrustAuthorityIsUnavailable() {
        Instant sweepAt = Instant.parse("2026-07-16T10:45:00Z");
        TestSuiteRunRepository repository = mock(TestSuiteRunRepository.class);
        AbandonedTestSuiteRun abandoned = new AbandonedTestSuiteRun(
                runningAdmissionRecord(), 12, "instance-dead", sweepAt.minusSeconds(1));
        when(repository.findAbandoned(sweepAt, 10)).thenReturn(List.of(abandoned));
        TestSuiteRunReconciliationService service = new TestSuiteRunReconciliationService(
                repository, objectMapper,
                new TestSuiteRunAttestationService(objectMapper, VisualEvidenceSigner.unavailable()),
                Clock.fixed(sweepAt, ZoneOffset.UTC));

        TestSuiteRunReconciliationResult result = service.reconcileExpired(10);

        assertThat(result.failed()).isOne();
        assertThat(result.reconciled()).isZero();
        verify(repository, never()).reconcileAbandoned(any(), any(), any());
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
                repository, objectMapper, attestations,
                Clock.fixed(sweepAt, ZoneOffset.UTC));

        TestSuiteRunReconciliationResult result = service.reconcileExpired(10);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.reconciled()).isZero();
        assertThat(result.raced()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.reconciledSuiteRunIds()).isEmpty();
    }

    @Test
    void tamperedCheckpointIsRejectedWithoutWritingDerivedEvidence() {
        Instant sweepAt = Instant.parse("2026-07-16T12:00:00Z");
        TestSuiteRunRepository repository = mock(TestSuiteRunRepository.class);
        TestSuiteRunRecord signed = runningRecord();
        TestSuiteRunEvidence altered = new TestSuiteRunEvidence("", signed.suiteRunId(),
                signed.clientRequestId(), signed.evidence().status(), signed.evidence().executionPurpose(),
                signed.evidence().suiteRef(), signed.evidence().target(), signed.evidence().startedAt(),
                null, signed.evidence().caseResults(), signed.evidence().coverage(),
                signed.evidence().promotion(), List.of("tampered"), signed.evidence().metadata());
        TestSuiteRunRecord tampered = new TestSuiteRunRecord(signed.suiteRunId(),
                signed.clientRequestId(), signed.requestFingerprint(), signed.tenantId(),
                signed.organizationId(), signed.projectId(), signed.environmentId(), signed.actorId(),
                signed.classification(), "", altered, signed.attestation(), signed.createdAt(),
                signed.expiresAt());
        AbandonedTestSuiteRun abandoned = new AbandonedTestSuiteRun(
                tampered, 9, "instance-dead", sweepAt.minusSeconds(1));
        when(repository.findAbandoned(sweepAt, 10)).thenReturn(List.of(abandoned));
        TestSuiteRunReconciliationService service = new TestSuiteRunReconciliationService(
                repository, objectMapper, attestations, Clock.fixed(sweepAt, ZoneOffset.UTC));

        TestSuiteRunReconciliationResult result = service.reconcileExpired(10);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.reconciled()).isZero();
        verify(repository, never()).reconcileAbandoned(any(), any(), any());
    }

    private TestSuiteRunRecord runningRecord() {
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
                runMetadata());
        String requestFingerprint = "sha256:" + "d".repeat(64);
        List<TestSuiteRunAttestation.ChildEvidenceRef> children = List.of(
                new TestSuiteRunAttestation.ChildEvidenceRef(
                        "golden", "child-run-1", "sha256:" + "1".repeat(64)),
                new TestSuiteRunAttestation.ChildEvidenceRef(
                        "negative", "child-run-3", "sha256:" + "3".repeat(64)));
        TestSuiteRunAttestation attestation = attestations.seal(evidence, requestFingerprint,
                children, TestSuiteRunAttestation.Scope.CHECKPOINT).attestation();
        return new TestSuiteRunRecord("suite-run-1", "request-1", requestFingerprint,
                "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL", "",
                evidence, attestation, started, started.plusSeconds(7200));
    }

    private TestSuiteRunRecord runningAdmissionRecord() {
        Instant started = Instant.parse("2026-07-16T10:20:00Z");
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "schema-suite", 1, "sha256:" + "1".repeat(64));
        TestSuite.Target target = new TestSuite.Target(
                "GRAPH", "graph-schema", "sha256:" + "2".repeat(64));
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "schema-fixture", 1, "sha256:" + "3".repeat(64));
        List<TestSuiteRunEvidence.CaseResult> cases = List.of(
                new TestSuiteRunEvidence.CaseResult(
                        "baseline", TestSuite.CaseType.BOUNDARY, fixture,
                        TestSuiteRunEvidence.CaseStatus.PASSED, "", null, null,
                        0, 0, "", ""),
                new TestSuiteRunEvidence.CaseResult(
                        "unexpected-rejection", TestSuite.CaseType.BOUNDARY, fixture,
                        TestSuiteRunEvidence.CaseStatus.FAILED, "", null, null,
                        0, 0, TestSchemaAdmissionEvaluator.EXPECTATION_MISMATCH, ""),
                new TestSuiteRunEvidence.CaseResult(
                        "required-name", TestSuite.CaseType.BOUNDARY, fixture,
                        TestSuiteRunEvidence.CaseStatus.PENDING, "", null, null,
                        0, 0, "", ""));
        List<TestSuiteRunEvidenceV3.AdmissionCaseResult> admissionResults = List.of(
                new TestSuiteRunEvidenceV3.AdmissionCaseResult(
                        "baseline", TestSuiteRunEvidenceV3.AdmissionCaseStatus.MATCHED,
                        TestSuiteV3.ExpectedOutcome.ACCEPTED,
                        TestSuiteV3.ExpectedOutcome.ACCEPTED, List.of(), List.of(), ""),
                new TestSuiteRunEvidenceV3.AdmissionCaseResult(
                        "unexpected-rejection",
                        TestSuiteRunEvidenceV3.AdmissionCaseStatus.EXPECTATION_MISMATCH,
                        TestSuiteV3.ExpectedOutcome.ACCEPTED,
                        TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED, List.of(),
                        List.of("visual.context.required"),
                        TestSchemaAdmissionEvaluator.EXPECTATION_MISMATCH),
                new TestSuiteRunEvidenceV3.AdmissionCaseResult(
                        "required-name", TestSuiteRunEvidenceV3.AdmissionCaseStatus.PENDING,
                        TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED, null,
                        List.of("visual.context.required"), List.of(), ""));
        TestSuiteRunEvidenceV3.AdmissionCoverageVerdict admissionCoverage =
                new TestSuiteRunEvidenceV3.AdmissionCoverageVerdict(
                        TestSuiteRunEvidenceV3.AdmissionCoverageStatus.INCOMPLETE,
                        3, 2, 1, List.of("unexpected-rejection"), List.of(),
                        List.of("required-name"), false);
        TestSuiteRunEvidence.PromotionVerdict promotion =
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                        List.of(TestSuiteRunEvidenceV3.SCHEMA_ADMISSION_ONLY,
                                TestSuiteRunEvidenceV3.BUSINESS_EXECUTION_NOT_PERFORMED),
                        false, 0, 0, false, false, false);
        TestSuiteRunEvidenceV3 evidence = new TestSuiteRunEvidenceV3(
                "", "suite-run-admission", "request-admission",
                TestSuiteRunEvidence.Status.RUNNING,
                TestSuiteRunEvidenceV3.EXECUTION_PURPOSE, suiteRef, target,
                started, null, cases, TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                promotion, TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                "sha256:" + "4".repeat(64), "sha256:" + "5".repeat(64),
                "boundary-cases-v1", TestSuiteRunEvidenceV3.VERIFICATION_MODE,
                TestBoundaryCasePlan.Status.GENERATED, 0, false,
                admissionResults, admissionCoverage, List.of(),
                runMetadata(Map.of("businessTargetInvoked", false, "childRunCount", 0)));
        String requestFingerprint = "sha256:" + "6".repeat(64);
        TestSuiteRunAttestation attestation = attestations.seal(evidence, requestFingerprint,
                List.of(), TestSuiteRunAttestation.Scope.CHECKPOINT).attestation();
        return new TestSuiteRunRecord(
                evidence.suiteRunId(), evidence.clientRequestId(), requestFingerprint,
                "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL", "",
                evidence, attestation, started, started.plusSeconds(3600));
    }

    private TestSuiteRunRecord runningPropertyRecord() {
        Instant started = Instant.parse("2026-07-16T10:35:00Z");
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "property-suite", 1, "sha256:" + "7".repeat(64));
        TestSuite.Target target = new TestSuite.Target(
                "GRAPH", "graph-property", "sha256:" + "8".repeat(64));
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "property-fixture", 1, "sha256:" + "9".repeat(64));
        List<TestSuiteRunEvidence.CaseResult> cases = List.of(
                new TestSuiteRunEvidence.CaseResult(
                        "property-001", TestSuite.CaseType.PROPERTY, fixture,
                        TestSuiteRunEvidence.CaseStatus.PASSED, "property-child-1",
                        TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                        1, 1, "", ""),
                new TestSuiteRunEvidence.CaseResult(
                        "property-001-shrink-001", TestSuite.CaseType.PROPERTY, fixture,
                        TestSuiteRunEvidence.CaseStatus.PENDING, "", null, null,
                        0, 0, "", ""));
        TestSuiteRunEvidenceV4.PropertyCaseResult root =
                new TestSuiteRunEvidenceV4.PropertyCaseResult(
                        "property-001", TestSuiteRunEvidenceV4.PropertyCaseRole.ROOT,
                        "", 0, "sha256:" + "a".repeat(64), 2,
                        TestSuiteRunEvidenceV4.PropertyCaseStatus.SATISFIED,
                        "property-child-1", TestRunEvidence.Status.PASSED, 1, 1, "");
        TestSuiteRunEvidenceV4.PropertyCaseResult shrink =
                new TestSuiteRunEvidenceV4.PropertyCaseResult(
                        "property-001-shrink-001",
                        TestSuiteRunEvidenceV4.PropertyCaseRole.SHRINK,
                        "property-001", 1, "sha256:" + "b".repeat(64), 1,
                        TestSuiteRunEvidenceV4.PropertyCaseStatus.PENDING,
                        "", null, 0, 0, "");
        List<TestSuiteRunEvidenceV4.PropertyTrialResult> trials = List.of(
                TestSuiteRunEvidenceV4.trialResult("property-001", root, List.of(shrink)));
        TestSuiteRunEvidenceV4 evidence = new TestSuiteRunEvidenceV4(
                "", "suite-run-property", "request-property",
                TestSuiteRunEvidence.Status.RUNNING,
                TestSuiteRunEvidenceV4.EXECUTION_PURPOSE, suiteRef, target,
                started, null, cases, TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(),
                TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION,
                TestSuiteV4.Quantification.BOUNDED_SAMPLED, false,
                "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64),
                new TestSuiteV4.PropertyGenerationPolicy(
                        "property-cases-v1", 42, 1, 1, 2, 32, 8, 32,
                        "DRAFT_2020_12_SHARED_VALIDATOR"),
                TestSuiteV4.SourcePlanStatus.GENERATED, false, List.of(), trials,
                TestSuiteRunEvidenceV4.coverage(trials), List.of(), runMetadata());
        String requestFingerprint = "sha256:" + "e".repeat(64);
        List<TestSuiteRunAttestation.ChildEvidenceRef> children = List.of(
                new TestSuiteRunAttestation.ChildEvidenceRef(
                        "property-001", "property-child-1", "sha256:" + "f".repeat(64)));
        TestSuiteRunAttestation attestation = attestations.seal(evidence, requestFingerprint,
                children, TestSuiteRunAttestation.Scope.CHECKPOINT).attestation();
        return new TestSuiteRunRecord(
                evidence.suiteRunId(), evidence.clientRequestId(), requestFingerprint,
                "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL", "",
                evidence, attestation, started, started.plusSeconds(3600));
    }

    private TestSuiteRunRecord runningMutationRecord() {
        Instant started = Instant.parse("2026-07-16T10:41:00Z");
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "mutation-suite", 2, fingerprint('1'));
        TestSuite.Target target = new TestSuite.Target("GRAPH", "graph-mutation", fingerprint('2'));
        TestSuite.FixtureBundleRef goldenFixture = new TestSuite.FixtureBundleRef(
                "fixture-golden", 1, fingerprint('3'));
        TestSuite.FixtureBundleRef negativeFixture = new TestSuite.FixtureBundleRef(
                "fixture-negative", 1, fingerprint('4'));
        List<TestSuiteRunEvidence.CaseResult> baseline = List.of(
                new TestSuiteRunEvidence.CaseResult(
                        "golden", TestSuite.CaseType.GOLDEN, goldenFixture,
                        TestSuiteRunEvidence.CaseStatus.PASSED, "baseline-golden",
                        TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                        1, 1, "", ""),
                new TestSuiteRunEvidence.CaseResult(
                        "negative", TestSuite.CaseType.NEGATIVE, negativeFixture,
                        TestSuiteRunEvidence.CaseStatus.PENDING, "", null, null,
                        0, 0, "", ""));
        TestSuiteV5.MutantRef first = mutationRef(1);
        TestSuiteV5.MutantRef second = mutationRef(2);
        List<TestSuiteRunEvidenceV5.MutantResult> mutants = List.of(
                new TestSuiteRunEvidenceV5.MutantResult(first,
                        TestSuiteRunEvidenceV5.MutantStatus.RUNNING,
                        List.of(new TestSuiteRunEvidenceV5.MutantCaseResult(
                                        "golden", goldenFixture, first.mutantTargetFingerprint(),
                                        TestSuiteRunEvidenceV5.MutantCaseStatus.ASSERTION_KILLED,
                                        "mutant-1-golden", fingerprint('5'),
                                        TestRunEvidence.Status.ASSERTION_FAILED,
                                        TestRunEvidence.EvidenceClass.CERTIFIABLE,
                                        1, 0, "ASSERTION_FAILED"),
                                pendingMutationCase("negative", negativeFixture, first)),
                        List.of("golden")),
                new TestSuiteRunEvidenceV5.MutantResult(second,
                        TestSuiteRunEvidenceV5.MutantStatus.PENDING,
                        List.of(pendingMutationCase("golden", goldenFixture, second),
                                pendingMutationCase("negative", negativeFixture, second)),
                        List.of()));
        TestSuiteV5.MutationScorePolicy scorePolicy = new TestSuiteV5.MutationScorePolicy(
                5_000, 1, false, false);
        TestSuiteRunEvidenceV5 evidence = new TestSuiteRunEvidenceV5(
                "", "suite-run-mutation", "request-mutation",
                TestSuiteRunEvidence.Status.RUNNING,
                TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, suiteRef, target,
                started, null, baseline, TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(),
                TestSuiteV5.EvaluationMode.PURE_DSL_MUTATION, TestSuiteV5.SOURCE_FORMAT,
                fingerprint('6'), fingerprint('7'), fingerprint('8'),
                new TestSuiteV5.MutationPolicy(TestSuiteV5.PLANNER_VERSION, 2,
                        TestSuiteV5.SOURCE_FORMAT, TestSuiteV5.VERIFICATION_MODE, false, false),
                TestSuiteV5.SourcePlanStatus.GENERATED, false, List.of(),
                new TestSuiteV5.OracleSuiteRef(
                        "oracle", 1, fingerprint('9'), TestSuite.SCHEMA_VERSION),
                TestSuiteRunEvidenceV5.BaselineStatus.RUNNING, mutants,
                TestSuiteRunEvidenceV5.score(
                        TestSuiteRunEvidenceV5.BaselineStatus.RUNNING, mutants, scorePolicy),
                List.of(), runMetadata(Map.of("strategy", "COLLECT_ALL")));
        String requestFingerprint = fingerprint('a');
        List<TestSuiteRunAttestation.ChildEvidenceRef> children = List.of(
                new TestSuiteRunAttestation.ChildEvidenceRef(
                        "baseline/golden", "baseline-golden", fingerprint('b')),
                new TestSuiteRunAttestation.ChildEvidenceRef(
                        "mutant-001/golden", "mutant-1-golden", fingerprint('c')));
        TestSuiteRunAttestation attestation = attestations.seal(evidence, requestFingerprint,
                children, TestSuiteRunAttestation.Scope.CHECKPOINT).attestation();
        return new TestSuiteRunRecord(
                evidence.suiteRunId(), evidence.clientRequestId(), requestFingerprint,
                "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL", "",
                evidence, attestation, started, started.plusSeconds(3600));
    }

    private static TestSuiteRunEvidenceV5.MutantCaseResult pendingMutationCase(
            String caseId,
            TestSuite.FixtureBundleRef fixture,
            TestSuiteV5.MutantRef mutant) {
        return new TestSuiteRunEvidenceV5.MutantCaseResult(
                caseId, fixture, mutant.mutantTargetFingerprint(),
                TestSuiteRunEvidenceV5.MutantCaseStatus.PENDING,
                "", "", null, null, 0, 0, "");
    }

    private static TestSuiteV5.MutantRef mutationRef(int index) {
        return new TestSuiteV5.MutantRef(
                "mutant-%03d".formatted(index),
                TestSuiteV5.MutationKind.DECISION_CONDITION_NEGATED,
                "/members/%d/predicate".formatted(index), index, 1,
                indexedFingerprint(index), indexedFingerprint(100 + index),
                indexedFingerprint(200 + index),
                TestSuiteV5.EquivalenceClassification.UNKNOWN);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String indexedFingerprint(int value) {
        return "sha256:" + "%064x".formatted(value);
    }

    private static Map<String, Object> runMetadata() {
        return Map.of(
                "tenantId", "tenant-a",
                "organizationId", "org-a",
                "projectId", "project-a",
                "environmentId", "test",
                "actorId", "runner",
                "classification", "INTERNAL");
    }

    private static Map<String, Object> runMetadata(Map<String, Object> additions) {
        Map<String, Object> metadata = new LinkedHashMap<>(runMetadata());
        metadata.putAll(additions);
        return Map.copyOf(metadata);
    }

    private TestSuiteRunRecord withId(TestSuiteRunRecord source, String runId, String requestId) {
        TestSuiteRunEvidence evidence = (TestSuiteRunEvidence) source.evidence();
        TestSuiteRunEvidence renamed = new TestSuiteRunEvidence("", runId, requestId,
                evidence.status(), evidence.executionPurpose(), evidence.suiteRef(), evidence.target(),
                evidence.startedAt(), evidence.completedAt(), evidence.caseResults(), evidence.coverage(),
                evidence.promotion(), evidence.diagnostics(), evidence.metadata());
        TestSuiteRunAttestation attestation = attestations.seal(renamed,
                source.requestFingerprint(), source.attestation().childEvidenceRefs(),
                TestSuiteRunAttestation.Scope.CHECKPOINT).attestation();
        return new TestSuiteRunRecord(runId, requestId, source.requestFingerprint(), source.tenantId(),
                source.organizationId(), source.projectId(), source.environmentId(), source.actorId(),
                source.classification(), "", renamed, attestation,
                source.createdAt(), source.expiresAt());
    }
}
