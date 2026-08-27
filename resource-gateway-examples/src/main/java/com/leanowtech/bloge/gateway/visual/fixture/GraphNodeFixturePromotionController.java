package com.leanowtech.bloge.gateway.visual.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Promotes one captured graph-node output into governed Fixture governance.
 */
@RestController
@ConditionalOnBean(GraphNodeFixturePromotionService.class)
public class GraphNodeFixturePromotionController {
    private final GraphNodeFixturePromotionService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the graph-node promotion transport boundary.
     *
     * @param service server-owned promotion orchestration
     * @param authenticator authenticated request identity provider
     */
    public GraphNodeFixturePromotionController(
            GraphNodeFixturePromotionService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /**
     * Derives a DRAFT Fixture from an exact graph node capture.
     *
     * @param draftId graph draft id
     * @param nodeId captured graph node id
     * @param request author-controlled bounded classification, retention, and redaction input
     * @param headers raw authenticated request headers
     * @return payload-free governed fixture receipt
     */
    @PostMapping("/api/visual/graphs/{draftId}/nodes/{nodeId}/fixtures:promote")
    public ResponseEntity<GraphNodeFixturePromotionService.PromotionResult> promote(
            @PathVariable String draftId,
            @PathVariable String nodeId,
            @RequestBody(required = false) GraphNodeFixturePromotionRequest request,
            @RequestHeader org.springframework.http.HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_WRITE);
        try {
            if (request == null) {
                throw new IllegalArgumentException("A graph-node Fixture promotion request is required");
            }
            return ResponseEntity.accepted().body(service.promote(draftId, nodeId, request, identity));
        } catch (IllegalArgumentException invalid) {
            throw problem(422, "RG.VISUAL.PROMOTION.REQUEST_INVALID", invalid.getMessage(),
                    identity.correlationId());
        } catch (GraphNodeFixturePromotionException failure) {
            throw problem(failure.status(), failure.code(), failure.getMessage(),
                    identity == null ? "" : identity.correlationId());
        }
    }

    private static IntegrationProblemException problem(
            int status,
            String code,
            String message,
            String correlationId) {
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:graph-node-fixture-promotion",
                message, status, code, false, correlationId, java.util.Map.of()));
    }
}
