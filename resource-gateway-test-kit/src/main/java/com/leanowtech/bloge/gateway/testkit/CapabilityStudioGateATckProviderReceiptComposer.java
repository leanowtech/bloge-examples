package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JDK-only, package-private final deterministic receipt composer for TCK_PROVIDER Gate A.
 *
 * <p>Composes a canonical role-self-test receipt from raw authority, provider JAR, and candidate JAR
 * bytes against a validated {@link CapabilityStudioGateATckProviderRoleSelfTest.TckRoleContract}
 * and a passing {@link CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot}.
 *
 * <p>Return value is canonical JSON bytes (no trailing LF).
 *
 * <p>API: {@code static byte[] compose(byte[], byte[], byte[], TckRoleContract, ValidationSnapshot)}
 *
 * <p>All error codes are fixed strings — no runtime values are embedded.
 *
 * @see CapabilityStudioGateATckProviderRoleSelfTest
 * @see CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot
 */
final class CapabilityStudioGateATckProviderReceiptComposer {

    // Snapshot fingerprints are always full sha256:<64hex> strings
    private static final Pattern SHA256_TYPED_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");

    // ── Error codes (all fixed) ───────────────────────────────────────
    static final String E_AUTHORITY_NULL                    = "RECEIPT_COMPOSER_AUTHORITY_NULL";
    static final String E_AUTHORITY_EMPTY                   = "RECEIPT_COMPOSER_AUTHORITY_EMPTY";
    static final String E_PROVIDER_NULL                    = "RECEIPT_COMPOSER_PROVIDER_NULL";
    static final String E_PROVIDER_EMPTY                   = "RECEIPT_COMPOSER_PROVIDER_EMPTY";
    static final String E_CANDIDATE_NULL                  = "RECEIPT_COMPOSER_CANDIDATE_NULL";
    static final String E_CANDIDATE_EMPTY                  = "RECEIPT_COMPOSER_CANDIDATE_EMPTY";
    static final String E_CONTRACT_NULL                    = "RECEIPT_COMPOSER_CONTRACT_NULL";
    static final String E_ARTIFACTS_NULL                   = "RECEIPT_COMPOSER_ARTIFACTS_NULL";
    static final String E_ARTIFACTS_ERRORS                 = "RECEIPT_COMPOSER_ARTIFACTS_ERRORS";
    static final String E_ARTIFACT_BINDING_MISMATCH        = "RECEIPT_COMPOSER_ARTIFACT_BINDING_MISMATCH";
    static final String E_SCHEMA_KEY_MISMATCH              = "RECEIPT_COMPOSER_SCHEMA_KEY_MISMATCH";
    static final String E_SHA256_FORMAT                     = "RECEIPT_COMPOSER_SHA256_FORMAT";
    static final String E_LENGTH_NOT_POSITIVE               = "RECEIPT_COMPOSER_LENGTH_NOT_POSITIVE";

    private CapabilityStudioGateATckProviderReceiptComposer() {}

    /**
     * Composes a deterministic role-self-test receipt from raw inputs and a validated contract.
     *
     * <p>Rejects on:</p>
     * <ul>
     *   <li>Any null raw byte array argument</li>
     *   <li>Any zero-length raw byte array</li>
     *   <li>{@code artifacts} containing any errors (non-empty error list)</li>
     *   <li>Schema fingerprint/length key mismatch: fingerprint and length maps must have identical key sets</li>
     *   <li>Schema set mismatch: snapshot fingerprint keys do not exactly match contract.visibleSchemaIds()</li>
     *   <li>Provider/candidate raw fingerprint mismatch: snapshot fingerprints must exactly equal canonicalizer.rawFingerprint(raw)</li>
     *   <li>Any schema fingerprint not matching {@code ^sha256:[0-9a-f]{64}$} (full sha256: prefix)</li>
     *   <li>Any byte-length entry in schemaLengths is not positive</li>
     * </ul>
     *
     * @param authorityRaw raw bytes of the protocol authority JSON
     * @param providerRaw raw bytes of the TCK_PROVIDER JAR artifact
     * @param candidateRaw raw bytes of the IMPLEMENTATION_CANDIDATE JAR artifact
     * @param contract validated TCK_PROVIDER role contract
     * @param artifacts passing validation snapshot (no errors)
     * @return canonical JSON bytes of the receipt (no trailing LF)
     * @throws CapabilityStudioGateAException on any fixed-code rejection
     */
    static byte[] compose(byte[] authorityRaw,
                          byte[] providerRaw,
                          byte[] candidateRaw,
                          CapabilityStudioGateATckProviderRoleSelfTest.TckRoleContract contract,
                          CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot artifacts) {

        // ── Null checks ──────────────────────────────────────────────
        if (authorityRaw == null)       throw new CapabilityStudioGateAException(E_AUTHORITY_NULL);
        if (providerRaw == null)        throw new CapabilityStudioGateAException(E_PROVIDER_NULL);
        if (candidateRaw == null)       throw new CapabilityStudioGateAException(E_CANDIDATE_NULL);
        if (contract == null)           throw new CapabilityStudioGateAException(E_CONTRACT_NULL);
        if (artifacts == null)          throw new CapabilityStudioGateAException(E_ARTIFACTS_NULL);

        // ── Empty checks ─────────────────────────────────────────────
        if (authorityRaw.length == 0)  throw new CapabilityStudioGateAException(E_AUTHORITY_EMPTY);
        if (providerRaw.length == 0)    throw new CapabilityStudioGateAException(E_PROVIDER_EMPTY);
        if (candidateRaw.length == 0)   throw new CapabilityStudioGateAException(E_CANDIDATE_EMPTY);

        // ── Snapshot errors check ─────────────────────────────────────
        if (!artifacts.isPassed()) {
            throw new CapabilityStudioGateAException(E_ARTIFACTS_ERRORS);
        }

        // ── Raw binding validation (exact string equals) ───────────────
        // Snapshot fingerprints are full sha256:<64hex>; canonicalizer returns same format.
        String actualProviderFp   = CapabilityStudioGateAReceiptCanonicalizer.rawFingerprint(providerRaw);
        String actualCandidateFp  = CapabilityStudioGateAReceiptCanonicalizer.rawFingerprint(candidateRaw);

        if (!actualProviderFp.equals(artifacts.providerRawFingerprint)) {
            throw new CapabilityStudioGateAException(E_ARTIFACT_BINDING_MISMATCH);
        }
        if (!actualCandidateFp.equals(artifacts.candidateRawFingerprint)) {
            throw new CapabilityStudioGateAException(E_ARTIFACT_BINDING_MISMATCH);
        }

        // ── Schema validation ─────────────────────────────────────────
        // Snapshot fingerprints are full sha256:<64hex> strings.
        Set<String> snapshotKeys = artifacts.candidateSchemaFingerprints.keySet();
        Set<String> lengthKeys  = artifacts.candidateSchemaByteLengths.keySet();

        // Key sets must be identical
        if (!snapshotKeys.equals(lengthKeys)) {
            throw new CapabilityStudioGateAException(E_SCHEMA_KEY_MISMATCH);
        }

        // Must exactly match contract visibleSchemaIds
        Set<String> contractSchemas = contract.visibleSchemaIds();
        if (!snapshotKeys.equals(contractSchemas)) {
            throw new CapabilityStudioGateAException(E_SCHEMA_KEY_MISMATCH);
        }

        // Validate each schema fingerprint (full sha256:<64hex>) and length (>0)
        for (String schemaId : contractSchemas) {
            String fp = artifacts.candidateSchemaFingerprints.get(schemaId);
            Long   len = artifacts.candidateSchemaByteLengths.get(schemaId);

            if (fp == null || !SHA256_TYPED_PATTERN.matcher(fp).matches()) {
                throw new CapabilityStudioGateAException(E_SHA256_FORMAT);
            }
            if (len == null || len <= 0) {
                throw new CapabilityStudioGateAException(E_LENGTH_NOT_POSITIVE);
            }
        }

        // ── Input Tree: exactly 3 records sorted by relativePath ───────
        // Each rawFingerprint is the full sha256:<64hex> string.
        List<Map<String, Object>> inputEntries = new ArrayList<>();

        Map<String, Object> candEntry = new LinkedHashMap<>();
        candEntry.put("relativePath", "role-views/TCK_PROVIDER/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar");
        candEntry.put("byteLength",    (long) candidateRaw.length);
        candEntry.put("rawFingerprint", actualCandidateFp); // full sha256:<64hex>
        inputEntries.add(candEntry);

        Map<String, Object> provEntry = new LinkedHashMap<>();
        provEntry.put("relativePath", "role-views/TCK_PROVIDER/inputs/artifacts/TCK_PROVIDER.jar");
        provEntry.put("byteLength",    (long) providerRaw.length);
        provEntry.put("rawFingerprint", actualProviderFp);  // full sha256:<64hex>
        inputEntries.add(provEntry);

        String authorityFingerprint = CapabilityStudioGateAReceiptCanonicalizer.rawFingerprint(authorityRaw);
        Map<String, Object> authEntry = new LinkedHashMap<>();
        authEntry.put("relativePath", "role-views/TCK_PROVIDER/inputs/authority.json");
        authEntry.put("byteLength",    (long) authorityRaw.length);
        authEntry.put("rawFingerprint", authorityFingerprint); // full sha256:<64hex>
        inputEntries.add(authEntry);

        String inputTreeFingerprint = CapabilityStudioGateAReceiptCanonicalizer.treeCommitment(
                inputEntries, contract.roleInputTreeDomain()); // returns bare hex

        // ── Schema Set: 57 entries, each rawFingerprint is full sha256:<64hex> ──
        List<Map<String, Object>> schemaSetEntries = new ArrayList<>();
        for (String schemaId : contractSchemas) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("relativePath",   "schemas/" + schemaId);
            entry.put("kind",           "SCHEMA");
            entry.put("byteLength",      artifacts.candidateSchemaByteLengths.get(schemaId));
            entry.put("rawFingerprint", artifacts.candidateSchemaFingerprints.get(schemaId)); // full sha256:<64hex>
            schemaSetEntries.add(entry);
        }
        String schemaSetFingerprint = CapabilityStudioGateAReceiptCanonicalizer.treeCommitment(
                schemaSetEntries, contract.schemaSetDomain()); // returns bare hex

        // ── Role view material (roleViewFingerprint NOT in material) ───
        // inputTreeFingerprint and schemaSetFingerprint are bare hex strings
        // prefixed once (treeCommitment returns bare hex).
        Map<String, Object> roleViewMaterial = new LinkedHashMap<>();
        roleViewMaterial.put("messageVersion",
                "capability-studio.gate-a.release-authority-bundle.role-view.v1");
        roleViewMaterial.put("role", contract.role());

        // visibleFileRefs: exactly 3 paths sorted
        List<String> visibleFileRefs = new ArrayList<>();
        visibleFileRefs.add("role-views/TCK_PROVIDER/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar");
        visibleFileRefs.add("role-views/TCK_PROVIDER/inputs/artifacts/TCK_PROVIDER.jar");
        visibleFileRefs.add("role-views/TCK_PROVIDER/inputs/authority.json");
        Collections.sort(visibleFileRefs);
        roleViewMaterial.put("visibleFileRefs", visibleFileRefs);

        // bare hex string prefixed once
        roleViewMaterial.put("inputTreeFingerprint", "sha256:" + inputTreeFingerprint);

        // forbiddenCapabilities: fixed canonical order
        roleViewMaterial.put("forbiddenCapabilities",
                List.of("ORACLE", "AUTHORITY_WORKSPACE", "REPOSITORY_ROOT", "OTHER_ROLE_INPUTS"));

        // requiredRuntimeArtifactRoles: sorted
        List<String> reqRoles = new ArrayList<>(contract.requiredRuntimeArtifactRoles());
        Collections.sort(reqRoles);
        roleViewMaterial.put("requiredRuntimeArtifactRoles", reqRoles);

        // packagedSchemaIds: sorted
        List<String> pkgSchemaIds = new ArrayList<>(contractSchemas);
        Collections.sort(pkgSchemaIds);
        roleViewMaterial.put("packagedSchemaIds", pkgSchemaIds);

        // visibleSchemaIds: sorted
        roleViewMaterial.put("visibleSchemaIds", pkgSchemaIds);

        // bare hex string prefixed once
        roleViewMaterial.put("schemaSetFingerprint", "sha256:" + schemaSetFingerprint);

        String roleViewFingerprint = CapabilityStudioGateAReceiptCanonicalizer.committed(
                contract.roleViewDomain(), roleViewMaterial); // returns bare hex

        // ── Build receipt (exactly 11 fields) ─────────────────────────
        Map<String, Object> receipt = new LinkedHashMap<>();

        receipt.put("messageVersion", contract.stdoutMessageVersion());
        receipt.put("role", contract.role());

        // authority {rawFingerprint: typedRaw, revision}
        Map<String, Object> authorityMap = new LinkedHashMap<>();
        authorityMap.put("rawFingerprint",
                CapabilityStudioGateAReceiptCanonicalizer.typedRaw(authorityRaw));
        authorityMap.put("revision", contract.revision());
        receipt.put("authority", authorityMap);

        // artifactRawFingerprint
        receipt.put("artifactRawFingerprint",
                CapabilityStudioGateAReceiptCanonicalizer.typedRaw(providerRaw));

        // profileRawFingerprint (null)
        receipt.put("profileRawFingerprint", null);

        // fixtureSetId
        receipt.put("fixtureSetId", contract.fixtureSetId());

        // capabilities: exact order from EXPECTED_CAPABILITIES
        List<String> caps = new ArrayList<>(CapabilityStudioGateATckProviderRoleSelfTest.EXPECTED_CAPABILITIES);
        receipt.put("capabilities", caps);

        // status
        receipt.put("status", "READY");

        // roleViewFingerprint: typedTree(bare) — only receipt fields are typed objects
        receipt.put("roleViewFingerprint",
                CapabilityStudioGateAReceiptCanonicalizer.typedTree(roleViewFingerprint));

        // inputTreeFingerprint: typedTree(bare)
        receipt.put("inputTreeFingerprint",
                CapabilityStudioGateAReceiptCanonicalizer.typedTree(inputTreeFingerprint));

        // receiptFingerprint: self-null
        receipt.put("receiptFingerprint", null);

        String receiptFingerprintBare = CapabilityStudioGateAReceiptCanonicalizer.committed(
                contract.receiptFingerprintDomain(), receipt); // null during hash

        receipt.put("receiptFingerprint",
                CapabilityStudioGateAReceiptCanonicalizer.typedSelfNull(receiptFingerprintBare));

        // ── Return canonical JSON bytes (no trailing LF) ────────────────
        return CapabilityStudioGateAReceiptCanonicalizer.canonical(receipt);
    }
}
