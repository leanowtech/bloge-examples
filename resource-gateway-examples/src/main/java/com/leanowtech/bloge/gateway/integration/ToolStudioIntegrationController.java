package com.leanowtech.bloge.gateway.integration;

import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

/**
 * Stable Resource Gateway integration API consumed by ANEKE Tool Studio.
 */
@RestController
@RequestMapping("/api/integration")
public class ToolStudioIntegrationController {

    private final ToolStudioIntegrationService service;
    private final IntegrationChangeFeedService changeFeedService;
    private final IntegrationRequestAuthenticator authenticator;

    ToolStudioIntegrationController(ToolStudioIntegrationService service) {
        this(service, null, null);
    }

    ToolStudioIntegrationController(ToolStudioIntegrationService service,
                                    IntegrationChangeFeedService changeFeedService) {
        this(service, changeFeedService, null);
    }

    @Autowired
    public ToolStudioIntegrationController(ToolStudioIntegrationService service,
                                           IntegrationChangeFeedService changeFeedService,
                                           IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.changeFeedService = changeFeedService;
        this.authenticator = authenticator;
    }

    @GetMapping("/capabilities")
    public IntegrationEnvelope<IntegrationCapabilities> capabilities() {
        return service.capabilities();
    }

    @GetMapping("/drafts/{draftId}/export")
    public IntegrationEnvelope<GraphDraftIntegrationBundle> exportDraft(
            @PathVariable String draftId,
            @RequestParam(defaultValue = "0") long revision,
            @RequestHeader HttpHeaders headers) {
        return service.exportDraft(draftId, revision, requestContext(headers, IntegrationOperation.DRAFT_EXPORT));
    }

    @GetMapping("/runs/{runId}/evidence")
    public IntegrationEnvelope<RunEvidenceBundle> runEvidence(@PathVariable String runId,
                                                              @RequestHeader HttpHeaders headers) {
        return service.runEvidence(runId, requestContext(headers, IntegrationOperation.RUN_EVIDENCE_READ));
    }

    @GetMapping("/runs/{runId}/replay")
    public IntegrationEnvelope<PayloadReplayBundle> replay(@PathVariable String runId,
                                                           @RequestHeader HttpHeaders headers) {
        return service.replay(runId, requestContext(headers, IntegrationOperation.RECORDED_PAYLOAD_READ));
    }

    @PostMapping("/runs/{runId}/replay")
    public IntegrationEnvelope<ReplayExecutionResult> executeReplay(
            @PathVariable String runId,
            @RequestBody ReplayExecutionRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.executeReplay(runId, request,
                requestContext(headers, IntegrationOperation.RECORDED_REPLAY));
    }

    @GetMapping("/evidence-keys/{keyId}")
    public IntegrationEnvelope<VisualEvidenceSigner.VerificationKey> evidenceKey(@PathVariable String keyId) {
        return service.evidenceKey(keyId);
    }

    @PostMapping("/gate-results")
    public IntegrationEnvelope<GovernanceGateResult> submitGateResult(
            @RequestBody GovernanceGateResult result,
            @RequestHeader HttpHeaders headers) {
        return service.submitGateResult(result, requestContext(headers, IntegrationOperation.GATE_RESULT_WRITE));
    }

    @GetMapping("/drafts/{draftId}/gate-result")
    public IntegrationEnvelope<GovernanceGateView> governanceGate(@PathVariable String draftId,
                                                                  @RequestHeader HttpHeaders headers) {
        return service.governanceGate(draftId, requestContext(headers, IntegrationOperation.GATE_RESULT_READ));
    }

    @GetMapping("/events")
    public IntegrationEnvelope<IntegrationChangeFeed> events(
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader HttpHeaders headers) {
        return requireChangeFeed().events(cursor, limit,
                requestContext(headers, IntegrationOperation.CHANGE_SYNC));
    }

    @GetMapping("/reconciliation")
    public IntegrationEnvelope<IntegrationReconciliationSnapshot> reconciliation(
            @RequestHeader HttpHeaders headers) {
        return requireChangeFeed().reconciliation(requestContext(headers, IntegrationOperation.CHANGE_SYNC));
    }

    @GetMapping("/operator-libraries/{libraryId}")
    public IntegrationEnvelope<com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary> operatorLibrary(
            @PathVariable String libraryId,
            @RequestParam(defaultValue = "0") long revision,
            @RequestHeader HttpHeaders headers) {
        return requireChangeFeed().operatorLibrary(libraryId, revision,
                requestContext(headers, IntegrationOperation.CHANGE_SYNC));
    }

    @GetMapping("/operator-test-suites/{suiteId}")
    public IntegrationEnvelope<com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite> testSuite(
            @PathVariable String suiteId,
            @RequestParam(defaultValue = "0") long revision,
            @RequestHeader HttpHeaders headers) {
        return requireChangeFeed().testSuite(suiteId, revision,
                requestContext(headers, IntegrationOperation.CHANGE_SYNC));
    }

    private IntegrationChangeFeedService requireChangeFeed() {
        if (changeFeedService == null) {
            throw new IllegalStateException("Integration change feed service is unavailable");
        }
        return changeFeedService;
    }

    private IntegrationRequestContext requestContext(HttpHeaders headers, IntegrationOperation operation) {
        if (authenticator != null) {
            return authenticator.authenticate(headers, operation);
        }
        return legacyRequestContext(headers);
    }

    private static IntegrationRequestContext legacyRequestContext(HttpHeaders headers) {
        String correlationId = header(headers, "X-Correlation-Id");
        if (correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        return new IntegrationRequestContext(
                header(headers, "X-Tenant-Id"),
                header(headers, "X-Organization-Id"),
                header(headers, "X-Project-Id"),
                header(headers, "X-Environment-Id"),
                header(headers, "X-Region"),
                defaulted(header(headers, "X-Actor-Type"), "WORKLOAD"),
                header(headers, "X-Actor-Id"),
                header(headers, "X-Delegated-By"),
                header(headers, "X-Purpose"),
                correlationId
        );
    }

    private static String header(HttpHeaders headers, String name) {
        return headers == null ? "" : defaulted(headers.getFirst(name), "");
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
