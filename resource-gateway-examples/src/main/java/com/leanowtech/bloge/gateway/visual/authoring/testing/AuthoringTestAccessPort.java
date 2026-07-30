package com.leanowtech.bloge.gateway.visual.authoring.testing;

import org.springframework.http.HttpHeaders;

/**
 * Authentication port for governed authoring test operations.
 *
 * <p>Gateway adapters authenticate transport credentials and project only the trusted identity
 * dimensions needed by the visual core.</p>
 */
public interface AuthoringTestAccessPort {

    enum Action {
        EXECUTE,
        EVIDENCE_READ,
        GATE_READ
    }

    AuthoringTestPrincipal authenticate(HttpHeaders headers, Action action);
}
