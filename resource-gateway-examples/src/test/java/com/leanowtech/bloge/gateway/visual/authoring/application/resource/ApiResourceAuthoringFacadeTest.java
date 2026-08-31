package com.leanowtech.bloge.gateway.visual.authoring.application.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringRequest;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.InMemoryApiConnectionCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionSnapshot;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.InMemoryApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.DefaultApiResourceProjectionCompiler;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiConnectionStoreResourceProjectionResolver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Behavior proof for the EXISTING-Connection/NONE-Fixture Resource tracer. */
class ApiResourceAuthoringFacadeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");

    @Test
    void createAndSameKeyReplayReturnOneExactReceipt() {
        Fixture fixture = fixture();
        ApiResourceAuthoringRequest request = request("create", "create-key",
                ApiResourceAuthoringPrecondition.create(), saveCommand(command("Get profile")));

        ApiResourceAuthoringResult first = fixture.facade().save(request);
        ApiResourceAuthoringResult replay = fixture.facade().save(request);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.stored()).isEqualTo(first.stored());
        assertThat(first.stored().receipt().schemaVersion())
                .isEqualTo("bloge.apiResourceSaveReceipt.v1");
        assertThat(first.stored().receipt().body().path("defaultFixture").isMissingNode()).isTrue();
        assertThat(first.stored().receipt().body().path("connection").path("revision").asLong()).isEqualTo(1);
        assertThat(first.stored().projections().connectionSnapshot()).isEqualTo(
                new ApiResourceConnectionSnapshot("customer", 1,
                        fixture.connections().findHead(SCOPE, "customer").orElseThrow().metadataFingerprint()));
    }

    @Test
    void oldEtagReplaysAfterHeadAdvancesButNewStaleCommandFails() {
        Fixture fixture = fixture();
        ApiResourceAuthoringResult first = fixture.facade().save(request("create", "one",
                ApiResourceAuthoringPrecondition.create(), saveCommand(command("Profile v1"))));
        ApiResourceAuthoringRequest update = request("update", "two",
                ApiResourceAuthoringPrecondition.matchStrongEtag(first.stored().receipt().strongEtag()),
                saveCommand(command("Profile v2")));
        ApiResourceAuthoringResult second = fixture.facade().save(update);
        fixture.facade().save(request("advance", "three",
                ApiResourceAuthoringPrecondition.matchStrongEtag(second.stored().receipt().strongEtag()),
                saveCommand(command("Profile v3"))));

        assertThat(fixture.facade().save(update).replayed()).isTrue();
        assertThatThrownBy(() -> fixture.facade().save(request("stale", "four",
                ApiResourceAuthoringPrecondition.matchStrongEtag(first.stored().receipt().strongEtag()),
                saveCommand(command("Stale")))))
                .isInstanceOf(ApiResourceAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringFailure.Code.CAS_MISMATCH);
    }

    @Test
    void unsupportedNestedConnectionAndFixtureAreRejectedBeforeClaim() {
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        ApiConnectionAuthoringStore connections = mock(ApiConnectionAuthoringStore.class);
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(
                resources, connections, new ApiResourceDecisions());
        ApiResourceSaveCommand createConnection = new ApiResourceSaveCommand(
                ApiResourceSaveCommand.SCHEMA_VERSION,
                ApiResourceSaveCommand.Connection.create(new ApiConnectionCommand(
                        "Created", "https://created.example.test", ApiConnectionCommand.Auth.none())),
                command("Profile"), ApiResourceSaveCommand.DefaultFixture.none());
        ApiResourceSaveCommand generatedFixture = new ApiResourceSaveCommand(
                ApiResourceSaveCommand.SCHEMA_VERSION,
                ApiResourceSaveCommand.Connection.existing("customer"), command("Profile"),
                ApiResourceSaveCommand.DefaultFixture.fromExamples("Defaults", List.of("one")));

        for (ApiResourceSaveCommand unsupported : List.of(createConnection, generatedFixture)) {
            assertThatThrownBy(() -> facade.save(request("unsupported", "key-" + unsupported.connection().mode(),
                    ApiResourceAuthoringPrecondition.create(), unsupported)))
                    .isInstanceOf(ApiResourceAuthoringFailure.class)
                    .extracting("code").isEqualTo(ApiResourceAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        }
        verify(resources, never()).claim(any(), any(), any());
        verify(connections, never()).findHead(any(), any());
    }

    @Test
    void invalidCommandDoesNotConsumeAClaim() {
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        ApiConnectionAuthoringStore connections = mock(ApiConnectionAuthoringStore.class);
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(
                resources, connections, new ApiResourceDecisions());

        assertThatThrownBy(() -> facade.save(request("invalid", "invalid-key",
                ApiResourceAuthoringPrecondition.create(), saveCommand(command("")))))
                .isInstanceOf(ApiResourceAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringFailure.Code.VALIDATION);
        verify(resources, never()).claim(any(), any(), any());
    }

    @Test
    void missingConnectionIsTerminalizedSoRetryIsNotBusy() {
        ApiConnectionAuthoringStore connections = mock(ApiConnectionAuthoringStore.class);
        InMemoryApiResourceCommitStore resources = new InMemoryApiResourceCommitStore(
                Clock.systemUTC(), Duration.ofSeconds(30), new ApiResourceDecisions(),
                (scope, resource) -> { throw new AssertionError("projection must not run"); });
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(
                resources, connections, new ApiResourceDecisions());
        ApiResourceAuthoringRequest request = request("missing", "missing-key",
                ApiResourceAuthoringPrecondition.create(), saveCommand(command("Profile")));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> facade.save(request))
                    .isInstanceOf(ApiResourceAuthoringFailure.class)
                    .extracting("code").isEqualTo(ApiResourceAuthoringFailure.Code.CONNECTION_NOT_FOUND);
        }
    }

    @Test
    void malformedResourceEtagIsRejectedBeforeClaim() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.facade().save(request("bad-etag", "bad-etag-key",
                ApiResourceAuthoringPrecondition.matchStrongEtag("W/\"weak\""),
                saveCommand(command("Profile")))))
                .isInstanceOf(ApiResourceAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringFailure.Code.VALIDATION);
    }

    @Test
    void changedPayloadWithSameKeyConflictsAndStaleCreateRetryIsNotBusy() {
        Fixture fixture = fixture();
        ApiResourceAuthoringRequest first = request("create", "same-key",
                ApiResourceAuthoringPrecondition.create(), saveCommand(command("Profile")));
        fixture.facade().save(first);

        assertThatThrownBy(() -> fixture.facade().save(request("create", "same-key",
                ApiResourceAuthoringPrecondition.create(), saveCommand(command("Changed")))))
                .isInstanceOf(ApiResourceAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringFailure.Code.CONFLICT);
        ApiResourceAuthoringRequest stale = request("stale", "stale-key",
                ApiResourceAuthoringPrecondition.create(), saveCommand(command("Stale")));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> fixture.facade().save(stale))
                    .isInstanceOf(ApiResourceAuthoringFailure.class)
                    .extracting("code").isEqualTo(ApiResourceAuthoringFailure.Code.CAS_MISMATCH);
        }
    }

    @Test
    void busyClaimCarriesAuthoritativeRetryDeadlineWithoutReadingConnection() {
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        ApiConnectionAuthoringStore connections = mock(ApiConnectionAuthoringStore.class);
        Instant retryAt = Instant.parse("2026-08-31T00:00:30Z");
        org.mockito.Mockito.when(resources.claim(any(), any(), any()))
                .thenReturn(new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult.Busy(
                        retryAt));
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(
                resources, connections, new ApiResourceDecisions());

        assertThatThrownBy(() -> facade.save(request("busy", "busy-key",
                ApiResourceAuthoringPrecondition.create(), saveCommand(command("Profile")))))
                .isInstanceOf(ApiResourceAuthoringFailure.class)
                .satisfies(error -> {
                    ApiResourceAuthoringFailure failure = (ApiResourceAuthoringFailure) error;
                    assertThat(failure.code()).isEqualTo(ApiResourceAuthoringFailure.Code.BUSY);
                    assertThat(failure.retryAt()).isEqualTo(retryAt);
                });
        verify(connections, never()).findHead(any(), any());
    }

    private static Fixture fixture() {
        ApiConnectionDecisions connectionDecisions = new ApiConnectionDecisions();
        InMemoryApiConnectionCommitStore connections = new InMemoryApiConnectionCommitStore(
                Clock.systemUTC(), connectionDecisions, Duration.ofSeconds(30), JSON);
        new ApiConnectionAuthoringFacade(connections, connectionDecisions).save(
                new ApiConnectionAuthoringRequest(SCOPE, "seed", "customer", "connection-key",
                        ApiConnectionAuthoringPrecondition.create(), new ApiConnectionCommand(
                        "Customer", "https://customer.example.test", ApiConnectionCommand.Auth.none(),
                        new ApiConnectionCommand.Defaults(5000, Map.of("X-Mode", "demo")))));
        InMemoryApiResourceCommitStore resources = new InMemoryApiResourceCommitStore(
                Clock.systemUTC(), Duration.ofSeconds(30), new ApiResourceDecisions(),
                new DefaultApiResourceProjectionCompiler(
                        new ApiConnectionStoreResourceProjectionResolver(connections)));
        return new Fixture(new ApiResourceAuthoringFacade(resources, connections,
                new ApiResourceDecisions()), connections);
    }

    private static ApiResourceAuthoringRequest request(String actor, String key,
                                                       ApiResourceAuthoringPrecondition precondition,
                                                       ApiResourceSaveCommand command) {
        return new ApiResourceAuthoringRequest(SCOPE, actor, "profile", key, precondition, command);
    }

    private static ApiResourceSaveCommand saveCommand(ApiResourceCommand command) {
        return new ApiResourceSaveCommand(ApiResourceSaveCommand.SCHEMA_VERSION,
                ApiResourceSaveCommand.Connection.existing("customer"), command,
                ApiResourceSaveCommand.DefaultFixture.none());
    }

    private static ApiResourceCommand command(String displayName) {
        SchemaEnvelope schema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id"));
        JsonNode value = JSON.createObjectNode().put("id", "one");
        return new ApiResourceCommand(displayName, null,
                new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(schema, schema),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.READ_ONLY,
                List.of(new ApiResourceCommand.Example("one", value, value)));
    }

    private record Fixture(ApiResourceAuthoringFacade facade,
                           InMemoryApiConnectionCommitStore connections) { }
}
