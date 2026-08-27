package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle.OracleLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.OracleReviewAuthorizer.ApprovalDecision;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredBusinessOracle;

import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** Enforces the independent business-owner lifecycle for Business Oracles. */
public class BusinessOracleService {

    private final BusinessOracleRepository oracles;
    private final OracleReviewAuthorizer authorizer;
    private final OracleBasisSource basisSource;
    private final OracleApprovalReceiptRepository approvalReceipts;
    private final ObjectMapper mapper;
    private final Clock clock;

    public BusinessOracleService(
            BusinessOracleRepository oracles,
            OracleReviewAuthorizer authorizer,
            OracleBasisSource basisSource
    ) {
        this(oracles, authorizer, basisSource, null, null, Clock.systemUTC());
    }

    public BusinessOracleService(
            BusinessOracleRepository oracles,
            OracleReviewAuthorizer authorizer,
            OracleBasisSource basisSource,
            Clock clock
    ) {
        this(oracles, authorizer, basisSource, null, null, clock);
    }

    public BusinessOracleService(
            BusinessOracleRepository oracles,
            OracleReviewAuthorizer authorizer,
            OracleBasisSource basisSource,
            OracleApprovalReceiptRepository approvalReceipts,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.oracles = Objects.requireNonNull(oracles, "oracles");
        this.authorizer = authorizer == null ? OracleReviewAuthorizer.denyAll() : authorizer;
        this.basisSource = basisSource == null ? OracleBasisSource.denyAll() : basisSource;
        this.approvalReceipts = approvalReceipts;
        this.mapper = mapper;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StoredBusinessOracle saveProposed(
            long expectedRevision,
            BusinessOracle candidate,
            PrincipalRef actor
    ) {
        requireActor(actor);
        if (candidate == null || expectedRevision < 0
                || candidate.revision() != expectedRevision
                || candidate.lifecycle() != OracleLifecycle.PROPOSED
                || candidate.approval().status() != ReviewStatus.PENDING) {
            throw failure("RG.CORRECTNESS.ORACLE_DRAFT_INVALID",
                    "Oracle save requires a matching PROPOSED revision with pending approval.");
        }
        StoredBusinessOracle current = oracles.findHead(
                candidate.scope(), candidate.oracleId()).orElse(null);
        if (current != null && current.oracle().lifecycle() != OracleLifecycle.PROPOSED) {
            throw failure("RG.CORRECTNESS.ORACLE_IMMUTABLE",
                    "Approved or superseded Oracle revisions cannot be edited.");
        }
        return oracles.saveIfRevision(expectedRevision, candidate, actor)
                .orElseThrow(BusinessOracleService::conflict);
    }

    public StoredBusinessOracle approve(
            EnterpriseScope scope,
            String oracleId,
            long expectedRevision,
            String reviewComment,
            PrincipalRef actor
    ) {
        requireActor(actor);
        String comment = reviewComment == null ? "" : reviewComment.trim();
        if (scope == null || oracleId == null || oracleId.isBlank()
                || expectedRevision < 1 || comment.isEmpty() || comment.length() > 4096) {
            throw failure("RG.CORRECTNESS.ORACLE_REVIEW_INVALID",
                    "Oracle approval requires an exact revision and bounded review comment.");
        }
        StoredBusinessOracle stored = oracles.findHead(scope, oracleId.trim())
                .orElseThrow(() -> failure("RG.CORRECTNESS.ORACLE_NOT_FOUND",
                        "Business Oracle was not found in the authorized scope."));
        BusinessOracle current = stored.oracle();
        if (current.revision() != expectedRevision) throw conflict();
        if (current.lifecycle() != OracleLifecycle.PROPOSED) {
            throw failure("RG.CORRECTNESS.ORACLE_IMMUTABLE",
                    "Only a proposed Business Oracle can be approved.");
        }
        if (current.basisRefs().isEmpty()) {
            throw failure("RG.CORRECTNESS.ORACLE_BASIS_REQUIRED",
                    "Business Oracle approval requires at least one exact basis reference.");
        }
        if (!basisSource.referencesAreCurrent(scope, current.target(), current.basisRefs())) {
            throw failure("RG.CORRECTNESS.ORACLE_BASIS_DRIFT",
                    "One or more Business Oracle basis references are no longer exact.");
        }
        ApprovalDecision decision = authorizer.authorize(scope, current, actor);
        if (decision == null || !decision.allowed()) {
            throw failure("RG.CORRECTNESS.ORACLE_APPROVAL_FORBIDDEN",
                    "The actor is not authorized to approve this Business Oracle.");
        }
        if (decision.independentReviewRequired()
                && current.metadata().createdBy().id().equals(actor.id())) {
            throw failure("RG.CORRECTNESS.FOUR_EYES_REQUIRED",
                    "Business Oracle approval requires a reviewer other than its creator.");
        }

        BusinessOracle approved = new BusinessOracle(
                current.schemaVersion(), current.oracleId(), current.revision(), current.scope(),
                current.target(), current.statement(), current.forbiddenOutcomes(),
                current.basisRefs(), current.owner(), OracleLifecycle.APPROVED,
                new ReviewRecord(ReviewStatus.APPROVED, actor, clock.instant(), comment),
                current.assertionSetRefs(), current.metadata());
        return oracles.saveIfRevision(expectedRevision, approved, actor)
                .orElseThrow(BusinessOracleService::conflict);
    }

    @Transactional
    public ApprovalResult approveIdempotently(
            EnterpriseScope scope,
            String oracleId,
            long expectedRevision,
            String reviewComment,
            PrincipalRef actor,
            String idempotencyKey
    ) {
        requireActor(actor);
        String normalizedId = oracleId == null ? "" : oracleId.trim();
        String comment = reviewComment == null ? "" : reviewComment.trim();
        if (scope == null || normalizedId.isEmpty() || expectedRevision < 1
                || comment.isEmpty() || comment.length() > 4096) {
            throw failure("RG.CORRECTNESS.ORACLE_REVIEW_INVALID",
                    "Oracle approval requires an exact revision and bounded review comment.");
        }
        if (approvalReceipts == null || mapper == null) {
            throw failure("RG.CORRECTNESS.IDEMPOTENCY_UNAVAILABLE",
                    "Oracle approval is unavailable because its receipt store is missing.");
        }
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (!key.matches("[A-Za-z0-9._~:-]{1,160}")) {
            throw failure("RG.CORRECTNESS.IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must use 1-160 portable non-whitespace characters.");
        }
        String keyFingerprint = CorrectnessProtocolFingerprint.derivedFingerprint(
                mapper, Map.of("idempotencyKey", key));
        String requestFingerprint = CorrectnessProtocolFingerprint.derivedFingerprint(
                mapper, Map.of(
                        "scope", scope,
                        "oracleId", normalizedId,
                        "expectedRevision", expectedRevision,
                        "reviewComment", comment,
                        "actorId", actor.id()));
        OracleApprovalReceipt existing = approvalReceipts.find(scope, keyFingerprint).orElse(null);
        if (existing != null) return replay(existing, requestFingerprint);

        StoredBusinessOracle stored = approve(
                scope, normalizedId, expectedRevision, comment, actor);
        OracleApprovalReceipt receipt = new OracleApprovalReceipt(
                "", scope, keyFingerprint, requestFingerprint,
                new ExactAssetRef(
                        "ORACLE", stored.oracle().oracleId(), stored.oracle().revision(),
                        stored.oracleFingerprint()),
                stored.oracle().basisRefs().size(), actor.id(), clock.instant());
        if (!approvalReceipts.saveIfAbsent(receipt)) {
            OracleApprovalReceipt winner = approvalReceipts.find(scope, keyFingerprint)
                    .orElseThrow(() -> failure("RG.CORRECTNESS.IDEMPOTENCY_UNAVAILABLE",
                            "The concurrent Oracle approval receipt is not yet readable."));
            return replay(winner, requestFingerprint);
        }
        return new ApprovalResult(stored, stored.oracle().basisRefs().size(), false);
    }

    private ApprovalResult replay(
            OracleApprovalReceipt receipt,
            String requestFingerprint
    ) {
        if (!receipt.requestFingerprint().equals(requestFingerprint)) {
            throw failure("RG.CORRECTNESS.IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key was already used for a different Oracle approval command.");
        }
        ExactAssetRef ref = receipt.oracleRef();
        StoredBusinessOracle stored = oracles.findRevision(
                        receipt.scope(), ref.id(), ref.revision())
                .filter(value -> value.oracleFingerprint().equals(ref.fingerprint()))
                .filter(value -> value.oracle().lifecycle() == OracleLifecycle.APPROVED)
                .orElseThrow(() -> failure("RG.CORRECTNESS.IDEMPOTENCY_RECEIPT_STALE",
                        "The approved Oracle referenced by the command receipt is unavailable."));
        return new ApprovalResult(stored, receipt.basisCount(), true);
    }

    private static void requireActor(PrincipalRef actor) {
        if (actor == null) {
            throw failure("RG.CORRECTNESS.ACTOR_REQUIRED",
                    "An authenticated command actor is required.");
        }
    }

    private static OracleAssertionCommandException conflict() {
        return failure("RG.CORRECTNESS.REVISION_CONFLICT",
                "The Business Oracle changed; reload the exact head and retry.");
    }

    private static OracleAssertionCommandException failure(String code, String message) {
        return new OracleAssertionCommandException(code, message);
    }

    public record ApprovalResult(
            StoredBusinessOracle stored,
            int basisCount,
            boolean replayed
    ) {
        public ApprovalResult {
            if (stored == null || stored.oracle().lifecycle() != OracleLifecycle.APPROVED
                    || basisCount < 1) {
                throw new IllegalArgumentException("Approved Business Oracle result is required");
            }
        }
    }
}
