package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCommandRequest;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCreateRequest;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionProtocolIntegrity;
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
 * Strict bounded decoder for stateful mirror Session create and command requests.
 *
 * <p>Duplicate and unknown typed fields are rejected before service admission. Only schema maps,
 * entity values, expression literals, and command input retain protocol-defined open JSON keys.
 * Spring buffers the body before this decoder, so deployment ingress must still enforce connection
 * and total-body limits.</p>
 */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = {"enabled", "stateful.enabled"},
        havingValue = "true")
public final class MirrorSessionRequestDecoder {
    /** Maximum raw create request including the 256 MiB canonical aggregate. */
    public static final int MAXIMUM_CREATE_BYTES =
            MirrorSessionProtocolIntegrity.MAXIMUM_PAYLOAD_BYTES + 1024 * 1024;
    /** Maximum raw state-transition command. */
    public static final int MAXIMUM_COMMAND_BYTES = 16 * 1024 * 1024;
    /** Maximum admitted JSON nesting depth. */
    public static final int MAXIMUM_DEPTH = 128;
    /** Maximum admitted JSON nodes before typed materialization. */
    public static final int MAXIMUM_NODES = 2_000_000;
    private static final Set<String> CREATE_FIELDS = Set.of(
            "schemaVersion", "requestId", "payload");
    private static final Set<String> COMMAND_FIELDS = Set.of(
            "schemaVersion", "writeEffectRef",
            "expectedStateFingerprint", "input");

    private final ObjectMapper strictMapper;

    /** Creates a duplicate-detecting mapper isolated from application-wide settings. */
    public MirrorSessionRequestDecoder(ObjectMapper mapper) {
        this.strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Decodes one complete sealed session create request. */
    public MirrorSessionCreateRequest decodeCreate(
            byte[] value, IntegrationRequestContext identity) {
        JsonNode root = parse(value, MAXIMUM_CREATE_BYTES, identity, "CREATE");
        requireFields(root, CREATE_FIELDS,
                MirrorSessionCreateRequest.SCHEMA_VERSION, identity, "CREATE");
        try {
            return strictMapper.treeToValue(
                    root, MirrorSessionCreateRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw invalid(identity, "CREATE");
        }
    }

    /** Decodes one complete state-transition command. */
    public MirrorSessionCommandRequest decodeCommand(
            byte[] value, IntegrationRequestContext identity) {
        JsonNode root = parse(value, MAXIMUM_COMMAND_BYTES, identity, "COMMAND");
        requireFields(root, COMMAND_FIELDS,
                MirrorSessionCommandRequest.SCHEMA_VERSION, identity, "COMMAND");
        if (!root.path("input").isObject()) {
            throw invalid(identity, "COMMAND");
        }
        try {
            return strictMapper.treeToValue(
                    root, MirrorSessionCommandRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw invalid(identity, "COMMAND");
        }
    }

    private JsonNode parse(
            byte[] value,
            int maximumBytes,
            IntegrationRequestContext identity,
            String operation) {
        if (value == null || value.length == 0 || value.length > maximumBytes) {
            throw invalid(identity, operation);
        }
        try {
            JsonNode root = strictMapper.readTree(value);
            requireStructuralBounds(root, identity, operation);
            return root;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException malformed) {
            throw invalid(identity, operation);
        }
    }

    private static void requireFields(
            JsonNode root,
            Set<String> expected,
            String schemaVersion,
            IntegrationRequestContext identity,
            String operation) {
        if (root == null || !root.isObject()) {
            throw invalid(identity, operation);
        }
        HashSet<String> actual = new HashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)
                || expected.stream().anyMatch(field -> root.path(field).isNull())
                || !root.path("schemaVersion").isTextual()
                || !schemaVersion.equals(root.path("schemaVersion").textValue())) {
            throw invalid(identity, operation);
        }
    }

    private static void requireStructuralBounds(
            JsonNode root,
            IntegrationRequestContext identity,
            String operation) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeLast();
            nodes++;
            if (nodes > MAXIMUM_NODES || current.depth() > MAXIMUM_DEPTH) {
                throw invalid(identity, operation);
            }
            current.node().elements().forEachRemaining(child ->
                    pending.add(new NodeDepth(
                            child, current.depth() + 1)));
        }
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity, String operation) {
        int maximumBytes = "CREATE".equals(operation)
                ? MAXIMUM_CREATE_BYTES : MAXIMUM_COMMAND_BYTES;
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.MIRROR.SESSION." + operation + "_REQUEST_MALFORMED",
                "The stateful mirror request does not match its strict bounded protocol.",
                identity.correlationId(), Map.of(
                        "maximumBytes", maximumBytes,
                        "maximumDepth", MAXIMUM_DEPTH,
                        "maximumNodes", MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
