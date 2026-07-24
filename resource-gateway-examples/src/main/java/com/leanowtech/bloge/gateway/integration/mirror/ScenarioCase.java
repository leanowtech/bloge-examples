package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable business-scenario binding over existing governed testing and mirror artifacts.
 *
 * <p>A scenario case is deliberately not a second test-case format. Business input and invocation
 * fixture values remain owned by the exact {@code TEST_SUITE} case and {@code FIXTURE_BUNDLE}
 * revision. This artifact freezes only the business intent, exact mirror plan, deterministic
 * ambient services, optional isolated Session checkpoint, fixture fault rules, and handling
 * assertions used by rehearsal.</p>
 *
 * @param schemaVersion exact scenario-case wire version
 * @param caseId stable case identity inside its enterprise scope
 * @param revision positive immutable revision
 * @param fingerprint canonical fingerprint with this field blanked
 * @param scope exact enterprise namespace
 * @param caseType business coverage intent
 * @param targetCapabilityRef exact capability under rehearsal
 * @param testSuiteRef exact existing governed test-suite revision
 * @param testCaseId exact case id inside the governed test suite
 * @param mirrorPlanRef exact mirror-plan generation
 * @param fixtureBundleRef exact fixture revision already referenced by the test case and plan
 * @param sessionCheckpointRef optional exact isolated Session checkpoint for stateful rehearsal
 * @param executionServices deterministic logical time, randomness, identity, and feature flags
 * @param faultRuleRefs exact fixture rule ids that express injected faults for this case
 * @param assertionRefs exact handling assertions evaluated over payload-free run evidence
 * @param provenance owner, approval, and lineage facts
 * @param lifecycle governed artifact lifecycle
 * @param createdAt immutable creation time
 */
public record ScenarioCase(
        String schemaVersion,
        String caseId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        CaseType caseType,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef testSuiteRef,
        String testCaseId,
        MirrorArtifactRef mirrorPlanRef,
        MirrorArtifactRef fixtureBundleRef,
        MirrorArtifactRef sessionCheckpointRef,
        MirrorPlan.ExecutionServices executionServices,
        List<String> faultRuleRefs,
        List<MirrorArtifactRef> assertionRefs,
        ArtifactProvenance provenance,
        CapabilitySnapshot.Lifecycle lifecycle,
        Instant createdAt
) {
    /** Current immutable scenario-case protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.scenarioCase.v1";
    /** Maximum fault rules selected from one exact FixtureBundle. */
    public static final int MAXIMUM_FAULT_RULES = 128;
    /** Maximum handling assertions attached to one case. */
    public static final int MAXIMUM_ASSERTIONS = 256;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Normalizes deterministic collections and rejects incomplete execution bindings. */
    public ScenarioCase {
        schemaVersion = version(schemaVersion);
        caseId = identifier(caseId, "caseId");
        if (revision < 1) {
            throw new IllegalArgumentException("scenario case revision must be positive");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        caseType = Objects.requireNonNull(caseType, "caseType");
        targetCapabilityRef = exactKind(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        testSuiteRef = exactKind(testSuiteRef, "TEST_SUITE", "testSuiteRef");
        testCaseId = identifier(testCaseId, "testCaseId");
        mirrorPlanRef = exactKind(mirrorPlanRef, "MIRROR_PLAN", "mirrorPlanRef");
        fixtureBundleRef = exactKind(
                fixtureBundleRef, "FIXTURE_BUNDLE", "fixtureBundleRef");
        if (sessionCheckpointRef != null) {
            sessionCheckpointRef = exactKind(
                    sessionCheckpointRef,
                    "MIRROR_SESSION_CHECKPOINT",
                    "sessionCheckpointRef");
        }
        executionServices = Objects.requireNonNull(executionServices, "executionServices");
        faultRuleRefs = normalizeIdentifiers(
                faultRuleRefs, MAXIMUM_FAULT_RULES, "faultRuleRefs");
        assertionRefs = normalizeRefs(
                assertionRefs,
                "CASE_HANDLING_ASSERTION",
                1,
                MAXIMUM_ASSERTIONS,
                "assertionRefs");
        provenance = Objects.requireNonNull(provenance, "provenance");
        lifecycle = lifecycle == null ? CapabilitySnapshot.Lifecycle.DRAFT : lifecycle;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");

        if (caseType == CaseType.FAULT && faultRuleRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "FAULT scenario case requires at least one fixture fault rule");
        }
        if (caseType != CaseType.FAULT && !faultRuleRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "fixture fault rules are admitted only by a FAULT scenario case");
        }
        if (caseType == CaseType.STATE_TRANSITION && sessionCheckpointRef == null) {
            throw new IllegalArgumentException(
                    "STATE_TRANSITION scenario case requires an isolated Session checkpoint");
        }
    }

    /** Business coverage intents supported by the first rehearsal protocol. */
    public enum CaseType {
        GOLDEN,
        NEGATIVE,
        BOUNDARY,
        REGRESSION,
        FAULT,
        STATE_TRANSITION,
        WHAT_IF
    }

    /** @return identical case material carrying a replacement canonical fingerprint */
    public ScenarioCase withFingerprint(String value) {
        return new ScenarioCase(
                schemaVersion, caseId, revision, value, scope, caseType,
                targetCapabilityRef, testSuiteRef, testCaseId, mirrorPlanRef,
                fixtureBundleRef, sessionCheckpointRef, executionServices,
                faultRuleRefs, assertionRefs, provenance, lifecycle, createdAt);
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported scenario case schemaVersion: " + normalized);
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

    private static MirrorArtifactRef exactKind(
            MirrorArtifactRef value, String kind, String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(field + " must be an exact " + kind + " ref");
        }
        return value;
    }

    private static List<String> normalizeIdentifiers(
            List<String> values, int maximum, String field) {
        List<String> normalized = values == null ? List.of() : values.stream()
                .map(value -> identifier(value, field))
                .distinct()
                .sorted()
                .toList();
        if (normalized.size() != (values == null ? 0 : values.size())
                || normalized.size() > maximum) {
            throw new IllegalArgumentException(field + " must be unique and bounded");
        }
        return normalized;
    }

    private static List<MirrorArtifactRef> normalizeRefs(
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
        Set<MirrorArtifactRef> unique = new HashSet<>(normalized);
        if (unique.size() != normalized.size()
                || normalized.size() < minimum
                || normalized.size() > maximum) {
            throw new IllegalArgumentException(field + " must be unique and bounded");
        }
        return List.copyOf(normalized);
    }
}
