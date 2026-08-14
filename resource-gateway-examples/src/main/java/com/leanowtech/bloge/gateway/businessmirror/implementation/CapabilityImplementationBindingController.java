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

/** Protected HTTP surface for runtime-owned Proposal implementation binding. */
@RestController
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
@RequestMapping("/api/business-mirror")
public final class CapabilityImplementationBindingController {
    private final CapabilityImplementationBindingService service;
    private final IntegrationRequestAuthenticator authenticator;

    public CapabilityImplementationBindingController(
            CapabilityImplementationBindingService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping("/proposals/{proposalId}/revisions/{revision}/implementation-bindings")
    public ResponseEntity<StoredCapabilityImplementationBinding> bind(
            @PathVariable String proposalId,
            @PathVariable long revision,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String bindingId,
            @RequestBody CapabilityImplementationBindingRequest request,
            @RequestHeader HttpHeaders headers) {
        CapabilityImplementationBindingRepository.CreateResult result = service.bind(
                proposalId, revision, bindingId, request,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_BIND));
        return ResponseEntity.status(result.created() ? 201 : 200)
                .header("Idempotent-Replayed", Boolean.toString(!result.created()))
                .header("ETag", '"' + result.binding().binding().fingerprint() + '"')
                .body(result.binding());
    }

    @GetMapping("/implementation-bindings/{bindingId}")
    public StoredCapabilityImplementationBinding find(
            @PathVariable String bindingId,
            @RequestHeader HttpHeaders headers) {
        return service.find(bindingId,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_READ));
    }

    private IntegrationRequestContext context(
            HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
