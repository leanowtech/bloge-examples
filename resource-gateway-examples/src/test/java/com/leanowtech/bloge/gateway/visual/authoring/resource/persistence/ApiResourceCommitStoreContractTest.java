package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public seam contract for scoped staging, fencing, idempotency and commit. */
class ApiResourceCommitStoreContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final CommandKey KEY = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k1");
    private static final String R1 = "sha256:0000000000000000000000000000000000000000000000000000000000000001";
    private static final String R2 = "sha256:0000000000000000000000000000000000000000000000000000000000000002";
    private static final String R3 = "sha256:0000000000000000000000000000000000000000000000000000000000000003";

    @Test
    void stagedIsInvisibleAndCommitPublishesHeadAndRevision() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), R1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        assertThat(staged.projections().descriptor().fingerprint())
                .isNotEqualTo(staged.projections().designContract().fingerprint())
                .isNotEqualTo(staged.projections().operator().fingerprint());
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
        CommandReceipt receipt = store.commit(lease, receipt(staged));
        assertThat(receipt.strongEtag()).isNotEqualTo("\"1-" + staged.resource().fingerprint() + "\"");
        assertThat(store.findHead(SCOPE, "profile")).contains(store.findRevision(SCOPE, "profile", 1).orElseThrow());
    }

    @Test
    void scopeAndIdempotencyCoordinatesAreIsolated() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), R1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        store.commit(lease, receipt(staged));
        AuthoringScope other = new AuthoringScope("other", "project", "dev");
        CommandKey otherKey = new CommandKey(other, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k1");
        assertThat(store.claim(otherKey, R1, ExpectedRevision.create())).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(store.claim(KEY, R1, ExpectedRevision.create())).isInstanceOf(ClaimResult.Replay.class);
        assertThat(store.claim(KEY, R2, ExpectedRevision.create())).isInstanceOf(ClaimResult.Conflict.class);
    }

    @Test
    void sameCoordinateAndFingerprintWithChangedExpectedRevisionIsConflict() {
        InMemoryApiResourceCommitStore store = store();

        assertThat(store.claim(KEY, R1, ExpectedRevision.create())).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(store.claim(KEY, R1, ExpectedRevision.match(1)))
                .isInstanceOf(ClaimResult.Conflict.class);
    }

    @Test
    void liveLeaseBusyAndExpiredLeaseTakesOverWithStaleAttemptFenced() {
        MutableClock clock = new MutableClock();
        InMemoryApiResourceCommitStore store = store(clock);
        CommandLease first = acquired(store, KEY, ExpectedRevision.create(), R1);
        assertThat(store.claim(KEY, R1, ExpectedRevision.create())).isInstanceOf(ClaimResult.Busy.class);
        clock.advance(Duration.ofSeconds(2));
        ClaimResult.Acquired second = (ClaimResult.Acquired) store.claim(KEY, R1, ExpectedRevision.create());
        assertThat(second.resumed()).isTrue();
        assertThat(second.lease().attemptNo()).isEqualTo(first.attemptNo() + 1);
        assertThatThrownBy(() -> store.stage(first, "connection", command("one")))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.LEASE_FENCED);
    }

    @Test
    void failedCoordinateCannotBeRetakenWithDifferentFingerprint() {
        MutableClock clock = new MutableClock();
        InMemoryApiResourceCommitStore store = store(clock);
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), R1);
        store.fail(lease, CommandFailureCode.INTERNAL);
        assertThat(store.claim(KEY, R2, ExpectedRevision.create())).isInstanceOf(ClaimResult.Conflict.class);
        assertThat(store.claim(KEY, R1, ExpectedRevision.create())).isInstanceOf(ClaimResult.Acquired.class);
    }

    @Test
    void invalidFingerprintsAndFailureCodesAreRejected() {
        InMemoryApiResourceCommitStore store = store();
        assertThatThrownBy(() -> store.claim(KEY, "sha256:bad", ExpectedRevision.create()))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), R1);
        assertThatThrownBy(() -> store.fail(null, CommandFailureCode.INTERNAL))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.LEASE_FENCED);
        assertThatThrownBy(() -> store.fail(lease, null))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
        for (String invalid : List.of("", "lowercase", "HAS SPACE", "A".repeat(129))) {
            assertThatThrownBy(() -> new CommandFailureCode(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void everyProjectionKindRejectsBodyFingerprintMismatch() {
        for (ProjectionDocument.Kind kind : ProjectionDocument.Kind.values()) {
            JsonNode body = JSON.createObjectNode().put("kind", kind.name());
            assertThatThrownBy(() -> new ProjectionDocument(kind,
                    new ApiResourceSpec.ResourceRef("API_RESOURCE", "profile", 1, R1), body, R2,
                    ProjectionDocument.State.READY)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void failRemovesStageAndLeavesPreviousHeadVisible() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease first = acquired(store, KEY, ExpectedRevision.create(), R1);
        store.stage(first, "connection", command("one"));
        StagedApiResource staged = store.stage(first, "connection", command("one"));
        store.commit(first, receipt(staged));
        CommandKey update = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k2");
        CommandLease second = acquired(store, update, ExpectedRevision.match(1), R2);
        store.stage(second, "connection", command("two"));
        store.fail(second, CommandFailureCode.PROJECTION_INVALID);
        assertThat(store.findHead(SCOPE, "profile")).isPresent().get().extracting(v -> v.resource().revision()).isEqualTo(1);
        assertThatThrownBy(() -> store.commit(second, receipt(staged)))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.LEASE_FENCED);
    }

    @Test
    void updateUsesExactCasAndStaleRevisionIsRejected() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease create = acquired(store, KEY, ExpectedRevision.create(), R1);
        store.stage(create, "connection", command("one"));
        StagedApiResource stagedCreate = store.stage(create, "connection", command("one"));
        store.commit(create, receipt(stagedCreate));
        CommandKey updateKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k2");
        CommandLease update = acquired(store, updateKey, ExpectedRevision.match(1), R2);
        store.stage(update, "connection", command("two"));
        StagedApiResource stagedUpdate = store.stage(update, "connection", command("two"));
        assertThat(store.commit(update, receipt(stagedUpdate)).body().get("result").asText()).isEqualTo("profile");
        CommandKey staleKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k3");
        CommandLease stale = acquired(store, staleKey, ExpectedRevision.match(1), R3);
        assertThatThrownBy(() -> store.stage(stale, "connection", command("three")))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.CAS_MISMATCH);
    }

    @Test
    void commitRechecksCasAfterAnotherCommandAdvancesHead() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease create = acquired(store, KEY, ExpectedRevision.create(), R1);
        store.stage(create, "connection", command("one"));
        StagedApiResource stagedCreate = store.stage(create, "connection", command("one"));
        store.commit(create, receipt(stagedCreate));
        CommandLease first = acquired(store, key("k2"), ExpectedRevision.match(1), R2);
        StagedApiResource stagedFirst = store.stage(first, "connection", command("two"));
        CommandLease second = acquired(store, key("k3"), ExpectedRevision.match(1), R3);
        store.stage(second, "connection", command("three"));
        StagedApiResource stagedSecond = store.stage(second, "connection", command("three"));
        store.commit(second, receipt(stagedSecond));
        assertThatThrownBy(() -> store.commit(first, receipt(stagedFirst)))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.CAS_MISMATCH);
        assertThat(store.findHead(SCOPE, "profile").orElseThrow().resource().revision()).isEqualTo(2);
    }

    @Test
    void projectionDriftFailsClosedBeforeHeadChanges() {
        InMemoryApiResourceCommitStore store = new InMemoryApiResourceCommitStore(Clock.systemUTC(), Duration.ofSeconds(1),
                (scope, resource) -> new ReadyApiResourceProjections(
                        doc(ProjectionDocument.Kind.DESCRIPTOR, resource),
                        new ProjectionDocument(ProjectionDocument.Kind.DESIGN_CONTRACT, new ApiResourceSpec.ResourceRef("API_RESOURCE", "other", resource.revision(), resource.fingerprint()), JSON.createObjectNode(), AuthoringFingerprints.of(JSON.createObjectNode()), ProjectionDocument.State.READY),
                        doc(ProjectionDocument.Kind.OPERATOR, resource)));
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), R1);
        assertThatThrownBy(() -> store.stage(lease, "connection", command("one")))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.PROJECTION_INVALID);
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
    }

    @Test
    void returnedJsonBodiesAreDefensiveCopies() {
        InMemoryApiResourceCommitStore store = store();
        CommandLease lease = acquired(store, KEY, ExpectedRevision.create(), R1);
        store.stage(lease, "connection", command("one"));
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        CommandReceipt receipt = store.commit(lease, receipt(staged));
        ((com.fasterxml.jackson.databind.node.ObjectNode) receipt.body()).put("resourceId", "tampered");
        assertThat(store.findHead(SCOPE, "profile").orElseThrow().receipt().body().get("result").asText()).isEqualTo("profile");
    }

    @Test
    void twoStoresWithSameExpectedRevisionProduceOneCommittedHead() throws Exception {
        InMemoryApiResourceCommitStore.State state = new InMemoryApiResourceCommitStore.State();
        InMemoryApiResourceCommitStore store = store(Clock.systemUTC(), state);
        InMemoryApiResourceCommitStore otherStore = store(Clock.systemUTC(), state);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CommandLease first = acquired(store, key("race-a"), ExpectedRevision.create(), R1);
            CommandLease second = acquired(otherStore, key("race-b"), ExpectedRevision.create(), R2);
            StagedApiResource stagedFirst = store.stage(first, "connection", command("one"));
            StagedApiResource stagedSecond = otherStore.stage(second, "connection", command("two"));
            CyclicBarrier barrier = new CyclicBarrier(2);
            var firstCommit = pool.submit(() -> { barrier.await(); return store.commit(first, receipt(stagedFirst)); });
            var secondCommit = pool.submit(() -> { barrier.await(); return otherStore.commit(second, receipt(stagedSecond)); });
            int successes = 0;
            try { firstCommit.get(); successes++; } catch (java.util.concurrent.ExecutionException ex) { assertStoreCode(ex, ApiResourceCommitStoreException.Code.CAS_MISMATCH); }
            try { secondCommit.get(); successes++; } catch (java.util.concurrent.ExecutionException ex) { assertStoreCode(ex, ApiResourceCommitStoreException.Code.CAS_MISMATCH); }
            assertThat(successes).isEqualTo(1);
            assertThat(store.findHead(SCOPE, "profile")).isPresent();
            assertThat(store.findRevision(SCOPE, "profile", 1)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertStoreCode(java.util.concurrent.ExecutionException error, ApiResourceCommitStoreException.Code code) {
        assertThat(error.getCause()).isInstanceOf(ApiResourceCommitStoreException.class).extracting("code").isEqualTo(code);
    }

    @Test
    void restartReusesStateAndReplaysCommittedReceipt() {
        InMemoryApiResourceCommitStore.State state = new InMemoryApiResourceCommitStore.State();
        InMemoryApiResourceCommitStore first = store(Clock.systemUTC(), state);
        CommandLease lease = acquired(first, KEY, ExpectedRevision.create(), R1);
        StagedApiResource staged = first.stage(lease, "connection", command("one"));
        CommandReceipt receipt = first.commit(lease, receipt(staged));
        InMemoryApiResourceCommitStore restarted = store(Clock.systemUTC(), state);
        ClaimResult.Replay replay = (ClaimResult.Replay) restarted.claim(KEY, R1, ExpectedRevision.create());
        assertThat(replay.receipt()).isEqualTo(receipt);
        assertThat(restarted.findRevision(SCOPE, "profile", 1)).isPresent();
    }

    private static CommandLease acquired(InMemoryApiResourceCommitStore store, CommandKey key,
                                         ExpectedRevision expected, String fingerprint) {
        return ((ClaimResult.Acquired) store.claim(key, fingerprint, expected)).lease();
    }

    private static CommandKey key(String idempotencyKey) {
        return new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", idempotencyKey);
    }

    private static InMemoryApiResourceCommitStore store() { return store(Clock.systemUTC(), new InMemoryApiResourceCommitStore.State()); }
    private static InMemoryApiResourceCommitStore store(Clock clock) { return store(clock, new InMemoryApiResourceCommitStore.State()); }
    private static InMemoryApiResourceCommitStore store(Clock clock, InMemoryApiResourceCommitStore.State state) {
        return new InMemoryApiResourceCommitStore(clock, Duration.ofSeconds(1), new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions(), ApiResourceCommitStoreContractTest::compile, state);
    }

    private static ReadyApiResourceProjections compile(AuthoringScope scope, ApiResourceSpec resource) {
        return new ReadyApiResourceProjections(doc(ProjectionDocument.Kind.DESCRIPTOR, resource),
                doc(ProjectionDocument.Kind.DESIGN_CONTRACT, resource), doc(ProjectionDocument.Kind.OPERATOR, resource));
    }

    private static ProjectionDocument doc(ProjectionDocument.Kind kind, ApiResourceSpec resource) {
        return new ProjectionDocument(kind, resource.ref(), JSON.createObjectNode().put("kind", kind.name()),
                AuthoringFingerprints.of(JSON.createObjectNode().put("kind", kind.name())), ProjectionDocument.State.READY);
    }

    private static CommandReceipt receipt(StagedApiResource staged) {
        JsonNode body = JSON.createObjectNode().put("result", staged.resource().resourceId());
        return new CommandReceipt("test.receipt.v1", body, AuthoringFingerprints.of(body), staged.strongEtag());
    }

    private static ApiResourceCommand command(String name) {
        Map<String, Object> schema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id")).schema();
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
        JsonNode value = JSON.createObjectNode().put("id", "one");
        return new ApiResourceCommand(name, null, new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(envelope, envelope), new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.READ_ONLY, List.of(new ApiResourceCommand.Example("one", value, value)));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
