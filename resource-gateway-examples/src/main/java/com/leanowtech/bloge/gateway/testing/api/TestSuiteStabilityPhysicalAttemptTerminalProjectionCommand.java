package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed request to project one verified physical terminal fact into its queue job.
 *
 * <p>The command carries only immutable source references and fingerprints. It never treats a
 * local timeout, non-confirming observation, or quarantine as terminal evidence. A
 * {@code CANCELLED} disposition must name an exact provider-confirmed cancellation receipt, and
 * {@code SUCCEEDED} must name the expected signed parent winner.</p>
 *
 * @param schemaVersion exact command generation
 * @param projectionId content-addressed projection identity
 * @param commandFingerprint canonical command-material fingerprint
 * @param tenantId exact tenant scope
 * @param environmentId exact isolated environment
 * @param jobId exact durable queue job
 * @param attemptId exact physical attempt
 * @param leaseEpoch exact queue lease generation that created the attempt
 * @param reservationRecordFingerprint exact physical-attempt reservation row
 * @param startCommandId exact retained start command
 * @param startEntryFingerprint exact retained start journal row
 * @param observationCommandId exact terminal observation command
 * @param observationEntryFingerprint exact accepted observation journal row
 * @param positiveStateFingerprint exact terminal positive-state floor
 * @param terminalDisposition expected provider-confirmed terminal disposition
 * @param cancellationCommandId exact confirmed cancellation command, or empty
 * @param cancellationEntryFingerprint exact confirmed cancellation row, or empty
 * @param parentStabilityRunId expected signed parent run only for success
 * @param parentEvidenceFingerprint expected signed parent evidence only for success
 */
public record TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand(
        String schemaVersion,
        String projectionId,
        String commandFingerprint,
        String tenantId,
        String environmentId,
        String jobId,
        String attemptId,
        long leaseEpoch,
        String reservationRecordFingerprint,
        String startCommandId,
        String startEntryFingerprint,
        String observationCommandId,
        String observationEntryFingerprint,
        String positiveStateFingerprint,
        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                terminalDisposition,
        String cancellationCommandId,
        String cancellationEntryFingerprint,
        String parentStabilityRunId,
        String parentEvidenceFingerprint) {

    /** Exact terminal-projection command generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionCommand.v1";
    private static final Pattern PROJECTION_ID =
            Pattern.compile("stability-attempt-terminal-project-[a-f0-9]{64}");
    private static final Pattern JOB_ID = Pattern.compile("stability-job-[a-f0-9]{64}");
    private static final Pattern ATTEMPT_ID =
            Pattern.compile("stability-attempt-[a-f0-9]{64}");
    private static final Pattern START_COMMAND_ID =
            Pattern.compile("stability-attempt-start-[a-f0-9]{64}");
    private static final Pattern OBSERVATION_COMMAND_ID =
            Pattern.compile("stability-attempt-observe-[a-f0-9]{64}");
    private static final Pattern CANCELLATION_COMMAND_ID =
            Pattern.compile("stability-attempt-cancel-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Enforces the source-reference and terminal-disposition truth table. */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand {
        schemaVersion = required(schemaVersion, "schemaVersion");
        projectionId = required(projectionId, "projectionId");
        commandFingerprint = required(commandFingerprint, "commandFingerprint");
        tenantId = identifier(tenantId, "tenantId");
        environmentId = required(environmentId, "environmentId");
        jobId = required(jobId, "jobId");
        attemptId = required(attemptId, "attemptId");
        reservationRecordFingerprint = required(
                reservationRecordFingerprint, "reservationRecordFingerprint");
        startCommandId = required(startCommandId, "startCommandId");
        startEntryFingerprint = required(startEntryFingerprint, "startEntryFingerprint");
        observationCommandId = required(observationCommandId, "observationCommandId");
        observationEntryFingerprint = required(
                observationEntryFingerprint, "observationEntryFingerprint");
        positiveStateFingerprint = required(
                positiveStateFingerprint, "positiveStateFingerprint");
        terminalDisposition = Objects.requireNonNull(
                terminalDisposition, "terminalDisposition");
        cancellationCommandId = normalized(cancellationCommandId);
        cancellationEntryFingerprint = normalized(cancellationEntryFingerprint);
        parentStabilityRunId = normalized(parentStabilityRunId);
        parentEvidenceFingerprint = normalized(parentEvidenceFingerprint);
        boolean cancellationReference = CANCELLATION_COMMAND_ID.matcher(
                cancellationCommandId).matches()
                && FINGERPRINT.matcher(cancellationEntryFingerprint).matches();
        boolean parentReference = IDENTIFIER.matcher(parentStabilityRunId).matches()
                && FINGERPRINT.matcher(parentEvidenceFingerprint).matches();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !PROJECTION_ID.matcher(projectionId).matches()
                || !FINGERPRINT.matcher(commandFingerprint).matches()
                || !projectionId.equals("stability-attempt-terminal-project-"
                + commandFingerprint.substring("sha256:".length()))
                || !Set.of("test", "staging").contains(environmentId)
                || !JOB_ID.matcher(jobId).matches()
                || !ATTEMPT_ID.matcher(attemptId).matches()
                || leaseEpoch < 1
                || !FINGERPRINT.matcher(reservationRecordFingerprint).matches()
                || !START_COMMAND_ID.matcher(startCommandId).matches()
                || !FINGERPRINT.matcher(startEntryFingerprint).matches()
                || !OBSERVATION_COMMAND_ID.matcher(observationCommandId).matches()
                || !FINGERPRINT.matcher(observationEntryFingerprint).matches()
                || !FINGERPRINT.matcher(positiveStateFingerprint).matches()
                || terminalDisposition
                == TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.NONE
                || cancellationReference != (terminalDisposition
                == TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.CANCELLED)
                || parentReference != (terminalDisposition
                == TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.SUCCEEDED)
                || !cancellationReference && (!cancellationCommandId.isEmpty()
                || !cancellationEntryFingerprint.isEmpty())
                || !parentReference && (!parentStabilityRunId.isEmpty()
                || !parentEvidenceFingerprint.isEmpty())) {
            throw new IllegalArgumentException(
                    "Invalid physical-attempt terminal-projection command");
        }
    }

    /**
     * Creates an exact command from integrity-verified durable source projections.
     *
     * @param objectMapper canonical protocol mapper
     * @param reservation exact physical-attempt reservation
     * @param start exact retained start command
     * @param observation exact accepted terminal observation command
     * @param positiveState exact terminal positive-state floor
     * @param cancellation exact confirmed cancellation when disposition is cancelled
     * @param parentStabilityRunId expected signed parent run only for success
     * @param parentEvidenceFingerprint expected signed parent evidence only for success
     * @return immutable content-addressed terminal-projection command
     */
    public static TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand create(
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptRegistry.Entry reservation,
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry start,
            TestSuiteStabilityPhysicalAttemptObservationJournal.Entry observation,
            TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState positiveState,
            Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> cancellation,
            String parentStabilityRunId,
            String parentEvidenceFingerprint) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        TestSuiteStabilityPhysicalAttemptRegistry.Entry exactReservation =
                Objects.requireNonNull(reservation, "reservation");
        TestSuiteStabilityPhysicalAttemptStartJournal.Entry exactStart =
                Objects.requireNonNull(start, "start");
        TestSuiteStabilityPhysicalAttemptObservationJournal.Entry exactObservation =
                Objects.requireNonNull(observation, "observation");
        TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState exactState =
                Objects.requireNonNull(positiveState, "positiveState");
        Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> exactCancellation =
                Objects.requireNonNull(cancellation, "cancellation");
        TestSuiteStabilityPhysicalAttemptIdentity identity = exactReservation.identity();
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = exactState.receipt();
        if (!exactStart.command().identity().equals(identity)
                || !exactObservation.command().identity().equals(identity)
                || !exactObservation.command().startCommand().equals(exactStart.command())
                || exactObservation.status()
                != TestSuiteStabilityPhysicalAttemptObservationJournal.Status.POSITIVE
                || exactObservation.attestation().isEmpty()
                || !exactObservation.command().commandId().equals(
                exactState.observationCommandId())
                || !exactObservation.attestation().orElseThrow().receipt().equals(receipt)
                || receipt.state()
                != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL) {
            throw new IllegalArgumentException(
                    "Terminal projection sources do not form one exact attempt chain");
        }
        validateCancellation(identity, receipt, exactCancellation);
        String cancellationCommandId = exactCancellation
                .map(value -> value.command().commandId()).orElse("");
        String cancellationFingerprint = exactCancellation
                .map(TestSuiteStabilityAttemptCancellationJournal.Entry::recordFingerprint)
                .orElse("");
        Map<String, Object> material = material(
                identity.tenantId(), identity.environmentId(), identity.jobId(),
                identity.attemptId(), identity.leaseEpoch(),
                exactReservation.recordFingerprint(), exactStart.command().commandId(),
                exactStart.recordFingerprint(), exactObservation.command().commandId(),
                exactObservation.recordFingerprint(), exactState.recordFingerprint(),
                receipt.terminalDisposition(), cancellationCommandId,
                cancellationFingerprint, parentStabilityRunId, parentEvidenceFingerprint);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand(
                SCHEMA_VERSION,
                "stability-attempt-terminal-project-"
                        + fingerprint.substring("sha256:".length()),
                fingerprint, identity.tenantId(), identity.environmentId(), identity.jobId(),
                identity.attemptId(), identity.leaseEpoch(),
                exactReservation.recordFingerprint(), exactStart.command().commandId(),
                exactStart.recordFingerprint(), exactObservation.command().commandId(),
                exactObservation.recordFingerprint(), exactState.recordFingerprint(),
                receipt.terminalDisposition(), cancellationCommandId,
                cancellationFingerprint, parentStabilityRunId, parentEvidenceFingerprint);
    }

    /**
     * Reconstructs the canonical semantic material used to derive command identity.
     *
     * @return canonical source references and expected terminal winner
     */
    public Map<String, Object> canonicalMaterial() {
        return material(tenantId, environmentId, jobId, attemptId, leaseEpoch,
                reservationRecordFingerprint, startCommandId, startEntryFingerprint,
                observationCommandId, observationEntryFingerprint, positiveStateFingerprint,
                terminalDisposition, cancellationCommandId, cancellationEntryFingerprint,
                parentStabilityRunId, parentEvidenceFingerprint);
    }

    private static void validateCancellation(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityPhysicalAttemptObservationReceipt terminal,
            Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> cancellation) {
        boolean cancelled = terminal.terminalDisposition()
                == TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.CANCELLED;
        if (cancelled != cancellation.isPresent()) {
            throw new IllegalArgumentException(
                    "Cancelled terminal projection requires one confirmed cancellation");
        }
        if (cancellation.isEmpty()) {
            return;
        }
        TestSuiteStabilityAttemptCancellationJournal.Entry entry = cancellation.orElseThrow();
        TestSuiteStabilityAttemptCancellationReceipt receipt = entry.attestation()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cancellation projection source is not accepted"))
                .receipt();
        TestSuiteStabilityAttemptCancellationCommand command = entry.command();
        if (entry.status() != TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED
                || !receipt.terminationConfirmed()
                || command.reason()
                != TestSuiteStabilityAttemptCancellationCommand.Reason.CANCELLED
                || !command.tenantId().equals(identity.tenantId())
                || !command.environmentId().equals(identity.environmentId())
                || !command.jobId().equals(identity.jobId())
                || !command.attemptId().equals(identity.attemptId())
                || command.leaseEpoch() != identity.leaseEpoch()
                || !command.ownerId().equals(identity.ownerId())
                || !command.runtimeBindingFingerprint().equals(
                identity.runtimeBindingFingerprint())
                || !receipt.providerId().equals(identity.providerId())
                || !receipt.deploymentId().equals(identity.deploymentId())
                || receipt.isolationMode() != identity.isolationMode()
                || !receipt.processIdentityFingerprint().equals(
                terminal.processIdentityFingerprint())
                || !receipt.terminalStateFingerprint().equals(
                terminal.runtimeStateFingerprint())
                || terminal.stateEffectiveAt().isBefore(receipt.confirmedAt())) {
            throw new IllegalArgumentException(
                    "Cancellation projection source contradicts terminal observation");
        }
    }

    private static Map<String, Object> material(
            String tenantId,
            String environmentId,
            String jobId,
            String attemptId,
            long leaseEpoch,
            String reservationRecordFingerprint,
            String startCommandId,
            String startEntryFingerprint,
            String observationCommandId,
            String observationEntryFingerprint,
            String positiveStateFingerprint,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition,
            String cancellationCommandId,
            String cancellationEntryFingerprint,
            String parentStabilityRunId,
            String parentEvidenceFingerprint) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("tenantId", tenantId);
        material.put("environmentId", environmentId);
        material.put("jobId", jobId);
        material.put("attemptId", attemptId);
        material.put("leaseEpoch", leaseEpoch);
        material.put("reservationRecordFingerprint", reservationRecordFingerprint);
        material.put("startCommandId", startCommandId);
        material.put("startEntryFingerprint", startEntryFingerprint);
        material.put("observationCommandId", observationCommandId);
        material.put("observationEntryFingerprint", observationEntryFingerprint);
        material.put("positiveStateFingerprint", positiveStateFingerprint);
        material.put("terminalDisposition", disposition);
        material.put("cancellationCommandId", normalized(cancellationCommandId));
        material.put("cancellationEntryFingerprint", normalized(cancellationEntryFingerprint));
        material.put("parentStabilityRunId", normalized(parentStabilityRunId));
        material.put("parentEvidenceFingerprint", normalized(parentEvidenceFingerprint));
        return Map.copyOf(material);
    }

    private static String identifier(String value, String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
