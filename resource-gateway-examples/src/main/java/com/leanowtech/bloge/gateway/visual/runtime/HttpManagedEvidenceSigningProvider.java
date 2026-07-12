package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** HTTP adapter for a private-network KMS/HSM evidence signing sidecar. */
public final class HttpManagedEvidenceSigningProvider implements ManagedEvidenceSigningProvider {
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;

    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final HttpClient client;
    private final URI keysUri;
    private final URI signUri;

    public HttpManagedEvidenceSigningProvider(ObjectMapper objectMapper, Settings settings) {
        this.settings = settings == null ? null : settings.validated();
        if (this.settings == null) {
            throw new IllegalArgumentException("Managed evidence signing HTTP settings are required");
        }
        this.objectMapper = (objectMapper == null ? new ObjectMapper() : objectMapper).copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.client = HttpClient.newBuilder()
                .connectTimeout(this.settings.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.keysUri = endpoint(this.settings.baseUri(), "v1/evidence-signing/keys");
        this.signUri = endpoint(this.settings.baseUri(), "v1/evidence-signing/sign");
    }

    @Override
    public KeySet fetchKeys() {
        JsonNode root = request(HttpRequest.newBuilder(keysUri)
                .GET()
                .timeout(settings.requestTimeout())
                .header("Accept", "application/json")
                .build(), "KEY_DISCOVERY");
        JsonNode keys = root.path("keys");
        if (!root.isObject() || !keys.isArray()) {
            throw failure("INVALID_KEY_SNAPSHOT", "Managed signing key response is invalid", false);
        }
        List<ManagedKey> parsed = new ArrayList<>();
        for (JsonNode key : keys) {
            if (key.has("privateKey") || key.has("encodedPrivateKey") || key.has("privateMaterial")
                    || key.has("d")) {
                throw failure("PRIVATE_KEY_MATERIAL_REJECTED",
                        "Managed signing provider must never export private key material", false);
            }
            parsed.add(new ManagedKey(text(key, "keyId"), text(key, "algorithm"),
                    text(key, "encodedPublicKey"), instant(key, "createdAt"), text(key, "state"),
                    text(key, "providerKeyVersion")));
        }
        return new KeySet(text(root, "schemaVersion"), instant(root, "generatedAt"),
                instant(root, "expiresAt"), text(root, "activeKeyId"), parsed);
    }

    @Override
    public SignatureResult sign(SignatureRequest request) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (IOException failure) {
            throw failure("SIGN_REQUEST_ENCODING_FAILED", "Managed signing request could not be encoded", false,
                    failure);
        }
        JsonNode root = request(HttpRequest.newBuilder(signUri)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(settings.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", request.requestId())
                .build(), "SIGN");
        return new SignatureResult(text(root, "schemaVersion"), text(root, "requestId"),
                text(root, "keyId"), text(root, "algorithm"), text(root, "materialFingerprint"),
                instant(root, "signedAt"), text(root, "signature"));
    }

    @Override
    public String providerName() {
        return settings.providerName();
    }

    private JsonNode request(HttpRequest request, String operation) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 409 && "SIGN".equals(operation)) {
                response.body().close();
                throw failure("KEY_VERSION_MISMATCH", "Managed signing key version changed", true);
            }
            if (status != 200) {
                response.body().close();
                boolean retryable = status == 408 || status == 429 || status >= 500;
                throw failure(retryable ? "PROVIDER_UNAVAILABLE" : "PROVIDER_REJECTED",
                        "Managed signing provider returned HTTP " + status + " for " + operation, retryable);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) {
                response.body().close();
                throw failure("INVALID_PROVIDER_RESPONSE",
                        "Managed signing provider returned a non-JSON response", false);
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > MAX_RESPONSE_BYTES) {
                response.body().close();
                throw failure("PROVIDER_RESPONSE_TOO_LARGE",
                        "Managed signing provider response exceeds its size limit", false);
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
                throw failure("PROVIDER_RESPONSE_TOO_LARGE",
                        "Managed signing provider response has an invalid size", false);
            }
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw failure("INVALID_PROVIDER_RESPONSE", "Managed signing provider response is invalid", false);
            }
            return root;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure("PROVIDER_UNAVAILABLE", "Managed signing request was interrupted", true, failure);
        } catch (JsonProcessingException failure) {
            throw failure("INVALID_PROVIDER_RESPONSE",
                    "Managed signing provider returned invalid or duplicate-field JSON", false, failure);
        } catch (IOException failure) {
            throw failure("PROVIDER_UNAVAILABLE", "Managed signing provider request failed", true, failure);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? "" : value.textValue();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException failure) {
            throw failure("INVALID_PROVIDER_RESPONSE",
                    "Managed signing provider returned an invalid " + field, false, failure);
        }
    }

    private static URI endpoint(URI baseUri, String path) {
        String base = baseUri.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + path);
    }

    private static EvidenceSigningProviderException failure(String code, String message, boolean retryable) {
        return new EvidenceSigningProviderException(code, message, retryable);
    }

    private static EvidenceSigningProviderException failure(String code,
                                                              String message,
                                                              boolean retryable,
                                                              Throwable cause) {
        return new EvidenceSigningProviderException(code, message, retryable, cause);
    }

    public record Settings(URI baseUri,
                           Duration requestTimeout,
                           String providerName,
                           boolean allowInsecureLoopback) {
        public Settings validated() {
            validateUri(baseUri, allowInsecureLoopback);
            Duration timeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
            if (timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Managed signing request timeout must be between 100ms and 30s");
            }
            String name = providerName == null ? "" : providerName.trim();
            if (name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Managed signing provider name is required");
            }
            return new Settings(baseUri, timeout, name, allowInsecureLoopback);
        }

        private static void validateUri(URI uri, boolean allowInsecureLoopback) {
            if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getQuery() != null) {
                throw new IllegalArgumentException("A valid managed signing provider base URI is required");
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
            if (!allowInsecureLoopback || !loopback || !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Managed signing provider must use HTTPS");
            }
        }
    }
}
