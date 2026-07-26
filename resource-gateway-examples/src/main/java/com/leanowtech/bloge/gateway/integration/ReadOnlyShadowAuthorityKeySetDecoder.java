package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetPublication;
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
 * Strict auth-bound decoder for root-signed Shadow authority key-set publications.
 *
 * <p>Authentication occurs before this decoder is invoked. Duplicate keys, unknown fields,
 * excessive bytes, depth, and nodes are rejected before cryptographic work; the decoded scope
 * must equal the authenticated complete enterprise scope.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class ReadOnlyShadowAuthorityKeySetDecoder {
    /** Maximum buffered body equal to the canonical publication maximum. */
    public static final int MAXIMUM_REQUEST_BYTES =
            ReadOnlyShadowAuthorityKeySetIntegrity.MAXIMUM_PUBLICATION_BYTES;
    /** Maximum nested JSON depth. */
    public static final int MAXIMUM_DEPTH = 32;
    /** Maximum object, array, and scalar node count. */
    public static final int MAXIMUM_NODES = 20_000;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "publicationFingerprint", "materialFingerprint",
            "material", "signatures");

    private final ObjectMapper strictMapper;

    /**
     * Creates a detached strict protocol mapper.
     *
     * @param mapper application mapper used only as a configuration baseline
     */
    public ReadOnlyShadowAuthorityKeySetDecoder(ObjectMapper mapper) {
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Decodes one untrusted publication after authentication.
     *
     * @param value untrusted buffered JSON bytes
     * @param identity authenticated integration identity
     * @return strict scope-bound publication
     */
    public ReadOnlyShadowAuthorityKeySetPublication decode(
            byte[] value, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope authenticatedScope = scope(identity);
        if (value == null || value.length == 0 || value.length > MAXIMUM_REQUEST_BYTES) {
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
                    || FIELDS.stream().anyMatch(field -> root.path(field).isNull())
                    || !ReadOnlyShadowAuthorityKeySetPublication.SCHEMA_VERSION.equals(
                    root.path("schemaVersion").textValue())) {
                throw invalid(identity);
            }
            requireBounds(root, identity);
            ReadOnlyShadowAuthorityKeySetPublication publication = strictMapper.treeToValue(
                    root, ReadOnlyShadowAuthorityKeySetPublication.class);
            if (!authenticatedScope.equals(publication.material().scope())) {
                throw new IntegrationProblemException(IntegrationProblem.forbidden(
                        "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_SCOPE_MISMATCH",
                        "Authority key-set scope does not match the authenticated scope.",
                        identity.correlationId(), Map.of()));
            }
            return publication;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(identity);
        }
    }

    /**
     * Derives a complete exact scope from an already authenticated request.
     *
     * @param identity authenticated request identity
     * @return complete test or staging enterprise scope
     */
    public static CapabilitySnapshot.Scope scope(IntegrationRequestContext identity) {
        IntegrationRequestContext exact = Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        if (exact.projectId().isBlank() || exact.region().isBlank()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_SCOPE_INCOMPLETE",
                    "Authority trust distribution requires complete enterprise scope.",
                    exact.correlationId(), Map.of()));
        }
        if (!("test".equalsIgnoreCase(exact.environmentId())
                || "staging".equalsIgnoreCase(exact.environmentId()))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_ENVIRONMENT_FORBIDDEN",
                    "Authority trust distribution is restricted to test and staging.",
                    exact.correlationId(), Map.of()));
        }
        return new CapabilitySnapshot.Scope(
                exact.tenantId(), exact.organizationId(), exact.projectId(),
                exact.environmentId(), exact.region());
    }

    private static void requireBounds(JsonNode root, IntegrationRequestContext identity) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeLast();
            if (++nodes > MAXIMUM_NODES || current.depth() > MAXIMUM_DEPTH) {
                throw invalid(identity);
            }
            current.node().elements().forEachRemaining(
                    child -> pending.add(new NodeDepth(child, current.depth() + 1)));
        }
    }

    private static IntegrationProblemException invalid(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_MALFORMED",
                "Authority key set must be bounded, unambiguous, closed v1 JSON.",
                identity.correlationId(), Map.of(
                        "schemaVersion",
                        ReadOnlyShadowAuthorityKeySetPublication.SCHEMA_VERSION,
                        "maximumBytes", MAXIMUM_REQUEST_BYTES,
                        "maximumDepth", MAXIMUM_DEPTH,
                        "maximumNodes", MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
