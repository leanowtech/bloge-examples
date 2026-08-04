package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, non-production Scenario import materialization surface. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/visual/scenario-imports")
public final class ScenarioImportController {

    private final ScenarioImportMaterializationService service;
    private final IntegrationRequestAuthenticator authenticator;

    /** Creates the authenticated transport adapter. */
    public ScenarioImportController(
            ScenarioImportMaterializationService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Re-parses and materializes one exact source/plan closure. */
    @PostMapping("/materialize")
    public ScenarioImportMaterializationResult materialize(
            @RequestBody ScenarioImportMaterializationRequest request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.TEST_SUITE_WRITE);
        return service.materialize(request, identity);
    }
}
