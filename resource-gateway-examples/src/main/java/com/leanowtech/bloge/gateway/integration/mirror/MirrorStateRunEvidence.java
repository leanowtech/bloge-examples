package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Payload-free state-access evidence for one Session-backed mirror DAG run.
 *
 * <p>The artifact binds a complete run to one immutable Session state head and records every
 * stateful resolver access without retaining entity values or business-key components. A consumer
 * can distinguish a live state hit, an absent key that allowed controlled fallback, and a
 * tombstone that terminated precedence. The surrounding {@link MirrorRunEvidence} supplies node,
 * attempt, resolution, and detached-signature closure.</p>
 *
 * @param schemaVersion state run-evidence protocol version
 * @param stateEvidenceFingerprint canonical fingerprint with this field blanked
 * @param runId exact terminal mirror run
 * @param planFingerprint exact sealed mirror plan
 * @param sessionStateRef exact immutable Session state head
 * @param stateModelRef exact state model used to interpret the state head
 * @param stateRevision zero-based committed state revision
 * @param worldFingerprint canonical business-world fingerprint
 * @param logicalClock deterministic Session logical time observed by the run
 * @param mode state access mode; v1 supports only one read-only snapshot
 * @param statefulBindings complete state-backed external binding closure
 * @param accesses ordered payload-free access outcomes
 * @param limitations bounded state-evidence limitations
 */
public record MirrorStateRunEvidence(
        String schemaVersion,
        String stateEvidenceFingerprint,
        String runId,
        String planFingerprint,
        MirrorArtifactRef sessionStateRef,
        MirrorArtifactRef stateModelRef,
        long stateRevision,
        String worldFingerprint,
        Instant logicalClock,
        Mode mode,
        List<StatefulBinding> statefulBindings,
        List<StateAccess> accesses,
        List<String> limitations
) implements MirrorStateEvidence {
    /** Current payload-free state run-evidence version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorStateRunEvidence.v1";
    /** Maximum stateful invocation sites admitted to one run. */
    public static final int MAXIMUM_BINDINGS = MirrorPlan.MAXIMUM_EXTERNAL_BINDINGS;
    /** Maximum state accesses admitted to one run. */
    public static final int MAXIMUM_ACCESSES = MirrorRunEvidence.MAXIMUM_RESOLUTIONS;
    /** Maximum bounded state-evidence limitations. */
    public static final int MAXIMUM_LIMITATIONS = 64;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** State mutation policy observed by the complete run. */
    public enum Mode {
        READ_ONLY_SNAPSHOT
    }

    /** Result of consulting the frozen Session state before any lower-priority resolver. */
    public enum AccessOutcome {
        LIVE_ENTITY,
        ABSENT,
        TOMBSTONED
    }

    /** Validates payload omission, exact references, deterministic ordering, and access closure. */
    public MirrorStateRunEvidence {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror state run-evidence version");
        }
        stateEvidenceFingerprint = optionalFingerprint(
                stateEvidenceFingerprint, "stateEvidenceFingerprint");
        runId = required(runId, "runId", 512);
        planFingerprint = fingerprint(planFingerprint, "planFingerprint");
        sessionStateRef = requireKind(
                sessionStateRef, "SESSION_STATE", "sessionStateRef");
        stateModelRef = requireKind(
                stateModelRef, "STATE_MODEL", "stateModelRef");
        if (stateRevision < 0
                || sessionStateRef.revision() != Math.addExact(stateRevision, 1)) {
            throw new IllegalArgumentException(
                    "sessionStateRef revision must encode stateRevision + 1");
        }
        worldFingerprint = fingerprint(worldFingerprint, "worldFingerprint");
        logicalClock = Objects.requireNonNull(logicalClock, "logicalClock");
        mode = Objects.requireNonNull(mode, "mode");
        statefulBindings = orderedBindings(statefulBindings);
        accesses = orderedAccesses(accesses);
        limitations = orderedStrings(
                limitations, "limitations", MAXIMUM_LIMITATIONS, 512);
        validateAccessClosure(statefulBindings, accesses);
    }

    /** @return a copy carrying a replacement canonical state-evidence fingerprint */
    public MirrorStateRunEvidence withFingerprint(String value) {
        return new MirrorStateRunEvidence(
                schemaVersion, value, runId, planFingerprint,
                sessionStateRef, stateModelRef, stateRevision,
                worldFingerprint, logicalClock, mode,
                statefulBindings, accesses, limitations);
    }

    /**
     * Exact state-backed plan binding.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph owning the invocation
     * @param capabilityRef exact state-backed read capability
     * @param stateReadSpecRef exact query-to-state lowering specification
     */
    public record StatefulBinding(
            String invocationSiteId,
            String graphPath,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef stateReadSpecRef
    ) {
        /** Validates one payload-free stateful binding. */
        public StatefulBinding {
            invocationSiteId = required(
                    invocationSiteId, "invocationSiteId", 2_048);
            graphPath = normalizeGraphPath(graphPath);
            capabilityRef = requireKind(
                    capabilityRef, "CAPABILITY", "capabilityRef");
            stateReadSpecRef = requireKind(
                    stateReadSpecRef, "STATE_READ_SPEC", "stateReadSpecRef");
        }
    }

    /**
     * One payload-free state resolver access.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph owning the invocation
     * @param correlationKey foreach, loop, or business correlation coordinate
     * @param occurrence one-based invocation occurrence
     * @param attempt one-based delegate attempt
     * @param capabilityRef exact state-backed read capability
     * @param stateReadSpecRef exact query-to-state lowering specification
     * @param requestFingerprint canonical invocation input fingerprint
     * @param businessKeyFingerprint canonical ordered business-key component fingerprint
     * @param outcome live, absent, or tombstoned state result
     * @param stateRecordFingerprint entity or tombstone fingerprint; blank only for absent
     * @param projectedOutputFingerprint projected output fingerprint; present only for live
     * @param errorCode stable tombstone error; blank for live or absent
     */
    public record StateAccess(
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef stateReadSpecRef,
            String requestFingerprint,
            String businessKeyFingerprint,
            AccessOutcome outcome,
            String stateRecordFingerprint,
            String projectedOutputFingerprint,
            String errorCode
    ) {
        /** Validates one access without accepting raw state or key material. */
        public StateAccess {
            invocationSiteId = required(
                    invocationSiteId, "invocationSiteId", 2_048);
            graphPath = normalizeGraphPath(graphPath);
            correlationKey = bounded(correlationKey, "correlationKey", 1_024);
            if (occurrence < 1 || attempt < 1) {
                throw new IllegalArgumentException(
                        "state access occurrence and attempt must be positive");
            }
            capabilityRef = requireKind(
                    capabilityRef, "CAPABILITY", "capabilityRef");
            stateReadSpecRef = requireKind(
                    stateReadSpecRef, "STATE_READ_SPEC", "stateReadSpecRef");
            requestFingerprint = fingerprint(
                    requestFingerprint, "requestFingerprint");
            businessKeyFingerprint = fingerprint(
                    businessKeyFingerprint, "businessKeyFingerprint");
            outcome = Objects.requireNonNull(outcome, "outcome");
            stateRecordFingerprint = optionalFingerprint(
                    stateRecordFingerprint, "stateRecordFingerprint");
            projectedOutputFingerprint = optionalFingerprint(
                    projectedOutputFingerprint, "projectedOutputFingerprint");
            errorCode = bounded(errorCode, "errorCode", 256);
            switch (outcome) {
                case LIVE_ENTITY -> {
                    if (stateRecordFingerprint.isBlank()
                            || projectedOutputFingerprint.isBlank()
                            || !errorCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "live state access requires record and output fingerprints");
                    }
                }
                case ABSENT -> {
                    if (!stateRecordFingerprint.isBlank()
                            || !projectedOutputFingerprint.isBlank()
                            || !errorCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "absent state access cannot claim a record, output, or error");
                    }
                }
                case TOMBSTONED -> {
                    if (stateRecordFingerprint.isBlank()
                            || !projectedOutputFingerprint.isBlank()
                            || !MirrorSessionStateError.ENTITY_TOMBSTONED.equals(
                            errorCode)) {
                        throw new IllegalArgumentException(
                                "tombstoned state access requires its record and terminal error");
                    }
                }
            }
        }
    }

    /**
     * Shared stable state error constants kept outside the runtime package.
     *
     * <p>The nested holder avoids making the evidence protocol depend on the resolver
     * implementation class.</p>
     */
    public static final class MirrorSessionStateError {
        /** Stable terminal error emitted when an exact Session entity was deleted. */
        public static final String ENTITY_TOMBSTONED =
                "RG.MIRROR.STATE.ENTITY_TOMBSTONED";

        private MirrorSessionStateError() {
        }
    }

    /** Prevents exact state and business-key fingerprints from entering generic logs. */
    @Override
    public String toString() {
        return "MirrorStateRunEvidence[runId=" + runId
                + ", stateRevision=" + stateRevision
                + ", bindingCount=" + statefulBindings.size()
                + ", accessCount=" + accesses.size()
                + ", mode=" + mode + "]";
    }

    private static List<StatefulBinding> orderedBindings(
            List<StatefulBinding> values) {
        List<StatefulBinding> result = values == null
                ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(
                        value, "statefulBinding"))
                .sorted(Comparator.comparing(
                        StatefulBinding::invocationSiteId)
                        .thenComparing(StatefulBinding::graphPath)
                        .thenComparing(value ->
                                value.capabilityRef().id()))
                .toList();
        boundedSize(result, "statefulBindings", MAXIMUM_BINDINGS);
        requireUnique(result.stream()
                .map(StatefulBinding::invocationSiteId).toList(),
                "statefulBindings");
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "state run evidence requires at least one stateful binding");
        }
        return result;
    }

    private static List<StateAccess> orderedAccesses(
            List<StateAccess> values) {
        List<StateAccess> result = values == null
                ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(value, "stateAccess"))
                .sorted(Comparator.comparing(
                        StateAccess::invocationSiteId)
                        .thenComparing(StateAccess::graphPath)
                        .thenComparing(StateAccess::correlationKey)
                        .thenComparingInt(StateAccess::occurrence)
                        .thenComparingInt(StateAccess::attempt))
                .toList();
        boundedSize(result, "accesses", MAXIMUM_ACCESSES);
        requireUnique(result.stream()
                .map(MirrorStateRunEvidence::accessCoordinate).toList(),
                "accesses");
        return result;
    }

    private static void validateAccessClosure(
            List<StatefulBinding> bindings,
            List<StateAccess> accesses) {
        java.util.Map<String, StatefulBinding> bySite =
                new java.util.LinkedHashMap<>();
        bindings.forEach(binding ->
                bySite.put(binding.invocationSiteId(), binding));
        for (StateAccess access : accesses) {
            StatefulBinding binding = bySite.get(access.invocationSiteId());
            if (binding == null
                    || !binding.graphPath().equals(access.graphPath())
                    || !binding.capabilityRef().equals(
                    access.capabilityRef())
                    || !binding.stateReadSpecRef().equals(
                    access.stateReadSpecRef())) {
                throw new IllegalArgumentException(
                        "state access must match one exact stateful binding");
            }
        }
    }

    private static String accessCoordinate(StateAccess access) {
        return access.invocationSiteId() + '\0'
                + access.correlationKey() + '\0'
                + access.occurrence() + '\0' + access.attempt();
    }

    private static List<String> orderedStrings(
            List<String> values,
            String field,
            int maximumItems,
            int maximumLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        boundedSize(values, field, maximumItems);
        Set<String> normalized = new TreeSet<>();
        for (String value : values) {
            if (!normalized.add(required(
                    value, field + " item", maximumLength))) {
                throw new IllegalArgumentException(
                        field + " must be unique");
            }
        }
        return List.copyOf(normalized);
    }

    private static void boundedSize(
            List<?> values, String field, int maximum) {
        if (values.size() > maximum) {
            throw new IllegalArgumentException(
                    field + " exceeds its item limit");
        }
    }

    private static void requireUnique(
            List<String> values, String field) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(
                    field + " coordinates must be unique");
        }
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return exact;
    }

    private static String normalizeGraphPath(String value) {
        String normalized = required(value, "graphPath", 2_048);
        if (!normalized.startsWith("/")) {
            throw new IllegalArgumentException(
                    "graphPath must start with /");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field, 71);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String required(
            String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String bounded(
            String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " exceeds its length limit");
        }
        return normalized;
    }
}
