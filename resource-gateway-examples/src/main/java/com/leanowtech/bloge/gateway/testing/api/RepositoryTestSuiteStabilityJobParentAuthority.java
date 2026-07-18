package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Repository-backed parent terminal authority for durable suite-stability jobs.
 *
 * <p>The stop uses a stable server actor so a successor can replay it after the outer queue
 * transaction fails. If signed evidence already exists, that immutable result wins and the queue
 * may converge to success instead of lying about cancellation or failure. Queue success is also
 * admitted only after the retained parent evidence and detached signature are independently
 * verified.</p>
 */
public final class RepositoryTestSuiteStabilityJobParentAuthority
        implements TestSuiteStabilityJobParentAuthority {

    private static final String CONTROL_ACTOR = "stability-job-control";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final TestSuiteStabilityRunRepository repository;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityAttestationService attestations;

    /**
     * @param repository durable parent progress, stop, and terminal authority
     * @param objectMapper canonical protocol mapper
     * @param attestations detached-signature verifier for completed-parent winners
     */
    public RepositoryTestSuiteStabilityJobParentAuthority(
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttestationService attestations) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.attestations = Objects.requireNonNull(attestations, "attestations");
    }

    @Override
    public Resolution stop(
            TestSuiteStabilityJobRecord job,
            TestSuiteStabilityExecutionStop.Reason reason,
            String failureCode,
            Duration retention) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(retention, "retention");
        TestSuiteStabilityExecutionDescriptor execution =
                TestSuiteStabilityExecutionIdentity.descriptor(objectMapper, job);
        try {
            repository.stop(new TestSuiteStabilityExecutionStopRequest(
                    execution.stabilityRunId(), execution.tenantId(),
                    execution.environmentId(), execution.clientRequestId(),
                    execution.requestFingerprint(), execution.classification(), reason,
                    failureCode, CONTROL_ACTOR, retention));
            return Resolution.stopped();
        } catch (TestSuiteStabilityRunConflictException conflict) {
            if (conflict.reason()
                    != TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT) {
                throw conflict;
            }
            return verifiedCompleted(execution, "", "", conflict);
        }
    }

    @Override
    public Resolution requireCompleted(
            TestSuiteStabilityJobRecord job,
            String stabilityRunId,
            String evidenceFingerprint) {
        Objects.requireNonNull(job, "job");
        String expectedRunId = normalized(stabilityRunId);
        String expectedEvidence = normalized(evidenceFingerprint);
        TestSuiteStabilityExecutionDescriptor execution =
                TestSuiteStabilityExecutionIdentity.descriptor(objectMapper, job);
        if (!execution.stabilityRunId().equals(expectedRunId)) {
            throw terminalConflict(
                    "Queue completion does not reference its deterministic parent run");
        }
        if (!FINGERPRINT.matcher(expectedEvidence).matches()) {
            throw terminalConflict(
                    "Queue completion does not reference canonical parent evidence");
        }
        return verifiedCompleted(execution, expectedRunId, expectedEvidence,
                terminalConflict("Signed parent completion is not durably available"));
    }

    private Resolution verifiedCompleted(
            TestSuiteStabilityExecutionDescriptor execution,
            String expectedRunId,
            String expectedEvidenceFingerprint,
            RuntimeException absentFailure) {
        TestSuiteStabilityRunRecord terminal = repository.find(
                execution.tenantId(), execution.environmentId(), execution.stabilityRunId())
                .orElseThrow(() -> absentFailure);
        if ((!expectedRunId.isBlank()
                && !terminal.stabilityRunId().equals(expectedRunId))
                || (!expectedEvidenceFingerprint.isBlank()
                && !terminal.evidenceFingerprint().equals(expectedEvidenceFingerprint))
                || !terminal.clientRequestId().equals(execution.clientRequestId())
                    || !terminal.requestFingerprint().equals(execution.requestFingerprint())
                    || !terminal.classification().equals(execution.classification())
                    || !terminal.stabilityRunId().equals(
                    terminal.evidence().stabilityRunId())
                    || !terminal.stabilityRunId().equals(
                    terminal.attestation().stabilityRunId())
                    || !terminal.requestFingerprint().equals(
                    terminal.attestation().requestFingerprint())
                    || !terminal.attestation().terminallyVerifiable()
                    || !terminal.evidenceFingerprint().equals(
                    ProtocolFingerprint.of(objectMapper, terminal.evidence()))
                    || !terminal.evidenceFingerprint().equals(
                    terminal.attestation().evidenceFingerprint())
                    || attestations.verify(terminal.evidence(), terminal.attestation())
                    != TestSuiteStabilityAttestationService.Verification.VERIFIED) {
            throw terminalConflict(
                    "Signed parent winner contradicts the durable stability job");
        }
        return Resolution.completed(
                terminal.stabilityRunId(), terminal.evidenceFingerprint());
    }

    private static TestSuiteStabilityRunConflictException terminalConflict(String message) {
        return new TestSuiteStabilityRunConflictException(
                TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT, message);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
