package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Online resolver and compiler for governed ScenarioPack rehearsal plans.
 *
 * <p>The service resolves every immutable dependency through its owning application boundary,
 * delegates cross-artifact proof to {@link ScenarioRehearsalCompiler}, and persists only the
 * resulting payload-free execution license. No TestSuite input or FixtureBundle value is copied
 * into the compiled-plan store.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class ScenarioRehearsalIntegrationService {
    private final ScenarioArtifactRegistryService scenarioArtifacts;
    private final TestSuiteRegistryService testSuites;
    private final FixtureBundleRepository fixtures;
    private final MirrorPlanIntegrationService mirrorPlans;
    private final ScenarioRehearsalCompiler compiler;
    private final CompiledScenarioRehearsalPlanRepository compiledPlans;
    private final Clock clock;

    /** Creates the online compiler with the trusted server clock. */
    @Autowired
    public ScenarioRehearsalIntegrationService(
            ScenarioArtifactRegistryService scenarioArtifacts,
            TestSuiteRegistryService testSuites,
            FixtureBundleRepository fixtures,
            MirrorPlanIntegrationService mirrorPlans,
            ScenarioRehearsalCompiler compiler,
            CompiledScenarioRehearsalPlanRepository compiledPlans) {
        this(
                scenarioArtifacts,
                testSuites,
                fixtures,
                mirrorPlans,
                compiler,
                compiledPlans,
                Clock.systemUTC());
    }

    /** Full constructor for deterministic application-service tests. */
    public ScenarioRehearsalIntegrationService(
            ScenarioArtifactRegistryService scenarioArtifacts,
            TestSuiteRegistryService testSuites,
            FixtureBundleRepository fixtures,
            MirrorPlanIntegrationService mirrorPlans,
            ScenarioRehearsalCompiler compiler,
            CompiledScenarioRehearsalPlanRepository compiledPlans,
            Clock clock) {
        this.scenarioArtifacts = Objects.requireNonNull(
                scenarioArtifacts, "scenarioArtifacts");
        this.testSuites = Objects.requireNonNull(testSuites, "testSuites");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.mirrorPlans = Objects.requireNonNull(mirrorPlans, "mirrorPlans");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.compiledPlans = Objects.requireNonNull(
                compiledPlans, "compiledPlans");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolves and compiles one exact ScenarioPack revision.
     *
     * @param packId stable ScenarioPack id
     * @param revision exact immutable revision
     * @param fingerprint reviewed pack fingerprint
     * @param identity authenticated mirror identity
     * @return persisted compiler-issued execution license
     */
    @Transactional
    public CompiledScenarioRehearsalPlan compile(
            String packId,
            long revision,
            String fingerprint,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        ScenarioPack pack = scenarioArtifacts.findPack(
                packId, revision, fingerprint, identity);
        List<CaseHandlingAssertion> assertions =
                pack.assertionRefs().stream()
                        .map(ref -> scenarioArtifacts.requireAssertion(
                                scope, ref, identity))
                        .toList();
        List<ScenarioRehearsalCompilationRequest.ResolvedCase> cases =
                new ArrayList<>(pack.caseRefs().size());
        for (MirrorArtifactRef caseRef : pack.caseRefs()) {
            ScenarioCase scenarioCase =
                    scenarioArtifacts.requireCase(
                            scope, caseRef, identity);
            StoredTestSuite suite = testSuites.find(
                    scenarioCase.testSuiteRef().id(),
                    scenarioCase.testSuiteRef().revision(),
                    identity);
            StoredFixtureBundle fixture = requireFixture(
                    scenarioCase.fixtureBundleRef(), identity);
            MirrorPlan mirrorPlan = mirrorPlans.findForExecution(
                    scenarioCase.mirrorPlanRef().id(), identity);
            MirrorSessionCheckpointBundle checkpoint =
                    scenarioCase.sessionCheckpointRef() == null
                            ? null
                            : scenarioArtifacts.requireCheckpoint(
                            scope,
                            scenarioCase.sessionCheckpointRef(),
                            identity);
            cases.add(
                    new ScenarioRehearsalCompilationRequest.ResolvedCase(
                            scenarioCase,
                            suite,
                            fixture,
                            mirrorPlan,
                            checkpoint));
        }
        CompiledScenarioRehearsalPlan compiled;
        try {
            compiled = compiler.compile(
                    new ScenarioRehearsalCompilationRequest(
                            pack, cases, assertions, clock.instant()));
        } catch (ScenarioRehearsalRejectedException rejected) {
            throw new IntegrationProblemException(
                    IntegrationProblem.conflict(
                            rejected.code(),
                            "ScenarioPack compilation rejected an inconsistent artifact closure.",
                            identity.correlationId(),
                            rejected.diagnostics()));
        } catch (RuntimeException invalid) {
            throw new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.REHEARSAL.COMPILER_UNAVAILABLE",
                            "ScenarioPack compiler failed closed.",
                            identity.correlationId(),
                            Map.of()));
        }
        try {
            return compiledPlans.create(compiled);
        } catch (IllegalArgumentException conflict) {
            throw new IntegrationProblemException(
                    IntegrationProblem.conflict(
                            "RG.MIRROR.REHEARSAL.COMPILED_PLAN_CONFLICT",
                            "Compiled plan identity already contains different content.",
                            identity.correlationId(),
                            Map.of()));
        } catch (RuntimeException unavailable) {
            throw new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.REHEARSAL.COMPILED_PLAN_STORE_UNAVAILABLE",
                            "Compiled rehearsal-plan store is unavailable.",
                            identity.correlationId(),
                            Map.of()));
        }
    }

    /**
     * Reads one exact compiler-issued plan in the authenticated scope.
     */
    public CompiledScenarioRehearsalPlan find(
            String planId,
            long revision,
            String fingerprint,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        MirrorArtifactRef expected;
        try {
            expected = new MirrorArtifactRef(
                    "COMPILED_REHEARSAL_PLAN",
                    planId,
                    revision,
                    fingerprint);
        } catch (IllegalArgumentException invalid) {
            throw new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.REHEARSAL.COMPILED_PLAN_REF_INVALID",
                            "An exact compiled plan id, revision, and fingerprint are required.",
                            identity.correlationId(),
                            Map.of()));
        }
        CompiledScenarioRehearsalPlan plan;
        try {
            plan = compiledPlans.find(scope, planId, revision)
                    .orElseThrow(() -> new IntegrationProblemException(
                            IntegrationProblem.notFound(
                                    "RG.MIRROR.REHEARSAL.COMPILED_PLAN_NOT_FOUND",
                                    "Compiled rehearsal plan was not found in the authorized scope.",
                                    identity.correlationId(),
                                    Map.of())));
        } catch (IntegrationProblemException expectedProblem) {
            throw expectedProblem;
        } catch (RuntimeException unavailable) {
            throw new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.REHEARSAL.COMPILED_PLAN_STORE_UNAVAILABLE",
                            "Compiled rehearsal-plan store is unavailable.",
                            identity.correlationId(),
                            Map.of()));
        }
        if (!expected.equals(
                CompiledScenarioRehearsalPlanIntegrity.reference(plan))) {
            throw new IntegrationProblemException(
                    IntegrationProblem.conflict(
                            "RG.MIRROR.REHEARSAL.COMPILED_PLAN_STALE",
                            "Compiled plan differs from the reviewed reference.",
                            identity.correlationId(),
                            Map.of()));
        }
        return plan;
    }

    private StoredFixtureBundle requireFixture(
            MirrorArtifactRef ref,
            IntegrationRequestContext identity) {
        try {
            return fixtures.find(
                            identity.tenantId(),
                            identity.environmentId(),
                            ref.id(),
                            ref.revision())
                    .orElseThrow(() -> new IntegrationProblemException(
                            IntegrationProblem.notFound(
                                    "RG.MIRROR.REHEARSAL.FIXTURE_NOT_FOUND",
                                    "FixtureBundle was not found in the authorized scope.",
                                    identity.correlationId(),
                                    Map.of())));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.REHEARSAL.FIXTURE_STORE_UNAVAILABLE",
                            "FixtureBundle registry is unavailable.",
                            identity.correlationId(),
                            Map.of()));
        }
    }
}
