package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies acceptance-engine-v1 authority resource fingerprints.
 *
 * <p>Two Domain2 constructions are used:
 * <ul>
 *   <li><strong>Domain2 raw</strong>       = SHA256( UTF8(domain) || I32BE(len) || exact bytes )</li>
 *   <li><strong>Domain2 canonical</strong> = SHA256( UTF8(domain) || I32BE(len) || Jackson-canonical JSON )</li>
 * </ul>
 *
 * <p>Catalog raw and profile raw: Domain2 on the exact unmodified file bytes.
 * Semantic and primitive-registry: Domain2 on Jackson-canonical JSON (sorted keys,
 * full wire fidelity including nulls, no extra whitespace).
 * No production fingerprint helper is reused.
 */
class CapabilityStudioAcceptanceAuthorityFingerprintTest {

    // Authority resource paths
    private static final String P_CATALOG = "/acceptance-engine-v1/builtin-contract-catalog.json";
    private static final String P_PROFILE = "/acceptance-engine-v1/builtin-compiler-profile-formal-v1.json";
    private static final String P_PLAN    = "/acceptance-engine-v1/rg-cs-felt-v1.acceptance.plan.json";

    // Goldens
    private static final String GOLDEN_CATALOG_RAW           = "sha256:b14c3ee599a87e0c10a94f4e0237455bcae93ff2c9fcc8a6a82ab9145942990c";
    private static final String GOLDEN_CATALOG_SEMANTIC      = "sha256:f20774c84f7f34cb0f95c9bb6f5061e048d94b2463da3ca20b560e338cdb7b4d";
    private static final String GOLDEN_PRIMITIVE_REGISTRY   = "sha256:15ea38ddeda10a7befb280efc4fdefb74503036cc06d55775da09665ae4c2686";
    private static final String GOLDEN_PROFILE_RAW          = "sha256:1bf4cc98feeb3c12ed413d50240f0799385efdb755139ba0375200096a672337";

    // Domain strings for Domain2
    private static final String D_CATALOG_RAW         = "RG-CS-CATALOG-RAW-v1";
    private static final String D_CATALOG_SEMANTIC   = "RG-CS-CATALOG-SEMANTIC-v1";
    private static final String D_PRIMITIVE_REGISTRY = "RG-CS-PRIMITIVE-REGISTRY-v1";
    private static final String D_PROFILE_RAW         = "RG-CS-COMPILER-PROFILE-RAW-v1";

    // Jackson with strict duplicate detection, keys sorted, nulls preserved
    private static final ObjectMapper CANON_JSON =
            new ObjectMapper(
                    JsonFactory.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .build())
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static byte[] loadResource(String path) {
        try (var in = CapabilityStudioAcceptanceAuthorityFingerprintTest.class.getResourceAsStream(path)) {
            if (in == null) throw new AssertionError("Missing: " + path);
            return in.readAllBytes();
        } catch (IOException e) { throw new AssertionError("Read error: " + path, e); }
    }

    /** Domain2 on exact raw bytes. */
    static String domain2Raw(String domain, byte[] payload) {
        byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
        byte[] lenBytes   = ByteBuffer.allocate(4).putInt(payload.length).array();
        var baos = new ByteArrayOutputStream(domainBytes.length + 4 + payload.length);
        try { baos.write(domainBytes); baos.write(lenBytes); baos.write(payload); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
        return sha256(baos.toByteArray());
    }

    /** Domain2 on Jackson-canonical JSON of an arbitrary value. */
    static String domain2Canonical(String domain, Object value) {
        try { return domain2Raw(domain, CANON_JSON.writeValueAsBytes(value)); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private static String sha256(byte[] bytes) {
        try { return "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new AssertionError("SHA-256 unavailable", e); }
    }

    private static String hex(byte[] b) {
        var sb = new StringBuilder(b.length * 2);
        for (byte v : b) { sb.append(Character.forDigit((v >>> 4) & 0xf, 16)); sb.append(Character.forDigit(v & 0xf, 16)); }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> canonicalMap(byte[] bytes) {
        try { return CANON_JSON.readValue(bytes, Map.class); }
        catch (IOException e) { throw new AssertionError("JSON parse failed", e); }
    }

    /**
     * Normalize catalog per §7.3:
     * - Sort stageExitContracts, acStandards, feltObligations arrays by contractId.
     * - For each contract entry, sort evidenceRoles/ownerRoles/externalFactRequirements ascending.
     * - Sort root-level canonicalCases by canonicalCaseId.
     * - Sort root-level suiteRuns by suiteRunId.
     * - Sort root-level matrixCells by matrixCellId.
     * Deep-copy so the original is untouched.
     */
    @SuppressWarnings("unchecked")
    private static Map<String,Object> normalizeCatalog(Map<String,Object> raw) {
        Map<String,Object> out = new java.util.LinkedHashMap<>(raw);
        // Sort stageExitContracts by contractId
        List<Map<String,Object>> sec = new ArrayList<>();
        for (Map<String,Object> c : (List<Map<String,Object>>) out.get("stageExitContracts"))
            sec.add(normalizeContract(c));
        sec.sort(Comparator.comparing(m -> (String) m.get("contractId")));
        out.put("stageExitContracts", sec);
        // Sort acStandards by contractId
        List<Map<String,Object>> acs = new ArrayList<>();
        for (Map<String,Object> c : (List<Map<String,Object>>) out.get("acStandards"))
            acs.add(normalizeContract(c));
        acs.sort(Comparator.comparing(m -> (String) m.get("contractId")));
        out.put("acStandards", acs);
        // Sort feltObligations by contractId
        List<Map<String,Object>> fo = new ArrayList<>();
        for (Map<String,Object> c : (List<Map<String,Object>>) out.get("feltObligations"))
            fo.add(normalizeContract(c));
        fo.sort(Comparator.comparing(m -> (String) m.get("contractId")));
        out.put("feltObligations", fo);
        // Sort canonicalCases by canonicalCaseId
        List<Map<String,Object>> cases = new ArrayList<>();
        for (Map<String,Object> c : (List<Map<String,Object>>) out.get("canonicalCases"))
            cases.add(new java.util.LinkedHashMap<>(c));
        cases.sort(Comparator.comparing(m -> (String) m.get("canonicalCaseId")));
        out.put("canonicalCases", cases);
        // Sort suiteRuns by suiteRunId
        List<Map<String,Object>> runs = new ArrayList<>();
        for (Map<String,Object> r : (List<Map<String,Object>>) out.get("suiteRuns"))
            runs.add(new java.util.LinkedHashMap<>(r));
        runs.sort(Comparator.comparing(m -> (String) m.get("suiteRunId")));
        out.put("suiteRuns", runs);
        // Sort matrixCells by matrixCellId
        List<Map<String,Object>> cells = new ArrayList<>();
        for (Map<String,Object> cell : (List<Map<String,Object>>) out.get("matrixCells"))
            cells.add(new java.util.LinkedHashMap<>(cell));
        cells.sort(Comparator.comparing(m -> (String) m.get("matrixCellId")));
        out.put("matrixCells", cells);
        return out;
    }

    /** Normalize one contract entry: sort evidenceRoles, ownerRoles, externalFactRequirements. */
    @SuppressWarnings("unchecked")
    private static Map<String,Object> normalizeContract(Map<String,Object> c) {
        Map<String,Object> out = new java.util.LinkedHashMap<>(c);
        if (out.containsKey("evidenceRoles")) {
            List<String> sorted = new ArrayList<>((List<String>) out.get("evidenceRoles"));
            Collections.sort(sorted);
            out.put("evidenceRoles", sorted);
        }
        if (out.containsKey("ownerRoles")) {
            List<String> sorted = new ArrayList<>((List<String>) out.get("ownerRoles"));
            Collections.sort(sorted);
            out.put("ownerRoles", sorted);
        }
        if (out.containsKey("externalFactRequirements")) {
            List<String> sorted = new ArrayList<>((List<String>) out.get("externalFactRequirements"));
            Collections.sort(sorted);
            out.put("externalFactRequirements", sorted);
        }
        return out;
    }

    // ----------------------------------------------------------------------
    // Tests
    // ----------------------------------------------------------------------

    @Test void allThreeResourcesAreOnClasspath() {
        assertThat(loadResource(P_CATALOG)).isNotEmpty();
        assertThat(loadResource(P_PROFILE)).isNotEmpty();
        assertThat(loadResource(P_PLAN)).isNotEmpty();
    }

    @Test void catalogRawFingerprintMatchesGolden() {
        // Domain2 on exact catalog bytes (not canonical JSON)
        assertThat(domain2Raw(D_CATALOG_RAW, loadResource(P_CATALOG))).isEqualTo(GOLDEN_CATALOG_RAW);
    }

    @Test void catalogSemanticFingerprintMatchesProfileExpectedThenFixedGolden() {
        byte[] catBytes = loadResource(P_CATALOG);
        Map<String,Object> catalog = canonicalMap(catBytes);
        Map<String,Object> normalizedCatalog = normalizeCatalog(catalog);
        Map<String,Object> profile = canonicalMap(loadResource(P_PROFILE));

        // Step 1: normalized catalog fingerprint equals the profile field
        String expectedFromField = (String) profile.get("expectedCatalogSemanticFingerprint");
        assertThat(domain2Canonical(D_CATALOG_SEMANTIC, normalizedCatalog))
            .isEqualTo(expectedFromField);

        // Step 2: profile field equals authoritative golden
        assertThat(expectedFromField).isEqualTo(GOLDEN_CATALOG_SEMANTIC);
    }

    @Test void primitiveRegistryFingerprintMatchesProfileExpectedThenFixedGolden() {
        Map<String,Object> profile = canonicalMap(loadResource(P_PROFILE));

        @SuppressWarnings("unchecked")
        List<Map<String,Object>> unsorted = (List<Map<String,Object>>) profile.get("primitiveDescriptors");

        // Sort descriptors by typeId (lexicographic)
        List<Map<String,Object>> sorted = new ArrayList<>(unsorted);
        sorted.sort(Comparator.comparing(m -> (String) m.get("typeId")));

        // Per §7.3: sort inputKinds, outputEvidenceKinds, capabilityRequirements in each descriptor
        List<Map<String,Object>> normalized = new ArrayList<>();
        for (Map<String,Object> desc : sorted) {
            Map<String,Object> nd = new java.util.LinkedHashMap<>(desc);
            nd.put("inputKinds", sortStringList(nd.get("inputKinds")));
            nd.put("outputEvidenceKinds", sortStringList(nd.get("outputEvidenceKinds")));
            nd.put("capabilityRequirements", sortStringList(nd.get("capabilityRequirements")));
            normalized.add(nd);
        }

        // Step 1: computed equals the profile field
        String expectedFromField = (String) profile.get("expectedPrimitiveRegistryFingerprint");
        assertThat(domain2Canonical(D_PRIMITIVE_REGISTRY, normalized))
            .isEqualTo(expectedFromField);

        // Step 2: field equals authoritative golden
        assertThat(expectedFromField).isEqualTo(GOLDEN_PRIMITIVE_REGISTRY);
    }

    @SuppressWarnings("unchecked")
    private static List<String> sortStringList(Object list) {
        List<String> sorted = new ArrayList<>((List<String>) list);
        Collections.sort(sorted);
        return sorted;
    }

    @Test void profileRawFingerprintMatchesGolden() {
        // Domain2 on exact profile bytes (no parse, no canonicalize)
        assertThat(domain2Raw(D_PROFILE_RAW, loadResource(P_PROFILE))).isEqualTo(GOLDEN_PROFILE_RAW);
    }

    @Test void planCatalogRefEmbeddedHashMatchesDomain2CatalogRaw() {
        byte[] catBytes  = loadResource(P_CATALOG);
        byte[] planBytes = loadResource(P_PLAN);
        Map<String,Object> plan = canonicalMap(planBytes);

        String catalogRef = (String) plan.get("catalogRef");
        int at = catalogRef.indexOf('@');
        if (at < 0) throw new AssertionError("catalogRef missing @: " + catalogRef);
        String embeddedHash = catalogRef.substring(at + 1);

        // plan.catalogRef @-suffix equals Domain2(catalog raw)
        assertThat(embeddedHash).isEqualTo(domain2Raw(D_CATALOG_RAW, catBytes));
    }

    @Test void planCompilerProfileMatchesProfileProfileId() {
        Map<String,Object> plan    = canonicalMap(loadResource(P_PLAN));
        Map<String,Object> prof   = canonicalMap(loadResource(P_PROFILE));
        assertThat(plan.get("compilerProfile")).isEqualTo(prof.get("profileId"));
    }

    @Test void planCatalogIdMatchesCatalogCatalogId() {
        Map<String,Object> plan    = canonicalMap(loadResource(P_PLAN));
        Map<String,Object> catalog = canonicalMap(loadResource(P_CATALOG));
        assertThat(plan.get("catalogId")).isEqualTo(catalog.get("catalogId"));
    }

    @Test void catalogRefPrefixMatchesCatalogId() {
        Map<String,Object> plan = canonicalMap(loadResource(P_PLAN));
        String catalogId  = (String) plan.get("catalogId");
        String catalogRef = (String) plan.get("catalogRef");
        assertThat(catalogRef).startsWith(catalogId + "@");
    }

    @Test void allThreeResourcesHaveCorrectSchemaVersion() {
        Map<String,Object> catalog = canonicalMap(loadResource(P_CATALOG));
        Map<String,Object> profile = canonicalMap(loadResource(P_PROFILE));
        Map<String,Object> plan    = canonicalMap(loadResource(P_PLAN));
        assertThat(catalog.get("schemaVersion")).isEqualTo("bloge.capability-studio.contract-catalog.v1");
        assertThat(profile.get("schemaVersion")).isEqualTo("bloge.capability-studio.compiler-profile-formal.v1");
        assertThat(plan.get("schemaVersion")).isEqualTo("bloge.capability-studio.acceptance-plan.v1");
    }
}
