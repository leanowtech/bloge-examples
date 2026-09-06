package com.leanowtech.bloge.gateway.solution.journey;

import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/** Human-only HTTP surface for business GOLDEN summaries and protected material review. */
@RestController
@RequestMapping("/api/solution/golden-review")
public final class BusinessGoldenReviewController {
    private final IntegrationRequestAuthenticator authenticator;
    private final BusinessGoldenReviewService reviews;

    /** Creates the transport over the shared integration authenticator and review service. */
    public BusinessGoldenReviewController(IntegrationRequestAuthenticator authenticator,
                                          BusinessGoldenReviewService reviews) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
    }

    /** Lists payload-free GOLDEN metadata visible to one human owner or reviewer. */
    @GetMapping("/{solutionRef}")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String solutionRef,
            @RequestParam String journeyRef,
            @RequestHeader HttpHeaders headers) {
        return protectedResponse(reviews.list(solutionRef, journeyRef, authenticate(headers)));
    }

    /** Returns one authorized business-language case without internal receipt or graph material. */
    @GetMapping("/{solutionRef}/cases/{caseId}/material")
    public ResponseEntity<Map<String, Object>> material(
            @PathVariable String solutionRef,
            @PathVariable String caseId,
            @RequestParam String journeyRef,
            @RequestHeader HttpHeaders headers) {
        return protectedResponse(reviews.readMaterial(
                solutionRef, journeyRef, caseId, authenticate(headers)));
    }

    /** Maps review failures to stable, payload-free responses with the same cache prohibition. */
    @ExceptionHandler(AgentTddToolException.class)
    public ResponseEntity<Map<String, Object>> failure(AgentTddToolException failure) {
        HttpStatus status = switch (failure.code()) {
            case "GOLDEN_REVIEW_AUTH_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            case "GOLDEN_REVIEW_HUMAN_REQUIRED", "GOLDEN_REVIEW_PURPOSE_FORBIDDEN",
                    "GOLDEN_REVIEW_ROLE_FORBIDDEN", "GOLDEN_REVIEW_CLEARANCE_FORBIDDEN" ->
                    HttpStatus.FORBIDDEN;
            case "DRAFT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "GOLDEN_REVIEW_AUDIT_UNAVAILABLE", "FIXTURE_MATERIAL_UNAVAILABLE" ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return protectedResponse(status, Map.of(
                "ok", false,
                "error", Map.of("code", failure.code(), "message", failure.getMessage())));
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers) {
        return authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW);
    }

    private static <T> ResponseEntity<T> protectedResponse(T body) {
        return protectedResponse(HttpStatus.OK, body);
    }

    private static <T> ResponseEntity<T> protectedResponse(HttpStatus status, T body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }
}
