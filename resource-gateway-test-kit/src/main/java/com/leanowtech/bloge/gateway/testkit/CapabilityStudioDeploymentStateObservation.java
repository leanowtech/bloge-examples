package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict payload-free observation of one already initialized deployment lease store.
 *
 * <p>The state-material fingerprint excludes {@link Phase}; equal store bytes therefore have one
 * material identity before and after an attempt. The observation fingerprint includes the phase.
 * Neither fingerprint authenticates the store. A deployment Authority must bind and retain the
 * observation as evidence outside the observed state root.</p>
 */
public final class CapabilityStudioDeploymentStateObservation {
    /** Fixed wire and canonical observation version. */
    public static final String MESSAGE_VERSION =
            "resource-gateway.capability-studio.deployment-state-observation.v1";
    /** Fixed phase-independent state-material fingerprint domain. */
    public static final String STATE_MATERIAL_MESSAGE_VERSION =
            "resource-gateway.capability-studio.deployment-state-material.v1";
    /** Maximum accepted encoded observation size. */
    public static final int MAXIMUM_BYTES = 64 * 1024;
    /** Maximum lease count represented by the aggregate inventory coordinate. */
    public static final int MAXIMUM_LEASES = 1024;

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Set<String> FIELDS = Set.of(
            "messageVersion", "phase", "evidenceTransactionId",
            "storeDescriptorFingerprint", "storeDescriptorRawFingerprint", "generation",
            "previousStateFingerprint", "stateFingerprint", "stateRawFingerprint",
            "checkpointFingerprint", "checkpointRawFingerprint", "revocationHeadSequence",
            "revocationHeadFingerprint", "revocationHeadRawFingerprint",
            "lifecycleHeadFingerprint", "fencingSequence", "leaseCount",
            "leaseInventoryFingerprint", "stateMaterialFingerprint",
            "observationFingerprint");

    private CapabilityStudioDeploymentStateObservation() {
    }

    /** Closed observation position around one formal attempt. */
    public enum Phase {
        /** Observation captured before formal acceptance. */
        BEFORE,
        /** Observation captured after formal acceptance. */
        AFTER
    }

    /**
     * Immutable deployment-state observation.
     *
     * @param messageVersion fixed v1 wire version
     * @param phase before or after phase
     * @param evidenceTransactionId stable evidence attempt identity
     * @param storeDescriptorFingerprint immutable store descriptor coordinate
     * @param storeDescriptorRawFingerprint exact store descriptor document fingerprint
     * @param generation durable state generation
     * @param previousStateFingerprint prior state fingerprint, or null at genesis
     * @param stateFingerprint canonical state fingerprint
     * @param stateRawFingerprint exact state document fingerprint
     * @param checkpointFingerprint canonical checkpoint fingerprint
     * @param checkpointRawFingerprint exact checkpoint document fingerprint
     * @param revocationHeadSequence durable revocation-head sequence
     * @param revocationHeadFingerprint canonical revocation-head fingerprint
     * @param revocationHeadRawFingerprint exact revocation-head document fingerprint
     * @param lifecycleHeadFingerprint current lifecycle material fingerprint, or null at genesis
     * @param fencingSequence durable lease fencing sequence
     * @param leaseCount number of durable leases
     * @param leaseInventoryFingerprint sorted payload-free lease inventory fingerprint
     * @param stateMaterialFingerprint phase-independent aggregate fingerprint
     * @param observationFingerprint phase-bound observation fingerprint
     */
    public record Observation(
            String messageVersion,
            Phase phase,
            String evidenceTransactionId,
            String storeDescriptorFingerprint,
            String storeDescriptorRawFingerprint,
            long generation,
            String previousStateFingerprint,
            String stateFingerprint,
            String stateRawFingerprint,
            String checkpointFingerprint,
            String checkpointRawFingerprint,
            long revocationHeadSequence,
            String revocationHeadFingerprint,
            String revocationHeadRawFingerprint,
            String lifecycleHeadFingerprint,
            long fencingSequence,
            int leaseCount,
            String leaseInventoryFingerprint,
            String stateMaterialFingerprint,
            String observationFingerprint) {
        /** Validates every bounded coordinate and both canonical fingerprints. */
        public Observation {
            if (!MESSAGE_VERSION.equals(messageVersion) || phase == null
                    || generation < 0 || revocationHeadSequence < 0
                    || fencingSequence < 0 || leaseCount < 0 || leaseCount > MAXIMUM_LEASES) {
                throw invalid();
            }
            requireFingerprint(evidenceTransactionId);
            requireFingerprint(storeDescriptorFingerprint);
            requireFingerprint(storeDescriptorRawFingerprint);
            requireNullableFingerprint(previousStateFingerprint);
            requireFingerprint(stateFingerprint);
            requireFingerprint(stateRawFingerprint);
            requireFingerprint(checkpointFingerprint);
            requireFingerprint(checkpointRawFingerprint);
            requireFingerprint(revocationHeadFingerprint);
            requireFingerprint(revocationHeadRawFingerprint);
            requireNullableFingerprint(lifecycleHeadFingerprint);
            requireFingerprint(leaseInventoryFingerprint);
            String expectedMaterial = CapabilityStudioDeploymentStateObservation
                    .stateMaterialFingerprint(
                    storeDescriptorFingerprint, storeDescriptorRawFingerprint,
                    generation, previousStateFingerprint,
                    stateFingerprint, stateRawFingerprint, checkpointFingerprint,
                    checkpointRawFingerprint, revocationHeadSequence,
                    revocationHeadFingerprint, revocationHeadRawFingerprint,
                    lifecycleHeadFingerprint, fencingSequence, leaseCount,
                    leaseInventoryFingerprint);
            if (!expectedMaterial.equals(stateMaterialFingerprint)) {
                throw invalid();
            }
            String expectedObservation = CapabilityStudioDeploymentStateObservation
                    .observationFingerprint(phase, evidenceTransactionId,
                    storeDescriptorFingerprint, storeDescriptorRawFingerprint,
                    generation, previousStateFingerprint,
                    stateFingerprint, stateRawFingerprint, checkpointFingerprint,
                    checkpointRawFingerprint, revocationHeadSequence,
                    revocationHeadFingerprint, revocationHeadRawFingerprint,
                    lifecycleHeadFingerprint, fencingSequence, leaseCount,
                    leaseInventoryFingerprint, stateMaterialFingerprint);
            if (!expectedObservation.equals(observationFingerprint)) {
                throw invalid();
            }
        }

        /**
         * Returns strict canonical UTF-8 wire bytes.
         *
         * @return defensive canonical wire bytes
         */
        public byte[] bytes() {
            byte[] bytes = write(observationNode(this, observationFingerprint));
            if (bytes.length > MAXIMUM_BYTES
                    || !CapabilityStudioSchemaSupport.validate(
                    read(bytes), CapabilityStudioSchemaSupport
                            .DEPLOYMENT_STATE_OBSERVATION_V1_RESOURCE).isEmpty()) {
                throw invalid();
            }
            return bytes;
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "Observation[phase=" + phase + ", material=REDACTED]";
        }
    }

    /**
     * Creates a fully fingerprinted observation from exact existing-store coordinates.
     *
     * @param phase observation phase
     * @param evidenceTransactionId stable evidence transaction identity
     * @param storeDescriptorFingerprint immutable store descriptor fingerprint
     * @param storeDescriptorRawFingerprint exact descriptor bytes fingerprint
     * @param generation durable state generation
     * @param previousStateFingerprint previous state fingerprint, or null
     * @param stateFingerprint canonical state fingerprint
     * @param stateRawFingerprint exact state bytes fingerprint
     * @param checkpointFingerprint canonical checkpoint fingerprint
     * @param checkpointRawFingerprint exact checkpoint bytes fingerprint
     * @param revocationHeadSequence durable revocation-head sequence
     * @param revocationHeadFingerprint canonical revocation-head fingerprint
     * @param revocationHeadRawFingerprint exact revocation-head bytes fingerprint
     * @param lifecycleHeadFingerprint lifecycle material fingerprint, or null
     * @param fencingSequence durable fencing sequence
     * @param leaseCount durable lease count
     * @param leaseInventoryFingerprint sorted lease inventory fingerprint
     * @return validated immutable observation
     */
    public static Observation create(
            Phase phase,
            String evidenceTransactionId,
            String storeDescriptorFingerprint,
            String storeDescriptorRawFingerprint,
            long generation,
            String previousStateFingerprint,
            String stateFingerprint,
            String stateRawFingerprint,
            String checkpointFingerprint,
            String checkpointRawFingerprint,
            long revocationHeadSequence,
            String revocationHeadFingerprint,
            String revocationHeadRawFingerprint,
            String lifecycleHeadFingerprint,
            long fencingSequence,
            int leaseCount,
            String leaseInventoryFingerprint) {
        String material = stateMaterialFingerprint(storeDescriptorFingerprint,
                storeDescriptorRawFingerprint, generation,
                previousStateFingerprint, stateFingerprint, stateRawFingerprint,
                checkpointFingerprint, checkpointRawFingerprint, revocationHeadSequence,
                revocationHeadFingerprint, revocationHeadRawFingerprint,
                lifecycleHeadFingerprint, fencingSequence, leaseCount,
                leaseInventoryFingerprint);
        String observation = observationFingerprint(phase, evidenceTransactionId,
                storeDescriptorFingerprint,
                storeDescriptorRawFingerprint, generation, previousStateFingerprint,
                stateFingerprint, stateRawFingerprint,
                checkpointFingerprint, checkpointRawFingerprint, revocationHeadSequence,
                revocationHeadFingerprint, revocationHeadRawFingerprint,
                lifecycleHeadFingerprint, fencingSequence, leaseCount,
                leaseInventoryFingerprint, material);
        return new Observation(MESSAGE_VERSION, phase, evidenceTransactionId,
                storeDescriptorFingerprint, storeDescriptorRawFingerprint,
                generation, previousStateFingerprint, stateFingerprint, stateRawFingerprint,
                checkpointFingerprint, checkpointRawFingerprint, revocationHeadSequence,
                revocationHeadFingerprint, revocationHeadRawFingerprint,
                lifecycleHeadFingerprint, fencingSequence, leaseCount,
                leaseInventoryFingerprint, material, observation);
    }

    /**
     * Strictly parses and independently verifies one observation.
     *
     * @param bytes exact bounded UTF-8 wire bytes
     * @return verified immutable observation
     */
    public static Observation verify(byte[] bytes) {
        if (bytes == null || bytes.length < 1 || bytes.length > MAXIMUM_BYTES) {
            throw invalid();
        }
        JsonNode parsed = read(bytes);
        if (!CapabilityStudioSchemaSupport.validate(parsed,
                CapabilityStudioSchemaSupport.DEPLOYMENT_STATE_OBSERVATION_V1_RESOURCE)
                .isEmpty() || !(parsed instanceof ObjectNode node)
                || !fieldNames(node).equals(FIELDS)) {
            throw invalid();
        }
        try {
            Observation observation = new Observation(
                    text(node, "messageVersion"), Phase.valueOf(text(node, "phase")),
                    fingerprintText(node, "evidenceTransactionId"),
                    text(node, "storeDescriptorFingerprint"),
                    text(node, "storeDescriptorRawFingerprint"), number(node, "generation"),
                    nullableText(node, "previousStateFingerprint"),
                    text(node, "stateFingerprint"), text(node, "stateRawFingerprint"),
                    text(node, "checkpointFingerprint"),
                    text(node, "checkpointRawFingerprint"),
                    number(node, "revocationHeadSequence"),
                    text(node, "revocationHeadFingerprint"),
                    text(node, "revocationHeadRawFingerprint"),
                    nullableText(node, "lifecycleHeadFingerprint"),
                    number(node, "fencingSequence"),
                    Math.toIntExact(number(node, "leaseCount")),
                    text(node, "leaseInventoryFingerprint"),
                    text(node, "stateMaterialFingerprint"),
                    text(node, "observationFingerprint"));
            if (!java.util.Arrays.equals(bytes, observation.bytes())) {
                throw invalid();
            }
            return observation;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * Returns the fixed canonical phase-independent material message.
     *
     * @param storeDescriptorFingerprint immutable store descriptor fingerprint
     * @param storeDescriptorRawFingerprint exact descriptor bytes fingerprint
     * @param generation durable state generation
     * @param previousStateFingerprint previous state fingerprint, or null
     * @param stateFingerprint canonical state fingerprint
     * @param stateRawFingerprint exact state bytes fingerprint
     * @param checkpointFingerprint canonical checkpoint fingerprint
     * @param checkpointRawFingerprint exact checkpoint bytes fingerprint
     * @param revocationHeadSequence durable revocation-head sequence
     * @param revocationHeadFingerprint canonical revocation-head fingerprint
     * @param revocationHeadRawFingerprint exact revocation-head bytes fingerprint
     * @param lifecycleHeadFingerprint lifecycle material fingerprint, or null
     * @param fencingSequence durable fencing sequence
     * @param leaseCount durable lease count
     * @param leaseInventoryFingerprint sorted lease inventory fingerprint
     * @return fixed canonical JSON message
     */
    public static String stateMaterialCanonicalMessage(
            String storeDescriptorFingerprint,
            String storeDescriptorRawFingerprint,
            long generation,
            String previousStateFingerprint,
            String stateFingerprint,
            String stateRawFingerprint,
            String checkpointFingerprint,
            String checkpointRawFingerprint,
            long revocationHeadSequence,
            String revocationHeadFingerprint,
            String revocationHeadRawFingerprint,
            String lifecycleHeadFingerprint,
            long fencingSequence,
            int leaseCount,
            String leaseInventoryFingerprint) {
        validateMaterial(storeDescriptorFingerprint, storeDescriptorRawFingerprint,
                generation, previousStateFingerprint,
                stateFingerprint, stateRawFingerprint, checkpointFingerprint,
                checkpointRawFingerprint, revocationHeadSequence,
                revocationHeadFingerprint, revocationHeadRawFingerprint,
                lifecycleHeadFingerprint, fencingSequence, leaseCount,
                leaseInventoryFingerprint);
        return new String(write(materialNode(storeDescriptorFingerprint,
                storeDescriptorRawFingerprint, generation,
                previousStateFingerprint, stateFingerprint, stateRawFingerprint,
                checkpointFingerprint, checkpointRawFingerprint, revocationHeadSequence,
                revocationHeadFingerprint, revocationHeadRawFingerprint,
                lifecycleHeadFingerprint, fencingSequence, leaseCount,
                leaseInventoryFingerprint)), StandardCharsets.UTF_8);
    }

    /**
     * Returns the deterministic phase-independent material fingerprint.
     *
     * @param storeDescriptorFingerprint immutable store descriptor fingerprint
     * @param storeDescriptorRawFingerprint exact descriptor bytes fingerprint
     * @param generation durable state generation
     * @param previousStateFingerprint previous state fingerprint, or null
     * @param stateFingerprint canonical state fingerprint
     * @param stateRawFingerprint exact state bytes fingerprint
     * @param checkpointFingerprint canonical checkpoint fingerprint
     * @param checkpointRawFingerprint exact checkpoint bytes fingerprint
     * @param revocationHeadSequence durable revocation-head sequence
     * @param revocationHeadFingerprint canonical revocation-head fingerprint
     * @param revocationHeadRawFingerprint exact revocation-head bytes fingerprint
     * @param lifecycleHeadFingerprint lifecycle material fingerprint, or null
     * @param fencingSequence durable fencing sequence
     * @param leaseCount durable lease count
     * @param leaseInventoryFingerprint sorted lease inventory fingerprint
     * @return lowercase SHA-256 material fingerprint
     */
    public static String stateMaterialFingerprint(
            String storeDescriptorFingerprint,
            String storeDescriptorRawFingerprint,
            long generation,
            String previousStateFingerprint,
            String stateFingerprint,
            String stateRawFingerprint,
            String checkpointFingerprint,
            String checkpointRawFingerprint,
            long revocationHeadSequence,
            String revocationHeadFingerprint,
            String revocationHeadRawFingerprint,
            String lifecycleHeadFingerprint,
            long fencingSequence,
            int leaseCount,
            String leaseInventoryFingerprint) {
        return sha256(stateMaterialCanonicalMessage(storeDescriptorFingerprint,
                storeDescriptorRawFingerprint, generation,
                previousStateFingerprint, stateFingerprint, stateRawFingerprint,
                checkpointFingerprint, checkpointRawFingerprint, revocationHeadSequence,
                revocationHeadFingerprint, revocationHeadRawFingerprint,
                lifecycleHeadFingerprint, fencingSequence, leaseCount,
                leaseInventoryFingerprint).getBytes(StandardCharsets.UTF_8));
    }

    private static String observationFingerprint(
            Phase phase, String evidenceTransactionId,
            String storeDescriptorFingerprint, String storeDescriptorRawFingerprint,
            long generation,
            String previousStateFingerprint, String stateFingerprint,
            String stateRawFingerprint, String checkpointFingerprint,
            String checkpointRawFingerprint, long revocationHeadSequence,
            String revocationHeadFingerprint, String revocationHeadRawFingerprint,
            String lifecycleHeadFingerprint, long fencingSequence, int leaseCount,
            String leaseInventoryFingerprint, String stateMaterialFingerprint) {
        requireFingerprint(evidenceTransactionId);
        return sha256(write(observationNode(phase, evidenceTransactionId,
                storeDescriptorFingerprint, storeDescriptorRawFingerprint, generation,
                previousStateFingerprint, stateFingerprint, stateRawFingerprint,
                checkpointFingerprint, checkpointRawFingerprint, revocationHeadSequence,
                revocationHeadFingerprint, revocationHeadRawFingerprint,
                lifecycleHeadFingerprint, fencingSequence, leaseCount,
                leaseInventoryFingerprint, stateMaterialFingerprint, null)));
    }

    private static ObjectNode materialNode(
            String descriptor, String descriptorRaw, long generation,
            String previous, String state,
            String stateRaw, String checkpoint, String checkpointRaw, long headSequence,
            String head, String headRaw, String lifecycle, long fencing, int leases,
            String inventory) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", STATE_MATERIAL_MESSAGE_VERSION);
        node.put("storeDescriptorFingerprint", descriptor);
        node.put("storeDescriptorRawFingerprint", descriptorRaw);
        node.put("generation", generation);
        putNullable(node, "previousStateFingerprint", previous);
        node.put("stateFingerprint", state);
        node.put("stateRawFingerprint", stateRaw);
        node.put("checkpointFingerprint", checkpoint);
        node.put("checkpointRawFingerprint", checkpointRaw);
        node.put("revocationHeadSequence", headSequence);
        node.put("revocationHeadFingerprint", head);
        node.put("revocationHeadRawFingerprint", headRaw);
        putNullable(node, "lifecycleHeadFingerprint", lifecycle);
        node.put("fencingSequence", fencing);
        node.put("leaseCount", leases);
        node.put("leaseInventoryFingerprint", inventory);
        return node;
    }

    private static ObjectNode observationNode(Observation value, String fingerprint) {
        return observationNode(value.phase(), value.evidenceTransactionId(),
                value.storeDescriptorFingerprint(), value.storeDescriptorRawFingerprint(),
                value.generation(), value.previousStateFingerprint(),
                value.stateFingerprint(), value.stateRawFingerprint(),
                value.checkpointFingerprint(), value.checkpointRawFingerprint(),
                value.revocationHeadSequence(), value.revocationHeadFingerprint(),
                value.revocationHeadRawFingerprint(), value.lifecycleHeadFingerprint(),
                value.fencingSequence(), value.leaseCount(),
                value.leaseInventoryFingerprint(), value.stateMaterialFingerprint(),
                fingerprint);
    }

    private static ObjectNode observationNode(
            Phase phase, String transactionId, String descriptor, String descriptorRaw,
            long generation, String previous,
            String state, String stateRaw, String checkpoint, String checkpointRaw,
            long headSequence, String head, String headRaw, String lifecycle,
            long fencing, int leases, String inventory, String material,
            String fingerprint) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", MESSAGE_VERSION);
        node.put("phase", phase.name());
        node.put("evidenceTransactionId", transactionId);
        node.put("storeDescriptorFingerprint", descriptor);
        node.put("storeDescriptorRawFingerprint", descriptorRaw);
        node.put("generation", generation);
        putNullable(node, "previousStateFingerprint", previous);
        node.put("stateFingerprint", state);
        node.put("stateRawFingerprint", stateRaw);
        node.put("checkpointFingerprint", checkpoint);
        node.put("checkpointRawFingerprint", checkpointRaw);
        node.put("revocationHeadSequence", headSequence);
        node.put("revocationHeadFingerprint", head);
        node.put("revocationHeadRawFingerprint", headRaw);
        putNullable(node, "lifecycleHeadFingerprint", lifecycle);
        node.put("fencingSequence", fencing);
        node.put("leaseCount", leases);
        node.put("leaseInventoryFingerprint", inventory);
        node.put("stateMaterialFingerprint", material);
        putNullable(node, "observationFingerprint", fingerprint);
        return node;
    }

    private static void validateMaterial(
            String descriptor, String descriptorRaw, long generation,
            String previous, String state,
            String stateRaw, String checkpoint, String checkpointRaw, long headSequence,
            String head, String headRaw, String lifecycle, long fencing, int leases,
            String inventory) {
        requireFingerprint(descriptor);
        requireFingerprint(descriptorRaw);
        requireNullableFingerprint(previous);
        requireFingerprint(state);
        requireFingerprint(stateRaw);
        requireFingerprint(checkpoint);
        requireFingerprint(checkpointRaw);
        requireFingerprint(head);
        requireFingerprint(headRaw);
        requireNullableFingerprint(lifecycle);
        requireFingerprint(inventory);
        if (generation < 0 || headSequence < 0 || fencing < 0
                || leases < 0 || leases > MAXIMUM_LEASES) {
            throw invalid();
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void requireFingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw invalid();
        }
    }

    private static void requireNullableFingerprint(String value) {
        if (value != null) {
            requireFingerprint(value);
        }
    }

    private static byte[] write(JsonNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException impossible) {
            throw new IllegalStateException("observation serialization unavailable");
        }
    }

    private static JsonNode read(byte[] bytes) {
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null) {
                throw invalid();
            }
            return node;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    private static Set<String> fieldNames(ObjectNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String nullableText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNull() ? null : text(node, field);
    }

    private static String fingerprintText(ObjectNode node, String field) {
        String value = text(node, field);
        requireFingerprint(value);
        return value;
    }

    private static long number(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        return value.longValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "RG.CAPABILITY_STUDIO.DEPLOYMENT_STATE_OBSERVATION_INVALID");
    }

}
