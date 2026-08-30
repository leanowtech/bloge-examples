package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.InMemoryApiConnectionCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringCommandClaimStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Behavior proof for the standalone Connection application tracer. */
class ApiConnectionAuthoringFacadeTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final String BASE_URL = "https://customer.example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void facadeHasNoExternalMapperConstructionPath() {
        assertThat(Arrays.stream(ApiConnectionAuthoringFacade.class.getConstructors())
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .noneMatch(ObjectMapper.class::equals)).isTrue();
    }

    @Test
    void createAndSameKeyCreateReplayReturnExactPayloadFreeReceipt() {
        var facade = facade();
        var request = request("create", "customer", "key-create", ApiConnectionAuthoringPrecondition.create(),
                command("Customer API"));

        ApiConnectionAuthoringResult first = facade.save(request);
        ApiConnectionAuthoringResult replay = facade.save(request);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.view()).isEqualTo(first.view());
        assertThat(replay.strongEtag()).isEqualTo(first.strongEtag());
    }

    @Test
    void oldEtagReplaysAfterAnotherKeyAdvancesTheHeadButNewKeyCasFails() {
        var facade = facade();
        ApiConnectionAuthoringResult first = facade.save(request("create", "customer", "key-one",
                ApiConnectionAuthoringPrecondition.create(), command("Customer API")));
        ApiConnectionAuthoringRequest update = request("update", "customer", "key-two",
                ApiConnectionAuthoringPrecondition.matchStrongEtag(first.strongEtag()), command("Customer v2"));
        ApiConnectionAuthoringResult second = facade.save(update);
        facade.save(request("advance", "customer", "key-three",
                ApiConnectionAuthoringPrecondition.matchStrongEtag(second.strongEtag()), command("Customer v3")));

        ApiConnectionAuthoringResult replay = facade.save(update);
        assertThat(second.view().revision()).isEqualTo(2);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.view().revision()).isEqualTo(2);

        assertThatThrownBy(() -> facade.save(request("stale", "customer", "key-three",
                ApiConnectionAuthoringPrecondition.matchStrongEtag(first.strongEtag()),
                command("Stale writer"))))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.CAS_MISMATCH);
    }

    @Test
    void unknownEtagForAnExistingTargetIsCasMismatch() {
        var facade = facade();
        facade.save(request("create", "customer", "known", ApiConnectionAuthoringPrecondition.create(),
                command("Customer API")));

        assertThatThrownBy(() -> facade.save(request("update", "customer", "unknown-tag",
                ApiConnectionAuthoringPrecondition.matchStrongEtag("\"not-the-current-tag\""),
                command("Customer v2"))))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.CAS_MISMATCH);
    }

    @Test
    void changedNonSecretPayloadWithTheSameKeyIsConflict() {
        var facade = facade();
        ApiConnectionAuthoringRequest first = request("create", "customer", "key-conflict",
                ApiConnectionAuthoringPrecondition.create(), command("Customer API"));
        facade.save(first);

        assertThatThrownBy(() -> facade.save(request("create", "customer", "key-conflict",
                ApiConnectionAuthoringPrecondition.create(), command("Changed name"))))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.CONFLICT);
    }

    @Test
    void failedStageCasIsTerminalizedSoARepeatedRequestIsCasMismatchNotBusy() {
        var store = new InMemoryApiConnectionCommitStore(Clock.systemUTC(),
                new ApiConnectionDecisions(), java.time.Duration.ofSeconds(30), JSON);
        var facade = new ApiConnectionAuthoringFacade(store, new ApiConnectionDecisions());
        // A Create claim can be acquired even though a head was concurrently
        // established before stage. The decision layer then rejects the stale
        // create before an active stage exists.
        facade.save(request("seed", "customer", "seed-key", ApiConnectionAuthoringPrecondition.create(),
                command("Customer API")));
        ApiConnectionAuthoringRequest stale = request("stale", "customer", "stale-key",
                ApiConnectionAuthoringPrecondition.create(), command("Customer API"));

        assertThatThrownBy(() -> facade.save(stale))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.CAS_MISMATCH);
        assertThatThrownBy(() -> facade.save(stale))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.CAS_MISMATCH);
    }

    @Test
    void replayUsesTheConfiguredStoreMapper() {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var decisions = new ApiConnectionDecisions();
        var store = new InMemoryApiConnectionCommitStore(Clock.systemUTC(), decisions,
                java.time.Duration.ofSeconds(30), mapper);
        var facade = new ApiConnectionAuthoringFacade(store, decisions);
        ApiConnectionAuthoringRequest request = request("custom-mapper", "customer", "custom-mapper-key",
                ApiConnectionAuthoringPrecondition.create(), command("Customer API"));

        ApiConnectionAuthoringResult first = facade.save(request);
        ApiConnectionAuthoringResult replay = facade.save(request);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.view()).isEqualTo(first.view());
        assertThat(replay.strongEtag()).isEqualTo(first.strongEtag());
    }

    @Test
    void malformedEtagAndInvalidCommandDoNotConsumeAClaim() {
        var store = mock(com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore.class);
        var facade = new ApiConnectionAuthoringFacade(store, new ApiConnectionDecisions());

        assertThatThrownBy(() -> facade.save(request("bad-etag", "customer", "key-bad-etag",
                ApiConnectionAuthoringPrecondition.matchStrongEtag("W/\"weak\""), command("Customer API"))))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.VALIDATION);
        assertThatThrownBy(() -> facade.save(request("bad-url", "customer", "key-bad-url",
                ApiConnectionAuthoringPrecondition.create(),
                new ApiConnectionCommand("Customer API", "http://not-https.example.com",
                        ApiConnectionCommand.Auth.none()))))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.VALIDATION);

        verify(store, never()).claim(any(), any(), any());
        verify(store, never()).findRevisionByStrongEtag(any(), any(), any());
    }

    @Test
    void unsupportedCredentialCapabilityIsRejectedWithoutClaimOrSecretLeak() {
        var store = mock(com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore.class);
        var facade = new ApiConnectionAuthoringFacade(store, new ApiConnectionDecisions());
        String secret = "do-not-leak-secret";
        ApiConnectionCommand command = new ApiConnectionCommand("Customer API", BASE_URL,
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value(secret)));

        assertThatThrownBy(() -> facade.save(request("unsupported", "customer", "key-secret",
                ApiConnectionAuthoringPrecondition.create(), command)))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.CAPABILITY_UNAVAILABLE)
                .asString().doesNotContain(secret);
        verify(store, never()).claim(any(), any(), any());
        verify(store, never()).findRevisionByStrongEtag(any(), any(), any());
    }

    @Test
    void stageOrCommitFailureIsMappedAndExactFailureCleanupIsAttempted() {
        var store = mock(com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore.class);
        var lease = new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease(
                "command", 1, "token", new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey(
                        SCOPE, "actor", com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_CONNECTION_SAVE,
                        "customer", "key"), "sha256:" + "a".repeat(64), java.time.Instant.now().plusSeconds(30),
                com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
        org.mockito.Mockito.when(store.claim(any(), any(), any()))
                .thenReturn(new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult.Acquired(lease, false));
        org.mockito.Mockito.doThrow(new com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException(
                com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code.STAGE_MISSING))
                .when(store).stage(any(), any(), any(), any());
        var facade = new ApiConnectionAuthoringFacade(store, new ApiConnectionDecisions());

        assertThatThrownBy(() -> facade.save(request("failure", "customer", "key",
                ApiConnectionAuthoringPrecondition.create(), command("Customer API"))))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.INTEGRITY);
        verify(store).fail(lease);
    }

    @Test
    void cleanupFailureIsTypedAndDoesNotExposeTheOriginalFailure() {
        var store = mock(com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore.class);
        var lease = lease("cleanup-failure");
        org.mockito.Mockito.when(store.claim(any(), any(), any()))
                .thenReturn(new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult.Acquired(lease, false));
        org.mockito.Mockito.doThrow(new com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException(
                com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code.STAGE_MISSING))
                .when(store).stage(any(), any(), any(), any());
        org.mockito.Mockito.doThrow(new com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException(
                com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code.INTEGRITY))
                .when(store).fail(lease);
        var facade = new ApiConnectionAuthoringFacade(store, new ApiConnectionDecisions());

        assertThatThrownBy(() -> facade.save(request("cleanup", "customer", "cleanup-key",
                ApiConnectionAuthoringPrecondition.create(), command("Customer API"))))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.PERSISTENCE);
    }

    @Test
    void busyCarriesTheAuthoritativeRetryAt() {
        var store = mock(com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore.class);
        Instant retryAt = Instant.parse("2026-08-31T00:00:30Z");
        org.mockito.Mockito.when(store.claim(any(), any(), any()))
                .thenReturn(new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult.Busy(retryAt));
        var facade = new ApiConnectionAuthoringFacade(store, new ApiConnectionDecisions());

        assertThatThrownBy(() -> facade.save(request("busy", "customer", "busy-key",
                ApiConnectionAuthoringPrecondition.create(), command("Customer API"))))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .satisfies(error -> {
                    ApiConnectionAuthoringFailure failure = (ApiConnectionAuthoringFailure) error;
                    assertThat(failure.code()).isEqualTo(ApiConnectionAuthoringFailure.Code.BUSY);
                    assertThat(failure.retryAt()).isEqualTo(retryAt);
                });
    }

    @Test
    void genericClaimFailureDoesNotEscapeAsAResourceFailure() {
        var store = mock(com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore.class);
        org.mockito.Mockito.when(store.claim(any(), any(), any()))
                .thenThrow(new AuthoringCommandClaimStoreException(AuthoringCommandClaimStoreException.Code.PERSISTENCE));
        var facade = new ApiConnectionAuthoringFacade(store, new ApiConnectionDecisions());

        assertThatThrownBy(() -> facade.save(request("claim-error", "customer", "claim-error-key",
                ApiConnectionAuthoringPrecondition.create(), command("Customer API"))))
                .isInstanceOf(ApiConnectionAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringFailure.Code.PERSISTENCE);
    }

    private static com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease lease(String key) {
        return new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease(
                "command-" + key, 1, "token-" + key,
                new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey(
                        SCOPE, "actor", com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_CONNECTION_SAVE,
                        "customer", key), "sha256:" + "c".repeat(64), Instant.now().plusSeconds(30),
                com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
    }

    @Test
    void expiredLeaseIsBusyWhileFencedLeaseIsLeaseLost() {
        for (var codeAndExpected : Map.of(
                com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code.LEASE_EXPIRED,
                ApiConnectionAuthoringFailure.Code.BUSY,
                com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code.LEASE_FENCED,
                ApiConnectionAuthoringFailure.Code.LEASE_LOST).entrySet()) {
            var store = mock(com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore.class);
            var lease = new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease(
                    "command-" + codeAndExpected.getKey(), 1, "token", new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey(
                            SCOPE, "actor", com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint.API_CONNECTION_SAVE,
                            "customer", "key"), "sha256:" + "b".repeat(64), java.time.Instant.now().plusSeconds(30),
                    com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
            org.mockito.Mockito.when(store.claim(any(), any(), any()))
                    .thenReturn(new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult.Acquired(lease, false));
            org.mockito.Mockito.doThrow(new com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException(
                    codeAndExpected.getKey())).when(store).stage(any(), any(), any(), any());
            var facade = new ApiConnectionAuthoringFacade(store, new ApiConnectionDecisions());

            assertThatThrownBy(() -> facade.save(request("lease", "customer", "key",
                    ApiConnectionAuthoringPrecondition.create(), command("Customer API"))))
                    .isInstanceOf(ApiConnectionAuthoringFailure.class)
                    .extracting("code").isEqualTo(codeAndExpected.getValue());
        }
    }

    private static ApiConnectionAuthoringFacade facade() {
        return new ApiConnectionAuthoringFacade(new InMemoryApiConnectionCommitStore(
                Clock.systemUTC(), new ApiConnectionDecisions(), java.time.Duration.ofSeconds(30), JSON),
                new ApiConnectionDecisions());
    }

    private static ApiConnectionAuthoringRequest request(String actor, String connectionId, String key,
                                                          ApiConnectionAuthoringPrecondition precondition,
                                                          ApiConnectionCommand command) {
        return new ApiConnectionAuthoringRequest(SCOPE, actor, connectionId, key, precondition, command);
    }

    private static ApiConnectionCommand command(String name) {
        return new ApiConnectionCommand(name, BASE_URL, ApiConnectionCommand.Auth.none(),
                new ApiConnectionCommand.Defaults(5000, Map.of("X-Mode", "demo")));
    }
}
