package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Compact payload-free projection returned by mirror run create and read operations.
 *
 * <p>The projection is derived only from independently verified evidence. It contains enough
 * identity and cardinality information for Tool Studio navigation and status polling without
 * exposing graph context, node values, edge values, fixture values, or replay payloads.</p>
 *
 * @param schemaVersion run-summary protocol version
 * @param runId terminal mirror run identity
 * @param requestId caller idempotency identity
 * @param planId admitted plan identity
 * @param planFingerprint exact admitted plan generation
 * @param requestContextFingerprint canonical effective context fingerprint
 * @param scope authenticated enterprise scope
 * @param status terminal mirror status
 * @param evidenceClass evidence trust class
 * @param startedAt execution start
 * @param completedAt execution completion
 * @param durationMs non-negative observed duration
 * @param nodeTraceCount payload-free node occurrence count
 * @param edgeTraceCount payload-free edge occurrence count
 * @param resolutionCount external resolver outcome count
 * @param evidenceBundleFingerprint exact signed evidence bundle
 */
public record MirrorRunSummary(
        String schemaVersion,
        String runId,
        String requestId,
        String planId,
        String planFingerprint,
        String requestContextFingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorRunEvidence.Status status,
        MirrorRunEvidence.EvidenceClass evidenceClass,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        int nodeTraceCount,
        int edgeTraceCount,
        int resolutionCount,
        String evidenceBundleFingerprint
) {
    /** Current payload-free run-summary protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.mirrorRunSummary.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates one complete terminal projection. */
    public MirrorRunSummary {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported mirror run summary schemaVersion");
        }
        runId = bounded(runId, "runId", 512);
        requestId = bounded(requestId, "requestId", 512);
        planId = bounded(planId, "planId", 512);
        planFingerprint = fingerprint(planFingerprint, "planFingerprint");
        requestContextFingerprint = fingerprint(requestContextFingerprint,
                "requestContextFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        if (scope.projectId().isBlank() || scope.region().isBlank()) {
            throw new IllegalArgumentException(
                    "mirror run summary requires complete project and region scope");
        }
        status = Objects.requireNonNull(status, "status");
        evidenceClass = Objects.requireNonNull(evidenceClass, "evidenceClass");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        long observedDuration = Duration.between(startedAt, completedAt).toMillis();
        if (observedDuration < 0 || durationMs != observedDuration) {
            throw new IllegalArgumentException(
                    "mirror run summary duration must match its timestamps");
        }
        if (nodeTraceCount < 0 || edgeTraceCount < 0 || resolutionCount < 0
                || nodeTraceCount > MirrorRunEvidence.MAXIMUM_TRACE_ITEMS
                || edgeTraceCount > MirrorRunEvidence.MAXIMUM_TRACE_ITEMS
                || resolutionCount > MirrorRunEvidence.MAXIMUM_RESOLUTIONS) {
            throw new IllegalArgumentException("mirror run summary counts exceed protocol limits");
        }
        evidenceBundleFingerprint = fingerprint(evidenceBundleFingerprint,
                "evidenceBundleFingerprint");
    }

    /**
     * Projects a signed bundle after repository or execution-boundary verification.
     *
     * @param bundle independently verified payload-free evidence
     * @return stable compact run projection
     */
    public static MirrorRunSummary from(MirrorEvidenceBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        MirrorRunEvidence evidence = bundle.evidence();
        return new MirrorRunSummary("", evidence.runId(), evidence.requestId(), evidence.planId(),
                evidence.planFingerprint(), evidence.requestContextFingerprint(), evidence.scope(),
                evidence.status(), evidence.evidenceClass(), evidence.startedAt(),
                evidence.completedAt(), Math.max(0,
                Duration.between(evidence.startedAt(), evidence.completedAt()).toMillis()),
                evidence.nodeTraces().size(), evidence.edgeTraces().size(),
                evidence.resolutions().size(), bundle.bundleFingerprint());
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String bounded(String value, String field, int maximum) {
        String normalized = required(value, field);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds its length limit");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256");
        }
        return normalized;
    }
}
