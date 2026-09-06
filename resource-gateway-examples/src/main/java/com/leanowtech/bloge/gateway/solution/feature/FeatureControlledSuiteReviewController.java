package com.leanowtech.bloge.gateway.solution.feature;

import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Human-only, no-store HTTP surface for Feature controlled-suite review. */
@RestController
@ConditionalOnBean(FeatureControlledSuiteReviewService.class)
@RequestMapping("/api/solution/feature-suite-review")
public final class FeatureControlledSuiteReviewController {
    private final IntegrationRequestAuthenticator authenticator;
    private final FeatureControlledSuiteReviewService reviews;

    /** Creates the transport over the shared identity and protected review boundaries. */
    public FeatureControlledSuiteReviewController(
            IntegrationRequestAuthenticator authenticator,
            FeatureControlledSuiteReviewService reviews) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
    }

    /** Lists payload-free suites in one Solution closure. */
    @GetMapping
    public ResponseEntity<List<FeatureControlledSuiteReviewService.SuiteReviewSummary>> list(
            @RequestParam String solutionRef, @RequestHeader HttpHeaders headers) {
        return noStore(HttpStatus.OK, reviews.listForSolution(solutionRef, authenticate(headers)));
    }

    /** Loads one authorized suite body without returning its vault receipt. */
    @GetMapping("/{featureRef}/material")
    public ResponseEntity<FeatureControlledSuiteReviewService.SuiteMaterialView> material(
            @PathVariable String featureRef,
            @RequestParam String solutionRef,
            @RequestHeader HttpHeaders headers) {
        return noStore(HttpStatus.OK,
                reviews.readMaterial(solutionRef, featureRef, authenticate(headers)));
    }

    /** Maps stable review failures without making protected responses cacheable. */
    @ExceptionHandler(AgentTddToolException.class)
    public ResponseEntity<Map<String, Object>> failure(AgentTddToolException failure) {
        HttpStatus status = switch (failure.code()) {
            case "FEATURE_SUITE_REVIEW_AUTH_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            case "FEATURE_SUITE_REVIEW_ROLE_FORBIDDEN", "FEATURE_SUITE_REVIEW_PURPOSE_FORBIDDEN",
                    "FEATURE_SUITE_REVIEW_CLEARANCE_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "REFERENCE_UNRESOLVED" -> HttpStatus.NOT_FOUND;
            case "FIXTURE_MATERIAL_UNAVAILABLE", "GOLDEN_REVIEW_AUDIT_UNAVAILABLE" ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return noStore(status, Map.of(
                "ok", false, "error", Map.of("code", failure.code(), "message", failure.getMessage())));
    }

    private com.leanowtech.bloge.gateway.integration.IntegrationRequestContext authenticate(
            HttpHeaders headers) {
        return authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW);
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
