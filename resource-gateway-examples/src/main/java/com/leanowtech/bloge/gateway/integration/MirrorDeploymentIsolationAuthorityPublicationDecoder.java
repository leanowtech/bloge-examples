package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityKeySetIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityKeySetPublication;
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
 * Strict bounded decoder for threshold-signed isolation-authority publications.
 *
 * <p>Duplicate keys are rejected before tree materialization, every record is recursively closed
 * to unknown fields, and byte, depth, and node limits are enforced before cryptographic work.
 * Spring buffers the body before this adapter, so deployment ingress must still enforce total
 * request and connection limits.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class MirrorDeploymentIsolationAuthorityPublicationDecoder {
    /** Maximum buffered publication body, equal to the canonical protocol maximum. */
    public static final int MAXIMUM_REQUEST_BYTES =
            MirrorDeploymentIsolationAuthorityKeySetIntegrity.MAXIMUM_PUBLICATION_BYTES;
    /** Maximum nested JSON depth. */
    public static final int MAXIMUM_DEPTH = 32;
    /** Maximum object, array, and scalar node count. */
    public static final int MAXIMUM_NODES = 10_000;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "publicationFingerprint", "materialFingerprint", "material",
            "signatures");

    private final ObjectMapper strictMapper;

    /**
     * Creates a detached strict mapper from the application protocol mapper.
     *
     * @param mapper application protocol mapper used only as immutable configuration input
     */
    public MirrorDeploymentIsolationAuthorityPublicationDecoder(ObjectMapper mapper) {
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Decodes one untrusted buffered body into the exact closed v1 protocol.
     *
     * @param value untrusted raw JSON bytes
     * @param identity authenticated identity used only for problem correlation
     * @return strict detached publication
     */
    public MirrorDeploymentIsolationAuthorityKeySetPublication decode(
            byte[] value, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
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
            if (!actual.equals(FIELDS) || FIELDS.stream().anyMatch(field -> root.path(field).isNull())
                    || !MirrorDeploymentIsolationAuthorityKeySetPublication.SCHEMA_VERSION.equals(
                    root.path("schemaVersion").textValue())) {
                throw invalid(identity);
            }
            requireBounds(root, identity);
            return strictMapper.treeToValue(root,
                    MirrorDeploymentIsolationAuthorityKeySetPublication.class);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(identity);
        }
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
            current.node().elements().forEachRemaining(child ->
                    pending.add(new NodeDepth(child, current.depth() + 1)));
        }
    }

    private static IntegrationProblemException invalid(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.MIRROR.AUTHORITY_PUBLICATION_MALFORMED",
                "Authority publication must be bounded, unambiguous, closed v1 JSON.",
                identity.correlationId(), Map.of(
                        "schemaVersion",
                        MirrorDeploymentIsolationAuthorityKeySetPublication.SCHEMA_VERSION,
                        "maximumBytes", MAXIMUM_REQUEST_BYTES,
                        "maximumDepth", MAXIMUM_DEPTH,
                        "maximumNodes", MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
