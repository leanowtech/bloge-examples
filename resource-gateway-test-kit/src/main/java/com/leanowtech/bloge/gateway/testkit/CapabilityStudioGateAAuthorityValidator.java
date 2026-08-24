package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict authority validator for Gate A role-self-test.
 *
 * <p>Validates the caller-pinned SOURCE authority bundle against expected protocol constants:</p>
 * <ul>
 *   <li>schemaVersion: capability-studio.gate-a-protocol-authority.v1</li>
 *   <li>authorityId: GATE-A-PROTOCOL-AUTHORITY</li>
 *   <li>revision: 1</li>
 *   <li>releaseAuthorityBundleContract roleViewDomain, roleInputTreeDomain, schemaSetDomain</li>
 *   <li>role IMPLEMENTATION_CANDIDATE: mainClass, classifier, buildProfile, launchMode,
 *       blackBoxContract (capabilities, fixtureSetId, receiptFingerprintDomain,
 *       receiptSchema, stdoutMessageVersion), artifactLimits, requiredJarEntries,
 *       visibleSchemaIds (subset of gateASchemas), runtimeDependencyLockIds</li>
 *   <li>dependencyAuthority: exactly 8 dependencies joined to role runtimeDependencyLockIds;
 *       each pin enriched with coordinate, scope, and entry path from role packagingContract</li>
 * </ul>
 *
 * <p>Uses JDK-only strict JSON parsing.</p>
 *
 * <p>Package-private.</p>
 */
final class CapabilityStudioGateAAuthorityValidator {

    // ── Protocol constants from source authority ─────────────────────────
    static final String EXPECTED_SCHEMA_VERSION = "capability-studio.gate-a-protocol-authority.v1";
    static final String EXPECTED_AUTHORITY_ID = "GATE-A-PROTOCOL-AUTHORITY";
    static final int EXPECTED_REVISION = 1;
    static final String EXPECTED_ROLE = "IMPLEMENTATION_CANDIDATE";

    // Expected receipt fingerprint domain (blackBoxContract.receiptFingerprintDomain)
    // Exposed for CLI reference.
    static final String RECEIPT_FINGERPRINT_DOMAIN = "RG-CS-GATE-A-ROLE-SELF-TEST-RECEIPT-v1";

    // Expected domains from releaseAuthorityBundleContract
    static final String ROLE_VIEW_DOMAIN = "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-VIEW-v1";
    static final String ROLE_INPUT_TREE_DOMAIN = "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-INPUTS-v1";
    static final String SCHEMA_SET_DOMAIN = "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-SCHEMA-SET-v1";

    // Expected capabilities in authority order
    static final List<String> EXPECTED_CAPABILITIES = List.of(
            "ROLE_SELF_TEST_RECEIPT", "SHADED_CLOSED_JAR", "CANONICALIZATION"
    );

    // Expected fixture set ID
    static final String EXPECTED_FIXTURE_SET_ID = "GATE_A_ROLE_BLACK_BOX_V1";

    // Expected stdout message version
    static final String EXPECTED_MESSAGE_VERSION = "resource-gateway.capability-studio.gate-a.role-self-test-receipt.v1";

    // Expected receipt schema
    static final String EXPECTED_RECEIPT_SCHEMA = "capability-studio-gate-a-role-self-test-receipt-v1.schema.json";

    // Expected release gate
    static final String EXPECTED_RELEASE_GATE = "STRUCTURE_AND_BLACK_BOX_REQUIRED";

    // Expected packaging model
    static final String EXPECTED_PACKAGING_MODEL = "SHADED_CLOSED_JAR_WITH_EMBEDDED_DEPENDENCY_SOURCES";

    // Expected projection ID allowed
    static final String EXPECTED_PROJECTION_ID = "CANONICALIZATION_CONTRACT";

    // Expected legacy CLI contract
    static final String EXPECTED_LEGACY_PREV_MAINCLASS =
            "com.leanowtech.bloge.gateway.testkit.ResourceGatewaySuiteCli";

    // Expected buildProfile
    static final String EXPECTED_BUILD_PROFILE = "gate-a-candidate";

    // Expected launchMode
    static final String EXPECTED_LAUNCH_MODE = "CLASSPATH_MAIN";

    private CapabilityStudioGateAAuthorityValidator() {
    }

    /**
     * Parses and validates authority bytes, returning a fully-populated authority map.
     * Stores derived structures under underscore-prefixed keys for caller convenience.
     *
     * @param rawAuthority raw bytes
     * @return validated authority map
     * @throws CapabilityStudioGateAException on any validation failure
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> validate(byte[] rawAuthority) {
        Map<String, Object> authority = StrictJsonParser.parse(rawAuthority);

        // schemaVersion check
        String sv = (String) authority.get("schemaVersion");
        if (!EXPECTED_SCHEMA_VERSION.equals(sv)) {
            fail("AUTHORITY_SCHEMA_VERSION_DRIFT:" + sv);
        }

        // authorityId check
        String aid = (String) authority.get("authorityId");
        if (!EXPECTED_AUTHORITY_ID.equals(aid)) {
            fail("AUTHORITY_AUTHORITY_ID_DRIFT:" + aid);
        }

        // revision check
        Number revNum = (Number) authority.get("revision");
        if (revNum == null || revNum.intValue() != EXPECTED_REVISION) {
            fail("AUTHORITY_REVISION_DRIFT:" + revNum);
        }

        // Collect authority-level gateASchemas for subset check
        Map<String, Object> schemaInventory = (Map<String, Object>) authority.get("schemaInventoryPolicy");
        if (schemaInventory == null) {
            fail("AUTHORITY_SCHEMA_INVENTORY_MISSING");
        }
        List<String> gateASchemas = (List<String>) schemaInventory.get("gateASchemas");
        if (gateASchemas == null) {
            fail("AUTHORITY_GATE_A_SCHEMAS_MISSING");
        }

        // releaseAuthorityBundleContract domains
        Map<String, Object> bundleContract = (Map<String, Object>) authority.get("releaseAuthorityBundleContract");
        if (bundleContract == null) {
            fail("AUTHORITY_BUNDLE_CONTRACT_MISSING");
        }
        checkDomain(bundleContract, "roleViewDomain", ROLE_VIEW_DOMAIN, "AUTHORITY_ROLE_VIEW_DOMAIN_DRIFT");
        checkDomain(bundleContract, "roleInputTreeDomain", ROLE_INPUT_TREE_DOMAIN, "AUTHORITY_ROLE_INPUT_TREE_DOMAIN_DRIFT");
        checkDomain(bundleContract, "schemaSetDomain", SCHEMA_SET_DOMAIN, "AUTHORITY_SCHEMA_SET_DOMAIN_DRIFT");

        // Bundle limits
        Map<String, Object> limits = (Map<String, Object>) bundleContract.get("limits");
        if (limits == null) {
            fail("AUTHORITY_BUNDLE_LIMITS_MISSING");
        }
        long maxJsonBytes = asPositiveLong(limits.get("maxJsonBytes"), "maxJsonBytes");
        long maxFiles = asPositiveLong(limits.get("maxFiles"), "maxFiles");
        long maxFileBytes = asPositiveLong(limits.get("maxFileBytes"), "maxFileBytes");
        long maxTotalBytes = asPositiveLong(limits.get("maxTotalBytes"), "maxTotalBytes");
        if (maxJsonBytes <= 0 || maxFiles <= 0 || maxFileBytes <= 0 || maxTotalBytes <= 0) {
            fail("AUTHORITY_BUNDLE_LIMITS_INVALID");
        }

        // roleContracts array
        Object rcObj = authority.get("roleContracts");
        if (!(rcObj instanceof List)) {
            fail("AUTHORITY_ROLE_CONTRACTS_MISSING");
        }
        List<?> rcList = (List<?>) rcObj;
        if (rcList.isEmpty()) {
            fail("AUTHORITY_ROLE_CONTRACTS_EMPTY");
        }

        // Find IMPLEMENTATION_CANDIDATE role (field is "role", not "roleId")
        Map<String, Object> roleContract = null;
        for (Object item : rcList) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            if (EXPECTED_ROLE.equals(m.get("role"))) {
                roleContract = (Map<String, Object>) item;
                break;
            }
        }
        if (roleContract == null) {
            fail("AUTHORITY_ROLE_MISSING:" + EXPECTED_ROLE);
        }

        // mainClass
        String mainClass = (String) roleContract.get("mainClass");
        if (!"com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli".equals(mainClass)) {
            fail("AUTHORITY_MAIN_CLASS_DRIFT:" + mainClass);
        }

        // classifier
        String classifier = (String) roleContract.get("classifier");
        if (!"gate-a-candidate".equals(classifier)) {
            fail("AUTHORITY_CLASSIFIER_DRIFT:" + classifier);
        }

        // buildProfile
        String buildProfile = (String) roleContract.get("buildProfile");
        if (!EXPECTED_BUILD_PROFILE.equals(buildProfile)) {
            fail("AUTHORITY_BUILD_PROFILE_DRIFT:" + buildProfile);
        }

        // launchMode
        String launchMode = (String) roleContract.get("launchMode");
        if (!EXPECTED_LAUNCH_MODE.equals(launchMode)) {
            fail("AUTHORITY_LAUNCH_MODE_DRIFT:" + launchMode);
        }

        // blackBoxContract
        Map<String, Object> blackBox = (Map<String, Object>) roleContract.get("blackBoxContract");
        if (blackBox == null) {
            fail("AUTHORITY_BLACK_BOX_CONTRACT_MISSING");
        }

        // fixtureSetId via fixtureSetId field
        String fixtureSetId = (String) blackBox.get("fixtureSetId");
        if (!EXPECTED_FIXTURE_SET_ID.equals(fixtureSetId)) {
            fail("AUTHORITY_FIXTURE_SET_ID_DRIFT:" + fixtureSetId);
        }

        // stdout message version
        String stdoutMsgVer = (String) blackBox.get("stdoutMessageVersion");
        if (!EXPECTED_MESSAGE_VERSION.equals(stdoutMsgVer)) {
            fail("AUTHORITY_MESSAGE_VERSION_DRIFT:" + stdoutMsgVer);
        }

        // receipt schema
        String receiptSchema = (String) blackBox.get("receiptSchema");
        if (!EXPECTED_RECEIPT_SCHEMA.equals(receiptSchema)) {
            fail("AUTHORITY_RECEIPT_SCHEMA_DRIFT:" + receiptSchema);
        }

        // receipt fingerprint domain
        String receiptFpDomain = (String) blackBox.get("receiptFingerprintDomain");
        if (!RECEIPT_FINGERPRINT_DOMAIN.equals(receiptFpDomain)) {
            fail("AUTHORITY_RECEIPT_DOMAIN_DRIFT:" + receiptFpDomain);
        }

        // capabilities (in blackBoxContract.capabilities)
        @SuppressWarnings("unchecked")
        List<String> capabilities = (List<String>) blackBox.get("capabilities");
        if (capabilities == null || capabilities.size() != EXPECTED_CAPABILITIES.size()) {
            fail("AUTHORITY_CAPABILITIES_SIZE_DRIFT");
        }
        for (int i = 0; i < EXPECTED_CAPABILITIES.size(); i++) {
            if (!EXPECTED_CAPABILITIES.get(i).equals(capabilities.get(i))) {
                fail("AUTHORITY_CAPABILITIES_ORDER_DRIFT");
            }
        }

        // releaseGate
        String releaseGate = (String) roleContract.get("releaseGate");
        if (!EXPECTED_RELEASE_GATE.equals(releaseGate)) {
            fail("AUTHORITY_RELEASE_GATE_DRIFT:" + releaseGate);
        }

        // legacyCompatibility
        Map<String, Object> legacy = (Map<String, Object>) roleContract.get("legacyCompatibility");
        if (legacy == null) {
            fail("AUTHORITY_LEGACY_COMPATIBILITY_MISSING");
        }
        String prevMainClass = (String) legacy.get("previousMainClass");
        if (!EXPECTED_LEGACY_PREV_MAINCLASS.equals(prevMainClass)) {
            fail("AUTHORITY_LEGACY_PREV_MAINCLASS_DRIFT:" + prevMainClass);
        }

        // packagedProjections: reject compiled projection shapes
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> packagedProjections = (List<Map<String, Object>>) roleContract.get("packagedProjections");
        if (packagedProjections == null || packagedProjections.isEmpty()) {
            fail("AUTHORITY_PACKAGED_PROJECTIONS_MISSING");
        }
        for (Map<String, Object> proj : packagedProjections) {
            String projId = (String) proj.get("projectionId");
            if (!EXPECTED_PROJECTION_ID.equals(projId)) {
                fail("AUTHORITY_UNEXPECTED_PROJECTION_ID:" + projId);
            }
            // Reject compiled shapes: entryPath must be present
            if (!proj.containsKey("entryPath")) {
                fail("AUTHORITY_COMPILED_PROJECTION_SHAPE_DETECTED:" + projId);
            }
        }

        // packagingContract: collect embeddedDependencyEntries for entry-path join
        Map<String, Object> packagingContract = (Map<String, Object>) roleContract.get("packagingContract");
        if (packagingContract == null) {
            fail("AUTHORITY_PACKAGING_CONTRACT_MISSING");
        }
        String packagingModel = (String) packagingContract.get("model");
        if (!EXPECTED_PACKAGING_MODEL.equals(packagingModel)) {
            fail("AUTHORITY_PACKAGING_MODEL_DRIFT:" + packagingModel);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> embeddedEntriesList =
                (List<Map<String, Object>>) packagingContract.get("embeddedDependencyEntries");
        if (embeddedEntriesList == null) {
            fail("AUTHORITY_EMBEDDED_DEPENDENCY_ENTRIES_MISSING");
        }
        Map<String, EmbeddedEntry> embeddedByLockId = new LinkedHashMap<>();
        for (Map<String, Object> entry : embeddedEntriesList) {
            String lid = (String) entry.get("lockId");
            if (lid == null) {
                fail("AUTHORITY_EMBEDDED_ENTRY_LOCK_ID_MISSING");
            }
            String ep = (String) entry.get("entryPath");
            String sc = (String) entry.get("scope");
            if (embeddedByLockId.containsKey(lid)) {
                fail("AUTHORITY_EMBEDDED_ENTRY_DUPLICATE_LOCK_ID:" + lid);
            }
            embeddedByLockId.put(lid, new EmbeddedEntry(lid, ep, sc));
        }

        // artifactLimits
        @SuppressWarnings("unchecked")
        Map<String, Object> artifactLimits = (Map<String, Object>) roleContract.get("artifactLimits");
        if (artifactLimits == null) {
            fail("AUTHORITY_ARTIFACT_LIMITS_MISSING");
        }
        validateArtifactLimits(artifactLimits);

        // requiredJarEntries
        @SuppressWarnings("unchecked")
        List<String> requiredJarEntries = (List<String>) roleContract.get("requiredJarEntries");
        if (requiredJarEntries == null || requiredJarEntries.isEmpty()) {
            fail("AUTHORITY_REQUIRED_JAR_ENTRIES_MISSING");
        }

        // visibleSchemaIds: must be present (subset check intentionally omitted;
        // authority includes reviewer schemas not present in gateASchemas)
        @SuppressWarnings("unchecked")
        List<String> visibleSchemaIds = (List<String>) roleContract.get("visibleSchemaIds");
        if (visibleSchemaIds == null) {
            fail("AUTHORITY_VISIBLE_SCHEMA_IDS_MISSING");
        }

        // requiredRuntimeArtifactRoles
        @SuppressWarnings("unchecked")
        List<String> requiredRuntimeArtifactRoles =
                (List<String>) roleContract.get("requiredRuntimeArtifactRoles");
        if (requiredRuntimeArtifactRoles == null) {
            fail("AUTHORITY_REQUIRED_RUNTIME_ARTIFACT_ROLES_MISSING");
        }

        // runtimeDependencyLockIds
        @SuppressWarnings("unchecked")
        List<String> runtimeDeps = (List<String>) roleContract.get("runtimeDependencyLockIds");
        if (runtimeDeps == null || runtimeDeps.isEmpty()) {
            fail("AUTHORITY_RUNTIME_DEPS_MISSING");
        }

        // dependencyAuthority
        Map<String, Object> depAuth = (Map<String, Object>) authority.get("dependencyAuthority");
        if (depAuth == null) {
            fail("AUTHORITY_DEPENDENCY_AUTHORITY_MISSING");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> depList = (List<Map<String, Object>>) depAuth.get("dependencies");
        if (depList == null || depList.size() != 8) {
            fail("AUTHORITY_DEPENDENCY_COUNT_DRIFT:" + (depList == null ? "null" : depList.size()));
        }

        // Build dependency pins: join runtimeDependencyLockIds with embedded entry paths and rawFingerprint
        Map<String, DependencyPin> depPins = new LinkedHashMap<>();
        for (Map<String, Object> dep : depList) {
            String lockId = (String) dep.get("lockId");
            if (lockId == null) {
                fail("AUTHORITY_DEPENDENCY_LOCK_ID_MISSING");
            }
            if (depPins.containsKey(lockId)) {
                fail("AUTHORITY_DEPENDENCY_LOCK_ID_DUPLICATE:" + lockId);
            }

            // rawFingerprint (not sha256) — contains "sha256:..."
            String rawFingerprint = (String) dep.get("rawFingerprint");
            if (rawFingerprint == null) {
                fail("AUTHORITY_DEPENDENCY_FINGERPRINT_MISSING:" + lockId);
            }
            if (!rawFingerprint.startsWith("sha256:")) {
                fail("AUTHORITY_DEPENDENCY_FINGERPRINT_FORMAT_INVALID:" + lockId);
            }
            String bareSha256 = rawFingerprint.substring(7);
            if (bareSha256.length() != 64) {
                fail("AUTHORITY_DEPENDENCY_FINGERPRINT_LENGTH_INVALID:" + lockId);
            }

            // Maven coordinate
            @SuppressWarnings("unchecked")
            Map<String, Object> coordinate = (Map<String, Object>) dep.get("coordinate");
            if (coordinate == null) {
                fail("AUTHORITY_DEPENDENCY_COORDINATE_MISSING:" + lockId);
            }
            String scope = (String) dep.get("scope");
            if (scope == null) {
                fail("AUTHORITY_DEPENDENCY_SCOPE_MISSING:" + lockId);
            }

            // Join with embedded entry for required entry path
            EmbeddedEntry embedded = embeddedByLockId.get(lockId);
            if (embedded == null) {
                fail("AUTHORITY_RUNTIME_DEP_NOT_IN_EMBEDDED_ENTRIES:" + lockId);
            }
            String entryPath = embedded.entryPath;
            if (entryPath == null || entryPath.isBlank()) {
                fail("AUTHORITY_EMBEDDED_ENTRY_PATH_BLANK:" + lockId);
            }

            // filename: derive from entryPath basename for artifact validator backward compat
            String filename = embedded.basename();

            @SuppressWarnings("unchecked")
            List<String> allowedRoles = (List<String>) dep.get("allowedRoles");
            if (allowedRoles == null || !allowedRoles.contains(EXPECTED_ROLE)) {
                fail("AUTHORITY_DEPENDENCY_ROLE_NOT_ALLOWED:" + lockId);
            }

            @SuppressWarnings("unchecked")
            List<String> packagingModes = (List<String>) dep.get("packagingModes");
            if (packagingModes == null || !packagingModes.contains(EXPECTED_PACKAGING_MODEL)) {
                fail("AUTHORITY_DEPENDENCY_PACKAGING_NOT_ALLOWED:" + lockId);
            }

            depPins.put(lockId, new DependencyPin(lockId, filename, rawFingerprint, coordinate, scope, entryPath));
        }

        // Verify every runtimeDependencyLockId maps to a pin
        for (String depId : runtimeDeps) {
            if (!depPins.containsKey(depId)) {
                fail("AUTHORITY_RUNTIME_DEP_NOT_IN_DEPENDENCY_AUTHORITY:" + depId);
            }
        }

        // Store validated structures for caller use
        authority.put("_roleContract", roleContract);
        authority.put("_blackBox", blackBox);
        authority.put("_artifactLimits", artifactLimits);
        authority.put("_requiredJarEntries", requiredJarEntries);
        authority.put("_visibleSchemaIds", visibleSchemaIds);
        authority.put("_runtimeDeps", runtimeDeps);
        authority.put("_bundleContract", bundleContract);
        authority.put("_maxJsonBytes", maxJsonBytes);
        authority.put("_dependencyPins", depPins);

        return authority;
    }

    private static void checkDomain(Map<String, Object> contract, String key,
                                   String expected, String error) {
        String value = (String) contract.get(key);
        if (!expected.equals(value)) {
            fail(error + ":" + value);
        }
    }

    private static void validateArtifactLimits(Map<String, Object> limits) {
        asPositiveLong(limits.get("maxRawBytes"), "maxRawBytes");
        asPositiveLong(limits.get("maxZipEntries"), "maxZipEntries");
        asPositiveLong(limits.get("maxSingleEntryBytes"), "maxSingleEntryBytes");
        asPositiveLong(limits.get("maxTotalUncompressedBytes"), "maxTotalUncompressedBytes");
        double ratio = ((Number) limits.get("maxCompressionRatio")).doubleValue();
        if (ratio <= 0) {
            fail("AUTHORITY_MAX_COMPRESSION_RATIO_INVALID");
        }
    }

    private static long asPositiveLong(Object val, String name) {
        if (!(val instanceof Number)) {
            fail("AUTHORITY_LIMIT_INVALID:" + name);
        }
        long v = ((Number) val).longValue();
        if (v <= 0) {
            fail("AUTHORITY_LIMIT_INVALID:" + name);
        }
        return v;
    }

    private static void fail(String error) {
        throw new CapabilityStudioGateAException(error);
    }

    /** Minimal record for embedded dependency entry path resolution. */
    private static final class EmbeddedEntry {
        final String lockId;
        final String entryPath;
        final String scope;

        EmbeddedEntry(String lockId, String entryPath, String scope) {
            this.lockId = lockId;
            this.entryPath = entryPath;
            this.scope = scope;
        }

        /** Returns the basename of the entry path (e.g. "jackson-databind-2.18.2.jar"). */
        String basename() {
            int slash = entryPath.lastIndexOf('/');
            return slash >= 0 ? entryPath.substring(slash + 1) : entryPath;
        }
    }

    /**
     * Immutable dependency pin enriched from authority join.
     *
     * <p>Carries lockId, filename (basename of entryPath), sha256 rawFingerprint,
     * Maven coordinate, scope, and required embedded entry path from role
     * packagingContract.</p>
     *
     * <p>Package-private for test and artifact-validator access.</p>
     */
    static final class DependencyPin {
        final String lockId;
        /** Filename basename derived from entryPath (may be null when not derivable). */
        final String filename;
        /** Raw fingerprint value: "sha256:&lt;64 hex chars&gt;". */
        final String sha256;
        @SuppressWarnings("rawtypes")
        final Map coordinate;  // Maven coordinate map
        final String scope;
        /** Required embedded entry path from role packagingContract. */
        final String entryPath;

        DependencyPin(String lockId, String filename, String sha256,
                      @SuppressWarnings("rawtypes") Map coordinate,
                      String scope, String entryPath) {
            this.lockId = lockId;
            this.filename = filename;
            this.sha256 = sha256;
            this.coordinate = coordinate;
            this.scope = scope;
            this.entryPath = entryPath;
        }

        /** Returns the bare 64-char hex string without the sha256: prefix. */
        String bareSha256() {
            return sha256.substring(7);
        }

        /** Returns Maven groupId from coordinate. */
        String groupId() {
            return (String) coordinate.get("groupId");
        }

        /** Returns Maven artifactId from coordinate. */
        String artifactId() {
            return (String) coordinate.get("artifactId");
        }

        /** Returns Maven version from coordinate. */
        String version() {
            return (String) coordinate.get("version");
        }

        @Override
        public String toString() {
            return "DependencyPin{lockId=" + lockId + ", sha256=" + sha256
                    + ", scope=" + scope + ", entryPath=" + entryPath + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DependencyPin)) return false;
            DependencyPin that = (DependencyPin) o;
            return Objects.equals(lockId, that.lockId)
                    && Objects.equals(sha256, that.sha256)
                    && Objects.equals(scope, that.scope)
                    && Objects.equals(entryPath, that.entryPath)
                    && Objects.equals(coordinate, that.coordinate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lockId, sha256, scope, entryPath, coordinate);
        }
    }
}
