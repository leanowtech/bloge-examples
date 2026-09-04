package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/** Authenticated read board and explicit human-review HTTP boundary for Agent TDD. */
@RestController
@RequestMapping("/api/agent-tdd")
public final class AgentTddBoardController {
    private final IntegrationRequestAuthenticator authenticator;
    private final AgentTddBoardService board;
    private final AgentTddLibraryOverviewService libraryOverview;
    private final AgentTddReviewService reviews;
    private final AgentTddAttestationService attestations;
    private final SolutionGovernanceService solutionGovernance;

    /** Creates the board boundary with existing integration authentication and audit. */
    public AgentTddBoardController(IntegrationRequestAuthenticator authenticator,
                                   AgentTddBoardService board,
                                   AgentTddLibraryOverviewService libraryOverview,
                                   AgentTddReviewService reviews) {
        this(authenticator, board, libraryOverview, reviews, null, null);
    }

    /** Creates the production board including the human-only attestation recovery entry. */
    public AgentTddBoardController(IntegrationRequestAuthenticator authenticator,
                                   AgentTddBoardService board,
                                   AgentTddLibraryOverviewService libraryOverview,
                                   AgentTddReviewService reviews,
                                   AgentTddAttestationService attestations) {
        this(authenticator, board, libraryOverview, reviews, attestations, null);
    }

    /** Creates the complete production board including Solution review and signoff. */
    @Autowired
    public AgentTddBoardController(IntegrationRequestAuthenticator authenticator,
                                   AgentTddBoardService board,
                                   AgentTddLibraryOverviewService libraryOverview,
                                   AgentTddReviewService reviews,
                                   AgentTddAttestationService attestations,
                                   SolutionGovernanceService solutionGovernance) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.board = Objects.requireNonNull(board, "board");
        this.libraryOverview = Objects.requireNonNull(libraryOverview, "libraryOverview");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.attestations = attestations;
        this.solutionGovernance = solutionGovernance;
    }

    /** Returns the structure-only scoped board. */
    @GetMapping("/board")
    public Map<String, Object> board(@RequestHeader HttpHeaders headers) {
        return this.board.board(authenticate(headers, IntegrationOperation.AGENT_TDD_READ));
    }

    /** Returns the business-readable platform building blocks and declared world model. */
    @GetMapping("/library-overview")
    public ResponseEntity<Map<String, Object>> libraryOverview(@RequestHeader HttpHeaders headers) {
        Map<String, Object> body = libraryOverview.overview(
                authenticate(headers, IntegrationOperation.AGENT_TDD_READ));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    /** Approves one pending business Oracle at the exact reviewed case-set revision. */
    @PostMapping("/reviews/oracles/{caseSetRef}/{caseId}/approve")
    public Map<String, Object> approveOracle(@PathVariable String caseSetRef,
                                             @PathVariable String caseId,
                                             @RequestBody RevisionRequest request,
                                             @RequestHeader HttpHeaders headers) {
        var stored = reviews.approveOracle(caseSetRef, caseId, request.expectedRevision(),
                request.proposalFingerprint(),
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return Map.of("assetRef", stored.assetRef(), "revision", stored.revision(), "status", "APPROVED");
    }

    /** Approves one frozen specification proposal at the exact reviewed revision. */
    @PostMapping("/reviews/specs/{toolRef}/approve")
    public Map<String, Object> approveSpec(@PathVariable String toolRef,
                                           @RequestBody RevisionRequest request,
                                           @RequestHeader HttpHeaders headers) {
        var stored = reviews.approvePublishSpec(toolRef, request.expectedRevision(),
                request.proposalFingerprint(),
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return Map.of("assetRef", stored.assetRef(), "revision", stored.revision(), "status", "APPROVED");
    }

    /** Opens the exact payload-bearing Oracle proposal for a human before approval. */
    @GetMapping("/reviews/oracles/{caseSetRef}/{caseId}")
    public ResponseEntity<Map<String, Object>> oracleReview(@PathVariable String caseSetRef,
                                                            @PathVariable String caseId,
                                                            @RequestParam long expectedRevision,
                                                            @RequestHeader HttpHeaders headers) {
        Map<String, Object> body = reviews.oracleReview(caseSetRef, caseId, expectedRevision,
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    /** Opens the exact payload-bearing publish specification for a human before approval. */
    @GetMapping("/reviews/specs/{toolRef}")
    public ResponseEntity<Map<String, Object>> publishSpecReview(@PathVariable String toolRef,
                                                                 @RequestParam long expectedRevision,
                                                                 @RequestHeader HttpHeaders headers) {
        Map<String, Object> body = reviews.publishSpecReview(toolRef, expectedRevision,
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    /** Records a separately authenticated owner signoff for executable publication. */
    @PostMapping("/reviews/tools/{toolRef}/signoffs/{signoffRef}/approve")
    public Map<String, Object> approveSignoff(@PathVariable String toolRef,
                                              @PathVariable String signoffRef,
                                              @RequestBody SignoffRequest request,
                                              @RequestHeader HttpHeaders headers) {
        var stored = reviews.approveToolSignoff(toolRef, signoffRef, request.draftRevision(),
                request.goldenSetId(), request.evidenceFingerprint(), request.implementationFingerprint(),
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return Map.of("assetRef", stored.assetRef(), "revision", stored.revision(), "status", "APPROVED");
    }

    /** Opens the exact Solution proposal, structure and current evidence for human review. */
    @GetMapping("/reviews/solutions/{solutionRef}")
    public ResponseEntity<Map<String, Object>> solutionReview(
            @PathVariable String solutionRef,
            @RequestParam long expectedRevision,
            @RequestHeader HttpHeaders headers) {
        Map<String, Object> body = solutionGovernance().review(solutionRef, expectedRevision,
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    /** Signs one exact Solution proposal and GREEN/reconciliation evidence line. */
    @PostMapping("/reviews/solutions/{solutionRef}/signoffs/{signoffRef}/approve")
    public Map<String, Object> approveSolutionSignoff(
            @PathVariable String solutionRef,
            @PathVariable String signoffRef,
            @RequestBody SolutionSignoffRequest request,
            @RequestHeader HttpHeaders headers) {
        AgentTddStoredAsset stored = solutionGovernance().approve(solutionRef, signoffRef,
                request.solutionRevision(), request.goldenSetId(), request.evidenceFingerprint(),
                request.implementationFingerprint(), request.proposalFingerprint(),
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
        return Map.of("assetRef", stored.assetRef(), "revision", stored.revision(), "status", "APPROVED");
    }

    /** Re-runs the current payload-free sandbox attestation after explicit human confirmation. */
    @PostMapping("/attestations/{toolRef}/rerun")
    public Map<String, Object> rerunAttestation(@PathVariable String toolRef,
                                                @RequestHeader HttpHeaders headers) {
        if (attestations == null) {
            throw new AgentTddToolException("GATE_REJECTED", "Attestation service is unavailable.");
        }
        return attestations.rerun(toolRef,
                authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE));
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

    private SolutionGovernanceService solutionGovernance() {
        if (solutionGovernance == null) {
            throw new AgentTddToolException("GATE_REJECTED", "Solution governance is unavailable.");
        }
        return solutionGovernance;
    }

    /** @param expectedRevision exact revision visible to the human reviewer */
    public record RevisionRequest(long expectedRevision, String proposalFingerprint) { }

    /** Exact GREEN and real-implementation material reviewed before executable publication. */
    public record SignoffRequest(long draftRevision,
                                 String goldenSetId,
                                 String evidenceFingerprint,
                                 String implementationFingerprint) { }

    /** Exact Solution proposal and GREEN line reviewed before owner approval. */
    public record SolutionSignoffRequest(long solutionRevision,
                                         String goldenSetId,
                                         String evidenceFingerprint,
                                         String implementationFingerprint,
                                         String proposalFingerprint) { }
}
