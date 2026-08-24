package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concise 9-test suite for ReceiptComposer.
 * Uses direct ValidationSnapshot construction, no temp files.
 */
class CapabilityStudioGateATckProviderReceiptComposerTest {

    private static final CapabilityStudioGateATckProviderRoleSelfTest.TckRoleContract CONTRACT;
    static {
        Path p = Path.of(System.getProperty("user.dir"), "..", "docs", "acceptance",
                "capability-studio", "gate-a-wire-v1", "protocol-compiler",
                "gate-a-protocol-authority-v1.json");
        try {
            CONTRACT = CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(Files.readAllBytes(p));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static byte[] raw(String label) {
        return (label + ":x").getBytes(StandardCharsets.UTF_8);
    }

    /** Full sha256:<64hex> string from canonicalizer. */
    private static String sha256typed(byte[] data) {
        return CapabilityStudioGateAReceiptCanonicalizer.rawFingerprint(data);
    }

    /** Bare 64-char hex string (for treeCommitment results). */
    private static String sha256bare(byte[] data) {
        String t = sha256typed(data);
        return t.substring(7); // strip "sha256:"
    }

    private static CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot snapshot(
            byte[] prov, byte[] cand) {
        // Snapshot schema fingerprints are full sha256:<64hex> strings.
        Map<String, String> fps = new TreeMap<>();
        Map<String, Long> lens = new TreeMap<>();
        for (String id : CONTRACT.visibleSchemaIds()) {
            String path = "schemas/" + id;
            fps.put(id, sha256typed(path.getBytes(StandardCharsets.UTF_8)));
            lens.put(id, (long) path.getBytes(StandardCharsets.UTF_8).length);
        }
        return new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                sha256typed(prov), Collections.emptyMap(),
                sha256typed(cand), sha256typed(new byte[0]),
                fps, lens, Collections.emptyList());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 1: Exact receipt 11 fields + exact capabilities + typed fingerprints
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    void compose_exactReceiptStructure_11fields_capabilitiesOrder_typedFingerprints() throws Exception {
        byte[] receipt = CapabilityStudioGateATckProviderReceiptComposer.compose(
                raw("a"), raw("p"), raw("c"), CONTRACT, snapshot(raw("p"), raw("c")));
        String json = new String(receipt, StandardCharsets.UTF_8);

        // No trailing LF
        assertThat(json).doesNotEndWith("\n").doesNotEndWith("\r");
        assertThat(receipt[receipt.length - 1]).isNotEqualTo((byte) '\n');

        // Exactly 11 top-level fields (canonical alphabetical order)
        String[] canonicalKeys = {"artifactRawFingerprint","authority","capabilities","fixtureSetId",
                "inputTreeFingerprint","messageVersion","profileRawFingerprint","receiptFingerprint",
                "role","roleViewFingerprint","status"};
        for (String k : canonicalKeys) {
            int f = json.indexOf("\"" + k + "\"");
            assertThat(f).as("Missing top-level field " + k).isGreaterThan(-1);
        }

        // messageVersion from contract
        assertThat(json).contains("\"" + CONTRACT.stdoutMessageVersion() + "\"");

        // capabilities exact order
        List<String> expectedCaps = CapabilityStudioGateATckProviderRoleSelfTest.EXPECTED_CAPABILITIES;
        int capStart = json.indexOf("\"capabilities\":[");
        String capJson = json.substring(capStart, json.indexOf("]", capStart) + 1);
        for (int i = 0; i < expectedCaps.size() - 1; i++) {
            int p1 = capJson.indexOf("\"" + expectedCaps.get(i) + "\"");
            int p2 = capJson.indexOf("\"" + expectedCaps.get(i + 1) + "\"");
            assertThat(p1).as(expectedCaps.get(i) + " before " + expectedCaps.get(i+1)).isLessThan(p2);
        }

        // Typed fingerprint kinds only in receipt's top-level fields
        assertThat(json).contains("\"kind\":\"RAW_BYTES\"");
        assertThat(json).contains("\"kind\":\"TREE_COMMITMENT\"");
        assertThat(json).contains("\"kind\":\"SELF_NULL_RECEIPT\"");
        assertThat(json).contains("\"algorithm\":\"SHA-256\"");

        // Exactly 5 sha256: prefixes (authority fp, artifact fp, roleViewFp, inputTreeFp, receiptFp)
        long shaCount = json.chars().filter(c -> c == ':').count() -
                        json.replace("\"sha256:", "").chars().filter(c -> c == ':').count();
        assertThat(shaCount).isEqualTo(5);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════════════
    // TEST 2: Independent Oracle — exact receipt byte-for-byte match
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    void compose_independentCommitments_matchReceipt() throws Exception {
        byte[] a = raw("a"), p = raw("p"), c = raw("c");
        var snap = snapshot(p, c);

        // ── Step 1: Build schema set records from snapshot ──────────────
        List<Map<String, Object>> schemaRecords = new ArrayList<>();
        for (String schemaId : CONTRACT.visibleSchemaIds()) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("relativePath", "schemas/" + schemaId);
            rec.put("kind", "SCHEMA");
            rec.put("byteLength", snap.candidateSchemaByteLengths.get(schemaId));
            rec.put("rawFingerprint", snap.candidateSchemaFingerprints.get(schemaId));
            schemaRecords.add(rec);
        }
        String schemaSetBare = CapabilityStudioGateAReceiptCanonicalizer.treeCommitment(
                schemaRecords, CONTRACT.schemaSetDomain());

        // ── Step 2: Build input tree entries ────────────────────────────
        List<Map<String, Object>> inputEntries = new ArrayList<>();
        Map<String, Object> e1 = new LinkedHashMap<>();
        e1.put("relativePath", "role-views/TCK_PROVIDER/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar");
        e1.put("byteLength", (long) c.length);
        e1.put("rawFingerprint", sha256typed(c));
        inputEntries.add(e1);
        Map<String, Object> e2 = new LinkedHashMap<>();
        e2.put("relativePath", "role-views/TCK_PROVIDER/inputs/artifacts/TCK_PROVIDER.jar");
        e2.put("byteLength", (long) p.length);
        e2.put("rawFingerprint", sha256typed(p));
        inputEntries.add(e2);
        Map<String, Object> e3 = new LinkedHashMap<>();
        e3.put("relativePath", "role-views/TCK_PROVIDER/inputs/authority.json");
        e3.put("byteLength", (long) a.length);
        e3.put("rawFingerprint", sha256typed(a));
        inputEntries.add(e3);
        String inputTreeBare = CapabilityStudioGateAReceiptCanonicalizer.treeCommitment(
                inputEntries, CONTRACT.roleInputTreeDomain());

        // ── Step 3: Build roleViewMaterial ──────────────────────────────
        Map<String, Object> roleViewMaterial = new LinkedHashMap<>();
        roleViewMaterial.put("messageVersion", "capability-studio.gate-a.release-authority-bundle.role-view.v1");
        roleViewMaterial.put("role", CONTRACT.role());

        List<String> vfr = new ArrayList<>();
        vfr.add("role-views/TCK_PROVIDER/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar");
        vfr.add("role-views/TCK_PROVIDER/inputs/artifacts/TCK_PROVIDER.jar");
        vfr.add("role-views/TCK_PROVIDER/inputs/authority.json");
        Collections.sort(vfr);
        roleViewMaterial.put("visibleFileRefs", vfr);
        roleViewMaterial.put("inputTreeFingerprint", "sha256:" + inputTreeBare);
        roleViewMaterial.put("forbiddenCapabilities",
                List.of("ORACLE", "AUTHORITY_WORKSPACE", "REPOSITORY_ROOT", "OTHER_ROLE_INPUTS"));

        List<String> reqRoles = new ArrayList<>(CONTRACT.requiredRuntimeArtifactRoles());
        Collections.sort(reqRoles);
        roleViewMaterial.put("requiredRuntimeArtifactRoles", reqRoles);

        List<String> pkgSchemaIds = new ArrayList<>(CONTRACT.visibleSchemaIds());
        Collections.sort(pkgSchemaIds);
        roleViewMaterial.put("packagedSchemaIds", pkgSchemaIds);
        roleViewMaterial.put("visibleSchemaIds", pkgSchemaIds);
        roleViewMaterial.put("schemaSetFingerprint", "sha256:" + schemaSetBare);

        String roleViewBare = CapabilityStudioGateAReceiptCanonicalizer.committed(
                CONTRACT.roleViewDomain(), roleViewMaterial);

        // ── Step 4: Build expected receipt (11 fields, pre-commit) ──────
        Map<String, Object> expectedReceipt = new LinkedHashMap<>();
        expectedReceipt.put("messageVersion", CONTRACT.stdoutMessageVersion());
        expectedReceipt.put("role", CONTRACT.role());

        Map<String, Object> authorityMap = new LinkedHashMap<>();
        authorityMap.put("rawFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedRaw(a));
        authorityMap.put("revision", CONTRACT.revision());
        expectedReceipt.put("authority", authorityMap);
        expectedReceipt.put("artifactRawFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedRaw(p));
        expectedReceipt.put("profileRawFingerprint", null);
        expectedReceipt.put("fixtureSetId", CONTRACT.fixtureSetId());
        expectedReceipt.put("capabilities", new ArrayList<>(CapabilityStudioGateATckProviderRoleSelfTest.EXPECTED_CAPABILITIES));
        expectedReceipt.put("status", "READY");
        expectedReceipt.put("roleViewFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedTree(roleViewBare));
        expectedReceipt.put("inputTreeFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedTree(inputTreeBare));
        expectedReceipt.put("receiptFingerprint", null);

        String receiptBare = CapabilityStudioGateAReceiptCanonicalizer.committed(
                CONTRACT.receiptFingerprintDomain(), expectedReceipt);
        expectedReceipt.put("receiptFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedSelfNull(receiptBare));

        // ── Step 5: Canonicalize and compare byte-for-byte ───────────────
        byte[] expectedBytes = CapabilityStudioGateAReceiptCanonicalizer.canonical(expectedReceipt);
        byte[] actualBytes = CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, snap);
        assertThat(actualBytes).isEqualTo(expectedBytes);
    }
    // TEST 3: Deterministic and no trailing LF
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    void compose_deterministic_noTrailingLF() throws Exception {
        byte[] a = raw("a"), p = raw("p"), c = raw("c");
        var snap = snapshot(p, c);

        byte[] r1 = CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, snap);
        byte[] r2 = CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, snap);
        byte[] r3 = CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, snap);

        assertThat(r1).isEqualTo(r2).isEqualTo(r3);
        String s = new String(r1, StandardCharsets.UTF_8);
        assertThat(s).doesNotEndWith("\n").doesNotEndWith("\r");
        assertThat(r1[r1.length - 1]).isNotEqualTo((byte) '\n');
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 4: Any input change changes receipt
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    void compose_inputChange_changesReceipt() throws Exception {
        byte[] a = raw("a"), p = raw("p"), c = raw("c");
        var snap = snapshot(p, c);

        byte[] base = CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, snap);

        // Change authority
        byte[] diffAuth = CapabilityStudioGateATckProviderReceiptComposer.compose(raw("x"), p, c, CONTRACT, snap);
        assertThat(diffAuth).isNotEqualTo(base);

        // Change provider (snapshot must match)
        byte[] diffProv = CapabilityStudioGateATckProviderReceiptComposer.compose(a, raw("y"), c, CONTRACT, snapshot(raw("y"), c));
        assertThat(diffProv).isNotEqualTo(base);

        // Change candidate (snapshot must match)
        byte[] diffCand = CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, raw("z"), CONTRACT, snapshot(p, raw("z")));
        assertThat(diffCand).isNotEqualTo(base);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 5: Null/empty guards merged
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    void compose_nullEmptyGuards_rejected() {
        byte[] a = raw("a"), p = raw("p"), c = raw("c");
        var snap = snapshot(p, c);

        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(null, p, c, CONTRACT, snap))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_NULL");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, null, c, CONTRACT, snap))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("PROVIDER_NULL");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, null, CONTRACT, snap))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("CANDIDATE_NULL");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, null, snap))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("CONTRACT_NULL");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, null))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("ARTIFACTS_NULL");

        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(new byte[0], p, c, CONTRACT, snap))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("AUTHORITY_EMPTY");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, new byte[0], c, CONTRACT, snap))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("PROVIDER_EMPTY");
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, new byte[0], CONTRACT, snap))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("CANDIDATE_EMPTY");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 6: Snapshot errors rejected
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    void compose_snapshotErrors_rejected() {
        byte[] a = raw("a"), p = raw("p"), c = raw("c");
        var good = snapshot(p, c);

        var badSnap = new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                good.providerRawFingerprint, good.providerEntryFingerprints,
                good.candidateRawFingerprint, good.candidateSpiFingerprint,
                good.candidateSchemaFingerprints, good.candidateSchemaByteLengths,
                List.of("SOME_ERROR"));

        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, badSnap))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("ARTIFACTS_ERRORS");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 7: Schema key mismatch merged
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    void compose_schemaKeyMismatch_rejected() {
        byte[] a = raw("a"), p = raw("p"), c = raw("c");
        var good = snapshot(p, c);

        // Fingerprint key missing
        Map<String, String> fps = new TreeMap<>(good.candidateSchemaFingerprints);
        String target = CONTRACT.visibleSchemaIds().iterator().next();
        fps.remove(target);
        var s1 = new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                good.providerRawFingerprint, good.providerEntryFingerprints,
                good.candidateRawFingerprint, good.candidateSpiFingerprint,
                fps, good.candidateSchemaByteLengths, Collections.emptyList());
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, s1))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("SCHEMA_KEY_MISMATCH");

        // Length key extra
        Map<String, Long> lens = new TreeMap<>(good.candidateSchemaByteLengths);
        lens.put("extra-schema", 100L);
        var s2 = new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                good.providerRawFingerprint, good.providerEntryFingerprints,
                good.candidateRawFingerprint, good.candidateSpiFingerprint,
                good.candidateSchemaFingerprints, lens, Collections.emptyList());
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, s2))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("SCHEMA_KEY_MISMATCH");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 8: Malformed fp / non-positive length merged
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    void compose_malformedFpOrNonpositiveLength_rejected() {
        byte[] a = raw("a"), p = raw("p"), c = raw("c");
        var good = snapshot(p, c);
        String target = CONTRACT.visibleSchemaIds().iterator().next();

        // Missing sha256: prefix (should be sha256:<64hex>)
        Map<String, String> badFps = new TreeMap<>(good.candidateSchemaFingerprints);
        badFps.put(target, "ab".repeat(32)); // bare hex without prefix
        var s1 = new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                good.providerRawFingerprint, good.providerEntryFingerprints,
                good.candidateRawFingerprint, good.candidateSpiFingerprint,
                badFps, good.candidateSchemaByteLengths, Collections.emptyList());
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, s1))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("SHA256_FORMAT");

        // Zero length
        Map<String, Long> badLens0 = new TreeMap<>(good.candidateSchemaByteLengths);
        badLens0.put(target, 0L);
        var s2 = new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                good.providerRawFingerprint, good.providerEntryFingerprints,
                good.candidateRawFingerprint, good.candidateSpiFingerprint,
                good.candidateSchemaFingerprints, badLens0, Collections.emptyList());
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, s2))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("LENGTH_NOT_POSITIVE");

        // Negative length
        Map<String, Long> badLensN = new TreeMap<>(good.candidateSchemaByteLengths);
        badLensN.put(target, -1L);
        var s3 = new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                good.providerRawFingerprint, good.providerEntryFingerprints,
                good.candidateRawFingerprint, good.candidateSpiFingerprint,
                good.candidateSchemaFingerprints, badLensN, Collections.emptyList());
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, s3))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("LENGTH_NOT_POSITIVE");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 9: Provider/candidate raw binding mismatch merged
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    void compose_rawBindingMismatch_rejected() {
        byte[] a = raw("a"), p = raw("p"), c = raw("c");
        var good = snapshot(p, c);

        // Provider fingerprint mismatch (full sha256:<64hex> format)
        var s1 = new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                "sha256:" + "ff".repeat(32), good.providerEntryFingerprints,
                good.candidateRawFingerprint, good.candidateSpiFingerprint,
                good.candidateSchemaFingerprints, good.candidateSchemaByteLengths, Collections.emptyList());
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, s1))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("ARTIFACT_BINDING_MISMATCH");

        // Candidate fingerprint mismatch (full sha256:<64hex> format)
        var s2 = new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                good.providerRawFingerprint, good.providerEntryFingerprints,
                "sha256:" + "00".repeat(32), good.candidateSpiFingerprint,
                good.candidateSchemaFingerprints, good.candidateSchemaByteLengths, Collections.emptyList());
        assertThatThrownBy(() -> CapabilityStudioGateATckProviderReceiptComposer.compose(a, p, c, CONTRACT, s2))
                .isInstanceOf(CapabilityStudioGateAException.class)
                .hasMessageContaining("ARTIFACT_BINDING_MISMATCH");
    }
}
