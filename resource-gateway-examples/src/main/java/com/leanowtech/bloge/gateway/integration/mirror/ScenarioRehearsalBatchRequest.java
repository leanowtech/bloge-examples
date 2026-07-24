package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict payload-free command for one durable multi-plan Scenario rehearsal batch.
 *
 * <p>The caller chooses only an idempotency identity and ordered exact compiled plans. Runtime
 * context, fixture values, Session state, scheduling priority, worker count, retry policy, and
 * capacity are deliberately server-owned and cannot be overridden by this command.</p>
 *
 * @param schemaVersion exact request protocol version
 * @param requestId stable idempotency identity inside one complete enterprise scope
 * @param entries ordered unique batch entries
 */
public record ScenarioRehearsalBatchRequest(
        String schemaVersion,
        String requestId,
        List<Entry> entries
) {
    /** Current batch-submission request version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchRequest.v1";
    /** Maximum exact plans admitted by one immutable batch. */
    public static final int MAXIMUM_ENTRIES = 256;
    /** Maximum aggregate cases admitted after exact plan resolution. */
    public static final int MAXIMUM_TOTAL_CASES = 10_000;
    private static final Pattern REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern ENTRY_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** Validates bounded order and exact reference uniqueness. */
    public ScenarioRehearsalBatchRequest {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario rehearsal batch request schemaVersion");
        }
        requestId = identifier(
                requestId, REQUEST_ID, "requestId");
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > MAXIMUM_ENTRIES) {
            throw new IllegalArgumentException(
                    "batch entries must be non-empty and bounded");
        }
        Set<String> entryIds = new HashSet<>();
        Set<MirrorArtifactRef> planRefs = new HashSet<>();
        for (Entry entry : entries) {
            if (entry == null
                    || !entryIds.add(entry.entryId())
                    || !planRefs.add(entry.compiledPlanRef())) {
                throw new IllegalArgumentException(
                        "batch entry ids and compiled plans must be unique");
            }
        }
    }

    /**
     * One exact plan selected for batch execution.
     *
     * @param entryId caller-stable business-free entry identity
     * @param compiledPlanRef exact compiler-issued plan
     */
    public record Entry(
            String entryId,
            MirrorArtifactRef compiledPlanRef
    ) {
        /** Validates one exact payload-free plan coordinate. */
        public Entry {
            entryId = identifier(
                    entryId, ENTRY_ID, "entryId");
            if (compiledPlanRef == null
                    || !"COMPILED_REHEARSAL_PLAN".equals(
                    compiledPlanRef.kind())) {
                throw new IllegalArgumentException(
                        "compiledPlanRef must identify an exact compiled rehearsal plan");
            }
        }
    }

    private static String identifier(
            String value, Pattern pattern, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }
}
