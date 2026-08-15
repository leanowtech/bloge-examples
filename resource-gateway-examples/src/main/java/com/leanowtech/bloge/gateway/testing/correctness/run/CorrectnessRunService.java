package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublicationAttempt;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Governed correctness run admission over the existing immutable TestSuite runner. */
public final class CorrectnessRunService {

    private static final String CLIENT_KEY_SCHEMA = "bloge.correctnessRunClientKey.v1";

    private final CorrectnessPreflightFacade preflight;
    private final CorrectnessPublicationRepository publications;
    private final TestSuiteExecutionService suiteExecutions;
    private final CorrectnessEvidenceCompanionFactory companions;
    private final CorrectnessEvidenceRepository evidence;
    private final ObjectMapper mapper;

    public CorrectnessRunService(
            CorrectnessPreflightFacade preflight,
            CorrectnessPublicationRepository publications,
            TestSuiteExecutionService suiteExecutions,
            CorrectnessEvidenceCompanionFactory companions,
            CorrectnessEvidenceRepository evidence,
            ObjectMapper mapper
    ) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.suiteExecutions = Objects.requireNonNull(suiteExecutions, "suiteExecutions");
        this.companions = Objects.requireNonNull(companions, "companions");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public CorrectnessRunResponse execute(
            CorrectnessRunRequest request,
            IntegrationRequestContext identity
    ) {
        requireIdentity(identity);
        if (request == null) {
            throw failure(400, "RG.CORRECTNESS.RUN_REQUEST_REQUIRED",
                    "A versioned correctness run request is required", false);
        }
        CorrectnessPreflightReport reviewed = preflight.preflight(
                new CorrectnessPreflightRequest(
                        "", request.publicationRef(), request.selection()), identity);
        if (!reviewed.preflightFingerprint().equals(request.preflightFingerprint())) {
            throw failure(409, "RG.CORRECTNESS.PREFLIGHT_STALE",
                    "The effective execution plan changed after it was reviewed", false);
        }
        if (!reviewed.blockers().isEmpty()) {
            throw failure(422, "RG.CORRECTNESS.PREFLIGHT_BLOCKED",
                    "The reviewed execution plan contains safety blockers", false);
        }

        EnterpriseScope scope = scope(identity);
        StoredCorrectnessPublication publication = publications.findPublication(
                        scope, request.publicationRef().publicationId())
                .orElseThrow(() -> failure(404, "RG.CORRECTNESS.PUBLICATION_NOT_FOUND",
                        "Correctness Publication was not found in the authorized scope", false));
        StoredCorrectnessPublicationAttempt committedAttempt = publications
                .findCommittedAttemptForPublication(scope, request.publicationRef().publicationId())
                .orElseThrow(() -> failure(
                        409, "RG.CORRECTNESS.PUBLICATION_TRACEABILITY_UNAVAILABLE",
                        "Publication predates exact source-map binding and must be republished", false));
        String clientFingerprint = clientFingerprint(request.clientRequestId(), identity);
        TestSuiteExecutionRequest suiteRequest = suiteRequest(
                request, reviewed, clientFingerprint);
        TestSuiteExecutionResponse suiteResponse = request.selection().mode()
                == CorrectnessRunRequest.Selection.Mode.ALL
                ? suiteExecutions.execute(
                suiteRequest.suiteRef().suiteId(), suiteRequest, identity)
                : suiteExecutions.executeSelected(
                suiteRequest.suiteRef().suiteId(), suiteRequest,
                request.selection().caseIds(), identity);
        if (suiteResponse.evidence().status() == TestSuiteRunEvidence.Status.RUNNING) {
            return new CorrectnessRunResponse(
                    "", CorrectnessRunResponse.Status.RUNNING, suiteResponse, null);
        }

        StoredCorrectnessEvidenceCompanion companion = evidence.find(
                        scope, suiteResponse.suiteRunId())
                .map(existing -> requireExisting(
                        existing, request, reviewed, suiteResponse, clientFingerprint))
                .orElseGet(() -> evidence.saveIfAbsent(scope, companions.create(
                        request, reviewed, publication, committedAttempt, suiteResponse,
                        clientFingerprint, identity)));
        return new CorrectnessRunResponse(
                "", CorrectnessRunResponse.Status.EVIDENCE_AVAILABLE,
                suiteResponse, companion);
    }

    public StoredCorrectnessEvidenceCompanion findEvidence(
            String suiteRunId,
            IntegrationRequestContext identity
    ) {
        requireIdentity(identity);
        return evidence.find(scope(identity), normalized(suiteRunId))
                .orElseThrow(() -> failure(404, "RG.CORRECTNESS.EVIDENCE_NOT_FOUND",
                        "Correctness evidence companion was not found in the authorized scope",
                        false));
    }

    private TestSuiteExecutionRequest suiteRequest(
            CorrectnessRunRequest request,
            CorrectnessPreflightReport reviewed,
            String clientFingerprint
    ) {
        ExactAssetRef suite = reviewed.compiledTestSuiteRef();
        return new TestSuiteExecutionRequest(
                "", new TestSuiteExecutionRequest.SuiteRef(
                suite.id(), suite.revision(), suite.fingerprint()),
                "correctness-run-" + clientFingerprint.substring("sha256:".length()),
                request.strategy() == CorrectnessRunRequest.Strategy.FAIL_FAST
                        ? TestSuiteExecutionRequest.Strategy.FAIL_FAST
                        : TestSuiteExecutionRequest.Strategy.COLLECT_ALL,
                Map.of(
                        "source", "CORRECTNESS_RUN",
                        "publicationFingerprint", request.publicationRef().fingerprint(),
                        "selectionFingerprint", request.selection().selectionFingerprint(),
                        "preflightFingerprint", request.preflightFingerprint(),
                        "clientRequestFingerprint", clientFingerprint));
    }

    private static StoredCorrectnessEvidenceCompanion requireExisting(
            StoredCorrectnessEvidenceCompanion existing,
            CorrectnessRunRequest request,
            CorrectnessPreflightReport reviewed,
            TestSuiteExecutionResponse suiteResponse,
            String clientFingerprint
    ) {
        CorrectnessEvidenceCompanion value = existing.companion();
        Map<String, String> plans = value.caseExecutions().stream().collect(
                java.util.stream.Collectors.toMap(
                        CorrectnessEvidenceCompanion.CaseExecutionRef::caseId,
                        CorrectnessEvidenceCompanion.CaseExecutionRef::executionPlanFingerprint));
        boolean samePlans = reviewed.cases().stream().allMatch(testCase ->
                testCase.executionPlanFingerprint().equals(plans.get(testCase.caseId())))
                && plans.size() == reviewed.cases().size();
        if (!value.publicationRef().equals(request.publicationRef())
                || !value.selection().equals(request.selection())
                || !value.suiteRunId().equals(suiteResponse.suiteRunId())
                || !value.suiteEvidenceFingerprint().equals(
                suiteResponse.evidenceFingerprint())
                || !value.clientRequestFingerprint().equals(clientFingerprint)
                || !samePlans) {
            throw failure(503, "RG.CORRECTNESS.EVIDENCE_IDEMPOTENCY_CONFLICT",
                    "Stored correctness evidence differs from the idempotent run closure", false);
        }
        return existing;
    }

    private String clientFingerprint(
            String clientRequestId,
            IntegrationRequestContext identity
    ) {
        return CorrectnessProtocolFingerprint.derivedFingerprint(mapper, Map.of(
                "schemaVersion", CLIENT_KEY_SCHEMA,
                "scope", scope(identity),
                "actorId", identity.actorId(),
                "clientRequestId", clientRequestId));
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        if (identity == null) {
            throw failure(401, "RG.CORRECTNESS.IDENTITY_REQUIRED",
                    "Verified integration identity is required", false);
        }
        identity.requireComplete();
        if (identity.projectId().isBlank() || identity.region().isBlank()) {
            throw failure(400, "RG.CORRECTNESS.ENTERPRISE_SCOPE_REQUIRED",
                    "Project and region are required for correctness execution", false);
        }
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static CorrectnessRunException failure(
            int status, String code, String message, boolean retryable
    ) {
        return new CorrectnessRunException(status, code, message, retryable);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
