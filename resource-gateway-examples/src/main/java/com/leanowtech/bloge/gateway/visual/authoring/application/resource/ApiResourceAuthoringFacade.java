package com.leanowtech.bloge.gateway.visual.authoring.application.resource;

import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StoredApiConnection;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StrongEtag;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionSnapshot;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceSaveReceiptClosure;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringCommandClaimStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandFailureCode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StagedApiResource;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * First compound Resource-save tracer: EXISTING Connection and no Fixture Set.
 *
 * <p>The facade validates the frozen compound command, resolves an opaque
 * Resource precondition, and claims one Resource lifecycle before staging all
 * three projections. Nested Connection creation and FROM_EXAMPLES remain
 * explicit unavailable capabilities; they are never ignored or partially
 * persisted.</p>
 *
 * <p>Committed replay is receipt-first and does not consult the current
 * Connection or Resource head. A newly acquired attempt resolves one payload-
 * free Auth.NONE Connection, then requires projection compilation to bind the
 * exact same committed revision and metadata fingerprint.</p>
 */
public final class ApiResourceAuthoringFacade {
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");

    private final ApiResourceCommitStore resources;
    private final ApiConnectionAuthoringStore connections;
    private final ApiResourceDecisions decisions;

    /** Creates the facade over one complete Resource lifecycle and read-only Connection authority. */
    public ApiResourceAuthoringFacade(ApiResourceCommitStore resources,
                                      ApiConnectionAuthoringStore connections,
                                      ApiResourceDecisions decisions) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    /** Saves one Resource revision or returns its exact committed replay. */
    public ApiResourceAuthoringResult save(ApiResourceAuthoringRequest request) {
        Preflight preflight = preflight(request);
        ExpectedRevision expected = expectedRevision(preflight);
        String fingerprint;
        try {
            fingerprint = decisions.requestFingerprint(request.resourceId(), preflight.connectionId(),
                    request.command().resource());
        } catch (RuntimeException ex) {
            throw mapFailure(ex, null);
        }
        CommandKey key = new CommandKey(request.scope(), request.actorId(), AuthoringEndpoint.API_RESOURCE_SAVE,
                request.resourceId(), request.idempotencyKey());
        ClaimResult claim;
        try {
            claim = resources.claim(key, fingerprint, expected);
        } catch (RuntimeException ex) {
            throw mapFailure(ex, null);
        }
        if (claim instanceof ClaimResult.Replay replay) return replay(preflight, replay.receipt());
        if (claim instanceof ClaimResult.Busy busy) throw failure(ApiResourceAuthoringFailure.Code.BUSY, busy.retryAt());
        if (claim instanceof ClaimResult.Conflict) throw failure(ApiResourceAuthoringFailure.Code.CONFLICT);
        if (!(claim instanceof ClaimResult.Acquired acquired)) {
            throw failure(ApiResourceAuthoringFailure.Code.INTEGRITY);
        }

        CommandLease lease = acquired.lease();
        boolean committed = false;
        try {
            StoredApiConnection connection = requiredConnection(request.scope(), preflight.connectionId());
            StagedApiResource staged = resources.stage(lease, preflight.connectionId(), request.command().resource());
            requireConnectionSnapshot(connection, staged.projections().connectionSnapshot());
            CommandReceipt receipt = ApiResourceSaveReceiptClosure.create(staged);
            StoredApiResource stored = new StoredApiResource(request.scope(), staged.resource(),
                    staged.projections(), receipt);
            CommandReceipt committedReceipt = resources.commit(lease, receipt);
            committed = true;
            if (!receipt.equals(committedReceipt)) throw failure(ApiResourceAuthoringFailure.Code.INTEGRITY);
            return new ApiResourceAuthoringResult(stored, false);
        } catch (RuntimeException ex) {
            if (!committed) {
                ApiResourceAuthoringFailure cleanup = safeFail(lease, failureCode(ex));
                if (cleanup != null) throw cleanup;
            }
            throw mapFailure(ex, lease);
        }
    }

    private Preflight preflight(ApiResourceAuthoringRequest request) {
        if (request == null || request.scope() == null || request.precondition() == null
                || request.command() == null || !validIdentifier(request.actorId(), 256)
                || !validIdentifier(request.resourceId(), 128)
                || !validIdentifier(request.idempotencyKey(), 256)) {
            throw failure(ApiResourceAuthoringFailure.Code.VALIDATION);
        }
        ApiResourceSaveCommand command = request.command();
        if (!ApiResourceSaveCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.connection() == null || command.resource() == null
                || command.defaultFixture() == null) {
            throw failure(ApiResourceAuthoringFailure.Code.VALIDATION);
        }
        if (!(command.connection() instanceof ApiResourceSaveCommand.Connection.Existing existing)
                || !(command.defaultFixture() instanceof ApiResourceSaveCommand.DefaultFixture.None)) {
            throw failure(ApiResourceAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        }
        if (!validIdentifier(existing.connectionId(), 128)) {
            throw failure(ApiResourceAuthoringFailure.Code.VALIDATION);
        }
        try {
            decisions.validateForAuthoring(command.resource());
        } catch (RuntimeException ex) {
            throw mapFailure(ex, null);
        }

        if (request.precondition() instanceof ApiResourceAuthoringPrecondition.Create) {
            return new Preflight(request.scope(), request.resourceId(), existing.connectionId(), null);
        }
        if (request.precondition() instanceof ApiResourceAuthoringPrecondition.MatchStrongEtag match) {
            if (!StrongEtag.isValid(match.strongEtag())) {
                throw failure(ApiResourceAuthoringFailure.Code.VALIDATION);
            }
            Optional<StoredApiResource> historical;
            try {
                historical = resources.findRevisionByStrongEtag(
                        request.scope(), request.resourceId(), match.strongEtag());
            } catch (RuntimeException ex) {
                throw mapFailure(ex, null);
            }
            if (historical.isEmpty()) {
                try {
                    if (resources.findHead(request.scope(), request.resourceId()).isPresent()) {
                        throw failure(ApiResourceAuthoringFailure.Code.CAS_MISMATCH);
                    }
                } catch (ApiResourceAuthoringFailure ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    throw mapFailure(ex, null);
                }
                throw failure(ApiResourceAuthoringFailure.Code.NOT_FOUND);
            }
            return new Preflight(request.scope(), request.resourceId(), existing.connectionId(), historical.get());
        }
        throw failure(ApiResourceAuthoringFailure.Code.VALIDATION);
    }

    private ApiResourceAuthoringResult replay(Preflight preflight, CommandReceipt receipt) {
        if (receipt == null) throw failure(ApiResourceAuthoringFailure.Code.INTEGRITY);
        StoredApiResource stored;
        try {
            stored = resources.findRevisionByStrongEtag(preflight.scope(), preflight.resourceId(),
                    receipt.strongEtag()).orElseThrow(() -> failure(ApiResourceAuthoringFailure.Code.INTEGRITY));
            if (!stored.receipt().equals(receipt)) throw failure(ApiResourceAuthoringFailure.Code.INTEGRITY);
            ApiResourceSaveReceiptClosure.require(receipt, stored.resource(),
                    stored.projections().connectionSnapshot());
        } catch (IllegalArgumentException ex) {
            throw failure(ApiResourceAuthoringFailure.Code.INTEGRITY);
        } catch (RuntimeException ex) {
            throw mapFailure(ex, null);
        }
        long inputRevision = preflight.historical() == null ? 0 : preflight.historical().resource().revision();
        if (stored.resource().revision() != inputRevision + 1
                || !preflight.connectionId().equals(stored.resource().connectionId())) {
            throw failure(ApiResourceAuthoringFailure.Code.INTEGRITY);
        }
        return new ApiResourceAuthoringResult(stored, true);
    }

    private StoredApiConnection requiredConnection(AuthoringScope scope, String connectionId) {
        StoredApiConnection stored;
        try {
            stored = connections.findHead(scope, connectionId)
                    .orElseThrow(() -> failure(ApiResourceAuthoringFailure.Code.CONNECTION_NOT_FOUND));
        } catch (RuntimeException ex) {
            throw mapFailure(ex, null);
        }
        if (!"NONE".equals(stored.view().auth().kind()) || stored.view().auth().configured()) {
            throw failure(ApiResourceAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        }
        return stored;
    }

    private static void requireConnectionSnapshot(StoredApiConnection connection,
                                                  ApiResourceConnectionSnapshot snapshot) {
        if (!connection.view().connectionId().equals(snapshot.connectionId())
                || connection.view().revision() != snapshot.revision()
                || !connection.metadataFingerprint().equals(snapshot.metadataFingerprint())) {
            throw failure(ApiResourceAuthoringFailure.Code.CONNECTION_CHANGED);
        }
    }

    private static ExpectedRevision expectedRevision(Preflight preflight) {
        return preflight.historical() == null ? ExpectedRevision.create()
                : ExpectedRevision.match(preflight.historical().resource().revision());
    }

    private ApiResourceAuthoringFailure safeFail(CommandLease lease, CommandFailureCode code) {
        try {
            resources.fail(lease, code);
            return null;
        } catch (RuntimeException cleanup) {
            return mapFailure(cleanup, lease);
        }
    }

    private static CommandFailureCode failureCode(RuntimeException ex) {
        if (ex instanceof ApiResourceCommitStoreException storeFailure) {
            return switch (storeFailure.code()) {
                case CAS_MISMATCH -> CommandFailureCode.CAS_MISMATCH;
                case PROJECTION_INVALID -> CommandFailureCode.PROJECTION_INVALID;
                case RECEIPT_INVALID -> CommandFailureCode.RECEIPT_INVALID;
                default -> CommandFailureCode.INTERNAL;
            };
        }
        if (ex instanceof ApiResourceAuthoringException domain
                && (domain.code() == ApiResourceAuthoringException.Code.ALREADY_EXISTS
                || domain.code() == ApiResourceAuthoringException.Code.CAS_MISMATCH
                || domain.code() == ApiResourceAuthoringException.Code.NOT_FOUND)) {
            return CommandFailureCode.CAS_MISMATCH;
        }
        return CommandFailureCode.INTERNAL;
    }

    private static ApiResourceAuthoringFailure mapFailure(RuntimeException ex, CommandLease lease) {
        if (ex instanceof ApiResourceAuthoringFailure failure) return failure;
        if (ex instanceof ApiResourceAuthoringException failure) {
            return switch (failure.code()) {
                case VALIDATION -> failure(ApiResourceAuthoringFailure.Code.VALIDATION);
                case NOT_FOUND -> failure(ApiResourceAuthoringFailure.Code.NOT_FOUND);
                case ALREADY_EXISTS, CAS_MISMATCH -> failure(ApiResourceAuthoringFailure.Code.CAS_MISMATCH);
            };
        }
        if (ex instanceof ApiResourceCommitStoreException failure) {
            return switch (failure.code()) {
                case LEASE_FENCED -> failure(ApiResourceAuthoringFailure.Code.LEASE_LOST);
                case LEASE_EXPIRED -> failure(ApiResourceAuthoringFailure.Code.BUSY,
                        lease == null ? null : lease.leaseUntil());
                case CAS_MISMATCH -> failure(ApiResourceAuthoringFailure.Code.CAS_MISMATCH);
                case PROJECTION_INVALID -> failure(ApiResourceAuthoringFailure.Code.PROJECTION_INVALID);
                case STAGE_MISSING, RECEIPT_INVALID, INTEGRITY ->
                        failure(ApiResourceAuthoringFailure.Code.INTEGRITY);
            };
        }
        if (ex instanceof AuthoringCommandClaimStoreException failure) {
            return switch (failure.code()) {
                case LEASE_FENCED -> failure(ApiResourceAuthoringFailure.Code.LEASE_LOST);
                case LEASE_EXPIRED -> failure(ApiResourceAuthoringFailure.Code.BUSY,
                        lease == null ? null : lease.leaseUntil());
                case INTEGRITY -> failure(ApiResourceAuthoringFailure.Code.INTEGRITY);
                case PERSISTENCE -> failure(ApiResourceAuthoringFailure.Code.PERSISTENCE);
            };
        }
        if (ex instanceof ApiConnectionCommitStoreException failure) {
            return failure(failure.code() == ApiConnectionCommitStoreException.Code.INTEGRITY
                    ? ApiResourceAuthoringFailure.Code.INTEGRITY
                    : ApiResourceAuthoringFailure.Code.PERSISTENCE);
        }
        if (ex instanceof IllegalArgumentException) return failure(ApiResourceAuthoringFailure.Code.VALIDATION);
        return failure(ApiResourceAuthoringFailure.Code.PERSISTENCE);
    }

    private static boolean validIdentifier(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
                && IDENTIFIER.matcher(value).matches();
    }

    private static ApiResourceAuthoringFailure failure(ApiResourceAuthoringFailure.Code code) {
        return new ApiResourceAuthoringFailure(code);
    }

    private static ApiResourceAuthoringFailure failure(ApiResourceAuthoringFailure.Code code, Instant retryAt) {
        return new ApiResourceAuthoringFailure(code, retryAt);
    }

    private record Preflight(AuthoringScope scope, String resourceId, String connectionId,
                             StoredApiResource historical) { }
}
