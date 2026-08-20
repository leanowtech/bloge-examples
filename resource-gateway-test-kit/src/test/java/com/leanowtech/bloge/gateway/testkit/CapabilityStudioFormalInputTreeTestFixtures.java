package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

final class CapabilityStudioFormalInputTreeTestFixtures {
    static final String AUTHORITY_SEMANTIC = fingerprint('a');
    static final String TARGET_SEMANTIC = fingerprint('b');
    static final String PUBLICATION_FINGERPRINT = fingerprint('c');
    static final String TRANSACTION_NONCE = fingerprint('d');

    private static final ObjectMapper JSON = new ObjectMapper();

    private CapabilityStudioFormalInputTreeTestFixtures() {
    }

    static Path privateDirectory(Path path) throws IOException {
        Path directory = Files.createDirectory(path);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    static Path authorityBundle(Path parent) throws IOException {
        return authorityBundle(parent, 1, 2);
    }

    static Path authorityBundle(Path parent, int artifactCount, int artifactSize)
            throws IOException {
        return authorityBundle(parent, artifactCount, 1, 1, artifactSize);
    }

    static Path maximumAuthorityBundle(Path parent) throws IOException {
        return authorityBundle(parent, 512, 64, 64, 1);
    }

    static Path exactMaximumReferencedAuthorityBundle(Path parent) throws IOException {
        Path root = maximumAuthorityBundle(parent);
        int keySetBytes = 128 * "{}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for (int index = 0; index < 512; index++) {
            int size = CapabilityStudioFormalInputTreeSnapshotter
                    .MAXIMUM_AUTHORITY_ARTIFACT_BYTES;
            if (index == 511) {
                size -= keySetBytes;
            }
            Files.write(root.resolve("artifact-%03d.json".formatted(index)),
                    bytes(size, (byte) ('A' + index % 20)));
        }
        return root;
    }

    private static Path authorityBundle(
            Path parent,
            int artifactCount,
            int issuerPolicyCount,
            int ownerPolicyCount,
            int artifactSize) throws IOException {
        Path root = Files.createDirectory(parent.resolve("authority-bundle"));
        ObjectNode manifest = JSON.createObjectNode()
                .put("schemaVersion",
                        "resource-gateway.capability-studio.mounted-authority-bundle.v1")
                .put("bundleId", "authority-bundle")
                .put("revision", 1)
                .put("generatedAt", "2026-01-01T00:00:00Z")
                .put("expiresAt", "2036-01-01T00:00:00Z")
                .put("bundleFingerprint", AUTHORITY_SEMANTIC);
        ArrayNode artifacts = manifest.putArray("artifacts");
        for (int index = 0; index < artifactCount; index++) {
            String file = "artifact-%03d.json".formatted(index);
            artifacts.addObject()
                    .put("referenceKind", "EVIDENCE")
                    .put("referenceKey", "evidence-%03d".formatted(index))
                    .put("exactRef", "evidence-ref-%03d".formatted(index))
                    .put("fingerprint", fingerprint('c'))
                    .put("artifactFile", file)
                    .put("artifactFileFingerprint", fingerprint('d'));
            Files.write(root.resolve(file), bytes(artifactSize, (byte) ('A' + index % 20)));
        }
        ArrayNode issuerPolicies = manifest.putArray("issuerPolicies");
        for (int index = 0; index < issuerPolicyCount; index++) {
            String file = "issuer-keys-%02d.json".formatted(index);
            ObjectNode issuerPolicy = issuerPolicies.addObject()
                    .put("issuerRef", "issuer-%02d".formatted(index))
                    .put("scope", "scope-%02d".formatted(index));
            issuerPolicy.putArray("allowedEvidenceKinds").add("ACCEPTANCE_EVIDENCE");
            issuerPolicy.put("keySetFile", file)
                    .put("pinnedKeySetFingerprint", fingerprint('e'))
                    .put("maxProofTtlSeconds", 300);
            Files.writeString(root.resolve(file), "{}\n");
        }
        ArrayNode ownerPolicies = manifest.putArray("ownerPolicies");
        for (int index = 0; index < ownerPolicyCount; index++) {
            String file = "owner-keys-%02d.json".formatted(index);
            ObjectNode ownerPolicy = ownerPolicies.addObject()
                    .put("role", "OWNER");
            ownerPolicy.putArray("allowedActorRefs").add("owner-%02d".formatted(index));
            ownerPolicy.put("signatureIssuerRef", "owner-issuer-%02d".formatted(index))
                    .put("scope", "owner-scope-%02d".formatted(index))
                    .put("keySetFile", file)
                    .put("pinnedKeySetFingerprint", fingerprint('f'))
                    .put("maxSignatureTtlSeconds", 300);
            Files.writeString(root.resolve(file), "{}\n");
        }
        Files.write(root.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE),
                JSON.writeValueAsBytes(manifest));
        return root;
    }

    static Path targetBundle(Path parent) throws IOException {
        Path root = Files.createDirectory(parent.resolve("target-bundle"));
        ObjectNode manifest = JSON.createObjectNode()
                .put("schemaVersion",
                        "resource-gateway.capability-studio.mounted-target-admission-bundle.v1")
                .put("bundleId", "target-bundle")
                .put("revision", 1)
                .put("lifecycleState", "ACTIVE")
                .putNull("predecessorBundleFingerprint");
        manifest.putObject("revocationAuthority")
                .put("registryRef", "registry")
                .put("revision", 1)
                .put("snapshotFingerprint", fingerprint('1'))
                .put("observedAt", "2026-01-01T00:00:00Z")
                .put("expiresAt", "2036-01-01T00:00:00Z");
        manifest.put("generatedAt", "2026-01-01T00:00:00Z")
                .put("expiresAt", "2036-01-01T00:00:00Z")
                .put("executionLeaseId", "lease-1")
                .putArray("trustedTargetIdentities").add("target-identity");
        manifest.putObject("targetBinding")
                .put("file", "target.json")
                .put("fileFingerprint", fingerprint('2'))
                .put("canonicalFingerprint", fingerprint('3'));
        admission(manifest.putObject("candidate"), true);
        admission(manifest.putObject("environment"), false);
        manifest.put("bundleFingerprint", TARGET_SEMANTIC);

        for (String file : new String[] {
                "target.json", "candidate-attestation.json", "candidate-keys.json",
                "candidate-proof.json", "environment-attestation.json",
                "environment-keys.json", "environment-proof.json"}) {
            Files.writeString(root.resolve(file), "{}\n");
        }
        Files.write(root.resolve(CapabilityStudioMountedTargetAdmissionBundle.MANIFEST_FILE),
                JSON.writeValueAsBytes(manifest));
        return root;
    }

    private static void admission(ObjectNode node, boolean candidate) {
        String prefix = candidate ? "candidate" : "environment";
        node.putObject("attestation")
                .put("file", prefix + "-attestation.json")
                .put("fileFingerprint", fingerprint(candidate ? '4' : '5'))
                .put("reference", prefix + "-ref")
                .put("revision", 1);
        node.putObject("policy")
                .put("policyRef", prefix + "-policy")
                .put("role", candidate ? "CANDIDATE_AUTHORITY" : "ENVIRONMENT_AUTHORITY")
                .put("issuer", prefix + "-issuer")
                .put("scope", prefix + "-scope")
                .put("keySetFile", prefix + "-keys.json")
                .put("keySetFileFingerprint", fingerprint(candidate ? '6' : '7'))
                .put("pinnedKeySetFingerprint", fingerprint(candidate ? '8' : '9'))
                .put("maximumProofTtlSeconds", 300)
                .put("proofFile", prefix + "-proof.json")
                .put("proofFileFingerprint", fingerprint(candidate ? '0' : 'a'));
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static byte[] bytes(int size, byte value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
