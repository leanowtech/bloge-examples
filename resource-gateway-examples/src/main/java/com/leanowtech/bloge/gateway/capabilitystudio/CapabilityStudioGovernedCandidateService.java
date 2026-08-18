package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedProvenanceMetadataCodec;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Executes one Capability Studio candidate through the existing governed publication and suite
 * runtime boundaries.
 *
 * <p>The service contains no lowering, registry, fixture-matching, or graph-execution logic. It
 * binds the deterministic compiler output to the independently verified publication receipt and
 * then submits that exact suite revision to {@link TestSuiteExecutionService}. The terminal
 * receipt is payload-free and can be independently fingerprinted.</p>
 */
public final class CapabilityStudioGovernedCandidateService {

    public static final String INTENT_SCHEMA_VERSION =
            "bloge.capabilityStudioGovernedCandidateIntent.v1";

    private static final int MAX_PROTOCOL_BYTES = 16 * 1_048_576;
    private static final String ERROR_PREFIX = "RG.CAPABILITY_STUDIO.GOVERNED_CANDIDATE.";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final ObjectMapper mapper;
    private final CapabilityStudioGovernedCompilationService compiler;
    private final CapabilityStudioGovernedAssetPublisher publisher;
    private final TestSuiteExecutionService suiteExecutions;
    private final TestExecutionApiService childExecutions;
    private final Optional<CapabilityStudioDeploymentCandidateAuthority.Binding> candidateBinding;

    public CapabilityStudioGovernedCandidateService(
            ObjectMapper mapper,
            CapabilityStudioGovernedCompilationService compiler,
            CapabilityStudioGovernedAssetPublisher publisher,
            TestSuiteExecutionService suiteExecutions,
            TestExecutionApiService childExecutions,
            CapabilityStudioDeploymentCandidateAuthority candidateAuthority) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.suiteExecutions = Objects.requireNonNull(suiteExecutions, "suiteExecutions");
        this.childExecutions = Objects.requireNonNull(childExecutions, "childExecutions");
        this.candidateBinding = Objects.requireNonNull(candidateAuthority, "candidateAuthority")
                .current();
    }

    /**
     * Compiles, publishes, independently verifies, and executes one exact candidate.
     *
     * @param graph exact graph for a graph target, otherwise null
     * @param operator exact operator definition for an operator target, otherwise null
     * @param contract exact target Contract
     * @param runtimeTarget independently discovered testing runtime target
     * @param datasetCompilation deterministic Dataset adapter output
     * @param clientRequestId caller-stable suite-run idempotency key
     * @param publicationIdentity dedicated publication workload identity
     * @param executionIdentity dedicated test-execution workload identity
     * @return payload-free exact candidate receipt and terminal suite evidence coordinates
     */
    public CandidateReceipt run(
            GraphDraft graph,
            OperatorDefinition operator,
            ContractDraft contract,
            TestExecutionApiRequest.Target runtimeTarget,
            CapabilityStudioScenarioDatasetCompilation datasetCompilation,
            String clientRequestId,
            IntegrationRequestContext publicationIdentity,
            IntegrationRequestContext executionIdentity) {
        return run(graph, operator, contract, runtimeTarget, datasetCompilation, clientRequestId,
                publicationIdentity, executionIdentity, candidateBinding.orElse(null));
    }

    /**
     * Executes an exact candidate and binds a deployment-owned build identity into the signed
     * aggregate request-metadata fingerprint.
     */
    private CandidateReceipt run(
            GraphDraft graph,
            OperatorDefinition operator,
            ContractDraft contract,
            TestExecutionApiRequest.Target runtimeTarget,
            CapabilityStudioScenarioDatasetCompilation datasetCompilation,
            String clientRequestId,
            IntegrationRequestContext publicationIdentity,
            IntegrationRequestContext executionIdentity,
            CapabilityStudioDeploymentCandidateAuthority.Binding candidateBuild) {
        String normalizedRequestId = normalized(clientRequestId);
        require(!normalizedRequestId.isBlank(), "CLIENT_REQUEST_ID_MISSING", "/clientRequestId");

        CapabilityStudioGovernedCompilation compilation = compiler.compile(
                graph, operator, contract, runtimeTarget, datasetCompilation);
        require(compilation.compiled(), "COMPILATION_BLOCKED", "/compilation");
        CapabilityStudioGovernedAssetPublisher.Receipt publication =
                publisher.publish(compilation, publicationIdentity);

        Map<String, Object> requestMetadata = new LinkedHashMap<>();
        requestMetadata.put("schemaVersion", INTENT_SCHEMA_VERSION);
        requestMetadata.put("compilationFingerprint", publication.compilationFingerprint());
        requestMetadata.put("sourceMapFingerprint", publication.sourceMapFingerprint());
        requestMetadata.put("publicationReceiptFingerprint", publication.receiptFingerprint());
        requestMetadata.put("suiteRef", publication.suiteRef());
        if (candidateBuild != null) {
            requestMetadata.put("candidateBuild", candidateBuild);
        }
        String candidateIntentFingerprint = candidateBuild == null
                ? ""
                : ProtocolFingerprint.of(mapper, requestMetadata);
        TestSuiteExecutionRequest request = new TestSuiteExecutionRequest(
                TestSuiteExecutionRequest.SCHEMA_VERSION,
                new TestSuiteExecutionRequest.SuiteRef(
                        publication.suiteRef().id(),
                        publication.suiteRef().revision(),
                        publication.suiteRef().fingerprint()),
                normalizedRequestId,
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL,
                requestMetadata);
        TestSuiteExecutionResponse response = suiteExecutions.execute(
                publication.suiteRef().id(), request, executionIdentity);
        CandidateEvidence evidence = verifiedEvidence(compilation, publication, response,
                normalizedRequestId, runtimeTarget, executionIdentity,
                candidateIntentFingerprint);
        String receiptFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                new CandidateMaterial(publication, evidence, candidateBuild),
                MAX_PROTOCOL_BYTES);
        return new CandidateReceipt(publication, evidence, candidateBuild, receiptFingerprint);
    }

    private CandidateEvidence verifiedEvidence(
            CapabilityStudioGovernedCompilation compilation,
            CapabilityStudioGovernedAssetPublisher.Receipt publication,
            TestSuiteExecutionResponse response,
            String clientRequestId,
            TestExecutionApiRequest.Target runtimeTarget,
            IntegrationRequestContext executionIdentity,
            String candidateIntentFingerprint) {
        require(response != null && response.evidence() != null,
                "EVIDENCE_MISSING", "/response/evidence");
        TestSuiteRunEvidenceProtocol evidence = response.evidence();
        require(!evidence.status().equals(TestSuiteRunEvidence.Status.RUNNING),
                "EVIDENCE_NOT_TERMINAL", "/response/evidence/status");
        require(response.suiteRunId().equals(evidence.suiteRunId())
                        && !response.suiteRunId().isBlank(),
                "RUN_ID_DRIFT", "/response/suiteRunId");
        require(response.evidenceFingerprint() != null
                        && FINGERPRINT.matcher(response.evidenceFingerprint()).matches(),
                "EVIDENCE_FINGERPRINT_INVALID", "/response/evidenceFingerprint");
        require(clientRequestId.equals(evidence.clientRequestId()),
                "CLIENT_REQUEST_ID_DRIFT", "/response/evidence/clientRequestId");
        require(evidence.suiteRef() != null
                        && publication.suiteRef().id().equals(evidence.suiteRef().suiteId())
                        && publication.suiteRef().revision() == evidence.suiteRef().revision()
                        && publication.suiteRef().fingerprint().equals(
                        evidence.suiteRef().fingerprint()),
                "SUITE_REF_DRIFT", "/response/evidence/suiteRef");

        Map<String, Object> expectedMetadata = compilation.plan().suite().testSuite().metadata();
        Map<String, Object> actualMetadata = evidence.metadata();
        if (!candidateIntentFingerprint.isBlank()) {
            require(candidateIntentFingerprint.equals(stringValue(
                            actualMetadata.get("requestMetadataFingerprint"))),
                    "CANDIDATE_INTENT_FINGERPRINT_DRIFT",
                    "/response/evidence/metadata/requestMetadataFingerprint");
        }
        String expectedProvenance = stringValue(
                expectedMetadata.get("governedProvenanceFingerprint"));
        String actualProvenance = stringValue(
                actualMetadata.get("governedProvenanceFingerprint"));
        require(FINGERPRINT.matcher(expectedProvenance).matches()
                        && expectedProvenance.equals(actualProvenance),
                "PROVENANCE_FINGERPRINT_DRIFT",
                "/response/evidence/metadata/governedProvenanceFingerprint");
        require(publication.sourceMapFingerprint().equals(stringValue(
                        actualMetadata.get("governedSourceMapFingerprint"))),
                "SOURCE_MAP_FINGERPRINT_DRIFT",
                "/response/evidence/metadata/governedSourceMapFingerprint");
        try {
            require(Objects.equals(
                            ScenarioGovernedProvenanceMetadataCodec.decodeExactRefs(
                                    expectedMetadata.get("governedExactRefs")),
                            ScenarioGovernedProvenanceMetadataCodec.decodeExactRefs(
                                    actualMetadata.get("governedExactRefs"))),
                    "EXACT_REF_CLOSURE_DRIFT",
                    "/response/evidence/metadata/governedExactRefs");
        } catch (IllegalArgumentException invalid) {
            throw new CapabilityStudioGovernedCompilationException(
                    ERROR_PREFIX + "EXACT_REF_CLOSURE_DRIFT",
                    "/response/evidence/metadata/governedExactRefs");
        }

        List<ChildRunRef> childRuns = evidence.caseResults().stream()
                .map(result -> verifiedChild(result, runtimeTarget, executionIdentity))
                .toList();
        return new CandidateEvidence(
                response.suiteRunId(), response.evidenceFingerprint(), evidence.status().name(),
                actualProvenance, publication.sourceMapFingerprint(),
                candidateIntentFingerprint, childRuns);
    }

    private ChildRunRef verifiedChild(
            TestSuiteRunEvidence.CaseResult aggregate,
            TestExecutionApiRequest.Target runtimeTarget,
            IntegrationRequestContext executionIdentity) {
        require(aggregate != null && !normalized(aggregate.runId()).isBlank(),
                "CHILD_RUN_ID_MISSING", "/response/evidence/caseResults/runId");
        TestExecutionApiResponse response = childExecutions.find(
                aggregate.runId(), TestExecutionApiRequest.Verbosity.FULL, executionIdentity);
        require(response != null && response.evidence() != null,
                "CHILD_EVIDENCE_MISSING", "/child/evidence");
        TestRunEvidence evidence = response.evidence();
        require(aggregate.runId().equals(response.runId())
                        && aggregate.runId().equals(evidence.runId()),
                "CHILD_RUN_ID_DRIFT", "/child/runId");
        require(response.target() != null && runtimeTarget != null
                        && runtimeTarget.kind().equals(response.target().kind())
                        && runtimeTarget.id().equals(response.target().id())
                        && runtimeTarget.fingerprint().equals(response.target().fingerprint())
                        && runtimeTarget.fingerprint().equals(evidence.targetFingerprint()),
                "CHILD_TARGET_DRIFT", "/child/target");
        require(aggregate.fixtureBundleRef() != null && response.fixtureBundleRef() != null
                        && aggregate.fixtureBundleRef().fixtureBundleId().equals(
                        response.fixtureBundleRef().fixtureBundleId())
                        && aggregate.fixtureBundleRef().revision()
                        == response.fixtureBundleRef().revision()
                        && aggregate.fixtureBundleRef().fingerprint().equals(
                        response.fixtureBundleRef().fingerprint())
                        && aggregate.fixtureBundleRef().fingerprint().equals(
                        evidence.fixtureBundleFingerprint()),
                "CHILD_FIXTURE_DRIFT", "/child/fixtureBundleRef");
        require(aggregate.evidenceStatus() == evidence.status()
                        && aggregate.evidenceClass() == evidence.evidenceClass()
                        && aggregate.status() == TestSuiteRunEvidence.CaseStatus.PASSED
                        && evidence.status() == TestRunEvidence.Status.PASSED,
                "CHILD_STATUS_DRIFT", "/child/evidence/status");
        require(response.integrity() != null && response.integrity().independentlyVerifiable()
                        && response.integrity().projection()
                        == com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity.Projection.FULL,
                "CHILD_INTEGRITY_INVALID", "/child/integrity");
        require(FINGERPRINT.matcher(evidence.semanticResultFingerprint()).matches(),
                "CHILD_SEMANTIC_FINGERPRINT_INVALID",
                "/child/evidence/semanticResultFingerprint");
        long assertionsPassed = evidence.assertionResults().stream()
                .filter(TestRunEvidence.AssertionResult::passed).count();
        require(aggregate.assertionsEvaluated() == evidence.assertionResults().size()
                        && aggregate.assertionsPassed() == assertionsPassed,
                "CHILD_ASSERTION_COUNT_DRIFT", "/child/evidence/assertionResults");

        List<NodeFact> nodes = evidence.nodeTrace().stream()
                .map(node -> new NodeFact(
                        node.nodeId(), node.operatorRef(), node.status(), node.errorCode(),
                        node.attempts().stream()
                                .map(attempt -> new AttemptFact(
                                        attempt.attempt(), attempt.status(), attempt.errorCode()))
                                .toList()))
                .toList();
        int fixtureControlsSatisfied = (int) evidence.fixtureConsumptions().stream()
                .filter(value -> "SATISFIED".equals(value.status())).count();
        return new ChildRunRef(
                aggregate.caseId(), aggregate.runId(), aggregate.status().name(),
                aggregate.fixtureBundleRef().fixtureBundleId(),
                aggregate.fixtureBundleRef().revision(),
                aggregate.fixtureBundleRef().fingerprint(), evidence.status().name(),
                evidence.evidenceClass().name(), response.integrity().evidenceFingerprint(),
                evidence.semanticResultFingerprint(),
                evidence.assertionResults().size(), Math.toIntExact(assertionsPassed),
                evidence.fixtureConsumptions().size(), fixtureControlsSatisfied, nodes);
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? normalized(string) : "";
    }

    private static void require(boolean condition, String suffix, String path) {
        if (!condition) {
            throw new CapabilityStudioGovernedCompilationException(ERROR_PREFIX + suffix, path);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /** Payload-free terminal child-run coordinate retained in candidate evidence. */
    public record ChildRunRef(
            String caseId,
            String runId,
            String status,
            String fixtureBundleId,
            long fixtureRevision,
            String fixtureFingerprint,
            String evidenceStatus,
            String evidenceClass,
            String evidenceFingerprint,
            String semanticResultFingerprint,
            int assertionsEvaluated,
            int assertionsPassed,
            int fixtureControlsEvaluated,
            int fixtureControlsSatisfied,
            List<NodeFact> nodes) {
        public ChildRunRef {
            caseId = normalized(caseId);
            runId = normalized(runId);
            status = normalized(status);
            fixtureBundleId = normalized(fixtureBundleId);
            fixtureFingerprint = normalized(fixtureFingerprint);
            evidenceStatus = normalized(evidenceStatus);
            evidenceClass = normalized(evidenceClass);
            evidenceFingerprint = normalized(evidenceFingerprint);
            semanticResultFingerprint = normalized(semanticResultFingerprint);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    /** Payload-free runtime node fact retained for business Oracle evaluation. */
    public record NodeFact(
            String nodeId,
            String operatorRef,
            String status,
            String errorCode,
            List<AttemptFact> attempts) {
        public NodeFact {
            nodeId = normalized(nodeId);
            operatorRef = normalized(operatorRef);
            status = normalized(status);
            errorCode = normalized(errorCode);
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }
    }

    /** Payload-free delegate-attempt fact retained for timeout Oracle evaluation. */
    public record AttemptFact(int attempt, String status, String errorCode) {
        public AttemptFact {
            status = normalized(status);
            errorCode = normalized(errorCode);
        }
    }

    /** Payload-free terminal suite-run evidence coordinates. */
    public record CandidateEvidence(
            String suiteRunId,
            String evidenceFingerprint,
            String status,
            String provenanceFingerprint,
            String sourceMapFingerprint,
            String candidateIntentFingerprint,
            List<ChildRunRef> childRuns) {
        public CandidateEvidence {
            suiteRunId = normalized(suiteRunId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            status = normalized(status);
            provenanceFingerprint = normalized(provenanceFingerprint);
            sourceMapFingerprint = normalized(sourceMapFingerprint);
            candidateIntentFingerprint = normalized(candidateIntentFingerprint);
            childRuns = childRuns == null ? List.of() : List.copyOf(childRuns);
        }
    }

    /** Complete payload-free receipt binding compilation, publication, and execution evidence. */
    public record CandidateReceipt(
            CapabilityStudioGovernedAssetPublisher.Receipt publication,
            CandidateEvidence evidence,
            CapabilityStudioDeploymentCandidateAuthority.Binding candidateBuild,
            String receiptFingerprint) {
        public CandidateReceipt {
            Objects.requireNonNull(publication, "publication");
            Objects.requireNonNull(evidence, "evidence");
            receiptFingerprint = normalized(receiptFingerprint);
        }
    }

    private record CandidateMaterial(
            CapabilityStudioGovernedAssetPublisher.Receipt publication,
            CandidateEvidence evidence,
            CapabilityStudioDeploymentCandidateAuthority.Binding candidateBuild) {
    }
}
