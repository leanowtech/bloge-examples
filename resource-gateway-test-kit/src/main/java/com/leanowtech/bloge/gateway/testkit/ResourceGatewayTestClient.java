package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Synchronous, dependency-light client for the profile-isolated Resource Gateway testing API.
 *
 * <p>The client sends a fresh credential and correlation id per request, rejects redirects and
 * unknown protocol versions, bounds request/response bodies, and never includes credentials or
 * payload bodies in thrown exception messages.</p>
 */
public final class ResourceGatewayTestClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** Public evidence projection verbosity. */
    public enum Verbosity {
        /** Terminal state, fingerprints, fixture consumption, and assertions. */
        SUMMARY,
        /** Summary plus payload-free node and edge observations. */
        STANDARD,
        /** Full sanitized evidence projection. */
        FULL
    }

    /** Supplies a short-lived bearer credential at request time. */
    @FunctionalInterface
    public interface BearerTokenProvider {
        /** Supplies the current credential.
         * @return current bearer token without the {@code Bearer} prefix
         */
        String bearerToken();
    }

    private final URI baseUri;
    private final HttpClient httpClient;
    private final BearerTokenProvider tokenProvider;
    private final Supplier<String> correlationIdProvider;
    private final Duration requestTimeout;
    private final int maxRequestBytes;
    private final int maxResponseBytes;

    private ResourceGatewayTestClient(Builder builder) {
        baseUri = validateBaseUri(builder.baseUri);
        tokenProvider = Objects.requireNonNull(builder.tokenProvider, "bearer token provider is required");
        correlationIdProvider = builder.correlationIdProvider;
        requestTimeout = builder.requestTimeout;
        maxRequestBytes = builder.maxRequestBytes;
        maxResponseBytes = builder.maxResponseBytes;
        httpClient = builder.httpClient == null
                ? HttpClient.newBuilder().connectTimeout(builder.connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build()
                : builder.httpClient;
    }

    /**
     * Starts a client builder for one Resource Gateway base URI.
     * @param baseUri absolute HTTP(S) server base URI
     * @return new client builder
     */
    public static Builder builder(URI baseUri) {
        return new Builder(baseUri);
    }

    /**
     * Discovers a graph contract and its frozen dependency fingerprint.
     * @param graphName registered graph name
     * @return typed target descriptor
     */
    public GraphTargetDescriptor describeGraphTarget(String graphName) {
        JsonNode response = exchange("GET", "/api/testing/targets/graphs/" + segment(graphName), "",
                "TEST_EXECUTION", null);
        requireVersion(response, TestingProtocol.GRAPH_TARGET_DESCRIPTOR_V1);
        return GraphTargetDescriptor.from(response);
    }

    /**
     * Registers one immutable fixture revision.
     * @param fixtureBundleId fixture id used in the endpoint
     * @param registrationRequest schema-complete registration request
     * @return committed immutable revision
     */
    public FixtureBundleRevision registerFixture(String fixtureBundleId, JsonNode registrationRequest) {
        JsonNode response = exchange("PUT", "/api/testing/fixture-bundles/" + segment(fixtureBundleId), "",
                "TEST_FIXTURE_WRITE", requiredObject(registrationRequest, "registrationRequest"));
        requireVersion(response, TestingProtocol.STORED_FIXTURE_BUNDLE_V1);
        return FixtureBundleRevision.from(response);
    }

    /**
     * Reads one tenant- and environment-scoped immutable fixture revision.
     * @param fixtureBundleId fixture id
     * @param revision positive immutable revision
     * @return stored fixture projection
     */
    public FixtureBundleRevision findFixture(String fixtureBundleId, long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        JsonNode response = exchange("GET", "/api/testing/fixture-bundles/" + segment(fixtureBundleId),
                "revision=" + revision, "TEST_FIXTURE_READ", null);
        requireVersion(response, TestingProtocol.STORED_FIXTURE_BUNDLE_V1);
        return FixtureBundleRevision.from(response);
    }

    /**
     * Executes one inline or stored-fixture request through the controlled graph runtime.
     * @param executionRequest schema-complete execution request
     * @return persisted run projection
     */
    public TestRun execute(JsonNode executionRequest) {
        JsonNode response = exchange("POST", "/api/testing/executions", "", "TEST_EXECUTION",
                requiredObject(executionRequest, "executionRequest"));
        requireVersion(response, TestingProtocol.TEST_EXECUTION_RESPONSE_V1);
        return projectRun(response);
    }

    /**
     * Executes one bounded independent batch through the same server kernel.
     * @param executionRequests 1 to 100 schema-complete execution requests
     * @return ordered batch projection
     */
    public TestRunBatch executeBatch(List<? extends JsonNode> executionRequests) {
        if (executionRequests == null || executionRequests.isEmpty() || executionRequests.size() > 100) {
            throw new IllegalArgumentException("A test batch must contain 1 to 100 executions");
        }
        ObjectNode batch = JSON.createObjectNode();
        batch.put("schemaVersion", TestingProtocol.TEST_EXECUTION_BATCH_REQUEST_V1);
        ArrayNode executions = batch.putArray("executions");
        executionRequests.forEach(request -> executions.add(requiredObject(request, "executionRequest").deepCopy()));
        JsonNode response = exchange("POST", "/api/testing/executions/batch", "", "TEST_EXECUTION", batch);
        requireVersion(response, TestingProtocol.TEST_EXECUTION_BATCH_RESPONSE_V1);
        List<TestRun> runs = new ArrayList<>();
        response.path("executions").forEach(item -> {
            requireVersion(item, TestingProtocol.TEST_EXECUTION_RESPONSE_V1);
            runs.add(projectRun(item));
        });
        if (runs.size() != executionRequests.size()) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.BATCH_CARDINALITY_MISMATCH",
                    "The server returned a different number of batch results.", null);
        }
        return new TestRunBatch(runs);
    }

    /**
     * Retrieves one persisted, authorization-scoped test run at the requested verbosity.
     * @param runId persisted run id
     * @param verbosity response projection, defaulting to STANDARD
     * @return run projection
     */
    public TestRun findRun(String runId, Verbosity verbosity) {
        String selected = (verbosity == null ? Verbosity.STANDARD : verbosity).name();
        JsonNode response = exchange("GET", "/api/testing/executions/" + segment(runId),
                "verbosity=" + selected, "TEST_EXECUTION", null);
        requireVersion(response, TestingProtocol.TEST_EXECUTION_RESPONSE_V1);
        return projectRun(response);
    }

    private JsonNode exchange(String method, String path, String query, String purpose, JsonNode body) {
        byte[] requestBody = serialize(body);
        URI uri = endpoint(path, query);
        String token = normalized(tokenProvider.bearerToken());
        if (token.isBlank() || token.length() > 4096 || token.contains("\r") || token.contains("\n")) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.CREDENTIAL_UNAVAILABLE",
                    "A bounded bearer credential is required.", null);
        }
        String correlationId = bounded(normalized(correlationIdProvider.get()), 128);
        if (correlationId.isBlank() || correlationId.contains("\r") || correlationId.contains("\n")) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.CORRELATION_ID_INVALID",
                    "The correlation id provider returned an invalid value.", null);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(requestBody);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Purpose", purpose)
                .header("X-Correlation-Id", correlationId)
                .method(method, publisher);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(request.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes = readBounded(response.body(), response.headers()
                    .firstValueAsLong("Content-Length").orElse(-1));
            JsonNode decoded = decode(bytes);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw problem(response.statusCode(), decoded);
            }
            if (!decoded.isObject()) {
                throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_MALFORMED",
                        "The server returned a non-object JSON response.", null);
            }
            return decoded;
        } catch (ResourceGatewayTestException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ResourceGatewayTestException.local("RG.TESTKIT.REQUEST_INTERRUPTED",
                    "The Resource Gateway test request was interrupted.", failure);
        } catch (IOException failure) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.TRANSPORT_FAILURE",
                    "The Resource Gateway test endpoint could not be reached.", failure);
        }
    }

    private byte[] serialize(JsonNode body) {
        if (body == null) {
            return new byte[0];
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(body);
            if (bytes.length > maxRequestBytes) {
                throw ResourceGatewayTestException.local("RG.TESTKIT.REQUEST_TOO_LARGE",
                        "The testing request exceeds the configured body limit.", null);
            }
            return bytes;
        } catch (JsonProcessingException failure) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.REQUEST_MALFORMED",
                    "The testing request could not be encoded as JSON.", failure);
        }
    }

    private byte[] readBounded(InputStream input, long contentLength) throws IOException {
        if (contentLength > maxResponseBytes) {
            input.close();
            throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_TOO_LARGE",
                    "The testing response exceeds the configured body limit.", null);
        }
        try (input) {
            byte[] bytes = input.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_TOO_LARGE",
                        "The testing response exceeds the configured body limit.", null);
            }
            return bytes;
        }
    }

    private static JsonNode decode(byte[] bytes) {
        try {
            if (bytes.length == 0) {
                throw new JsonProcessingException("empty response") { };
            }
            return JSON.readTree(bytes);
        } catch (JsonProcessingException failure) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_MALFORMED",
                    "The server returned malformed JSON.", failure);
        } catch (IOException failure) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_MALFORMED",
                    "The server response could not be decoded.", failure);
        }
    }

    private static ResourceGatewayTestException problem(int status, JsonNode body) {
        if (body != null && body.isObject()) {
            return new ResourceGatewayTestException(status,
                    bounded(body.path("code").asText("RG.TESTKIT.HTTP_ERROR"), 160),
                    bounded(body.path("title").asText("The Resource Gateway rejected the test request."), 512),
                    body.path("retryable").asBoolean(false),
                    bounded(body.path("correlationId").asText(), 128), null);
        }
        return new ResourceGatewayTestException(status, "RG.TESTKIT.HTTP_ERROR",
                "The Resource Gateway rejected the test request.", status >= 500, "", null);
    }

    private static TestRun projectRun(JsonNode response) {
        try {
            return TestRun.from(response);
        } catch (IllegalArgumentException failure) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_CONTRACT_INVALID",
                    "The server returned an invalid test-run projection.", failure);
        }
    }

    private static void requireVersion(JsonNode response, String expected) {
        String actual = response.path("schemaVersion").asText();
        if (!expected.equals(actual)) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.PROTOCOL_VERSION_MISMATCH",
                    "Expected " + expected + " but received " + bounded(actual, 128) + ".", null);
        }
    }

    private URI endpoint(String path, String query) {
        String base = baseUri.toString();
        String value = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
        return URI.create(query == null || query.isBlank() ? value : value + "?" + query);
    }

    private static JsonNode requiredObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return value;
    }

    private static String segment(String value) {
        String normalized = normalized(value);
        if (normalized.isBlank() || normalized.length() > 512) {
            throw new IllegalArgumentException("URI path identifiers must contain 1 to 512 characters");
        }
        StringBuilder encoded = new StringBuilder();
        for (byte current : normalized.getBytes(StandardCharsets.UTF_8)) {
            int octet = current & 0xff;
            if ((octet >= 'a' && octet <= 'z') || (octet >= 'A' && octet <= 'Z')
                    || (octet >= '0' && octet <= '9') || octet == '-' || octet == '.'
                    || octet == '_' || octet == '~') {
                encoded.append((char) octet);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((octet >>> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(octet & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static URI validateBaseUri(URI value) {
        Objects.requireNonNull(value, "baseUri is required");
        String scheme = normalized(value.getScheme()).toLowerCase(java.util.Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || value.getHost() == null
                || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must be an absolute HTTP(S) URI without credentials, query, or fragment");
        }
        return value;
    }

    private static String bounded(String value, int maximum) {
        String normalized = normalized(value);
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /** Mutable construction options for one immutable test client. */
    public static final class Builder {
        private final URI baseUri;
        private HttpClient httpClient;
        private BearerTokenProvider tokenProvider;
        private Supplier<String> correlationIdProvider = () -> UUID.randomUUID().toString();
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private int maxRequestBytes = DEFAULT_MAX_BODY_BYTES;
        private int maxResponseBytes = DEFAULT_MAX_BODY_BYTES;

        private Builder(URI baseUri) {
            this.baseUri = baseUri;
        }

        /**
         * Uses the supplied JDK client, for example one configured with enterprise TLS.
         * @param value configured HTTP client
         * @return this builder
         */
        public Builder httpClient(HttpClient value) {
            httpClient = Objects.requireNonNull(value, "httpClient");
            return this;
        }

        /**
         * Configures a request-time bearer token provider.
         * @param value credential provider
         * @return this builder
         */
        public Builder bearerToken(BearerTokenProvider value) {
            tokenProvider = Objects.requireNonNull(value, "tokenProvider");
            return this;
        }

        /**
         * Configures request correlation ids, useful when the calling test owns trace context.
         * @param value correlation-id supplier
         * @return this builder
         */
        public Builder correlationId(Supplier<String> value) {
            correlationIdProvider = Objects.requireNonNull(value, "correlationIdProvider");
            return this;
        }

        /**
         * Sets the connection timeout used when the builder creates the JDK client.
         * @param value positive timeout
         * @return this builder
         */
        public Builder connectTimeout(Duration value) {
            connectTimeout = positive(value, "connectTimeout");
            return this;
        }

        /**
         * Sets the per-request timeout.
         * @param value positive timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration value) {
            requestTimeout = positive(value, "requestTimeout");
            return this;
        }

        /**
         * Sets the encoded JSON request-body limit.
         * @param value positive byte limit
         * @return this builder
         */
        public Builder maxRequestBytes(int value) {
            maxRequestBytes = positive(value, "maxRequestBytes");
            return this;
        }

        /**
         * Sets the response-body limit enforced before JSON decoding.
         * @param value positive byte limit
         * @return this builder
         */
        public Builder maxResponseBytes(int value) {
            maxResponseBytes = positive(value, "maxResponseBytes");
            return this;
        }

        /**
         * Builds one thread-safe immutable client.
         * @return configured client
         */
        public ResourceGatewayTestClient build() {
            return new ResourceGatewayTestClient(this);
        }

        private static Duration positive(Duration value, String field) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }

        private static int positive(int value, String field) {
            if (value < 1) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }
    }
}
