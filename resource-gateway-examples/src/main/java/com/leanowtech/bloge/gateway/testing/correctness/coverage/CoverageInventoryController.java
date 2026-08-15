package com.leanowtech.bloge.gateway.testing.correctness.coverage;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;
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

/** Authenticated command adapter for Coverage Inventory authoring and review. */
@RestController
@ConditionalOnBean(CoverageInventoryService.class)
@RequestMapping("/api/visual/coverage-inventories")
public final class CoverageInventoryController {

    private static final List<String> WRITE_CAPABILITIES = List.of(
            "COVERAGE_INVENTORY_V1", "COVERAGE_INVENTORY_CAS_V1");

    private final CoverageInventoryService service;
    private final IntegrationRequestAuthenticator authenticator;

    public CoverageInventoryController(
            CoverageInventoryService service,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PutMapping("/{inventoryId}")
    public ResponseEntity<CorrectnessApiEnvelope<StoredCoverageInventory>> save(
            @PathVariable String inventoryId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) CoverageInventory candidate
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_INVENTORY_WRITE);
        try {
            long expectedRevision = expectedRevision(headers);
            EnterpriseScope scope = scope(identity);
            if (candidate == null || !inventoryId.equals(candidate.inventoryId())) {
                throw failure("RG.CORRECTNESS.INVENTORY_DRAFT_INVALID",
                        "The request body must match the Coverage Inventory path.");
            }
            requireScope(candidate.scope(), scope);
            StoredCoverageInventory stored = service.saveDraft(
                    expectedRevision, candidate, actor(identity));
            HttpStatus status = expectedRevision == 0 ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status)
                    .eTag(Long.toString(stored.inventory().revision()))
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope, WRITE_CAPABILITIES, stored));
        } catch (CoverageCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/{inventoryId}:freeze")
    public ResponseEntity<CorrectnessApiEnvelope<CoverageInventoryService.FreezeResult>> freeze(
            @PathVariable String inventoryId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) FreezeRequest request
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_INVENTORY_FREEZE);
        try {
            long expectedRevision = expectedRevision(headers);
            if (request == null) {
                throw failure("RG.CORRECTNESS.FREEZE_REVIEW_INVALID",
                        "A freeze review comment is required.");
            }
            EnterpriseScope scope = scope(identity);
            String idempotencyKey = headers.getFirst("Idempotency-Key");
            CoverageInventoryService.FreezeResult result = service.freezeIdempotently(
                    scope, inventoryId, expectedRevision, request.comment(), actor(identity),
                    idempotencyKey);
            return ResponseEntity.ok()
                    .eTag(Long.toString(result.stored().inventory().revision()))
                    .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope,
                            List.of("COVERAGE_INVENTORY_FREEZE_V1"), result));
        } catch (CoverageCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/{inventoryId}:impact")
    public CorrectnessApiEnvelope<CoverageImpactProposal> impact(
            @PathVariable String inventoryId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) ImpactRequest request
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_INVENTORY_IMPACT);
        try {
            if (request == null || request.target() == null) {
                throw failure("RG.CORRECTNESS.IMPACT_REQUEST_INVALID",
                        "An exact impact target is required.");
            }
            EnterpriseScope scope = scope(identity);
            CoverageImpactProposal result = service.proposeImpact(
                    scope, inventoryId, request.target());
            return CorrectnessApiEnvelope.of(
                    identity.correlationId(), scope,
                    List.of("COVERAGE_IMPACT_PROPOSAL_V1"), result);
        } catch (CoverageCommandException failure) {
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
                "If-Match must contain one non-negative numeric Inventory revision.");
    }

    private static void requireScope(EnterpriseScope supplied, EnterpriseScope authorized) {
        if (!authorized.equals(supplied)) {
            throw failure("RG.CORRECTNESS.INVENTORY_NOT_FOUND",
                    "Coverage Inventory was not found in the authorized scope.");
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

    private static CoverageCommandException failure(String code, String message) {
        return new CoverageCommandException(code, message);
    }

    private static IntegrationProblemException problem(
            CoverageCommandException failure,
            IntegrationRequestContext identity
    ) {
        int status = switch (failure.code()) {
            case "RG.CORRECTNESS.INVENTORY_NOT_FOUND" -> 404;
            case "RG.CORRECTNESS.FREEZE_FORBIDDEN",
                    "RG.CORRECTNESS.FOUR_EYES_REQUIRED" -> 403;
            case "RG.CORRECTNESS.REVISION_CONFLICT",
                    "RG.CORRECTNESS.INVENTORY_IMMUTABLE",
                    "RG.CORRECTNESS.REFERENCE_DRIFT",
                    "RG.CORRECTNESS.IDEMPOTENCY_CONFLICT",
                    "RG.CORRECTNESS.IDEMPOTENCY_RECEIPT_STALE" -> 409;
            case "RG.CORRECTNESS.PRECONDITION_REQUIRED" -> 428;
            case "RG.CORRECTNESS.IDEMPOTENCY_UNAVAILABLE" -> 503;
            case "RG.CORRECTNESS.DENOMINATOR_EMPTY",
                    "RG.CORRECTNESS.DENOMINATOR_NOT_FROZEN",
                    "RG.CORRECTNESS.OBLIGATION_REVIEW_REQUIRED",
                    "RG.CORRECTNESS.WAIVER_EXPIRED" -> 422;
            default -> 400;
        };
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:correctness-authoring", failure.getMessage(), status,
                failure.code(), false, identity.correlationId(), Map.of()));
    }

    public record FreezeRequest(String comment) {}

    public record ImpactRequest(ExactTargetRef target) {}
}
