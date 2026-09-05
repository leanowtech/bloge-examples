package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/** Separately authenticated HTTP boundary for feature-engineering fulfillment. */
@RestController
@RequestMapping("/api/agent-tdd/feature-handoffs")
public final class FeatureHandoffController {
    private final IntegrationRequestAuthenticator authenticator;
    private final FeatureHandoffService handoffs;

    /** Creates the non-MCP engineering endpoint over the canonical handoff lifecycle. */
    public FeatureHandoffController(
            IntegrationRequestAuthenticator authenticator, FeatureHandoffService handoffs) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.handoffs = Objects.requireNonNull(handoffs, "handoffs");
    }

    /** Binds and fixture-verifies one implementation without exposing this power to Agent tools. */
    @PostMapping("/{featureRef}/fulfil")
    public Map<String, Object> fulfil(@PathVariable String featureRef,
                                      @RequestBody FulfilRequest request,
                                      @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.AGENT_TDD_FEATURE_ENG);
        return handoffs.fulfil(featureRef, request.evaluationRef(), request.fixtureInputs(), identity);
    }

    /** Controlled implementation reference and non-persisted verification fixture inputs. */
    public record FulfilRequest(String evaluationRef, JsonNode fixtureInputs) { }
}
