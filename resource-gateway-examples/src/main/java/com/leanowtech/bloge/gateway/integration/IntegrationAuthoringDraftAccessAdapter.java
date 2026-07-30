package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftAccessPort;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPrincipal;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Maps gateway authentication policy onto the visual authoring lifecycle access port.
 */
@Component
public final class IntegrationAuthoringDraftAccessAdapter
        implements AuthoringDraftAccessPort {

    private final IntegrationRequestAuthenticator authenticator;

    public IntegrationAuthoringDraftAccessAdapter(
            IntegrationRequestAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    public AuthoringPrincipal authenticate(HttpHeaders headers, Action action) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers,
                switch (Objects.requireNonNull(action, "action")) {
                    case READ -> IntegrationOperation.AUTHORING_DRAFT_READ;
                    case WRITE -> IntegrationOperation.AUTHORING_DRAFT_WRITE;
                    case COMMIT -> IntegrationOperation.AUTHORING_DRAFT_COMMIT;
                });
        return new AuthoringPrincipal(
                identity.tenantId(),
                identity.organizationId(),
                identity.projectId(),
                identity.environmentId(),
                identity.region(),
                identity.actorId());
    }
}
