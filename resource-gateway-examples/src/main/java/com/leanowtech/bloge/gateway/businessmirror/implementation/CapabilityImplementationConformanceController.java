package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected HTTP surface for exact same-suite implementation conformance. */
@RestController
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
@RequestMapping("/api/business-mirror")
public final class CapabilityImplementationConformanceController {
    private final CapabilityImplementationConformanceService service;
    private final IntegrationRequestAuthenticator authenticator;

    public CapabilityImplementationConformanceController(
            CapabilityImplementationConformanceService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping("/proposals/{proposalId}/revisions/{revision}/implementation-conformances")
    public ResponseEntity<StoredCapabilityImplementationConformance> conform(
            @PathVariable String proposalId,
            @PathVariable long revision,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String conformanceId,
            @RequestBody CapabilityImplementationConformanceRequest request,
            @RequestHeader HttpHeaders headers) {
        StoredCapabilityImplementationConformance result = service.conform(
                proposalId, revision, conformanceId, request,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_CONFORM));
        return ResponseEntity.ok()
                .header("ETag", '"' + result.report().fingerprint() + '"')
                .body(result);
    }

    @GetMapping("/implementation-bindings/{bindingId}/revisions/{revision}/conformance")
    public StoredCapabilityImplementationConformance find(
            @PathVariable String bindingId,
            @PathVariable long revision,
            @RequestHeader HttpHeaders headers) {
        return service.findByBinding(bindingId, revision, context(headers,
                IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_CONFORMANCE_READ));
    }

    private IntegrationRequestContext context(
            HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
