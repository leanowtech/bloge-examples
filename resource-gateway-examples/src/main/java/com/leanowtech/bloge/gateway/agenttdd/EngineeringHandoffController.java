package com.leanowtech.bloge.gateway.agenttdd;

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

/** Separately authenticated HTTP boundary for WRITE Instruction implementation fulfillment. */
@RestController
@RequestMapping("/api/agent-tdd/engineering-handoffs")
public final class EngineeringHandoffController {
    private final IntegrationRequestAuthenticator authenticator;
    private final EngineeringHandoffService handoffs;

    /** Creates the non-MCP engineering endpoint over the canonical handoff lifecycle. */
    public EngineeringHandoffController(
            IntegrationRequestAuthenticator authenticator, EngineeringHandoffService handoffs) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.handoffs = Objects.requireNonNull(handoffs, "handoffs");
    }

    /** Binds one implementation while preserving the exact business Instruction contract. */
    @PostMapping("/{solutionRef}/instructions/{instructionRef}/fulfil")
    public Map<String, Object> fulfil(@PathVariable String solutionRef,
                                      @PathVariable String instructionRef,
                                      @RequestBody FulfilRequest request,
                                      @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.AGENT_TDD_INSTRUCTION_ENG);
        return handoffs.fulfil(solutionRef, instructionRef, request.bindingRef(), identity);
    }

    /** Controlled implementation reference supplied by the accountable engineer. */
    public record FulfilRequest(String bindingRef) { }
}
