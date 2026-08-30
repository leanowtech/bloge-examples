package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.SecretOperationContext;
import org.junit.jupiter.api.TestInstance;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Concrete reference fixture for the reusable pending-secret contract. */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
final class InMemoryPendingSecretStoreContractTest extends PendingSecretStoreContractTest {
    @Override protected PendingSecretStore newStore(Clock clock) {
        return new InMemoryPendingSecretStore(clock);
    }

    @org.junit.jupiter.api.Test
    void staleAttemptAndCompetingCommandCannotOverwriteWinningBinding() {
        PendingSecretStore store = newStore(fixedClock());
        CommandLease first = lease("latest-attempt", 1, "first-token", NOW.plusSeconds(60));
        CommandLease latest = lease("latest-attempt", 2, "latest-token", NOW.plusSeconds(60));
        PendingSecretBatch firstBatch = batch(first, "token");
        PendingSecretBatch latestBatch = batch(latest, "token");
        store.stage(firstBatch);
        store.stage(latestBatch);

        FinalizedSecretSlots latestProof = store.commitBindings(latestBatch,
                java.util.List.of(activation("token", "latest-token", "latest-active")));

        assertThatThrownBy(() -> store.commitBindings(firstBatch,
                java.util.List.of(activation("token", "first-token", "stale-active"))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.LEASE_FENCED);
        assertThat(store.findActive(latestBatch.lease().coordinate(), "token")).contains(
                new com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding(
                        "provider:one", "latest-active", "latest-attempt"));
        assertThat(store.commitBindings(latestBatch,
                java.util.List.of(activation("token", "latest-token", "latest-active"))))
                .isEqualTo(latestProof);

        PendingSecretBatch competing = batch(lease("competing", 1, "competing-token", NOW.plusSeconds(60)), "token");
        store.stage(competing);
        assertThatThrownBy(() -> store.commitBindings(competing,
                java.util.List.of(activation("token", "competing-token", "competing-active"))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.LEASE_FENCED);
        assertThat(store.findActive(latestBatch.lease().coordinate(), "token")).contains(
                new com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding(
                        "provider:one", "latest-active", "latest-attempt"));
    }

    @org.junit.jupiter.api.Test
    void higherAttemptWithDriftedNestedOuterCasIsFenced() {
        PendingSecretStore store = newStore(fixedClock());
        CommandKey outerKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE,
                "resource", "idempotency-nested-cas-fence");
        CommandLease first = new CommandLease("nested-cas-fence", 1, "first-token", outerKey,
                "fingerprint-nested-cas-fence", NOW.plusSeconds(60),
                com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.match(7));
        PendingSecretBatch firstBatch = nested(first, ExpectedRevision.create(), 1);
        store.stage(firstBatch);

        CommandLease driftedRetry = new CommandLease("nested-cas-fence", 2, "retry-token", outerKey,
                "fingerprint-nested-cas-fence", NOW.plusSeconds(60),
                com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.match(8));
        PendingSecretBatch driftedBatch = nested(driftedRetry, ExpectedRevision.create(), 1);

        assertThatThrownBy(() -> store.stage(driftedBatch))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.LEASE_FENCED);
    }

    private PendingSecretBatch nested(CommandLease outer, ExpectedRevision childExpected, long revision) {
        SecretOperationContext context = new SecretOperationContext(SCOPE, "actor", "connection-save",
                "connection", revision, outer.commandId(), outer.attemptNo(), outer.attemptToken(), "token");
        PreparedExternalSecret prepared = new PreparedExternalSecret("provider:one", outer.attemptToken(),
                "opaque-nested-cas-fence", outer.leaseUntil(), context);
        return new PendingSecretBatch(new PendingSecretLease(outer,
                new ConnectionRevisionCoordinate(SCOPE, "connection", revision), childExpected),
                java.util.List.of(new PendingSecretOperation.Prepared("token", SecretSourceMode.VALUE, prepared)));
    }
}
