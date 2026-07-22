package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureProjectionRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Protected integration transport for projecting a visual graph draft into a
 * sealed capability closure.
 *
 * <p>The request carries only portable authoring data. Enterprise scope,
 * purpose, ownership, region, and lifecycle are derived by the authenticated
 * service boundary so a caller cannot forge governance identity in the
 * payload.</p>
 */
@RestController
@RequestMapping("/api/integration/capability-closures")
public class CapabilityClosureIntegrationController {
    private final CapabilityClosureIntegrationService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the protected projection endpoint.
     *
     * @param service scope-enforcing closure projection boundary
     * @param authenticator integration workload authenticator and purpose authorizer
     */
    public CapabilityClosureIntegrationController(CapabilityClosureIntegrationService service,
                                                  IntegrationRequestAuthenticator authenticator) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /**
     * Projects a portable graph draft into one sealed, server-scoped draft
     * capability closure.
     *
     * @param request portable graph draft and projection metadata
     * @param headers authentication and authorized-purpose headers
     * @return integration envelope containing the integrity-sealed closure
     */
    @PostMapping("/project")
    public IntegrationEnvelope<CapabilityClosure> project(
            @RequestBody CapabilityClosureProjectionRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.project(request,
                authenticator.authenticate(headers, IntegrationOperation.CAPABILITY_CLOSURE_PROJECTION));
    }
}
