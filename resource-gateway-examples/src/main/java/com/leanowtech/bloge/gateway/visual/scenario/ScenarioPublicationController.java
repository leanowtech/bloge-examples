package com.leanowtech.bloge.gateway.visual.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authenticated non-production HTTP boundary for governed Scenario publication and receipts.
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/visual/scenario-draft-sets")
public class ScenarioPublicationController {

    private final ScenarioPublicationService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * @param service recoverable publication saga
     * @param authenticator workload authentication and purpose enforcement
     */
    public ScenarioPublicationController(
            ScenarioPublicationService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Publishes one exact retained source revision with a dedicated publisher purpose. */
    @PostMapping("/{scenarioDraftSetId}/publications")
    public StoredScenarioPublication publish(
            @PathVariable String scenarioDraftSetId,
            @RequestParam long revision,
            @RequestHeader HttpHeaders headers) {
        return service.publish(scenarioDraftSetId, revision,
                context(headers, IntegrationOperation.TEST_SCENARIO_PUBLISH));
    }

    /** Reads the current payload-free publication receipt. */
    @GetMapping("/publications/{publicationId}")
    public StoredScenarioPublication find(
            @PathVariable String publicationId,
            @RequestHeader HttpHeaders headers) {
        return service.find(publicationId,
                context(headers, IntegrationOperation.TEST_SUITE_READ));
    }

    /** Reads immutable publication transition history for incident review. */
    @GetMapping("/publications/{publicationId}/history")
    public List<StoredScenarioPublication> history(
            @PathVariable String publicationId,
            @RequestHeader HttpHeaders headers) {
        return service.history(publicationId,
                context(headers, IntegrationOperation.TEST_SUITE_READ));
    }

    private IntegrationRequestContext context(
            HttpHeaders headers,
            IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
