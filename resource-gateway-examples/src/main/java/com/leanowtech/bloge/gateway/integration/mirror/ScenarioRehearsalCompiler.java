package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleIntegrityException;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundleIntegrity;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuiteIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure fail-closed compiler from a resolved ScenarioPack closure to one execution license.
 *
 * <p>The compiler repeats integrity and exact-reference checks after repository lookup. It also
 * closes semantic gaps that a portable offline verifier cannot see: TestSuite case membership,
 * FixtureBundle identity and fault selection, MirrorPlan policy equality, root target lineage,
 * lifecycle validity at compilation time, and optional Session checkpoint authority.</p>
 */
public final class ScenarioRehearsalCompiler {
    /** Stable plan-id suffix that isolates future compiler protocol generations. */
    public static final String PLAN_ID_SUFFIX = "@compiled-v1";
    private static final long MIRROR_PLAN_REFERENCE_REVISION = 1L;
    private static final EnumSet<FixtureRule.BehaviorKind> FAULT_BEHAVIORS =
            EnumSet.of(
                    FixtureRule.BehaviorKind.THROW,
                    FixtureRule.BehaviorKind.DELAY,
                    FixtureRule.BehaviorKind.TIMEOUT,
                    FixtureRule.BehaviorKind.DENY);

    private final ObjectMapper mapper;
    private final MirrorSessionCheckpointIntegrityService checkpointIntegrity;

    /**
     * Creates the compiler.
     *
     * @param mapper canonical protocol mapper
     * @param checkpointIntegrity governed checkpoint verifier; may be absent only for stateless packs
     */
    public ScenarioRehearsalCompiler(
            ObjectMapper mapper,
            MirrorSessionCheckpointIntegrityService checkpointIntegrity) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.checkpointIntegrity = checkpointIntegrity;
    }

    /**
     * Compiles a complete exact closure or rejects it before BLOGE can be invoked.
     *
     * @param request fully resolved immutable closure and trusted policy time
     * @return sealed payload-free execution license
     */
    public CompiledScenarioRehearsalPlan compile(
            ScenarioRehearsalCompilationRequest request) {
        Objects.requireNonNull(request, "request");
        ScenarioPack pack = request.pack();
        Instant validatedAt = request.validatedAt();
        verifyPack(pack, validatedAt);
        Map<MirrorArtifactRef, CaseHandlingAssertion> assertions =
                verifyAssertions(pack, request.assertions(), validatedAt);
        List<ScenarioRehearsalCompilationRequest.ResolvedCase> resolvedCases =
                orderedCases(pack, request.cases());

        Map<MirrorArtifactRef, MirrorPlan.ExecutionServices> servicesByPlan =
                new HashMap<>();
        Set<MirrorArtifactRef> checkpointRefs = new HashSet<>();
        List<CompiledScenarioRehearsalPlan.CaseBinding> bindings =
                new ArrayList<>(resolvedCases.size());
        for (ScenarioRehearsalCompilationRequest.ResolvedCase resolved : resolvedCases) {
            bindings.add(compileCase(
                    pack, resolved, assertions, servicesByPlan,
                    checkpointRefs, validatedAt));
        }
        CompiledScenarioRehearsalPlan material =
                new CompiledScenarioRehearsalPlan(
                        "", pack.packId() + PLAN_ID_SUFFIX,
                        pack.revision(), "", pack.scope(),
                        ScenarioPackIntegrity.reference(pack),
                        pack.targetCapabilityRef(),
                        bindings,
                        pack.assertionRefs(),
                        pack.policy());
        return CompiledScenarioRehearsalPlanIntegrity.seal(mapper, material);
    }

    private CompiledScenarioRehearsalPlan.CaseBinding compileCase(
            ScenarioPack pack,
            ScenarioRehearsalCompilationRequest.ResolvedCase resolved,
            Map<MirrorArtifactRef, CaseHandlingAssertion> assertions,
            Map<MirrorArtifactRef, MirrorPlan.ExecutionServices> servicesByPlan,
            Set<MirrorArtifactRef> checkpointRefs,
            Instant validatedAt) {
        ScenarioCase scenarioCase = resolved.scenarioCase();
        try {
            ScenarioPackIntegrity.verifyCase(mapper, scenarioCase);
        } catch (IllegalArgumentException invalid) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.CASE_INTEGRITY_INVALID",
                    "caseRef", safeCaseRef(scenarioCase));
        }
        requireScope(pack.scope(), scenarioCase.scope(), "CASE_SCOPE_INVALID", scenarioCase.caseId());
        requireRef(
                pack.targetCapabilityRef(),
                scenarioCase.targetCapabilityRef(),
                "CASE_TARGET_INVALID",
                scenarioCase.caseId());
        requireLive(
                scenarioCase.lifecycle(), scenarioCase.provenance(),
                scenarioCase.createdAt(), pack.policy().certificationRequired(),
                validatedAt, "CASE_LIFECYCLE_INVALID", scenarioCase.caseId());
        for (MirrorArtifactRef assertionRef : scenarioCase.assertionRefs()) {
            if (!assertions.containsKey(assertionRef)) {
                throw reject(
                        "RG.MIRROR.REHEARSAL.CASE_ASSERTION_CLOSURE_INVALID",
                        "caseId", scenarioCase.caseId());
            }
        }

        StoredTestSuite suite = verifiedSuite(resolved.testSuite(), scenarioCase);
        StoredFixtureBundle storedFixture =
                verifiedFixture(resolved.fixtureBundle(), scenarioCase);
        MirrorPlan mirrorPlan = verifiedPlan(
                resolved.mirrorPlan(), scenarioCase, validatedAt, pack.policy());
        verifyTargetLineage(pack, suite, storedFixture, mirrorPlan, scenarioCase);
        verifyPlanPolicy(pack, mirrorPlan, scenarioCase);
        verifyFixtureRules(storedFixture.bundle(), scenarioCase);
        verifyCheckpoint(
                pack, scenarioCase, mirrorPlan,
                resolved.sessionCheckpoint(), checkpointRefs, validatedAt);

        MirrorPlan.ExecutionServices previous = servicesByPlan.putIfAbsent(
                scenarioCase.mirrorPlanRef(), scenarioCase.executionServices());
        if (previous != null && !previous.equals(scenarioCase.executionServices())) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.EXECUTION_SERVICES_DRIFT",
                    "mirrorPlanId", scenarioCase.mirrorPlanRef().id());
        }
        return new CompiledScenarioRehearsalPlan.CaseBinding(
                ScenarioPackIntegrity.reference(scenarioCase),
                scenarioCase.caseType(),
                scenarioCase.testSuiteRef(),
                scenarioCase.testCaseId(),
                scenarioCase.mirrorPlanRef(),
                scenarioCase.fixtureBundleRef(),
                scenarioCase.sessionCheckpointRef(),
                scenarioCase.executionServices(),
                scenarioCase.assertionRefs());
    }

    private StoredTestSuite verifiedSuite(
            StoredTestSuite value, ScenarioCase scenarioCase) {
        StoredTestSuite suite;
        try {
            suite = StoredTestSuiteIntegrity.verifiedSnapshot(
                    mapper, value,
                    scenarioCase.scope().tenantId(),
                    scenarioCase.scope().environmentId(),
                    scenarioCase.testSuiteRef().id(),
                    scenarioCase.testSuiteRef().revision());
        } catch (RuntimeException invalid) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.TEST_SUITE_INTEGRITY_INVALID",
                    "caseId", scenarioCase.caseId());
        }
        requireRef(
                scenarioCase.testSuiteRef(),
                new MirrorArtifactRef(
                        "TEST_SUITE", suite.suiteId(), suite.revision(),
                        suite.fingerprint()),
                "TEST_SUITE_REF_INVALID",
                scenarioCase.caseId());
        long matches = suite.suite().cases().stream()
                .filter(candidate -> scenarioCase.testCaseId().equals(candidate.caseId()))
                .count();
        if (matches != 1) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.TEST_CASE_MEMBERSHIP_INVALID",
                    "caseId", scenarioCase.caseId());
        }
        TestSuite.TestCase testCase = suite.suite().cases().stream()
                .filter(candidate -> scenarioCase.testCaseId().equals(candidate.caseId()))
                .findFirst()
                .orElseThrow();
        MirrorArtifactRef fixtureRef = new MirrorArtifactRef(
                "FIXTURE_BUNDLE",
                testCase.fixtureBundleRef().fixtureBundleId(),
                testCase.fixtureBundleRef().revision(),
                testCase.fixtureBundleRef().fingerprint());
        requireRef(
                scenarioCase.fixtureBundleRef(),
                fixtureRef,
                "TEST_CASE_FIXTURE_REF_INVALID",
                scenarioCase.caseId());
        return suite;
    }

    private StoredFixtureBundle verifiedFixture(
            StoredFixtureBundle value, ScenarioCase scenarioCase) {
        try {
            StoredFixtureBundle fixture =
                    StoredFixtureBundleIntegrity.verifiedSnapshot(
                            mapper, value,
                            scenarioCase.scope().tenantId(),
                            scenarioCase.scope().environmentId(),
                            scenarioCase.fixtureBundleRef().id(),
                            scenarioCase.fixtureBundleRef().revision());
            requireRef(
                    scenarioCase.fixtureBundleRef(),
                    new MirrorArtifactRef(
                            "FIXTURE_BUNDLE",
                            fixture.fixtureBundleId(),
                            fixture.revision(),
                            fixture.fingerprint()),
                    "FIXTURE_REF_INVALID",
                    scenarioCase.caseId());
            return fixture;
        } catch (FixtureBundleIntegrityException invalid) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.FIXTURE_INTEGRITY_INVALID",
                    "caseId", scenarioCase.caseId());
        }
    }

    private MirrorPlan verifiedPlan(
            MirrorPlan value,
            ScenarioCase scenarioCase,
            Instant validatedAt,
            ScenarioPack.RehearsalPolicy packPolicy) {
        try {
            MirrorPlanIntegrity.verify(mapper, value);
        } catch (RuntimeException invalid) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.MIRROR_PLAN_INTEGRITY_INVALID",
                    "caseId", scenarioCase.caseId());
        }
        MirrorArtifactRef actual = new MirrorArtifactRef(
                "MIRROR_PLAN",
                value.planId(),
                MIRROR_PLAN_REFERENCE_REVISION,
                value.planFingerprint());
        requireRef(
                scenarioCase.mirrorPlanRef(), actual,
                "MIRROR_PLAN_REF_INVALID", scenarioCase.caseId());
        requireScope(
                scenarioCase.scope(), value.scope(),
                "MIRROR_PLAN_SCOPE_INVALID", scenarioCase.caseId());
        if (value.scenarioPackRef() != null) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.CYCLIC_PLAN_BINDING_INVALID",
                    "mirrorPlanId", value.planId());
        }
        Instant requiredUntil;
        try {
            requiredUntil = validatedAt.plus(packPolicy.totalTimeout());
        } catch (RuntimeException overflow) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.TIME_BOUNDS_INVALID",
                    "caseId", scenarioCase.caseId());
        }
        if (value.compiledAt().isAfter(validatedAt)
                || value.expiresAt().isBefore(requiredUntil)) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.MIRROR_PLAN_EXPIRED",
                    "caseId", scenarioCase.caseId());
        }
        return value;
    }

    private void verifyTargetLineage(
            ScenarioPack pack,
            StoredTestSuite suite,
            StoredFixtureBundle fixture,
            MirrorPlan plan,
            ScenarioCase scenarioCase) {
        requireRef(
                pack.targetCapabilityRef(),
                plan.rootCapability(),
                "MIRROR_PLAN_TARGET_INVALID",
                scenarioCase.caseId());
        CapabilitySnapshot root = plan.capabilityClosure().stream()
                .filter(snapshot -> snapshot.capabilityId().equals(
                                plan.rootCapability().id())
                        && snapshot.revision() == plan.rootCapability().revision()
                        && snapshot.fingerprint().equals(
                                plan.rootCapability().fingerprint()))
                .findFirst()
                .orElseThrow(() -> reject(
                        "RG.MIRROR.REHEARSAL.ROOT_CAPABILITY_MISSING",
                        "caseId", scenarioCase.caseId()));
        TestSuite.Target target = suite.suite().target();
        String expectedKind = root.source().sourceKind().name();
        if (!expectedKind.equals(target.kind())
                || !root.source().sourceRef().equals(target.id())
                || !root.source().sourceFingerprint().equals(target.fingerprint())
                || !target.fingerprint().equals(
                        fixture.bundle().targetFingerprint())) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.TARGET_LINEAGE_INVALID",
                    "caseId", scenarioCase.caseId());
        }
        requireClassification(
                suite.suite().classification(),
                pack.policy().maximumClassification(),
                "TEST_SUITE_CLASSIFICATION_FORBIDDEN",
                scenarioCase.caseId());
        requireClassification(
                fixture.bundle().classification(),
                pack.policy().maximumClassification(),
                "FIXTURE_CLASSIFICATION_FORBIDDEN",
                scenarioCase.caseId());
    }

    private void verifyPlanPolicy(
            ScenarioPack pack, MirrorPlan plan, ScenarioCase scenarioCase) {
        MirrorPlan.ExecutionPolicy actual = plan.policy();
        ScenarioPack.RehearsalPolicy expected = pack.policy();
        if (actual.realExternalCallsAllowed()
                || actual.externalCredentialsAllowed()
                || actual.networkEgressAllowed()
                || actual.maximumInvocations()
                != expected.maximumInvocationsPerCase()
                || !actual.timeout().equals(expected.caseTimeout())
                || actual.certificationRequired()
                != expected.certificationRequired()
                || actual.maximumClassification()
                != expected.maximumClassification()
                || !actual.allowedRegions().equals(expected.allowedRegions())
                || !plan.executionServices().equals(
                        scenarioCase.executionServices())
                || !plan.fixtureBundleRef().equals(
                        scenarioCase.fixtureBundleRef())
                || !plan.stateModelRefs().equals(pack.stateModelRefs())) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.PLAN_POLICY_DRIFT",
                    "caseId", scenarioCase.caseId());
        }
    }

    private void verifyFixtureRules(
            FixtureBundle fixture, ScenarioCase scenarioCase) {
        Map<String, FixtureRule> rules = fixture.rules().stream()
                .collect(Collectors.toMap(
                        FixtureRule::ruleId,
                        Function.identity(),
                        (left, right) -> {
                            throw reject(
                                    "RG.MIRROR.REHEARSAL.FIXTURE_RULE_ID_DUPLICATE",
                                    "caseId", scenarioCase.caseId());
                        },
                        LinkedHashMap::new));
        Set<String> faultRules = rules.values().stream()
                .filter(rule -> FAULT_BEHAVIORS.contains(rule.behavior().kind()))
                .map(FixtureRule::ruleId)
                .collect(Collectors.toSet());
        Set<String> selected = Set.copyOf(scenarioCase.faultRuleRefs());
        if (!faultRules.equals(selected)) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.FAULT_SELECTION_INVALID",
                    "caseId", scenarioCase.caseId());
        }
    }

    private void verifyCheckpoint(
            ScenarioPack pack,
            ScenarioCase scenarioCase,
            MirrorPlan plan,
            MirrorSessionCheckpointBundle bundle,
            Set<MirrorArtifactRef> checkpointRefs,
            Instant validatedAt) {
        if (scenarioCase.sessionCheckpointRef() == null) {
            if (bundle != null) {
                throw reject(
                        "RG.MIRROR.REHEARSAL.UNEXPECTED_CHECKPOINT",
                        "caseId", scenarioCase.caseId());
            }
            return;
        }
        if (bundle == null || checkpointIntegrity == null
                || checkpointIntegrity.verify(bundle)
                != MirrorSessionCheckpointIntegrityService.Verification.VERIFIED) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.CHECKPOINT_INTEGRITY_INVALID",
                    "caseId", scenarioCase.caseId());
        }
        MirrorSessionCheckpoint checkpoint = bundle.checkpoint();
        MirrorArtifactRef actual;
        try {
            actual = ScenarioPackIntegrity.reference(bundle);
        } catch (IllegalArgumentException invalid) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.CHECKPOINT_REVISION_INVALID",
                    "caseId", scenarioCase.caseId());
        }
        requireRef(
                scenarioCase.sessionCheckpointRef(),
                actual,
                "CHECKPOINT_REF_INVALID",
                scenarioCase.caseId());
        if (!checkpointRefs.add(actual)
                || !checkpoint.scope().equals(pack.scope())
                || !checkpoint.planFingerprint().equals(plan.planFingerprint())
                || !checkpoint.logicalClock().equals(
                        scenarioCase.executionServices().logicalClock())
                || !plan.stateModelRefs().contains(checkpoint.stateModelRef())
                || !pack.writeEffectRefs().equals(checkpoint.writeEffectRefs())
                || !checkpoint.sessionExpiresAt().isAfter(
                        validatedAt.plus(pack.policy().totalTimeout()))) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.CHECKPOINT_CLOSURE_INVALID",
                    "caseId", scenarioCase.caseId());
        }
    }

    private Map<MirrorArtifactRef, CaseHandlingAssertion> verifyAssertions(
            ScenarioPack pack,
            List<CaseHandlingAssertion> supplied,
            Instant validatedAt) {
        Map<MirrorArtifactRef, CaseHandlingAssertion> indexed = new HashMap<>();
        for (CaseHandlingAssertion assertion : supplied) {
            try {
                ScenarioPackIntegrity.verifyAssertion(mapper, assertion);
            } catch (RuntimeException invalid) {
                throw reject(
                        "RG.MIRROR.REHEARSAL.ASSERTION_INTEGRITY_INVALID",
                        "assertionId",
                        assertion == null ? "" : assertion.assertionId());
            }
            MirrorArtifactRef ref = ScenarioPackIntegrity.reference(assertion);
            if (indexed.putIfAbsent(ref, assertion) != null) {
                throw reject(
                        "RG.MIRROR.REHEARSAL.ASSERTION_DUPLICATE",
                        "assertionId", assertion.assertionId());
            }
            requireScope(
                    pack.scope(), assertion.scope(),
                    "ASSERTION_SCOPE_INVALID", assertion.assertionId());
            requireLive(
                    assertion.lifecycle(), assertion.provenance(),
                    assertion.createdAt(), pack.policy().certificationRequired(),
                    validatedAt, "ASSERTION_LIFECYCLE_INVALID",
                    assertion.assertionId());
        }
        if (!indexed.keySet().equals(Set.copyOf(pack.assertionRefs()))) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.ASSERTION_CLOSURE_INVALID",
                    "packId", pack.packId());
        }
        return Map.copyOf(indexed);
    }

    private List<ScenarioRehearsalCompilationRequest.ResolvedCase> orderedCases(
            ScenarioPack pack,
            List<ScenarioRehearsalCompilationRequest.ResolvedCase> supplied) {
        Map<MirrorArtifactRef, ScenarioRehearsalCompilationRequest.ResolvedCase> indexed =
                new HashMap<>();
        for (ScenarioRehearsalCompilationRequest.ResolvedCase resolved : supplied) {
            MirrorArtifactRef ref;
            try {
                ref = ScenarioPackIntegrity.reference(resolved.scenarioCase());
            } catch (RuntimeException invalid) {
                throw reject(
                        "RG.MIRROR.REHEARSAL.CASE_INTEGRITY_INVALID",
                        "caseRef", "");
            }
            if (indexed.putIfAbsent(ref, resolved) != null) {
                throw reject(
                        "RG.MIRROR.REHEARSAL.CASE_DUPLICATE",
                        "caseId", ref.id());
            }
        }
        if (!indexed.keySet().equals(Set.copyOf(pack.caseRefs()))) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.CASE_CLOSURE_INVALID",
                    "packId", pack.packId());
        }
        return pack.caseRefs().stream().map(indexed::get).toList();
    }

    private void verifyPack(ScenarioPack pack, Instant validatedAt) {
        try {
            ScenarioPackIntegrity.verify(mapper, pack);
        } catch (RuntimeException invalid) {
            throw reject(
                    "RG.MIRROR.REHEARSAL.PACK_INTEGRITY_INVALID",
                    "packId", pack == null ? "" : pack.packId());
        }
        requireLive(
                pack.lifecycle(), pack.provenance(), pack.createdAt(),
                pack.policy().certificationRequired(), validatedAt,
                "PACK_LIFECYCLE_INVALID", pack.packId());
    }

    private static void requireLive(
            CapabilitySnapshot.Lifecycle lifecycle,
            ArtifactProvenance provenance,
            Instant createdAt,
            boolean certificationRequired,
            Instant validatedAt,
            String reason,
            String artifactId) {
        boolean invalidLifecycle = lifecycle == CapabilitySnapshot.Lifecycle.STALE
                || lifecycle == CapabilitySnapshot.Lifecycle.REVOKED
                || certificationRequired
                && lifecycle != CapabilitySnapshot.Lifecycle.ACTIVE;
        boolean invalidProvenance = createdAt.isAfter(validatedAt)
                || !provenance.revocationRef().isBlank()
                || provenance.expiresAt() != null
                && !provenance.expiresAt().isAfter(validatedAt)
                || certificationRequired
                && (provenance.approvedAt() == null
                || provenance.approvedAt().isAfter(validatedAt));
        if (invalidLifecycle || invalidProvenance) {
            throw reject(
                    "RG.MIRROR.REHEARSAL." + reason,
                    "artifactId", artifactId);
        }
    }

    private static void requireClassification(
            String actual,
            CapabilityContract.DataClassification maximum,
            String reason,
            String caseId) {
        CapabilityContract.DataClassification classification;
        try {
            classification = CapabilityContract.DataClassification.valueOf(
                    actual == null ? "" : actual.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw reject(
                    "RG.MIRROR.REHEARSAL." + reason,
                    "caseId", caseId);
        }
        if (classification.ordinal() > maximum.ordinal()) {
            throw reject(
                    "RG.MIRROR.REHEARSAL." + reason,
                    "caseId", caseId);
        }
    }

    private static void requireScope(
            CapabilitySnapshot.Scope expected,
            CapabilitySnapshot.Scope actual,
            String reason,
            String artifactId) {
        if (!expected.equals(actual)) {
            throw reject(
                    "RG.MIRROR.REHEARSAL." + reason,
                    "artifactId", artifactId);
        }
    }

    private static void requireRef(
            MirrorArtifactRef expected,
            MirrorArtifactRef actual,
            String reason,
            String artifactId) {
        if (!expected.equals(actual)) {
            throw reject(
                    "RG.MIRROR.REHEARSAL." + reason,
                    "artifactId", artifactId);
        }
    }

    private static ScenarioRehearsalRejectedException reject(
            String code, String key, Object value) {
        return new ScenarioRehearsalRejectedException(
                code, Map.of(key, value == null ? "" : value));
    }

    private static String safeCaseRef(ScenarioCase scenarioCase) {
        return scenarioCase == null ? "" : scenarioCase.caseId();
    }
}
