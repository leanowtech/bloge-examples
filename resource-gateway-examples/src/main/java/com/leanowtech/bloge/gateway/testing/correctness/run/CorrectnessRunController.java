package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Authenticated no-store HTTP adapter for correctness execution safety review. */
@RestController
@ConditionalOnBean(CorrectnessPreflightFacade.class)
@RequestMapping("/api/visual/correctness-runs:preflight")
public final class CorrectnessRunController {

    private final CorrectnessPreflightFacade preflight;
    private final IntegrationRequestAuthenticator authenticator;

    public CorrectnessRunController(
            CorrectnessPreflightFacade preflight,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.preflight = preflight;
        this.authenticator = authenticator;
    }

    @PostMapping
    public ResponseEntity<CorrectnessApiEnvelope<CorrectnessPreflightReport>> preflight(
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) CorrectnessPreflightRequest request
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_PREFLIGHT);
        try {
            CorrectnessPreflightReport report = preflight.preflight(request, identity);
            CorrectnessApiEnvelope<CorrectnessPreflightReport> envelope =
                    CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope(identity),
                            List.of("CORRECTNESS_PREFLIGHT_V1"), report);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(envelope);
        } catch (CorrectnessRunException failure) {
            throw new IntegrationProblemException(new IntegrationProblem(
                    "", "urn:bloge:problem:correctness-run", failure.getMessage(),
                    failure.status(), failure.code(), failure.retryable(),
                    identity.correlationId(), Map.of()));
        }
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }
}
