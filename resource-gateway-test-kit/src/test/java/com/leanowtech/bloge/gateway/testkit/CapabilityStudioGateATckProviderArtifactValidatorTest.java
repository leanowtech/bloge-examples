package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for CapabilityStudioGateATckProviderArtifactValidator.
 * All JARs are dynamically built; no TCK module dependency required.
 * Error codes are all fixed strings.
 */
class CapabilityStudioGateATckProviderArtifactValidatorTest {

    @TempDir
    Path temp;

    // ── Authority ─────────────────────────────────────────────────────────

    private CapabilityStudioGateATckProviderRoleSelfTest.TckRoleContract realContract() {
        Path authorityPath = Path.of(
                System.getProperty("user.dir"),
                "..", "docs", "acceptance", "capability-studio", "gate-a-wire-v1",
                "protocol-compiler", "gate-a-protocol-authority-v1.json");
        try {
            byte[] raw = Files.readAllBytes(authorityPath);
            return CapabilityStudioGateATckProviderRoleSelfTest.projectAndValidate(raw);
        } catch (IOException | CapabilityStudioGateAException e) {
            throw new AssertionError("Cannot load real authority contract", e);
        }
    }

    // ── JAR builders ───────────────────────────────────────────────────

    byte[] buildValidProviderJar(byte[] candidateSpiBytes) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);

            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider\n".getBytes(StandardCharsets.UTF_8));

            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());

            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("version=1.0.0\ngroupId=com.leanowtech.bloge\n artifactId=resource-gateway-gate-a-tck-provider\n".getBytes(StandardCharsets.UTF_8));

            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write(buildValidDependenciesJson(candidateSpiBytes));
        }
        return baos.toByteArray();
    }

    byte[] buildValidDependenciesJson(byte[] candidateSpiBytes) throws Exception {
        String spiFp = (candidateSpiBytes != null) ? sha256Hex(candidateSpiBytes) : sha256Hex(new byte[0]);

        Map<String, Object> rawFp = new LinkedHashMap<>();
        rawFp.put("algorithm", "SHA-256");
        rawFp.put("kind", "RAW_BYTES");
        rawFp.put("value", "sha256:" + spiFp);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("coordinate", "com.leanowtech.bloge:bloge-resource-gateway-test-kit:1.0.0:gate-a-candidate");
        entry.put("entryPath", "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        entry.put("rawFingerprint", rawFp);
        entry.put("scope", "provided");

        List<Map<String, Object>> entriesList = new ArrayList<>();
        entriesList.add(entry);

        Map<String, Object> commitBase = new LinkedHashMap<>();
        commitBase.put("entries", entriesList);
        commitBase.put("schemaVersion", "capability-studio.gate-a-dependency-lock-manifest.v1");
        String manifestFp = committed("RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1", canonicalize(commitBase));

        Map<String, Object> fpDict = new LinkedHashMap<>();
        fpDict.put("algorithm", "SHA-256");
        fpDict.put("kind", "AGGREGATE_COMMITMENT");
        fpDict.put("value", "sha256:" + manifestFp);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("entries", entriesList);
        manifest.put("manifestFingerprint", fpDict);
        manifest.put("schemaVersion", "capability-studio.gate-a-dependency-lock-manifest.v1");

        return canonicalize(manifest).getBytes(StandardCharsets.UTF_8);
    }

    byte[] buildValidCandidateJar(Set<String> visibleSchemaIds) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            mf.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);

            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class"));
            jos.write(minimalClassFile());

            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class"));
            jos.write(minimalClassFile());

            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAReceiptCanonicalizer.class"));
            jos.write(minimalClassFile());

            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderRoleSelfTest.class"));
            jos.write(minimalClassFile());

            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderArtifactValidator.class"));
            jos.write(minimalClassFile());

            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAException.class"));
            jos.write(minimalClassFile());

            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/StrictJsonParser.class"));
            jos.write(minimalClassFile());

            for (String schemaId : visibleSchemaIds) {
                String content = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"$id\":\"" + schemaId + "\"}";
                jos.putNextEntry(new JarEntry("schemas/" + schemaId));
                jos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }
        return baos.toByteArray();
    }

    static byte[] minimalClassFile() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        try {
            dos.writeInt(0xCAFEBABE);
            dos.writeShort(0);
            dos.writeShort(61);
            dos.writeShort(1);
            dos.writeByte(10);
            dos.writeShort(0);
            dos.writeShort(0);
            dos.writeShort(0);
            dos.writeShort(0);
            dos.writeShort(0);
            dos.writeShort(0);
            dos.writeShort(0);
            dos.flush();
        } catch (IOException e) { throw new RuntimeException(e); }
        return out.toByteArray();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private String canonicalize(Object value) {
        StringBuilder sb = new StringBuilder();
        canonicalizeTo(value, sb);
        return sb.toString();
    }

    private void canonicalizeTo(Object v, StringBuilder sb) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { sb.append("\"").append(v).append("\""); return; }
        if (v instanceof Number) { sb.append(v); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof List) {
            sb.append("[");
            List<?> list = (List<?>) v;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                canonicalizeTo(list.get(i), sb);
            }
            sb.append("]");
            return;
        }
        if (v instanceof Map) {
            sb.append("{");
            Map<?, ?> m = (Map<?, ?>) v;
            List<?> keys = m.keySet().stream().sorted().toList();
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(",");
                Object key = keys.get(i);
                canonicalizeTo(key, sb);
                sb.append(":");
                canonicalizeTo(m.get(key), sb);
            }
            sb.append("}");
        }
    }

    private String committed(String domain, String canonicalValue) {
        try {
            byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
            byte[] jsonBytes = canonicalValue.getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[domainBytes.length + 1 + jsonBytes.length];
            System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
            combined[domainBytes.length] = 0;
            System.arraycopy(jsonBytes, 0, combined, domainBytes.length + 1, jsonBytes.length);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(combined);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String sha256Hex(byte[] raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] readEntryBytes(byte[] raw, String name) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(raw))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(name) && !e.isDirectory()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = zis.read(buf)) >= 0) baos.write(buf, 0, r);
                    return baos.toByteArray();
                }
            }
        } catch (IOException ex) { throw new RuntimeException(ex); }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // TESTS (15 total)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void validate_providerAndCandidateBothValid_isPassed() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        byte[] providerJar = buildValidProviderJar(candidateSpiBytes);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).isEmpty();
        assertThat(snap.isPassed()).isTrue();
    }

    @Test
    void validate_providerEntryCountWrong_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write(buildValidDependenciesJson(candidateSpiBytes));
        }
        byte[] providerJar = baos.toByteArray();

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_ENTRY_COUNT_MISMATCH);
    }

    @Test
    void validate_providerServiceDescriptorMultiLine_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider\ncom.leanowtech.bloge.gatetckprovider.GateATckProvider2\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("version=1.0.0\ngroupId=com.leanowtech.bloge\n artifactId=resource-gateway-gate-a-tck-provider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write(buildValidDependenciesJson(candidateSpiBytes));
        }
        byte[] providerJar = baos.toByteArray();

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_SPI_DESCRIPTOR_MULTI);
    }

    @Test
    void validate_providerServiceDescriptorNoLf_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("version=1.0.0\ngroupId=com.leanowtech.bloge\n artifactId=resource-gateway-gate-a-tck-provider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write(buildValidDependenciesJson(candidateSpiBytes));
        }
        byte[] providerJar = baos.toByteArray();

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_SPI_DESCRIPTOR_NO_LF);
    }

    @Test
    void validate_providerServiceDescriptorWrongClass_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.WrongClass\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("version=1.0.0\ngroupId=com.leanowtech.bloge\n artifactId=resource-gateway-gate-a-tck-provider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write(buildValidDependenciesJson(candidateSpiBytes));
        }
        byte[] providerJar = baos.toByteArray();

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_SPI_DESCRIPTOR_CLASS);
    }

    @Test
    void validate_providerServiceDescriptorHasExtraClass_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider\ncom.leanowtech.bloge.gatetckprovider.Extra\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/Extra.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("version=1.0.0\ngroupId=com.leanowtech.bloge\n artifactId=resource-gateway-gate-a-tck-provider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write(buildValidDependenciesJson(candidateSpiBytes));
        }
        byte[] providerJar = baos.toByteArray();

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_SPI_DESCRIPTOR_MULTI);
    }

    @Test
    void validate_providerDescriptorMalformedUtf8_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            byte[] validPrefix = "com.leanowtech.bloge.gatetckprovider.GateATckProvider\n".getBytes(StandardCharsets.UTF_8);
            byte[] badBytes = new byte[validPrefix.length + 3];
            System.arraycopy(validPrefix, 0, badBytes, 0, validPrefix.length);
            badBytes[validPrefix.length]     = (byte) 0x80;
            badBytes[validPrefix.length + 1] = (byte) 0x80;
            badBytes[validPrefix.length + 2] = (byte) 0x80;
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write(badBytes);
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("version=1.0.0\ngroupId=com.leanowtech.bloge\n artifactId=resource-gateway-gate-a-tck-provider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write(buildValidDependenciesJson(candidateSpiBytes));
        }
        byte[] providerJar = baos.toByteArray();

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_SPI_DESCRIPTOR_ENCODING);
        assertThat(snap.errors).doesNotContain(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_SPI_DESCRIPTOR_NO_LF);
        assertThat(snap.errors).doesNotContain(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_SPI_DESCRIPTOR_CLASS);
    }

    @Test
    void scan_providerEntryRatioExceedsTinyLimit_reportsFixedCode() throws Exception {
        var tinyLimits = new CapabilityStudioGateATckProviderArtifactValidator.ArchiveLimits(
                100L, 1024L, 10240L, 2.0, 128);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("v=1\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write("{\"schemaVersion\":\"capability-studio.gate-a-dependency-lock-manifest.v1\",\"entries\":[]}".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("data/chunk.bin"));
            jos.write(new byte[10240]);
        }
        byte[] providerJar = baos.toByteArray();
        Path provPath = temp.resolve("prov.jar");
        Files.write(provPath, providerJar);

        List<String> errors = new ArrayList<>();
        CapabilityStudioGateATckProviderArtifactValidator.scanViaZipFile(
                provPath, "PROVIDER", tinyLimits, errors);

        assertThat(errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_ENTRY_RATIO_EXCEEDED);
    }

    @Test
    void scan_providerTotalUncompressedExceedsTinyLimit_reportsFixedCode() throws Exception {
        var tinyLimits = new CapabilityStudioGateATckProviderArtifactValidator.ArchiveLimits(
                100L, 8192L, 10240L, 100.0, 128);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("v=1\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write("{\"schemaVersion\":\"capability-studio.gate-a-dependency-lock-manifest.v1\",\"entries\":[]}".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 3; i++) {
                byte[] chunk = new byte[4096];
                int seed = 0x9e3779b9 + i;
                for (int j = 0; j < chunk.length; j++) {
                    seed = (int) ((seed * 1103515245L + 12345) & 0x7fffffffL);
                    chunk[j] = (byte) seed;
                }
                jos.putNextEntry(new JarEntry("data/chunk" + i + ".bin"));
                jos.write(chunk);
            }
        }
        byte[] providerJar = baos.toByteArray();
        Path provPath = temp.resolve("prov.jar");
        Files.write(provPath, providerJar);

        List<String> errors = new ArrayList<>();
        CapabilityStudioGateATckProviderArtifactValidator.scanViaZipFile(
                provPath, "PROVIDER", tinyLimits, errors);

        assertThat(errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_TOTAL_UNCOMPRESSED_EXCEEDED);
    }

    @Test
    void validate_candidateMissingCli_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            mf.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            // CLI — OMITTED
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAReceiptCanonicalizer.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderRoleSelfTest.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderArtifactValidator.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAException.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/StrictJsonParser.class"));
            jos.write(minimalClassFile());
            for (String schemaId : visible) {
                String content = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"$id\":\"" + schemaId + "\"}";
                jos.putNextEntry(new JarEntry("schemas/" + schemaId));
                jos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }
        byte[] candidateJar = baos.toByteArray();
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        byte[] providerJar = buildValidProviderJar(candidateSpiBytes);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_CANDIDATE_MISSING_CLI);
    }

    @Test
    void validate_candidateMissingSpi_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            mf.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class"));
            jos.write(minimalClassFile());
            // SPI — OMITTED
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAReceiptCanonicalizer.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderRoleSelfTest.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderArtifactValidator.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAException.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/StrictJsonParser.class"));
            jos.write(minimalClassFile());
            for (String schemaId : visible) {
                String content = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"$id\":\"" + schemaId + "\"}";
                jos.putNextEntry(new JarEntry("schemas/" + schemaId));
                jos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }
        byte[] candidateJar = baos.toByteArray();
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        byte[] providerJar = buildValidProviderJar(candidateSpiBytes);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_CANDIDATE_MISSING_SPI);
    }

    @Test
    void validate_candidateMissingRoleSelfTest_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            mf.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAReceiptCanonicalizer.class"));
            jos.write(minimalClassFile());
            // RoleSelfTest — OMITTED
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderArtifactValidator.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAException.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/StrictJsonParser.class"));
            jos.write(minimalClassFile());
            for (String schemaId : visible) {
                String content = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"$id\":\"" + schemaId + "\"}";
                jos.putNextEntry(new JarEntry("schemas/" + schemaId));
                jos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }
        byte[] candidateJar = baos.toByteArray();
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        byte[] providerJar = buildValidProviderJar(candidateSpiBytes);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_CANDIDATE_MISSING_PROJECTION);
    }

    @Test
    void validate_providerManifestMultiRelease_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            mf.getMainAttributes().putValue("Multi-Release", "true");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("version=1.0.0\ngroupId=com.leanowtech.bloge\n artifactId=resource-gateway-gate-a-tck-provider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write(buildValidDependenciesJson(candidateSpiBytes));
        }
        byte[] providerJar = baos.toByteArray();

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_MANIFEST_MULTI_RELEASE);
    }

    @Test
    void snapshot_mutableInputsAreDefensivelyCopied() throws Exception {
        Map<String, String> provEntries = new LinkedHashMap<>();
        provEntries.put("META-INF/MANIFEST.MF", "fp1");
        Map<String, String> schemas = new LinkedHashMap<>();
        schemas.put("visible-schema-1", "fp2");
        List<String> errors = new ArrayList<>();
        errors.add("ERROR_ONE");

        var snap = new CapabilityStudioGateATckProviderArtifactValidator.ValidationSnapshot(
                "provFp", provEntries, "candFp", "spiFp", schemas, errors);

        provEntries.put("EXTRA_ENTRY", "fp_extra");
        schemas.put("extra-schema", "fp_extra");
        errors.add("ERROR_TWO");

        assertThat(snap.providerEntryFingerprints).doesNotContainKey("EXTRA_ENTRY");
        assertThat(snap.candidateSchemaFingerprints).doesNotContainKey("extra-schema");
        assertThat(snap.errors).hasSize(1);
        assertThat(snap.errors).containsExactly("ERROR_ONE");

        assertThatThrownBy(() ->
                ((java.util.Map<String, String>) snap.providerEntryFingerprints).put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() ->
                ((java.util.List<String>) snap.errors).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validate_providerRawSizeExceeds16MiB_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        byte[] providerJar = buildValidProviderJar(candidateSpiBytes);

        byte[] oversizedProvider = new byte[17 * 1024 * 1024];
        Arrays.fill(oversizedProvider, (byte) 'X');

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, oversizedProvider);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                oversizedProvider, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_RAW_SIZE_EXCEEDED);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helper: build provider JAR with custom dep manifest bytes
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parses depJson via StrictJsonParser, mutates it with the supplied consumer,
     * optionally recomputes manifestFingerprint over {schemaVersion, entries},
     * then builds a valid provider JAR using the resulting dep bytes.
     * Requires candidateJar bytes for SPI fingerprint in the dep manifest.
     */
    private byte[] buildProviderJarWithDeps(byte[] candidateJar,
            java.util.function.Consumer<java.util.Map<String, Object>> depMutator,
            boolean recomputeFingerprint) throws Exception {
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        Map<String, Object> man = StrictJsonParser.parse(
                buildValidDependenciesJson(candidateSpiBytes));
        depMutator.accept(man);
        if (recomputeFingerprint) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) man.get("entries");
            String schemaVer = (String) man.get("schemaVersion");
            Map<String, Object> commitBase = new LinkedHashMap<>();
            commitBase.put("schemaVersion", schemaVer);
            commitBase.put("entries", entries);
            String newFp = committed("RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1", canonicalize(commitBase));
            @SuppressWarnings("unchecked")
            Map<String, Object> fpDict = (Map<String, Object>) man.get("manifestFingerprint");
            fpDict.put("value", "sha256:" + newFp);
        }
        byte[] depBytes = canonicalize(man).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("version=1.0.0\ngroupId=com.leanowtech.bloge\n artifactId=resource-gateway-gate-a-tck-provider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write(depBytes);
        }
        return baos.toByteArray();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Dep-manifest coverage (7 tests)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Wrong coordinate in dep entry → E_PROVIDER_DEP_COORDINATE.
     * Recomputes manifestFingerprint so fingerprint check passes.
     */
    @Test
    void validate_depWrongCoordinate_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);

        byte[] providerJar = buildProviderJarWithDeps(candidateJar,
                m -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = ((java.util.List<Map<String, Object>>) m.get("entries")).get(0);
                    entry.put("coordinate", "com.leanowtech.bloge:WRONG:0.0.0:gate-a-candidate");
                }, true);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_DEP_COORDINATE);
        assertThat(snap.errors).hasSize(1);
    }

    /**
     * Wrong entryPath in dep entry (dots instead of slashes) → E_PROVIDER_DEP_ENTRY_PATH.
     * Recomputes manifestFingerprint so fingerprint check passes.
     */
    @Test
    void validate_depWrongEntryPath_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);

        byte[] providerJar = buildProviderJarWithDeps(candidateJar,
                m -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = ((java.util.List<Map<String, Object>>) m.get("entries")).get(0);
                    entry.put("entryPath", "com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.class");
                }, true);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_DEP_ENTRY_PATH);
        assertThat(snap.errors).hasSize(1);
    }

    /**
     * SPI fingerprint in dep is well-typed sha256 but of wrong bytes
     * (SHA-256 of empty bytes instead of actual SPI class) → E_PROVIDER_DEP_SPI_FP_MISMATCH.
     * Recomputes manifestFingerprint so fingerprint check passes.
     */
    @Test
    void validate_depSpiFingerprintMismatch_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);

        byte[] providerJar = buildProviderJarWithDeps(candidateJar,
                m -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = ((java.util.List<Map<String, Object>>) m.get("entries")).get(0);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rawFp = (Map<String, Object>) entry.get("rawFingerprint");
                    // SHA-256 of empty bytes instead of actual SPI class
                    rawFp.put("value", "sha256:" + sha256Hex(new byte[0]));
                }, true);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_DEP_SPI_FP_MISMATCH);
        assertThat(snap.errors).hasSize(1);
    }

    /**
     * Malformed sha256 value in rawFingerprint (missing hex portion) → E_PROVIDER_DEP_RAW_FP_VALUE_FMT.
     * Recomputes manifestFingerprint so fingerprint check passes.
     */
    @Test
    void validate_depMalformedSpiFingerprint_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);

        byte[] providerJar = buildProviderJarWithDeps(candidateJar,
                m -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = ((java.util.List<Map<String, Object>>) m.get("entries")).get(0);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rawFp = (Map<String, Object>) entry.get("rawFingerprint");
                    rawFp.put("value", "sha256:BAD");
                }, true);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_DEP_RAW_FP_VALUE_FMT);
        assertThat(snap.errors).hasSize(1);
    }

    /**
     * Extra top-level key in dep manifest → E_PROVIDER_DEP_TOP_KEYS.
     * Does NOT recompute manifestFingerprint — invalidates it too (secondary structural code).
     */
    @Test
    void validate_depExtraTopKey_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);

        byte[] providerJar = buildProviderJarWithDeps(candidateJar,
                m -> m.put("extraKey", 1),
                false);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_DEP_TOP_KEYS);
    }

    /**
     * Extra key in dep entry map → E_PROVIDER_DEP_MISSING_FIELD.
     * Recomputes manifestFingerprint so fingerprint check passes.
     */
    @Test
    void validate_depExtraEntryKey_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);

        byte[] providerJar = buildProviderJarWithDeps(candidateJar,
                m -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = ((java.util.List<Map<String, Object>>) m.get("entries")).get(0);
                    entry.put("extraField", "garbage");
                }, true);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_DEP_MISSING_FIELD);
        assertThat(snap.errors).hasSize(1);
    }

    /**
     * Extra key in manifestFingerprint dict → E_PROVIDER_DEP_FP_TYPE.
     * Does NOT recompute manifestFingerprint — the invalid fp structure itself triggers the code.
     */
    @Test
    void validate_depExtraFingerprintKey_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);

        byte[] providerJar = buildProviderJarWithDeps(candidateJar,
                m -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fpDict = (Map<String, Object>) m.get("manifestFingerprint");
                    fpDict.put("extraFpKey", "junk");
                }, false);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_DEP_FP_TYPE);
    }

    // 5 additional static-validator boundary tests
    // ══════════════════════════════════════════════════════════════════════

    /**
     * scanViaZipFile: 5 entries with maxZipEntries=2
     * → E_PROVIDER_ZIP_ENTRY_COUNT_EXCEEDED exactly once.
     */
    @Test
    void scan_providerEntryCountExceedsTinyLimit_reportsFixedCode() throws Exception {
        var tinyLimits = new CapabilityStudioGateATckProviderArtifactValidator.ArchiveLimits(
                2L, 8192L, 10240L, 100.0, 128);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);
            jos.putNextEntry(new JarEntry(
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider"));
            jos.write("com.leanowtech.bloge.gatetckprovider.GateATckProvider\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties"));
            jos.write("v=1\n".getBytes(StandardCharsets.UTF_8));
            jos.putNextEntry(new JarEntry("META-INF/gate-a/manifests/dependencies.json"));
            jos.write("{\"schemaVersion\":\"capability-studio.gate-a-dependency-lock-manifest.v1\",\"entries\":[]}".getBytes(StandardCharsets.UTF_8));
        }
        byte[] providerJar = baos.toByteArray();
        Path provPath = temp.resolve("prov.jar");
        Files.write(provPath, providerJar);

        List<String> errors = new ArrayList<>();
        CapabilityStudioGateATckProviderArtifactValidator.scanViaZipFile(
                provPath, "PROVIDER", tinyLimits, errors);

        assertThat(errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_ZIP_ENTRY_COUNT_EXCEEDED);
        assertThat(errors).hasSize(1);
    }

    /**
     * scanViaZipFile: single 64-byte STORED entry with maxSingleEntryBytes=16
     * → E_PROVIDER_ENTRY_SIZE_OVERFLOW exactly once.
     * Uses ZipOutputStream with explicit Deflater.NO_COMPRESSION to control sizes.
     */
    @Test
    void scan_providerEntrySizeExceedsTinyLimit_reportsFixedCode() throws Exception {
        var tinyLimits = new CapabilityStudioGateATckProviderArtifactValidator.ArchiveLimits(
                100L, 16L, 10240L, 100.0, 128);

        // ZipOutputStream with STORED method for a known uncompressed size of 64
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.setLevel(Deflater.NO_COMPRESSION);
            ZipEntry manifest = new ZipEntry("META-INF/MANIFEST.MF");
            manifest.setMethod(ZipEntry.STORED);
            manifest.setSize(0);
            manifest.setCrc(0);
            zos.putNextEntry(manifest);
            zos.closeEntry();

            ZipEntry payload = new ZipEntry("data/payload.bin");
            byte[] payloadBytes = new byte[64];
            payload.setMethod(ZipEntry.STORED);
            payload.setSize(64);
            payload.setCrc(computeCrc32(payloadBytes));
            zos.putNextEntry(payload);
            zos.write(payloadBytes);
            zos.closeEntry();
        }
        byte[] providerJar = baos.toByteArray();
        Path provPath = temp.resolve("prov.jar");
        Files.write(provPath, providerJar);

        List<String> errors = new ArrayList<>();
        CapabilityStudioGateATckProviderArtifactValidator.scanViaZipFile(
                provPath, "PROVIDER", tinyLimits, errors);

        assertThat(errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_ENTRY_SIZE_OVERFLOW);
        assertThat(errors).hasSize(1);
    }

    private static long computeCrc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * validate: providerPath points to a valid JAR but the caller-supplied
     * expectedRaw bytes differ from the file contents (same length).
     * → E_PROVIDER_PATH_STABLE_MISMATCH.
     */
    @Test
    void validate_providerPathStableMismatch_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        byte[] providerJar = buildValidProviderJar(candidateSpiBytes);

        // Write valid JAR to path
        Path provPath = temp.resolve("prov.jar");
        Files.write(provPath, providerJar);

        // Caller-supplied bytes differ from file (same length: 'X' vs actual bytes)
        byte[] wrongBytes = new byte[providerJar.length];
        Arrays.fill(wrongBytes, (byte) 'X');

        Path candPath = temp.resolve("cand.jar");
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                wrongBytes, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_PATH_STABLE_MISMATCH);
    }

    /**
     * validate: providerPath is a symlink to a valid JAR.
     * → E_PROVIDER_PATH_SYMLINK.
     * On platforms without symlink support the test is skipped.
     */
    @Test
    void validate_providerPathIsSymlink_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();
        byte[] candidateJar = buildValidCandidateJar(visible);
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        byte[] providerJar = buildValidProviderJar(candidateSpiBytes);

        Path targetPath = temp.resolve("prov-target.jar");
        Path symlinkPath = temp.resolve("prov-symlink.jar");

        Files.write(targetPath, providerJar);
        Path symlink = Files.createSymbolicLink(symlinkPath, targetPath);

        Path candPath = temp.resolve("cand.jar");
        Files.write(candPath, candidateJar);

        // Skip on platforms that do not support symlinks
        if (!Files.isSymbolicLink(symlink)) {
            return;
        }

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, symlink, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_PROVIDER_PATH_SYMLINK);
    }

    /**
     * validate: candidate JAR contains all visible schemas plus one extra.
     * → E_CANDIDATE_SCHEMA_EXTRA.
     */
    @Test
    void validate_candidateSchemaExtra_reportsFixedCode() throws Exception {
        var contract = realContract();
        Set<String> visible = realContract().visibleSchemaIds();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            mf.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            mf.write(jos);

            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAReceiptCanonicalizer.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderRoleSelfTest.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderArtifactValidator.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAException.class"));
            jos.write(minimalClassFile());
            jos.putNextEntry(new JarEntry(
                    "com/leanowtech/bloge/gateway/testkit/StrictJsonParser.class"));
            jos.write(minimalClassFile());

            // All visible schemas
            for (String schemaId : visible) {
                String content = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"$id\":\"" + schemaId + "\"}";
                jos.putNextEntry(new JarEntry("schemas/" + schemaId));
                jos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            // Extra schema
            String extraSchemaId = "extra-schema-id.schema.json";
            String extraContent = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"$id\":\"" + extraSchemaId + "\"}";
            jos.putNextEntry(new JarEntry("schemas/" + extraSchemaId));
            jos.write(extraContent.getBytes(StandardCharsets.UTF_8));
        }
        byte[] candidateJar = baos.toByteArray();
        byte[] candidateSpiBytes = readEntryBytes(candidateJar,
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class");
        byte[] providerJar = buildValidProviderJar(candidateSpiBytes);

        Path provPath = temp.resolve("prov.jar");
        Path candPath = temp.resolve("cand.jar");
        Files.write(provPath, providerJar);
        Files.write(candPath, candidateJar);

        var snap = CapabilityStudioGateATckProviderArtifactValidator.validate(
                providerJar, provPath, candidateJar, candPath, contract, false);

        assertThat(snap.errors).contains(
                CapabilityStudioGateATckProviderArtifactValidator.E_CANDIDATE_SCHEMA_EXTRA);
    }
}
