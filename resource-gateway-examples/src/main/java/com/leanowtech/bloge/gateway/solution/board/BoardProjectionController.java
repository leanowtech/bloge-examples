package com.leanowtech.bloge.gateway.solution.board;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Human-only, no-store transport for the payload-bearing business review board. */
@RestController
@RequestMapping("/api/agent-tdd/solutions")
public final class BoardProjectionController {
    private final IntegrationRequestAuthenticator authenticator;
    private final BoardProjectionService projections;

    /** Creates the review transport over integration authentication and the business projector. */
    public BoardProjectionController(
            IntegrationRequestAuthenticator authenticator, BoardProjectionService projections) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.projections = Objects.requireNonNull(projections, "projections");
    }

    /** Returns the five business panels and prevents intermediaries from caching case details. */
    @GetMapping("/{solutionRef}/board")
    public ResponseEntity<BoardProjectionService.BoardView> board(
            @PathVariable String solutionRef, @RequestHeader HttpHeaders headers) {
        var identity = authenticator.authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(projections.project(solutionRef, identity));
    }
}
