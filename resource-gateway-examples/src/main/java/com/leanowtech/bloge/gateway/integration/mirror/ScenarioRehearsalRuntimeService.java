package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Synchronous generation-one runtime for one exact compiled Scenario rehearsal plan.
 *
 * <p>The service is an orchestrator over existing protected boundaries. It never executes BLOGE
 * directly: TestSuite supplies the immutable context, an optional checkpoint supplies an exact
 * Session state fence, {@link MirrorRunIntegrationService} owns child idempotency and live-state
 * admission, and handling assertions consume only independently verified evidence. Passing the
 * original fence to that coordinator lets a completed stateful retry resolve before the current
 * Session head is inspected.</p>
 *
 * <p>Generation one is deliberately stateless at the aggregate layer. Stable child request ids
 * make an interrupted retry reuse already committed Mirror runs, but concurrent aggregate leases,
 * durable progress, batch scheduling, and checkpoint cloning remain later control-plane work.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class ScenarioRehearsalRuntimeService {
    private static final String PURPOSE = "MIRROR_REHEARSAL";

    private final ScenarioRehearsalIntegrationService rehearsals;
    private final ScenarioArtifactRegistryService scenarioArtifacts;
    private final TestSuiteRegistryService testSuites;
    private final MirrorRunIntegrationService mirrorRuns;
    private final MirrorEvidenceIntegrityService evidenceIntegrity;
    private final ScenarioHandlingAssertionEvaluator assertionEvaluator;
    private final ObjectMapper mapper;
    private final MirrorSessionIntegrationService sessions;
    private final Clock clock;

    /** Creates the protected runtime with optional stateful Session support. */
    @Autowired
    public ScenarioRehearsalRuntimeService(
            ScenarioRehearsalIntegrationService rehearsals,
            ScenarioArtifactRegistryService scenarioArtifacts,
            TestSuiteRegistryService testSuites,
            MirrorRunIntegrationService mirrorRuns,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            ScenarioHandlingAssertionEvaluator assertionEvaluator,
            ObjectMapper mapper,
            ObjectProvider<MirrorSessionIntegrationService> sessionProvider) {
        this(
                rehearsals, scenarioArtifacts, testSuites, mirrorRuns,
                evidenceIntegrity, assertionEvaluator, mapper,
                Objects.requireNonNull(
                        sessionProvider, "sessionProvider").getIfAvailable(),
                Clock.systemUTC());
    }

    /** Full constructor for deterministic service tests. */
    public ScenarioRehearsalRuntimeService(
            ScenarioRehearsalIntegrationService rehearsals,
            ScenarioArtifactRegistryService scenarioArtifacts,
            TestSuiteRegistryService testSuites,
            MirrorRunIntegrationService mirrorRuns,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            ScenarioHandlingAssertionEvaluator assertionEvaluator,
            ObjectMapper mapper,
            MirrorSessionIntegrationService sessions,
            Clock clock) {
        this.rehearsals = Objects.requireNonNull(rehearsals, "rehearsals");
        this.scenarioArtifacts = Objects.requireNonNull(
                scenarioArtifacts, "scenarioArtifacts");
        this.testSuites = Objects.requireNonNull(testSuites, "testSuites");
        this.mirrorRuns = Objects.requireNonNull(mirrorRuns, "mirrorRuns");
        this.evidenceIntegrity = Objects.requireNonNull(
                evidenceIntegrity, "evidenceIntegrity");
        this.assertionEvaluator = Objects.requireNonNull(
                assertionEvaluator, "assertionEvaluator");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.sessions = sessions;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Executes every compiled case sequentially and returns a sealed aggregate interpretation.
     *
     * @param request exact payload-free rehearsal command
     * @param identity authenticated full enterprise mirror identity
     * @return content-addressed aggregate over verified child evidence
     */
    public ScenarioRehearsalResult execute(
            ScenarioRehearsalExecutionRequest request,
            IntegrationRequestContext identity) {
        Objects.requireNonNull(request, "request");
        requirePurpose(identity);
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        MirrorArtifactRef planRef = request.compiledPlanRef();
        CompiledScenarioRehearsalPlan plan = rehearsals.find(
                planRef.id(), planRef.revision(),
                planRef.fingerprint(), identity);
        Instant startedAt = clock.instant();
        List<ResolvedCase> resolved = resolveCases(
                plan, scope, identity, startedAt);
        List<ScenarioCaseRehearsalResult> results =
                new ArrayList<>(resolved.size());
        Instant deadline;
        try {
            deadline = startedAt.plus(plan.policy().totalTimeout());
        } catch (RuntimeException invalid) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TIME_BOUNDS_INVALID",
                    "Compiled rehearsal time bounds are invalid.");
        }
        for (int index = 0; index < resolved.size(); index++) {
            ResolvedCase current = resolved.get(index);
            Instant now = clock.instant();
            if (now.isAfter(
                    deadline.minus(plan.policy().caseTimeout()))) {
                results.add(unscheduled(
                        index, request.requestId(), current, now,
                        "RG.MIRROR.REHEARSAL.TOTAL_TIMEOUT_EXCEEDED"));
                continue;
            }
            results.add(executeCase(
                    index, request.requestId(), current, scope, identity));
        }
        Instant aggregateStartedAt = results.stream()
                .map(ScenarioCaseRehearsalResult::startedAt)
                .filter(value -> value.isBefore(startedAt))
                .min(Instant::compareTo)
                .orElse(startedAt);
        Instant completedAt = clock.instant();
        if (!results.isEmpty()
                && completedAt.isBefore(
                results.getLast().completedAt())) {
            completedAt = results.getLast().completedAt();
        }
        ScenarioCaseRehearsalResult.Outcome outcome =
                ScenarioRehearsalResult.deriveOutcome(results);
        ScenarioRehearsalResult material =
                new ScenarioRehearsalResult(
                        "", "", request.requestId(), planRef, scope,
                        plan.targetCapabilityRef(), outcome, results,
                        ScenarioRehearsalResult.Summary.from(results),
                        aggregateStartedAt, completedAt);
        return ScenarioRehearsalResultIntegrity.seal(mapper, material);
    }

    private List<ResolvedCase> resolveCases(
            CompiledScenarioRehearsalPlan plan,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity,
            Instant admittedAt) {
        List<ResolvedCase> resolved =
                new ArrayList<>(plan.cases().size());
        Instant requiredUntil;
        try {
            requiredUntil = admittedAt.plus(
                    plan.policy().totalTimeout());
        } catch (RuntimeException invalid) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TIME_BOUNDS_INVALID",
                    "Compiled rehearsal time bounds are invalid.");
        }
        for (CompiledScenarioRehearsalPlan.CaseBinding binding
                : plan.cases()) {
            ScenarioCase scenarioCase = scenarioArtifacts.requireCase(
                    scope, binding.scenarioCaseRef(), identity);
            requireBinding(binding, scenarioCase, plan, identity);
            requireLiveCase(
                    scenarioCase, plan.policy(), admittedAt,
                    requiredUntil, identity);
            StoredTestSuite suite = testSuites.find(
                    binding.testSuiteRef().id(),
                    binding.testSuiteRef().revision(),
                    identity);
            TestSuite.TestCase testCase =
                    requireTestCase(binding, suite, identity);
            Map<String, Object> context =
                    requireGraphContext(testCase, identity);
            List<CaseHandlingAssertion> assertions =
                    binding.assertionRefs().stream()
                            .map(ref -> requireLiveAssertion(
                                    scenarioArtifacts.requireAssertion(
                                            scope, ref, identity),
                                    scope, admittedAt, requiredUntil,
                                    identity))
                            .toList();
            MirrorSessionCheckpointBundle checkpoint =
                    binding.sessionCheckpointRef() == null
                            ? null
                            : scenarioArtifacts.requireCheckpoint(
                            scope,
                            binding.sessionCheckpointRef(),
                            identity);
            if (checkpoint != null
                    && !checkpoint.checkpoint().sessionExpiresAt()
                    .isAfter(requiredUntil)) {
                throw conflict(
                        identity,
                        "RG.MIRROR.REHEARSAL.CHECKPOINT_EXPIRES_DURING_RUN",
                        "Scenario checkpoint does not cover the rehearsal deadline.");
            }
            resolved.add(new ResolvedCase(
                    binding, scenarioCase, context, assertions, checkpoint));
        }
        return List.copyOf(resolved);
    }

    private ScenarioCaseRehearsalResult executeCase(
            int caseIndex,
            String aggregateRequestId,
            ResolvedCase resolved,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        Instant startedAt = clock.instant();
        String childRequestId = childRequestId(
                aggregateRequestId, caseIndex);
        MirrorSessionRunBinding sessionBinding =
                sessionBinding(resolved, identity);
        MirrorExecutionRequest childRequest = new MirrorExecutionRequest(
                sessionBinding == null
                        ? MirrorExecutionRequest.SCHEMA_VERSION
                        : MirrorExecutionRequest.STATEFUL_SCHEMA_VERSION,
                childRequestId,
                resolved.binding().mirrorPlanRef().id(),
                resolved.binding().mirrorPlanRef().fingerprint(),
                resolved.context(),
                sessionBinding);
        MirrorRunSummary summary;
        try {
            summary = mirrorRuns.execute(childRequest, identity);
        } catch (IntegrationProblemException rejected) {
            if (rejected.problem().retryable()) {
                throw rejected;
            }
            return preEvidence(
                    caseIndex, childRequestId, resolved,
                    ScenarioCaseRehearsalResult.Outcome.FAIL,
                    machineCode(rejected.problem().code()),
                    startedAt, clock.instant());
        }

        MirrorEvidenceIntegrityService.VerifiedBundle verified;
        try {
            verified = evidenceIntegrity.requireVerified(
                    mirrorRuns.evidence(summary.runId(), identity));
        } catch (IllegalStateException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_VERIFIER_UNAVAILABLE",
                    "Scenario evidence verification authority is unavailable.");
        } catch (IllegalArgumentException invalid) {
            return preEvidence(
                    caseIndex, childRequestId, resolved,
                    ScenarioCaseRehearsalResult.Outcome.INDETERMINATE,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_INTEGRITY_INVALID",
                    startedAt, clock.instant());
        }
        MirrorEvidenceBundle bundle = verified.bundle();
        MirrorRunEvidence evidence = bundle.evidence();
        requireChildEvidence(
                resolved.binding(), childRequestId, scope,
                summary, bundle, identity);
        List<ScenarioHandlingAssertionResult> assertionResults =
                resolved.assertions().stream()
                        .map(assertion ->
                                assertionEvaluator.evaluate(
                                        assertion, verified))
                        .toList();
        ScenarioCaseRehearsalResult.Outcome outcome =
                ScenarioCaseRehearsalResult.deriveOutcome(
                        evidence.status(), assertionResults);
        String diagnosticCode =
                diagnosticCode(outcome, evidence.status());
        ScenarioCaseRehearsalResult material =
                new ScenarioCaseRehearsalResult(
                        "", "", caseIndex,
                        resolved.binding().scenarioCaseRef(),
                        resolved.binding().caseType(),
                        resolved.binding().testSuiteRef(),
                        resolved.binding().testCaseId(),
                        resolved.binding().mirrorPlanRef(),
                        resolved.binding().fixtureBundleRef(),
                        resolved.binding().sessionCheckpointRef(),
                        childRequestId, outcome, evidence.runId(),
                        bundle.bundleFingerprint(), evidence.status(),
                        evidence.evidenceClass(), assertionResults,
                        diagnosticCode, evidence.startedAt(),
                        evidence.completedAt());
        return ScenarioRehearsalResultIntegrity.sealCase(mapper, material);
    }

    private MirrorSessionRunBinding sessionBinding(
            ResolvedCase resolved,
            IntegrationRequestContext identity) {
        if (resolved.checkpoint() == null) {
            return null;
        }
        if (sessions == null) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.STATEFUL_RUNTIME_UNAVAILABLE",
                    "Stateful Scenario rehearsal runtime is unavailable.");
        }
        MirrorSessionCheckpoint checkpoint =
                resolved.checkpoint().checkpoint();
        return new MirrorSessionRunBinding(
                checkpoint.sessionId(),
                checkpoint.stateFingerprint());
    }

    private ScenarioCaseRehearsalResult unscheduled(
            int caseIndex,
            String aggregateRequestId,
            ResolvedCase resolved,
            Instant now,
            String code) {
        return preEvidence(
                caseIndex,
                childRequestId(aggregateRequestId, caseIndex),
                resolved,
                ScenarioCaseRehearsalResult.Outcome.INDETERMINATE,
                code, now, now);
    }

    private ScenarioCaseRehearsalResult preEvidence(
            int caseIndex,
            String childRequestId,
            ResolvedCase resolved,
            ScenarioCaseRehearsalResult.Outcome outcome,
            String code,
            Instant startedAt,
            Instant completedAt) {
        ScenarioCaseRehearsalResult material =
                new ScenarioCaseRehearsalResult(
                        "", "", caseIndex,
                        resolved.binding().scenarioCaseRef(),
                        resolved.binding().caseType(),
                        resolved.binding().testSuiteRef(),
                        resolved.binding().testCaseId(),
                        resolved.binding().mirrorPlanRef(),
                        resolved.binding().fixtureBundleRef(),
                        resolved.binding().sessionCheckpointRef(),
                        childRequestId, outcome,
                        "", "", null, null, List.of(),
                        code, startedAt, completedAt);
        return ScenarioRehearsalResultIntegrity.sealCase(mapper, material);
    }

    private static void requireBinding(
            CompiledScenarioRehearsalPlan.CaseBinding binding,
            ScenarioCase scenarioCase,
            CompiledScenarioRehearsalPlan plan,
            IntegrationRequestContext identity) {
        if (!plan.scope().equals(scenarioCase.scope())
                || !plan.targetCapabilityRef().equals(
                scenarioCase.targetCapabilityRef())
                || binding.caseType() != scenarioCase.caseType()
                || !binding.testSuiteRef().equals(
                scenarioCase.testSuiteRef())
                || !binding.testCaseId().equals(
                scenarioCase.testCaseId())
                || !binding.mirrorPlanRef().equals(
                scenarioCase.mirrorPlanRef())
                || !binding.fixtureBundleRef().equals(
                scenarioCase.fixtureBundleRef())
                || !Objects.equals(
                binding.sessionCheckpointRef(),
                scenarioCase.sessionCheckpointRef())
                || !binding.executionServices().equals(
                scenarioCase.executionServices())
                || !binding.assertionRefs().equals(
                scenarioCase.assertionRefs())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CASE_BINDING_STALE",
                    "ScenarioCase differs from the compiled execution binding.");
        }
    }

    private static void requireLiveCase(
            ScenarioCase scenarioCase,
            ScenarioPack.RehearsalPolicy policy,
            Instant admittedAt,
            Instant requiredUntil,
            IntegrationRequestContext identity) {
        ArtifactProvenance provenance = scenarioCase.provenance();
        boolean inactive = scenarioCase.lifecycle()
                == CapabilitySnapshot.Lifecycle.STALE
                || scenarioCase.lifecycle()
                == CapabilitySnapshot.Lifecycle.REVOKED
                || policy.certificationRequired()
                && scenarioCase.lifecycle()
                != CapabilitySnapshot.Lifecycle.ACTIVE;
        boolean invalidProvenance =
                scenarioCase.createdAt().isAfter(admittedAt)
                        || !provenance.revocationRef().isBlank()
                        || provenance.expiresAt() != null
                        && !provenance.expiresAt().isAfter(requiredUntil)
                        || policy.certificationRequired()
                        && (provenance.approvedAt() == null
                        || provenance.approvedAt().isAfter(admittedAt));
        if (inactive || invalidProvenance) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CASE_LIFECYCLE_INVALID",
                    "ScenarioCase is not admitted at execution time.");
        }
    }

    private static CaseHandlingAssertion requireLiveAssertion(
            CaseHandlingAssertion assertion,
            CapabilitySnapshot.Scope scope,
            Instant admittedAt,
            Instant requiredUntil,
            IntegrationRequestContext identity) {
        ArtifactProvenance provenance = assertion.provenance();
        if (!scope.equals(assertion.scope())
                || assertion.lifecycle()
                != CapabilitySnapshot.Lifecycle.ACTIVE
                || assertion.createdAt().isAfter(admittedAt)
                || provenance.approvedAt() == null
                || provenance.approvedAt().isAfter(admittedAt)
                || provenance.expiresAt() != null
                && !provenance.expiresAt().isAfter(requiredUntil)
                || !provenance.revocationRef().isBlank()) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.ASSERTION_LIFECYCLE_INVALID",
                    "Handling assertion is not approved for the rehearsal window.");
        }
        return assertion;
    }

    private TestSuite.TestCase requireTestCase(
            CompiledScenarioRehearsalPlan.CaseBinding binding,
            StoredTestSuite suite,
            IntegrationRequestContext identity) {
        MirrorArtifactRef actual = new MirrorArtifactRef(
                "TEST_SUITE", suite.suiteId(), suite.revision(),
                suite.fingerprint());
        if (!binding.testSuiteRef().equals(actual)
                || !suite.enterpriseScoped()) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TEST_SUITE_STALE",
                    "TestSuite differs from the compiled execution binding.");
        }
        List<TestSuite.TestCase> matches = suite.suite().cases().stream()
                .filter(candidate -> binding.testCaseId().equals(
                        candidate.caseId()))
                .toList();
        if (matches.size() != 1) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TEST_CASE_STALE",
                    "Compiled TestSuite case is missing or ambiguous.");
        }
        TestSuite.TestCase testCase = matches.getFirst();
        TestSuite.FixtureBundleRef fixture =
                testCase.fixtureBundleRef();
        MirrorArtifactRef fixtureRef = new MirrorArtifactRef(
                "FIXTURE_BUNDLE",
                fixture.fixtureBundleId(),
                fixture.revision(),
                fixture.fingerprint());
        if (!binding.fixtureBundleRef().equals(fixtureRef)) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TEST_CASE_FIXTURE_STALE",
                    "TestSuite case fixture differs from the compiled binding.");
        }
        return testCase;
    }

    private Map<String, Object> requireGraphContext(
            TestSuite.TestCase testCase,
            IntegrationRequestContext identity) {
        if (!(testCase.input() instanceof Map<?, ?>)) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CASE_INPUT_NOT_CONTEXT",
                    "Generation-one Mirror rehearsal requires a graph-context test input.");
        }
        try {
            return mapper.convertValue(
                    testCase.input(),
                    new TypeReference<>() {
                    });
        } catch (RuntimeException invalid) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CASE_INPUT_INVALID",
                    "TestSuite case input cannot be detached as a graph context.");
        }
    }

    private static void requireChildEvidence(
            CompiledScenarioRehearsalPlan.CaseBinding binding,
            String childRequestId,
            CapabilitySnapshot.Scope scope,
            MirrorRunSummary summary,
            MirrorEvidenceBundle bundle,
            IntegrationRequestContext identity) {
        MirrorRunEvidence evidence = bundle.evidence();
        if (!summary.runId().equals(evidence.runId())
                || !childRequestId.equals(summary.requestId())
                || !childRequestId.equals(evidence.requestId())
                || !binding.mirrorPlanRef().id().equals(
                evidence.planId())
                || !binding.mirrorPlanRef().fingerprint().equals(
                evidence.planFingerprint())
                || !binding.fixtureBundleRef().equals(
                evidence.fixtureBundleRef())
                || !scope.equals(evidence.scope())
                || !bundle.bundleFingerprint().equals(
                summary.evidenceBundleFingerprint())
                || summary.status() != evidence.status()
                || summary.evidenceClass() != evidence.evidenceClass()) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CHILD_EVIDENCE_IDENTITY_INVALID",
                    "Mirror child evidence differs from the compiled case binding.");
        }
    }

    private static String diagnosticCode(
            ScenarioCaseRehearsalResult.Outcome outcome,
            MirrorRunEvidence.Status status) {
        if (outcome == ScenarioCaseRehearsalResult.Outcome.PASS) {
            return "";
        }
        if (status == MirrorRunEvidence.Status.PASSED) {
            return outcome == ScenarioCaseRehearsalResult.Outcome.FAIL
                    ? "RG.MIRROR.REHEARSAL.BLOCKER_ASSERTION_FAILED"
                    : "RG.MIRROR.REHEARSAL.BLOCKER_ASSERTION_INDETERMINATE";
        }
        return outcome == ScenarioCaseRehearsalResult.Outcome.FAIL
                ? "RG.MIRROR.REHEARSAL.CASE_EXECUTION_FAILED"
                : "RG.MIRROR.REHEARSAL.CASE_EVIDENCE_INDETERMINATE";
    }

    private static String childRequestId(
            String aggregateRequestId, int caseIndex) {
        return aggregateRequestId + ":case:"
                + String.format("%03d", caseIndex);
    }

    private static String machineCode(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("[A-Z][A-Z0-9_.-]{0,254}")
                ? normalized
                : "RG.MIRROR.REHEARSAL.CASE_REJECTED";
    }

    private static void requirePurpose(IntegrationRequestContext identity) {
        if (identity == null || !PURPOSE.equals(identity.purpose())) {
            throw new IntegrationProblemException(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.REHEARSAL.PURPOSE_REQUIRED",
                            "Scenario rehearsal requires MIRROR_REHEARSAL purpose.",
                            identity == null ? "" : identity.correlationId(),
                            Map.of()));
        }
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.conflict(
                        code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        code, title, identity.correlationId(), Map.of()));
    }

    private record ResolvedCase(
            CompiledScenarioRehearsalPlan.CaseBinding binding,
            ScenarioCase scenarioCase,
            Map<String, Object> context,
            List<CaseHandlingAssertion> assertions,
            MirrorSessionCheckpointBundle checkpoint) {
        private ResolvedCase {
            binding = Objects.requireNonNull(binding, "binding");
            scenarioCase = Objects.requireNonNull(
                    scenarioCase, "scenarioCase");
            context = Collections.unmodifiableMap(
                    new LinkedHashMap<>(
                            Objects.requireNonNull(context, "context")));
            assertions = List.copyOf(assertions);
        }
    }
}
