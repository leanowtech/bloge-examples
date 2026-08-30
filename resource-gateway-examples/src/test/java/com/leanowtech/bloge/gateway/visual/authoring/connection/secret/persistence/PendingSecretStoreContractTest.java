package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.SecretOperationContext;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reusable persistence contract. A JDBC adapter can extend this class and provide
 * only a fresh store; provider I/O and coordinator transaction placement remain
 * integration responsibilities outside this contract.
 */
public abstract class PendingSecretStoreContractTest {
    protected static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    protected static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** Supplies a clean store for each test. */
    protected abstract PendingSecretStore newStore(Clock clock);

    @Test void stageAndFindExactAreAtomicAndActiveIsInvisible() {
        PendingSecretBatch batch = batch(lease("one", 1, "one-token", NOW.plusSeconds(60)), "token");
        PendingSecretStore store = newStore(fixedClock());
        store.stage(batch);
        assertThat(store.findExact(batch.lease().commandLease(), batch.lease().coordinate())).contains(batch);
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isEmpty();
    }

    @Test void exactReentryIsIdempotentButDriftIsIntegrity() {
        PendingSecretStore store = newStore(fixedClock());
        PendingSecretBatch batch = batch(lease("reentry", 1, "r-token", NOW.plusSeconds(60)), "token");
        store.stage(batch);
        store.stage(batch);
        PendingSecretBatch drift = batchWithLocator(lease("reentry", 1, "r-token", NOW.plusSeconds(60)),
                "token", "different-locator");
        assertThatThrownBy(() -> store.stage(drift)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    @Test void partialAndExtraActivationOutputsAreRejectedWithoutWrites() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("partial", 1, "p-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token", "password");
        store.stage(batch);
        ActivatedSecretSlot onlyOne = activation("token", "p-token", "active-token");
        assertThatThrownBy(() -> store.commitBindings(batch, List.of(onlyOne)))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isEmpty();
        assertThat(store.findExact(lease, batch.lease().coordinate())).contains(batch);
    }

    @Test void wrongProviderOrLeaseActivationIsRejected() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("provider", 1, "p-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token");
        store.stage(batch);
        ActivatedSecretSlot wrong = new ActivatedSecretSlot("token",
                new ActivatedExternalSecret("other-provider", "p-token", "active"));
        assertThatThrownBy(() -> store.commitBindings(batch, List.of(wrong)))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
    }

    @Test void commitWritesOnlyExactActivatedSlotsAndClearsPending() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("commit", 1, "c-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token", "password");
        store.stage(batch);
        store.commitBindings(batch, List.of(activation("token", "c-token", "active-token"),
                activation("password", "c-token", "active-password")));
        assertThat(store.findExact(lease, batch.lease().coordinate())).isEmpty();
        assertThat(store.findActive(batch.lease().coordinate(), "token")).contains(new ActiveSecretBinding(
                "provider:one", "active-token", "commit"));
        assertThat(store.findActive(batch.lease().coordinate(), "password")).isPresent();
    }

    @Test void commitReplayIsExactAndDoesNotDuplicateOrDrift() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("replay", 1, "replay-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token");
        List<ActivatedSecretSlot> outputs = List.of(activation("token", "replay-token", "active"));
        store.stage(batch);
        store.commitBindings(batch, outputs);
        store.commitBindings(batch, outputs);
        assertThatThrownBy(() -> store.commitBindings(batch,
                List.of(activation("token", "replay-token", "different"))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    @Test void keepExistingCopiesLocatorAndBindsNewCommand() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("keep", 1, "keep-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = new PendingSecretBatch(new PendingSecretLease(lease, coordinate(lease, 3)),
                List.of(new PendingSecretOperation.Retained("token",
                        new ActiveSecretBinding("provider:old", "old-active", "old-command"))));
        store.stage(batch);
        store.commitBindings(batch, List.of());
        assertThat(store.findActive(batch.lease().coordinate(), "token")).contains(
                new ActiveSecretBinding("provider:old", "old-active", "keep"));
    }

    @Test void staleLeaseCannotReadOrMutateExactAttempt() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("fence", 1, "winner", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token");
        store.stage(batch);
        CommandLease stale = lease("fence", 2, "stale", NOW.plusSeconds(60));
        assertThat(store.findExact(stale, batch.lease().coordinate())).isEmpty();
        assertThatThrownBy(() -> store.markAbortRequired(new PendingSecretLease(stale, batch.lease().coordinate())))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.LEASE_FENCED);
    }

    @Test void expiredLeaseIsRecoverableButLivePendingIsNotDue() {
        PendingSecretStore store = newStore(fixedClock());
        PendingSecretBatch live = batch(lease("live", 1, "live-token", NOW.plusSeconds(1)), "token");
        PendingSecretBatch expired = batch(lease("expired", 1, "expired-token", NOW.minusSeconds(1)), "token");
        store.stage(live);
        assertThatThrownBy(() -> store.stage(expired)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.LEASE_EXPIRED);
        // An expired provider receipt may still be hydrated for abort recovery.
        PendingSecretBatch recoverable = batch(lease("expired", 1, "expired-token", NOW.plusSeconds(1)), "token");
        store.stage(recoverable);
        assertThat(store.findRecoveryDue(10)).isEmpty();
    }

    @Test void explicitAbortMayFenceAnExpiredExactLeaseAndIsIdempotent() {
        Clock clock = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);
        PendingSecretStore store = newStore(clock);
        CommandLease lease = lease("abort", 1, "abort-token", NOW.plusSeconds(1));
        PendingSecretBatch batch = batch(lease, "token");
        // In-memory stage models the transaction-time expiry check, so stage before time advances.
        store = newStore(fixedClock());
        store.stage(batch);
        store.markAbortRequired(batch.lease());
        store.markAbortRequired(batch.lease());
        SecretAbortCandidate candidate = store.findRecoveryDue(1).getFirst();
        store.completeAbort(candidate);
        store.completeAbort(candidate);
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isEmpty();
    }

    @Test void abortCandidateMustBeExactAndOnlyAbortRequired() {
        PendingSecretStore store = newStore(fixedClock());
        PendingSecretBatch batch = batch(lease("candidate", 1, "candidate-token", NOW.plusSeconds(60)), "token");
        store.stage(batch);
        SecretAbortCandidate notMarked = new SecretAbortCandidate(batch, List.of());
        assertThatThrownBy(() -> store.completeAbort(notMarked)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.RECOVERY_STATE);
    }

    @Test void recoveryIsStableAndBoundedByCompleteBatches() {
        PendingSecretStore store = newStore(fixedClock());
        for (String id : List.of("b", "a", "c")) {
            PendingSecretBatch batch = batch(lease(id, 1, id + "-token", NOW.plusSeconds(60)), "token");
            store.stage(batch);
            store.markAbortRequired(batch.lease());
        }
        assertThat(store.findRecoveryDue(2)).extracting(candidate -> candidate.batch().lease().commandLease().commandId())
                .containsExactly("a", "b");
    }

    @Test void twoAttemptsSameRevisionDoNotCrossAbort() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease first = lease("same-command", 1, "first", NOW.plusSeconds(60));
        CommandLease second = lease("same-command", 2, "second", NOW.plusSeconds(60));
        PendingSecretBatch firstBatch = batch(first, "token");
        PendingSecretBatch secondBatch = batch(second, "token");
        store.stage(firstBatch);
        store.stage(secondBatch);
        store.commitBindings(firstBatch, List.of(activation("token", "first", "first-active")));
        store.commitBindings(secondBatch, List.of(activation("token", "second", "second-active")));
        store.markAbortRequired(firstBatch.lease());
        store.completeAbort(store.findRecoveryDue(10).getFirst());
        // The durable coordinate has one current binding; the newer exact attempt remains the winner.
        assertThat(store.findActive(firstBatch.lease().coordinate(), "token")).contains(
                new ActiveSecretBinding("provider:one", "second-active", "same-command"));
        assertThat(store.findActive(secondBatch.lease().coordinate(), "token")).contains(
                new ActiveSecretBinding("provider:one", "second-active", "same-command"));
    }

    @Test void differentCoordinatesNeverLeakActiveBinding() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("coordinate", 1, "coordinate-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token");
        store.stage(batch);
        store.commitBindings(batch, List.of(activation("token", "coordinate-token", "active")));
        assertThat(store.findActive(new ConnectionRevisionCoordinate(SCOPE, "other", 3), "token")).isEmpty();
        assertThat(store.findActive(new ConnectionRevisionCoordinate(SCOPE, "connection", 4), "token")).isEmpty();
    }

    @Test void jsonAndDiagnosticsNeverExposeOpaqueValues() throws Exception {
        CommandLease lease = lease("redact", 1, "secret-attempt", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token");
        String json = new ObjectMapper().writeValueAsString(batch);
        assertThat(json).doesNotContain("secret-attempt", "lease-id", "opaque-locator");
        assertThat(batch.toString()).doesNotContain("secret-attempt", "lease-id", "opaque-locator");
    }

    @Test void malformedRecoveryLimitIsStableIntegrity() {
        PendingSecretStore store = newStore(fixedClock());
        assertThatThrownBy(() -> store.findRecoveryDue(0)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    protected Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    protected PendingSecretBatch batch(CommandLease lease, String... slots) {
        List<PendingSecretOperation> operations = java.util.Arrays.stream(slots)
                .map(slot -> new PendingSecretOperation.Prepared(slot, SecretSourceMode.VALUE,
                        prepared(lease, slot))).map(operation -> (PendingSecretOperation) operation).toList();
        return new PendingSecretBatch(new PendingSecretLease(lease, coordinate(lease, 3)), operations);
    }

    protected PendingSecretBatch batchWithLocator(CommandLease lease, String slot, String locator) {
        PreparedExternalSecret value = prepared(lease, slot);
        value = new PreparedExternalSecret(value.providerId(), value.leaseId(), locator,
                value.leaseUntil(), value.context());
        return new PendingSecretBatch(new PendingSecretLease(lease, coordinate(lease, 3)),
                List.of(new PendingSecretOperation.Prepared(slot, SecretSourceMode.VALUE, value)));
    }

    protected PreparedExternalSecret prepared(CommandLease lease, String slot) {
        SecretOperationContext context = new SecretOperationContext(lease.key().scope(), lease.key().actorId(),
                "connection-save", lease.key().targetId(), 3, lease.commandId(), lease.attemptNo(),
                lease.attemptToken(), slot);
        return new PreparedExternalSecret("provider:one", lease.attemptToken(), "opaque-locator-" + slot,
                lease.leaseUntil(), context);
    }

    protected ActivatedSecretSlot activation(String slot, String leaseId, String locator) {
        return new ActivatedSecretSlot(slot, new ActivatedExternalSecret("provider:one", leaseId, locator));
    }

    protected ConnectionRevisionCoordinate coordinate(CommandLease lease, long revision) {
        return new ConnectionRevisionCoordinate(lease.key().scope(), lease.key().targetId(), revision);
    }

    protected CommandLease lease(String commandId, int attempt, String token, Instant until) {
        CommandKey key = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                "connection", "idempotency-" + commandId);
        return new CommandLease(commandId, attempt, token, key, "fingerprint-" + commandId, until,
                ExpectedRevision.match(2));
    }
}
