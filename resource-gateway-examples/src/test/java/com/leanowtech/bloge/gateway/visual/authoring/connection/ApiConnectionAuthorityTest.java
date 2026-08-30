package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract tests for the pure Connection command, authority and view seams. */
class ApiConnectionAuthorityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final AuthoringScope OTHER_SCOPE = new AuthoringScope("other", "project", "dev");

    @Test
    void allAuthAndSecretVariantsRoundTripToTheStrictWireShape() throws Exception {
        for (WireCase testCase : new WireCase[]{
                new WireCase(new ApiConnectionCommand.Auth.None(), "NONE", null, null),
                new WireCase(ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("token")),
                        "BEARER", "token", "VALUE"),
                new WireCase(ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.secretRef("vault://team/key")),
                        "BEARER", "token", "SECRET_REF"),
                new WireCase(ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.keepExisting()),
                        "BEARER", "token", "KEEP_EXISTING"),
                new WireCase(ApiConnectionCommand.Auth.basic("alice", ApiConnectionCommand.SecretWrite.value("password")),
                        "BASIC", "password", "VALUE"),
                new WireCase(ApiConnectionCommand.Auth.basic("alice", ApiConnectionCommand.SecretWrite.secretRef("vault://team/key")),
                        "BASIC", "password", "SECRET_REF"),
                new WireCase(ApiConnectionCommand.Auth.basic("alice", ApiConnectionCommand.SecretWrite.keepExisting()),
                        "BASIC", "password", "KEEP_EXISTING"),
                new WireCase(ApiConnectionCommand.Auth.apiKey("X-Api-Key", ApiConnectionCommand.SecretWrite.value("key")),
                        "API_KEY", "value", "VALUE"),
                new WireCase(ApiConnectionCommand.Auth.apiKey("X-Api-Key", ApiConnectionCommand.SecretWrite.secretRef("vault://team/key")),
                        "API_KEY", "value", "SECRET_REF"),
                new WireCase(ApiConnectionCommand.Auth.apiKey("X-Api-Key", ApiConnectionCommand.SecretWrite.keepExisting()),
                        "API_KEY", "value", "KEEP_EXISTING")}) {
            ApiConnectionCommand.Auth auth = testCase.auth();
            ApiConnectionCommand command = new ApiConnectionCommand("Customer API", "https://customer.example.com", auth,
                    new ApiConnectionCommand.Defaults(5000, Map.of("Accept", "application/json")));
            JsonNode wire = JSON.valueToTree(command);
            assertThat(wire.path("schemaVersion").asText()).isEqualTo(ApiConnectionCommand.SCHEMA_VERSION);
            assertThat(wire.path("auth").path("kind").asText()).isEqualTo(testCase.kind());
            if (auth instanceof ApiConnectionCommand.Auth.None) {
                assertThat(wire.path("auth").fieldNames()).toIterable().containsExactly("kind");
            } else if (auth instanceof ApiConnectionCommand.Auth.Bearer) {
                assertThat(wire.path("auth").fieldNames()).toIterable().containsExactlyInAnyOrder("kind", "token");
            } else if (auth instanceof ApiConnectionCommand.Auth.Basic) {
                assertThat(wire.path("auth").fieldNames()).toIterable().containsExactlyInAnyOrder("kind", "username", "password");
            } else {
                assertThat(wire.path("auth").fieldNames()).toIterable().containsExactlyInAnyOrder("kind", "headerName", "value");
            }
            if (testCase.secretField() != null) {
                assertThat(wire.at("/auth/" + testCase.secretField() + "/mode").asText())
                        .isEqualTo(testCase.mode());
            }
            assertThat(JSON.treeToValue(wire, ApiConnectionCommand.class)).isEqualTo(command);
        }
    }

    private record WireCase(ApiConnectionCommand.Auth auth, String kind, String secretField, String mode) { }

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
        assertThat(first.secretSlots()).containsExactly("token");
        assertThat(first.view().auth().kind()).isEqualTo("BEARER");
        assertThat(first.view().auth().configured()).isTrue();
        assertThat(JSON.valueToTree(first.view()).toString()).doesNotContain("vault", "token", "do-not-persist");
        assertThat(first.toString()).doesNotContain("https://customer.example.com", "do-not-persist", "vault");

        ApiConnectionCommand update = command(new ApiConnectionCommand.Auth.Bearer(
                new ApiConnectionCommand.SecretWrite.KeepExisting()));
        ApiConnectionSpec second = decisions.next(SCOPE, Optional.of(first), "customer", update,
                ExpectedRevision.match(1));
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.secretSlots()).containsExactly("token");
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
    void sameScopeIsRequiredForPreparedValuesAndExactAuthorizedReferences() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        ApiConnectionCommand value = command(new ApiConnectionCommand.Auth.Bearer(
                new ApiConnectionCommand.SecretWrite.Value("one-time-value")));
        ApiConnectionSpec current = decisions.next(SCOPE, Optional.empty(), "same-scope", value,
                ExpectedRevision.create(), new PreparedSecretBinding("token",
                        new SecretReference(SCOPE, "vault://staged/token")));
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.of(current), "same-scope", value,
                ExpectedRevision.match(1), new PreparedSecretBinding("token",
                        new SecretReference(OTHER_SCOPE, "vault://staged/token"))))
                .isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.NOT_FOUND);

        String requested = "vault://requested/token";
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.empty(), "wrong-ref", command(
                new ApiConnectionCommand.Auth.Bearer(new ApiConnectionCommand.SecretWrite.SecretRef(requested))),
                ExpectedRevision.create(), new PreparedSecretBinding("token",
                        new SecretReference(SCOPE, "vault://authorized/token"))))
                .isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.NOT_FOUND);
    }

    @Test
    void secretReferenceUsesTheCommonWireLimit() {
        String exact = "vault://" + "x".repeat(2040);
        assertThat(new ApiConnectionCommand.SecretWrite.SecretRef(exact).ref()).isEqualTo(exact);
        assertThat(new SecretReference(SCOPE, exact).ref()).isEqualTo(exact);
        String oversized = "vault://" + "x".repeat(2041);
        assertThatThrownBy(() -> new ApiConnectionCommand.SecretWrite.SecretRef(oversized))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretReference(SCOPE, oversized))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void basicAndApiKeyAuthorityMatrixSupportsValueRefKeepAndRejectsUnpreparedWrites() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        String basicRef = "vault://basic/password";
        ApiConnectionSpec basicValue = decisions.next(SCOPE, Optional.empty(), "basic-value", command(
                new ApiConnectionCommand.Auth.Basic("alice", new ApiConnectionCommand.SecretWrite.Value("password"))),
                ExpectedRevision.create(), new PreparedSecretBinding("password",
                        new SecretReference(SCOPE, "vault://basic/staged")));
        assertThat(basicValue.view().auth().configured()).isTrue();
        ApiConnectionSpec basicRefSpec = decisions.next(SCOPE, Optional.empty(), "basic-ref", command(
                new ApiConnectionCommand.Auth.Basic("alice", new ApiConnectionCommand.SecretWrite.SecretRef(basicRef))),
                ExpectedRevision.create(), new PreparedSecretBinding("password", new SecretReference(SCOPE, basicRef)));
        ApiConnectionSpec basicKeep = decisions.next(SCOPE, Optional.of(basicRefSpec), "basic-ref", command(
                new ApiConnectionCommand.Auth.Basic("alice", new ApiConnectionCommand.SecretWrite.KeepExisting())),
                ExpectedRevision.match(1));
        assertThat(basicKeep.revision()).isEqualTo(2);

        String apiKeyRef = "vault://api/key";
        ApiConnectionSpec apiKeyValue = decisions.next(SCOPE, Optional.empty(), "key-value", command(
                new ApiConnectionCommand.Auth.ApiKey("X-Api-Key", new ApiConnectionCommand.SecretWrite.Value("key"))),
                ExpectedRevision.create(), new PreparedSecretBinding("value",
                        new SecretReference(SCOPE, "vault://api/staged")));
        assertThat(apiKeyValue.view().auth().configured()).isTrue();
        ApiConnectionSpec apiKeyRefSpec = decisions.next(SCOPE, Optional.empty(), "key-ref", command(
                new ApiConnectionCommand.Auth.ApiKey("X-Api-Key", new ApiConnectionCommand.SecretWrite.SecretRef(apiKeyRef))),
                ExpectedRevision.create(), new PreparedSecretBinding("value", new SecretReference(SCOPE, apiKeyRef)));
        ApiConnectionSpec apiKeyKeep = decisions.next(SCOPE, Optional.of(apiKeyRefSpec), "key-ref", command(
                new ApiConnectionCommand.Auth.ApiKey("X-Api-Key", new ApiConnectionCommand.SecretWrite.KeepExisting())),
                ExpectedRevision.match(1));
        assertThat(apiKeyKeep.revision()).isEqualTo(2);

        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.empty(), "missing-value", command(
                new ApiConnectionCommand.Auth.Basic("alice", new ApiConnectionCommand.SecretWrite.Value("password"))),
                ExpectedRevision.create())).isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.VALIDATION);
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.empty(), "missing-keep", command(
                new ApiConnectionCommand.Auth.ApiKey("X-Api-Key", new ApiConnectionCommand.SecretWrite.KeepExisting())),
                ExpectedRevision.create())).isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.VALIDATION);
    }

    @Test
    void fingerprintsAreStableFormattedAndChangeWithAuthOrDefaults() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        PreparedSecretBinding staged = new PreparedSecretBinding("token", new SecretReference(SCOPE, "vault://fp/token"));
        ApiConnectionCommand value = command(new ApiConnectionCommand.Auth.Bearer(
                new ApiConnectionCommand.SecretWrite.Value("secret")));
        ApiConnectionSpec first = decisions.next(SCOPE, Optional.empty(), "fingerprinted", value,
                ExpectedRevision.create(), staged);
        ApiConnectionSpec equivalent = decisions.next(SCOPE, Optional.empty(), "fingerprinted", value,
                ExpectedRevision.create(), staged);
        assertThat(first.fingerprint()).matches("^sha256:[0-9a-f]{64}$").isEqualTo(equivalent.fingerprint());
        ApiConnectionSpec authChanged = decisions.next(SCOPE, Optional.of(first), "fingerprinted",
                command(new ApiConnectionCommand.Auth.None()), ExpectedRevision.match(1));
        assertThat(authChanged.fingerprint()).isNotEqualTo(first.fingerprint());
        ApiConnectionSpec defaultsChanged = decisions.next(SCOPE, Optional.of(first), "fingerprinted",
                command(new ApiConnectionCommand.Auth.Bearer(new ApiConnectionCommand.SecretWrite.KeepExisting()),
                        "https://customer.example.com", new ApiConnectionCommand.Defaults(6000, Map.of())),
                ExpectedRevision.match(1));
        assertThat(defaultsChanged.fingerprint()).isNotEqualTo(first.fingerprint());
    }

    @Test
    void requestFingerprintIsExplicitlyNoneOnly() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        assertThatThrownBy(() -> decisions.requestFingerprint(SCOPE, "customer", command(
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("not-fingerprinted")))))
                .isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.VALIDATION);
    }

    @Test
    void fingerprintsIgnoreSecretReferencesButIncludeEveryAuthoritySlotAndMetadataField() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        ApiConnectionSpec first = decisions.next(SCOPE, Optional.empty(), "fingerprinted-refs", command(
                new ApiConnectionCommand.Auth.Bearer(ApiConnectionCommand.SecretWrite.secretRef(
                        "vault://one/token"))), ExpectedRevision.create(), new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://one/token")));
        ApiConnectionSpec anotherReference = decisions.next(SCOPE, Optional.empty(), "fingerprinted-refs",
                command(new ApiConnectionCommand.Auth.Bearer(ApiConnectionCommand.SecretWrite.secretRef(
                        "vault://two/token"))), ExpectedRevision.create(), new PreparedSecretBinding("token",
                new SecretReference(SCOPE, "vault://two/token")));
        assertThat(anotherReference.fingerprint()).isEqualTo(first.fingerprint());

        ApiConnectionSpec basic = decisions.next(SCOPE, Optional.empty(), "fingerprinted-refs", command(
                new ApiConnectionCommand.Auth.Basic("alice", ApiConnectionCommand.SecretWrite.value("password"))),
                ExpectedRevision.create(), new PreparedSecretBinding("password",
                        new SecretReference(SCOPE, "vault://basic/password")));
        ApiConnectionSpec renamed = decisions.next(SCOPE, Optional.empty(), "fingerprinted-refs", command(
                new ApiConnectionCommand.Auth.Basic("bob", ApiConnectionCommand.SecretWrite.value("password"))),
                ExpectedRevision.create(), new PreparedSecretBinding("password",
                        new SecretReference(SCOPE, "vault://basic/other")));
        ApiConnectionSpec apiKey = decisions.next(SCOPE, Optional.empty(), "fingerprinted-refs", command(
                new ApiConnectionCommand.Auth.ApiKey("X-Api-Key", ApiConnectionCommand.SecretWrite.value("key"))),
                ExpectedRevision.create(), new PreparedSecretBinding("value",
                        new SecretReference(SCOPE, "vault://key/value")));
        ApiConnectionSpec otherApiKeyHeader = decisions.next(SCOPE, Optional.empty(), "fingerprinted-refs", command(
                new ApiConnectionCommand.Auth.ApiKey("X-Other-Key", ApiConnectionCommand.SecretWrite.value("key"))),
                ExpectedRevision.create(), new PreparedSecretBinding("value",
                        new SecretReference(SCOPE, "vault://key/other")));
        assertThat(renamed.fingerprint()).isNotEqualTo(basic.fingerprint());
        assertThat(apiKey.fingerprint()).isNotEqualTo(basic.fingerprint());
        assertThat(otherApiKeyHeader.fingerprint()).isNotEqualTo(apiKey.fingerprint());
        assertThat(first.secretSlots()).containsExactly("token");
        assertThat(basic.secretSlots()).containsExactly("password");
        assertThat(apiKey.secretSlots()).containsExactly("value");
    }

    @Test
    void authorityNeverExposesSecretReferencesOrProviderLocators() throws Exception {
        ApiConnectionSpec spec = new ApiConnectionDecisions().next(SCOPE, Optional.empty(), "redacted", command(
                new ApiConnectionCommand.Auth.Bearer(ApiConnectionCommand.SecretWrite.secretRef(
                        "vault://private/provider-token"))), ExpectedRevision.create(),
                new PreparedSecretBinding("token", new SecretReference(SCOPE, "vault://private/provider-token")));
        assertThat(spec.toString()).doesNotContain("vault", "provider-token");
        assertThat(spec.view().toString()).doesNotContain("vault", "provider-token");
        assertThat(JSON.valueToTree(spec).toString()).doesNotContain("vault", "provider-token");
        assertThat(JSON.valueToTree(spec.view()).toString()).doesNotContain("vault", "provider-token");
        assertThat(Arrays.stream(ApiConnectionSpec.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("secretBindings", "secretBindingMap");
        assertThat(Arrays.stream(ApiConnectionSpec.class.getDeclaredMethods())
                .map(Method::toGenericString)
                .filter(signature -> signature.contains("SecretReference")))
                .isEmpty();
    }

    @Test
    void keepExistingRequiresTheExactSlotAndCompatibleAuthHeader() {
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        ApiConnectionSpec bearer = decisions.next(SCOPE, Optional.empty(), "keep-guards", command(
                new ApiConnectionCommand.Auth.Bearer(ApiConnectionCommand.SecretWrite.value("token"))),
                ExpectedRevision.create(), new PreparedSecretBinding("token",
                        new SecretReference(SCOPE, "vault://keep/token")));
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.of(bearer), "keep-guards", command(
                new ApiConnectionCommand.Auth.Basic("alice", ApiConnectionCommand.SecretWrite.keepExisting())),
                ExpectedRevision.match(1))).isInstanceOf(ApiConnectionAuthoringException.class)
                .extracting("code").isEqualTo(ApiConnectionAuthoringException.Code.VALIDATION);

        ApiConnectionSpec key = decisions.next(SCOPE, Optional.empty(), "keep-key", command(
                new ApiConnectionCommand.Auth.ApiKey("X-Api-Key", ApiConnectionCommand.SecretWrite.value("key"))),
                ExpectedRevision.create(), new PreparedSecretBinding("value",
                        new SecretReference(SCOPE, "vault://keep/key")));
        assertThatThrownBy(() -> decisions.next(SCOPE, Optional.of(key), "keep-key", command(
                new ApiConnectionCommand.Auth.ApiKey("X-Other-Key",
                        ApiConnectionCommand.SecretWrite.keepExisting())), ExpectedRevision.match(1)))
                .isInstanceOf(ApiConnectionAuthoringException.class)
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
                ApiConnectionAuthoringException.Code.VALIDATION);
        assertThat(error.getMessage()).isEqualTo("connection command is invalid");
        assertThat(error.toString()).isEqualTo("ApiConnectionAuthoringException[code=VALIDATION]");
        assertThat(command.toString()).doesNotContain("https://example.com", "application/json");
    }

    private static ApiConnectionCommand command(ApiConnectionCommand.Auth auth) {
        return command(auth, "https://customer.example.com", new ApiConnectionCommand.Defaults(5000, Map.of()));
    }

    private static ApiConnectionCommand command(ApiConnectionCommand.Auth auth, String baseUrl,
                                                ApiConnectionCommand.Defaults defaults) {
        return new ApiConnectionCommand("Customer API", baseUrl, auth, defaults);
    }
}
