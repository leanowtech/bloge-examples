package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Protected Stage 1 mirror planning transport.
 *
 * <p>The controller is physically absent unless the isolated mirror composition is enabled under
 * a test or staging profile. Authentication and purpose authorization always happen before an
 * application-service lookup.</p>
 */
@RestController
@RequestMapping("/api/mirror/plans")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public class MirrorIntegrationController {
    private final MirrorPlanIntegrationService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final MirrorPlanRequestDecoder decoder;

    /** Creates the protected plan transport. */
    public MirrorIntegrationController(
            MirrorPlanIntegrationService service,
            IntegrationRequestAuthenticator authenticator,
            MirrorPlanRequestDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /** Compiles an exact content-addressed mirror plan. */
    @PostMapping
    public IntegrationEnvelope<MirrorPlan> create(
            @RequestBody JsonNode request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_PLAN_COMPILE);
        return IntegrationEnvelope.of("MIRROR_PLAN", MirrorPlan.SCHEMA_VERSION,
                service.create(decoder.decode(request, identity), identity));
    }

    /** Reads one verified payload-free plan in the authenticated enterprise scope. */
    @GetMapping("/{planId}")
    public IntegrationEnvelope<MirrorPlan> find(
            @PathVariable String planId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_PLAN_READ);
        return IntegrationEnvelope.of("MIRROR_PLAN", MirrorPlan.SCHEMA_VERSION,
                service.find(planId, identity));
    }
}
