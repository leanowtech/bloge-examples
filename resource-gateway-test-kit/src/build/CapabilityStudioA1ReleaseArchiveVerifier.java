/*
 * Standalone archive boundary verifier for Capability Studio A1 Step 0.
 * Runs as a Java source-file launcher and intentionally has no dependencies.
 */
public final class CapabilityStudioA1ReleaseArchiveVerifier {
    private static final String A1_PREFIX = "schemas/resource-gateway-capability-studio-a1/";
    private static final String LEGACY_PREFIX = "schemas/resource-gateway-capability-studio/";
    private static final String MAVEN_PREFIX =
            "META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/";

    // acceptance-engine-v1 Wire authority paths
    private static final String ACCEPTANCE_PREFIX = "acceptance-engine-v1/";
    private static final java.util.Set<String> ACCEPTANCE_AUTHORITY_PATHS = java.util.Set.of(
            ACCEPTANCE_PREFIX + "builtin-contract-catalog.json",
            ACCEPTANCE_PREFIX + "builtin-compiler-profile-formal-v1.json",
            ACCEPTANCE_PREFIX + "rg-cs-felt-v1.acceptance.plan.json"
    );

    private static final java.util.Set<String> AUTHORITATIVE_PATHS = java.util.Set.of(
            A1_PREFIX + "acceptance-receipt-v1.schema.json",
            A1_PREFIX + "attack-case-v1.schema.json",
            A1_PREFIX + "compiler-manifest-v1.schema.json",
            A1_PREFIX + "evidence-catalog-entry-v1.schema.json",
            A1_PREFIX + "hermetic-observation-v1.schema.json",
            A1_PREFIX + "ledger-entry-v1.schema.json",
            A1_PREFIX + "normative-primitives-v1.schema.json",
            A1_PREFIX + "observation-receipt-v1.schema.json",
            A1_PREFIX + "observer-failure-v1.schema.json",
            A1_PREFIX + "oracle-manifest-v1.schema.json",
            A1_PREFIX + "revocation-record-v1.schema.json",
            A1_PREFIX + "source-package-v1.schema.json",
            A1_PREFIX + "source-unit-v1.schema.json"
    );
    private static final java.util.Set<String> PROTOCOL_ALLOWED_FILES = protocolAllowedFiles();
    private static final int EXIT_FAIL = 1;
    private static final int EXIT_CONFIG = 2;
    private static final int EXIT_JAR = 3;

    private CapabilityStudioA1ReleaseArchiveVerifier() { }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: java CapabilityStudioA1ReleaseArchiveVerifier.java "
                    + "<normal-jar> <shaded-cli-jar> <a1-protocol-jar> <legacy-schema-root>");
            System.exit(EXIT_CONFIG);
        }
        java.util.Set<String> expectedLegacySchemas = readExpectedLegacySchemas(args[3]);
        java.util.Set<String> expectedAcceptance = ACCEPTANCE_AUTHORITY_PATHS;
        int failures = verifyHelpers();
        failures += verifyCompatibilityJar("normal", args[0], expectedLegacySchemas, expectedAcceptance);
        failures += verifyCompatibilityJar("shaded-cli", args[1], expectedLegacySchemas, expectedAcceptance);
        failures += verifyProtocolJar("a1-protocol", args[2]);
        if (failures > 0) {
            System.err.println("A1 ARCHIVE BOUNDARY FAILED: " + failures + " check(s)");
            System.exit(EXIT_FAIL);
        }
        System.out.println("A1 ARCHIVE BOUNDARY PASSED");
    }

    private static java.util.Set<String> protocolAllowedFiles() {
        java.util.Set<String> files = new java.util.HashSet<>(AUTHORITATIVE_PATHS);
        files.add("META-INF/MANIFEST.MF");
        files.add(MAVEN_PREFIX + "pom.xml");
        files.add(MAVEN_PREFIX + "pom.properties");
        return java.util.Set.copyOf(files);
    }

    private static int verifyHelpers() {
        int failures = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (recordDuplicate(seen, "schemas/a.json")
                || !recordDuplicate(seen, "schemas/a.json")) {
            System.err.println("FAIL [self-test]: duplicate detection");
            failures++;
        }
        for (String path : java.util.List.of(
                "/absolute", "C:/absolute", "a\\b", "a/../b", "a/./b", "a//b")) {
            if (isNormalizedEntryName(path, false)) {
                System.err.println("FAIL [self-test]: invalid ZIP path accepted: " + path);
                failures++;
            }
        }
        if (!isNormalizedEntryName("META-INF/MANIFEST.MF", false)
                || !isNormalizedEntryName("schemas/", true)) {
            System.err.println("FAIL [self-test]: normalized ZIP path rejected");
            failures++;
        }
        return failures;
    }

    private static int verifyCompatibilityJar(
            String label, String jarPath, java.util.Set<String> expectedLegacySchemas,
            java.util.Set<String> expectedAcceptance) {
        ArchiveInventory inventory = readArchive(label, jarPath);
        int failures = inventory.commonFailures(label);
        java.util.Set<String> leaked = inventory.filesWithPrefix(A1_PREFIX);
        if (!leaked.isEmpty()) {
            System.err.println("FAIL [" + label + "]: Target A1 resources leaked into compatibility JAR:");
            leaked.forEach(path -> System.err.println("  " + path));
            failures++;
        }
        java.util.Set<String> actualLegacySchemas = inventory.filesWithPrefix(LEGACY_PREFIX);
        if (!actualLegacySchemas.equals(expectedLegacySchemas)) {
            java.util.Set<String> missing = new java.util.TreeSet<>(expectedLegacySchemas);
            missing.removeAll(actualLegacySchemas);
            java.util.Set<String> unexpected = new java.util.TreeSet<>(actualLegacySchemas);
            unexpected.removeAll(expectedLegacySchemas);
            System.err.println("FAIL [" + label + "]: Legacy compatibility schema inventory drifted");
            missing.forEach(path -> System.err.println("  missing: " + path));
            unexpected.forEach(path -> System.err.println("  unexpected: " + path));
            failures++;
        }
        // acceptance-engine-v1 Wire authority: exact 3 files, no more, no less
        java.util.Set<String> actualAcceptance = inventory.filesWithPrefix(ACCEPTANCE_PREFIX);
        if (!actualAcceptance.equals(expectedAcceptance)) {
            java.util.Set<String> missing = new java.util.TreeSet<>(expectedAcceptance);
            missing.removeAll(actualAcceptance);
            java.util.Set<String> extra = new java.util.TreeSet<>(actualAcceptance);
            extra.removeAll(expectedAcceptance);
            System.err.println("FAIL [" + label + "]: acceptance-engine-v1 authority inventory mismatch");
            missing.forEach(p -> System.err.println("  missing: " + p));
            extra.forEach(p -> System.err.println("  extra: " + p));
            failures++;
        }
        System.out.println("[" + label + "] inventory: files=" + inventory.files.size()
                + ", legacySchemas=" + actualLegacySchemas.size()
                + ", targetA1Schemas=" + inventory.schemaCount(A1_PREFIX)
                + ", acceptanceAuthority=" + actualAcceptance.size());
        printResult(label, failures);
        return failures;
    }

    private static java.util.Set<String> readExpectedLegacySchemas(String rootPath) {
        java.nio.file.Path root = java.nio.file.Path.of(rootPath).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || java.nio.file.Files.isSymbolicLink(root)) {
            System.err.println("ERROR [legacy-authority]: not a real directory: " + rootPath);
            System.exit(EXIT_CONFIG);
        }
        java.util.Set<String> expected = new java.util.TreeSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(root)) {
            stream.sorted().forEach(path -> {
                if (java.nio.file.Files.isSymbolicLink(path)
                        || !java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    System.err.println("ERROR [legacy-authority]: non-regular entry: " + path);
                    System.exit(EXIT_CONFIG);
                }
                String name = path.getFileName().toString();
                if (name.endsWith(".schema.json")) {
                    expected.add(LEGACY_PREFIX + name);
                }
            });
        } catch (java.io.IOException exception) {
            System.err.println("ERROR [legacy-authority]: " + exception.getMessage());
            System.exit(EXIT_CONFIG);
        }
        if (expected.isEmpty()) {
            System.err.println("ERROR [legacy-authority]: no schemas found");
            System.exit(EXIT_CONFIG);
        }
        return java.util.Set.copyOf(expected);
    }

    private static int verifyProtocolJar(String label, String jarPath) {
        ArchiveInventory inventory = readArchive(label, jarPath);
        int failures = inventory.commonFailures(label);
        // acceptance-engine-v1 Wire authority must NOT be in a1-protocol JAR
        java.util.Set<String> leakedAcceptance = inventory.filesWithPrefix(ACCEPTANCE_PREFIX);
        if (!leakedAcceptance.isEmpty()) {
            System.err.println("FAIL [" + label + "]: acceptance-engine-v1 Wire authority leaked into a1-protocol JAR:");
            leakedAcceptance.forEach(path -> System.err.println("  " + path));
            failures++;
        }
        java.util.Set<String> missing = new java.util.TreeSet<>(PROTOCOL_ALLOWED_FILES);
        missing.removeAll(inventory.files);
        java.util.Set<String> unexpected = new java.util.TreeSet<>(inventory.files);
        unexpected.removeAll(PROTOCOL_ALLOWED_FILES);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            System.err.println("FAIL [" + label + "]: resource-only inventory is not closed");
            missing.forEach(path -> System.err.println("  missing: " + path));
            unexpected.forEach(path -> System.err.println("  unexpected: " + path));
            failures++;
        }
        java.util.Set<String> a1Schemas = inventory.filesWithPrefix(A1_PREFIX);
        if (!a1Schemas.equals(AUTHORITATIVE_PATHS)) {
            System.err.println("FAIL [" + label + "]: expected the exact 13 Target A1 schema paths");
            failures++;
        }
        java.util.Set<String> unexpectedDirectories = new java.util.TreeSet<>();
        for (String directory : inventory.directories) {
            if (PROTOCOL_ALLOWED_FILES.stream().noneMatch(path -> path.startsWith(directory))) {
                unexpectedDirectories.add(directory);
            }
        }
        if (!unexpectedDirectories.isEmpty()) {
            System.err.println("FAIL [" + label + "]: unexpected directories:");
            unexpectedDirectories.forEach(path -> System.err.println("  " + path));
            failures++;
        }
        java.util.Set<String> forbidden = new java.util.TreeSet<>();
        for (String path : inventory.files) {
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".class") || lower.endsWith(".py") || lower.endsWith(".pyc")
                    || path.startsWith(LEGACY_PREFIX)
                    || lower.contains("/scripts/") || lower.startsWith("scripts/")
                    || lower.contains("fixture") || lower.contains("quarantine")
                    || lower.contains("gate-a1-step0") || lower.contains("step0-manifest")) {
                forbidden.add(path);
            }
        }
        if (!forbidden.isEmpty()) {
            System.err.println("FAIL [" + label + "]: non-protocol material present:");
            forbidden.forEach(path -> System.err.println("  " + path));
            failures++;
        }
        System.out.println("[" + label + "] inventory: files=" + inventory.files.size()
                + ", targetA1Schemas=" + a1Schemas.size()
                + ", classes=0, legacySchemas=0, nonReleaseInstances=0");
        printResult(label, failures);
        return failures;
    }

    private static ArchiveInventory readArchive(String label, String jarPath) {
        java.io.File file = new java.io.File(jarPath);
        if (!file.isFile() || !file.canRead()) {
            System.err.println("ERROR [" + label + "]: JAR not readable: " + jarPath);
            System.exit(EXIT_JAR);
        }
        ArchiveInventory inventory = new ArchiveInventory();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!isNormalizedEntryName(name, entry.isDirectory())) {
                    inventory.invalidPaths.add(name);
                }
                if (recordDuplicate(inventory.seen, name)) {
                    inventory.duplicates.add(name);
                }
                if (entry.isDirectory()) {
                    inventory.directories.add(name);
                } else {
                    inventory.files.add(name);
                }
            }
        } catch (java.io.IOException exception) {
            System.err.println("ERROR [" + label + "]: invalid JAR: " + exception.getMessage());
            System.exit(EXIT_JAR);
        }
        return inventory;
    }

    private static void printResult(String label, int failures) {
        System.out.println("[" + label + "] " + (failures == 0 ? "PASS" : "FAIL"));
    }

    private static boolean recordDuplicate(java.util.Set<String> seen, String name) {
        return !seen.add(name);
    }

    private static boolean isNormalizedEntryName(String name, boolean directory) {
        if (name == null || name.isEmpty() || name.indexOf('\\') >= 0
                || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            if (name.charAt(index) <= 0x1f) {
                return false;
            }
        }
        String path = name;
        if (directory) {
            if (!path.endsWith("/") || path.length() == 1) {
                return false;
            }
            path = path.substring(0, path.length() - 1);
        } else if (path.endsWith("/")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static final class ArchiveInventory {
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        private final java.util.Set<String> files = new java.util.TreeSet<>();
        private final java.util.Set<String> directories = new java.util.TreeSet<>();
        private final java.util.Set<String> duplicates = new java.util.TreeSet<>();
        private final java.util.Set<String> invalidPaths = new java.util.TreeSet<>();

        private int commonFailures(String label) {
            int failures = 0;
            if (!duplicates.isEmpty()) {
                System.err.println("FAIL [" + label + "]: duplicate ZIP entries: " + duplicates);
                failures++;
            }
            if (!invalidPaths.isEmpty()) {
                System.err.println("FAIL [" + label + "]: unsafe ZIP paths: " + invalidPaths);
                failures++;
            }
            return failures;
        }

        private java.util.Set<String> filesWithPrefix(String prefix) {
            java.util.Set<String> matches = new java.util.TreeSet<>();
            files.stream().filter(path -> path.startsWith(prefix)).forEach(matches::add);
            return matches;
        }

        private long schemaCount(String prefix) {
            return files.stream()
                    .filter(path -> path.startsWith(prefix) && path.endsWith(".schema.json"))
                    .count();
        }
    }
}
