package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free, content-addressed checkpoint of run-scoped execution-service state.
 *
 * <p>The snapshot carries only logical time, hashed provider scopes, deterministic sequence
 * cursors and usage counters. Random seeds, identity values, feature decisions, secret material and
 * raw provider scopes are deliberately absent. A snapshot is valid only for the exact effective
 * plan and execution-service binding set recorded here.</p>
 *
 * @param schemaVersion snapshot wire version
 * @param planFingerprint exact effective-plan fingerprint
 * @param bindingSetFingerprint canonical fingerprint of the provider binding set
 * @param logicalTime current logical time, or {@code null} for a wall-clock binding
 * @param randomScopeCursors next RANDOM occurrence by hashed scope
 * @param uuidScopeCursors next UUID occurrence by hashed scope
 * @param usages cumulative provider/function usage needed to continue evidence after resume
 * @param restorable whether every declared or observed semantic provider can resume exactly
 * @param restoreGaps stable reasons preventing exact restore
 * @param snapshotFingerprint canonical fingerprint of all preceding fields
 */
public record ExecutionServiceStateSnapshot(
        String schemaVersion,
        String planFingerprint,
        String bindingSetFingerprint,
        Instant logicalTime,
        Map<String, Long> randomScopeCursors,
        Map<String, Long> uuidScopeCursors,
        List<UsageState> usages,
        boolean restorable,
        List<String> restoreGaps,
        String snapshotFingerprint
) {
    /** Current provider-state checkpoint protocol version. */
    public static final String SCHEMA_VERSION = "bloge.executionServiceStateSnapshot.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int MAX_SCOPES = 10_000;
    private static final int MAX_USAGES = 6;
    private static final int MAX_GAPS = 100;
    private static final int MAX_GAP_LENGTH = 300;
    private static final int MAX_CALL_SITE_LENGTH = 2_048;

    /** Normalizes immutable collections and rejects structurally unsafe checkpoint material. */
    public ExecutionServiceStateSnapshot {
        schemaVersion = normalized(schemaVersion);
        planFingerprint = requiredFingerprint(planFingerprint, "planFingerprint");
        bindingSetFingerprint = requiredFingerprint(bindingSetFingerprint, "bindingSetFingerprint");
        randomScopeCursors = immutableCursors(randomScopeCursors, "randomScopeCursors");
        uuidScopeCursors = immutableCursors(uuidScopeCursors, "uuidScopeCursors");
        usages = usages == null ? List.of() : List.copyOf(usages);
        restoreGaps = restoreGaps == null ? List.of() : List.copyOf(restoreGaps);
        snapshotFingerprint = requiredFingerprint(snapshotFingerprint, "snapshotFingerprint");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported execution-service state snapshot version");
        }
        if (usages.size() > MAX_USAGES) {
            throw new IllegalArgumentException("Execution-service usage entry limit exceeded");
        }
        Set<String> services = new HashSet<>();
        if (usages.stream().anyMatch(usage -> !services.add(usage.service()))) {
            throw new IllegalArgumentException("Execution-service usage entries must be unique by service");
        }
        List<UsageState> sortedUsages = new ArrayList<>(usages);
        sortedUsages.sort(Comparator.comparing(UsageState::service));
        if (!sortedUsages.equals(usages)) {
            throw new IllegalArgumentException("Execution-service usage entries must use service order");
        }
        if (restoreGaps.size() > MAX_GAPS
                || new HashSet<>(restoreGaps).size() != restoreGaps.size()
                || restoreGaps.stream().anyMatch(gap -> gap == null || gap.isBlank()
                || gap.length() > MAX_GAP_LENGTH)) {
            throw new IllegalArgumentException("Restore gaps must be bounded, unique reasons");
        }
        if (restorable != restoreGaps.isEmpty()) {
            throw new IllegalArgumentException(
                    "restorable must be true exactly when restoreGaps is empty");
        }
    }

    /**
     * Returns the canonical material covered by {@link #snapshotFingerprint()}.
     *
     * @return immutable fingerprint material without the self-referential fingerprint field
     */
    public Map<String, Object> fingerprintMaterial() {
        return Map.of(
                "schemaVersion", schemaVersion,
                "planFingerprint", planFingerprint,
                "bindingSetFingerprint", bindingSetFingerprint,
                "logicalTime", logicalTime == null ? "" : logicalTime.toString(),
                "randomScopeCursors", randomScopeCursors,
                "uuidScopeCursors", uuidScopeCursors,
                "usages", usages,
                "restorable", restorable,
                "restoreGaps", restoreGaps);
    }

    /**
     * Cumulative payload-free usage state restored with provider cursors.
     *
     * @param service stable execution-service kind
     * @param providerCalls all direct provider calls
     * @param semanticProviderCalls direct calls that can affect business semantics
     * @param functionCalls environment-dependent function invocations declaring this service
     * @param functionCallSites stable structural function call-site ids
     * @param providerScopeFingerprints SHA-256 digests of provider scopes
     */
    public record UsageState(
            String service,
            long providerCalls,
            long semanticProviderCalls,
            long functionCalls,
            List<String> functionCallSites,
            List<String> providerScopeFingerprints
    ) {
        /** Creates immutable lists and rejects impossible cumulative counters. */
        public UsageState {
            service = normalized(service);
            functionCallSites = functionCallSites == null ? List.of() : List.copyOf(functionCallSites);
            providerScopeFingerprints = providerScopeFingerprints == null
                    ? List.of() : List.copyOf(providerScopeFingerprints);
            if (service.isBlank()) {
                throw new IllegalArgumentException("Execution-service usage requires a service");
            }
            if (!Set.of("TIME", "RANDOM", "UUID", "IDENTITY", "FEATURE_FLAG", "SECRET")
                    .contains(service)) {
                throw new IllegalArgumentException("Unsupported execution-service usage kind");
            }
            if (providerCalls < 0 || semanticProviderCalls < 0 || functionCalls < 0
                    || semanticProviderCalls > providerCalls) {
                throw new IllegalArgumentException("Execution-service usage counters are invalid");
            }
            if (new HashSet<>(functionCallSites).size() != functionCallSites.size()) {
                throw new IllegalArgumentException("Function call sites must be unique");
            }
            if (functionCallSites.stream().anyMatch(site -> site == null || site.isBlank()
                    || site.length() > MAX_CALL_SITE_LENGTH)) {
                throw new IllegalArgumentException("Function call sites must be bounded non-blank values");
            }
            if (functionCallSites.size() > MAX_SCOPES
                    || providerScopeFingerprints.size() > MAX_SCOPES) {
                throw new IllegalArgumentException("Execution-service usage scope limit exceeded");
            }
            if (new HashSet<>(providerScopeFingerprints).size()
                    != providerScopeFingerprints.size()
                    || providerScopeFingerprints.stream().anyMatch(value -> !fingerprint(value))) {
                throw new IllegalArgumentException(
                        "Provider scope fingerprints must be unique canonical SHA-256 values");
            }
        }
    }

    private static Map<String, Long> immutableCursors(Map<String, Long> values, String field) {
        Map<String, Long> result = values == null ? Map.of() : Map.copyOf(values);
        if (result.size() > MAX_SCOPES) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_SCOPES + " scopes");
        }
        result.forEach((scope, cursor) -> {
            if (!fingerprint(scope) || cursor == null || cursor <= 0) {
                throw new IllegalArgumentException(
                        field + " requires canonical scope fingerprints and positive cursors");
            }
        });
        return result;
    }

    private static String requiredFingerprint(String value, String field) {
        String result = normalized(value);
        if (!fingerprint(result)) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 fingerprint");
        }
        return result;
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
