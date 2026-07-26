package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBinding;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingRegistrationRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict auth-bound decoder for detached Shadow source-binding registration.
 *
 * <p>Authentication precedes parsing. Duplicate keys, unknown fields, trailing tokens, excessive
 * bytes, depth, and nodes are rejected before the complete request scope is compared with the
 * authenticated enterprise namespace.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class ReadOnlyShadowSourceBindingDecoder {
    /** Largest admitted registration body. */
    public static final int MAXIMUM_REQUEST_BYTES =
            ReadOnlyShadowSourceBinding.MAXIMUM_CANONICAL_BYTES;
    /** Deepest admitted registration JSON. */
    public static final int MAXIMUM_DEPTH = 48;
    /** Largest object, array, and scalar node count. */
    public static final int MAXIMUM_NODES = 20_000;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "bindingId",
            "revision",
            "scope",
            "scenarioCaseRef",
            "targetCapabilityRef",
            "candidatePlanRef",
            "baselineBindingRef",
            "comparisonPolicyRef",
            "requestContextFingerprint",
            "baseline",
            "candidateEvidenceRef",
            "validFrom",
            "expiresAt",
            "issuedAt");

    private final ObjectMapper strictMapper;

    /**
     * Creates a detached strict protocol mapper.
     *
     * @param mapper application mapper used only as a configuration baseline
     */
    public ReadOnlyShadowSourceBindingDecoder(
            ObjectMapper mapper) {
        strictMapper = Objects.requireNonNull(
                mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Decodes one exact unsigned registration after authentication.
     *
     * @param value untrusted buffered JSON bytes
     * @param identity authenticated integration identity
     * @return strict scope-bound registration
     */
    public ReadOnlyShadowSourceBindingRegistrationRequest decode(
            byte[] value,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope authenticatedScope =
                ReadOnlyShadowAuthorityKeySetDecoder.scope(identity);
        if (value == null
                || value.length == 0
                || value.length > MAXIMUM_REQUEST_BYTES) {
            throw invalid(identity);
        }
        try {
            JsonNode root = strictMapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw invalid(identity);
            }
            HashSet<String> actual = new HashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(FIELDS)
                    || !ReadOnlyShadowSourceBindingRegistrationRequest
                    .SCHEMA_VERSION.equals(
                            root.path("schemaVersion").textValue())) {
                throw invalid(identity);
            }
            requireBounds(root, identity);
            ReadOnlyShadowSourceBindingRegistrationRequest request =
                    strictMapper.treeToValue(
                            root,
                            ReadOnlyShadowSourceBindingRegistrationRequest
                                    .class);
            if (!authenticatedScope.equals(request.scope())) {
                throw new IntegrationProblemException(
                        IntegrationProblem.forbidden(
                                "RG.MIRROR.SHADOW_SOURCE_BINDING_SCOPE_MISMATCH",
                                "Source-binding scope does not match the authenticated scope.",
                                identity.correlationId(),
                                Map.of()));
            }
            return request;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(identity);
        }
    }

    private static void requireBounds(
            JsonNode root,
            IntegrationRequestContext identity) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int count = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeLast();
            if (++count > MAXIMUM_NODES
                    || current.depth() > MAXIMUM_DEPTH) {
                throw invalid(identity);
            }
            current.node().elements().forEachRemaining(
                    child -> pending.add(
                            new NodeDepth(
                                    child,
                                    current.depth() + 1)));
        }
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.badRequest(
                        "RG.MIRROR.SHADOW_SOURCE_BINDING_MALFORMED",
                        "Source binding must be bounded, unambiguous, closed v1 JSON.",
                        identity.correlationId(),
                        Map.of(
                                "schemaVersion",
                                ReadOnlyShadowSourceBindingRegistrationRequest
                                        .SCHEMA_VERSION,
                                "maximumBytes",
                                MAXIMUM_REQUEST_BYTES,
                                "maximumDepth",
                                MAXIMUM_DEPTH,
                                "maximumNodes",
                                MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
