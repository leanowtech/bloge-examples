package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Authenticated CAS command adapter for governed Scenario v2 authoring. */
@RestController
@ConditionalOnBean(ScenarioDraftSetV2Service.class)
@RequestMapping("/api/visual/scenario-draft-sets-v2")
public final class ScenarioDraftSetV2Controller {

    private final ScenarioDraftSetV2Service service;
    private final IntegrationRequestAuthenticator authenticator;

    public ScenarioDraftSetV2Controller(
            ScenarioDraftSetV2Service service,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PutMapping("/{scenarioDraftSetId}")
    public ResponseEntity<CorrectnessApiEnvelope<StoredScenarioDraftSetV2>> save(
            @PathVariable String scenarioDraftSetId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) ScenarioDraftSetV2 candidate
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_SCENARIO_WRITE);
        try {
            long expectedRevision = expectedRevision(headers);
            EnterpriseScope scope = scope(identity);
            if (candidate == null
                    || !scenarioDraftSetId.equals(candidate.scenarioDraftSetId())) {
                throw failure("RG.CORRECTNESS.SCENARIO_DRAFT_INVALID",
                        "The request body must match the Scenario Draft Set path.");
            }
            requireScope(candidate.scope(), scope);
            StoredScenarioDraftSetV2 stored = service.saveDraft(
                    expectedRevision, candidate, actor(identity));
            HttpStatus status = expectedRevision == 0 ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status)
                    .eTag(Long.toString(stored.scenarioDraftSet().revision()))
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope,
                            List.of("SCENARIO_DRAFT_SET_V2", "SCENARIO_CAS_V2"), stored));
        } catch (ScenarioV2CommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/{scenarioDraftSetId}/cases/{scenarioId}:review-ready")
    public ResponseEntity<CorrectnessApiEnvelope<ScenarioDraftSetV2Service.TransitionResult>>
            markReviewReady(
                    @PathVariable String scenarioDraftSetId,
                    @PathVariable String scenarioId,
                    @RequestHeader HttpHeaders headers
            ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_SCENARIO_REVIEW_READY);
        try {
            EnterpriseScope scope = scope(identity);
            ScenarioDraftSetV2Service.TransitionResult result = service.markReviewReady(
                    scope, scenarioDraftSetId, scenarioId,
                    expectedRevision(headers), actor(identity));
            return ResponseEntity.ok()
                    .eTag(Long.toString(result.stored().scenarioDraftSet().revision()))
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope,
                            List.of("SCENARIO_REVIEW_READINESS_V1"), result));
        } catch (ScenarioV2CommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/{scenarioDraftSetId}/cases/{scenarioId}:approve")
    public ResponseEntity<CorrectnessApiEnvelope<ScenarioDraftSetV2Service.TransitionResult>>
            approveCanonical(
                    @PathVariable String scenarioDraftSetId,
                    @PathVariable String scenarioId,
                    @RequestHeader HttpHeaders headers,
                    @RequestBody(required = false) ApprovalRequest request
            ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_SCENARIO_APPROVE);
        try {
            if (request == null) {
                throw failure("RG.CORRECTNESS.SCENARIO_REVIEW_INVALID",
                        "A canonical approval comment is required.");
            }
            EnterpriseScope scope = scope(identity);
            ScenarioDraftSetV2Service.TransitionResult result =
                    service.approveCanonicalIdempotently(
                            scope, scenarioDraftSetId, scenarioId,
                            expectedRevision(headers), request.comment(), actor(identity),
                            headers.getFirst("Idempotency-Key"));
            return ResponseEntity.ok()
                    .eTag(Long.toString(result.stored().scenarioDraftSet().revision()))
                    .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope,
                            List.of("SCENARIO_CANONICAL_APPROVAL_V1"), result));
        } catch (ScenarioV2CommandException failure) {
            throw problem(failure, identity);
        }
    }

    private static long expectedRevision(HttpHeaders headers) {
        String value = headers == null ? "" : headers.getFirst(HttpHeaders.IF_MATCH);
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("W/")) normalized = normalized.substring(2).trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"")
                && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            long revision = Long.parseLong(normalized);
            if (revision >= 0) return revision;
        } catch (NumberFormatException ignored) {
            // Mapped to one stable precondition problem below.
        }
        throw failure("RG.CORRECTNESS.PRECONDITION_REQUIRED",
                "If-Match must contain one non-negative numeric Scenario revision.");
    }

    private static void requireScope(EnterpriseScope supplied, EnterpriseScope authorized) {
        if (!authorized.equals(supplied)) {
            throw failure("RG.CORRECTNESS.SCENARIO_NOT_FOUND",
                    "Scenario Draft Set was not found in the authorized scope.");
        }
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private static PrincipalRef actor(IntegrationRequestContext identity) {
        PrincipalKind kind = switch (identity.actorType()) {
            case "USER" -> PrincipalKind.USER;
            case "TEAM" -> PrincipalKind.TEAM;
            default -> PrincipalKind.SERVICE;
        };
        return new PrincipalRef(identity.actorId(), kind, "");
    }

    private static ScenarioV2CommandException failure(String code, String message) {
        return new ScenarioV2CommandException(code, message);
    }

    private static IntegrationProblemException problem(
            ScenarioV2CommandException failure,
            IntegrationRequestContext identity
    ) {
        int status = switch (failure.code()) {
            case "RG.CORRECTNESS.SCENARIO_NOT_FOUND",
                    "RG.CORRECTNESS.SCENARIO_CASE_NOT_FOUND" -> 404;
            case "RG.CORRECTNESS.SCENARIO_REVIEW_FORBIDDEN",
                    "RG.CORRECTNESS.FOUR_EYES_REQUIRED" -> 403;
            case "RG.CORRECTNESS.REVISION_CONFLICT",
                    "RG.CORRECTNESS.SCENARIO_IMMUTABLE",
                    "RG.CORRECTNESS.SCENARIO_TRANSITION_REQUIRED",
                    "RG.CORRECTNESS.SCENARIO_TRANSITION_INVALID",
                    "RG.CORRECTNESS.SCENARIO_REFERENCE_DRIFT",
                    "RG.CORRECTNESS.IDEMPOTENCY_CONFLICT",
                    "RG.CORRECTNESS.IDEMPOTENCY_RECEIPT_STALE" -> 409;
            case "RG.CORRECTNESS.PRECONDITION_REQUIRED" -> 428;
            case "RG.CORRECTNESS.IDEMPOTENCY_UNAVAILABLE" -> 503;
            case "RG.CORRECTNESS.SCENARIO_CLOSURE_INCOMPLETE" -> 422;
            default -> 400;
        };
        Map<String, Object> details = failure.closureReport() == null
                ? Map.of() : Map.of("closure", failure.closureReport());
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:correctness-authoring", failure.getMessage(), status,
                failure.code(), false, identity.correlationId(), details));
    }

    public record ApprovalRequest(String comment) {}
}
