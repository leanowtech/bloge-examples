package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestation;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationRevocationRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict bounded decoder for attestation ingest and irreversible revocation commands. */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class MirrorDeploymentIsolationAttestationDecoder {
    /** Maximum buffered ingest body, equal to the canonical attestation maximum. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            MirrorDeploymentIsolationAttestationIntegrity.MAXIMUM_ATTESTATION_BYTES;
    /** Maximum buffered revocation command body. */
    public static final int MAXIMUM_REVOCATION_BYTES = 64 * 1024;
    /** Maximum nested JSON depth before mapping. */
    public static final int MAXIMUM_DEPTH = 32;
    /** Maximum object, array, and scalar node count before mapping. */
    public static final int MAXIMUM_NODES = 10_000;

    private static final Set<String> ATTESTATION_FIELDS = Set.of(
            "schemaVersion", "attestationFingerprint", "material", "seal");
    private static final Set<String> REVOCATION_FIELDS = Set.of(
            "schemaVersion", "attestationRevision", "attestationFingerprint",
            "expectedStatusRevision", "expectedStatusFingerprint", "reason");

    private final ObjectMapper strictMapper;

    /**
     * Creates a detached strict mapper from immutable application mapper configuration.
     *
     * @param mapper application protocol mapper
     */
    public MirrorDeploymentIsolationAttestationDecoder(ObjectMapper mapper) {
        strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Decodes one untrusted attestation body after transport authentication.
     *
     * @param value untrusted buffered JSON bytes
     * @param identity authenticated identity used only for problem correlation
     * @return exact closed v1 attestation
     */
    public MirrorDeploymentIsolationAttestation decodeAttestation(
            byte[] value, IntegrationRequestContext identity) {
        JsonNode root = decode(value, MAXIMUM_ATTESTATION_BYTES, ATTESTATION_FIELDS,
                MirrorDeploymentIsolationAttestation.SCHEMA_VERSION, identity);
        try {
            return strictMapper.treeToValue(root, MirrorDeploymentIsolationAttestation.class);
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(identity, "ATTESTATION");
        }
    }

    /**
     * Decodes one untrusted optimistically fenced revocation command.
     *
     * @param value untrusted buffered JSON bytes
     * @param identity authenticated identity used only for problem correlation
     * @return exact closed v1 revocation command
     */
    public MirrorDeploymentIsolationAttestationRevocationRequest decodeRevocation(
            byte[] value, IntegrationRequestContext identity) {
        JsonNode root = decode(value, MAXIMUM_REVOCATION_BYTES, REVOCATION_FIELDS,
                MirrorDeploymentIsolationAttestationRevocationRequest.SCHEMA_VERSION, identity);
        try {
            return strictMapper.treeToValue(
                    root, MirrorDeploymentIsolationAttestationRevocationRequest.class);
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(identity, "REVOCATION");
        }
    }

    private JsonNode decode(
            byte[] value,
            int maximumBytes,
            Set<String> fields,
            String schemaVersion,
            IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        if (value == null || value.length == 0 || value.length > maximumBytes) {
            throw invalid(identity, "REQUEST");
        }
        try {
            JsonNode root = strictMapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw invalid(identity, "REQUEST");
            }
            HashSet<String> actual = new HashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(fields) || fields.stream().anyMatch(
                    field -> root.path(field).isNull())
                    || !schemaVersion.equals(root.path("schemaVersion").textValue())) {
                throw invalid(identity, "REQUEST");
            }
            requireBounds(root, identity);
            return root;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(identity, "REQUEST");
        }
    }

    private static void requireBounds(JsonNode root, IntegrationRequestContext identity) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeLast();
            if (++nodes > MAXIMUM_NODES || current.depth() > MAXIMUM_DEPTH) {
                throw invalid(identity, "REQUEST");
            }
            current.node().elements().forEachRemaining(child ->
                    pending.add(new NodeDepth(child, current.depth() + 1)));
        }
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity, String kind) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.MIRROR.ISOLATION_ATTESTATION_" + kind + "_MALFORMED",
                "Deployment-isolation trust input must be bounded, unambiguous, closed v1 JSON.",
                identity.correlationId(), Map.of(
                        "maximumDepth", MAXIMUM_DEPTH,
                        "maximumNodes", MAXIMUM_NODES)));
    }

    private record NodeDepth(JsonNode node, int depth) {
    }
}
