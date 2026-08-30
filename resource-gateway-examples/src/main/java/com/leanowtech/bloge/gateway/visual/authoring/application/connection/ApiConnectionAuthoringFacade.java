package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StrongEtag;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Standalone Connection application tracer for the API_CONNECTION_SAVE
 * lifecycle.
 *
 * <p>This facade accepts one lifecycle-complete store, validates the pure
 * command before looking up an ETag or consuming a claim, and then executes
 * claim → stage → commit. It currently supports only {@link
 * ApiConnectionCommand.Auth.None}. Credential writes remain an explicit
 * future capability owned by the secret coordinator; rejecting them here
 * prevents values and references from entering fingerprints or diagnostics.</p>
 *
 * <p>Replay is receipt-first and historical: an old ETag reconstructs the
 * expected revision, while a committed receipt is validated against the exact
 * historical Connection view even if the current head has advanced. A newly
 * acquired command still relies on the commit store's current-head CAS.</p>
 */
public final class ApiConnectionAuthoringFacade {
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");

    private final ApiConnectionAuthoringStore store;
    private final ApiConnectionDecisions decisions;
    private final ObjectMapper mapper;

    /**
     * Creates a facade with explicitly shared pure collaborators. The mapper
     * must be the same configured mapper used by the lifecycle store when it
     * serializes receipt bodies; replay validation is intentionally exact.
     */
    public ApiConnectionAuthoringFacade(ApiConnectionAuthoringStore store,
                                       ApiConnectionDecisions decisions,
                                       ObjectMapper mapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    }

    /**
     * Saves one payload-free API Connection view.
     *
     * @param request trusted scope/actor/target and write-only command
     * @return committed view, strong ETag, and replay marker
     * @throws ApiConnectionAuthoringFailure closed, payload-free application failure
     */
    public ApiConnectionAuthoringResult save(ApiConnectionAuthoringRequest request) {
        Preflight preflight = preflight(request);
        ExpectedRevision expected = expectedRevision(preflight);
        String fingerprint = safeFingerprint(preflight, request.command());
        CommandKey key = new CommandKey(request.scope(), request.actorId(),
                AuthoringEndpoint.API_CONNECTION_SAVE, request.connectionId(), request.idempotencyKey());

        ClaimResult claim;
        try {
            claim = store.claim(key, fingerprint, expected);
        } catch (RuntimeException ex) {
            throw mapFailure(ex);
        }
        if (claim instanceof ClaimResult.Replay replay) {
            return replay(preflight, replay.receipt());
        }
        if (claim instanceof ClaimResult.Busy) throw failure(ApiConnectionAuthoringFailure.Code.BUSY);
        if (claim instanceof ClaimResult.Conflict) throw failure(ApiConnectionAuthoringFailure.Code.CONFLICT);
        if (!(claim instanceof ClaimResult.Acquired acquired)) {
            throw failure(ApiConnectionAuthoringFailure.Code.INTEGRITY);
        }

        CommandLease lease = acquired.lease();
        try {
            store.stage(lease, request.connectionId(), expected, request.command());
            var committed = store.commit(lease);
            return new ApiConnectionAuthoringResult(committed.view(), committed.strongEtag(), false);
        } catch (RuntimeException ex) {
            safeFail(lease);
            throw mapFailure(ex);
        }
    }

    private Preflight preflight(ApiConnectionAuthoringRequest request) {
        if (request == null || request.scope() == null || request.command() == null
                || request.precondition() == null || !validIdentifier(request.actorId(), 256)
                || !validIdentifier(request.connectionId(), 128)
                || !validIdentifier(request.idempotencyKey(), 256)) {
            throw failure(ApiConnectionAuthoringFailure.Code.VALIDATION);
        }
        ApiConnectionCommand command = request.command();
        if (command.auth() != null && !(command.auth() instanceof ApiConnectionCommand.Auth.None)) {
            // Capability is checked before the pure fingerprint path.  No
            // credential value/ref is copied into a failure or log message.
            throw failure(ApiConnectionAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        }
        try {
            decisions.validateForAuthoring(command);
        } catch (ApiConnectionAuthoringException ex) {
            throw mapFailure(ex);
        }
        if (request.precondition() instanceof ApiConnectionAuthoringPrecondition.MatchStrongEtag match) {
            if (!StrongEtag.isValid(match.strongEtag())) {
                throw failure(ApiConnectionAuthoringFailure.Code.VALIDATION);
            }
            Optional<com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StoredApiConnection> historical;
            try {
                historical = store.findRevisionByStrongEtag(request.scope(), request.connectionId(), match.strongEtag());
            } catch (RuntimeException ex) {
                throw mapFailure(ex);
            }
            if (historical.isEmpty()) {
                // An existing target with an unknown historical tag is an
                // optimistic-concurrency miss, not a missing resource. This
                // distinction lets callers retry with a freshly read tag.
                try {
                    if (store.findHead(request.scope(), request.connectionId()).isPresent()) {
                        throw failure(ApiConnectionAuthoringFailure.Code.CAS_MISMATCH);
                    }
                } catch (ApiConnectionAuthoringFailure ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    throw mapFailure(ex);
                }
                throw failure(ApiConnectionAuthoringFailure.Code.NOT_FOUND);
            }
            return new Preflight(request.scope(), request.connectionId(), historical.get());
        }
        if (request.precondition() instanceof ApiConnectionAuthoringPrecondition.Create) {
            return new Preflight(request.scope(), request.connectionId(), null);
        }
        throw failure(ApiConnectionAuthoringFailure.Code.VALIDATION);
    }

    private String safeFingerprint(Preflight preflight, ApiConnectionCommand command) {
        try {
            return decisions.requestFingerprint(preflight.scope(), preflight.connectionId(), command);
        } catch (ApiConnectionAuthoringException ex) {
            throw mapFailure(ex);
        }
    }

    private ExpectedRevision expectedRevision(Preflight preflight) {
        return preflight.historical() == null ? ExpectedRevision.create()
                : ExpectedRevision.match(preflight.historical().view().revision());
    }

    private ApiConnectionAuthoringResult replay(Preflight preflight, CommandReceipt receipt) {
        if (receipt == null || !ApiConnectionView.SCHEMA_VERSION.equals(receipt.schemaVersion())
                || !StrongEtag.isValid(receipt.strongEtag())) {
            throw failure(ApiConnectionAuthoringFailure.Code.INTEGRITY);
        }
        var scope = preflight.scope();
        var connectionId = preflight.connectionId();
        var requestedRevision = preflight.historical() == null ? null : preflight.historical().view().revision();
        // For an update the precondition ETag identifies the input revision;
        // the receipt ETag identifies the newly committed output revision.
        // Resolve that output independently so replay remains valid after the
        // current head advances. Create replay follows the same exact lookup.
        com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StoredApiConnection historical;
        try {
            historical = store.findRevisionByStrongEtag(scope, connectionId, receipt.strongEtag()).orElse(null);
        } catch (RuntimeException ex) {
            throw mapFailure(ex);
        }
        if (historical == null) throw failure(ApiConnectionAuthoringFailure.Code.INTEGRITY);
        if (!scope.equals(historical.scope())
                || !connectionId.equals(historical.view().connectionId())) {
            throw failure(ApiConnectionAuthoringFailure.Code.INTEGRITY);
        }
        if (requestedRevision != null && historical.view().revision() != requestedRevision + 1
                || requestedRevision == null && historical.view().revision() != 1) {
            throw failure(ApiConnectionAuthoringFailure.Code.INTEGRITY);
        }
        JsonNode expectedBody = mapper.valueToTree(historical.view());
        if (!expectedBody.equals(receipt.body())
                || !AuthoringFingerprints.of(expectedBody).equals(receipt.bodyFingerprint())) {
            throw failure(ApiConnectionAuthoringFailure.Code.INTEGRITY);
        }
        return new ApiConnectionAuthoringResult(historical.view(), historical.strongEtag(), true);
    }

    private void safeFail(CommandLease lease) {
        try {
            store.fail(lease);
        } catch (RuntimeException ignored) {
            // Preserve the original stage/commit failure. The store's exact
            // lease fence remains the cleanup authority.
        }
    }

    private static boolean validIdentifier(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
                && IDENTIFIER.matcher(value).matches();
    }

    private static ApiConnectionAuthoringFailure mapFailure(RuntimeException ex) {
        if (ex instanceof ApiConnectionAuthoringFailure failure) return failure;
        if (ex instanceof ApiConnectionAuthoringException failure) {
            return switch (failure.code()) {
                case NOT_FOUND -> failure(ApiConnectionAuthoringFailure.Code.NOT_FOUND);
                case CAS_MISMATCH, ALREADY_EXISTS -> failure(ApiConnectionAuthoringFailure.Code.CAS_MISMATCH);
                case VALIDATION -> failure(ApiConnectionAuthoringFailure.Code.VALIDATION);
            };
        }
        if (ex instanceof ApiConnectionCommitStoreException failure) {
            return switch (failure.code()) {
                case LEASE_FENCED, CAS_MISMATCH -> failure(ApiConnectionAuthoringFailure.Code.CAS_MISMATCH);
                case LEASE_EXPIRED -> failure(ApiConnectionAuthoringFailure.Code.BUSY);
                case STAGE_MISSING -> failure(ApiConnectionAuthoringFailure.Code.INTEGRITY);
                case INTEGRITY -> failure(ApiConnectionAuthoringFailure.Code.INTEGRITY);
            };
        }
        if (ex instanceof ApiResourceCommitStoreException failure) {
            return switch (failure.code()) {
                case LEASE_FENCED, CAS_MISMATCH -> failure(ApiConnectionAuthoringFailure.Code.CAS_MISMATCH);
                case LEASE_EXPIRED -> failure(ApiConnectionAuthoringFailure.Code.BUSY);
                default -> failure(ApiConnectionAuthoringFailure.Code.PERSISTENCE);
            };
        }
        if (ex instanceof IllegalArgumentException) return failure(ApiConnectionAuthoringFailure.Code.VALIDATION);
        return failure(ApiConnectionAuthoringFailure.Code.PERSISTENCE);
    }

    private static ApiConnectionAuthoringFailure failure(ApiConnectionAuthoringFailure.Code code) {
        return new ApiConnectionAuthoringFailure(code);
    }

    private record Preflight(
            AuthoringScope scope,
            String connectionId,
            com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.StoredApiConnection historical) { }
}
