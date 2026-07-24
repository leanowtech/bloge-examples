package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable governed aggregate of exact business scenarios and rehearsal policy.
 *
 * <p>The pack references content-addressed ScenarioCase and CaseHandlingAssertion revisions. It
 * does not copy test inputs, fixture payloads, executable DSL, or mutable registry aliases. A
 * rehearsal compiler must resolve the complete closure and prove its scope, target, plan,
 * fixture, Session, time, fault, and assertion consistency before any case can run.</p>
 *
 * @param schemaVersion exact scenario-pack wire version
 * @param packId stable pack identity inside its enterprise scope
 * @param revision positive immutable revision
 * @param fingerprint canonical fingerprint with this field blanked
 * @param scope exact enterprise namespace
 * @param targetCapabilityRef exact root capability under rehearsal
 * @param caseRefs ordered business scenario closure
 * @param assertionRefs pack-wide handling assertions
 * @param writeEffectRefs exact virtual write effects admitted by stateful cases
 * @param corpusSnapshotRef optional exact recorded-corpus snapshot
 * @param stateModelRefs exact state models admitted by stateful cases
 * @param policy deterministic fail-closed rehearsal policy
 * @param provenance owner, approval, and lineage facts
 * @param lifecycle governed artifact lifecycle
 * @param createdAt immutable creation time
 */
public record ScenarioPack(
        String schemaVersion,
        String packId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef targetCapabilityRef,
        List<MirrorArtifactRef> caseRefs,
        List<MirrorArtifactRef> assertionRefs,
        List<MirrorArtifactRef> writeEffectRefs,
        MirrorArtifactRef corpusSnapshotRef,
        List<MirrorArtifactRef> stateModelRefs,
        RehearsalPolicy policy,
        ArtifactProvenance provenance,
        CapabilitySnapshot.Lifecycle lifecycle,
        Instant createdAt
) {
    /** Current immutable scenario-pack protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.scenarioPack.v1";
    /** Maximum business cases in one generation-one synchronous pack. */
    public static final int MAXIMUM_CASES = 256;
    /** Maximum pack-wide assertions in one generation-one pack. */
    public static final int MAXIMUM_ASSERTIONS = 512;
    /** Maximum BLOGE operator occurrences admitted for one case. */
    public static final int MAXIMUM_INVOCATIONS_PER_CASE = 1_000_000;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Normalizes references while preserving case order as business reporting order. */
    public ScenarioPack {
        schemaVersion = version(schemaVersion);
        packId = identifier(packId, "packId");
        if (revision < 1) {
            throw new IllegalArgumentException("scenario pack revision must be positive");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        targetCapabilityRef = exactKind(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        caseRefs = orderedRefs(
                caseRefs, "SCENARIO_CASE", 1, MAXIMUM_CASES, "caseRefs");
        assertionRefs = sortedRefs(
                assertionRefs,
                "CASE_HANDLING_ASSERTION",
                1,
                MAXIMUM_ASSERTIONS,
                "assertionRefs");
        writeEffectRefs = sortedRefs(
                writeEffectRefs, "WRITE_EFFECT", 0, 256, "writeEffectRefs");
        if (corpusSnapshotRef != null) {
            corpusSnapshotRef = exactKind(
                    corpusSnapshotRef, "CORPUS_SNAPSHOT", "corpusSnapshotRef");
        }
        stateModelRefs = sortedRefs(
                stateModelRefs, "STATE_MODEL", 0, 256, "stateModelRefs");
        policy = Objects.requireNonNull(policy, "policy");
        provenance = Objects.requireNonNull(provenance, "provenance");
        lifecycle = lifecycle == null ? CapabilitySnapshot.Lifecycle.DRAFT : lifecycle;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");

        if (caseRefs.size() > policy.maximumCases()) {
            throw new IllegalArgumentException(
                    "scenario pack case count exceeds its rehearsal policy");
        }
        if (writeEffectRefs.isEmpty() != stateModelRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "state models and write effects must either both be absent or both be present");
        }
    }

    /**
     * Deterministic, fail-closed generation-one rehearsal policy.
     *
     * @param scheduling fixed sequential scheduling policy
     * @param isolatedCaseSessions must be true so mutable state never crosses case boundaries
     * @param realExternalCallsAllowed must be false
     * @param externalCredentialsAllowed must be false
     * @param networkEgressAllowed must be false
     * @param evidenceMode fixed payload-free evidence mode
     * @param maximumCases bounded case count admitted from the pack
     * @param maximumInvocationsPerCase bounded BLOGE operator occurrence budget
     * @param caseTimeout positive per-case logical timeout
     * @param totalTimeout positive aggregate logical timeout
     * @param certificationRequired whether non-certifiable evidence fails the rehearsal
     * @param maximumClassification highest data classification admitted by policy
     * @param allowedRegions explicit execution and residency allowlist
     */
    public record RehearsalPolicy(
            Scheduling scheduling,
            boolean isolatedCaseSessions,
            boolean realExternalCallsAllowed,
            boolean externalCredentialsAllowed,
            boolean networkEgressAllowed,
            EvidenceMode evidenceMode,
            int maximumCases,
            int maximumInvocationsPerCase,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Duration caseTimeout,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Duration totalTimeout,
            boolean certificationRequired,
            CapabilityContract.DataClassification maximumClassification,
            List<String> allowedRegions
    ) {
        /** Enforces deterministic sequential execution and a non-production isolation boundary. */
        public RehearsalPolicy {
            scheduling = scheduling == null ? Scheduling.SEQUENTIAL : scheduling;
            evidenceMode = evidenceMode == null ? EvidenceMode.HASH_ONLY : evidenceMode;
            caseTimeout = Objects.requireNonNull(caseTimeout, "caseTimeout");
            totalTimeout = Objects.requireNonNull(totalTimeout, "totalTimeout");
            maximumClassification = Objects.requireNonNull(
                    maximumClassification, "maximumClassification");
            allowedRegions = allowedRegions == null ? List.of() : allowedRegions.stream()
                    .map(ScenarioPack::normalizedRegion)
                    .distinct()
                    .sorted()
                    .toList();
            if (scheduling != Scheduling.SEQUENTIAL
                    || !isolatedCaseSessions
                    || realExternalCallsAllowed
                    || externalCredentialsAllowed
                    || networkEgressAllowed
                    || evidenceMode != EvidenceMode.HASH_ONLY
                    || maximumCases < 1
                    || maximumCases > MAXIMUM_CASES
                    || maximumInvocationsPerCase < 1
                    || maximumInvocationsPerCase
                    > MAXIMUM_INVOCATIONS_PER_CASE
                    || caseTimeout.isZero()
                    || caseTimeout.isNegative()
                    || totalTimeout.isZero()
                    || totalTimeout.isNegative()
                    || totalTimeout.compareTo(caseTimeout) < 0
                    || allowedRegions.isEmpty()) {
                throw new IllegalArgumentException(
                        "rehearsal policy violates deterministic isolation bounds");
            }
        }
    }

    /** Generation-one scheduling contract. */
    public enum Scheduling {
        SEQUENTIAL
    }

    /** Generation-one portable evidence contract. */
    public enum EvidenceMode {
        HASH_ONLY
    }

    /** @return identical pack material carrying a replacement canonical fingerprint */
    public ScenarioPack withFingerprint(String value) {
        return new ScenarioPack(
                schemaVersion, packId, revision, value, scope, targetCapabilityRef,
                caseRefs, assertionRefs, writeEffectRefs, corpusSnapshotRef,
                stateModelRefs, policy, provenance, lifecycle, createdAt);
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported scenario pack schemaVersion: " + normalized);
        }
        return normalized;
    }

    private static String identifier(String value, String field) {
        String normalized = MirrorStateProtocolSupport.required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String normalizedRegion(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 128) {
            throw new IllegalArgumentException("allowed region is invalid");
        }
        return normalized;
    }

    private static MirrorArtifactRef exactKind(
            MirrorArtifactRef value, String kind, String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(field + " must be an exact " + kind + " ref");
        }
        return value;
    }

    private static List<MirrorArtifactRef> orderedRefs(
            List<MirrorArtifactRef> values,
            String kind,
            int minimum,
            int maximum,
            String field) {
        List<MirrorArtifactRef> normalized = values == null ? List.of() : values.stream()
                .map(value -> exactKind(value, kind, field))
                .toList();
        return validateRefs(normalized, minimum, maximum, field);
    }

    private static List<MirrorArtifactRef> sortedRefs(
            List<MirrorArtifactRef> values,
            String kind,
            int minimum,
            int maximum,
            String field) {
        List<MirrorArtifactRef> normalized = values == null ? List.of() : values.stream()
                .map(value -> exactKind(value, kind, field))
                .sorted(Comparator.comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        return validateRefs(normalized, minimum, maximum, field);
    }

    private static List<MirrorArtifactRef> validateRefs(
            List<MirrorArtifactRef> values, int minimum, int maximum, String field) {
        Set<MirrorArtifactRef> unique = new HashSet<>(values);
        if (unique.size() != values.size()
                || values.size() < minimum
                || values.size() > maximum) {
            throw new IllegalArgumentException(field + " must be unique and bounded");
        }
        return List.copyOf(values);
    }
}
