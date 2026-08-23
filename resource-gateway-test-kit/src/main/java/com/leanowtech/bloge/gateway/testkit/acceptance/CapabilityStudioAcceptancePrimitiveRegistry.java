package com.leanowtech.bloge.gateway.testkit.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.Map.Entry;

/**
 * Immutable primitive registry snapshot built from the packaged compiler profile.
 * Package-private: constructed by CapabilityStudioAcceptancePlanCompiler.
 * <p>
 * Verifies profile integrity and computes the canonical fingerprint that must
 * match the {@code expectedPrimitiveRegistryFingerprint} pin in the profile.
 */
final class CapabilityStudioAcceptancePrimitiveRegistry {

    private final Map<String, PrimitiveDescriptor> byTypeId;
    private final List<String> phaseOrder;
    private final String fingerprint;

    CapabilityStudioAcceptancePrimitiveRegistry(JsonNode profileNode) {
        validateProfileStructure(profileNode);

        // phaseOrder — exactly 10
        ArrayNode phaseOrderArray = (ArrayNode) profileNode.get("phaseOrder");
        this.phaseOrder = new ArrayList<>();
        for (JsonNode p : phaseOrderArray) phaseOrder.add(p.asText());

        // allowedEffectClasses
        Set<String> allowed = new HashSet<>();
        for (JsonNode ec : (ArrayNode) profileNode.get("allowedEffectClasses"))
            allowed.add(ec.asText());

        // barriers — exactly 7, expected IDs
        Map<String, JsonNode> barriers = new LinkedHashMap<>();
        for (JsonNode b : (ArrayNode) profileNode.get("barriers"))
            barriers.put(b.get("barrierId").asText(), b);
        if (barriers.size() != 7)
            bad("/barriers", "Expected 7 barriers, got " + barriers.size());

        // Validate exact 7 ordered barrier IDs per profile frozen order
        List<String> expectedBarrierIds = List.of(
                "PURE_VERIFY_GATE", "LEASE_GATE", "NO_DELETE_AFTER_LEASE",
                "DURABLE_COMMIT_GATE", "STORE_RECEIPT_GATE",
                "OWNER_SIGNOFF_GATE", "NO_ACCEPTED_FROM_LOCAL");
        List<String> actualBarrierIds = new ArrayList<>(barriers.keySet());
        if (!expectedBarrierIds.equals(actualBarrierIds))
            bad("/barriers", "Barrier IDs not in expected order: " + actualBarrierIds);

        // Validate each barrier has non-empty unique phases from phaseOrder
        Set<String> phaseSet = new HashSet<>(phaseOrder);
        for (Map.Entry<String, JsonNode> be : barriers.entrySet()) {
            String bid = be.getKey();
            JsonNode phasesNode = be.getValue().get("phases");
            if (!phasesNode.isArray() || phasesNode.isEmpty())
                bad("/barriers/" + bid, "phases must be non-empty array");
            Set<String> seen = new HashSet<>();
            for (JsonNode pn : phasesNode) {
                String phase = pn.asText();
                if (!phaseSet.contains(phase))
                    bad("/barriers/" + bid, "Unknown phase: " + phase);
                if (!seen.add(phase))
                    bad("/barriers/" + bid, "Duplicate phase in barrier: " + phase);
            }
        }

        // primitiveDescriptors — exactly 8
        ArrayNode descriptors = (ArrayNode) profileNode.get("primitiveDescriptors");
        if (descriptors.size() != 8)
            bad("/primitiveDescriptors", "Expected 8 descriptors, got " + descriptors.size());

        this.byTypeId = new LinkedHashMap<>();
        for (JsonNode d : descriptors) {
            String typeId = d.get("typeId").asText();
            int    rev   = d.get("revision").asInt();
            String ec    = d.get("effectClass").asText();
            String phase = d.get("phase").asText();

            if (!allowed.contains(ec))
                bad("/primitiveDescriptors/" + typeId, "Effect class not allowed: " + ec);
            if (!phaseOrder.contains(phase))
                bad("/primitiveDescriptors/" + typeId, "Phase not in phaseOrder: " + phase);

            byTypeId.put(typeId, new PrimitiveDescriptor(typeId, rev, ec, phase));
        }

        // Compute and verify fingerprint
        this.fingerprint = computeFingerprint(descriptors);
        String expected = profileNode.get("expectedPrimitiveRegistryFingerprint").asText();
        if (!this.fingerprint.equals(expected))
            bad("/primitiveDescriptors",
                    "Registry fingerprint mismatch: expected " + expected + ", got " + this.fingerprint);
    }

    private void validateProfileStructure(JsonNode profileNode) {
        if (!profileNode.has("schemaVersion")
                || !profileNode.get("schemaVersion").asText()
                        .equals("bloge.capability-studio.compiler-profile-formal.v1"))
            bad("/schemaVersion", "Profile schemaVersion mismatch");
        if (!profileNode.has("profileId")
                || !profileNode.get("profileId").asText().equals("formal-evidence-v1"))
            bad("/profileId", "Profile ID mismatch");
        ArrayNode arr = (ArrayNode) profileNode.get("phaseOrder");
        if (arr == null || arr.size() != 10)
            bad("/phaseOrder", "Expected 10 phases, got " + (arr == null ? 0 : arr.size()));
    }

    private void bad(String path, String msg) {
        throw new CompilerException(CompilerException.ReasonCode.INVALID_PLAN_STRUCTURE, path, msg);
    }

    PrimitiveDescriptor get(String typeId) {
        PrimitiveDescriptor d = byTypeId.get(typeId);
        if (d == null)
            throw new CompilerException(CompilerException.ReasonCode.INVALID_REGISTRY_TYPE_NOT_FOUND,
                    "/primitives", "Unknown typeId: " + typeId);
        return d;
    }

    int phaseIndex(String phase) {
        int idx = phaseOrder.indexOf(phase);
        if (idx < 0)
            throw new CompilerException(CompilerException.ReasonCode.INVALID_PLAN_STRUCTURE,
                    "/primitives", "Unknown phase: " + phase);
        return idx;
    }

    String  fingerprint()    { return fingerprint; }
    List<String> phaseOrder() { return Collections.unmodifiableList(phaseOrder); }

    /**
     * Canonical fingerprint: descriptors sorted by typeId,
     * inputKinds/outputEvidenceKinds/capabilityRequirements sorted,
     * deep copy of all fields including failureMapping object,
     * Domain2("RG-CS-PRIMITIVE-REGISTRY-v1", canonical JSON).
     */
    private String computeFingerprint(ArrayNode descriptors) {
        List<JsonNode> sorted = new ArrayList<>();
        descriptors.forEach(sorted::add);
        sorted.sort(Comparator.comparing(d -> d.get("typeId").asText()));

        List<JsonNode> normalised = new ArrayList<>();
        for (JsonNode d : sorted) {
            normalised.add(normaliseDescriptor((ObjectNode) d));
        }

        byte[] bytes;
        try {
            bytes = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER
                    .writeValueAsBytes(normalised);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise registry descriptors", e);
        }
        return Domain2.compute("RG-CS-PRIMITIVE-REGISTRY-v1", bytes);
    }

    /**
     * Normalise a single descriptor:
     * - Deep copy all fields
     * - Sort inputKinds, outputEvidenceKinds, capabilityRequirements arrays
     * - Preserve failureMapping as complete deep copy (no asText on object)
     */
    private ObjectNode normaliseDescriptor(ObjectNode d) {
        ObjectNode out = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createObjectNode();
        List<String> keys = new ArrayList<>();
        d.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());

        for (String k : keys) {
            JsonNode v = d.get(k);
            if (k.equals("inputKinds") || k.equals("outputEvidenceKinds") || k.equals("capabilityRequirements")) {
                // Sort string arrays
                ArrayNode sorted = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createArrayNode();
                List<String> items = new ArrayList<>();
                v.forEach(n -> items.add(n.asText()));
                items.sort(Comparator.naturalOrder());
                for (String s : items) sorted.add(s);
                out.set(k, sorted);
            } else {
                //                 Deep copy all other fields including failureMapping object
                // No asText on non-array nodes - preserve complete structure
                out.set(k, deepCopyNode(v));
            }
        }
        return out;
    }

    /**
     * Recursively deep copy a JsonNode preserving structure.
     */
    private JsonNode deepCopyNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            for (String k : keys) {
                out.set(k, deepCopyNode(node.get(k)));
            }
            return out;
        } else if (node.isArray()) {
            ArrayNode out = CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createArrayNode();
            for (JsonNode e : node) {
                out.add(deepCopyNode(e));
            }
            return out;
        } else if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                return out().numberNode(node.asLong());
            } else {
                return out().numberNode(node.asDouble());
            }
        } else if (node.isBoolean()) {
            return out().booleanNode(node.asBoolean());
        } else if (node.isNull()) {
            return out().nullNode();
        } else {
            return out().textNode(node.asText());
        }
    }

    private ObjectNode out() {
        return CapabilityStudioAcceptancePlanProtocol.CANONICAL_MAPPER.createObjectNode();
    }

    record PrimitiveDescriptor(String typeId, int revision, String effectClass, String phase) {}
}
