package com.leanowtech.bloge.gateway.integration;

import org.springframework.http.HttpHeaders;
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

    public ToolStudioIntegrationController(ToolStudioIntegrationService service) {
        this.service = service;
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
        return service.exportDraft(draftId, revision, requestContext(headers));
    }

    @GetMapping("/runs/{runId}/evidence")
    public IntegrationEnvelope<RunEvidenceBundle> runEvidence(@PathVariable String runId,
                                                              @RequestHeader HttpHeaders headers) {
        return service.runEvidence(runId, requestContext(headers));
    }

    @GetMapping("/runs/{runId}/replay")
    public IntegrationEnvelope<PayloadReplayBundle> replay(@PathVariable String runId,
                                                           @RequestHeader HttpHeaders headers) {
        return service.replay(runId, requestContext(headers));
    }

    @GetMapping("/evidence-keys/{keyId}")
    public IntegrationEnvelope<VisualEvidenceSigner.VerificationKey> evidenceKey(@PathVariable String keyId) {
        return service.evidenceKey(keyId);
    }

    @PostMapping("/gate-results")
    public IntegrationEnvelope<GovernanceGateResult> submitGateResult(
            @RequestBody GovernanceGateResult result,
            @RequestHeader HttpHeaders headers) {
        return service.submitGateResult(result, requestContext(headers));
    }

    @GetMapping("/drafts/{draftId}/gate-result")
    public IntegrationEnvelope<GovernanceGateView> governanceGate(@PathVariable String draftId,
                                                                  @RequestHeader HttpHeaders headers) {
        return service.governanceGate(draftId, requestContext(headers));
    }

    private static IntegrationRequestContext requestContext(HttpHeaders headers) {
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
