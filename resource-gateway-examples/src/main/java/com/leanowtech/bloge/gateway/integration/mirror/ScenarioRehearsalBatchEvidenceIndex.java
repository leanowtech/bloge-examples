package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Content-addressed terminal index for one durable Scenario rehearsal batch.
 *
 * <p>The index freezes the original payload-free request, exact compiled-plan manifest, terminal
 * job projection, and complete ordered item outcomes. Child aggregate evidence remains separately
 * addressable by {@code runId} and {@code evidenceBundleFingerprint}; this keeps a batch index
 * bounded while preserving a complete independently traversable correctness closure.</p>
 *
 * @param schemaVersion exact portable index version
 * @param indexFingerprint canonical fingerprint with this field blanked
 * @param request original strict payload-free request
 * @param manifest immutable exact-plan execution closure
 * @param job terminal integrity-sealed job projection
 * @param items complete ordered terminal item index
 */
public record ScenarioRehearsalBatchEvidenceIndex(
        String schemaVersion,
        String indexFingerprint,
        ScenarioRehearsalBatchRequest request,
        ScenarioRehearsalBatchManifest manifest,
        ScenarioRehearsalBatchJob job,
        List<ScenarioRehearsalBatchItemPage.Item> items
) {
    /** Legacy evidence-index version that embeds a v1 terminal job. */
    public static final String V1_SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchEvidenceIndex.v1";
    /** Current evidence-index version that embeds a v2 terminal job. */
    public static final String V2_SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchEvidenceIndex.v2";
    /** Current portable batch evidence-index version. */
    public static final String SCHEMA_VERSION =
            V2_SCHEMA_VERSION;
    /** Maximum canonical index bytes admitted to signing and verification. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            16 * 1024 * 1024;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates exact request, manifest, terminal job, item, and summary correspondence. */
    public ScenarioRehearsalBatchEvidenceIndex {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!V1_SCHEMA_VERSION.equals(schemaVersion)
                && !V2_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario rehearsal batch evidence-index version");
        }
        indexFingerprint = optionalFingerprint(indexFingerprint);
        request = Objects.requireNonNull(request, "request");
        manifest = Objects.requireNonNull(manifest, "manifest");
        job = Objects.requireNonNull(job, "job");
        items = items == null ? List.of() : List.copyOf(items);
        String expectedJobVersion =
                V1_SCHEMA_VERSION.equals(schemaVersion)
                        ? ScenarioRehearsalBatchJob
                        .V1_SCHEMA_VERSION
                        : ScenarioRehearsalBatchJob
                        .V2_SCHEMA_VERSION;
        if (!expectedJobVersion.equals(job.schemaVersion())
                || !job.status().terminal()
                || job.completedAt() == null
                || !job.jobId().equals(manifest.batchId())
                || !job.requestId().equals(request.requestId())
                || !job.requestId().equals(manifest.requestId())
                || !job.scope().equals(manifest.scope())
                || !job.manifestFingerprint().equals(
                manifest.manifestFingerprint())
                || request.entries().size() != manifest.entries().size()
                || items.size() != manifest.entries().size()
                || items.size() != job.summary().totalItems()) {
            throw new IllegalArgumentException(
                    "Scenario batch evidence source closure is inconsistent");
        }
        for (int index = 0; index < items.size(); index++) {
            ScenarioRehearsalBatchRequest.Entry requested =
                    request.entries().get(index);
            ScenarioRehearsalBatchManifest.Entry planned =
                    manifest.entries().get(index);
            ScenarioRehearsalBatchItemPage.Item item =
                    Objects.requireNonNull(
                            items.get(index), "item");
            if (planned.entryIndex() != index
                    || item.itemIndex() != index
                    || !requested.entryId().equals(planned.entryId())
                    || !requested.compiledPlanRef().equals(
                    planned.compiledPlanRef())
                    || !item.compiledPlanRef().equals(
                    planned.compiledPlanRef())
                    || !item.childRequestId().equals(
                    planned.aggregateRequestId())
                    || !item.status().terminal()
                    || item.completedAt() == null
                    || item.completedAt().isAfter(job.completedAt())
                    || (!item.runId().isBlank()
                    && !item.runId().equals(
                            planned.aggregateRunId()))) {
                throw new IllegalArgumentException(
                        "Scenario batch evidence item closure is inconsistent");
            }
        }
        ScenarioRehearsalBatchJob.Summary derived =
                summary(items);
        if (!derived.equals(job.summary())
                || (job.status()
                        == ScenarioRehearsalBatchJob.Status.SUCCEEDED
                && derived.passedItems() != derived.totalItems())
                || (job.status()
                        != ScenarioRehearsalBatchJob.Status.SUCCEEDED
                && derived.passedItems() == derived.totalItems())) {
            throw new IllegalArgumentException(
                    "Scenario batch evidence summary or status is inconsistent");
        }
    }

    /** @return identical index carrying a replacement canonical fingerprint */
    public ScenarioRehearsalBatchEvidenceIndex withFingerprint(
            String value) {
        return new ScenarioRehearsalBatchEvidenceIndex(
                schemaVersion,
                value,
                request,
                manifest,
                job,
                items);
    }

    private static ScenarioRehearsalBatchJob.Summary summary(
            List<ScenarioRehearsalBatchItemPage.Item> items) {
        int passed = count(
                items, ScenarioRehearsalBatchItemPage.Status.PASSED);
        int failed = count(
                items, ScenarioRehearsalBatchItemPage.Status.FAILED);
        int indeterminate = count(
                items,
                ScenarioRehearsalBatchItemPage.Status.INDETERMINATE);
        int cancelled = count(
                items, ScenarioRehearsalBatchItemPage.Status.CANCELLED);
        return new ScenarioRehearsalBatchJob.Summary(
                items.size(),
                passed + failed + indeterminate + cancelled,
                passed,
                failed,
                indeterminate,
                cancelled);
    }

    private static int count(
            List<ScenarioRehearsalBatchItemPage.Item> items,
            ScenarioRehearsalBatchItemPage.Status status) {
        return Math.toIntExact(
                items.stream()
                        .filter(item -> item.status() == status)
                        .count());
    }

    private static String optionalFingerprint(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "indexFingerprint must be blank or canonical SHA-256");
        }
        return normalized;
    }
}
