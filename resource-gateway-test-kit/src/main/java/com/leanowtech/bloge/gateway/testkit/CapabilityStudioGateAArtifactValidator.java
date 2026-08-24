package com.leanowtech.bloge.gateway.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

final class CapabilityStudioGateAArtifactValidator {

    private static final String EXPECTED_MAIN_CLASS =
            "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli";
    private static final String DEP_PREFIX = "META-INF/gate-a/dependencies/";

    private CapabilityStudioGateAArtifactValidator() {}

    static final class ValidationResult {
        public final Manifest manifest;
        public final Map<String, byte[]> rawEntries;
        public final List<String> requiredJarEntriesMissing;
        public final Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> dependencyPins;

        ValidationResult(Manifest manifest, Map<String, byte[]> rawEntries,
                        List<String> requiredJarEntriesMissing,
                        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> dependencyPins) {
            this.manifest = manifest;
            this.rawEntries = Collections.unmodifiableMap(new LinkedHashMap<>(rawEntries));
            this.requiredJarEntriesMissing = Collections.unmodifiableList(new ArrayList<>(requiredJarEntriesMissing));
            this.dependencyPins = Collections.unmodifiableMap(new LinkedHashMap<>(dependencyPins));
        }

        boolean isValid() { return requiredJarEntriesMissing.isEmpty(); }
    }

    static ValidationResult validate(
            byte[] rawArtifact,
            Map<String, Object> limits,
            List<String> requiredJarEntries,
            Map<String, DependencyPin> depPins,
            Path expectedArtifactPath) {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> authorityPins =
                new LinkedHashMap<>();
        for (Map.Entry<String, DependencyPin> e : depPins.entrySet()) {
            DependencyPin pin = e.getValue();
            Map<String, String> coord = new HashMap<>();
            coord.put("groupId", "com.leanowtech.bloge");
            coord.put("artifactId", "bloge-unknown");
            coord.put("version", "0.0.0");
            authorityPins.put(e.getKey(),
                    new CapabilityStudioGateAAuthorityValidator.DependencyPin(
                            pin.lockId, pin.filename, pin.sha256,
                            coord, "compile", pin.filename));
        }
        return validate(rawArtifact, limits, requiredJarEntries, authorityPins, expectedArtifactPath, false);
    }

    static ValidationResult validate(
            byte[] rawArtifact,
            Map<String, Object> limits,
            List<String> requiredJarEntries,
            Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins,
            Path expectedArtifactPath,
            boolean enforceCodeSource) {

        long maxRawBytes = asPositiveLong(limits.get("maxRawBytes"), "maxRawBytes");
        if (rawArtifact.length > maxRawBytes) {
            fail("ARTIFACT_RAW_BYTES_LIMIT:" + rawArtifact.length + ">" + maxRawBytes);
        }

        int maxZipEntries = (int) asPositiveLong(limits.get("maxZipEntries"), "maxZipEntries");
        long maxSingleEntryBytes = asPositiveLong(limits.get("maxSingleEntryBytes"), "maxSingleEntryBytes");
        long maxTotalUncompressedBytes = asPositiveLong(limits.get("maxTotalUncompressedBytes"), "maxTotalUncompressedBytes");

        Object ratioVal = limits.get("maxCompressionRatio");
        if (!(ratioVal instanceof Number) || ((Number) ratioVal).doubleValue() <= 0) {
            fail("ARTIFACT_MAX_COMPRESSION_RATIO_INVALID");
        }
        double maxCompressionRatio = ((Number) ratioVal).doubleValue();

        if (expectedArtifactPath != null) {
            BasicFileAttributes attrs = readBasicAttributesFailClosed(expectedArtifactPath, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) {
                fail("ARTIFACT_PATH_NOT_REGULAR:" + expectedArtifactPath.getFileName());
            }

            String fileKeyBefore = String.valueOf(attrs.fileKey());
            long sizeBefore = attrs.size();
            long mtimeBefore = attrs.lastModifiedTime().toMillis();

            try {
                byte[] pathBytes = Files.readAllBytes(expectedArtifactPath);
                if (pathBytes.length != rawArtifact.length) {
                    fail("ARTIFACT_PATH_RAW_BYTES_MISMATCH");
                }
                if (!constantTimeEquals(pathBytes, rawArtifact)) {
                    fail("ARTIFACT_PATH_RAW_BYTES_MISMATCH");
                }
            } catch (IOException e) {
                fail("ARTIFACT_PATH_READ_ERROR:" + e.getClass().getSimpleName());
            }

            BasicFileAttributes attrsAfter = readBasicAttributesFailClosed(expectedArtifactPath, LinkOption.NOFOLLOW_LINKS);
            if (!String.valueOf(attrsAfter.fileKey()).equals(fileKeyBefore)) {
                fail("ARTIFACT_PATH_FILEKEY_CHANGED:" + expectedArtifactPath.getFileName());
            }
            if (attrsAfter.size() != sizeBefore) {
                fail("ARTIFACT_PATH_SIZE_CHANGED:" + expectedArtifactPath.getFileName());
            }
            if (attrsAfter.lastModifiedTime().toMillis() != mtimeBefore) {
                fail("ARTIFACT_PATH_MTIME_CHANGED:" + expectedArtifactPath.getFileName());
            }
        }

        if (enforceCodeSource) {
            validateCodeSource(rawArtifact, expectedArtifactPath);
        }

        boolean isSynthetic = (expectedArtifactPath == null);
        Path tempFile = null;
        try {
            if (isSynthetic) {
                tempFile = Files.createTempFile("artifact-", ".jar");
                Files.write(tempFile, rawArtifact);
            } else {
                tempFile = expectedArtifactPath;
            }

            BasicFileAttributes attrsBefore = readBasicAttributesFailClosed(tempFile, LinkOption.NOFOLLOW_LINKS);
            String fileKeyBefore = String.valueOf(attrsBefore.fileKey());
            long sizeBefore = attrsBefore.size();
            long mtimeBefore = attrsBefore.lastModifiedTime().toMillis();

            ValidationResult result = validateJarFromPath(tempFile, maxZipEntries, maxSingleEntryBytes,
                    maxTotalUncompressedBytes, maxCompressionRatio, requiredJarEntries, depPins);

            BasicFileAttributes attrsAfter = readBasicAttributesFailClosed(tempFile, LinkOption.NOFOLLOW_LINKS);
            if (!String.valueOf(attrsAfter.fileKey()).equals(fileKeyBefore)) {
                fail("ARTIFACT_PATH_FILEKEY_CHANGED:" + tempFile.getFileName());
            }
            if (attrsAfter.size() != sizeBefore) {
                fail("ARTIFACT_PATH_SIZE_CHANGED:" + tempFile.getFileName());
            }
            if (attrsAfter.lastModifiedTime().toMillis() != mtimeBefore) {
                fail("ARTIFACT_PATH_MTIME_CHANGED:" + tempFile.getFileName());
            }

            return result;
        } catch (IOException e) {
            fail("ARTIFACT_PATH_IO_ERROR:" + e.getClass().getSimpleName());
            throw new AssertionError("unreachable");
        } finally {
            if (isSynthetic && tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {}
            }
        }
    }

    private static BasicFileAttributes readBasicAttributesFailClosed(Path path, LinkOption... options) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, options);
        } catch (IOException e) {
            fail("ARTIFACT_PATH_ATTRIBUTES_ERROR:" + e.getClass().getSimpleName());
            throw new AssertionError("unreachable");
        }
    }

    private static ValidationResult validateJarFromPath(
            Path jarPath,
            int maxZipEntries,
            long maxSingleEntryBytes,
            long maxTotalUncompressedBytes,
            double maxCompressionRatio,
            List<String> requiredJarEntries,
            Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins) throws IOException {

        JarFile jarFile = null;
        try {
            jarFile = new JarFile(jarPath.toFile(), false);

            int entryCount = jarFile.size();
            if (entryCount > maxZipEntries) {
                fail("ARTIFACT_ENTRY_COUNT_LIMIT:" + entryCount + ">" + maxZipEntries);
            }

            Set<String> normalizedNames = new HashSet<>();
            Map<String, byte[]> rawEntries = new LinkedHashMap<>();
            long totalUncompressed = 0;
            List<String> extraDependencyJars = new ArrayList<>();

            for (ZipEntry zipEntry : Collections.list(jarFile.entries())) {
                String name = zipEntry.getName();
                validateEntryName(name, normalizedNames);

                int csize = (int) zipEntry.getCompressedSize();
                int usize = (int) zipEntry.getSize();
                int method = zipEntry.getMethod();

                if (csize < 0 || usize < 0) {
                    fail("ARTIFACT_ENTRY_SIZE_ANOMALY:" + name);
                }

                if (name.endsWith("/")) {
                    rawEntries.put(name, new byte[0]);
                    continue;
                }

                if (usize > maxSingleEntryBytes) {
                    fail("ARTIFACT_ENTRY_SIZE_LIMIT:" + name + ":" + usize + ">" + maxSingleEntryBytes);
                }

                if (name.startsWith(DEP_PREFIX) && name.endsWith(".jar")) {
                    boolean isKnownDep = depPins.values().stream()
                            .anyMatch(p -> p.entryPath.equals(name));
                    if (!isKnownDep) {
                        extraDependencyJars.add(name);
                    }
                }

                byte[] content;
                try (InputStream is = jarFile.getInputStream(zipEntry)) {
                    content = readBounded(is, maxSingleEntryBytes);
                }

                if (content.length > maxSingleEntryBytes) {
                    fail("ARTIFACT_ENTRY_SIZE_LIMIT:" + name + ":" + content.length + ">" + maxSingleEntryBytes);
                }

                if (method == ZipEntry.DEFLATED && usize > 0 && csize > 0) {
                    double ratio = (double) usize / csize;
                    if (ratio > maxCompressionRatio) {
                        fail("ARTIFACT_COMPRESSION_RATIO_EXCEEDED:" + name + ":" + ratio + ">" + maxCompressionRatio);
                    }
                }

                if (usize == 0 && content.length > 0) {
                    fail("ARTIFACT_ENTRY_ZERO_SIZE_MISMATCH:" + name);
                }

                totalUncompressed += content.length;
                if (totalUncompressed > maxTotalUncompressedBytes) {
                    fail("ARTIFACT_TOTAL_UNCOMPRESSED_LIMIT:" + totalUncompressed + ">" + maxTotalUncompressedBytes);
                }

                rawEntries.put(name, content);
            }

            if (!extraDependencyJars.isEmpty()) {
                fail("ARTIFACT_EXTRA_DEPENDENCY_JAR:" + String.join(",", extraDependencyJars));
            }

            Manifest manifest = jarFile.getManifest();
            String mainClass = null;
            if (manifest != null) {
                mainClass = manifest.getMainAttributes().getValue("Main-Class");
                if (!EXPECTED_MAIN_CLASS.equals(mainClass)) {
                    fail("ARTIFACT_MAIN_CLASS_MISMATCH:" + mainClass);
                }
            } else {
                fail("ARTIFACT_MANIFEST_MISSING");
            }

            List<String> missing = new ArrayList<>();
            for (String required : requiredJarEntries) {
                if (!rawEntries.containsKey(required)) {
                    missing.add(required);
                }
            }

            Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> verifiedPins = new LinkedHashMap<>();
            for (Map.Entry<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> e : depPins.entrySet()) {
                CapabilityStudioGateAAuthorityValidator.DependencyPin pin = e.getValue();
                String entryPath = pin.entryPath;
                byte[] entryBytes = rawEntries.get(entryPath);
                if (entryBytes == null) {
                    fail("ARTIFACT_DEPENDENCY_ENTRY_MISSING:" + entryPath);
                }
                String actualFingerprint = CapabilityStudioGateAReceiptCanonicalizer.rawFingerprint(entryBytes);
                if (!actualFingerprint.equals(pin.sha256)) {
                    fail("ARTIFACT_DEPENDENCY_SHA256_MISMATCH:" + entryPath);
                }
                verifiedPins.put(e.getKey(), pin);
            }

            if (verifiedPins.size() != depPins.size()) {
                fail("ARTIFACT_DEPENDENCY_COUNT_MISMATCH:" + verifiedPins.size() + "!=" + depPins.size());
            }

            return new ValidationResult(manifest, rawEntries, missing, verifiedPins);

        } catch (CapabilityStudioGateAException e) {
            throw e;
        } catch (IOException e) {
            fail("ARTIFACT_IO_ERROR:" + e.getClass().getSimpleName());
            throw new AssertionError("unreachable");
        } finally {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private static void validateEntryName(String name, Set<String> normalizedNames) {
        if (name == null || name.isEmpty()) {
            fail("ARTIFACT_ENTRY_EMPTY_NAME");
        }
        if (name.startsWith("/") || name.contains("\\")) {
            fail("ARTIFACT_ENTRY_PATH_INVALID:" + name);
        }
        String normalized = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (normalized.isEmpty()) {
            fail("ARTIFACT_ENTRY_EMPTY_NAME");
        }
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                fail("ARTIFACT_ENTRY_DOT_PATH:" + name);
            }
        }
        if (!normalizedNames.add(normalized)) {
            fail("ARTIFACT_ENTRY_COLLISION:" + normalized);
        }
    }

    private static byte[] readBounded(InputStream is, long bound) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(
                (int) Math.min(Math.min(bound, Integer.MAX_VALUE), Integer.MAX_VALUE));
        byte[] buf = new byte[8192];
        long read = 0;
        int r;
        while ((r = is.read(buf)) != -1) {
            read += r;
            if (bound < Long.MAX_VALUE && read > bound) {
                fail("ARTIFACT_ENTRY_SIZE_LIMIT_EXCEEDED:" + read);
            }
            baos.write(buf, 0, r);
        }
        return baos.toByteArray();
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static void validateCodeSource(byte[] rawArtifact, Path expectedArtifactPath) {
        try {
            Class<?> cliClass = Class.forName(EXPECTED_MAIN_CLASS);
            CodeSource cs = cliClass.getProtectionDomain().getCodeSource();
            if (cs == null) {
                fail("ARTIFACT_CODESOURCE_NULL");
            }
            java.net.URI location;
            try {
                location = cs.getLocation().toURI();
            } catch (java.net.URISyntaxException e) {
                fail("ARTIFACT_CODESOURCE_LOCATION_URI_ERROR:" + e.getMessage());
                throw new AssertionError("unreachable");
            }
            if (location == null) {
                fail("ARTIFACT_CODESOURCE_LOCATION_NULL");
            }
            Path codeSourcePath = Path.of(location);
            BasicFileAttributes attrs = Files.readAttributes(codeSourcePath,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) {
                fail("ARTIFACT_CODESOURCE_NOT_REGULAR_JAR");
            }
            byte[] runningBytes = Files.readAllBytes(codeSourcePath);
            String runningFp = CapabilityStudioGateAReceiptCanonicalizer.rawFingerprint(runningBytes);
            String artifactFp = CapabilityStudioGateAReceiptCanonicalizer.rawFingerprint(rawArtifact);
            if (!runningFp.equals(artifactFp)) {
                fail("ARTIFACT_CODESOURCE_FINGERPRINT_MISMATCH");
            }
        } catch (CapabilityStudioGateAException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            fail("ARTIFACT_CODESOURCE_CLASS_NOT_FOUND");
        } catch (IOException e) {
            fail("ARTIFACT_CODESOURCE_IO_ERROR:" + e.getClass().getSimpleName());
        } catch (Exception e) {
            fail("ARTIFACT_CODESOURCE_CHECK_ERROR:" + e.getClass().getSimpleName());
        }
    }

    private static long asPositiveLong(Object val, String name) {
        if (!(val instanceof Number)) {
            fail("ARTIFACT_LIMIT_INVALID:" + name);
        }
        long v = ((Number) val).longValue();
        if (v <= 0) {
            fail("ARTIFACT_LIMIT_INVALID:" + name);
        }
        return v;
    }

    private static void fail(String error) {
        throw new CapabilityStudioGateAException(error);
    }

    static final class DependencyPin {
        final String lockId;
        final String filename;
        final String sha256;

        DependencyPin(String lockId, String filename, String sha256) {
            this.lockId = lockId;
            this.filename = filename;
            this.sha256 = sha256;
        }

        String bareSha256() {
            return sha256.substring(7);
        }
    }
}
