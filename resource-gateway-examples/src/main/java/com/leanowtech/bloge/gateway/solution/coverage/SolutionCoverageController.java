package com.leanowtech.bloge.gateway.solution.coverage;

import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenReviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/** Human-only, no-store HTTP projection of the exact Solution coverage matrix. */
@RestController
@ConditionalOnBean(SolutionCoverageService.class)
@RequestMapping("/api/solution/coverage")
public final class SolutionCoverageController {
    private final IntegrationRequestAuthenticator authenticator;
    private final SolutionCoverageService coverage;

    /** Creates the human transport over the shared identity and coverage authorities. */
    public SolutionCoverageController(IntegrationRequestAuthenticator authenticator,
                                      SolutionCoverageService coverage) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.coverage = Objects.requireNonNull(coverage, "coverage");
    }

    /**
     * Returns stable obligation and covering-case identifiers to an authorized human reviewer.
     * The Agent MCP uses {@link SolutionCoverageService.CoverageStatus#agentProjection()} instead
     * and therefore cannot receive these human coordinates.
     */
    @GetMapping("/{solutionRef}")
    public ResponseEntity<HumanCoverageStatus> status(
            @PathVariable String solutionRef,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW);
        requireHumanReviewer(identity);
        SolutionCoverageService.CoverageStatus status = coverage.status(identity, solutionRef);
        HumanCoverageStatus body = new HumanCoverageStatus(
                solutionRef.trim(), status.inventoryId(), status.inventoryRevision(),
                status.solutionFingerprint(), status.obligations(), status.summary());
        return protectedResponse(org.springframework.http.HttpStatus.OK, body);
    }

    /** Preserves authenticated problem status while prohibiting storage of human coordinates. */
    @ExceptionHandler(IntegrationProblemException.class)
    public ResponseEntity<IntegrationProblem> integrationFailure(IntegrationProblemException failure) {
        return protectedResponse(
                org.springframework.http.HttpStatus.valueOf(failure.problem().status()),
                failure.problem());
    }

    /** Maps coverage derivation failures to a payload-free, no-store problem response. */
    @ExceptionHandler(AgentTddToolException.class)
    public ResponseEntity<IntegrationProblem> coverageFailure(AgentTddToolException failure) {
        org.springframework.http.HttpStatus status = switch (failure.code()) {
            case "DRAFT_NOT_FOUND", "REFERENCE_UNRESOLVED" ->
                    org.springframework.http.HttpStatus.NOT_FOUND;
            case "FIXTURE_MATERIAL_UNAVAILABLE" ->
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
            default -> org.springframework.http.HttpStatus.CONFLICT;
        };
        IntegrationProblem problem = new IntegrationProblem(
                IntegrationProblem.SCHEMA_VERSION,
                "urn:bloge:problem:solution-coverage",
                failure.getMessage(), status.value(), failure.code(), failure.retryable(), "",
                java.util.Map.of());
        return protectedResponse(status, problem);
    }

    private static void requireHumanReviewer(IntegrationRequestContext identity) {
        boolean reviewer = identity != null
                && "HUMAN".equals(identity.actorType())
                && identity.groups().stream().anyMatch(
                        BusinessGoldenReviewService.REVIEWER_GROUP::equalsIgnoreCase);
        if (!reviewer) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SOLUTION.COVERAGE.HUMAN_REVIEW_FORBIDDEN",
                    "Solution coverage case coordinates require an authorized human reviewer.",
                    identity == null ? "" : identity.correlationId(), java.util.Map.of()));
        }
    }

    private static <T> ResponseEntity<T> protectedResponse(
            org.springframework.http.HttpStatus status, T body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    /** Human projection retaining exact obligation ids and the approved cases that cover them. */
    public record HumanCoverageStatus(
            String solutionRef,
            String inventoryId,
            long inventoryRevision,
            String solutionFingerprint,
            List<SolutionCoverageService.CoverageItem> obligations,
            SolutionCoverageService.CoverageSummary summary) {
        /** Freezes the controller response independently from the service collection. */
        public HumanCoverageStatus {
            solutionRef = Objects.requireNonNull(solutionRef, "solutionRef");
            inventoryId = Objects.requireNonNull(inventoryId, "inventoryId");
            solutionFingerprint = Objects.requireNonNull(
                    solutionFingerprint, "solutionFingerprint");
            obligations = obligations == null ? List.of() : List.copyOf(obligations);
            summary = Objects.requireNonNull(summary, "summary");
        }
    }
}
