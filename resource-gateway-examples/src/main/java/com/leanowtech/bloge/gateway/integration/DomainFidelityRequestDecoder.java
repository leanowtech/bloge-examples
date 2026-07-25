package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventoryRegistrationRequest;
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
 * Strict bounded decoder for owner-governed Domain Fidelity commands.
 *
 * <p>Authentication must precede this decoder. It rejects duplicate or unknown fields, missing
 * explicit fields, unsupported versions, trailing content, excessive nesting, and oversized raw
 * or canonical JSON before a command reaches the application service.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class DomainFidelityRequestDecoder {
    /** Largest admitted raw or canonical inventory command. */
    public static final int MAXIMUM_REQUEST_BYTES =
            8 * 1024 * 1024;
    /** Deepest admitted JSON structure. */
    public static final int MAXIMUM_DEPTH = 64;
    /** Largest admitted object, array, and scalar node count. */
    public static final int MAXIMUM_NODES = 100_000;

    private static final Set<String> INVENTORY_FIELDS =
            Set.of(
                    "schemaVersion",
                    "inventoryId",
                    "revision",
                    "expectedPredecessorFingerprint",
                    "domainId",
                    "taxonomyRef",
                    "units",
                    "effectiveAt",
                    "expiresAt");

    private final ObjectMapper strictMapper;

    /**
     * Creates an isolated recursive strict mapper.
     *
     * @param mapper application mapper used only as a configured base
     */
    public DomainFidelityRequestDecoder(
            ObjectMapper mapper) {
        strictMapper = Objects.requireNonNull(
                mapper, "mapper").copy()
                .enable(
                        JsonParser.Feature
                                .STRICT_DUPLICATE_DETECTION)
                .enable(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(
                        DeserializationFeature
                                .FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Decodes one exact inventory-registration command.
     *
     * @param value untrusted raw request bytes
     * @param identity already authenticated request context
     * @return strict command without trusted server fields
     */
    public DomainFidelityInventoryRegistrationRequest
    decodeInventoryRegistration(
            byte[] value,
            IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        if (value == null || value.length == 0
                || value.length > MAXIMUM_REQUEST_BYTES) {
            throw invalid(
                    identity,
                    "Domain Fidelity command exceeds its raw size limit.");
        }
        try {
            JsonNode tree = strictMapper.readTree(value);
            if (tree == null || !tree.isObject()) {
                throw invalid(
                        identity,
                        "Domain Fidelity command must be a JSON object.");
            }
            HashSet<String> actual = new HashSet<>();
            tree.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(INVENTORY_FIELDS)
                    || INVENTORY_FIELDS.stream().anyMatch(
                    field -> tree.path(field).isMissingNode())
                    || !DomainFidelityInventoryRegistrationRequest
                    .SCHEMA_VERSION.equals(
                            tree.path("schemaVersion")
                                    .textValue())) {
                throw invalid(
                        identity,
                        "Domain Fidelity command must contain exactly one supported field set.");
            }
            requireStructuralBounds(tree, identity);
            if (strictMapper.writeValueAsBytes(tree).length
                    > MAXIMUM_REQUEST_BYTES) {
                throw invalid(
                        identity,
                        "Domain Fidelity command exceeds its canonical size limit.");
            }
            return strictMapper.treeToValue(
                    tree,
                    DomainFidelityInventoryRegistrationRequest.class);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException
                 | IllegalArgumentException malformed) {
            throw invalid(
                    identity,
                    "Domain Fidelity command does not match the strict public protocol.");
        }
    }

    private static void requireStructuralBounds(
            JsonNode root,
            IntegrationRequestContext identity) {
        ArrayDeque<NodeDepth> pending =
                new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeLast();
            nodes++;
            if (nodes > MAXIMUM_NODES
                    || current.depth() > MAXIMUM_DEPTH) {
                throw invalid(
                        identity,
                        "Domain Fidelity command exceeds its structural limits.");
            }
            current.node().elements()
                    .forEachRemaining(child ->
                            pending.add(
                                    new NodeDepth(
                                            child,
                                            current.depth()
                                                    + 1)));
        }
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.badRequest(
                        "RG.MIRROR.FIDELITY.REQUEST_MALFORMED",
                        title,
                        identity.correlationId(),
                        Map.of(
                                "schemaVersion",
                                DomainFidelityInventoryRegistrationRequest
                                        .SCHEMA_VERSION,
                                "maximumBytes",
                                MAXIMUM_REQUEST_BYTES,
                                "maximumDepth",
                                MAXIMUM_DEPTH,
                                "maximumNodes",
                                MAXIMUM_NODES)));
    }

    private record NodeDepth(
            JsonNode node, int depth) {
    }
}
