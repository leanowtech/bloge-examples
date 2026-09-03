package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/** Authenticated read board and explicit human-review HTTP boundary for Agent TDD. */
@RestController
@RequestMapping("/api/agent-tdd")
public final class AgentTddBoardController {
    private final IntegrationRequestAuthenticator authenticator;
    private final AgentTddBoardService board;
    private final AgentTddReviewService reviews;

    /** Creates the board boundary with existing integration authentication and audit. */
    public AgentTddBoardController(IntegrationRequestAuthenticator authenticator,
                                   AgentTddBoardService board,
                                   AgentTddReviewService reviews) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.board = Objects.requireNonNull(board, "board");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
    }

    /** Returns the structure-only scoped board. */
    @GetMapping("/board")
    public Map<String, Object> board(@RequestHeader HttpHeaders headers) {
        return this.board.board(authenticate(headers, IntegrationOperation.AGENT_TDD_READ));
    }

    /** Approves one pending business Oracle at the exact reviewed case-set revision. */
    @PostMapping("/reviews/oracles/{caseSetRef}/{caseId}/approve")
    public Map<String, Object> approveOracle(@PathVariable String caseSetRef,
                                             @PathVariable String caseId,
                                             @RequestBody RevisionRequest request,
                                             @RequestHeader HttpHeaders headers) {
        var stored = reviews.approveOracle(caseSetRef, caseId, request.expectedRevision(),
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return Map.of("assetRef", stored.assetRef(), "revision", stored.revision(), "status", "APPROVED");
    }

    /** Approves one frozen specification proposal at the exact reviewed revision. */
    @PostMapping("/reviews/specs/{toolRef}/approve")
    public Map<String, Object> approveSpec(@PathVariable String toolRef,
                                           @RequestBody RevisionRequest request,
                                           @RequestHeader HttpHeaders headers) {
        var stored = reviews.approvePublishSpec(toolRef, request.expectedRevision(),
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return Map.of("assetRef", stored.assetRef(), "revision", stored.revision(), "status", "APPROVED");
    }

    /** Records a separately authenticated owner signoff for executable publication. */
    @PostMapping("/reviews/tools/{toolRef}/signoffs/{signoffRef}/approve")
    public Map<String, Object> approveSignoff(@PathVariable String toolRef,
                                              @PathVariable String signoffRef,
                                              @RequestBody SignoffRequest request,
                                              @RequestHeader HttpHeaders headers) {
        var stored = reviews.approveToolSignoff(toolRef, signoffRef, request.draftRevision(),
                request.goldenSetId(), request.evidenceFingerprint(),
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return Map.of("assetRef", stored.assetRef(), "revision", stored.revision(), "status", "APPROVED");
    }

    /** Maps stale or invalid reviews to a payload-free, stable conflict response. */
    @ExceptionHandler(AgentTddToolException.class)
    public ResponseEntity<Map<String, Object>> reviewFailure(AgentTddToolException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "ok", false, "error", Map.of("code", failure.code(), "message", failure.getMessage())));
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }

    /** @param expectedRevision exact revision visible to the human reviewer */
    public record RevisionRequest(long expectedRevision) { }

    /** Exact baseline material the human reviewed before approving executable publication. */
    public record SignoffRequest(long draftRevision, String goldenSetId, String evidenceFingerprint) { }
}
