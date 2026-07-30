package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestAccessPort;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestPrincipal;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Maps gateway authentication policy onto the visual authoring test access port.
 */
@Component
public final class IntegrationAuthoringTestAccessAdapter implements AuthoringTestAccessPort {

    private final IntegrationRequestAuthenticator authenticator;

    public IntegrationAuthoringTestAccessAdapter(
            IntegrationRequestAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    public AuthoringTestPrincipal authenticate(
            HttpHeaders headers,
            Action action) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers,
                switch (Objects.requireNonNull(action, "action")) {
                    case DRAFT_READ -> IntegrationOperation.AUTHORING_DRAFT_READ;
                    case EXECUTE -> IntegrationOperation.AUTHORING_TEST_EXECUTE;
                    case EVIDENCE_READ ->
                            IntegrationOperation.AUTHORING_TEST_EVIDENCE_READ;
                    case GATE_READ -> IntegrationOperation.AUTHORING_TEST_GATE_READ;
                });
        return new AuthoringTestPrincipal(
                identity.tenantId(),
                identity.organizationId(),
                identity.projectId(),
                identity.environmentId(),
                identity.region(),
                identity.actorId());
    }
}
