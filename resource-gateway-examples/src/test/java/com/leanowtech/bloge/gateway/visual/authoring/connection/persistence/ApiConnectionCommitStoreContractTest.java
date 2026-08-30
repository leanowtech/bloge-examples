package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.SecretOperationContext;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.ConnectionRevisionCoordinate;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.FinalizedSecretSlots;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.InMemoryPendingSecretStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.PendingSecretBatch;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.PendingSecretLease;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.PendingSecretOperation;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.SecretSourceMode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceSaveReceiptClosure;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the Connection metadata commit seam.
 *
 * <p>The test deliberately exercises only the public stage/commit/fail/read
 * boundary. It uses the in-memory implementation as a deterministic model for
 * the later JDBC implementation; it is not a database certification.</p>
 */
abstract class ApiConnectionCommitStoreContractTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final AuthoringScope OTHER_SCOPE = new AuthoringScope("other", "project", "dev");
    private static final String BASE_URL = "https://customer.example.com";
    private static final String REQUEST_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final Instant TEST_NOW = Instant.now();

    /** Factory seam for exercising this matrix against a future JDBC adapter. */
    protected abstract ApiConnectionCommitStore createStore(Clock clock);

    @Test
    void stageIsInvisibleUntilCommitAndCommittedReadIsDefensive() throws Exception {
        ApiConnectionCommitStore store = newStore();
        CommandLease lease = lease("create-1", 1, "attempt-1", SCOPE, "customer", ExpectedRevision.create());

        StagedApiConnection staged = stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());

        assertThat(staged.view().revision()).isEqualTo(1);
        assertThat(store.findHead(SCOPE, "customer")).isEmpty();
        assertThat(store.findRevision(SCOPE, "customer", 1)).isEmpty();

        StoredApiConnection committed = store.commit(lease);
        assertThat(committed.view().revision()).isEqualTo(1);
        assertThat(committed.view().displayName()).isEqualTo("Customer API");
        assertThat(store.findHead(SCOPE, "customer")).contains(committed);
        assertThat(store.findRevision(SCOPE, "customer", 1)).contains(committed);

        assertThatThrownBy(() -> committed.view().defaults().headers().put("Injected", "no"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.findHead(SCOPE, "customer").orElseThrow().view().defaults().headers())
                .doesNotContainKey("Injected");
        assertThat(new ObjectMapper().writeValueAsString(committed.view()))
                .doesNotContain("vault://", "secret-value");
    }

    @Test
    void createUpdateAndHistoryUseTheAuthorityCas() {
        ApiConnectionCommitStore store = newStore();
        CommandLease create = lease("create-2", 1, "a-2", SCOPE, "customer", ExpectedRevision.create());
        stage(store, create, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(create);

        CommandLease update = lease("update-2", 1, "u-2", SCOPE, "customer", ExpectedRevision.match(1));
        ApiConnectionCommand renamed = new ApiConnectionCommand("Customer v2", BASE_URL,
                ApiConnectionCommand.Auth.none(), new ApiConnectionCommand.Defaults(5000, Map.of()));
        stage(store, update, "customer", ExpectedRevision.match(1), renamed);
        StoredApiConnection head = store.commit(update);

        assertThat(head.view().revision()).isEqualTo(2);
        assertThat(head.view().displayName()).isEqualTo("Customer v2");
        assertThat(store.findRevision(SCOPE, "customer", 1).orElseThrow().view().displayName())
                .isEqualTo("Customer API");
        assertThat(store.findRevision(SCOPE, "customer", 2)).contains(head);
    }

    @Test
    void oldStrongEtagResolvesAfterTheHeadAdvances() {
        ApiConnectionCommitStore store = newStore();
        CommandLease create = lease("etag-history", 1, "etag-history-create", SCOPE, "customer",
                ExpectedRevision.create());
        StagedApiConnection staged = stage(store, create, "customer", ExpectedRevision.create(), noneCommand());
        assertThat(store.findRevisionByStrongEtag(SCOPE, "customer", staged.strongEtag())).isEmpty();
        StoredApiConnection first = store.commit(create);

        CommandLease update = lease("etag-history-update", 1, "etag-history-update-token", SCOPE,
                "customer", ExpectedRevision.match(1));
        stage(store, update, "customer", ExpectedRevision.match(1), renamedCommand("Customer v2"));
        StoredApiConnection second = store.commit(update);

        assertThat(second.view().revision()).isEqualTo(2);
        assertThat(store.findRevisionByStrongEtag(SCOPE, "customer", first.strongEtag()))
                .contains(first);
        assertThat(store.findRevisionByStrongEtag(SCOPE, "customer", second.strongEtag()))
                .contains(second);
        assertThat(store.findRevisionByStrongEtag(OTHER_SCOPE, "customer", first.strongEtag()))
                .isEmpty();
        assertThat(store.findRevisionByStrongEtag(SCOPE, "other", first.strongEtag())).isEmpty();
        assertThat(store.findRevisionByStrongEtag(SCOPE, "customer", "\"unknown\"")).isEmpty();

        CommandLease stale = lease("etag-history-stale", 1, "etag-history-stale-token", SCOPE,
                "customer", ExpectedRevision.match(1));
        assertThatThrownBy(() -> stage(store, stale, "customer", ExpectedRevision.match(1),
                renamedCommand("Stale writer"))).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
    }

    @Test
    void strongEtagLookupRejectsWeakAndListValidators() {
        ApiConnectionCommitStore store = newStore();
        for (String invalid : new String[]{"W/\"etag\"", "\"W/etag\"", "\"etag\", \"other\"", "etag"}) {
            assertThatThrownBy(() -> store.findRevisionByStrongEtag(SCOPE, "customer", invalid))
                    .isInstanceOf(ApiConnectionCommitStoreException.class)
                    .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        }
    }

    @Test
    void sameAttemptReentryReturnsTheSameStageWithoutDuplicatingIt() {
        ApiConnectionCommitStore store = newStore();
        CommandLease lease = lease("reentry", 1, "reentry-token", SCOPE, "customer", ExpectedRevision.create());

        StagedApiConnection first = stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());
        StagedApiConnection second = stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());

        assertThat(second).isEqualTo(first);
        assertThat(store.commit(lease).strongEtag()).isEqualTo(first.strongEtag());
    }

    @Test
    void sameAttemptReentryWithDifferentConnectionCasIsIntegrityFailure() {
        ApiConnectionCommitStore store = newStore();
        CommandLease lease = lease("reentry-cas", 1, "reentry-cas-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());
        assertThatThrownBy(() -> stage(store, lease, "customer", ExpectedRevision.match(1), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void twoCommandsMayStageOneLogicalRevisionButOnlyOneCanCommit() {
        ApiConnectionCommitStore store = newStore();
        CommandLease firstLease = lease("winner-a", 1, "winner-a-token", SCOPE, "customer", ExpectedRevision.create());
        CommandLease secondLease = lease("winner-b", 1, "winner-b-token", SCOPE, "customer", ExpectedRevision.create());

        stage(store, firstLease, "customer", ExpectedRevision.create(), noneCommand());
        stage(store, secondLease, "customer", ExpectedRevision.create(), noneCommand());
        StoredApiConnection winner = store.commit(firstLease);

        assertThatThrownBy(() -> store.commit(secondLease))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        assertThat(store.findHead(SCOPE, "customer")).contains(winner);
    }

    @Test
    void updateCommandsAreCheckedAgainstTheHeadAgainAtCommit() {
        ApiConnectionCommitStore store = newStore();
        CommandLease base = lease("base", 1, "base-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, base, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(base);
        CommandLease first = lease("update-a", 1, "update-a-token", SCOPE, "customer", ExpectedRevision.match(1));
        CommandLease second = lease("update-b", 1, "update-b-token", SCOPE, "customer", ExpectedRevision.match(1));
        stage(store, first, "customer", ExpectedRevision.match(1), renamedCommand("Customer A"));
        stage(store, second, "customer", ExpectedRevision.match(1), renamedCommand("Customer B"));

        store.commit(first);
        assertThatThrownBy(() -> store.commit(second))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        assertThat(store.findHead(SCOPE, "customer").orElseThrow().view().displayName())
                .isEqualTo("Customer A");
    }

    @Test
    void expiredLeaseCannotStageOrCommitAndFailIsAStaleNoOp() {
        MutableClock clock = new MutableClock(Instant.now());
        Clock zoned = clock.withZone(ZoneId.of("Asia/Singapore"));
        assertThat(zoned.getZone()).isEqualTo(ZoneId.of("Asia/Singapore"));
        assertThat(zoned.instant()).isEqualTo(clock.instant());
        ApiConnectionCommitStore store = newStore(clock);
        CommandLease live = leaseAt(clock, "expiry", 1, "expiry-token", SCOPE, "customer",
                ExpectedRevision.create(), 10);
        stage(store, live, "customer", ExpectedRevision.create(), noneCommand());
        clock.advanceSeconds(20);
        store.fail(live);
        assertThatThrownBy(() -> store.commit(live)).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_EXPIRED);
    }

    @Test
    void newerAttemptTakesOverOldStageAndFencesOldLease() {
        MutableClock clock = new MutableClock(Instant.now());
        ApiConnectionCommitStore store = newStore(clock);
        CommandLease old = leaseAt(clock, "takeover", 1, "old-token", SCOPE, "customer", ExpectedRevision.create(), 10);
        CommandLease current = leaseAt(clock, "takeover", 2, "new-token", SCOPE, "customer",
                ExpectedRevision.create(), 30);
        stage(store, old, "customer", ExpectedRevision.create(), noneCommand());

        assertThatThrownBy(() -> stage(store, current, "customer", ExpectedRevision.create(),
                renamedCommand("Current")))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        clock.advanceSeconds(20);
        StagedApiConnection replacement = stage(store, current, "customer", ExpectedRevision.create(),
                renamedCommand("Current"));
        store.fail(old);

        assertThatThrownBy(() -> store.commit(old))
                .isInstanceOf(ApiConnectionCommitStoreException.class);
        assertThat(store.commit(current)).isEqualTo(new StoredApiConnection(SCOPE, replacement.view(),
                replacement.metadataFingerprint(), replacement.strongEtag(), current.commandId()));
    }

    @Test
    void expiredLeaseRejectsSameOrLowerAttemptAndDifferentCommandTakeover() {
        MutableClock clock = new MutableClock(TEST_NOW);
        ApiConnectionCommitStore store = newStore(clock);
        CommandLease old = leaseAt(clock, "takeover-negative", 2, "old-token", SCOPE, "customer",
                ExpectedRevision.create(), 10);
        stage(store, old, "customer", ExpectedRevision.create(), noneCommand());
        clock.advanceSeconds(2);
        CommandLease lower = leaseWithTarget("takeover-negative", 1, "lower-token", SCOPE, "customer",
                AuthoringEndpoint.API_CONNECTION_SAVE, ExpectedRevision.create());
        CommandLease different = leaseWithTargetAndKey("different-command", 3, "different-token", SCOPE,
                "customer", AuthoringEndpoint.API_CONNECTION_SAVE, ExpectedRevision.create(), "key-takeover-negative");
        assertThatThrownBy(() -> stage(store, lower, "customer", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class).extracting("code")
                .isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        assertThatThrownBy(() -> stage(store, different, "customer", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class).extracting("code")
                .isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
    }

    @Test
    void failRemovesOnlyTheExactLiveStage() {
        ApiConnectionCommitStore store = newStore();
        CommandLease first = lease("fail-1", 1, "fail-1-token", SCOPE, "customer", ExpectedRevision.create());
        CommandLease second = lease("fail-2", 1, "fail-2-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, first, "customer", ExpectedRevision.create(), noneCommand());
        stage(store, second, "customer", ExpectedRevision.create(), noneCommand());

        store.fail(first);
        assertThatThrownBy(() -> store.commit(first))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.STAGE_MISSING);
        assertThat(store.commit(second).view().revision()).isEqualTo(1);
    }

    @Test
    void keepExistingUsesTheOpaqueBindingButNeverReturnsItInTheView() throws Exception {
        ApiConnectionCommitStore store = newStore();
        PreparedSecretBinding prepared = new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://team/customer-token"));
        ApiConnectionCommand bearer = new ApiConnectionCommand("Customer API", BASE_URL,
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("one-time-secret")),
                new ApiConnectionCommand.Defaults(5000, Map.of()));
        CommandLease secretCreate = lease("secret-create", 1, "secret-token", SCOPE, "customer",
                ExpectedRevision.create());
        StagedApiConnection staged = stage(store, secretCreate, "customer", ExpectedRevision.create(), bearer, prepared);
        assertThat(staged.view().auth().configured()).isTrue();
        assertThatThrownBy(() -> store.commit(secretCreate))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        store.fail(secretCreate);
    }

    @Test
    void secretCommitRequiresAnExactLocatorFreeFinalizedProof() {
        ApiConnectionCommitStore store = newStore();
        PreparedSecretBinding prepared = new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://team/customer-token"));
        CommandLease lease = lease("secret-activation", 1, "secret-activation-token", SCOPE, "customer",
                ExpectedRevision.create());
        stage(store, lease, "customer", ExpectedRevision.create(),
                new ApiConnectionCommand("Secret API", BASE_URL,
                        ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("one-time-secret")),
                        new ApiConnectionCommand.Defaults(5000, Map.of())), prepared);

        assertThatThrownBy(() -> store.commit(lease))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        FinalizedSecretSlots wrongCoordinate = proofFor(lease, new ConnectionRevisionCoordinate(SCOPE, "other", 1),
                ExpectedRevision.create(), "token");
        assertThatThrownBy(() -> store.commit(lease, wrongCoordinate))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        FinalizedSecretSlots wrongSlot = proofFor(lease, new ConnectionRevisionCoordinate(SCOPE, "customer", 1),
                ExpectedRevision.create(), "password");
        assertThatThrownBy(() -> store.commit(lease, wrongSlot))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        FinalizedSecretSlots proof = proofFor(lease, new ConnectionRevisionCoordinate(SCOPE, "customer", 1),
                ExpectedRevision.create(), "token");
        StoredApiConnection committed = store.commit(lease, proof);
        assertThat(committed.view().auth().configured()).isTrue();
        assertThat(store.findHead(SCOPE, "customer")).contains(committed);
    }

    @Test
    void readsAreScopeExactAndMissingRevisionIsEmpty() {
        ApiConnectionCommitStore store = newStore();
        CommandLease scopeLease = lease("scope", 1, "scope-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, scopeLease, "customer", ExpectedRevision.create(), noneCommand());
        store.commit(scopeLease);

        assertThat(store.findHead(OTHER_SCOPE, "customer")).isEmpty();
        assertThat(store.findRevision(SCOPE, "customer", 0)).isEmpty();
        assertThat(store.findRevision(SCOPE, "customer", 99)).isEmpty();
    }

    @Test
    void errorsAndStringFormsDoNotLeakEndpointOrSecretData() {
        ApiConnectionCommitStore store = newStore();
        CommandLease lease = lease("redaction", 1, "redaction-token", SCOPE, "customer", ExpectedRevision.create());
        StagedApiConnection staged = stage(store, lease, "customer", ExpectedRevision.create(),
                new ApiConnectionCommand("Customer API",
                BASE_URL, ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("secret-value")),
                new ApiConnectionCommand.Defaults(5000, Map.of())) ,
                new PreparedSecretBinding("token", new SecretReference(SCOPE, "vault://redacted/token")));

        assertThat(staged.toString()).doesNotContain(BASE_URL, "secret-value", "vault://redacted/token");
        assertThatThrownBy(() -> stage(store, lease, "different", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .satisfies(error -> assertThat(error.toString())
                        .doesNotContain(BASE_URL, "secret-value", "vault://redacted/token"));
    }

    @Test
    void stageRejectsACommandThatDoesNotMatchTheLeaseTarget() {
        ApiConnectionCommitStore store = newStore();
        CommandLease lease = leaseWithTarget("target", 1, "target-token", SCOPE, "other",
                AuthoringEndpoint.API_CONNECTION_SAVE, ExpectedRevision.create());

        assertThatThrownBy(() -> stage(store, lease, "customer", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);

        CommandLease expectedMismatch = lease("target-mismatch", 1, "target-mismatch-token", SCOPE,
                "customer", ExpectedRevision.match(1));
        assertThatThrownBy(() -> stage(store, expectedMismatch, "customer", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);

        CommandLease resourceLease = leaseWithTarget("resource-cas", 1, "resource-cas-token", SCOPE, "profile",
                AuthoringEndpoint.API_RESOURCE_SAVE, ExpectedRevision.match(1));
        assertThatThrownBy(() -> stage(store, resourceLease, "customer", ExpectedRevision.match(1), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void connectionCasIsIndependentFromCompositeLeaseExpectation() {
        ApiConnectionCommitStore store = newStore();
        CommandLease resourceLease = leaseWithTarget("composite", 1, "composite-token", SCOPE, "profile",
                AuthoringEndpoint.API_RESOURCE_SAVE, ExpectedRevision.match(999));
        stage(store, resourceLease, "customer", ExpectedRevision.create(), noneCommand());
        assertThat(commitChild(store, resourceLease).view().revision()).isEqualTo(1);
    }

    @Test
    void nestedCommitMustUseExplicitChildSeamAndDoesNotPublishAConnectionReceipt() {
        ApiConnectionCommitStore store = newStore();
        CommandLease resourceLease = leaseWithTarget("nested-child", 1, "nested-child-token", SCOPE,
                "profile", AuthoringEndpoint.API_RESOURCE_SAVE, ExpectedRevision.match(7));
        stage(store, resourceLease, "customer", ExpectedRevision.create(), noneCommand());

        assertThatThrownBy(() -> store.commit(resourceLease))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);
        StoredApiConnection child = commitChild(store, resourceLease);
        assertThat(child.view().revision()).isEqualTo(1);
        if (childIsVisibleAfterOuterCommit()) {
            assertThat(store.findHead(SCOPE, "customer")).contains(child);
        } else {
            assertThat(store.findHead(SCOPE, "customer")).isEmpty();
        }
    }

    @Test
    void nestedChildReservesItsConnectionCoordinateBeforeOuterPublication() {
        ApiConnectionCommitStore store = newStore();
        CommandLease childLease = leaseWithTarget("nested-reservation", 1, "nested-reservation-token", SCOPE,
                "profile", AuthoringEndpoint.API_RESOURCE_SAVE, ExpectedRevision.match(7));
        stage(store, childLease, "customer", ExpectedRevision.create(), noneCommand());
        StoredApiConnection child = commitChild(store, childLease);
        if (childIsVisibleAfterOuterCommit()) {
            assertThat(store.findHead(SCOPE, "customer")).contains(child);
            assertThatThrownBy(() -> stage(store, lease("nested-reservation-competing", 1,
                    "nested-reservation-competing-token", SCOPE, "customer", ExpectedRevision.create()),
                    "customer", ExpectedRevision.create(), renamedCommand("Competing")))
                    .isInstanceOf(ApiConnectionCommitStoreException.class)
                    .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
            return;
        }

        CommandLease competing = lease("nested-reservation-competing", 1, "nested-reservation-competing-token",
                SCOPE, "customer", ExpectedRevision.create());
        boolean stagedCompeting = true;
        try {
            stage(store, competing, "customer", ExpectedRevision.create(), renamedCommand("Competing"));
        } catch (ApiConnectionCommitStoreException ex) {
            stagedCompeting = false;
            assertThat(ex.code()).isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        }
        if (stagedCompeting) {
            assertThatThrownBy(() -> store.commit(competing))
                    .isInstanceOf(ApiConnectionCommitStoreException.class)
                    .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        }

        store.failChild(childLease);
        if (!stagedCompeting) {
            stage(store, competing, "customer", ExpectedRevision.create(), renamedCommand("Competing"));
        }
        assertThat(store.commit(competing).view().displayName()).isEqualTo("Competing");
        assertThat(child.view().revision()).isEqualTo(1);
    }

    @Test
    void childPublicationBindsFirstReplayAndFailureToTheExactOuterAuthority() {
        ApiConnectionCommitStore store = newStore();
        CommandLease resourceLease = leaseWithTarget("child-publication", 1, "child-publication-token", SCOPE,
                "profile", AuthoringEndpoint.API_RESOURCE_SAVE, ExpectedRevision.match(7));
        stage(store, resourceLease, "customer", ExpectedRevision.create(), noneCommand());
        StoredApiConnection child = commitChild(store, resourceLease);
        CommandReceipt receipt = resourceReceipt("profile", "customer", child.view().revision());
        prepareOuterReceipt(store, resourceLease, receipt, child);

        assertThat(store.publishChild(resourceLease, receipt)).isEqualTo(child);
        assertThat(store.publishChild(resourceLease, receipt)).isEqualTo(child);

        ObjectNode alteredBody = (ObjectNode) receipt.body();
        alteredBody.with("projections").put("operator", "PENDING");
        CommandReceipt alteredReceipt = new CommandReceipt(receipt.schemaVersion(), alteredBody,
                com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints.of(alteredBody),
                receipt.strongEtag());
        assertThatThrownBy(() -> store.publishChild(resourceLease, alteredReceipt))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.INTEGRITY);

        CommandLease alteredLease = new CommandLease(resourceLease.commandId(), resourceLease.attemptNo(),
                resourceLease.attemptToken(), resourceLease.key(), resourceLease.requestFingerprint(),
                resourceLease.leaseUntil().plusSeconds(1), resourceLease.expectedRevision());
        assertThatThrownBy(() -> store.publishChild(alteredLease, receipt))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);

        store.failChild(resourceLease);
        assertThat(store.findHead(SCOPE, "customer")).contains(child);
    }

    @Test
    void higherAttemptCannotTakeOverWithAChangedRequestFingerprint() {
        MutableClock clock = new MutableClock(TEST_NOW);
        ApiConnectionCommitStore store = newStore(clock);
        CommandLease old = leaseAt(clock, "fingerprint-takeover", 1, "old-token", SCOPE, "customer",
                ExpectedRevision.create(), 10);
        stage(store, old, "customer", ExpectedRevision.create(), noneCommand());
        clock.advanceSeconds(20);
        CommandLease changed = new CommandLease("fingerprint-takeover", 2, "new-token",
                old.key(), "sha256:" + "b".repeat(64), clock.instant().plusSeconds(30),
                ExpectedRevision.create());
        assertThatThrownBy(() -> stage(store, changed, "customer", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
    }

    @Test
    void failedHigherAttemptCannotTakeOverWithAChangedRequestFingerprint() {
        ApiConnectionCommitStore store = newStore();
        CommandLease failed = lease("failed-fingerprint-takeover", 1, "failed-token", SCOPE, "customer",
                ExpectedRevision.create());
        stage(store, failed, "customer", ExpectedRevision.create(), noneCommand());
        store.fail(failed);
        CommandLease changed = new CommandLease(failed.commandId(), 2, "replacement-token", failed.key(),
                "sha256:" + "b".repeat(64), failed.leaseUntil(), ExpectedRevision.create());

        assertThatThrownBy(() -> stage(store, changed, "customer", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
    }

    @Test
    void alteredLeaseFieldsAreFencedEvenWhenTokenCoordinatesMatch() {
        ApiConnectionCommitStore store = newStore();
        CommandLease lease = lease("exact", 1, "exact-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());
        CommandLease altered = new CommandLease(lease.commandId(), lease.attemptNo(), lease.attemptToken(),
                lease.key(), "sha256:" + "b".repeat(64), lease.leaseUntil(), lease.expectedRevision());
        assertThatThrownBy(() -> store.commit(altered)).isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
        assertThat(store.commit(lease).view().revision()).isEqualTo(1);
    }

    @Test
    void exactFailedLeaseCannotStageAgain() {
        ApiConnectionCommitStore store = newStore();
        CommandLease lease = lease("failed", 1, "failed-token", SCOPE, "customer", ExpectedRevision.create());
        stage(store, lease, "customer", ExpectedRevision.create(), noneCommand());
        store.fail(lease);
        assertThatThrownBy(() -> stage(store, lease, "customer", ExpectedRevision.create(), noneCommand()))
                .isInstanceOf(ApiConnectionCommitStoreException.class)
                .extracting("code").isEqualTo(ApiConnectionCommitStoreException.Code.LEASE_FENCED);
    }

    @Test
    void resourceReceiptClosureRequiresTheCompleteCanonicalBody() {
        ObjectMapper mapper = new ObjectMapper();
        var body = mapper.createObjectNode().put("schemaVersion", ApiResourceSaveReceiptClosure.SCHEMA_VERSION);
        body.putObject("connection").put("connectionId", "customer").put("revision", 1);
        body.putObject("resource").put("kind", "API_RESOURCE").put("resourceId", "profile")
                .put("revision", 1).put("fingerprint", "sha256:" + "b".repeat(64));
        body.putObject("projections").put("descriptor", "READY")
                .put("designContract", "READY").put("operator", "READY");
        CommandReceipt receipt = new CommandReceipt(ApiResourceSaveReceiptClosure.SCHEMA_VERSION, body,
                com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints.of(body),
                "\"outer\"");
        ApiResourceSaveReceiptClosure.require(receipt, "profile", "customer", 1);

        var fixture = body.deepCopy();
        fixture.putObject("defaultFixture").put("fixtureSetId", "fixtures").put("revision", 1)
                .put("fingerprint", "sha256:" + "c".repeat(64)).putArray("cases")
                .addObject().put("exampleName", "happy").put("caseId", "case-1");
        assertThatThrownBy(() -> ApiResourceSaveReceiptClosure.require(new CommandReceipt(
                ApiResourceSaveReceiptClosure.SCHEMA_VERSION, fixture,
                com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints.of(fixture),
                "\"outer\""), "profile", "customer", 1)).isInstanceOf(IllegalArgumentException.class);

        var hugeRevision = body.deepCopy();
        hugeRevision.with("connection").put("revision", new java.math.BigInteger("9223372036854775808"));
        assertThatThrownBy(() -> ApiResourceSaveReceiptClosure.require(new CommandReceipt(
                ApiResourceSaveReceiptClosure.SCHEMA_VERSION, hugeRevision,
                com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints.of(hugeRevision),
                "\"outer\""), "profile", "customer", 1)).isInstanceOf(IllegalArgumentException.class);

        var extra = body.deepCopy().put("unexpected", true);
        assertThatThrownBy(() -> ApiResourceSaveReceiptClosure.require(new CommandReceipt(
                ApiResourceSaveReceiptClosure.SCHEMA_VERSION, extra,
                com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints.of(extra),
                "\"outer\""), "profile", "customer", 1)).isInstanceOf(IllegalArgumentException.class);
        var drift = body.deepCopy();
        drift.with("connection").put("connectionId", "other");
        assertThatThrownBy(() -> ApiResourceSaveReceiptClosure.require(new CommandReceipt(
                ApiResourceSaveReceiptClosure.SCHEMA_VERSION, drift,
                com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints.of(drift),
                "\"outer\""), "profile", "customer", 1)).isInstanceOf(IllegalArgumentException.class);
        var notReady = body.deepCopy();
        notReady.with("projections").put("operator", "PENDING");
        assertThatThrownBy(() -> ApiResourceSaveReceiptClosure.require(new CommandReceipt(
                ApiResourceSaveReceiptClosure.SCHEMA_VERSION, notReady,
                com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints.of(notReady),
                "\"outer\""), "profile", "customer", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorsHaveOnlyCodeDerivedMessages() {
        ApiConnectionCommitStoreException error = new ApiConnectionCommitStoreException(
                ApiConnectionCommitStoreException.Code.CAS_MISMATCH);
        assertThat(error.getMessage()).isEqualTo("connection revision does not match");
        assertThat(error.toString()).doesNotContain(BASE_URL, "secret-value", "vault://");
    }

    @Test
    void valueObjectsRejectWeakAndUnsafeEtagsButAcceptShortAndMaximumSafeTags() {
        CommandLease lease = lease("etag", 1, "etag-token", SCOPE, "customer", ExpectedRevision.create());
        ApiConnectionSpec spec = new ApiConnectionDecisions().next(SCOPE, Optional.empty(), "customer",
                noneCommand(), ExpectedRevision.create());
        for (String invalid : new String[]{"W/\"x\"", "\"W/x\"", "\"a\n\"", "\"a\"b\""}) {
            assertThatThrownBy(() -> new StagedApiConnection(lease, spec, ExpectedRevision.create(), invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StoredApiConnection(SCOPE, spec.view(), spec.fingerprint(), invalid,
                    lease.commandId())).isInstanceOf(IllegalArgumentException.class);
        }
        new StagedApiConnection(lease, spec, ExpectedRevision.create(), "\"x\"");
        new StoredApiConnection(SCOPE, spec.view(), spec.fingerprint(), "\"x\"", lease.commandId());
        String maximum = "\"" + "x".repeat(254) + "\"";
        new StagedApiConnection(lease, spec, ExpectedRevision.create(), maximum);
        new StoredApiConnection(SCOPE, spec.view(), spec.fingerprint(), maximum, lease.commandId());
    }

    private static ApiConnectionCommand noneCommand() {
        return new ApiConnectionCommand("Customer API", BASE_URL, ApiConnectionCommand.Auth.none(),
                new ApiConnectionCommand.Defaults(5000, Map.of("Accept", "application/json")));
    }

    private static ApiConnectionCommand renamedCommand(String displayName) {
        return new ApiConnectionCommand(displayName, BASE_URL, ApiConnectionCommand.Auth.none(),
                new ApiConnectionCommand.Defaults(5000, Map.of()));
    }

    private static CommandReceipt resourceReceipt(String resourceId, String connectionId, long connectionRevision) {
        ObjectMapper mapper = new ObjectMapper();
        var body = mapper.createObjectNode().put("schemaVersion", ApiResourceSaveReceiptClosure.SCHEMA_VERSION);
        body.putObject("connection").put("connectionId", connectionId)
                .put("revision", Math.toIntExact(connectionRevision));
        body.putObject("resource").put("kind", "API_RESOURCE").put("resourceId", resourceId)
                .put("revision", 1).put("fingerprint", "sha256:" + "b".repeat(64));
        body.putObject("projections").put("descriptor", "READY")
                .put("designContract", "READY").put("operator", "READY");
        return new CommandReceipt(ApiResourceSaveReceiptClosure.SCHEMA_VERSION, body,
                com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints.of(body),
                "\"outer\"");
    }

    private StagedApiConnection stage(ApiConnectionCommitStore store, CommandLease lease,
                                      String connectionId, ExpectedRevision expected,
                                      ApiConnectionCommand command, PreparedSecretBinding... prepared) {
        if (lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE) {
            prepareChildStage(store, lease);
        }
        return store.stage(lease, connectionId, expected, command, prepared);
    }

    /** Seeds the outer journal only for adapters that require an external resource authority. */
    protected void prepareChildStage(ApiConnectionCommitStore store, CommandLease lease) { }

    /** Records the committed outer receipt only for adapters with a shared resource journal. */
    protected void prepareOuterReceipt(ApiConnectionCommitStore store, CommandLease lease,
                                       CommandReceipt receipt, StoredApiConnection child) { }

    /** JDBC children must be invoked by the outer transaction; in-memory needs no wrapper. */
    protected StoredApiConnection commitChild(ApiConnectionCommitStore store, CommandLease lease) {
        return store.commitChild(lease);
    }

    /** JDBC child commits are visible only after the test harness closes the outer journal. */
    protected boolean childIsVisibleAfterOuterCommit() {
        return false;
    }


    /** Mints proofs only through the pending-secret store, mirroring production authority boundaries. */
    private static FinalizedSecretSlots proofFor(CommandLease commandLease,
                                                 ConnectionRevisionCoordinate coordinate,
                                                 ExpectedRevision connectionExpected, String slot) {
        InMemoryPendingSecretStore pending = new InMemoryPendingSecretStore(Clock.fixed(TEST_NOW, ZoneId.of("UTC")));
        CommandLease proofCommand = coordinate.connectionId().equals(commandLease.key().targetId())
                ? commandLease
                : leaseWithTarget(commandLease.commandId(), commandLease.attemptNo(), commandLease.attemptToken(),
                SCOPE, coordinate.connectionId(), AuthoringEndpoint.API_CONNECTION_SAVE, connectionExpected);
        PendingSecretLease lease = new PendingSecretLease(proofCommand, coordinate, connectionExpected);
        SecretOperationContext context = new SecretOperationContext(SCOPE, proofCommand.key().actorId(),
                "API_CONNECTION_SAVE", coordinate.connectionId(), coordinate.revision(), commandLease.commandId(),
                proofCommand.attemptNo(), proofCommand.attemptToken(), slot);
        PreparedExternalSecret prepared = new PreparedExternalSecret("provider:test", proofCommand.attemptToken(),
                "opaque-" + slot, TEST_NOW.plusSeconds(30), context);
        PendingSecretBatch batch = new PendingSecretBatch(lease,
                List.of(new PendingSecretOperation.Prepared(slot, SecretSourceMode.VALUE, prepared)));
        pending.stage(batch);
        return pending.prepareFinalization(batch, List.of(new com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.ActivatedSecretSlot(
                slot, new ActivatedExternalSecret("provider:test", proofCommand.attemptToken(), "active-" + slot))));
    }

    private ApiConnectionCommitStore newStore(Clock clock) {
        return createStore(clock);
    }

    private ApiConnectionCommitStore newStore() {
        return newStore(Clock.fixed(TEST_NOW, ZoneId.of("UTC")));
    }

    private static CommandLease leaseAt(Clock clock, String commandId, int attemptNo, String attemptToken,
                                        AuthoringScope scope, String connectionId, ExpectedRevision expected,
                                        long seconds) {
        return new CommandLease(commandId, attemptNo, attemptToken,
                new CommandKey(scope, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                        connectionId, "key-" + commandId), REQUEST_FINGERPRINT,
                clock.instant().plusSeconds(seconds), expected);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;
        private MutableClock(Instant instant) { this(instant, ZoneId.of("UTC")); }
        private MutableClock(Instant instant, ZoneId zone) { this.instant = instant; this.zone = zone; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(instant, zone); }
        @Override public Instant instant() { return instant; }
        private void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
    }

    private static CommandLease lease(String commandId, int attemptNo, String attemptToken,
                                      AuthoringScope scope, String connectionId, ExpectedRevision expected) {
        return new CommandLease(commandId, attemptNo, attemptToken,
                new CommandKey(scope, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                        connectionId, "key-" + commandId), REQUEST_FINGERPRINT,
                TEST_NOW.plusSeconds(30), expected);
    }

    private static CommandLease leaseWithTarget(String commandId, int attemptNo, String attemptToken,
                                                AuthoringScope scope, String target,
                                                AuthoringEndpoint endpoint, ExpectedRevision expected) {
        return leaseWithTargetAndKey(commandId, attemptNo, attemptToken, scope, target, endpoint, expected,
                "key-" + commandId);
    }

    private static CommandLease leaseWithTargetAndKey(String commandId, int attemptNo, String attemptToken,
                                                      AuthoringScope scope, String target,
                                                      AuthoringEndpoint endpoint, ExpectedRevision expected,
                                                      String idempotencyKey) {
        return new CommandLease(commandId, attemptNo, attemptToken,
                new CommandKey(scope, "actor", endpoint, target, idempotencyKey), REQUEST_FINGERPRINT,
                TEST_NOW.plusSeconds(30), expected);
    }
}
