package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract tests for the pure Connection command, authority and view seams. */
class ApiConnectionAuthorityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final AuthoringScope OTHER_SCOPE = new AuthoringScope("other", "project", "dev");

    @Test
    void allAuthAndSecretVariantsRoundTripToTheStrictWireShape() throws Exception {
        for (ApiConnectionCommand.Auth auth : new ApiConnectionCommand.Auth[]{
                new ApiConnectionCommand.Auth.None(),
                new ApiConnectionCommand.Auth.Bearer(new ApiConnectionCommand.SecretWrite.Value("token")),
                new ApiConnectionCommand.Auth.Basic("alice", new ApiConnectionCommand.SecretWrite.SecretRef("vault://team/key")),
                new ApiConnectionCommand.Auth.ApiKey("X-Api-Key", new ApiConnectionCommand.SecretWrite.KeepExisting())}) {
            ApiConnectionCommand command = new ApiConnectionCommand("Customer API", "https://customer.example.com", auth,
                    new ApiConnectionCommand.Defaults(5000, Map.of("Accept", "application/json")));
            JsonNode wire = JSON.valueToTree(command);
            assertThat(wire.path("schemaVersion").asText()).isEqualTo(ApiConnectionCommand.SCHEMA_VERSION);
            assertThat(wire.path("auth").path("kind").asText()).isEqualTo(auth.kind());
            if (auth instanceof ApiConnectionCommand.Auth.None) {
                assertThat(wire.path("auth").fieldNames()).toIterable().containsExactly("kind");
            } else if (auth instanceof ApiConnectionCommand.Auth.Bearer) {
                assertThat(wire.path("auth").fieldNames()).toIterable().containsExactlyInAnyOrder("kind", "token");
                assertThat(wire.at("/auth/token/mode").asText()).isIn("VALUE", "SECRET_REF", "KEEP_EXISTING");
            } else if (auth instanceof ApiConnectionCommand.Auth.Basic) {
                assertThat(wire.path("auth").fieldNames()).toIterable().containsExactlyInAnyOrder("kind", "username", "password");
                assertThat(wire.at("/auth/password/mode").asText()).isIn("VALUE", "SECRET_REF", "KEEP_EXISTING");
            } else {
                assertThat(wire.path("auth").fieldNames()).toIterable().containsExactlyInAnyOrder("kind", "headerName", "value");
                assertThat(wire.at("/auth/value/mode").asText()).isIn("VALUE", "SECRET_REF", "KEEP_EXISTING");
            }
            assertThat(JSON.treeToValue(wire, ApiConnectionCommand.class)).isEqualTo(command);
        }
    }

    @Test
    void commandAndViewShapesFollowTheVersionedSchemaProperties() throws Exception {
        Path schemaRoot = Path.of("..", "docs", "schemas", "resource-gateway-authoring");
        JsonNode commandSchema = JSON.readTree(schemaRoot.resolve("connection-command-v1.schema.json").toFile());
        JsonNode viewSchema = JSON.readTree(schemaRoot.resolve("connection-view-v1.schema.json").toFile());
        ApiConnectionCommand command = new ApiConnectionCommand("Public API", "https://api.example.com",
                new ApiConnectionCommand.Auth.None());
        JsonNode commandWire = JSON.valueToTree(command);
        assertThat(commandWire).isEqualTo(JSON.readTree(schemaRoot.resolve("examples/connection-minimal.json").toFile()));
        ApiConnectionCommand complete = new ApiConnectionCommand("Customer Service", "https://customer.example.com",
                new ApiConnectionCommand.Auth.Bearer(new ApiConnectionCommand.SecretWrite.Value("write-only-token")),
                new ApiConnectionCommand.Defaults(5000, Map.of("Accept", "application/json")));
        JsonNode completeWire = JSON.valueToTree(complete);
        assertThat(completeWire).isEqualTo(JSON.readTree(schemaRoot.resolve("examples/connection-complete.json").toFile()));
        assertThat(commandSchema.path("required").isArray()).isTrue();

        ApiConnectionView view = new ApiConnectionView(ApiConnectionView.SCHEMA_VERSION, "public", 1,
                "Public API", "https://api.example.com", new ApiConnectionView.Auth("NONE", false),
                new ApiConnectionCommand.Defaults(30_000, Map.of()));
        JsonNode viewWire = JSON.valueToTree(view);
        assertThat(viewWire).isEqualTo(JSON.readTree("""
                {"schemaVersion":"bloge.apiConnectionView.v1","connectionId":"public","revision":1,
                 "displayName":"Public API","baseUrl":"https://api.example.com",
                 "auth":{"kind":"NONE","configured":false},"defaults":{"timeoutMs":30000,"headers":{}}}
                """));
        assertThat(viewSchema.path("required").toString())
                .isEqualTo("[\"schemaVersion\",\"connectionId\",\"revision\",\"displayName\",\"baseUrl\",\"auth\"]");
        assertThat(viewWire.toString()).doesNotContain("token", "password", "value", "ref", "username", "headerName");
    }

    @Test
    void decisionsApplyCreateUpdateCasAndReturnSecretFreeView() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        ApiConnectionCommand create = command(new ApiConnectionCommand.Auth.Bearer(
                new ApiConnectionCommand.SecretWrite.Value("do-not-persist")));
        PreparedSecretBinding staged = new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://staged/token"));

        ApiConnectionSpec first = decisions.next(SCOPE, Optional.empty(), "customer", create,
                ExpectedRevision.create(), staged);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.secretBindings()).containsEntry("token", staged.reference());
        assertThat(first.view().auth().kind()).isEqualTo("BEARER");
        assertThat(first.view().auth().configured()).isTrue();
        assertThat(JSON.valueToTree(first.view()).toString()).doesNotContain("vault", "token", "do-not-persist");

        ApiConnectionCommand update = command(new ApiConnectionCommand.Auth.Bearer(
                new ApiConnectionCommand.SecretWrite.KeepExisting()));
        ApiConnectionSpec second = decisions.next(SCOPE, Optional.of(first), "customer", update,
                ExpectedRevision.match(1));
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.secretBindings()).isEqualTo(first.secretBindings());
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.of(second), "customer", update,
                ExpectedRevision.match(1))).isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.CAS_MISMATCH);
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.of(first), "customer", create,
                ExpectedRevision.create())).isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.ALREADY_EXISTS);
    }

    @Test
    void secretReferencesRequireTheSameScopeAndKeepExistingRequiresCompatibleUpdate() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        String ref = "vault://team/token";
        ApiConnectionSpec first = decisions.next(SCOPE, Optional.empty(), "customer", command(
                new ApiConnectionCommand.Auth.Bearer(new ApiConnectionCommand.SecretWrite.SecretRef(ref))),
                ExpectedRevision.create(), new PreparedSecretBinding("token", new SecretReference(SCOPE, ref)));
        assertThatThrownBy(() -> decisions.next(OTHER_SCOPE, Optional.of(first), "customer", command(
                new ApiConnectionCommand.Auth.Bearer(new ApiConnectionCommand.SecretWrite.SecretRef("vault://team/token"))),
                ExpectedRevision.match(1))).isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.NOT_FOUND);
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.of(first), "customer", command(
                new ApiConnectionCommand.Auth.Basic("alice", new ApiConnectionCommand.SecretWrite.KeepExisting())),
                ExpectedRevision.match(1))).isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.VALIDATION);
    }

    @Test
    void validatesHttpsUrlTimeoutHeadersAndApiKeyCollision() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        for (String url : new String[]{"http://example.com", "https://user@example.com", "https://example.com?q=1",
                "https://example.com#fragment", "https://example.com\n"}) {
            assertThatThrownBy(() -> decisions.next(SCOPE, Optional.empty(), "customer", command(
                    new ApiConnectionCommand.Auth.None(), url, new ApiConnectionCommand.Defaults(1000, Map.of())),
                    ExpectedRevision.create())).isInstanceOf(ApiConnectionAuthoringException.class);
        }
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.empty(), "customer", command(
                new ApiConnectionCommand.Auth.None(), "https://example.com", new ApiConnectionCommand.Defaults(99, Map.of())),
                ExpectedRevision.create())).isInstanceOf(ApiConnectionAuthoringException.class);
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.empty(), "customer", command(
                new ApiConnectionCommand.Auth.ApiKey("X-Key", new ApiConnectionCommand.SecretWrite.SecretRef("vault://x/key")),
                "https://example.com", new ApiConnectionCommand.Defaults(1000, Map.of("x-key", "collision"))),
                ExpectedRevision.create())).isInstanceOf(ApiConnectionAuthoringException.class);
    }

    @Test
    void authorityRejectsMissingOrUnsupportedCommandSchemaVersion() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        for (String version : new String[]{null, "bloge.apiConnectionCommand.v0"}) {
            ApiConnectionCommand command = new ApiConnectionCommand(version, "Public API", "https://api.example.com",
                    new ApiConnectionCommand.Auth.None(), null);
            assertThatThrownBy(() -> decisions.next(SCOPE, Optional.empty(), "public", command,
                    ExpectedRevision.create())).isInstanceOf(ApiConnectionAuthoringException.class)
                    .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.VALIDATION);
        }
    }

    @Test
    void mapsAndExceptionsAreDefensiveAndRedacted() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        ApiConnectionCommand command = command(new ApiConnectionCommand.Auth.Bearer(
                new ApiConnectionCommand.SecretWrite.Value("secret-value")), "https://example.com",
                new ApiConnectionCommand.Defaults(1000, headers));
        headers.put("Injected", "bad");
        assertThat(command.defaults().headers()).doesNotContainKey("Injected");
        assertThat(command.toString()).doesNotContain("secret-value");
        assertThat(new ApiConnectionCommand.SecretWrite.SecretRef("vault://private/ref").toString())
                .doesNotContain("vault://private/ref");
        ApiConnectionAuthoringException error = new ApiConnectionAuthoringException(
                ApiConnectionAuthoringException.Code.VALIDATION, "safe message");
        assertThat(error.toString()).doesNotContain("secret", "vault://");
    }

    private static ApiConnectionCommand command(ApiConnectionCommand.Auth auth) {
        return command(auth, "https://customer.example.com", new ApiConnectionCommand.Defaults(5000, Map.of()));
    }

    private static ApiConnectionCommand command(ApiConnectionCommand.Auth auth, String baseUrl,
                                                ApiConnectionCommand.Defaults defaults) {
        return new ApiConnectionCommand("Customer API", baseUrl, auth, defaults);
    }
}
