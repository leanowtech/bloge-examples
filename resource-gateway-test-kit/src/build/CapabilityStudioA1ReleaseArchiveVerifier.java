/*
 * Standalone archive boundary verifier for Capability Studio A1 Step 0.
 * Verifies JAR packaging boundaries: normal/shaded JARs contain the 3 acceptance-engine-v1
 * Wire authority files; a1-protocol JAR excludes them.
 * Runs as a Java source-file launcher and intentionally has no dependencies.
 */
public final class CapabilityStudioA1ReleaseArchiveVerifier {
    // Exact 3 Wire authority source paths under acceptance-engine-v1/
    private static final String ENGINE_V1_PREFIX = "acceptance-engine-v1/";
    private static final String AUTHORITATIVE_SOURCES = ENGINE_V1_PREFIX;
    private static final java.util.Set<String> AUTHORITY_SOURCE_PATHS = java.util.Set.of(
            ENGINE_V1_PREFIX + "builtin-contract-catalog.json",
            ENGINE_V1_PREFIX + "builtin-compiler-profile-formal-v1.json",
            ENGINE_V1_PREFIX + "rg-cs-felt-v1.acceptance.plan.json"
    );
    private static final int EXPECTED_AUTHORITY_COUNT = 3;

    private static final int EXIT_OK = 0;
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
        int failures = verifyHelpers();
        failures += verifyCompatibilityJar("normal", args[0]);
        failures += verifyCompatibilityJar("shaded-cli", args[1]);
        failures += verifyProtocolJar("a1-protocol", args[2]);
        if (failures > 0) {
            System.err.println("A1 ARCHIVE BOUNDARY FAILED: " + failures + " check(s)");
            System.exit(EXIT_FAIL);
        }
        System.out.println("A1 ARCHIVE BOUNDARY PASSED");
        System.exit(EXIT_OK);
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

    /**
     * Verify normal or shaded-cli JAR: must contain exactly the 3 Wire authority files
     * and must NOT contain a1-protocol resources.
     */
    private static int verifyCompatibilityJar(String label, String jarPath) {
        ArchiveInventory inv = readArchive(label, jarPath);
        int failures = inv.commonFailures(label);

        // Check exactly 3 authority files are present
        java.util.Set<String> authorityFiles = new java.util.TreeSet<>();
        for (String path : AUTHORITY_SOURCE_PATHS) {
            if (inv.files.contains(path)) {
                authorityFiles.add(path);
            }
        }
        if (authorityFiles.size() != EXPECTED_AUTHORITY_COUNT) {
            java.util.Set<String> missing = new java.util.TreeSet<>(AUTHORITY_SOURCE_PATHS);
            missing.removeAll(authorityFiles);
            System.err.println("FAIL [" + label + "]: expected exactly " + EXPECTED_AUTHORITY_COUNT
                    + " authority files, found " + authorityFiles.size()
                    + "; missing: " + missing);
            failures++;
        }
        // Verify no a1-protocol resources leaked in
        java.util.Set<String> leaked = inv.filesWithPrefix("schemas/resource-gateway-capability-studio-a1/");
        if (!leaked.isEmpty()) {
            System.err.println("FAIL [" + label + "]: a1-protocol resources leaked into " + label + " JAR:");
            leaked.forEach(path -> System.err.println("  " + path));
            failures++;
        }

        System.out.println("[" + label + "] inventory: files=" + inv.files.size()
                + ", authority=" + authorityFiles.size()
                + ", a1-leaked=" + leaked.size());
        printResult(label, failures);
        return failures;
    }

    /**
     * Verify a1-protocol JAR: must NOT contain the 3 Wire authority files.
     * It contains a1 schemas which are validated separately.
     */
    private static int verifyProtocolJar(String label, String jarPath) {
        ArchiveInventory inv = readArchive(label, jarPath);
        int failures = inv.commonFailures(label);

        // Verify the 3 Wire authority files are NOT in a1 JAR
        java.util.Set<String> found = new java.util.TreeSet<>();
        for (String path : AUTHORITY_SOURCE_PATHS) {
            if (inv.files.contains(path)) {
                found.add(path);
            }
        }
        if (!found.isEmpty()) {
            System.err.println("FAIL [" + label + "]: Wire authority files must not be in a1 JAR:");
            found.forEach(path -> System.err.println("  " + path));
            failures++;
        }
        // Count a1 schema files (should be present in a1-protocol JAR)
        long a1Count = inv.schemaCount("schemas/resource-gateway-capability-studio-a1/");

        System.out.println("[" + label + "] inventory: files=" + inv.files.size()
                + ", authority-present=" + found.size()
                + ", a1Schemas=" + a1Count);
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
                if (!entry.isDirectory()) {
                    inventory.files.add(name);
                } else {
                    inventory.directories.add(name);
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
            for (String path : files) {
                if (path.startsWith(prefix)) {
                    matches.add(path);
                }
            }
            return matches;
        }

        private long schemaCount(String prefix) {
            long count = 0;
            for (String path : files) {
                if (path.startsWith(prefix) && path.endsWith(".schema.json")) {
                    count++;
                }
            }
            return count;
        }
    }
}
