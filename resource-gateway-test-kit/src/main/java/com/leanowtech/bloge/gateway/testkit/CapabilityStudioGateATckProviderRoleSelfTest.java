package com.leanowtech.bloge.gateway.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * TCK_PROVIDER role self-test authority projection for CapabilityStudio Gate A.
 *
 * <p>Layer 1 of the TCK role kernel: parses the caller-pinned authority document
 * and validates the TCK_PROVIDER role contract against all protocol invariants.
 * This layer does not execute TCK logic; it only validates the authority structure
 * and extracts the typed contract for use by subsequent layers.
 *
 * <p>Fail-closed: any validation failure throws a typed exception with a fixed
 * reason code that does not embed input values.
 *
 * <p>Uses JDK-only strict JSON parsing via {@link StrictJsonParser}.
 *
 * <p>Package-private.
 */
final class CapabilityStudioGateATckProviderRoleSelfTest {

    // ── Protocol constants ──────────────────────────────────────────────
    static final String SCHEMA_VERSION = "capability-studio.gate-a-protocol-authority.v1";
    static final String AUTHORITY_ID = "GATE-A-PROTOCOL-AUTHORITY";
    static final int REVISION = 1;

    static final String ROLE_VIEW_DOMAIN = "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-VIEW-v1";
    static final String ROLE_INPUT_TREE_DOMAIN = "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-INPUTS-v1";
    static final String SCHEMA_SET_DOMAIN = "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-SCHEMA-SET-v1";

    static final String EXPECTED_ROLE = "TCK_PROVIDER";
    static final String EXPECTED_MODULE_PATH = "resource-gateway-gate-a-tck-provider";
    static final String EXPECTED_POM_PATH = "resource-gateway-gate-a-tck-provider/pom.xml";
    static final String EXPECTED_GROUP_ID = "com.leanowtech.bloge";
    static final String EXPECTED_ARTIFACT_ID = "resource-gateway-gate-a-tck-provider";
    static final String EXPECTED_VERSION = "1.0.0";
    static final String EXPECTED_PACKAGING = "jar";
    static final String EXPECTED_LAUNCH_MODE = "SERVICE_LOADER_PROBE";
    static final String EXPECTED_BUILD_PROFILE = "gate-a-provider";
    static final String EXPECTED_RELEASE_GATE = "STRUCTURE_AND_BLACK_BOX_REQUIRED";
    static final String EXPECTED_ARTIFACT_PATH = "resource-gateway-gate-a-tck-provider/target/resource-gateway-gate-a-tck-provider-1.0.0.jar";

    static final String EXPECTED_FIXTURE_SET_ID = "GATE_A_ROLE_BLACK_BOX_V1";
    static final String EXPECTED_STDOUT_VERSION = "resource-gateway.capability-studio.gate-a.role-self-test-receipt.v1";
    static final String EXPECTED_RECEIPT_SCHEMA = "capability-studio-gate-a-role-self-test-receipt-v1.schema.json";
    static final String EXPECTED_RECEIPT_DOMAIN = "RG-CS-GATE-A-ROLE-SELF-TEST-RECEIPT-v1";
    static final String EXPECTED_STDERR_POLICY = "EMPTY";
    static final String EXPECTED_CONTRACT_KIND = "DETERMINISTIC_ROLE_SELF_TEST";

    static final String EXPECTED_PACKAGING_MODEL = "THIN_SERVICE_PROVIDER";
    static final String EXPECTED_DEP_LOCK_ENTRY = "META-INF/gate-a/manifests/dependencies.json";
    static final String EXPECTED_CLOSURE_POLICY = "THIN_SERVICE_DESCRIPTOR_AND_IMPLEMENTATION_ONLY";
    static final String EXPECTED_DEP_LOCK_AUTHORITY = "TOP_LEVEL_DEPENDENCY_AUTHORITY";
    static final String EXPECTED_DEP_LOCK_MODE = "PROVIDED_ABI_ONLY_NO_EMBEDDED_RUNTIME";
    static final String EXPECTED_SPI_INTERFACE = "com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider";
    static final String EXPECTED_SPI_DESCRIPTOR = "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider";
    static final String EXPECTED_EMBEDDING_POLICY = "PROVIDED_ABI_NOT_EMBEDDED";
    static final String EXPECTED_CANDIDATE_CLASSIFIER = "gate-a-candidate";

    static final List<String> EXPECTED_COMPILE_ALLOWLIST = List.of("com.leanowtech.bloge:bloge-resource-gateway-test-kit");
    static final int EXPECTED_FORBIDDEN_COUNT = 3;
    static final int EXPECTED_JAR_ENTRY_COUNT = 5;

    static final Set<String> EXPECTED_JAR_ENTRIES = Set.of(
            "META-INF/MANIFEST.MF",
            "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider",
            "com/leanowtech/bloge/gatetckprovider/GateATckProvider.class",
            "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties",
            "META-INF/gate-a/manifests/dependencies.json"
    );

    static final List<String> EXPECTED_CAPABILITIES = List.of(
            "ROLE_SELF_TEST_RECEIPT", "THIN_SERVICE_PROVIDER", "PROVIDER_IDENTITY"
    );
    static final int EXPECTED_VISIBLE_SCHEMA_COUNT = 57;

    static final long EXPECTED_MAX_RAW_BYTES = 16777216L;
    static final long EXPECTED_MAX_ZIP_ENTRIES = 512L;
    static final long EXPECTED_MAX_SINGLE_ENTRY_BYTES = 8388608L;
    static final long EXPECTED_MAX_TOTAL_UNCOMPRESSED = 67108864L;
    static final double EXPECTED_MAX_COMPRESSION_RATIO = 100.0;

    private static final long MAX_AUTHORITY_BYTES = 8 * 1024 * 1024;

    private CapabilityStudioGateATckProviderRoleSelfTest() {}

    // Error codes — fixed, no embedded values
    static final String E_TCK_ROLE_INVALID         = "TCK_PROVIDER_ROLE_INVALID";
    static final String E_TCK_FIXTURE_SET_INVALID = "TCK_PROVIDER_FIXTURE_SET_INVALID";
    static final String E_PROVIDER_PATH_INVALID   = "PROVIDER_PATH_INVALID";
    static final String E_PROVIDER_READ_FAILED   = "PROVIDER_READ_FAILED";
    static final String E_CANDIDATE_READ_FAILED  = "CANDIDATE_READ_FAILED";
    static final String E_IC_COUNT_DRIFT            = "AUTHORITY_IMPLEMENTATION_CANDIDATE_COUNT_DRIFT";
    static final String E_IC_DEP_JAR_ENTRY_DRIFT    = "AUTHORITY_IC_DEP_JAR_ENTRY_DRIFT";
    private static final Pattern DEP_JAR_NAME_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]*\\.jar$");

    /**
     * Returns true when entry is a well-formed dep-jar path strictly equivalent to
     * {@code META-INF/gate-a/dependencies/<basename>} where basename matches the
     * {@code ^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$} pattern (no slash, backslash,
     * NUL, or C0/C1 control chars).
     */
    private static boolean isValidDepJarEntryName(String entry) {
        if (entry == null || entry.isEmpty()) return false;
        if (!entry.startsWith("META-INF/gate-a/dependencies/") || !entry.endsWith(".jar")) return false;
        String name = entry.substring("META-INF/gate-a/dependencies/".length());
        // Reject empty basename and anything not matching the strict pattern
        return DEP_JAR_NAME_PATTERN.matcher(name).matches();
    }

    static byte[] execute(String[] args, boolean enforceCodeSource) {
        // Strict argument validation — fixed codes only, no embedded values
        validateArgs(args);
        if (!"TCK_PROVIDER".equals(args[2]))        throw new CapabilityStudioGateAException(E_TCK_ROLE_INVALID);
        if (!EXPECTED_FIXTURE_SET_ID.equals(args[8])) throw new CapabilityStudioGateAException(E_TCK_FIXTURE_SET_INVALID);

        // Path construction: catch IllegalArgumentException / SecurityException → fixed codes
        Path authorityPath;
        Path providerPath;
        try {
            authorityPath = Path.of(args[4]);
        } catch (RuntimeException e) {
            throw new CapabilityStudioGateAException("AUTHORITY_PATH_INVALID");
        }
        try {
            providerPath = Path.of(args[6]);
        } catch (RuntimeException e) {
            throw new CapabilityStudioGateAException(E_PROVIDER_PATH_INVALID);
        }

        // Bounded stable authority read
        byte[] authorityRaw = readStableBounded(authorityPath, MAX_AUTHORITY_BYTES);
        if (authorityRaw == null) throw new CapabilityStudioGateAException("AUTHORITY_READ_FAILED");

        // Authority projection
        TckRoleContract contract = projectAndValidate(authorityRaw);

        // TCK_PROVIDER contract verification
        if (!EXPECTED_ROLE.equals(contract.role()))              throw new CapabilityStudioGateAException(E_TCK_ROLE_INVALID);
        if (!EXPECTED_FIXTURE_SET_ID.equals(contract.fixtureSetId())) throw new CapabilityStudioGateAException(E_TCK_FIXTURE_SET_INVALID);

        // Bounded stable provider read (ArtifactValidator owns path security)
        byte[] providerRaw = readStableBounded(providerPath, contract.maxRawBytes());
        if (providerRaw == null) throw new CapabilityStudioGateAException(E_PROVIDER_READ_FAILED);

        // Bounded stable candidate read (from ChallengeCli CodeSource — not caller-reported)
        Path candidatePath = CapabilityStudioGateATckProviderRuntimeProbe.candidateArtifactPath();
        byte[] candidateRaw = readStableBounded(candidatePath, contract.maxRawBytes());
        if (candidateRaw == null) throw new CapabilityStudioGateAException(E_CANDIDATE_READ_FAILED);

        // ArtifactValidator.validate
        CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot snap =
                CapabilityStudioGateATckProviderArtifactValidator.validate(
                        providerRaw, providerPath, candidateRaw, candidatePath, contract, enforceCodeSource);
        if (!snap.isPassed()) throw new CapabilityStudioGateAException(snap.errors.get(0));

        // RuntimeProbe.probe (no local variable — pure call)
        CapabilityStudioGateATckProviderRuntimeProbe.probe(
                CapabilityStudioGateAChallengeCli.class.getClassLoader(),
                providerPath, candidatePath, enforceCodeSource);

        // ReceiptComposer.compose
        return CapabilityStudioGateATckProviderReceiptComposer.compose(
                authorityRaw, providerRaw, candidateRaw, contract, snap);
    }

    @SuppressWarnings("unchecked")
    static TckRoleContract projectAndValidate(byte[] rawAuthority) {
        Map<String, Object> authority = StrictJsonParser.parse(rawAuthority);

        // Authority-level
        if (!SCHEMA_VERSION.equals(authority.get("schemaVersion"))) {
            fail("AUTHORITY_SCHEMA_VERSION_DRIFT");
        }
        if (!AUTHORITY_ID.equals(authority.get("authorityId"))) {
            fail("AUTHORITY_AUTHORITY_ID_DRIFT");
        }
        Number revNum = (Number) authority.get("revision");
        if (revNum == null || revNum.intValue() != REVISION) {
            fail("AUTHORITY_REVISION_DRIFT");
        }

        // Release authority bundle contract
        Map<String, Object> bundle = (Map<String, Object>) authority.get("releaseAuthorityBundleContract");
        if (bundle == null) {
            fail("AUTHORITY_RELEASE_BUNDLE_MISSING");
        }
        if (!"capability-studio.gate-a-release-authority-bundle.v1".equals(bundle.get("schemaVersion"))) {
            fail("AUTHORITY_BUNDLE_SCHEMA_VERSION_DRIFT");
        }
        if (!ROLE_VIEW_DOMAIN.equals(bundle.get("roleViewDomain"))) {
            fail("AUTHORITY_ROLE_VIEW_DOMAIN_DRIFT");
        }
        if (!ROLE_INPUT_TREE_DOMAIN.equals(bundle.get("roleInputTreeDomain"))) {
            fail("AUTHORITY_ROLE_INPUT_TREE_DOMAIN_DRIFT");
        }
        if (!SCHEMA_SET_DOMAIN.equals(bundle.get("schemaSetDomain"))) {
            fail("AUTHORITY_SCHEMA_SET_DOMAIN_DRIFT");
        }

        // Schema inventory: gateASchemas + requiredReviewerSchemas
        Map<String, Object> schemaInv = (Map<String, Object>) authority.get("schemaInventoryPolicy");
        if (schemaInv == null) {
            fail("AUTHORITY_SCHEMA_INVENTORY_MISSING");
        }
        List<String> gateASchemas = (List<String>) schemaInv.get("gateASchemas");
        if (gateASchemas == null) {
            fail("AUTHORITY_GATE_A_SCHEMAS_MISSING");
        }
        List<String> reviewerSchemas = (List<String>) schemaInv.get("requiredReviewerSchemas");
        if (reviewerSchemas == null) {
            fail("AUTHORITY_REVIEWER_SCHEMAS_MISSING");
        }

        // Build combined allowed set and detect duplicates in inventory itself
        Set<String> allowedSchemas = new HashSet<>();
        Set<String> inventorySeen = new HashSet<>();
        for (String s : gateASchemas) {
            if (inventorySeen.contains(s)) {
                fail("AUTHORITY_GATE_A_SCHEMAS_DUPLICATE");
            }
            inventorySeen.add(s);
            allowedSchemas.add(s);
        }
        for (String s : reviewerSchemas) {
            if (inventorySeen.contains(s)) {
                fail("AUTHORITY_REVIEWER_SCHEMAS_DUPLICATE");
            }
            inventorySeen.add(s);
            allowedSchemas.add(s);
        }

        // roleContracts: find exactly one TCK_PROVIDER
        List<Map<String, Object>> roleContracts = (List<Map<String, Object>>) authority.get("roleContracts");
        if (roleContracts == null) {
            fail("AUTHORITY_ROLE_CONTRACTS_MISSING");
        }
        Map<String, Object> tckContract = null;
        int tckCount = 0;
        for (Map<String, Object> rc : roleContracts) {
            if (EXPECTED_ROLE.equals(rc.get("role"))) {
                tckContract = rc;
                tckCount++;
            }
        }
        if (tckCount != 1) {
            fail("AUTHORITY_TCK_PROVIDER_COUNT_DRIFT");
        }
        if (tckContract == null) {
            fail("AUTHORITY_TCK_PROVIDER_MISSING");
        }

        validateRoleContract(tckContract, allowedSchemas);

        // IMPLEMENTATION_CANDIDATE: find exactly one, extract allowed dependency jars
        Map<String, Object> icContract = null;
        int icCount = 0;
        for (Map<String, Object> rc : roleContracts) {
            if ("IMPLEMENTATION_CANDIDATE".equals(rc.get("role"))) {
                icContract = rc;
                icCount++;
            }
        }
        if (icCount != 1) {
            fail(E_IC_COUNT_DRIFT);
        }

        @SuppressWarnings("unchecked")
        List<String> icJarEntries = (List<String>) icContract.get("requiredJarEntries");
        if (icJarEntries == null) {
            fail(E_IC_DEP_JAR_ENTRY_DRIFT);
        }

        // Filter: prefix META-INF/gate-a/dependencies/ + suffix .jar
        Set<String> seenDepJars = new HashSet<>();
        Set<String> depJars = new HashSet<>();
        int depJarCount = 0;
        for (String entry : icJarEntries) {
            if (entry.startsWith("META-INF/gate-a/dependencies/") && entry.endsWith(".jar")) {
                depJarCount++;
                if (seenDepJars.contains(entry)) {
                    fail(E_IC_DEP_JAR_ENTRY_DRIFT);
                }
                seenDepJars.add(entry);
                // Validate dep-jar entry name format
                if (!isValidDepJarEntryName(entry)) {
                    fail(E_IC_DEP_JAR_ENTRY_DRIFT);
                }
                depJars.add(entry);
            }
        }
        if (depJarCount != 8) {
            fail(E_IC_DEP_JAR_ENTRY_DRIFT);
        }

        return buildContract(authority, tckContract, Collections.unmodifiableSet(depJars));
    }

    private static void validateRoleContract(Map<String, Object> rc, Set<String> allowedSchemas) {
        // module/pom/GAV/version/packaging
        expectNull(rc, "buildIdentityRole", "AUTHORITY_BUILD_IDENTITY_ROLE_NULL_DRIFT");
        expectValue(rc, "modulePath", EXPECTED_MODULE_PATH, "AUTHORITY_MODULE_PATH_DRIFT");
        expectValue(rc, "pomPath", EXPECTED_POM_PATH, "AUTHORITY_POM_PATH_DRIFT");
        expectValue(rc, "groupId", EXPECTED_GROUP_ID, "AUTHORITY_GROUP_ID_DRIFT");
        expectValue(rc, "artifactId", EXPECTED_ARTIFACT_ID, "AUTHORITY_ARTIFACT_ID_DRIFT");
        expectValue(rc, "packaging", EXPECTED_PACKAGING, "AUTHORITY_PACKAGING_DRIFT");
        expectValue(rc, "artifactVersion", EXPECTED_VERSION, "AUTHORITY_VERSION_DRIFT");

        if (rc.get("mainClass") != null) fail("AUTHORITY_MAIN_CLASS_NULL_DRIFT");
        if (rc.get("classifier") != null) fail("AUTHORITY_CLASSIFIER_NULL_DRIFT");
        expectNull(rc, "profilePath", "AUTHORITY_PROFILE_PATH_NULL_DRIFT");
        expectNull(rc, "registryPath", "AUTHORITY_REGISTRY_PATH_NULL_DRIFT");
        expectNull(rc, "profileFingerprintField", "AUTHORITY_PROFILE_FP_FIELD_NULL_DRIFT");

        expectValue(rc, "artifactPath", EXPECTED_ARTIFACT_PATH, "AUTHORITY_ARTIFACT_PATH_DRIFT");
        expectValue(rc, "launchMode", EXPECTED_LAUNCH_MODE, "AUTHORITY_LAUNCH_MODE_DRIFT");
        expectValue(rc, "buildProfile", EXPECTED_BUILD_PROFILE, "AUTHORITY_BUILD_PROFILE_DRIFT");
        expectValue(rc, "releaseGate", EXPECTED_RELEASE_GATE, "AUTHORITY_RELEASE_GATE_DRIFT");

        // runtimeDependencyAllowlist must be empty
        List<?> runtimeAllow = (List<?>) rc.get("runtimeDependencyAllowlist");
        if (runtimeAllow == null || !runtimeAllow.isEmpty()) {
            fail("AUTHORITY_RUNTIME_ALLOWLIST_EMPTY_DRIFT");
        }

        // compileDependencyAllowlist: List exact equality
        List<String> compileAllow = (List<String>) rc.get("compileDependencyAllowlist");
        if (compileAllow == null || !EXPECTED_COMPILE_ALLOWLIST.equals(compileAllow)) {
            fail("AUTHORITY_COMPILE_ALLOWLIST_DRIFT");
        }

        // forbiddenProjectDependencies: exact count
        List<?> forbidden = (List<?>) rc.get("forbiddenProjectDependencies");
        if (forbidden == null || forbidden.size() != EXPECTED_FORBIDDEN_COUNT) {
            fail("AUTHORITY_FORBIDDEN_COUNT_DRIFT");
        }

        // requiredJarEntries: exact count + all required entries present
        List<String> jarEntries = (List<String>) rc.get("requiredJarEntries");
        if (jarEntries == null || jarEntries.size() != EXPECTED_JAR_ENTRY_COUNT) {
            fail("AUTHORITY_JAR_ENTRY_COUNT_DRIFT");
        }
        for (String expected : EXPECTED_JAR_ENTRIES) {
            if (!jarEntries.contains(expected)) {
                fail("AUTHORITY_JAR_ENTRY_MISSING");
            }
        }

        // BlackBox contract
        Map<String, Object> blackBox = (Map<String, Object>) rc.get("blackBoxContract");
        if (blackBox == null) fail("AUTHORITY_BLACKBOX_MISSING");

        expectValue(blackBox, "fixtureSetId", EXPECTED_FIXTURE_SET_ID, "AUTHORITY_BB_FIXTURE_SET_ID_DRIFT");
        expectValue(blackBox, "stdoutMessageVersion", EXPECTED_STDOUT_VERSION, "AUTHORITY_BB_STDOUT_VERSION_DRIFT");
        expectValue(blackBox, "stderrPolicy", EXPECTED_STDERR_POLICY, "AUTHORITY_BB_STDERR_POLICY_DRIFT");
        expectValue(blackBox, "contractKind", EXPECTED_CONTRACT_KIND, "AUTHORITY_BB_CONTRACT_KIND_DRIFT");
        expectValue(blackBox, "receiptSchema", EXPECTED_RECEIPT_SCHEMA, "AUTHORITY_BB_RECEIPT_SCHEMA_DRIFT");
        expectValue(blackBox, "receiptFingerprintDomain", EXPECTED_RECEIPT_DOMAIN, "AUTHORITY_BB_RECEIPT_DOMAIN_DRIFT");
        expectValue(blackBox, "authorityInputPolicy", "ONE_STABLE_O_NOFOLLOW_RAW_BYTE_SNAPSHOT", "AUTHORITY_BB_AUTH_INPUT_POLICY_DRIFT");
        expectValue(blackBox, "artifactInputPolicy", "ACTUAL_ROLE_JAR_RAW_BYTES", "AUTHORITY_BB_ARTIFACT_INPUT_POLICY_DRIFT");
        expectValue(blackBox, "profileInputPolicy", "NO_PROFILE", "AUTHORITY_BB_PROFILE_INPUT_POLICY_DRIFT");

        // Capabilities: size + membership + order
        List<String> caps = (List<String>) blackBox.get("capabilities");
        if (caps == null) {
            fail("AUTHORITY_CAPABILITIES_DRIFT");
        }
        if (caps.size() != EXPECTED_CAPABILITIES.size()) {
            fail("AUTHORITY_CAPABILITIES_SIZE_DRIFT");
        }
        for (String cap : EXPECTED_CAPABILITIES) {
            if (!caps.contains(cap)) {
                fail("AUTHORITY_CAPABILITY_MISSING");
            }
        }

        // Artifact limits
        Map<String, Object> artifactLimits = (Map<String, Object>) rc.get("artifactLimits");
        if (artifactLimits == null) fail("AUTHORITY_ARTIFACT_LIMITS_MISSING");
        validateArtifactLimits(artifactLimits);

        // BlackBoxFixtureContract
        Map<String, Object> fixture = (Map<String, Object>) rc.get("blackBoxFixtureContract");
        if (fixture == null) fail("AUTHORITY_BB_FIXTURE_CONTRACT_MISSING");
        expectValue(fixture, "fixtureSetId", EXPECTED_FIXTURE_SET_ID, "AUTHORITY_BB_FIXTURE_CONTRACT_ID_DRIFT");

        List<Map<String, Object>> fixtures = (List<Map<String, Object>>) fixture.get("fixtures");
        if (fixtures == null || fixtures.size() != 2) fail("AUTHORITY_BB_FIXTURE_COUNT_DRIFT");
        Set<String> fixtureNames = new HashSet<>();
        for (Map<String, Object> f : fixtures) {
            String name = (String) f.get("name");
            fixtureNames.add(name);
            if (!"FILE".equals(f.get("kind"))) fail("AUTHORITY_BB_FIXTURE_KIND_DRIFT");
            if (!"RAW_BYTES".equals(f.get("commitmentKind"))) fail("AUTHORITY_BB_FIXTURE_COMMITMENT_DRIFT");
        }
        if (!fixtureNames.contains("AUTHORITY_SNAPSHOT") || !fixtureNames.contains("ROLE_ARTIFACT")) {
            fail("AUTHORITY_BB_FIXTURE_NAMES_DRIFT");
        }

        Map<String, Object> oracle = (Map<String, Object>) fixture.get("oracle");
        if (oracle == null) fail("AUTHORITY_BB_ORACLE_MISSING");
        if (!"FILE".equals(oracle.get("kind"))) fail("AUTHORITY_BB_ORACLE_KIND_DRIFT");
        if (!"RAW_BYTES".equals(oracle.get("commitmentKind"))) fail("AUTHORITY_BB_ORACLE_COMMITMENT_DRIFT");

        Map<String, Object> oraclePolicy = (Map<String, Object>) fixture.get("oracleCompilationPolicy");
        if (oraclePolicy == null) fail("AUTHORITY_BB_ORACLE_POLICY_MISSING");
        expectValue(oraclePolicy, "mode", "CALLER_INDEPENDENT", "AUTHORITY_BB_ORACLE_MODE_DRIFT");
        expectValue(oraclePolicy, "testedRoleExecution", "FORBIDDEN", "AUTHORITY_BB_ORACLE_TESTED_EXECUTION_DRIFT");

        // Packaging contract
        Map<String, Object> packaging = (Map<String, Object>) rc.get("packagingContract");
        if (packaging == null) fail("AUTHORITY_PACKAGING_CONTRACT_MISSING");
        expectValue(packaging, "model", EXPECTED_PACKAGING_MODEL, "AUTHORITY_PACKAGING_MODEL_DRIFT");
        expectValue(packaging, "dependencyLockManifestEntryPath", EXPECTED_DEP_LOCK_ENTRY, "AUTHORITY_DEP_LOCK_ENTRY_DRIFT");
        expectValue(packaging, "closurePolicy", EXPECTED_CLOSURE_POLICY, "AUTHORITY_CLOSURE_POLICY_DRIFT");
        expectValue(packaging, "dependencyLockAuthority", EXPECTED_DEP_LOCK_AUTHORITY, "AUTHORITY_DEP_LOCK_AUTHORITY_DRIFT");
        expectValue(packaging, "dependencyLockManifestMode", EXPECTED_DEP_LOCK_MODE, "AUTHORITY_DEP_LOCK_MODE_DRIFT");

        // class/resource manifest entries must be null
        expectNull(packaging, "classManifestEntryPath", "AUTHORITY_CLASS_MANIFEST_NULL_DRIFT");
        expectNull(packaging, "resourceManifestEntryPath", "AUTHORITY_RESOURCE_MANIFEST_NULL_DRIFT");

        List<?> embedded = (List<?>) packaging.get("embeddedDependencyEntries");
        if (embedded == null || !embedded.isEmpty()) fail("AUTHORITY_EMBEDDED_DEPS_EMPTY_DRIFT");

        // providedAbiDependencies
        List<Map<String, Object>> providedAbi = (List<Map<String, Object>>) packaging.get("providedAbiDependencies");
        if (providedAbi == null || providedAbi.size() != 1) fail("AUTHORITY_PROVIDED_ABI_DEPS_SIZE_DRIFT");

        Map<String, Object> providedAbiDep = providedAbi.get(0);
        expectValue(providedAbiDep, "candidateSpiInterfaceClass", EXPECTED_SPI_INTERFACE, "AUTHORITY_SPI_INTERFACE_DRIFT");
        expectValue(providedAbiDep, "providerServiceDescriptorEntryPath", EXPECTED_SPI_DESCRIPTOR, "AUTHORITY_SPI_DESCRIPTOR_PATH_DRIFT");
        expectValue(providedAbiDep, "embeddingPolicy", EXPECTED_EMBEDDING_POLICY, "AUTHORITY_EMBEDDING_POLICY_DRIFT");

        Map<String, Object> candidateCoord = (Map<String, Object>) providedAbiDep.get("candidateCoordinate");
        if (candidateCoord == null) fail("AUTHORITY_CANDIDATE_COORD_MISSING");
        expectValue(candidateCoord, "classifier", EXPECTED_CANDIDATE_CLASSIFIER, "AUTHORITY_CANDIDATE_CLASSIFIER_DRIFT");

        // runtimeDependencyLockIds: empty
        List<?> runtimeLockIds = (List<?>) rc.get("runtimeDependencyLockIds");
        if (runtimeLockIds == null || !runtimeLockIds.isEmpty()) {
            fail("AUTHORITY_RUNTIME_DEP_LOCK_IDS_EMPTY_DRIFT");
        }

        // requiredRuntimeArtifactRoles: List exact equality
        List<String> runtimeArtifactRoles = (List<String>) rc.get("requiredRuntimeArtifactRoles");
        List<String> expectedRuntimeRoles = List.of("IMPLEMENTATION_CANDIDATE");
        if (runtimeArtifactRoles == null || !expectedRuntimeRoles.equals(runtimeArtifactRoles)) {
            fail("AUTHORITY_RUNTIME_ARTIFACT_ROLES_DRIFT");
        }

        // Visible schema IDs: inventory membership, then duplicate, then count
        List<String> visibleSchemas = (List<String>) rc.get("visibleSchemaIds");
        if (visibleSchemas == null) fail("AUTHORITY_VISIBLE_SCHEMA_IDS_MISSING");
        Set<String> seen = new HashSet<>();
        for (String sid : visibleSchemas) {
            if (!allowedSchemas.contains(sid)) {
                fail("AUTHORITY_VISIBLE_SCHEMA_NOT_IN_INVENTORY");
            }
            if (seen.contains(sid)) {
                fail("AUTHORITY_VISIBLE_SCHEMA_DUPLICATE");
            }
            seen.add(sid);
        }
        if (visibleSchemas.size() != EXPECTED_VISIBLE_SCHEMA_COUNT) {
            fail("AUTHORITY_VISIBLE_SCHEMA_COUNT_DRIFT");
        }
    }

    private static void validateArtifactLimits(Map<String, Object> limits) {
        expectPosLong(limits, "maxRawBytes", EXPECTED_MAX_RAW_BYTES, "AUTHORITY_MAX_RAW_BYTES_DRIFT");
        expectPosLong(limits, "maxZipEntries", EXPECTED_MAX_ZIP_ENTRIES, "AUTHORITY_MAX_ZIP_ENTRIES_DRIFT");
        expectPosLong(limits, "maxSingleEntryBytes", EXPECTED_MAX_SINGLE_ENTRY_BYTES, "AUTHORITY_MAX_SINGLE_ENTRY_DRIFT");
        expectPosLong(limits, "maxTotalUncompressedBytes", EXPECTED_MAX_TOTAL_UNCOMPRESSED, "AUTHORITY_MAX_TOTAL_UNCOMPRESSED_DRIFT");
        Object ratioRaw = limits.get("maxCompressionRatio");
        if (!(ratioRaw instanceof Number)) {
            fail("AUTHORITY_MAX_COMPRESSION_RATIO_DRIFT");
        }
        Number ratio = (Number) ratioRaw;
        double ratioVal = ratio.doubleValue();
        if (Double.isNaN(ratioVal) || Double.isInfinite(ratioVal) || ratioVal <= 0) {
            fail("AUTHORITY_MAX_COMPRESSION_RATIO_DRIFT");
        }
        if (ratioVal != EXPECTED_MAX_COMPRESSION_RATIO) {
            fail("AUTHORITY_MAX_COMPRESSION_RATIO_DRIFT");
        }
    }

    private static void expectValue(Map<String, Object> m, String k, Object exp, String err) {
        if (!Objects.equals(exp, m.get(k))) fail(err);
    }
    private static void expectNull(Map<String, Object> m, String k, String err) {
        if (m.get(k) != null) fail(err);
    }
    private static void expectPosLong(Map<String, Object> m, String k, long exp, String err) {
        Object v = m.get(k);
        if (!(v instanceof Number) || ((Number) v).longValue() != exp) fail(err);
    }

    @SuppressWarnings("unchecked")
    private static TckRoleContract buildContract(Map<String, Object> authority, Map<String, Object> rc, Set<String> candidateDependencyJarEntries) {
        Map<String, Object> bb = (Map<String, Object>) rc.get("blackBoxContract");
        List<String> visibleSchemas = (List<String>) rc.get("visibleSchemaIds");
        Set<String> requiredRuntimeArtifactRoles = Set.of("IMPLEMENTATION_CANDIDATE");
        Set<String> visibleSchemaIds = new HashSet<>(visibleSchemas);

        return new TckRoleContract(
                SCHEMA_VERSION, AUTHORITY_ID, REVISION,
                ROLE_VIEW_DOMAIN, ROLE_INPUT_TREE_DOMAIN, SCHEMA_SET_DOMAIN,
                EXPECTED_ROLE, EXPECTED_MODULE_PATH, EXPECTED_ARTIFACT_PATH,
                EXPECTED_LAUNCH_MODE, EXPECTED_BUILD_PROFILE, EXPECTED_RELEASE_GATE,
                EXPECTED_FIXTURE_SET_ID, EXPECTED_STDOUT_VERSION, EXPECTED_STDERR_POLICY,
                EXPECTED_CONTRACT_KIND, EXPECTED_RECEIPT_SCHEMA, EXPECTED_RECEIPT_DOMAIN,
                new HashSet<>(EXPECTED_CAPABILITIES),
                EXPECTED_MAX_RAW_BYTES, EXPECTED_MAX_ZIP_ENTRIES,
                EXPECTED_MAX_SINGLE_ENTRY_BYTES, EXPECTED_MAX_TOTAL_UNCOMPRESSED, EXPECTED_MAX_COMPRESSION_RATIO,
                new HashSet<>(EXPECTED_JAR_ENTRIES),
                EXPECTED_PACKAGING_MODEL, EXPECTED_DEP_LOCK_MODE,
                EXPECTED_SPI_INTERFACE, EXPECTED_SPI_DESCRIPTOR, EXPECTED_EMBEDDING_POLICY,
                requiredRuntimeArtifactRoles,
                visibleSchemaIds,
                candidateDependencyJarEntries
        );
    }

    private static void validateArgs(String[] args) {
        if (args == null)               throw new CapabilityStudioGateAException("NULL_ARGS");
        if (args.length != 9)          throw new CapabilityStudioGateAException("INVALID_ARG_COUNT");
        if (!"--role-self-test".equals(args[0])) throw new CapabilityStudioGateAException("INVALID_ARG_0");
        if (!"--role".equals(args[1]))            throw new CapabilityStudioGateAException("INVALID_ARG_1");
        if (!"--authority".equals(args[3]))       throw new CapabilityStudioGateAException("INVALID_ARG_3");
        if (!"--artifact".equals(args[5]))        throw new CapabilityStudioGateAException("INVALID_ARG_5");
        if (!"--fixture-set-id".equals(args[7])) throw new CapabilityStudioGateAException("INVALID_ARG_7");
    }

    private static byte[] readStableBounded(Path path, long maxBytes) {
        try {
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.isSymbolicLink()) return null;
            if (before.size() > maxBytes) return null;
            try (var channel = java.nio.file.Files.newByteChannel(path, java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes opened = Files.readAttributes(path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (!sameFile(before, opened)) return null;
                var baos = new java.io.ByteArrayOutputStream();
                var buf = java.nio.ByteBuffer.allocate(8192);
                while (channel.read(buf) >= 0) {
                    buf.flip();
                    baos.write(buf.array(), buf.arrayOffset(), buf.remaining());
                    buf.clear();
                    if (baos.size() > maxBytes) return null;
                }
                BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (!sameFile(before, after)) return null;
                return baos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean sameFile(BasicFileAttributes a, BasicFileAttributes b) {
        return a.isRegularFile() && !a.isSymbolicLink()
                && a.size() == b.size()
                && a.lastModifiedTime().equals(b.lastModifiedTime());
    }

    private static void fail(String error) {
        throw new CapabilityStudioGateAException(error);
    }

    // ── Typed contract record ──────────────────────────────────────────────

    static final class TckRoleContract {
        private final String schemaVersion;
        private final String authorityId;
        private final int revision;
        private final String roleViewDomain;
        private final String roleInputTreeDomain;
        private final String schemaSetDomain;
        private final String role;
        private final String modulePath;
        private final String artifactPath;
        private final String launchMode;
        private final String buildProfile;
        private final String releaseGate;
        private final String fixtureSetId;
        private final String stdoutMessageVersion;
        private final String stderrPolicy;
        private final String contractKind;
        private final String receiptSchema;
        private final String receiptFingerprintDomain;
        private final Set<String> capabilities;
        private final long maxRawBytes;
        private final long maxZipEntries;
        private final long maxSingleEntryBytes;
        private final long maxTotalUncompressedBytes;
        private final double maxCompressionRatio;
        private final Set<String> requiredJarEntries;
        private final String packagingModel;
        private final String dependencyLockManifestMode;
        private final String spiInterfaceClass;
        private final String spiDescriptorEntryPath;
        private final String embeddingPolicy;
        private final Set<String> requiredRuntimeArtifactRoles;
        private final Set<String> visibleSchemaIds;
        private final Set<String> candidateDependencyJarEntries;

        TckRoleContract(String schemaVersion, String authorityId, int revision,
                        String roleViewDomain, String roleInputTreeDomain, String schemaSetDomain,
                        String role, String modulePath, String artifactPath,
                        String launchMode, String buildProfile, String releaseGate,
                        String fixtureSetId, String stdoutMessageVersion, String stderrPolicy,
                        String contractKind, String receiptSchema, String receiptFingerprintDomain,
                        Set<String> capabilities, long maxRawBytes, long maxZipEntries,
                        long maxSingleEntryBytes, long maxTotalUncompressedBytes, double maxCompressionRatio,
                        Set<String> requiredJarEntries, String packagingModel,
                        String dependencyLockManifestMode,
                        String spiInterfaceClass, String spiDescriptorEntryPath, String embeddingPolicy,
                        Set<String> requiredRuntimeArtifactRoles, Set<String> visibleSchemaIds, Set<String> candidateDependencyJarEntries) {
            this.schemaVersion = schemaVersion;
            this.authorityId = authorityId;
            this.revision = revision;
            this.roleViewDomain = roleViewDomain;
            this.roleInputTreeDomain = roleInputTreeDomain;
            this.schemaSetDomain = schemaSetDomain;
            this.role = role;
            this.modulePath = modulePath;
            this.artifactPath = artifactPath;
            this.launchMode = launchMode;
            this.buildProfile = buildProfile;
            this.releaseGate = releaseGate;
            this.fixtureSetId = fixtureSetId;
            this.stdoutMessageVersion = stdoutMessageVersion;
            this.stderrPolicy = stderrPolicy;
            this.contractKind = contractKind;
            this.receiptSchema = receiptSchema;
            this.receiptFingerprintDomain = receiptFingerprintDomain;
            this.capabilities = Collections.unmodifiableSet(new HashSet<>(capabilities));
            this.maxRawBytes = maxRawBytes;
            this.maxZipEntries = maxZipEntries;
            this.maxSingleEntryBytes = maxSingleEntryBytes;
            this.maxTotalUncompressedBytes = maxTotalUncompressedBytes;
            this.maxCompressionRatio = maxCompressionRatio;
            this.requiredJarEntries = Collections.unmodifiableSet(new HashSet<>(requiredJarEntries));
            this.packagingModel = packagingModel;
            this.dependencyLockManifestMode = dependencyLockManifestMode;
            this.spiInterfaceClass = spiInterfaceClass;
            this.spiDescriptorEntryPath = spiDescriptorEntryPath;
            this.embeddingPolicy = embeddingPolicy;
            this.requiredRuntimeArtifactRoles = Collections.unmodifiableSet(new HashSet<>(requiredRuntimeArtifactRoles));
            this.visibleSchemaIds = Collections.unmodifiableSet(new HashSet<>(visibleSchemaIds));
            this.candidateDependencyJarEntries = Collections.unmodifiableSet(new HashSet<>(candidateDependencyJarEntries));
        }

        String schemaVersion() { return schemaVersion; }
        String authorityId() { return authorityId; }
        int revision() { return revision; }
        String roleViewDomain() { return roleViewDomain; }
        String roleInputTreeDomain() { return roleInputTreeDomain; }
        String schemaSetDomain() { return schemaSetDomain; }
        String role() { return role; }
        String modulePath() { return modulePath; }
        String artifactPath() { return artifactPath; }
        String launchMode() { return launchMode; }
        String buildProfile() { return buildProfile; }
        String releaseGate() { return releaseGate; }
        String fixtureSetId() { return fixtureSetId; }
        String stdoutMessageVersion() { return stdoutMessageVersion; }
        String stderrPolicy() { return stderrPolicy; }
        String contractKind() { return contractKind; }
        String receiptSchema() { return receiptSchema; }
        String receiptFingerprintDomain() { return receiptFingerprintDomain; }
        Set<String> capabilities() { return capabilities; }
        long maxRawBytes() { return maxRawBytes; }
        long maxZipEntries() { return maxZipEntries; }
        long maxSingleEntryBytes() { return maxSingleEntryBytes; }
        long maxTotalUncompressedBytes() { return maxTotalUncompressedBytes; }
        double maxCompressionRatio() { return maxCompressionRatio; }
        Set<String> requiredJarEntries() { return requiredJarEntries; }
        String packagingModel() { return packagingModel; }
        String dependencyLockManifestMode() { return dependencyLockManifestMode; }
        String spiInterfaceClass() { return spiInterfaceClass; }
        String spiDescriptorEntryPath() { return spiDescriptorEntryPath; }
        String embeddingPolicy() { return embeddingPolicy; }
        Set<String> requiredRuntimeArtifactRoles() { return requiredRuntimeArtifactRoles; }
        Set<String> visibleSchemaIds() { return visibleSchemaIds; }
        Set<String> candidateDependencyJarEntries() { return candidateDependencyJarEntries; }
    }
}
