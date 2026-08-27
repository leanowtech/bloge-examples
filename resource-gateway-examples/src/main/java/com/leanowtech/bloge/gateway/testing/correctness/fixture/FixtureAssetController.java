package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
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

/** Authenticated CAS and review adapter for payload-free Fixture catalog metadata. */
@RestController
@ConditionalOnBean(FixtureCatalogService.class)
@RequestMapping("/api/visual/fixture-assets")
public final class FixtureAssetController {

    private final FixtureCatalogService service;
    private final IntegrationRequestAuthenticator authenticator;

    public FixtureAssetController(
            FixtureCatalogService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PutMapping("/{fixtureAssetId}")
    public ResponseEntity<CorrectnessApiEnvelope<StoredFixtureAsset>> save(
            @PathVariable String fixtureAssetId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) FixtureAssetDescriptor candidate) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_FIXTURE_WRITE);
        try {
            long expectedRevision = expectedRevision(headers);
            EnterpriseScope scope = scope(identity);
            if (candidate == null || !fixtureAssetId.equals(candidate.fixtureAssetId())) {
                throw failure("RG.CORRECTNESS.FIXTURE_DRAFT_INVALID",
                        "The request body must match the Fixture asset path");
            }
            requireScope(candidate.scope(), scope);
            StoredFixtureAsset stored = service.saveDraft(
                    expectedRevision, candidate, actor(identity));
            HttpStatus status = expectedRevision == 0 ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status)
                    .eTag(Long.toString(stored.descriptor().revision()))
                    .body(envelope(identity, scope, "FIXTURE_CATALOG_CAS_V1", stored));
        } catch (FixtureCatalogCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/{fixtureAssetId}:review-ready")
    public ResponseEntity<CorrectnessApiEnvelope<StoredFixtureAsset>> reviewReady(
            @PathVariable String fixtureAssetId,
            @RequestHeader HttpHeaders headers) {
        return transition(
                fixtureAssetId, headers, IntegrationOperation.CORRECTNESS_FIXTURE_REVIEW_READY,
                "FIXTURE_REVIEW_READY_V1",
                (scope, revision, actor) -> service.submitForReview(
                        scope, fixtureAssetId, revision, actor));
    }

    /**
     * Records bounded metadata-only reviewer attestations on a proposed Fixture.
     *
     * @param fixtureAssetId exact Fixture identifier
     * @param headers authenticated reviewer headers and exact If-Match revision
     * @param request explicit redaction acknowledgements and bounded comment
     * @return payload-free catalog metadata receipt
     */
    @PostMapping("/{fixtureAssetId}:verify-review")
    public ResponseEntity<CorrectnessApiEnvelope<StoredFixtureAsset>> verifyReview(
            @PathVariable String fixtureAssetId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) FixtureReviewVerificationRequest request) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_FIXTURE_APPROVE);
        try {
            EnterpriseScope scope = scope(identity);
            StoredFixtureAsset stored = service.verifyForApproval(
                    scope, fixtureAssetId, expectedRevision(headers), request, actor(identity));
            return ResponseEntity.ok()
                    .eTag(Long.toString(stored.descriptor().revision()))
                    .body(envelope(identity, scope, "FIXTURE_REVIEW_VERIFIED_V1", stored));
        } catch (FixtureCatalogCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/{fixtureAssetId}:approve")
    public ResponseEntity<CorrectnessApiEnvelope<FixtureCatalogService.ApprovalResult>> approve(
            @PathVariable String fixtureAssetId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) ReviewRequest request) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_FIXTURE_APPROVE);
        try {
            if (request == null) {
                throw failure("RG.CORRECTNESS.FIXTURE_REVIEW_INVALID",
                        "A Fixture approval comment is required");
            }
            EnterpriseScope scope = scope(identity);
            var result = service.approveIdempotently(
                    scope, fixtureAssetId, expectedRevision(headers), request.comment(),
                    actor(identity), headers.getFirst("Idempotency-Key"));
            return ResponseEntity.ok()
                    .eTag(Long.toString(result.stored().descriptor().revision()))
                    .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                    .body(envelope(identity, scope, "FIXTURE_OWNER_APPROVAL_V1", result));
        } catch (FixtureCatalogCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/{fixtureAssetId}:activate")
    public ResponseEntity<CorrectnessApiEnvelope<StoredFixtureAsset>> activate(
            @PathVariable String fixtureAssetId,
            @RequestHeader HttpHeaders headers) {
        return transition(
                fixtureAssetId, headers, IntegrationOperation.CORRECTNESS_FIXTURE_ACTIVATE,
                "FIXTURE_ACTIVATION_V1",
                (scope, revision, actor) -> service.activate(
                        scope, fixtureAssetId, revision, actor));
    }

    @PostMapping("/{fixtureAssetId}:revoke")
    public ResponseEntity<CorrectnessApiEnvelope<StoredFixtureAsset>> revoke(
            @PathVariable String fixtureAssetId,
            @RequestHeader HttpHeaders headers) {
        return transition(
                fixtureAssetId, headers, IntegrationOperation.CORRECTNESS_FIXTURE_REVOKE,
                "FIXTURE_REVOCATION_V1",
                (scope, revision, actor) -> service.revoke(
                        scope, fixtureAssetId, revision, actor));
    }

    private ResponseEntity<CorrectnessApiEnvelope<StoredFixtureAsset>> transition(
            String fixtureAssetId,
            HttpHeaders headers,
            IntegrationOperation operation,
            String capability,
            Transition command) {
        IntegrationRequestContext identity = authenticator.authenticate(headers, operation);
        try {
            EnterpriseScope scope = scope(identity);
            StoredFixtureAsset stored = command.apply(
                    scope, expectedRevision(headers), actor(identity));
            return ResponseEntity.ok()
                    .eTag(Long.toString(stored.descriptor().revision()))
                    .body(envelope(identity, scope, capability, stored));
        } catch (FixtureCatalogCommandException failure) {
            throw problem(failure, identity);
        }
    }

    private static <T> CorrectnessApiEnvelope<T> envelope(
            IntegrationRequestContext identity,
            EnterpriseScope scope,
            String capability,
            T data) {
        return CorrectnessApiEnvelope.of(
                identity.correlationId(), scope, List.of(capability), data);
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
            // Mapped to the stable precondition problem below.
        }
        throw failure("RG.CORRECTNESS.PRECONDITION_REQUIRED",
                "If-Match must contain one non-negative numeric Fixture revision");
    }

    private static void requireScope(EnterpriseScope supplied, EnterpriseScope authorized) {
        if (!authorized.equals(supplied)) {
            throw failure("RG.CORRECTNESS.FIXTURE_NOT_FOUND",
                    "Fixture was not found in the authorized scope");
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

    private static IntegrationProblemException problem(
            FixtureCatalogCommandException failure,
            IntegrationRequestContext identity) {
        int status = switch (failure.code()) {
            case "RG.CORRECTNESS.FIXTURE_NOT_FOUND" -> 404;
            case "RG.CORRECTNESS.FIXTURE_APPROVAL_FORBIDDEN",
                    "RG.CORRECTNESS.FIXTURE_REVOKE_FORBIDDEN",
                    "RG.CORRECTNESS.FOUR_EYES_REQUIRED" -> 403;
            case "RG.CORRECTNESS.REVISION_CONFLICT",
                    "RG.CORRECTNESS.FIXTURE_TRANSITION_REQUIRED",
                    "RG.CORRECTNESS.FIXTURE_TRANSITION_INVALID",
                    "RG.CORRECTNESS.FIXTURE_MATERIAL_DRIFT",
                    "RG.CORRECTNESS.FIXTURE_SCHEMA_DRIFT",
                    "RG.CORRECTNESS.IDEMPOTENCY_CONFLICT",
                    "RG.CORRECTNESS.IDEMPOTENCY_RECEIPT_STALE" -> 409;
            case "RG.CORRECTNESS.PRECONDITION_REQUIRED" -> 428;
            case "RG.CORRECTNESS.IDEMPOTENCY_UNAVAILABLE" -> 503;
            case "RG.CORRECTNESS.FIXTURE_MATERIAL_BINDING_MISMATCH",
                    "RG.CORRECTNESS.FIXTURE_MATERIAL_EXPIRED",
                    "RG.CORRECTNESS.FIXTURE_REVIEW_INCOMPLETE" -> 422;
            default -> 400;
        };
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:correctness-authoring", failure.getMessage(), status,
                failure.code(), false, identity.correlationId(), Map.of()));
    }

    private static FixtureCatalogCommandException failure(String code, String message) {
        return new FixtureCatalogCommandException(code, message);
    }

    @FunctionalInterface
    private interface Transition {
        StoredFixtureAsset apply(EnterpriseScope scope, long revision, PrincipalRef actor);
    }

    public record ReviewRequest(String comment) {}
}
