package com.leanowtech.bloge.gateway.testkit.acceptance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import com.networknt.schema.dialect.Dialects;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handles JSON parsing (strict duplicate-key), Draft2020-12 schema validation,
 * classpath resource loading, and Domain2 helpers.
 * Package-private: constructed by CapabilityStudioAcceptancePlanCompiler.
 */
final class CapabilityStudioAcceptancePlanProtocol {

    /** Canonical mapper: keys sorted, nulls preserved. */
    static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    private final Map<String, Schema> schemas;
    private final byte[] profileBytes;
    private final JsonNode profileNode;
    private final String profileRawFingerprint;

    CapabilityStudioAcceptancePlanProtocol() {
        this.schemas       = new LinkedHashMap<>();
        this.profileBytes   = loadProfileAndSchemas();
        this.profileNode    = parseStrict(profileBytes, "compiler profile");
        this.profileRawFingerprint = Domain2.compute(
                "RG-CS-COMPILER-PROFILE-RAW-v1", profileBytes);
    }

    // ── Schema + profile loading ─────────────────────────────────────────────

    private byte[] loadProfileAndSchemas() {
        loadSchema(
            "bloge.capability-studio.acceptance-plan.v1",
            "/schemas/resource-gateway-capability-studio/capability-studio-acceptance-plan-v1.schema.json");
        loadSchema(
            "bloge.capability-studio.contract-catalog.v1",
            "/schemas/resource-gateway-capability-studio/capability-studio-contract-catalog-v1.schema.json");
        loadSchema(
            "bloge.capability-studio.compiled-plan.v1",
            "/schemas/resource-gateway-capability-studio/capability-studio-compiled-acceptance-plan-v1.schema.json");
        loadSchema(
            "bloge.capability-studio.compiled-plan-verification-result.v1",
            "/schemas/resource-gateway-capability-studio/capability-studio-compiled-plan-verification-result-v1.schema.json");

        byte[] profile;
        try (InputStream in = getClass().getResourceAsStream(
                "/acceptance-engine-v1/builtin-compiler-profile-formal-v1.json")) {
            if (in == null) throw new IllegalStateException(
                    "Profile not on classpath: /acceptance-engine-v1/builtin-compiler-profile-formal-v1.json");
            profile = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load compiler profile", e);
        }
        return profile;
    }

    private void loadSchema(String key, String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Schema not found: " + path);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SchemaRegistry reg = SchemaRegistry.withDialect(Dialects.getDraft202012());
            Schema schema = reg.getSchema(
                    SchemaLocation.of(path), text, InputFormat.JSON);
            schemas.put(key, schema);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load schema: " + path, e);
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    /**
     * Parse with strict duplicate-key detection.
     * Throws INVALID_PLAN_STRUCTURE on duplicate keys or malformed JSON.
     */
    JsonNode parseStrict(byte[] bytes, String label) {
        try {
            ObjectMapper m = new ObjectMapper(
                    JsonFactory.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .build());
            return m.readTree(bytes);
        } catch (JsonParseException e) {
            throw new CompilerException(CompilerException.ReasonCode.INVALID_PLAN_STRUCTURE,
                    "/", "Invalid JSON in " + label + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Unexpected I/O error parsing " + label, e);
        }
    }

    /** Parse with canonical mapper (keys sorted). */
    JsonNode parseCanonical(byte[] bytes) {
        try { return CANONICAL_MAPPER.readTree(bytes); }
        catch (IOException e) { throw new RuntimeException("Unexpected I/O error", e); }
    }

    // ── Schema validation ───────────────────────────────────────────────────

    void validateSchema(String schemaId, byte[] bytes, String path) {
        Schema schema = schemas.get(schemaId);
        if (schema == null)
            throw new IllegalStateException("Schema not loaded: " + schemaId);
        List<com.networknt.schema.Error> errors = schema.validate(
                new String(bytes, StandardCharsets.UTF_8), InputFormat.JSON,
                context -> context.executionConfig(config -> config.failFast(true)));
        if (!errors.isEmpty()) {
            String first = errors.get(0).getMessage();
            throw new CompilerException(CompilerException.ReasonCode.INVALID_SCHEMA,
                    path, "Schema validation failed: " + first);
        }
    }

    // ── Profile access ──────────────────────────────────────────────────────

    JsonNode getProfileNode() { return profileNode; }

    String getProfileRawFingerprint() { return profileRawFingerprint; }

    String getExpectedCatalogSemanticFingerprint() {
        return profileNode.get("expectedCatalogSemanticFingerprint").asText();
    }

    Schema getCompiledPlanSchema() {
        return schemas.get("bloge.capability-studio.compiled-plan.v1");
    }

    // ── Catalog fingerprint helpers ────────────────────────────────────────────

    String computeCatalogRawFingerprint(byte[] catalogBytes) {
        return Domain2.compute("RG-CS-CATALOG-RAW-v1", catalogBytes);
    }

    /**
     * Compute catalog semantic fingerprint:
     * Domain2("RG-CS-CATALOG-SEMANTIC-v1", canonical JSON of normalised catalog).
     */
    String computeCatalogSemanticFingerprint(JsonNode catalogNode) {
        JsonNode norm = normalizeCatalog(catalogNode);
        // canonicalJsonBytes applies canonicalRebuild which recursively sorts all object keys,
        // including top-level fields, ensuring deterministic JSON canonicalization.
        return Domain2.compute("RG-CS-CATALOG-SEMANTIC-v1", canonicalJsonBytes(norm));
    }

    /**
     * Normalise catalog per §7 canonicalization rules:
     * <ul>
     *   <li>stageExitContracts / acStandards / feltObligations: sorted by contractId;
     *       each contract entry's evidenceRoles / ownerRoles /
     *       externalFactRequirements sorted</li>
     *   <li>canonicalCases: sorted by canonicalCaseId</li>
     *   <li>suiteRuns: sorted by suiteRunId</li>
     *   <li>matrixCells: sorted by matrixCellId</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    JsonNode normalizeCatalog(JsonNode raw) {
        try {
            JsonNode node = parseCanonical(CANONICAL_MAPPER.writeValueAsBytes(raw));
            ObjectNode out = CANONICAL_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey();
                JsonNode v = e.getValue();
                out.set(k, v.isArray() ? normaliseArray(k, (ArrayNode) v) : deepCopy(v));
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Failed to normalise catalog", e);
        }
    }

    private ArrayNode normaliseArray(String key, ArrayNode arr) {
        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        switch (key) {
            case "stageExitContracts":
            case "acStandards":
            case "feltObligations": {
                List<JsonNode> list = new ArrayList<>();
                arr.forEach(list::add);
                list.sort(Comparator.comparing(n -> n.get("contractId").asText()));
                for (JsonNode n : list) result.add(normaliseContract((ObjectNode) n));
                break;
            }
            case "canonicalCases": {
                List<JsonNode> list = new ArrayList<>();
                arr.forEach(list::add);
                list.sort(Comparator.comparing(n -> n.get("canonicalCaseId").asText()));
                for (JsonNode n : list) result.add(deepCopy(n));
                break;
            }
            case "suiteRuns": {
                List<JsonNode> list = new ArrayList<>();
                arr.forEach(list::add);
                list.sort(Comparator.comparing(n -> n.get("suiteRunId").asText()));
                for (JsonNode n : list) result.add(deepCopy(n));
                break;
            }
            case "matrixCells": {
                List<JsonNode> list = new ArrayList<>();
                arr.forEach(list::add);
                list.sort(Comparator.comparing(n -> n.get("matrixCellId").asText()));
                for (JsonNode n : list) result.add(deepCopy(n));
                break;
            }
            default:
                for (JsonNode n : arr) result.add(deepCopy(n));
        }
        return result;
    }

    private ObjectNode normaliseContract(ObjectNode c) {
        ObjectNode out = CANONICAL_MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = c.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (v.isArray() && (k.equals("evidenceRoles")
                    || k.equals("ownerRoles")
                    || k.equals("externalFactRequirements"))) {
                ArrayNode sorted = CANONICAL_MAPPER.createArrayNode();
                List<String> items = new ArrayList<>();
                v.forEach(n -> items.add(n.asText()));
                items.sort(Comparator.naturalOrder());
                for (String s : items) sorted.add(s);
                out.set(k, sorted);
            } else {
                out.set(k, deepCopy(v));
            }
        }
        return out;
    }

    /**
     * Recursive canonical JSON bytes for semantic hashing.
     * Object field names sorted by Java String (UTF-16) order, recursively rebuilt.
     * Arrays preserve current normalised order.
     * Scalars are deep-copied.
     * Output is compact JSON (no pretty-printing).
     */
    byte[] canonicalJsonBytes(JsonNode node) {
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(canonicalRebuild(node));
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialise canonical JSON", e);
        }
    }

    /**
     * Recursively rebuild a JsonNode with:
     * - Object keys sorted by Java String (UTF-16) order
     * - Arrays preserved as-is (after prior normalisation)
     * - Scalars deep-copied
     */
    private JsonNode canonicalRebuild(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = CANONICAL_MAPPER.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            // Java String.compareTo is UTF-16 code unit order
            keys.sort(Comparator.naturalOrder());
            for (String k : keys) {
                out.set(k, canonicalRebuild(node.get(k)));
            }
            return out;
        } else if (node.isArray()) {
            ArrayNode out = CANONICAL_MAPPER.createArrayNode();
            for (JsonNode e : node) {
                out.add(canonicalRebuild(e));
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
            // Textual scalar - deep copy
            return out().textNode(node.asText());
        }
    }

    private ObjectNode out() {
        return CANONICAL_MAPPER.createObjectNode();
    }

    /**
     * Deep copy a JsonNode preserving structure.
     */
    private JsonNode deepCopy(JsonNode node) {
        return canonicalRebuild(node);
    }

    // ── Plan canonicalization ────────────────────────────────────────────────

    /**
     * Compute plan source semantic fingerprint:
     * Domain2("RG-CS-PLAN-SOURCE-SEMANTIC-v1", canonical JSON of plan
     * with primitives sorted by id and dependsOn sorted).
     */
    String computePlanSemanticFingerprint(JsonNode planNode) {
        JsonNode canonical = canonicalizePlan(planNode);
        // canonicalJsonBytes applies canonicalRebuild which recursively sorts all object keys,
        // ensuring deterministic JSON canonicalization independent of SerializationFeature flags.
        return Domain2.compute("RG-CS-PLAN-SOURCE-SEMANTIC-v1", canonicalJsonBytes(canonical));
    }

    /**
     * Deep-copy canonical form of plan:
     * primitives sorted by id ascending; dependsOn sorted within each primitive.
     */
    JsonNode canonicalizePlan(JsonNode planNode) {
        ObjectNode out = CANONICAL_MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = planNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if ("primitives".equals(e.getKey()) && e.getValue().isArray()) {
                out.set("primitives", canonicalizePrimitives((ArrayNode) e.getValue()));
            } else {
                out.set(e.getKey(), deepCopy(e.getValue()));
            }
        }
        return out;
    }

    private ArrayNode canonicalizePrimitives(ArrayNode primitives) {
        List<JsonNode> list = new ArrayList<>();
        primitives.forEach(list::add);
        list.sort(Comparator.comparing(n -> n.get("id").asText()));
        ArrayNode result = CANONICAL_MAPPER.createArrayNode();
        for (JsonNode p : list) {
            ObjectNode sp = CANONICAL_MAPPER.createObjectNode();
            sp.put("id",       p.get("id").asText());
            sp.put("typeId",   p.get("typeId").asText());
            sp.put("revision", p.get("revision").asInt());
            ArrayNode sortedDeps = CANONICAL_MAPPER.createArrayNode();
            List<String> deps = new ArrayList<>();
            p.get("dependsOn").forEach(n -> deps.add(n.asText()));
            deps.sort(Comparator.naturalOrder());
            for (String d : deps) sortedDeps.add(d);
            sp.set("dependsOn", sortedDeps);
            if (p.has("inputSlot")) sp.put("inputSlot", p.get("inputSlot").asText());
            result.add(sp);
        }
        return result;
    }
}
