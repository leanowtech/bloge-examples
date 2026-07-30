package com.leanowtech.bloge.gateway.visual.authoring.application;

import org.springframework.http.HttpHeaders;

/**
 * Authentication port for enterprise-scoped progressive authoring operations.
 */
public interface AuthoringDraftAccessPort {

    enum Action {
        READ,
        WRITE,
        COMMIT
    }

    AuthoringPrincipal authenticate(HttpHeaders headers, Action action);
}
