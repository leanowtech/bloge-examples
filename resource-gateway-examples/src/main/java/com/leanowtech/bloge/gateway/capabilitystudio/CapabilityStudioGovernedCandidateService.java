package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final TestSuiteExecutionService executions;

    public CapabilityStudioGovernedCandidateService(
            ObjectMapper mapper,
            CapabilityStudioGovernedCompilationService compiler,
            CapabilityStudioGovernedAssetPublisher publisher,
            TestSuiteExecutionService executions) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.executions = Objects.requireNonNull(executions, "executions");
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
        TestSuiteExecutionRequest request = new TestSuiteExecutionRequest(
                TestSuiteExecutionRequest.SCHEMA_VERSION,
                new TestSuiteExecutionRequest.SuiteRef(
                        publication.suiteRef().id(),
                        publication.suiteRef().revision(),
                        publication.suiteRef().fingerprint()),
                normalizedRequestId,
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL,
                requestMetadata);
        TestSuiteExecutionResponse response = executions.execute(
                publication.suiteRef().id(), request, executionIdentity);
        CandidateEvidence evidence = verifiedEvidence(compilation, publication, response,
                normalizedRequestId);
        String receiptFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                new CandidateMaterial(publication, evidence),
                MAX_PROTOCOL_BYTES);
        return new CandidateReceipt(publication, evidence, receiptFingerprint);
    }

    private CandidateEvidence verifiedEvidence(
            CapabilityStudioGovernedCompilation compilation,
            CapabilityStudioGovernedAssetPublisher.Receipt publication,
            TestSuiteExecutionResponse response,
            String clientRequestId) {
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
        require(Objects.equals(expectedMetadata.get("governedExactRefs"),
                        actualMetadata.get("governedExactRefs")),
                "EXACT_REF_CLOSURE_DRIFT",
                "/response/evidence/metadata/governedExactRefs");

        List<ChildRunRef> childRuns = evidence.caseResults().stream()
                .map(result -> new ChildRunRef(
                        result.caseId(), result.runId(), result.status().name(),
                        result.fixtureBundleRef() == null ? "" :
                                result.fixtureBundleRef().fixtureBundleId(),
                        result.fixtureBundleRef() == null ? 0 :
                                result.fixtureBundleRef().revision(),
                        result.fixtureBundleRef() == null ? "" :
                                result.fixtureBundleRef().fingerprint()))
                .toList();
        return new CandidateEvidence(
                response.suiteRunId(), response.evidenceFingerprint(), evidence.status().name(),
                actualProvenance, publication.sourceMapFingerprint(), childRuns);
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
            String fixtureFingerprint) {
        public ChildRunRef {
            caseId = normalized(caseId);
            runId = normalized(runId);
            status = normalized(status);
            fixtureBundleId = normalized(fixtureBundleId);
            fixtureFingerprint = normalized(fixtureFingerprint);
        }
    }

    /** Payload-free terminal suite-run evidence coordinates. */
    public record CandidateEvidence(
            String suiteRunId,
            String evidenceFingerprint,
            String status,
            String provenanceFingerprint,
            String sourceMapFingerprint,
            List<ChildRunRef> childRuns) {
        public CandidateEvidence {
            suiteRunId = normalized(suiteRunId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            status = normalized(status);
            provenanceFingerprint = normalized(provenanceFingerprint);
            sourceMapFingerprint = normalized(sourceMapFingerprint);
            childRuns = childRuns == null ? List.of() : List.copyOf(childRuns);
        }
    }

    /** Complete payload-free receipt binding compilation, publication, and execution evidence. */
    public record CandidateReceipt(
            CapabilityStudioGovernedAssetPublisher.Receipt publication,
            CandidateEvidence evidence,
            String receiptFingerprint) {
        public CandidateReceipt {
            Objects.requireNonNull(publication, "publication");
            Objects.requireNonNull(evidence, "evidence");
            receiptFingerprint = normalized(receiptFingerprint);
        }
    }

    private record CandidateMaterial(
            CapabilityStudioGovernedAssetPublisher.Receipt publication,
            CandidateEvidence evidence) {
    }
}
