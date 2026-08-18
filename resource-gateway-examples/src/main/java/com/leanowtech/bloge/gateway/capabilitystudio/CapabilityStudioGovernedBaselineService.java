package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Executes the non-production governed Capability Studio baseline through the existing compiler,
 * registry, and suite execution control plane.
 *
 * <p>Each invocation creates one server-owned batch id, publishes one exact governed closure, and
 * executes that same immutable suite three times. Caller identity and payload are intentionally
 * not inputs to this service. The bean is composed only under test/staging demo configuration.</p>
 */
public final class CapabilityStudioGovernedBaselineService {

    public static final String BASELINE_ID = "capability-studio-governed-9x3-v1";
    public static final int CASE_COUNT = 9;
    public static final int ROUND_COUNT = 3;
    public static final int EXPECTED_CHILD_RUN_COUNT = CASE_COUNT * ROUND_COUNT;

    private static final String PUBLISH_PURPOSE = "TEST_SCENARIO_PUBLISH";
    private static final String EXECUTE_PURPOSE = "TEST_EXECUTION";
    private static final String WORKLOAD_GROUP = "resource-gateway-test-runtime-operators";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final String CANDIDATE_LIMITATION =
            "IMMUTABLE_RELEASE_CANDIDATE_NOT_BOUND";
    private static final String CERTIFIABLE_LIMITATION =
            "CERTIFIABLE_EVIDENCE_NOT_ESTABLISHED";
    private static final List<String> REMAINING_RELEASE_LIMITATIONS = List.of(
            "RUNTIME_ENVIRONMENT_NOT_ATTESTED",
            "DEPLOYMENT_EGRESS_NOT_OBSERVED",
            "OWNER_SIGNOFF_NOT_PRESENT");

    private final CapabilityStudioGoldenDemoPack pack;
    private final ObjectMapper mapper;
    private final OperatorRegistry operators;
    private final CapabilityStudioFeatureRehearsalService rehearsal;
    private final CapabilityStudioScenarioDatasetProjector datasetProjector;
    private final ScenarioGovernedRegistryGateway registry;
    private final CapabilityStudioGovernedCandidateService candidate;
    private final CapabilityStudioDeploymentCandidateAuthority candidateAuthority;
    private final Supplier<String> batchIdSupplier;

    public CapabilityStudioGovernedBaselineService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            OperatorRegistry operators,
            CapabilityStudioFeatureRehearsalService rehearsal,
            CapabilityStudioScenarioDatasetProjector datasetProjector,
            ScenarioGovernedRegistryGateway registry,
            CapabilityStudioGovernedCandidateService candidate) {
        this(pack, mapper, operators, rehearsal, datasetProjector, registry, candidate,
                CapabilityStudioDeploymentCandidateAuthority.unbound(),
                () -> UUID.randomUUID().toString());
    }

    /** Creates the demo baseline with a deployment-owned candidate binding authority. */
    public CapabilityStudioGovernedBaselineService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            OperatorRegistry operators,
            CapabilityStudioFeatureRehearsalService rehearsal,
            CapabilityStudioScenarioDatasetProjector datasetProjector,
            ScenarioGovernedRegistryGateway registry,
            CapabilityStudioGovernedCandidateService candidate,
            CapabilityStudioDeploymentCandidateAuthority candidateAuthority) {
        this(pack, mapper, operators, rehearsal, datasetProjector, registry, candidate,
                candidateAuthority, () -> UUID.randomUUID().toString());
    }

    /** Test-only seam for deterministic batch ids; production composition uses UUIDs. */
    CapabilityStudioGovernedBaselineService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            OperatorRegistry operators,
            CapabilityStudioFeatureRehearsalService rehearsal,
            CapabilityStudioScenarioDatasetProjector datasetProjector,
            ScenarioGovernedRegistryGateway registry,
            CapabilityStudioGovernedCandidateService candidate,
            CapabilityStudioDeploymentCandidateAuthority candidateAuthority,
            Supplier<String> batchIdSupplier) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
        this.operators = Objects.requireNonNull(operators, "operators");
        this.rehearsal = Objects.requireNonNull(rehearsal, "rehearsal");
        this.datasetProjector = Objects.requireNonNull(datasetProjector, "datasetProjector");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.candidateAuthority = Objects.requireNonNull(candidateAuthority, "candidateAuthority");
        this.batchIdSupplier = Objects.requireNonNull(batchIdSupplier, "batchIdSupplier");
    }

    /**
     * Runs the server-owned 9 cases in three full rounds and returns a payload-free receipt.
     * Any invariant violation becomes a {@code FAILED_CLOSED} projection.
     */
    public CapabilityStudioGovernedBaselineProjection run() {
        try {
            return runVerified();
        } catch (BaselineFailure failure) {
            return failed(failure.code());
        } catch (CapabilityStudioGovernedCompilationException failure) {
            return failed(failure.code());
        } catch (RuntimeException failure) {
            return failed("GOVERNED_BASELINE_EXECUTION_FAILED");
        }
    }

    private CapabilityStudioGovernedBaselineProjection runVerified() {
        String batchId = normalized(batchIdSupplier.get());
        require(!batchId.isBlank(), "BATCH_ID_MISSING");

        CapabilityStudioGoldenGovernedTarget.Target target =
                CapabilityStudioGoldenGovernedTarget.create(mapper);
        CapabilityStudioFeatureRehearsalService.RuntimeAsset runtimeAsset = rehearsal.runtimeAsset();
        operators.register(CapabilityStudioFeatureRehearsalService.TOOL_REF,
                runtimeAsset.operator());

        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset =
                CapabilityStudioGoldenGovernedTarget.retarget(datasetProjector.project(), target);
        require(dataset.cases().size() == CASE_COUNT, "CANONICAL_CASE_COUNT_INVALID");
        IntegrationRequestContext publicationIdentity = identity(dataset, PUBLISH_PURPOSE,
                "capability-studio-governed-publisher", batchId + ":publish");
        IntegrationRequestContext executionIdentity = identity(dataset, EXECUTE_PURPOSE,
                "capability-studio-governed-runner", batchId + ":execute");
        TestExecutionApiRequest.Target runtimeTarget = registry.describeOperatorTarget(
                CapabilityStudioFeatureRehearsalService.TOOL_REF, executionIdentity);
        require(runtimeTarget != null && "OPERATOR".equals(runtimeTarget.kind())
                && target.operator().operatorRef().equals(runtimeTarget.id())
                && FINGERPRINT.matcher(runtimeTarget.fingerprint()).matches(),
                "RUNTIME_TARGET_NOT_EXACT");

        CapabilityStudioScenarioDatasetCompilation datasetCompilation =
                new CapabilityStudioScenarioDatasetCompiler(mapper).compile(
                        dataset,
                        new CapabilityStudioScenarioDatasetCompiler.ExactCompilationTarget(
                                target.exactTarget(), target.contractFingerprint()),
                        new CapabilityStudioGoldenScenarioMaterialResolver(pack));
        List<String> expectedCaseIds = dataset.cases().stream()
                .map(value -> value.caseRef().id()).sorted().toList();
        List<CapabilityStudioGovernedCandidateService.CandidateReceipt> receipts = new ArrayList<>();
        for (int round = 1; round <= ROUND_COUNT; round++) {
            String clientRequestId = batchId + ":round-" + round;
            receipts.add(candidate.run(null, target.operator(), target.contract(), runtimeTarget,
                    datasetCompilation, clientRequestId, publicationIdentity, executionIdentity));
        }
        return projectVerified(receipts, expectedCaseIds, rehearsal.operatorFootprints(),
                runtimeAsset.realExternalCalls().get());
    }

    private CapabilityStudioGovernedBaselineProjection projectVerified(
            List<CapabilityStudioGovernedCandidateService.CandidateReceipt> receipts,
            List<String> expectedCaseIds,
            List<CapabilityStudioFeatureRehearsalService.OperatorFootprint> operatorFootprints,
            int realExternalCallCount) {
        require(receipts.size() == ROUND_COUNT, "ROUND_COUNT_INVALID");
        Set<String> suiteRunIds = new HashSet<>();
        Set<String> childRunIds = new HashSet<>();
        Set<String> caseIds = new HashSet<>(expectedCaseIds);
        require(expectedCaseIds.size() == CASE_COUNT && caseIds.size() == CASE_COUNT,
                "CANONICAL_CASE_SET_INVALID");

        CapabilityStudioGovernedAssetPublisher.Receipt publication = receipts.getFirst().publication();
        String compilationFingerprint = publication.compilationFingerprint();
        String sourceMapFingerprint = publication.sourceMapFingerprint();
        String provenanceFingerprint = receipts.getFirst().evidence().provenanceFingerprint();
        requireFingerprint(compilationFingerprint, "COMPILATION_FINGERPRINT_INVALID");
        requireFingerprint(sourceMapFingerprint, "SOURCE_MAP_FINGERPRINT_INVALID");
        requireFingerprint(provenanceFingerprint, "PROVENANCE_FINGERPRINT_INVALID");
        require(publication.suiteRef() != null && "TEST_SUITE".equals(publication.suiteRef().kind()),
                "SUITE_REF_INVALID");
        CapabilityStudioDeploymentCandidateAuthority.Binding candidateBuild =
                receipts.getFirst().candidateBuild();
        String candidateIntentFingerprint =
                receipts.getFirst().evidence().candidateIntentFingerprint();
        if (candidateBuild == null) {
            require(candidateIntentFingerprint.isBlank(), "UNBOUND_CANDIDATE_HAS_INTENT");
        } else {
            requireFingerprint(candidateIntentFingerprint, "CANDIDATE_INTENT_FINGERPRINT_INVALID");
        }

        Set<String> evidenceClasses = receipts.stream()
                .flatMap(receipt -> receipt.evidence().childRuns().stream())
                .map(CapabilityStudioGovernedCandidateService.ChildRunRef::evidenceClass)
                .collect(java.util.stream.Collectors.toSet());
        require(evidenceClasses.size() == 1, "CHILD_EVIDENCE_CLASS_DRIFT");
        String evidenceClass = evidenceClasses.iterator().next();
        require(CapabilityStudioGovernedBaselineProjection.EXPLORATORY.equals(evidenceClass)
                        || CapabilityStudioGovernedBaselineProjection.CERTIFIABLE.equals(evidenceClass),
                "CHILD_EVIDENCE_CLASS_INVALID");

        Map<String, List<CapabilityStudioGovernedBaselineProjection.CaseRound>> caseRounds =
                new LinkedHashMap<>();
        expectedCaseIds.forEach(caseId -> caseRounds.put(caseId, new ArrayList<>()));
        List<CapabilityStudioGovernedBaselineProjection.Round> rounds = new ArrayList<>();
        for (int index = 0; index < receipts.size(); index++) {
            int round = index + 1;
            CapabilityStudioGovernedCandidateService.CandidateReceipt receipt = receipts.get(index);
            CapabilityStudioGovernedCandidateService.CandidateEvidence evidence = receipt.evidence();
            require(publication.equals(receipt.publication()), "PUBLICATION_DRIFT");
            require(compilationFingerprint.equals(receipt.publication().compilationFingerprint()),
                    "COMPILATION_FINGERPRINT_DRIFT");
            require(sourceMapFingerprint.equals(receipt.publication().sourceMapFingerprint()),
                    "SOURCE_MAP_FINGERPRINT_DRIFT");
            require(provenanceFingerprint.equals(evidence.provenanceFingerprint()),
                    "PROVENANCE_FINGERPRINT_DRIFT");
            require(Objects.equals(candidateBuild, receipt.candidateBuild()),
                    "CANDIDATE_BUILD_DRIFT");
            require(candidateIntentFingerprint.equals(evidence.candidateIntentFingerprint()),
                    "CANDIDATE_INTENT_FINGERPRINT_DRIFT");
            require(suiteRunIds.add(evidence.suiteRunId()), "SUITE_RUN_ID_NOT_UNIQUE");
            require(evidence.status().equals(TestSuiteRunEvidence.Status.PASSED.name()),
                    "SUITE_STATUS_NOT_PASSED");
            require(evidence.childRuns().size() == CASE_COUNT, "CHILD_RUN_COUNT_INVALID");
            Set<String> roundCases = new HashSet<>();
            for (CapabilityStudioGovernedCandidateService.ChildRunRef child : evidence.childRuns()) {
                require(child != null && childRunIds.add(child.runId()), "CHILD_RUN_ID_NOT_UNIQUE");
                require(child.status().equals(TestSuiteRunEvidence.Status.PASSED.name()),
                        "CHILD_STATUS_NOT_PASSED");
                require(caseIds.contains(child.caseId()) && roundCases.add(child.caseId()),
                        "CASE_COVERAGE_INVALID");
                caseRounds.get(child.caseId()).add(new CapabilityStudioGovernedBaselineProjection.CaseRound(
                        round, child.runId(), child.status(), child.fixtureBundleId(),
                        child.fixtureRevision(), child.fixtureFingerprint(),
                        child.evidenceFingerprint(), child.semanticResultFingerprint(),
                        child.assertionsEvaluated(), child.assertionsPassed(),
                        child.fixtureControlsEvaluated(), child.fixtureControlsSatisfied()));
            }
            require(roundCases.equals(caseIds), "CASE_COVERAGE_INVALID");
            rounds.add(new CapabilityStudioGovernedBaselineProjection.Round(
                    round, evidence.suiteRunId(), evidence.evidenceFingerprint(), evidence.status(),
                    evidence.childRuns().size()));
        }
        require(suiteRunIds.size() == ROUND_COUNT, "SUITE_RUN_CARDINALITY_INVALID");
        require(childRunIds.size() == EXPECTED_CHILD_RUN_COUNT, "CHILD_RUN_CARDINALITY_INVALID");
        caseRounds.values().forEach(roundsForCase -> require(roundsForCase.size() == ROUND_COUNT,
                "CASE_ROUND_CARDINALITY_INVALID"));
        require(realExternalCallCount == 0, "REAL_EXTERNAL_CALL_FORBIDDEN");

        List<CapabilityStudioGovernedBaselineProjection.CaseProjection> cases = expectedCaseIds.stream()
                .map(caseId -> oracleProjection(
                        caseId,
                        caseRounds.get(caseId).stream()
                                .sorted(Comparator.comparingInt(
                                        CapabilityStudioGovernedBaselineProjection.CaseRound::round))
                                .toList(),
                        receipts,
                        operatorFootprints,
                        realExternalCallCount,
                        evidenceClass))
                .toList();
        CapabilityStudioGovernedAssetPublisher.ExactRef suite = publication.suiteRef();
        return new CapabilityStudioGovernedBaselineProjection(
                CapabilityStudioGovernedBaselineProjection.SCHEMA_VERSION,
                CapabilityStudioGovernedBaselineProjection.EVIDENCE_KIND,
                BASELINE_ID,
                CapabilityStudioGovernedBaselineProjection.PASSED,
                CapabilityStudioGovernedBaselineProjection.VERIFICATION_SCOPE,
                CapabilityStudioGovernedBaselineProjection.RELEASE_GATE_STATUS,
                CapabilityStudioGovernedBaselineProjection.DEVELOPMENT_VERIFIED,
                evidenceClass,
                CASE_COUNT,
                ROUND_COUNT,
                suiteRunIds.size(),
                childRunIds.size(),
                cases.size(),
                EXPECTED_CHILD_RUN_COUNT,
                EXPECTED_CHILD_RUN_COUNT,
                realExternalCallCount,
                compilationFingerprint,
                sourceMapFingerprint,
                provenanceFingerprint,
                candidateBuild(candidateBuild),
                candidateIntentFingerprint.isBlank() ? null : candidateIntentFingerprint,
                new CapabilityStudioGovernedBaselineProjection.Publication(
                        publication.receiptFingerprint(),
                        new CapabilityStudioGovernedBaselineProjection.SuiteRef(
                                suite.kind(), suite.id(), suite.revision(), suite.fingerprint()),
                        publication.fixtureRefs().size()),
                rounds,
                cases,
                limitations(candidateBuild, evidenceClass),
                List.of());
    }

    private CapabilityStudioGovernedBaselineProjection failed(String diagnostic) {
        return new CapabilityStudioGovernedBaselineProjection(
                CapabilityStudioGovernedBaselineProjection.SCHEMA_VERSION,
                CapabilityStudioGovernedBaselineProjection.EVIDENCE_KIND,
                BASELINE_ID,
                CapabilityStudioGovernedBaselineProjection.FAILED_CLOSED,
                CapabilityStudioGovernedBaselineProjection.VERIFICATION_SCOPE,
                CapabilityStudioGovernedBaselineProjection.RELEASE_GATE_STATUS,
                CapabilityStudioGovernedBaselineProjection.NOT_VERIFIED,
                null,
                CASE_COUNT,
                ROUND_COUNT,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                candidateBuild(candidateAuthority.current().orElse(null)),
                null,
                null,
                List.of(),
                List.of(),
                limitations(candidateAuthority.current().orElse(null), null),
                List.of(diagnostic));
    }

    private CapabilityStudioGovernedBaselineProjection.CaseProjection oracleProjection(
            String caseId,
            List<CapabilityStudioGovernedBaselineProjection.CaseRound> rounds,
            List<CapabilityStudioGovernedCandidateService.CandidateReceipt> receipts,
            List<CapabilityStudioFeatureRehearsalService.OperatorFootprint> operatorFootprints,
            int realExternalCallCount,
            String evidenceClass) {
        require(rounds.size() == ROUND_COUNT, "ORACLE_ROUND_COUNT_INVALID");
        String semanticFingerprint = rounds.getFirst().semanticResultFingerprint();
        requireFingerprint(semanticFingerprint, "BUSINESS_RESULT_FINGERPRINT_INVALID");
        require(rounds.stream().allMatch(round ->
                        semanticFingerprint.equals(round.semanticResultFingerprint())),
                "BUSINESS_RESULT_NOT_STABLE");
        require(rounds.stream().allMatch(round ->
                        round.assertionsEvaluated() == 1 && round.assertionsPassed() == 1),
                "BUSINESS_ASSERTION_NOT_PASSED");
        require(rounds.stream().allMatch(round ->
                        round.fixtureControlsEvaluated() > 0
                                && round.fixtureControlsEvaluated()
                                == round.fixtureControlsSatisfied()),
                "FIXTURE_CONTROL_NOT_SATISFIED");

        List<CapabilityStudioGovernedCandidateService.ChildRunRef> children = receipts.stream()
                .flatMap(receipt -> receipt.evidence().childRuns().stream())
                .filter(child -> caseId.equals(child.caseId()))
                .toList();
        require(children.size() == ROUND_COUNT, "ORACLE_CHILD_EVIDENCE_MISSING");
        require(children.stream().allMatch(child ->
                        evidenceClass.equals(child.evidenceClass())),
                "CHILD_EVIDENCE_CLASS_DRIFT");
        List<String> proofs = new ArrayList<>(List.of(
                "BUSINESS_ASSERTION_PASSED",
                "SEMANTIC_RESULT_STABLE",
                "FIXTURE_CONTROL_SATISFIED",
                "ZERO_REAL_EXTERNAL_CALLS"));
        switch (caseId) {
            case "case-compensation-history-timeout" -> {
                require(children.stream().allMatch(this::timeoutFallbackObserved),
                        "TIMEOUT_FALLBACK_NOT_PROVEN");
                proofs.add("TIMEOUT_FALLBACK_CONFIRMED");
            }
            case "case-duplicate-cancellation" -> {
                require(children.stream().map(
                                CapabilityStudioGovernedCandidateService.ChildRunRef::runId)
                                .distinct().count() == ROUND_COUNT,
                        "DUPLICATE_RUN_ID_NOT_UNIQUE");
                proofs.add("DUPLICATE_IDEMPOTENCY_CONFIRMED");
            }
            case "case-forbidden-write-effect" -> {
                require(noWriteObserved(children, operatorFootprints, realExternalCallCount),
                        "FORBIDDEN_WRITE_EFFECT_OBSERVED");
                proofs.add("FORBIDDEN_WRITE_EFFECT_ABSENT");
            }
            default -> {
                // The common governed assertion and stability checks are the complete v3 Oracle.
            }
        }
        int assertionsEvaluated = rounds.stream()
                .mapToInt(CapabilityStudioGovernedBaselineProjection.CaseRound::assertionsEvaluated)
                .sum();
        int assertionsPassed = rounds.stream()
                .mapToInt(CapabilityStudioGovernedBaselineProjection.CaseRound::assertionsPassed)
                .sum();
        int fixtureControlsEvaluated = rounds.stream().mapToInt(
                        CapabilityStudioGovernedBaselineProjection.CaseRound::fixtureControlsEvaluated)
                .sum();
        int fixtureControlsSatisfied = rounds.stream().mapToInt(
                        CapabilityStudioGovernedBaselineProjection.CaseRound::fixtureControlsSatisfied)
                .sum();
        return new CapabilityStudioGovernedBaselineProjection.CaseProjection(
                caseId,
                "oracle-" + caseId.substring("case-".length()),
                "PASS",
                semanticFingerprint,
                assertionsEvaluated,
                assertionsPassed,
                fixtureControlsEvaluated,
                fixtureControlsSatisfied,
                proofs,
                rounds);
    }

    private boolean timeoutFallbackObserved(
            CapabilityStudioGovernedCandidateService.ChildRunRef child) {
        CapabilityStudioGovernedCandidateService.NodeFact timeout = child.nodes().stream()
                .filter(node -> "compensationHistoryLookup".equals(node.nodeId()))
                .findFirst().orElse(null);
        if (timeout == null || !"MOCKED".equals(timeout.status())
                || timeout.attempts().stream().noneMatch(attempt ->
                "TIMEOUT".equals(attempt.status())
                        && "COMPENSATION_HISTORY_TIMEOUT".equals(attempt.errorCode()))) {
            return false;
        }
        return List.of("aggregateCancellationContext", "cancellationDecision").stream()
                .allMatch(nodeId -> child.nodes().stream()
                        .filter(node -> nodeId.equals(node.nodeId()))
                        .map(CapabilityStudioGovernedCandidateService.NodeFact::status)
                        .anyMatch(status -> List.of("SUCCESS", "MOCKED").contains(status)));
    }

    private static boolean noWriteObserved(
            List<CapabilityStudioGovernedCandidateService.ChildRunRef> children,
            List<CapabilityStudioFeatureRehearsalService.OperatorFootprint> operatorFootprints,
            int realExternalCallCount) {
        if (operatorFootprints == null || operatorFootprints.isEmpty()
                || realExternalCallCount != 0) {
            return false;
        }
        boolean writeOperator = operatorFootprints.stream().anyMatch(operator ->
                operator.sideEffectType() == SideEffectType.WRITE
                        || operator.sideEffectType() == SideEffectType.MIXED
                        || operator.operatorRef().toLowerCase(Locale.ROOT).contains("write"));
        boolean writeTrace = children.stream().flatMap(child -> child.nodes().stream())
                .anyMatch(node -> node.operatorRef().toLowerCase(Locale.ROOT).contains("write"));
        return !writeOperator && !writeTrace;
    }

    private IntegrationRequestContext identity(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            String purpose,
            String actorId,
            String correlationId) {
        CapabilityStudioScenarioDatasetProjector.Scope scope = dataset.datasetRef().scope();
        return new IntegrationRequestContext(
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), "WORKLOAD", actorId, "", purpose, correlationId,
                Set.of(WORKLOAD_GROUP), "RESTRICTED", "");
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new BaselineFailure(code);
        }
    }

    private static void requireFingerprint(String value, String code) {
        require(value != null && FINGERPRINT.matcher(value).matches(), code);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static CapabilityStudioGovernedBaselineProjection.CandidateBuild candidateBuild(
            CapabilityStudioDeploymentCandidateAuthority.Binding value) {
        return value == null ? null : new CapabilityStudioGovernedBaselineProjection.CandidateBuild(
                value.authority(), value.instanceId(), value.buildRef(), value.revision(),
                value.sourceCommit(), value.sourceTreeStatus(), value.artifactFingerprint());
    }

    private static List<String> limitations(
            CapabilityStudioDeploymentCandidateAuthority.Binding candidateBuild,
            String evidenceClass) {
        List<String> result = new ArrayList<>();
        if (candidateBuild == null) {
            result.add(CANDIDATE_LIMITATION);
        }
        result.add(REMAINING_RELEASE_LIMITATIONS.get(0));
        if (!CapabilityStudioGovernedBaselineProjection.CERTIFIABLE.equals(evidenceClass)) {
            result.add(CERTIFIABLE_LIMITATION);
        }
        result.add(REMAINING_RELEASE_LIMITATIONS.get(1));
        result.add(REMAINING_RELEASE_LIMITATIONS.get(2));
        return List.copyOf(result);
    }

    private static final class BaselineFailure extends RuntimeException {
        private BaselineFailure(String code) {
            Objects.requireNonNull(code, "code");
            this.code = code;
        }

        private final String code;

        private String code() {
            return code;
        }
    }
}
