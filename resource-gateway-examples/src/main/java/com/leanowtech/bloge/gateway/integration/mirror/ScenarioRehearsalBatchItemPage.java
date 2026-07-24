package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable index-based page over one immutable Scenario batch manifest and its mutable outcomes.
 *
 * @param schemaVersion item-page protocol version
 * @param jobId owning durable batch
 * @param manifestFingerprint exact ordered manifest identity
 * @param items bounded contiguous item page
 * @param nextIndex next zero-based index, or null at the end
 */
public record ScenarioRehearsalBatchItemPage(
        String schemaVersion,
        String jobId,
        String manifestFingerprint,
        List<Item> items,
        Integer nextIndex
) {
    /** Current batch item page version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchItemPage.v1";
    /** Maximum public item-page size. */
    public static final int MAXIMUM_PAGE_SIZE = 100;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Durable item state. */
    public enum Status {
        PENDING,
        RUNNING,
        PASSED,
        FAILED,
        INDETERMINATE,
        CANCELLED;

        /** @return whether the item contributes to completed job counters */
        public boolean terminal() {
            return switch (this) {
                case PASSED, FAILED, INDETERMINATE, CANCELLED -> true;
                case PENDING, RUNNING -> false;
            };
        }
    }

    /**
     * One payload-free item projection.
     *
     * @param itemIndex zero-based immutable manifest position
     * @param compiledPlanRef exact plan
     * @param childRequestId stable aggregate child idempotency identity
     * @param status durable item state
     * @param attemptCount claimed infrastructure-attempt count
     * @param runId terminal Scenario aggregate id, blank without evidence
     * @param evidenceBundleFingerprint terminal signed aggregate evidence, blank without evidence
     * @param workbookSeedFingerprint deterministic workbook seed, blank without evidence
     * @param failureCode bounded structural failure, blank on pass or pending
     * @param startedAt first claim time, null before execution
     * @param completedAt terminal item time, otherwise null
     */
    public record Item(
            int itemIndex,
            MirrorArtifactRef compiledPlanRef,
            String childRequestId,
            Status status,
            int attemptCount,
            String runId,
            String evidenceBundleFingerprint,
            String workbookSeedFingerprint,
            String failureCode,
            Instant startedAt,
            Instant completedAt
    ) {
        /** Validates item identity and terminal evidence correspondence. */
        public Item {
            if (itemIndex < 0
                    || itemIndex
                    >= ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES) {
                throw new IllegalArgumentException(
                        "Scenario batch itemIndex is invalid");
            }
            if (compiledPlanRef == null
                    || !"COMPILED_REHEARSAL_PLAN".equals(
                    compiledPlanRef.kind())) {
                throw new IllegalArgumentException(
                        "compiledPlanRef must identify an exact compiled rehearsal plan");
            }
            childRequestId = identifier(
                    childRequestId, "childRequestId");
            status = Objects.requireNonNull(status, "status");
            if (attemptCount < 0
                    || attemptCount
                    > 5) {
                throw new IllegalArgumentException(
                        "Scenario batch attemptCount is invalid");
            }
            runId = optionalIdentifier(runId, "runId");
            evidenceBundleFingerprint = optionalFingerprint(
                    evidenceBundleFingerprint,
                    "evidenceBundleFingerprint");
            workbookSeedFingerprint = optionalFingerprint(
                    workbookSeedFingerprint,
                    "workbookSeedFingerprint");
            failureCode = code(failureCode);
            boolean completeEvidence =
                    !runId.isBlank()
                            && !evidenceBundleFingerprint.isBlank()
                            && !workbookSeedFingerprint.isBlank();
            boolean noEvidence =
                    runId.isBlank()
                            && evidenceBundleFingerprint.isBlank()
                            && workbookSeedFingerprint.isBlank();
            if (status.terminal() != (completedAt != null)
                    || attemptCount == 0
                    && status != Status.PENDING
                    && status != Status.CANCELLED
                    || startedAt == null && attemptCount > 0
                    || startedAt != null && attemptCount == 0
                    || startedAt != null && completedAt != null
                    && completedAt.isBefore(startedAt)
                    || !(completeEvidence || noEvidence)
                    || completeEvidence
                    && status != Status.PASSED
                    && status != Status.FAILED
                    && status != Status.INDETERMINATE
                    || status == Status.PASSED
                    && !completeEvidence
                    || status == Status.PASSED
                    && !failureCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Scenario batch item lifecycle is inconsistent");
            }
        }

        /** @return exact terminal aggregate outcome represented by this item */
        public ScenarioCaseRehearsalResult.Outcome outcome() {
            return switch (status) {
                case PASSED ->
                        ScenarioCaseRehearsalResult.Outcome.PASS;
                case FAILED ->
                        ScenarioCaseRehearsalResult.Outcome.FAIL;
                case INDETERMINATE ->
                        ScenarioCaseRehearsalResult.Outcome.INDETERMINATE;
                case PENDING, RUNNING, CANCELLED -> null;
            };
        }
    }

    /** Validates a contiguous, bounded, stable page. */
    public ScenarioRehearsalBatchItemPage {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch item page schemaVersion");
        }
        jobId = identifier(jobId, "jobId");
        manifestFingerprint = requiredFingerprint(
                manifestFingerprint, "manifestFingerprint");
        items = items == null ? List.of() : List.copyOf(items);
        if (items.size() > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Scenario batch item page is too large");
        }
        for (int index = 1; index < items.size(); index++) {
            if (items.get(index).itemIndex()
                    != items.get(index - 1).itemIndex() + 1) {
                throw new IllegalArgumentException(
                        "Scenario batch item page must be contiguous");
            }
        }
        if (nextIndex != null
                && (nextIndex < 0
                || nextIndex
                > ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES
                || !items.isEmpty()
                && nextIndex
                != items.get(items.size() - 1).itemIndex() + 1)) {
            throw new IllegalArgumentException(
                    "Scenario batch nextIndex is invalid");
        }
    }

    private static String identifier(String value, String field) {
        String normalized = normalized(value);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String optionalIdentifier(
            String value, String field) {
        String normalized = normalized(value);
        if (!normalized.isBlank()
                && !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String requiredFingerprint(
            String value, String field) {
        String normalized = normalized(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = normalized(value);
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String code(String value) {
        String normalized = normalized(value).toUpperCase(
                java.util.Locale.ROOT);
        if (!normalized.isBlank()
                && !CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "failureCode is invalid");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
