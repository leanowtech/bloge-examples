package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, payload-free execution license for one fully resolved ScenarioPack revision.
 *
 * <p>The plan is produced only after the rehearsal compiler resolves and verifies every exact
 * ScenarioCase, CaseHandlingAssertion, TestSuite, FixtureBundle, MirrorPlan, and optional Session
 * checkpoint. Runtime code consumes this artifact instead of joining mutable registries on the
 * execution path.</p>
 *
 * @param schemaVersion exact compiled-plan protocol version
 * @param planId stable compiler-generation identity derived from the ScenarioPack
 * @param revision immutable revision inherited from the ScenarioPack
 * @param fingerprint canonical fingerprint with this field blanked
 * @param scope exact enterprise namespace
 * @param scenarioPackRef exact source ScenarioPack
 * @param targetCapabilityRef exact root capability under rehearsal
 * @param cases ordered compiled case bindings
 * @param assertionRefs exact pack-wide handling-assertion closure
 * @param policy deterministic isolation and budget policy
 */
public record CompiledScenarioRehearsalPlan(
        String schemaVersion,
        String planId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef scenarioPackRef,
        MirrorArtifactRef targetCapabilityRef,
        List<CaseBinding> cases,
        List<MirrorArtifactRef> assertionRefs,
        ScenarioPack.RehearsalPolicy policy
) {
    /** Current compiled rehearsal-plan protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.compiledScenarioRehearsalPlan.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Canonicalizes one complete payload-free execution license. */
    public CompiledScenarioRehearsalPlan {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported compiled rehearsal plan schemaVersion");
        }
        planId = identifier(planId, "planId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "compiled rehearsal plan revision must be positive");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        scenarioPackRef = exactKind(
                scenarioPackRef, "SCENARIO_PACK", "scenarioPackRef");
        targetCapabilityRef = exactKind(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        cases = cases == null ? List.of() : List.copyOf(cases);
        assertionRefs = assertionRefs == null ? List.of() : List.copyOf(assertionRefs);
        policy = Objects.requireNonNull(policy, "policy");
        if (cases.isEmpty() || cases.size() > policy.maximumCases()) {
            throw new IllegalArgumentException(
                    "compiled rehearsal cases must be non-empty and policy bounded");
        }
        Set<MirrorArtifactRef> caseRefs = new HashSet<>();
        Set<MirrorArtifactRef> checkpointRefs = new HashSet<>();
        for (CaseBinding binding : cases) {
            if (binding == null || !caseRefs.add(binding.scenarioCaseRef())) {
                throw new IllegalArgumentException(
                        "compiled rehearsal case bindings must be unique");
            }
            if (binding.sessionCheckpointRef() != null
                    && !checkpointRefs.add(binding.sessionCheckpointRef())) {
                throw new IllegalArgumentException(
                        "compiled rehearsal case Sessions must be isolated");
            }
        }
        if (assertionRefs.isEmpty()
                || new HashSet<>(assertionRefs).size() != assertionRefs.size()) {
            throw new IllegalArgumentException(
                    "compiled rehearsal assertion closure must be non-empty and unique");
        }
    }

    /**
     * Exact runtime coordinates for one business case without its input or fixture values.
     *
     * @param scenarioCaseRef exact ScenarioCase
     * @param caseType business coverage intent
     * @param testSuiteRef exact governed TestSuite
     * @param testCaseId exact case within the suite
     * @param mirrorPlanRef exact reusable MirrorPlan
     * @param fixtureBundleRef exact governed FixtureBundle
     * @param sessionCheckpointRef optional exact isolated Session checkpoint
     * @param executionServices deterministic ambient service bindings
     * @param assertionRefs exact assertions evaluated for this case
     */
    public record CaseBinding(
            MirrorArtifactRef scenarioCaseRef,
            ScenarioCase.CaseType caseType,
            MirrorArtifactRef testSuiteRef,
            String testCaseId,
            MirrorArtifactRef mirrorPlanRef,
            MirrorArtifactRef fixtureBundleRef,
            MirrorArtifactRef sessionCheckpointRef,
            MirrorPlan.ExecutionServices executionServices,
            List<MirrorArtifactRef> assertionRefs
    ) {
        /** Validates one exact payload-free case binding. */
        public CaseBinding {
            scenarioCaseRef = exactKind(
                    scenarioCaseRef, "SCENARIO_CASE", "scenarioCaseRef");
            caseType = Objects.requireNonNull(caseType, "caseType");
            testSuiteRef = exactKind(testSuiteRef, "TEST_SUITE", "testSuiteRef");
            testCaseId = identifier(testCaseId, "testCaseId");
            mirrorPlanRef = exactKind(
                    mirrorPlanRef, "MIRROR_PLAN", "mirrorPlanRef");
            fixtureBundleRef = exactKind(
                    fixtureBundleRef, "FIXTURE_BUNDLE", "fixtureBundleRef");
            if (sessionCheckpointRef != null) {
                sessionCheckpointRef = exactKind(
                        sessionCheckpointRef,
                        "MIRROR_SESSION_CHECKPOINT",
                        "sessionCheckpointRef");
            }
            executionServices = Objects.requireNonNull(
                    executionServices, "executionServices");
            assertionRefs = assertionRefs == null ? List.of() : List.copyOf(assertionRefs);
            if (assertionRefs.isEmpty()
                    || new HashSet<>(assertionRefs).size() != assertionRefs.size()) {
                throw new IllegalArgumentException(
                        "compiled case assertion refs must be non-empty and unique");
            }
        }
    }

    /** @return identical material carrying a replacement canonical fingerprint */
    public CompiledScenarioRehearsalPlan withFingerprint(String value) {
        return new CompiledScenarioRehearsalPlan(
                schemaVersion, planId, revision, value, scope, scenarioPackRef,
                targetCapabilityRef, cases, assertionRefs, policy);
    }

    private static MirrorArtifactRef exactKind(
            MirrorArtifactRef value, String kind, String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(
                    field + " must be an exact " + kind + " ref");
        }
        return value;
    }

    private static String identifier(String value, String field) {
        String normalized = MirrorStateProtocolSupport.required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
