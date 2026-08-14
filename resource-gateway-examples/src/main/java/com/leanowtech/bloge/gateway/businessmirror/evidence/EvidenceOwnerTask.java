package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned owner work item derived from one exact Package evidence debt signal. */
public record EvidenceOwnerTask(
        String schemaVersion,
        String taskFingerprint,
        String taskId,
        long version,
        CapabilitySnapshot.Scope scope,
        String packageId,
        long compilationRevision,
        long projectionRevision,
        String domainId,
        PackageEvidenceIndex.DriftReason reason,
        PackageEvidenceIndex.SignalSeverity severity,
        String owner,
        Status status,
        List<PackageEvidenceIndex.EvidenceSource> sourceLineage,
        Instant detectedAt,
        Instant dueAt,
        Instant updatedAt,
        String actedBy,
        @JsonInclude(JsonInclude.Include.ALWAYS) MirrorArtifactRef resolutionEvidenceRef,
        String deepLink
) {
    public static final String SCHEMA_VERSION = "resourceGateway.evidenceOwnerTask.v1";
    private static final int MAXIMUM_CANONICAL_BYTES = 1_048_576;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces exact lineage and state-specific resolution material. */
    public EvidenceOwnerTask {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        taskFingerprint = taskFingerprint == null ? "" : taskFingerprint.trim();
        taskId = required(taskId, "taskId");
        scope = Objects.requireNonNull(scope, "scope");
        packageId = required(packageId, "packageId");
        domainId = required(domainId, "domainId");
        reason = Objects.requireNonNull(reason, "reason");
        severity = Objects.requireNonNull(severity, "severity");
        owner = required(owner, "owner");
        status = Objects.requireNonNull(status, "status");
        sourceLineage = sourceLineage == null ? List.of() : sourceLineage.stream()
                .map(value -> Objects.requireNonNull(value, "sourceLineage item"))
                .distinct().sorted().toList();
        detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
        dueAt = Objects.requireNonNull(dueAt, "dueAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        actedBy = actedBy == null ? "" : actedBy.trim();
        deepLink = required(deepLink, "deepLink");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !taskFingerprint.isEmpty() && !FINGERPRINT.matcher(taskFingerprint).matches()
                || version < 1 || compilationRevision < 1 || projectionRevision < 1
                || sourceLineage.isEmpty() || dueAt.isBefore(detectedAt)
                || updatedAt.isBefore(detectedAt)
                || status == Status.OPEN && (!actedBy.isBlank() || resolutionEvidenceRef != null)
                || status == Status.ACKNOWLEDGED && (actedBy.isBlank()
                || resolutionEvidenceRef != null)
                || status == Status.RESOLVED && (actedBy.isBlank()
                || resolutionEvidenceRef == null)
                || status == Status.SUPERSEDED && actedBy.isBlank()) {
            throw new IllegalArgumentException("evidence owner task is inconsistent");
        }
    }

    /** Creates an open task from a projector signal. */
    public static EvidenceOwnerTask open(
            PackageEvidenceIndex index,
            PackageEvidenceIndex.DriftSignal signal,
            String deepLink,
            ObjectMapper mapper) {
        PackageEvidenceIndex exact = Objects.requireNonNull(index, "index");
        PackageEvidenceIndex.DriftSignal debt = Objects.requireNonNull(signal, "signal");
        return new EvidenceOwnerTask("", "", debt.signalId(), 1, exact.scope(),
                exact.packageId(), exact.compilationRevision(), exact.projectionRevision(),
                exact.domainId(), debt.reason(), debt.severity(), debt.owner(), Status.OPEN,
                debt.sourceLineage(), debt.detectedAt(), debt.dueAt(), debt.detectedAt(),
                "", null, deepLink).seal(mapper);
    }

    /** Returns a new immutable lifecycle revision. */
    public EvidenceOwnerTask transition(
            Status target,
            String actor,
            MirrorArtifactRef evidenceRef,
            Instant at,
            ObjectMapper mapper) {
        Status next = Objects.requireNonNull(target, "target");
        if (!status.mayTransitionTo(next)) {
            throw new IllegalStateException("evidence owner task transition is not allowed");
        }
        String exactActor = required(actor, "actor");
        if (next == Status.RESOLVED && evidenceRef == null) {
            throw new IllegalArgumentException("resolved task requires exact resolution evidence");
        }
        return new EvidenceOwnerTask(schemaVersion, "", taskId, Math.addExact(version, 1),
                scope, packageId, compilationRevision, projectionRevision, domainId, reason,
                severity, owner, next, sourceLineage, detectedAt, dueAt,
                Objects.requireNonNull(at, "at"), exactActor,
                next == Status.RESOLVED ? evidenceRef : null, deepLink).seal(mapper);
    }

    public EvidenceOwnerTask seal(ObjectMapper mapper) {
        if (!taskFingerprint.isBlank()) {
            verify(mapper);
            return this;
        }
        return withFingerprint(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"), withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES));
    }

    public void verify(ObjectMapper mapper) {
        if (taskFingerprint.isBlank()
                || !taskFingerprint.equals(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"), withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES))) {
            throw new IllegalArgumentException("Evidence owner task fingerprint mismatch");
        }
    }

    public EvidenceOwnerTask withFingerprint(String value) {
        return new EvidenceOwnerTask(schemaVersion, value, taskId, version, scope, packageId,
                compilationRevision, projectionRevision, domainId, reason, severity, owner,
                status, sourceLineage, detectedAt, dueAt, updatedAt, actedBy,
                resolutionEvidenceRef, deepLink);
    }

    /** Task lifecycle is operational debt management, not an ANEKE publication gate. */
    public enum Status {
        OPEN,
        ACKNOWLEDGED,
        RESOLVED,
        SUPERSEDED;

        boolean mayTransitionTo(Status target) {
            return switch (this) {
                case OPEN -> target == ACKNOWLEDGED || target == RESOLVED
                        || target == SUPERSEDED;
                case ACKNOWLEDGED -> target == RESOLVED || target == SUPERSEDED;
                case RESOLVED, SUPERSEDED -> false;
            };
        }
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 1024) {
            throw new IllegalArgumentException(field + " must not be blank or oversized");
        }
        return exact;
    }
}
