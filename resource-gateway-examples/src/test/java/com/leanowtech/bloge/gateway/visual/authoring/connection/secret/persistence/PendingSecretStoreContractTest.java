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
import java.util.Set;
import java.lang.reflect.Modifier;

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
        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isEmpty();
    }

    @Test void exactReentryIsIdempotentButDriftIsIntegrity() {
        PendingSecretStore store = newStore(fixedClock());
        PendingSecretBatch batch = batch(lease("reentry", 1, "r-token", NOW.plusSeconds(60)), "password", "token");
        store.stage(batch);
        PendingSecretBatch reordered = batch(lease("reentry", 1, "r-token", NOW.plusSeconds(60)), "token", "password");
        store.stage(reordered);
        PendingSecretBatch drift = batchWithLocator(lease("reentry", 1, "r-token", NOW.plusSeconds(60)), "token", "different-locator");
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
        assertThat(store.findExact(batch.lease())).contains(batch);
    }

    @Test void finalizeReturnsExactCoordinateAndSortedSlotsAfterBindingsAreWritten() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("proof", 1, "proof-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token", "password");
        store.stage(batch);

        FinalizedSecretSlots proof = store.finalizeActivation(batch, List.of(
                activation("token", "proof-token", "active-token"),
                activation("password", "proof-token", "active-password")));

        assertThat(proof.coordinate()).isEqualTo(batch.lease().coordinate());
        assertThat(proof.slots()).containsExactly("password", "token");
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isPresent();
        assertThat(store.findExact(batch.lease())).isEmpty();
    }

    @Test void prepareIsReadOnlyAndCommitReturnsThePreparedProof() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("prepare", 1, "prepare-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token", "password");
        List<ActivatedSecretSlot> outputs = List.of(activation("token", "prepare-token", "active-token"),
                activation("password", "prepare-token", "active-password"));
        store.stage(batch);

        FinalizedSecretSlots prepared = store.prepareFinalization(batch, outputs);

        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isEmpty();
        assertThat(store.commitBindings(batch, outputs)).isEqualTo(prepared);
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
        assertThat(store.findExact(batch.lease())).isEmpty();
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
        FinalizedSecretSlots first = store.commitBindings(batch, outputs);
        FinalizedSecretSlots replay = store.commitBindings(batch, outputs);
        assertThat(replay).isEqualTo(first);
        assertThat(replay.coordinate()).isEqualTo(batch.lease().coordinate());
        assertThat(replay.slots()).containsExactly("token");
        assertThatThrownBy(() -> store.commitBindings(batch,
                List.of(activation("token", "replay-token", "different"))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    @Test void keepExistingCopiesLocatorAndBindsNewCommand() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("keep", 1, "keep-token", NOW.plusSeconds(60));
        CommandLease oldLease = leaseAtRevision("old-command", 1, "old-token", NOW.plusSeconds(60), 2);
        PendingSecretBatch old = batchForRevision(oldLease, 2, "token");
        store.stage(old);
        store.commitBindings(old, List.of(activation("token", "old-token", "old-active")));
        PendingSecretBatch batch = new PendingSecretBatch(new PendingSecretLease(lease, coordinate(lease, 3), lease.expectedRevision()),
                List.of(new PendingSecretOperation.Retained("token", coordinate(lease, 2))));
        store.stage(batch);
        FinalizedSecretSlots proof = store.finalizeActivation(batch, List.of());
        assertThat(proof.coordinate()).isEqualTo(batch.lease().coordinate());
        assertThat(proof.slots()).containsExactly("token");
        assertThat(store.findActive(batch.lease().coordinate(), "token")).contains(
                new ActiveSecretBinding("provider:one", "old-active", "keep"));
    }

    @Test void keepOnlyBatchRejectsProviderOutputAndAbortAfterCommit() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease oldLease = leaseAtRevision("keep-old", 1, "old-token", NOW.plusSeconds(60), 2);
        PendingSecretBatch old = batchForRevision(oldLease, 2, "token");
        store.stage(old);
        store.commitBindings(old, List.of(activation("token", "old-token", "old-active")));
        CommandLease lease = lease("keep-negative", 1, "keep-negative-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = new PendingSecretBatch(new PendingSecretLease(lease, coordinate(lease, 3), lease.expectedRevision()),
                List.of(new PendingSecretOperation.Retained("token", coordinate(lease, 2))));
        store.stage(batch);
        assertThatThrownBy(() -> store.commitBindings(batch,
                List.of(activation("token", "keep-negative-token", "unexpected"))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
        store.commitBindings(batch, List.of());
        assertThatThrownBy(() -> store.markAbortRequired(batch.lease()))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.RECOVERY_STATE);
    }

    @Test void createAndWrongCasCannotUseKeepExisting() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease create = leaseAtRevision("create", 1, "create-token", NOW.plusSeconds(60), 1);
        PendingSecretBatch createKeep = new PendingSecretBatch(new PendingSecretLease(create, coordinate(create, 1), create.expectedRevision()),
                List.of(new PendingSecretOperation.Retained("token",
                        new ConnectionRevisionCoordinate(SCOPE, "connection", 1))));
        assertThatThrownBy(() -> store.stage(createKeep)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
        CommandKey wrongKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                "connection", "idempotency-wrong-cas");
        CommandLease wrongCas = new CommandLease("wrong-cas", 1, "wrong-cas-token", wrongKey,
                "fingerprint-wrong-cas", NOW.plusSeconds(60), ExpectedRevision.match(1));
        PendingSecretBatch wrongBatch = batchForRevision(wrongCas, 3, "token");
        assertThatThrownBy(() -> store.stage(wrongBatch)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    @Test void staleLeaseCannotReadOrMutateExactAttempt() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = lease("fence", 1, "winner", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "token");
        store.stage(batch);
        CommandLease stale = lease("fence", 2, "stale", NOW.plusSeconds(60));
        assertThat(store.findExact(new PendingSecretLease(stale, batch.lease().coordinate(), stale.expectedRevision()))).isEmpty();
        assertThatThrownBy(() -> store.markAbortRequired(new PendingSecretLease(stale, batch.lease().coordinate(), stale.expectedRevision())))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.LEASE_FENCED);
    }

    @Test void exactAttemptCannotBeStagedAtAnotherCoordinate() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease first = leaseTarget("global", 1, "global-token", "connection-a", 3);
        store.stage(batchForRevision(first, 3, "token"));
        CommandLease sameAttempt = leaseTarget("global", 1, "global-token", "connection-b", 3);
        assertThatThrownBy(() -> store.stage(batchForRevision(sameAttempt, 3, "token")))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
        CommandLease retryWrongAuthority = leaseTarget("global", 2, "retry-token", "connection-b", 3);
        assertThatThrownBy(() -> store.stage(batchForRevision(retryWrongAuthority, 3, "token")))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
        store.markAbortRequired(batchForRevision(first, 3, "token").lease());
        store.completeAbort(store.claimRecoveryDue(1).getFirst());
        CommandLease retry = leaseTarget("global", 2, "retry-token", "connection-a", 3);
        store.stage(batchForRevision(retry, 3, "token"));
    }

    @Test void nestedResourceCommandUsesExplicitChildCreateCas() {
        PendingSecretStore store = newStore(fixedClock());
        CommandKey outerKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE,
                "resource", "idempotency-nested");
        CommandLease outer = new CommandLease("nested", 1, "nested-token", outerKey,
                "fingerprint-nested", NOW.plusSeconds(60), ExpectedRevision.match(7));
        ConnectionRevisionCoordinate child = new ConnectionRevisionCoordinate(SCOPE, "connection", 1);
        SecretOperationContext context = new SecretOperationContext(SCOPE, "actor", "connection-save",
                "connection", 1, "nested", 1, "nested-token", "token");
        PreparedExternalSecret prepared = new PreparedExternalSecret("provider:one", "nested-token",
                "nested-opaque", NOW.plusSeconds(60), context);
        PendingSecretBatch batch = new PendingSecretBatch(new PendingSecretLease(outer, child,
                ExpectedRevision.create()), List.of(new PendingSecretOperation.Prepared("token",
                SecretSourceMode.VALUE, prepared)));
        store.stage(batch);
        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(store.findExact(new PendingSecretLease(outer, child, ExpectedRevision.match(1)))).isEmpty();
        store.commitBindings(batch, List.of(activation("token", "nested-token", "nested-active")));
        assertThat(store.findActive(child, "token")).isPresent();

        CommandLease childMatch = new CommandLease("nested-match", 1, "nested-match-token", outerKey,
                "fingerprint-nested-match", NOW.plusSeconds(60), ExpectedRevision.match(7));
        SecretOperationContext matchContext = new SecretOperationContext(SCOPE, "actor", "connection-save",
                "connection", 2, "nested-match", 1, "nested-match-token", "token");
        PendingSecretBatch invalid = new PendingSecretBatch(new PendingSecretLease(childMatch,
                new ConnectionRevisionCoordinate(SCOPE, "connection", 2), ExpectedRevision.match(1)),
                List.of(new PendingSecretOperation.Prepared("token", SecretSourceMode.VALUE,
                        new PreparedExternalSecret("provider:one", "nested-match-token", "opaque-match",
                                NOW.plusSeconds(60), matchContext))));
        assertThatThrownBy(() -> store.stage(invalid)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    @Test void standaloneConnectionTargetMustMatchChildCoordinate() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease lease = leaseTarget("standalone-mismatch", 1, "standalone-token", "resource", 3);
        ConnectionRevisionCoordinate coordinate = new ConnectionRevisionCoordinate(SCOPE, "connection", 3);
        SecretOperationContext context = new SecretOperationContext(SCOPE, "actor", "connection-save",
                "connection", 3, "standalone-mismatch", 1, "standalone-token", "token");
        PendingSecretBatch batch = new PendingSecretBatch(new PendingSecretLease(lease, coordinate,
                ExpectedRevision.match(2)), List.of(new PendingSecretOperation.Prepared("token",
                SecretSourceMode.VALUE, new PreparedExternalSecret("provider:one", "standalone-token",
                        "standalone-opaque", NOW.plusSeconds(60), context))));
        assertThatThrownBy(() -> store.stage(batch)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    @Test void expiredLeaseIsRecoverableButLivePendingIsNotDue() {
        MutableClock clock = new MutableClock(NOW);
        PendingSecretStore store = newStore(clock);
        PendingSecretBatch live = batch(lease("live", 1, "live-token", NOW.plusSeconds(10)), "token");
        store.stage(live);
        assertThat(store.claimRecoveryDue(10)).isEmpty();
        clock.advanceSeconds(11);
        SecretAbortCandidate candidate = store.claimRecoveryDue(10).getFirst();
        store.completeAbort(candidate);
        store.completeAbort(candidate);
        assertThat(store.findExact(live.lease())).isEmpty();
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
        SecretAbortCandidate candidate = store.claimRecoveryDue(1).getFirst();
        store.completeAbort(candidate);
        store.completeAbort(candidate);
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isEmpty();
    }

    @Test void providerExpiryIsIndependentAndEffectiveDeadlineIsTheEarlierExpiry() {
        MutableClock clock = new MutableClock(NOW);
        PendingSecretStore store = newStore(clock);
        CommandLease lease = lease("provider-expiry", 1, "provider-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batchWithProviderExpiry(lease, NOW.plusSeconds(5));
        store.stage(batch);
        clock.advanceSeconds(6);
        SecretAbortCandidate candidate = store.claimRecoveryDue(1).getFirst();
        store.completeAbort(candidate);
        assertThat(store.findExact(batch.lease())).isEmpty();
    }

    @Test void abortCandidateMustBeExactAndOnlyAbortRequired() {
        PendingSecretStore store = newStore(fixedClock());
        PendingSecretBatch batch = batch(lease("candidate", 1, "candidate-token", NOW.plusSeconds(60)), "token");
        store.stage(batch);
        SecretAbortCandidate notMarked = new SecretAbortCandidate(batch);
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
        assertThat(store.claimRecoveryDue(2)).extracting(candidate -> candidate.batch().lease().commandLease().commandId())
                .containsExactly("a", "b");
    }

    @Test void boundedRecoveryClaimsWholeMultiSlotBatchInStableOrder() {
        MutableClock clock = new MutableClock(NOW);
        PendingSecretStore store = newStore(clock);
        CommandLease first = lease("a-multi", 1, "a-token", NOW.plusSeconds(5));
        CommandLease second = lease("b-single", 1, "b-token", NOW.plusSeconds(5));
        PendingSecretBatch multi = batch(first, "password", "token");
        PendingSecretBatch single = batch(second, "token");
        store.stage(multi);
        store.stage(single);
        clock.advanceSeconds(6);
        List<SecretAbortCandidate> firstClaim = store.claimRecoveryDue(1);
        assertThat(firstClaim).singleElement().satisfies(candidate ->
                assertThat(candidate.batch().operations()).hasSize(2));
        store.completeAbort(firstClaim.getFirst());
        assertThat(store.claimRecoveryDue(1)).singleElement()
                .extracting(candidate -> candidate.batch().lease().commandLease().commandId())
                .isEqualTo("b-single");
    }

    @Test void recoveryClaimIsExclusiveAndExpiredClaimIsFencedAfterReclaim() {
        MutableClock clock = new MutableClock(NOW);
        PendingSecretStore store = newStore(clock);
        PendingSecretBatch batch = batch(lease("claim", 1, "claim-token", NOW.plusSeconds(5)), "token", "password");
        store.stage(batch);
        clock.advanceSeconds(6);

        SecretAbortCandidate first = store.claimRecoveryDue(10).getFirst();
        assertThat(store.claimRecoveryDue(10)).isEmpty();
        clock.advanceSeconds(31);
        SecretAbortCandidate reclaimed = store.claimRecoveryDue(10).getFirst();
        assertThat(reclaimed.recoveryClaimToken()).isNotEqualTo(first.recoveryClaimToken());
        assertThatThrownBy(() -> store.completeAbort(first)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.RECOVERY_STATE);
        store.completeAbort(reclaimed);
        store.completeAbort(reclaimed);
        assertThat(store.findExact(batch.lease())).isEmpty();
    }

    @Test void twoAttemptsSameRevisionDoNotCrossAbort() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease first = lease("same-command", 1, "first", NOW.plusSeconds(60));
        CommandLease second = lease("same-command", 2, "second", NOW.plusSeconds(60));
        PendingSecretBatch firstBatch = batch(first, "token");
        PendingSecretBatch secondBatch = batch(second, "token");
        store.stage(firstBatch);
        store.stage(secondBatch);
        store.markAbortRequired(firstBatch.lease());
        store.completeAbort(store.claimRecoveryDue(10).getFirst());
        store.commitBindings(secondBatch, List.of(activation("token", "second", "second-active")));
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

        FinalizedSecretSlots proof = new FinalizedSecretSlots(batch.lease(), Set.of("token"));
        String proofJson = new ObjectMapper().writeValueAsString(proof);
        assertThat(proofJson).contains("connection", "token")
                .doesNotContain("secret-attempt", "lease-id", "opaque-locator", "provider:one");
        assertThat(proof.toString()).doesNotContain("secret-attempt", "lease-id", "opaque-locator", "provider:one");
    }

    @Test void finalizedProofRejectsUnknownSlotsAndDefensivelySortsInput() {
        Set<String> slots = new java.util.HashSet<>(Set.of("token", "password"));
        CommandLease lease = lease("proof-shape", 1, "proof-shape-token", NOW.plusSeconds(60));
        FinalizedSecretSlots proof = new FinalizedSecretSlots(new PendingSecretLease(lease,
                coordinate(lease, 3), lease.expectedRevision()), slots);
        slots.clear();
        assertThat(proof.slots()).containsExactly("password", "token");
        assertThatThrownBy(() -> new FinalizedSecretSlots(proof.lease(), Set.of("unknown")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void proofIdentityIncludesTheFullAttemptLeaseAndCannotBeCreatedByJackson() throws Exception {
        CommandLease first = lease("proof-identity", 1, "attempt-one", NOW.plusSeconds(60));
        CommandLease second = lease("proof-identity", 2, "attempt-two", NOW.plusSeconds(60));
        FinalizedSecretSlots firstProof = new FinalizedSecretSlots(new PendingSecretLease(first,
                coordinate(first, 3), first.expectedRevision()), Set.of("token"));
        FinalizedSecretSlots secondProof = new FinalizedSecretSlots(new PendingSecretLease(second,
                coordinate(second, 3), second.expectedRevision()), Set.of("token"));
        assertThat(secondProof).isNotEqualTo(firstProof);
        assertThat(FinalizedSecretSlots.class.getDeclaredConstructors())
                .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()));
        String json = new ObjectMapper().writeValueAsString(firstProof);
        assertThat(json).doesNotContain("attempt-one", "attempt-two", "fingerprint");
        assertThatThrownBy(() -> new ObjectMapper().readValue(json, FinalizedSecretSlots.class))
                .isInstanceOf(Exception.class);
    }

    @Test void malformedRecoveryLimitIsStableIntegrity() {
        PendingSecretStore store = newStore(fixedClock());
        assertThatThrownBy(() -> store.claimRecoveryDue(0)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    protected Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    private static final class MutableClock extends Clock {
        private Instant current;
        private MutableClock(Instant current) { this.current = current; }
        private void advanceSeconds(long seconds) { current = current.plusSeconds(seconds); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }

    protected PendingSecretBatch batch(CommandLease lease, String... slots) {
        return batchForRevision(lease, 3, slots);
    }

    protected PendingSecretBatch batchForRevision(CommandLease lease, long revision, String... slots) {
        List<PendingSecretOperation> operations = java.util.Arrays.stream(slots)
                .map(slot -> new PendingSecretOperation.Prepared(slot, SecretSourceMode.VALUE,
                        prepared(lease, slot, revision))).map(operation -> (PendingSecretOperation) operation).toList();
        return new PendingSecretBatch(new PendingSecretLease(lease, coordinate(lease, revision), lease.expectedRevision()), operations);
    }

    protected PendingSecretBatch batchWithLocator(CommandLease lease, String slot, String locator) {
        PreparedExternalSecret value = prepared(lease, slot, 3);
        value = new PreparedExternalSecret(value.providerId(), value.leaseId(), locator,
                value.leaseUntil(), value.context());
        return new PendingSecretBatch(new PendingSecretLease(lease, coordinate(lease, 3), lease.expectedRevision()),
                List.of(new PendingSecretOperation.Prepared(slot, SecretSourceMode.VALUE, value)));
    }

    protected PendingSecretBatch batchWithProviderExpiry(CommandLease lease, Instant providerExpiry) {
        PreparedExternalSecret value = prepared(lease, "token", 3);
        value = new PreparedExternalSecret(value.providerId(), value.leaseId(), value.opaqueLocator(),
                providerExpiry, value.context());
        return new PendingSecretBatch(new PendingSecretLease(lease, coordinate(lease, 3), lease.expectedRevision()),
                List.of(new PendingSecretOperation.Prepared("token", SecretSourceMode.VALUE, value)));
    }

    protected PreparedExternalSecret prepared(CommandLease lease, String slot, long revision) {
        SecretOperationContext context = new SecretOperationContext(lease.key().scope(), lease.key().actorId(),
                "connection-save", lease.key().targetId(), revision, lease.commandId(), lease.attemptNo(),
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
        return leaseAtRevision(commandId, attempt, token, until, 3);
    }

    protected CommandLease leaseAtRevision(String commandId, int attempt, String token, Instant until, long revision) {
        return leaseTarget(commandId, attempt, token, "connection", revision, until);
    }

    protected CommandLease leaseTarget(String commandId, int attempt, String token, String target, long revision) {
        return leaseTarget(commandId, attempt, token, target, revision, NOW.plusSeconds(60));
    }

    private CommandLease leaseTarget(String commandId, int attempt, String token, String target, long revision,
                                     Instant until) {
        CommandKey key = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                target, "idempotency-" + commandId);
        ExpectedRevision expected = revision == 1 ? ExpectedRevision.create() : ExpectedRevision.match(revision - 1);
        return new CommandLease(commandId, attempt, token, key, "fingerprint-" + commandId, until, expected);
    }
}
