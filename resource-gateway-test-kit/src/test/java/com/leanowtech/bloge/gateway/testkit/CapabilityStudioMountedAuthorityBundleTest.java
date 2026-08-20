package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioMountedAuthorityBundleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void rejectsBundleGeneratedInTheFutureBeforeReadingReferencedFiles() throws Exception {
        Path root = Files.createTempDirectory("authority-bundle-lifecycle-");
        writeManifest(root, NOW.plusSeconds(1), NOW.plusSeconds(3601));

        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(root, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.MANIFEST_NOT_YET_VALID");
    }

    @Test
    void rejectsExpiredBundleWithStableCode() throws Exception {
        Path root = Files.createTempDirectory("authority-bundle-lifecycle-");
        writeManifest(root, NOW.minusSeconds(3601), NOW.minusSeconds(1));

        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(root, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.MANIFEST_EXPIRED");
    }

    @Test
    void loadsOneImmutableKeySetSnapshotReusedByIssuerAndOwnerPolicies() throws Exception {
        Path root = Files.createTempDirectory("authority-bundle-reuse-");
        String expectedFingerprint = writeBundle(root, "ENVIRONMENT_ATTESTATION");

        CapabilityStudioMountedAuthorityBundle bundle =
                CapabilityStudioMountedAuthorityBundle.load(root, CLOCK);

        assertThat(bundle.bundleFingerprint()).isEqualTo(expectedFingerprint);
        assertThat(bundle.evidenceResolver().resolve(new CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest(
                CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind.EVIDENCE,
                "evidence-1",
                new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                        "evidence://demo/1", fingerprint('a')))).status())
                .isEqualTo(CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionStatus.AVAILABLE);
        assertThat(bundle.evidenceResolver().resolve(new CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest(
                CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind.EVIDENCE,
                "missing",
                new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                        "evidence://demo/missing", fingerprint('9')))).status())
                .isEqualTo(CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionStatus.NOT_FOUND);
        Files.delete(root.resolve("artifact.json"));
        Files.delete(root.resolve("keys.json"));
        assertThat(bundle.evidenceResolver().resolve(new CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest(
                CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind.EVIDENCE,
                "evidence-1",
                new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                        "evidence://demo/1", fingerprint('a')))).status())
                .isEqualTo(CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionStatus.AVAILABLE);
        assertThat(bundle.toString()).doesNotContain("evidence://demo/1", "bundle:demo");
    }

    @Test
    void rejectsAllThreeAuthorityPathsAfterTheMountedBundleExpires() throws Exception {
        Path root = Files.createTempDirectory("authority-bundle-expiry-");
        writeBundle(root, "ENVIRONMENT_ATTESTATION");
        MutableClock clock = new MutableClock(NOW);
        CapabilityStudioMountedAuthorityBundle bundle =
                CapabilityStudioMountedAuthorityBundle.load(root, clock);

        clock.advanceTo(NOW.plusSeconds(3600));

        assertThat(bundle.evidenceResolver().resolve(null).status())
                .isEqualTo(CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionStatus.UNAVAILABLE);
        assertThat(bundle.evidenceIssuerPolicy().verify(null, null, null).status())
                .isEqualTo(CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.Decision.REJECTED);
        assertThat(bundle.evidenceIssuerPolicy().verify(null, null, null).reasonCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2.AUTHORITY_BUNDLE_EXPIRED");
        assertThat(bundle.ownerAuthority().verify(null, null, null).status())
                .isEqualTo(CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.Decision.REJECTED);
        assertThat(bundle.ownerAuthority().verify(null, null, null).reasonCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2.AUTHORITY_BUNDLE_EXPIRED");
    }

    @Test
    void acceptsSchemaFilenameBoundariesInTheLoader() throws Exception {
        Path shortest = Files.createTempDirectory("authority-bundle-name-short-");
        writeBundle(shortest, "ENVIRONMENT_ATTESTATION", "a.json");
        assertThat(CapabilityStudioMountedAuthorityBundle.load(
                shortest, CLOCK)).isNotNull();

        Path longest = Files.createTempDirectory("authority-bundle-name-long-");
        writeBundle(longest, "ENVIRONMENT_ATTESTATION", "a".repeat(123) + ".json");
        assertThat(CapabilityStudioMountedAuthorityBundle.load(
                longest, CLOCK)).isNotNull();
    }

    @Test
    void rejectsCategoryConfusedEvidenceArtifactWithStableCode() throws Exception {
        Path root = Files.createTempDirectory("authority-bundle-category-");
        writeBundle(root, "OWNER_SIGNATURE");

        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(root, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.ARTIFACT_CATEGORY_INVALID");
    }

    @Test
    void rejectsArtifactSymlinkAtTheNoFollowReadBoundary() throws Exception {
        Path root = Files.createTempDirectory("authority-bundle-symlink-");
        writeManifest(root, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        Path target = Files.createTempFile("authority-bundle-target-", ".json");
        Files.createSymbolicLink(root.resolve("artifact.json"), target);

        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(root, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.FILE_INVALID");
    }

    @Test
    void rejectsManifestSchemaAndPathConfusion() throws Exception {
        for (String fileName : new String[]{"../artifact.json", "/tmp/artifact.json"}) {
            Path root = Files.createTempDirectory("authority-bundle-path-");
            writeBundle(root, "ENVIRONMENT_ATTESTATION");
            mutateManifest(root, manifest -> ((ObjectNode) manifest.path("artifacts").get(0))
                    .put("artifactFile", fileName), true);

            assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(root, CLOCK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.MANIFEST_SCHEMA_INVALID");
        }

        Path root = Files.createTempDirectory("authority-bundle-schema-");
        writeBundle(root, "ENVIRONMENT_ATTESTATION");
        mutateManifest(root, manifest -> manifest.put("unexpected", "payload"), false);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(root, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.MANIFEST_SCHEMA_INVALID");
    }

    @Test
    void rejectsDuplicateFieldsInManifestArtifactAndKeySet() throws Exception {
        Path duplicateManifest = Files.createTempDirectory("authority-bundle-duplicate-manifest-");
        writeBundle(duplicateManifest, "ENVIRONMENT_ATTESTATION");
        injectDuplicateField(duplicateManifest.resolve(
                CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE), "bundleId");
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(
                duplicateManifest, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.JSON_INVALID");

        Path duplicateArtifact = Files.createTempDirectory("authority-bundle-duplicate-artifact-json-");
        writeBundle(duplicateArtifact, "ENVIRONMENT_ATTESTATION");
        injectDuplicateField(duplicateArtifact.resolve("artifact.json"), "schemaVersion");
        refreshArtifactFingerprint(duplicateArtifact);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(
                duplicateArtifact, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.ARTIFACT_INVALID");

        Path duplicateKeySet = Files.createTempDirectory("authority-bundle-duplicate-keyset-");
        writeBundle(duplicateKeySet, "ENVIRONMENT_ATTESTATION");
        injectDuplicateField(duplicateKeySet.resolve("keys.json"), "schemaVersion");
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(
                duplicateKeySet, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.KEY_SET_INVALID");
    }

    @Test
    void rejectsManifestAndArtifactFingerprintTamper() throws Exception {
        Path bundleTampered = Files.createTempDirectory("authority-bundle-fingerprint-");
        writeBundle(bundleTampered, "ENVIRONMENT_ATTESTATION");
        mutateManifest(bundleTampered,
                manifest -> manifest.put("bundleFingerprint", fingerprint('0')), false);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(bundleTampered, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.MANIFEST_FINGERPRINT_INVALID");

        Path artifactTampered = Files.createTempDirectory("authority-bundle-artifact-fingerprint-");
        writeBundle(artifactTampered, "ENVIRONMENT_ATTESTATION");
        mutateManifest(artifactTampered, manifest -> ((ObjectNode) manifest.path("artifacts").get(0))
                .put("artifactFileFingerprint", fingerprint('0')), true);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(artifactTampered, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.ARTIFACT_FILE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsDuplicateBindingsAndUnsortedPolicyArrays() throws Exception {
        Path duplicateArtifact = Files.createTempDirectory("authority-bundle-duplicate-artifact-");
        writeBundle(duplicateArtifact, "ENVIRONMENT_ATTESTATION");
        Files.copy(duplicateArtifact.resolve("artifact.json"),
                duplicateArtifact.resolve("duplicate.json"));
        mutateManifest(duplicateArtifact, manifest -> {
            ObjectNode duplicate = manifest.path("artifacts").get(0).deepCopy();
            duplicate.put("artifactFile", "duplicate.json");
            ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.path("artifacts")).add(duplicate);
        }, true);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(duplicateArtifact, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.BINDING_DUPLICATE");

        for (String array : new String[]{"issuerPolicies", "ownerPolicies"}) {
            Path duplicatePolicy = Files.createTempDirectory("authority-bundle-duplicate-policy-");
            writeBundle(duplicatePolicy, "ENVIRONMENT_ATTESTATION");
            mutateManifest(duplicatePolicy, manifest -> {
                ObjectNode duplicate = manifest.path(array).get(0).deepCopy();
                ((com.fasterxml.jackson.databind.node.ArrayNode) manifest.path(array)).add(duplicate);
            }, true);
            String code = "issuerPolicies".equals(array)
                    ? "ISSUER_POLICY_ORDER" : "OWNER_POLICY_ORDER";
            assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(
                    duplicatePolicy, CLOCK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE." + code);
        }
    }

    @Test
    void rejectsInvalidKeySetPinAndMalformedOrOversizedJson() throws Exception {
        Path keySetPin = Files.createTempDirectory("authority-bundle-keyset-pin-");
        writeBundle(keySetPin, "ENVIRONMENT_ATTESTATION");
        mutateManifest(keySetPin, manifest -> {
            ((ObjectNode) manifest.path("issuerPolicies").get(0))
                    .put("pinnedKeySetFingerprint", fingerprint('0'));
            ((ObjectNode) manifest.path("ownerPolicies").get(0))
                    .put("pinnedKeySetFingerprint", fingerprint('0'));
        }, true);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(keySetPin, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.KEY_SET_PIN_INVALID");

        Path malformed = Files.createTempDirectory("authority-bundle-malformed-");
        writeBundle(malformed, "ENVIRONMENT_ATTESTATION");
        Files.writeString(malformed.resolve("artifact.json"), "{", StandardCharsets.UTF_8);
        refreshArtifactFingerprint(malformed);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(malformed, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.ARTIFACT_INVALID");

        Path oversized = Files.createTempDirectory("authority-bundle-oversized-");
        writeBundle(oversized, "ENVIRONMENT_ATTESTATION");
        Files.write(oversized.resolve("artifact.json"), new byte[65 * 1024]);
        refreshArtifactFingerprint(oversized);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(oversized, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.ARTIFACT_SIZE_OR_TYPE_INVALID");
    }

    @Test
    void rejectsRootAndManifestSymlinksAndOversizedManifest() throws Exception {
        Path actualRoot = Files.createTempDirectory("authority-bundle-root-");
        writeBundle(actualRoot, "ENVIRONMENT_ATTESTATION");
        Path rootLink = Files.createTempDirectory("authority-bundle-root-link-parent-")
                .resolve("bundle");
        Files.createSymbolicLink(rootLink, actualRoot);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(rootLink, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.ROOT_INVALID");

        Path manifestLink = Files.createTempDirectory("authority-bundle-manifest-link-");
        writeBundle(manifestLink, "ENVIRONMENT_ATTESTATION");
        Path manifestTarget = Files.createTempFile("authority-bundle-manifest-target-", ".json");
        Files.write(manifestTarget, Files.readAllBytes(
                manifestLink.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE)));
        Files.delete(manifestLink.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE));
        Files.createSymbolicLink(manifestLink.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE),
                manifestTarget);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(manifestLink, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.MANIFEST_SIZE_OR_TYPE_INVALID");

        Path oversizedManifest = Files.createTempDirectory("authority-bundle-manifest-size-");
        writeBundle(oversizedManifest, "ENVIRONMENT_ATTESTATION");
        Files.write(oversizedManifest.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE),
                new byte[1024 * 1024 + 1]);
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(oversizedManifest, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.MANIFEST_SIZE_OR_TYPE_INVALID");
    }

    @Test
    void rejectsRootWhenNoFollowDirectoryIdentityCannotBeDetermined() throws Exception {
        Path archive = Path.of(System.getProperty("java.io.tmpdir"),
                "authority-bundle-no-file-key-" + UUID.randomUUID() + ".zip");
        try (FileSystem fileSystem = FileSystems.newFileSystem(
                URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            Path root = fileSystem.getPath("/");
            writeBundle(root, "ENVIRONMENT_ATTESTATION");
            assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(root, CLOCK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.ROOT_INVALID");
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    @Test
    void rejectsTotalReferencedBytesAndDoesNotLeakSensitiveDetails() throws Exception {
        Path root = Files.createTempDirectory("authority-bundle-total-size-");
        writeBundle(root, "ENVIRONMENT_ATTESTATION");
        ObjectNode keySet = (ObjectNode) JSON.readTree(
                Files.readAllBytes(root.resolve("keys.json")));
        byte[] keySetBytes = JSON.writeValueAsBytes(keySet);
        byte[] padded = java.util.Arrays.copyOf(keySetBytes, 1024 * 1024);
        java.util.Arrays.fill(padded, keySetBytes.length, padded.length, (byte) ' ');
        ObjectNode manifest = readManifest(root);
        com.fasterxml.jackson.databind.node.ArrayNode issuers =
                (com.fasterxml.jackson.databind.node.ArrayNode) manifest.path("issuerPolicies");
        ((ObjectNode) issuers.get(0)).put("issuerRef", "issuer:00");
        for (int index = 1; index <= 33; index++) {
            String file = String.format("keys-%02d.json", index);
            Files.write(root.resolve(file), padded);
            ObjectNode issuer = ((ObjectNode) issuers.get(0)).deepCopy();
            issuer.put("issuerRef", "issuer:" + String.format("%02d", index));
            issuer.put("keySetFile", file);
            issuers.add(issuer);
        }
        saveManifest(root, manifest, true);

        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(root, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.TOTAL_SIZE_LIMIT");

        Path redacted = Files.createTempDirectory("authority-bundle-redacted-");
        String secretPath = redacted.resolve("customer-secret.json").toString();
        assertThatThrownBy(() -> CapabilityStudioMountedAuthorityBundle.load(redacted, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.LOAD_FAILED")
                .hasMessageNotContaining(secretPath);
    }

    private static ObjectNode readManifest(Path root) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readAllBytes(
                root.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE)));
    }

    private static void mutateManifest(Path root, Consumer<ObjectNode> mutation,
                                       boolean refreshFingerprint) throws Exception {
        ObjectNode manifest = readManifest(root);
        mutation.accept(manifest);
        saveManifest(root, manifest, refreshFingerprint);
    }

    private static void saveManifest(Path root, ObjectNode manifest,
                                     boolean refreshFingerprint) throws Exception {
        if (refreshFingerprint) {
            ObjectNode fingerprintInput = manifest.deepCopy();
            fingerprintInput.putNull("bundleFingerprint");
            manifest.put("bundleFingerprint", EvidenceVerificationSupport.sha256(fingerprintInput));
        }
        Files.write(root.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE),
                JSON.writeValueAsBytes(manifest));
    }

    private static void refreshArtifactFingerprint(Path root) throws Exception {
        mutateManifest(root, manifest -> {
            try {
                ((ObjectNode) manifest.path("artifacts").get(0)).put("artifactFileFingerprint",
                        sha256(Files.readAllBytes(root.resolve("artifact.json"))));
            } catch (Exception failure) {
                throw new IllegalStateException("test fixture cannot be refreshed", failure);
            }
        }, true);
    }

    private static void injectDuplicateField(Path file, String field) throws Exception {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        String marker = "\"" + field + "\":";
        int position = json.indexOf(marker);
        if (position < 0) {
            throw new IllegalStateException("test fixture field is absent");
        }
        Files.writeString(file, json.substring(0, position) + marker + "null," +
                json.substring(position), StandardCharsets.UTF_8);
    }

    private static void writeManifest(Path root, Instant generatedAt, Instant expiresAt)
            throws Exception {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schemaVersion",
                "resource-gateway.capability-studio.mounted-authority-bundle.v1");
        manifest.put("bundleId", "bundle:demo");
        manifest.put("revision", 1);
        manifest.put("generatedAt", generatedAt.toString());
        manifest.put("expiresAt", expiresAt.toString());
        manifest.put("bundleFingerprint", "sha256:" + "0".repeat(64));
        manifest.putArray("artifacts").addObject()
                .put("referenceKind", "EVIDENCE")
                .put("referenceKey", "evidence-1")
                .put("exactRef", "evidence://demo/1")
                .put("fingerprint", "sha256:" + "1".repeat(64))
                .put("artifactFile", "artifact.json")
                .put("artifactFileFingerprint", "sha256:" + "2".repeat(64));
        ObjectNode issuer = manifest.putArray("issuerPolicies").addObject();
        issuer.put("issuerRef", "issuer:demo");
        issuer.put("scope", "scope:demo");
        issuer.putArray("allowedEvidenceKinds").add("ACCEPTANCE_EVIDENCE");
        issuer.put("keySetFile", "keys.json");
        issuer.put("pinnedKeySetFingerprint", "sha256:" + "3".repeat(64));
        issuer.put("maxProofTtlSeconds", 60);
        ObjectNode owner = manifest.putArray("ownerPolicies").addObject();
        owner.put("role", "role:demo");
        owner.putArray("allowedActorRefs").add("actor:demo");
        owner.put("signatureIssuerRef", "issuer:demo");
        owner.put("scope", "scope:demo");
        owner.put("keySetFile", "keys.json");
        owner.put("pinnedKeySetFingerprint", "sha256:" + "3".repeat(64));
        owner.put("maxSignatureTtlSeconds", 60);
        ObjectNode fingerprintInput = manifest.deepCopy();
        fingerprintInput.putNull("bundleFingerprint");
        manifest.put("bundleFingerprint", EvidenceVerificationSupport.sha256(fingerprintInput));
        Files.write(root.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE),
                JSON.writeValueAsBytes(manifest));
    }

    private static String writeBundle(Path root, String evidenceKind) throws Exception {
        return writeBundle(root, evidenceKind, "artifact.json");
    }

    private static String writeBundle(Path root, String evidenceKind, String artifactFile)
            throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ObjectNode keySet = keySet(pair);
        Files.write(root.resolve("keys.json"), JSON.writeValueAsBytes(keySet));
        ObjectNode artifact = artifact(evidenceKind);
        byte[] artifactBytes = JSON.writeValueAsBytes(artifact);
        Files.write(root.resolve(artifactFile), artifactBytes);

        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schemaVersion",
                "resource-gateway.capability-studio.mounted-authority-bundle.v1");
        manifest.put("bundleId", "bundle:demo");
        manifest.put("revision", 1);
        manifest.put("generatedAt", NOW.minusSeconds(60).toString());
        manifest.put("expiresAt", NOW.plusSeconds(3600).toString());
        manifest.put("bundleFingerprint", "sha256:" + "0".repeat(64));
        manifest.putArray("artifacts").addObject()
                .put("referenceKind", "EVIDENCE")
                .put("referenceKey", "evidence-1")
                .put("exactRef", "evidence://demo/1")
                .put("fingerprint", fingerprint('a'))
                .put("artifactFile", artifactFile)
                .put("artifactFileFingerprint", sha256(artifactBytes));
        ObjectNode issuer = manifest.putArray("issuerPolicies").addObject();
        issuer.put("issuerRef", "issuer:demo");
        issuer.put("scope", "scope:demo");
        issuer.putArray("allowedEvidenceKinds").add(evidenceKind);
        issuer.put("keySetFile", "keys.json");
        issuer.put("pinnedKeySetFingerprint", keySet.path("snapshotFingerprint").asText());
        issuer.put("maxProofTtlSeconds", 60);
        ObjectNode owner = manifest.putArray("ownerPolicies").addObject();
        owner.put("role", "role:demo");
        owner.putArray("allowedActorRefs").add("actor:demo");
        owner.put("signatureIssuerRef", "issuer:demo");
        owner.put("scope", "scope:demo");
        owner.put("keySetFile", "keys.json");
        owner.put("pinnedKeySetFingerprint", keySet.path("snapshotFingerprint").asText());
        owner.put("maxSignatureTtlSeconds", 60);
        ObjectNode fingerprintInput = manifest.deepCopy();
        fingerprintInput.putNull("bundleFingerprint");
        String bundleFingerprint = EvidenceVerificationSupport.sha256(fingerprintInput);
        manifest.put("bundleFingerprint", bundleFingerprint);
        Files.write(root.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE),
                JSON.writeValueAsBytes(manifest));
        return bundleFingerprint;
    }

    private static ObjectNode keySet(KeyPair pair) throws Exception {
        Instant generatedAt = NOW.minusSeconds(60);
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        material.put("provider", "bundle-test");
        material.put("generatedAt", generatedAt.toString());
        material.put("expiresAt", NOW.plusSeconds(3600).toString());
        material.put("activeKeyId", "evidence-key");
        material.put("policyCompleteness", "COMPLETE");
        ArrayNode keys = material.putArray("keys");
        keys.addObject()
                .put("keyId", "evidence-key")
                .put("algorithm", "Ed25519")
                .put("encodedPublicKey", Base64.getEncoder().encodeToString(
                        pair.getPublic().getEncoded()))
                .put("createdAt", NOW.minusSeconds(3600).toString())
                .put("notBefore", NOW.minusSeconds(3600).toString())
                .putNull("notAfter")
                .put("state", "ACTIVE")
                .put("providerKeyVersion", "v1");
        ArrayNode events = material.putArray("events");
        event(events, 1, "created", "CREATED");
        event(events, 2, "activated", "ACTIVATED");
        String snapshotFingerprint = EvidenceVerificationSupport.sha256(material);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(snapshotFingerprint.getBytes(StandardCharsets.UTF_8));
        ObjectNode keySet = material.deepCopy();
        keySet.put("snapshotFingerprint", snapshotFingerprint);
        keySet.putObject("attestation")
                .put("schemaVersion", "bloge.visualRunEvidenceSeal.v1")
                .put("materialFingerprint", snapshotFingerprint)
                .put("algorithm", "Ed25519")
                .put("keyId", "evidence-key")
                .put("signedAt", generatedAt.plusSeconds(1).toString())
                .put("signature", Base64.getEncoder().encodeToString(signature.sign()));
        return keySet;
    }

    private static void event(ArrayNode events, long sequence, String id, String type) {
        Instant effectiveAt = "CREATED".equals(type)
                ? NOW.minusSeconds(3600) : NOW.minusSeconds(60);
        events.addObject()
                .put("sequence", sequence)
                .put("eventId", id)
                .put("keyId", "evidence-key")
                .put("type", type)
                .put("occurredAt", NOW.minusSeconds(60).toString())
                .put("effectiveAt", effectiveAt.toString())
                .putNull("revocationMode")
                .putNull("invalidFrom")
                .put("reasonCode", "KEY_LIFECYCLE");
    }

    private static ObjectNode artifact(String evidenceKind) {
        ObjectNode artifact = JSON.createObjectNode();
        artifact.put("schemaVersion",
                CapabilityStudioAuthorityEvidenceResolver.SCHEMA_VERSION);
        artifact.put("referenceKind", "EVIDENCE");
        artifact.put("referenceKey", "evidence-1");
        ObjectNode coordinate = artifact.putObject("coordinate");
        coordinate.put("exactRef", "evidence://demo/1");
        coordinate.put("fingerprint", fingerprint('a'));
        artifact.put("evidenceKind", evidenceKind);
        artifact.put("issuerRef", "issuer:demo");
        artifact.put("scope", "scope:demo");
        artifact.putObject("bindings")
                .put("candidateArtifactFingerprint", fingerprint('b'))
                .put("candidateIntentFingerprint", fingerprint('c'))
                .put("environmentFingerprint", fingerprint('d'))
                .put("evidenceClosureFingerprint", fingerprint('e'));
        artifact.putObject("observationWindow")
                .put("from", NOW.minusSeconds(50).toString())
                .put("through", NOW.minusSeconds(40).toString());
        artifact.putObject("seal")
                .put("keyId", "evidence-key")
                .put("algorithm", "Ed25519")
                .put("materialFingerprint", fingerprint('f'))
                .put("signedAt", NOW.minusSeconds(40).toString())
                .put("expiresAt", NOW.plusSeconds(60).toString())
                .put("signature", "c2lnbmF0dXJl");
        return artifact;
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advanceTo(Instant next) {
            current = next;
        }

        @Override
        public Instant instant() {
            return current;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
