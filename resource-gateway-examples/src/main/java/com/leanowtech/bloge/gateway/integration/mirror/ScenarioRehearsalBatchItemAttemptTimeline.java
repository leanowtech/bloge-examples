package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned payload-free attempt evidence for one durable Scenario batch item.
 *
 * <p>The projection exposes database-authoritative lifecycle times and stable reason codes. It
 * deliberately excludes worker identities, fixtures, request/response payloads, and exception
 * text.</p>
 *
 * @param schemaVersion public attempt-timeline protocol version
 * @param jobId owning batch
 * @param itemIndex immutable manifest position
 * @param maximumAttempts server-owned attempt ceiling
 * @param attemptsUsed number of claimed attempts
 * @param attemptsRemaining remaining server-owned budget
 * @param deadlineAt absolute batch deadline
 * @param failureMode immutable batch fallback policy
 * @param historyComplete whether every retained item lifecycle fact is represented
 * @param attempts oldest-to-newest exact attempt observations
 * @param authorTarget exact immutable Author source binding, or null when none is proven
 */
public record ScenarioRehearsalBatchItemAttemptTimeline(
        String schemaVersion,
        String jobId,
        int itemIndex,
        int maximumAttempts,
        int attemptsUsed,
        int attemptsRemaining,
        Instant deadlineAt,
        ScenarioRehearsalBatchPolicy.FailureMode failureMode,
        boolean historyComplete,
        List<Attempt> attempts,
        AuthorTarget authorTarget
) {
    /** Current public attempt-timeline protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchItemAttemptTimeline.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /**
     * Exact authoring source coordinates supplied by a host binding registry.
     *
     * @param kind authoring asset kind
     * @param id stable source identity
     * @param label human-readable source label
     * @param draftId exact graph draft identity, blank for non-graph targets
     * @param revision immutable source revision
     * @param sourceFingerprint exact source content address
     * @param nodeId optional graph node coordinate
     * @param scenarioId optional Scenario coordinate
     * @param runId optional visual run coordinate
     * @param owner accountable team or principal
     * @param requiredRole role required to edit the target
     */
    public record AuthorTarget(
            Kind kind,
            String id,
            String label,
            String draftId,
            long revision,
            String sourceFingerprint,
            String nodeId,
            String scenarioId,
            String runId,
            String owner,
            String requiredRole
    ) {
        /** Closed Author source kinds understood by the visual workspace. */
        public enum Kind {
            GRAPH_DRAFT,
            OPERATOR,
            FUNCTION
        }

        /** Rejects partial or mutable source coordinates. */
        public AuthorTarget {
            kind = Objects.requireNonNull(kind, "kind");
            id = identifier(id, "authorTarget.id");
            label = required(label, "authorTarget.label");
            draftId = optionalIdentifier(
                    draftId, "authorTarget.draftId");
            if (revision < 1) {
                throw new IllegalArgumentException(
                        "authorTarget.revision must be positive");
            }
            sourceFingerprint = requiredFingerprint(
                    sourceFingerprint,
                    "authorTarget.sourceFingerprint");
            nodeId = optionalIdentifier(
                    nodeId, "authorTarget.nodeId");
            scenarioId = optionalIdentifier(
                    scenarioId, "authorTarget.scenarioId");
            runId = optionalIdentifier(
                    runId, "authorTarget.runId");
            owner = required(owner, "authorTarget.owner");
            requiredRole = required(
                    requiredRole, "authorTarget.requiredRole");
            if ((kind == Kind.GRAPH_DRAFT) != !draftId.isBlank()) {
                throw new IllegalArgumentException(
                        "graph Author targets require draftId and non-graph targets forbid it");
            }
        }
    }

    /** One exact attempt reconstructed from append-only lifecycle facts. */
    public record Attempt(
            int attempt,
            State state,
            Instant startedAt,
            Instant observedAt,
            String outcome,
            String reasonCode,
            long claimSequence,
            long observationSequence
    ) {
        /** Closed attempt state vocabulary. */
        public enum State {
            RUNNING,
            RETRY_SCHEDULED,
            TERMINAL
        }

        /** Validates event coordinates without manufacturing an observation. */
        public Attempt {
            if (attempt < 1 || attempt > 5) {
                throw new IllegalArgumentException(
                        "Scenario batch attempt is outside policy bounds");
            }
            state = Objects.requireNonNull(state, "state");
            startedAt = Objects.requireNonNull(startedAt, "startedAt");
            outcome = optionalCode(outcome, "outcome");
            reasonCode = optionalCode(reasonCode, "reasonCode");
            if (claimSequence < 1
                    || observationSequence < 0
                    || observationSequence > 0
                    && observationSequence <= claimSequence
                    || state == State.RUNNING
                    && (observedAt != null
                    || observationSequence != 0
                    || !outcome.isBlank()
                    || !reasonCode.isBlank())
                    || state != State.RUNNING
                    && (observedAt == null
                    || observationSequence == 0)
                    || state == State.RETRY_SCHEDULED
                    && reasonCode.isBlank()
                    || state == State.TERMINAL
                    && outcome.isBlank()) {
                throw new IllegalArgumentException(
                        "Scenario batch attempt lifecycle is inconsistent");
            }
        }
    }

    /** Validates bounded counters and complete ordered attempt coordinates. */
    public ScenarioRehearsalBatchItemAttemptTimeline {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch attempt timeline schemaVersion");
        }
        jobId = identifier(jobId, "jobId");
        if (itemIndex < 0
                || itemIndex
                >= ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES
                || maximumAttempts < 1
                || maximumAttempts > 5
                || attemptsUsed < 0
                || attemptsUsed > maximumAttempts
                || attemptsRemaining != maximumAttempts - attemptsUsed) {
            throw new IllegalArgumentException(
                    "Scenario batch attempt timeline counters are inconsistent");
        }
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        failureMode = Objects.requireNonNull(failureMode, "failureMode");
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        if ((historyComplete && attempts.size() != attemptsUsed)
                || (!historyComplete && attempts.size() > attemptsUsed)) {
            throw new IllegalArgumentException(
                    "Scenario batch attempt timeline does not match its counter");
        }
        for (int index = 0; index < attempts.size(); index++) {
            if (attempts.get(index).attempt() != index + 1) {
                throw new IllegalArgumentException(
                        "Scenario batch attempts must be contiguous and ordered");
            }
        }
    }

    private static String identifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String optionalIdentifier(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String requiredFingerprint(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new IllegalArgumentException(field + " is blank or exceeds its bound");
        }
        return normalized;
    }

    private static String optionalCode(String value, String field) {
        String normalized = value == null
                ? ""
                : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.isBlank() && !CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
