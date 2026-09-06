package com.leanowtech.bloge.gateway.solution.journey;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;

import java.util.List;
import java.util.Objects;

/** Authenticated, no-store transport for Solution-scoped Fixture descriptor metadata. */
@RestController
@ConditionalOnBean(BusinessFixtureIndexService.class)
@ConditionalOnProperty(
        prefix = "gateway.testing.correctness",
        name = "enabled",
        havingValue = "true")
@RequestMapping("/api/agent-tdd/solutions")
public final class BusinessFixtureIndexController {
    private final IntegrationRequestAuthenticator authenticator;
    private final BusinessFixtureIndexService index;

    /** Creates the metadata-only control-panel endpoint. */
    public BusinessFixtureIndexController(
            IntegrationRequestAuthenticator authenticator, BusinessFixtureIndexService index) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.index = Objects.requireNonNull(index, "index");
    }

    /** Returns Feature and Instruction Fixture groups without protected material coordinates. */
    @GetMapping("/{solutionRef}/fixtures")
    public ResponseEntity<List<BusinessFixtureIndexService.CapabilityFixtures>> list(
            @PathVariable String solutionRef, @RequestHeader HttpHeaders headers) {
        var identity = reviewer(headers);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(index.listForSolution(solutionRef, identity));
    }

    /** Returns one protected Fixture body after exact Solution membership and human authorization. */
    @GetMapping("/{solutionRef}/fixtures/{fixtureAssetId}/material")
    public ResponseEntity<BusinessFixtureIndexService.FixtureMaterialView> material(
            @PathVariable String solutionRef,
            @PathVariable String fixtureAssetId,
            @RequestHeader HttpHeaders headers) {
        var identity = reviewer(headers);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(index.readMaterialForSolution(solutionRef, fixtureAssetId, identity));
    }

    /** Maps protected material failures without exposing receipt or payload detail. */
    @ExceptionHandler(AgentTddToolException.class)
    public ResponseEntity<java.util.Map<String, Object>> failure(AgentTddToolException failure) {
        HttpStatus status = switch (failure.code()) {
            case "GOLDEN_REVIEW_CLEARANCE_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "REFERENCE_UNRESOLVED" -> HttpStatus.NOT_FOUND;
            case "FIXTURE_MATERIAL_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(java.util.Map.of("ok", false,
                        "error", java.util.Map.of("code", failure.code(),
                                "message", failure.getMessage())));
    }

    private com.leanowtech.bloge.gateway.integration.IntegrationRequestContext reviewer(
            HttpHeaders headers) {
        var identity = authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW);
        boolean humanReviewer = "HUMAN".equals(identity.actorType())
                && identity.groups().stream().anyMatch(
                BusinessGoldenReviewService.REVIEWER_GROUP::equalsIgnoreCase);
        if (!humanReviewer) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.SOLUTION.FIXTURE_INDEX.HUMAN_REVIEW_FORBIDDEN",
                    "Solution Fixture material requires an authorized human reviewer.",
                    identity.correlationId(), java.util.Map.of()));
        }
        return identity;
    }
}
