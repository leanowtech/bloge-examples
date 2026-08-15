package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredAssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredBusinessOracle;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Authenticated command adapter for Business Oracle and Assertion Set authoring. */
@RestController
@ConditionalOnBean({BusinessOracleService.class, AssertionSetService.class})
public final class OracleAssertionController {

    private final BusinessOracleService oracleService;
    private final AssertionSetService assertionService;
    private final IntegrationRequestAuthenticator authenticator;

    public OracleAssertionController(
            BusinessOracleService oracleService,
            AssertionSetService assertionService,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.oracleService = oracleService;
        this.assertionService = assertionService;
        this.authenticator = authenticator;
    }

    @PutMapping("/api/visual/oracles/{oracleId}")
    public ResponseEntity<CorrectnessApiEnvelope<StoredBusinessOracle>> saveOracle(
            @PathVariable String oracleId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) BusinessOracle candidate
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_ORACLE_WRITE);
        try {
            long expectedRevision = expectedRevision(headers);
            EnterpriseScope scope = scope(identity);
            if (candidate == null || !oracleId.equals(candidate.oracleId())) {
                throw failure("RG.CORRECTNESS.ORACLE_DRAFT_INVALID",
                        "The request body must match the Business Oracle path.");
            }
            requireScope(candidate.scope(), scope);
            StoredBusinessOracle stored = oracleService.saveProposed(
                    expectedRevision, candidate, actor(identity));
            HttpStatus status = expectedRevision == 0 ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status)
                    .eTag(Long.toString(stored.oracle().revision()))
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope,
                            List.of("BUSINESS_ORACLE_CAS_V1"), stored));
        } catch (OracleAssertionCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/api/visual/oracles/{oracleId}:approve")
    public ResponseEntity<CorrectnessApiEnvelope<BusinessOracleService.ApprovalResult>>
            approveOracle(
                    @PathVariable String oracleId,
                    @RequestHeader HttpHeaders headers,
                    @RequestBody(required = false) ApprovalRequest request
            ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_ORACLE_APPROVE);
        try {
            if (request == null) {
                throw failure("RG.CORRECTNESS.ORACLE_REVIEW_INVALID",
                        "An Oracle approval comment is required.");
            }
            EnterpriseScope scope = scope(identity);
            BusinessOracleService.ApprovalResult result = oracleService.approveIdempotently(
                    scope, oracleId, expectedRevision(headers), request.comment(), actor(identity),
                    headers.getFirst("Idempotency-Key"));
            return ResponseEntity.ok()
                    .eTag(Long.toString(result.stored().oracle().revision()))
                    .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope,
                            List.of("BUSINESS_ORACLE_APPROVAL_V1"), result));
        } catch (OracleAssertionCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PutMapping("/api/visual/assertion-sets/{assertionSetId}")
    public ResponseEntity<CorrectnessApiEnvelope<StoredAssertionSet>> saveAssertionSet(
            @PathVariable String assertionSetId,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) AssertionSet candidate
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_ASSERTION_WRITE);
        try {
            long expectedRevision = expectedRevision(headers);
            if (candidate == null || !assertionSetId.equals(candidate.assertionSetId())) {
                throw failure("RG.CORRECTNESS.ASSERTION_DRAFT_INVALID",
                        "The request body must match the Assertion Set path.");
            }
            EnterpriseScope scope = scope(identity);
            StoredAssertionSet stored = assertionService.saveDraft(
                    scope, expectedRevision, candidate, actor(identity));
            HttpStatus status = expectedRevision == 0 ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status)
                    .eTag(Long.toString(stored.assertionSet().revision()))
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope,
                            List.of("ASSERTION_SET_CAS_V1"), stored));
        } catch (OracleAssertionCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/api/visual/assertion-sets:compile-preview")
    public CorrectnessApiEnvelope<AssertionCompilationReport> compilePreview(
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) AssertionSet candidate
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_ASSERTION_WRITE);
        try {
            EnterpriseScope scope = scope(identity);
            AssertionCompilationReport report = assertionService.compilePreview(scope, candidate);
            return CorrectnessApiEnvelope.of(
                    identity.correlationId(), scope,
                    List.of("ASSERTION_COMPILATION_REPORT_V1"), report);
        } catch (OracleAssertionCommandException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/api/visual/assertion-sets/{assertionSetId}:validate")
    public ResponseEntity<CorrectnessApiEnvelope<AssertionSetService.ValidationResult>>
            validateAssertionSet(
                    @PathVariable String assertionSetId,
                    @RequestHeader HttpHeaders headers
            ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_ASSERTION_VALIDATE);
        try {
            EnterpriseScope scope = scope(identity);
            AssertionSetService.ValidationResult result = assertionService.validate(
                    scope, assertionSetId, expectedRevision(headers), actor(identity));
            return ResponseEntity.ok()
                    .eTag(Long.toString(result.stored().assertionSet().revision()))
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope,
                            List.of("ASSERTION_SET_VALIDATION_V1"), result));
        } catch (OracleAssertionCommandException failure) {
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
                "If-Match must contain one non-negative numeric revision.");
    }

    private static void requireScope(EnterpriseScope supplied, EnterpriseScope authorized) {
        if (!authorized.equals(supplied)) {
            throw failure("RG.CORRECTNESS.ORACLE_NOT_FOUND",
                    "Business Oracle was not found in the authorized scope.");
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

    private static OracleAssertionCommandException failure(String code, String message) {
        return new OracleAssertionCommandException(code, message);
    }

    private static IntegrationProblemException problem(
            OracleAssertionCommandException failure,
            IntegrationRequestContext identity
    ) {
        int status = switch (failure.code()) {
            case "RG.CORRECTNESS.ORACLE_NOT_FOUND",
                    "RG.CORRECTNESS.ASSERTION_SET_NOT_FOUND" -> 404;
            case "RG.CORRECTNESS.ORACLE_APPROVAL_FORBIDDEN",
                    "RG.CORRECTNESS.FOUR_EYES_REQUIRED" -> 403;
            case "RG.CORRECTNESS.REVISION_CONFLICT",
                    "RG.CORRECTNESS.ORACLE_IMMUTABLE",
                    "RG.CORRECTNESS.ASSERTION_SET_IMMUTABLE",
                    "RG.CORRECTNESS.ORACLE_REFERENCE_DRIFT",
                    "RG.CORRECTNESS.ORACLE_BASIS_DRIFT",
                    "RG.CORRECTNESS.IDEMPOTENCY_CONFLICT",
                    "RG.CORRECTNESS.IDEMPOTENCY_RECEIPT_STALE" -> 409;
            case "RG.CORRECTNESS.PRECONDITION_REQUIRED" -> 428;
            case "RG.CORRECTNESS.IDEMPOTENCY_UNAVAILABLE" -> 503;
            case "RG.CORRECTNESS.ORACLE_BASIS_REQUIRED",
                    "RG.CORRECTNESS.ORACLE_NOT_APPROVED",
                    "RG.CORRECTNESS.ASSERTION_NONE",
                    "RG.CORRECTNESS.ASSERTION_UNSUPPORTED",
                    "RG.CORRECTNESS.EVALUATOR_CAPABILITY_UNSUPPORTED",
                    "RG.CORRECTNESS.RUNTIME_LOWERING_UNAVAILABLE",
                    "RG.CORRECTNESS.TARGET_MISMATCH" -> 422;
            default -> 400;
        };
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:correctness-authoring", failure.getMessage(), status,
                failure.code(), false, identity.correlationId(), Map.of()));
    }

    public record ApprovalRequest(String comment) {}
}
