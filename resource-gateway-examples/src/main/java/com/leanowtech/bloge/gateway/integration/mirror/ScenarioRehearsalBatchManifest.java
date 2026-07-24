package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed payload-free execution closure for one durable Scenario batch.
 *
 * <p>Every entry binds an exact compiled plan to deterministic aggregate request and run
 * identities. Queue workers consume this manifest rather than reinterpreting a mutable submission
 * body. The manifest contains no TestSuite input, fixture value, Session payload, or credential.</p>
 *
 * @param schemaVersion exact manifest protocol version
 * @param batchId stable full-scope batch identity
 * @param manifestFingerprint canonical fingerprint with this field blanked
 * @param scope complete enterprise scope
 * @param requestId caller idempotency identity
 * @param entries ordered exact execution closure
 * @param totalCases exact sum of all compiled-plan case counts
 */
public record ScenarioRehearsalBatchManifest(
        String schemaVersion,
        String batchId,
        String manifestFingerprint,
        CapabilitySnapshot.Scope scope,
        String requestId,
        List<Entry> entries,
        int totalCases
) {
    /** Current immutable batch-manifest version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchManifest.v1";
    /** Artifact kind used by later batch evidence and deep links. */
    public static final String ARTIFACT_KIND =
            "SCENARIO_REHEARSAL_BATCH_MANIFEST";
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces ordered identity and derived case-count closure. */
    public ScenarioRehearsalBatchManifest {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario rehearsal batch manifest schemaVersion");
        }
        batchId = required(batchId, "batchId");
        if (!ScenarioRehearsalBatchIdentity.hasCanonicalShape(batchId)) {
            throw new IllegalArgumentException(
                    "batchId must be canonical");
        }
        manifestFingerprint = optionalFingerprint(
                manifestFingerprint);
        scope = Objects.requireNonNull(scope, "scope");
        requestId = required(requestId, "requestId");
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.isEmpty()
                || entries.size()
                > ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES) {
            throw new IllegalArgumentException(
                    "manifest entries must be non-empty and bounded");
        }
        Set<String> entryIds = new HashSet<>();
        Set<MirrorArtifactRef> plans = new HashSet<>();
        Set<String> aggregateRequests = new HashSet<>();
        Set<String> aggregateRuns = new HashSet<>();
        long derivedCases = 0;
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = Objects.requireNonNull(
                    entries.get(index), "entry");
            if (entry.entryIndex() != index
                    || !entryIds.add(entry.entryId())
                    || !plans.add(entry.compiledPlanRef())
                    || !aggregateRequests.add(
                    entry.aggregateRequestId())
                    || !aggregateRuns.add(
                    entry.aggregateRunId())) {
                throw new IllegalArgumentException(
                        "manifest entry order and identities must be unique");
            }
            derivedCases = Math.addExact(
                    derivedCases, entry.caseCount());
        }
        if (derivedCases != totalCases
                || totalCases < 1
                || totalCases
                > ScenarioRehearsalBatchRequest
                .MAXIMUM_TOTAL_CASES) {
            throw new IllegalArgumentException(
                    "manifest totalCases must be exact and bounded");
        }
    }

    /**
     * One deterministic aggregate execution inside the batch.
     *
     * @param entryIndex zero-based manifest order
     * @param entryId caller-stable payload-free entry id
     * @param compiledPlanRef exact plan
     * @param aggregateRequestId server-derived aggregate idempotency key
     * @param aggregateRunId server-derived full-scope run identity
     * @param caseCount exact cases frozen by the compiled plan
     * @param executionTimeout exact plan total timeout used to fence one worker claim
     */
    public record Entry(
            int entryIndex,
            String entryId,
            MirrorArtifactRef compiledPlanRef,
            String aggregateRequestId,
            String aggregateRunId,
            int caseCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Duration executionTimeout
    ) {
        /** Validates one complete queue-independent entry closure. */
        public Entry {
            if (entryIndex < 0) {
                throw new IllegalArgumentException(
                        "entryIndex must not be negative");
            }
            entryId = required(entryId, "entryId");
            if (compiledPlanRef == null
                    || !"COMPILED_REHEARSAL_PLAN".equals(
                    compiledPlanRef.kind())) {
                throw new IllegalArgumentException(
                        "compiledPlanRef must identify an exact plan");
            }
            aggregateRequestId = required(
                    aggregateRequestId, "aggregateRequestId");
            aggregateRunId = required(
                    aggregateRunId, "aggregateRunId");
            executionTimeout = Objects.requireNonNull(
                    executionTimeout, "executionTimeout");
            if (!ScenarioRehearsalRunIdentity.hasCanonicalShape(
                    aggregateRunId)
                    || caseCount < 1
                    || caseCount > ScenarioPack.MAXIMUM_CASES
                    || executionTimeout.isZero()
                    || executionTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "aggregate run identity or caseCount is invalid");
            }
        }
    }

    /** @return identical material carrying a replacement canonical fingerprint */
    public ScenarioRehearsalBatchManifest withFingerprint(
            String value) {
        return new ScenarioRehearsalBatchManifest(
                schemaVersion,
                batchId,
                value,
                scope,
                requestId,
                entries,
                totalCases);
    }

    /** @return exact manifest artifact reference after sealing */
    public MirrorArtifactRef reference() {
        if (manifestFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "batch manifest must be sealed before reference");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                batchId,
                1,
                manifestFingerprint);
    }

    private static String optionalFingerprint(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "manifestFingerprint must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }
}
