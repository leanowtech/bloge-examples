package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK-only Gate A role-self-test CLI for IMPLEMENTATION_CANDIDATE.
 *
 * <p>Exact arguments (9 total):
 * <pre>
 * --role-self-test --role IMPLEMENTATION_CANDIDATE --authority ABS_PATH --artifact ABS_PATH --fixture-set-id GATE_A_ROLE_BLACK_BOX_V1
 * </pre>
 *
 * <p>Success: stdout = canonical receipt + LF, stderr = empty, exit 0
 * <p>Failure: stdout = empty, stderr = minimal error, exit != 0
 *
 * <p>No external runtime dependencies. Uses JDK-only canonicalization matching Python.
 */
public final class CapabilityStudioGateAChallengeCli {

    private static final String ARG_ROLE_SELF_TEST = "--role-self-test";
    private static final String ARG_ROLE = "--role";
    private static final String ARG_AUTHORITY = "--authority";
    private static final String ARG_ARTIFACT = "--artifact";
    private static final String ARG_FIXTURE_SET_ID = "--fixture-set-id";

    // Bounded authority read: max 8MiB per releaseAuthorityBundleContract.limits.maxJsonBytes
    private static final long MAX_AUTHORITY_BYTES = 8 * 1024 * 1024;

    private CapabilityStudioGateAChallengeCli() {}

    /**
     * Entry point. Enforces CodeSource through ArtifactValidator.validate(..., true).
     *
     * @param args CLI arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs with CodeSource enforcement (main entry point).
     *
     * @param args CLI arguments
     * @param out  success output stream (stdout)
     * @param err  error output stream (stderr)
     * @return exit code: 0 success, non-zero failure
     */
    public static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        return runForTest(args, out, err, true);
    }

    /**
     * Runs the role self-test.
     *
     * @param args CLI arguments
     * @param out success output stream
     * @param err error output stream
     * @param enforceCodeSource if true, validates executing JAR matches --artifact; if false, skips (synthetic tests only)
     * @return exit code: 0 success, non-zero failure
     */
    public static int runForTest(String[] args, java.io.PrintStream out, java.io.PrintStream err, boolean enforceCodeSource) {
        try {
            byte[] receipt = execute(args, enforceCodeSource);
            synchronized (out) {
                out.print(new String(receipt, StandardCharsets.UTF_8));
                out.print('\n');
                out.flush();
                if (out.checkError()) {
                    return 1;
                }
            }
            return 0;
        } catch (CapabilityStudioGateAException e) {
            synchronized (err) {
                err.print(e.errorCode());
                err.print('\n');
                err.flush();
            }
            return 1;
        } catch (RuntimeException e) {
            synchronized (err) {
                err.print("INTERNAL_ERROR");
                err.print('\n');
                err.flush();
            }
            return 1;
        }
    }

    /**
     * Executes the role self-test and returns the canonical receipt bytes.
     *
     * @param enforceCodeSource if true, validates executing JAR matches --artifact
     */
    @SuppressWarnings("unchecked")
    static byte[] execute(String[] args, boolean enforceCodeSource) {
        validateArgs(args);

        String role = args[2];
        Path authorityPath = Path.of(args[4]);
        Path artifactPath = Path.of(args[6]);
        String fixtureSetId = args[8];

        if (!"IMPLEMENTATION_CANDIDATE".equals(role)) {
            fail("INVALID_ROLE:" + role);
        }
        if (!"GATE_A_ROLE_BLACK_BOX_V1".equals(fixtureSetId)) {
            fail("INVALID_FIXTURE_SET_ID:" + fixtureSetId);
        }

        // Read authority with NOFOLLOW, bounded at 8MiB
        byte[] authorityRaw = readStableBounded(authorityPath, "AUTHORITY", MAX_AUTHORITY_BYTES);
        if (authorityRaw == null) {
            fail("AUTHORITY_READ_FAILED");
        }

        // Validate authority bytes within maxJsonBytes limit from authority bundle limits
        Map<String, Object> validatedAuthority = CapabilityStudioGateAAuthorityValidator.validate(authorityRaw);
        Map<String, Object> bundleContract = (Map<String, Object>) validatedAuthority.get("_bundleContract");
        if (bundleContract != null) {
            Object limitsObj = bundleContract.get("limits");
            if (limitsObj instanceof Map) {
                Number maxJson = (Number) ((Map<?, ?>) limitsObj).get("maxJsonBytes");
                if (maxJson != null && authorityRaw.length > maxJson.longValue()) {
                    fail("AUTHORITY_MAX_JSON_BYTES_EXCEEDED");
                }
            }
        }

        // Read artifact with NOFOLLOW
        byte[] artifactRaw = readStable(artifactPath, "ARTIFACT");
        if (artifactRaw == null) {
            fail("ARTIFACT_READ_FAILED");
        }

        // Extract validated structures from authority
        Map<String, Object> roleContract = (Map<String, Object>) validatedAuthority.get("_roleContract");
        Map<String, Object> blackBox = (Map<String, Object>) validatedAuthority.get("_blackBox");
        Map<String, Object> limits = (Map<String, Object>) validatedAuthority.get("_artifactLimits");
        List<String> requiredJarEntries = (List<String>) validatedAuthority.get("_requiredJarEntries");
        List<String> visibleSchemaIds = (List<String>) validatedAuthority.get("_visibleSchemaIds");
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> dependencyPins =
                (Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin>) validatedAuthority.get("_dependencyPins");

        // Validate artifact (with optional CodeSource enforcement)
        CapabilityStudioGateAArtifactValidator.ValidationResult artifactResult =
                CapabilityStudioGateAArtifactValidator.validate(
                        artifactRaw, limits, requiredJarEntries, dependencyPins, artifactPath, enforceCodeSource);

        if (!artifactResult.isValid()) {
            fail("ARTIFACT_VALIDATION_FAILED:" + String.join(",", artifactResult.requiredJarEntriesMissing));
        }

        // Build schema pins from authority (currently empty — no pre-computed pins in authority)
        Map<String, String> schemaPins = buildSchemaPins(validatedAuthority, visibleSchemaIds);

        // Validate schemas: rejects extra schema entries not in visible set
        CapabilityStudioGateASchemaValidator.SchemaValidationResult schemaResult =
                CapabilityStudioGateASchemaValidator.validate(
                        artifactResult.rawEntries, visibleSchemaIds, schemaPins, "schemas/");

        if (!schemaResult.isValid()) {
            StringBuilder sb = new StringBuilder("SCHEMA_VALIDATION_FAILED:");
            if (!schemaResult.missingSchemas.isEmpty()) {
                sb.append("MISSING:").append(String.join(",", schemaResult.missingSchemas));
            }
            if (!schemaResult.mismatchedSchemas.isEmpty()) {
                sb.append("MISMATCH:").append(String.join(",", schemaResult.mismatchedSchemas));
            }
            if (!schemaResult.extraSchemas.isEmpty()) {
                sb.append("EXTRA:").append(String.join(",", schemaResult.extraSchemas));
            }
            fail(sb.toString());
        }

        // Derive receipt
        return deriveReceipt(validatedAuthority, authorityRaw, artifactRaw, roleContract, blackBox,
                artifactResult, schemaResult);
    }

    /**
     * Derives the role-self-test receipt matching Python canonicalization.
     */
    @SuppressWarnings("unchecked")
    private static byte[] deriveReceipt(
            Map<String, Object> authority,
            byte[] authorityRaw,
            byte[] artifactRaw,
            Map<String, Object> roleContract,
            Map<String, Object> blackBox,
            CapabilityStudioGateAArtifactValidator.ValidationResult artifactResult,
            CapabilityStudioGateASchemaValidator.SchemaValidationResult schemaResult) {

        // Build input tree entries
        // role-views/IMPLEMENTATION_CANDIDATE/inputs/authority.json
        // role-views/IMPLEMENTATION_CANDIDATE/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar
        List<Map<String, Object>> inputEntries = new ArrayList<>();

        Map<String, Object> authEntry = new LinkedHashMap<>();
        authEntry.put("relativePath", "role-views/IMPLEMENTATION_CANDIDATE/inputs/authority.json");
        authEntry.put("byteLength", (long) authorityRaw.length);
        authEntry.put("rawFingerprint", sha256Hex(authorityRaw));
        inputEntries.add(authEntry);

        Map<String, Object> artifactEntry = new LinkedHashMap<>();
        artifactEntry.put("relativePath", "role-views/IMPLEMENTATION_CANDIDATE/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar");
        artifactEntry.put("byteLength", (long) artifactRaw.length);
        artifactEntry.put("rawFingerprint", sha256Hex(artifactRaw));
        inputEntries.add(artifactEntry);

        String inputTreeFingerprint = CapabilityStudioGateAReceiptCanonicalizer.treeCommitment(
                inputEntries, CapabilityStudioGateAAuthorityValidator.ROLE_INPUT_TREE_DOMAIN);

        // Build schema set entries (sorted by path)
        List<Map<String, Object>> schemaSetEntries = buildSchemaSetEntries(schemaResult);

        String schemaSetFingerprint = CapabilityStudioGateAReceiptCanonicalizer.treeCommitment(
                schemaSetEntries, CapabilityStudioGateAAuthorityValidator.SCHEMA_SET_DOMAIN);

        // Build role view (omitting roleViewFingerprint for hashing)
        List<String> sortedSchemaIds = new ArrayList<>(schemaResult.schemaFingerprints.keySet());
        Collections.sort(sortedSchemaIds);

        List<String> sortedRequiredRuntimeArtifactRoles = new ArrayList<>();
        Object rtRoles = roleContract.get("requiredRuntimeArtifactRoles");
        if (rtRoles instanceof List) {
            sortedRequiredRuntimeArtifactRoles = new ArrayList<>((List<String>) rtRoles);
            Collections.sort(sortedRequiredRuntimeArtifactRoles);
        }

        List<String> forbiddenCapabilities = List.of(
                "ORACLE", "AUTHORITY_WORKSPACE", "REPOSITORY_ROOT", "OTHER_ROLE_INPUTS");

        Map<String, Object> roleViewMaterial = new LinkedHashMap<>();
        roleViewMaterial.put("messageVersion", "capability-studio.gate-a.release-authority-bundle.role-view.v1");
        roleViewMaterial.put("role", "IMPLEMENTATION_CANDIDATE");
        roleViewMaterial.put("visibleFileRefs", List.of(
                "role-views/IMPLEMENTATION_CANDIDATE/inputs/authority.json",
                "role-views/IMPLEMENTATION_CANDIDATE/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar"));
        roleViewMaterial.put("inputTreeFingerprint", inputTreeFingerprint);
        roleViewMaterial.put("forbiddenCapabilities", forbiddenCapabilities);
        roleViewMaterial.put("requiredRuntimeArtifactRoles", sortedRequiredRuntimeArtifactRoles);
        roleViewMaterial.put("packagedSchemaIds", sortedSchemaIds);
        roleViewMaterial.put("visibleSchemaIds", sortedSchemaIds);
        roleViewMaterial.put("schemaSetFingerprint", schemaSetFingerprint);

        String roleViewFingerprint = CapabilityStudioGateAReceiptCanonicalizer.committed(
                CapabilityStudioGateAAuthorityValidator.ROLE_VIEW_DOMAIN, roleViewMaterial);

        // Get authority revision from source
        Number revision = (Number) authority.get("revision");
        int authorityRevision = revision != null ? revision.intValue() : 1;

        // Get capabilities from blackBox in order (validated by AuthorityValidator)
        List<String> capabilities = (List<String>) blackBox.get("capabilities");

        // Build receipt (exactly 11 fields)
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("messageVersion", CapabilityStudioGateAAuthorityValidator.EXPECTED_MESSAGE_VERSION);
        receipt.put("role", "IMPLEMENTATION_CANDIDATE");
        receipt.put("authority", Map.of(
                "rawFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedRaw(authorityRaw),
                "revision", authorityRevision
        ));
        receipt.put("artifactRawFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedRaw(artifactRaw));
        receipt.put("profileRawFingerprint", null);
        receipt.put("fixtureSetId", "GATE_A_ROLE_BLACK_BOX_V1");
        receipt.put("capabilities", capabilities);
        receipt.put("status", "READY");
        receipt.put("roleViewFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedTree(roleViewFingerprint));
        receipt.put("inputTreeFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedTree(inputTreeFingerprint));
        receipt.put("receiptFingerprint", null); // Will be computed with null

        // Compute receipt fingerprint with SELF_NULL
        String receiptFingerprint = CapabilityStudioGateAReceiptCanonicalizer.committed(
                CapabilityStudioGateAAuthorityValidator.RECEIPT_FINGERPRINT_DOMAIN, receipt);

        receipt.put("receiptFingerprint", CapabilityStudioGateAReceiptCanonicalizer.typedSelfNull(receiptFingerprint));

        return CapabilityStudioGateAReceiptCanonicalizer.canonical(receipt);
    }

    /**
     * Builds schema set entries for tree commitment.
     * EXACT fields: relativePath, kind, byteLength, rawFingerprint (bare sha256).
     */
    private static List<Map<String, Object>> buildSchemaSetEntries(
            CapabilityStudioGateASchemaValidator.SchemaValidationResult schemaResult) {

        List<String> sortedIds = new ArrayList<>(schemaResult.schemaFingerprints.keySet());
        Collections.sort(sortedIds);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (String schemaId : sortedIds) {
            String path = "schemas/" + schemaId;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("relativePath", path);
            entry.put("kind", "SCHEMA");
            byte[] schemaBytes = schemaResult.embeddedSchemas.get(schemaId);
            entry.put("byteLength", (long) schemaBytes.length);
            entry.put("rawFingerprint", schemaResult.schemaFingerprints.get(schemaId));
            entries.add(entry);
        }
        return entries;
    }

    /**
     * Builds schema pins from authority. Currently returns empty map as no pre-computed
     * pins are stored in the authority. Schema validation verifies embedded bytes directly.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> buildSchemaPins(
            Map<String, Object> authority, List<String> visibleSchemaIds) {
        // No pre-computed schema pins in authority — return empty.
        // SchemaValidator computes bare fingerprints from embedded schema bytes.
        return Collections.emptyMap();
    }

    private static void validateArgs(String[] args) {
        if (args == null) {
            fail("NULL_ARGS");
        }
        if (args.length != 9) {
            fail("INVALID_ARG_COUNT:" + args.length);
        }
        if (!ARG_ROLE_SELF_TEST.equals(args[0])) {
            fail("INVALID_ARG_0:" + args[0]);
        }
        if (!ARG_ROLE.equals(args[1])) {
            fail("INVALID_ARG_1:" + args[1]);
        }
        if (!ARG_AUTHORITY.equals(args[3])) {
            fail("INVALID_ARG_3:" + args[3]);
        }
        if (!ARG_ARTIFACT.equals(args[5])) {
            fail("INVALID_ARG_5:" + args[5]);
        }
        if (!ARG_FIXTURE_SET_ID.equals(args[7])) {
            fail("INVALID_ARG_7:" + args[7]);
        }
    }

    /**
     * Reads a file with NOFOLLOW, returning null on any failure.
     */
    static byte[] readStable(Path path, String label) {
        return readStableBounded(path, label, Long.MAX_VALUE);
    }

    /**
     * Reads a file with NOFOLLOW, bounded to maxBytes, returning null on any failure.
     */
    static byte[] readStableBounded(Path path, String label, long maxBytes) {
        try {
            BasicFileAttributes before = Files.readAttributes(
                    path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.isSymbolicLink()) {
                return null;
            }
            if (before.size() > maxBytes) {
                return null;
            }
            try (var channel = java.nio.file.Files.newByteChannel(
                    path, java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes opened = Files.readAttributes(
                        path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (!sameFile(before, opened)) {
                    return null;
                }
                var baos = new java.io.ByteArrayOutputStream();
                var buf = java.nio.ByteBuffer.allocate(8192);
                while (channel.read(buf) >= 0) {
                    buf.flip();
                    baos.write(buf.array(), buf.arrayOffset(), buf.remaining());
                    buf.clear();
                    if (baos.size() > maxBytes) {
                        return null;
                    }
                }
                BasicFileAttributes after = Files.readAttributes(
                        path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (!sameFile(before, after)) {
                    return null;
                }
                return baos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean sameFile(BasicFileAttributes a, BasicFileAttributes b) {
        return a.isRegularFile()
                && !a.isSymbolicLink()
                && a.size() == b.size()
                && a.lastModifiedTime().equals(b.lastModifiedTime());
    }

    private static String sha256Hex(byte[] data) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            var sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available");
        }
    }

    private static void fail(String error) {
        throw new CapabilityStudioGateAException(error);
    }
}
