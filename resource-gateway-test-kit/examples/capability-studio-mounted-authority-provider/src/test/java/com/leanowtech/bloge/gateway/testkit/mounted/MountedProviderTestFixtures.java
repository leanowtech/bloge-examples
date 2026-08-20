package com.leanowtech.bloge.gateway.testkit.mounted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioAuthorityEvidenceResolver;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioMountedAuthorityBundle;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioMountedTargetAdmissionBundle;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.AdmissionWindow;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateAttestationFacts;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAttestationFacts;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.ExactReference;
import com.leanowtech.bloge.gateway.testkit.TestingProtocol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

final class MountedProviderTestFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String LEASE = "lease:mounted-provider:1";
    static final String IDENTITY = "runtime:mounted-provider";
    static final String SCOPE = "tenant:test/environment:acceptance";

    private MountedProviderTestFixtures() {
    }

    static Fixture write(Path parent, String name) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Path authorityRoot = Files.createDirectory(parent.resolve(name + "-authority"));
        Path targetRoot = Files.createDirectory(parent.resolve(name + "-target"));
        Path stateRoot = privateDirectory(parent.resolve(name + "-state"));
        writeAuthorityBundle(authorityRoot, now, name);
        writeTargetBundle(targetRoot, now, name);
        return new Fixture(authorityRoot.toAbsolutePath(), targetRoot.toAbsolutePath(),
                stateRoot.toAbsolutePath());
    }

    static void replaceDirectoryContents(Path target, Path source) throws Exception {
        try (var files = Files.list(target)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        try (var files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
    }

    static void copyDirectory(Path source, Path target) throws Exception {
        Files.createDirectory(target);
        try (var files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
    }

    static Path privateDirectory(Path path) throws Exception {
        Path directory = Files.createDirectory(path).toAbsolutePath();
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
        return directory;
    }

    private static void writeAuthorityBundle(Path root, Instant now, String name)
            throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ObjectNode keySet = keySet(pair, "authority-key", "authority-provider", now);
        write(root.resolve("keys.json"), keySet);
        ObjectNode artifact = authorityArtifact(now);
        byte[] artifactBytes = JSON.writeValueAsBytes(artifact);
        Files.write(root.resolve("artifact.json"), artifactBytes);

        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schemaVersion",
                "resource-gateway.capability-studio.mounted-authority-bundle.v1");
        manifest.put("bundleId", "bundle:" + safeName(name));
        manifest.put("revision", 1);
        manifest.put("generatedAt", now.minusSeconds(120).toString());
        manifest.put("expiresAt", now.plusSeconds(3600).toString());
        manifest.put("bundleFingerprint", fingerprint('0'));
        manifest.putArray("artifacts").addObject()
                .put("referenceKind", "EVIDENCE")
                .put("referenceKey", "evidence-1")
                .put("exactRef", "evidence://test/1")
                .put("fingerprint", fingerprint('a'))
                .put("artifactFile", "artifact.json")
                .put("artifactFileFingerprint", sha256(artifactBytes));
        ObjectNode issuer = manifest.putArray("issuerPolicies").addObject();
        issuer.put("issuerRef", "issuer:test");
        issuer.put("scope", "scope:test");
        issuer.putArray("allowedEvidenceKinds").add("ENVIRONMENT_ATTESTATION");
        issuer.put("keySetFile", "keys.json");
        issuer.put("pinnedKeySetFingerprint",
                keySet.path("snapshotFingerprint").textValue());
        issuer.put("maxProofTtlSeconds", 600);
        ObjectNode owner = manifest.putArray("ownerPolicies").addObject();
        owner.put("role", "role:test");
        owner.putArray("allowedActorRefs").add("actor:test");
        owner.put("signatureIssuerRef", "issuer:test");
        owner.put("scope", "scope:test");
        owner.put("keySetFile", "keys.json");
        owner.put("pinnedKeySetFingerprint",
                keySet.path("snapshotFingerprint").textValue());
        owner.put("maxSignatureTtlSeconds", 600);
        ObjectNode material = manifest.deepCopy();
        material.putNull("bundleFingerprint");
        manifest.put("bundleFingerprint", canonicalSha256(material));
        write(root.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE), manifest);
    }

    private static ObjectNode authorityArtifact(Instant now) {
        ObjectNode artifact = JSON.createObjectNode();
        artifact.put("schemaVersion", CapabilityStudioAuthorityEvidenceResolver.SCHEMA_VERSION);
        artifact.put("referenceKind", "EVIDENCE");
        artifact.put("referenceKey", "evidence-1");
        artifact.putObject("coordinate")
                .put("exactRef", "evidence://test/1")
                .put("fingerprint", fingerprint('a'));
        artifact.put("evidenceKind", "ENVIRONMENT_ATTESTATION");
        artifact.put("issuerRef", "issuer:test");
        artifact.put("scope", "scope:test");
        artifact.putObject("bindings")
                .put("candidateArtifactFingerprint", fingerprint('b'))
                .put("candidateIntentFingerprint", fingerprint('c'))
                .put("environmentFingerprint", fingerprint('d'))
                .put("evidenceClosureFingerprint", fingerprint('e'));
        artifact.putObject("observationWindow")
                .put("from", now.minusSeconds(100).toString())
                .put("through", now.minusSeconds(90).toString());
        artifact.putObject("seal")
                .put("keyId", "authority-key")
                .put("algorithm", "Ed25519")
                .put("materialFingerprint", fingerprint('f'))
                .put("signedAt", now.minusSeconds(90).toString())
                .put("expiresAt", now.plusSeconds(600).toString())
                .put("signature", "c2lnbmF0dXJl");
        return artifact;
    }

    private static void writeTargetBundle(Path root, Instant now, String name) throws Exception {
        ObjectNode candidate = candidateAttestation(now);
        byte[] candidateBytes = JSON.writeValueAsBytes(candidate);
        String candidateRaw = sha256(candidateBytes);
        ObjectNode environment = environmentAttestation(now, candidate, candidateRaw);
        byte[] environmentBytes = JSON.writeValueAsBytes(environment);
        String environmentRaw = sha256(environmentBytes);
        ObjectNode target = targetBinding(candidate, candidateRaw, environment, environmentRaw);
        byte[] targetBytes = JSON.writeValueAsBytes(target);
        String targetRaw = sha256(targetBytes);
        String targetCanonical = target.path("fingerprint").textValue();

        CandidateCoordinate candidateCoordinate = new CandidateCoordinate(
                candidate.path("candidateRef").textValue(), 1, candidateRaw);
        EnvironmentCoordinate environmentCoordinate = new EnvironmentCoordinate(
                environment.path("environmentRef").textValue(), 1, environmentRaw);
        CandidateAttestationFacts candidateFacts = candidateFacts(candidate, candidateCoordinate);
        EnvironmentAttestationFacts environmentFacts = environmentFacts(
                environment, environmentCoordinate);
        var proofContext = new CapabilityStudioMountedTargetAdmissionBundle.ProofBindingContext(
                targetRaw, targetCanonical, candidateCoordinate, environmentCoordinate,
                LEASE, Set.of(IDENTITY));

        KeyPair candidatePair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair environmentPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ObjectNode candidateKeySet = keySet(
                candidatePair, "candidate-key", "candidate-provider", now);
        ObjectNode environmentKeySet = keySet(
                environmentPair, "environment-key", "environment-provider", now);
        ObjectNode candidateProof = proof(
                CapabilityStudioMountedTargetAdmissionBundle.CANDIDATE_PROOF_VERSION,
                "CANDIDATE_AUTHORITY", "candidate-key", candidateFacts.issuedAt(),
                candidateFacts.expiresAt(),
                CapabilityStudioMountedTargetAdmissionBundle.candidateProofFingerprint(
                        candidateFacts, proofContext, "policy:candidate",
                        candidateKeySet.path("snapshotFingerprint").textValue(),
                        "candidate-key", candidateFacts.issuedAt(),
                        candidateFacts.expiresAt()), candidatePair);
        ObjectNode environmentProof = proof(
                CapabilityStudioMountedTargetAdmissionBundle.ENVIRONMENT_PROOF_VERSION,
                "ENVIRONMENT_AUTHORITY", "environment-key", environmentFacts.issuedAt(),
                environmentFacts.expiresAt(),
                CapabilityStudioMountedTargetAdmissionBundle.environmentProofFingerprint(
                        environmentFacts, proofContext, "policy:environment",
                        environmentKeySet.path("snapshotFingerprint").textValue(),
                        "environment-key", environmentFacts.issuedAt(),
                        environmentFacts.expiresAt()), environmentPair);

        write(root.resolve("target.json"), target);
        write(root.resolve("candidate.json"), candidate);
        write(root.resolve("environment.json"), environment);
        write(root.resolve("candidate-keys.json"), candidateKeySet);
        write(root.resolve("environment-keys.json"), environmentKeySet);
        write(root.resolve("candidate-proof.json"), candidateProof);
        write(root.resolve("environment-proof.json"), environmentProof);

        ObjectNode manifest = targetManifest(root, target, candidate, environment,
                candidateKeySet, environmentKeySet, now, name);
        write(root.resolve(CapabilityStudioMountedTargetAdmissionBundle.MANIFEST_FILE), manifest);
    }

    private static ObjectNode targetManifest(
            Path root,
            ObjectNode target,
            ObjectNode candidate,
            ObjectNode environment,
            ObjectNode candidateKeySet,
            ObjectNode environmentKeySet,
            Instant now,
            String name) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schemaVersion",
                "resource-gateway.capability-studio.mounted-target-admission-bundle.v1");
        manifest.put("bundleId", "target-admission:" + safeName(name));
        manifest.put("revision", 1);
        manifest.put("lifecycleState", "ACTIVE");
        manifest.putNull("predecessorBundleFingerprint");
        manifest.putObject("revocationAuthority")
                .put("registryRef", "registry:target-admission")
                .put("revision", 1)
                .put("snapshotFingerprint", fingerprint('7'))
                .put("observedAt", now.minusSeconds(120).toString())
                .put("expiresAt", now.plusSeconds(1200).toString());
        manifest.put("generatedAt", now.minusSeconds(120).toString());
        manifest.put("expiresAt", now.plusSeconds(1200).toString());
        manifest.put("executionLeaseId", LEASE);
        manifest.putArray("trustedTargetIdentities").add(IDENTITY);
        manifest.putObject("targetBinding")
                .put("file", "target.json")
                .put("fileFingerprint", sha256(bytes(root.resolve("target.json"))))
                .put("canonicalFingerprint", target.path("fingerprint").textValue());
        ObjectNode candidateNode = manifest.putObject("candidate");
        candidateNode.putObject("attestation")
                .put("file", "candidate.json")
                .put("fileFingerprint", sha256(bytes(root.resolve("candidate.json"))))
                .put("reference", candidate.path("candidateRef").textValue())
                .put("revision", 1);
        policy(candidateNode.putObject("policy"), "policy:candidate",
                "CANDIDATE_AUTHORITY", "issuer:candidate-authority", "candidate-keys.json",
                candidateKeySet.path("snapshotFingerprint").textValue(),
                "candidate-proof.json", root);
        ObjectNode environmentNode = manifest.putObject("environment");
        environmentNode.putObject("attestation")
                .put("file", "environment.json")
                .put("fileFingerprint", sha256(bytes(root.resolve("environment.json"))))
                .put("reference", environment.path("environmentRef").textValue())
                .put("revision", 1);
        policy(environmentNode.putObject("policy"), "policy:environment",
                "ENVIRONMENT_AUTHORITY", "issuer:deployment-control-plane",
                "environment-keys.json",
                environmentKeySet.path("snapshotFingerprint").textValue(),
                "environment-proof.json", root);
        manifest.put("bundleFingerprint", fingerprint('0'));
        manifest.put("bundleFingerprint",
                CapabilityStudioMountedTargetAdmissionBundle
                        .canonicalManifestFingerprint(manifest));
        return manifest;
    }

    private static void policy(
            ObjectNode policy,
            String policyRef,
            String role,
            String issuer,
            String keySetFile,
            String keySetPin,
            String proofFile,
            Path root) {
        policy.put("policyRef", policyRef);
        policy.put("role", role);
        policy.put("issuer", issuer);
        policy.put("scope", SCOPE);
        policy.put("keySetFile", keySetFile);
        policy.put("keySetFileFingerprint", sha256(bytes(root.resolve(keySetFile))));
        policy.put("pinnedKeySetFingerprint", keySetPin);
        policy.put("maximumProofTtlSeconds", 3600);
        policy.put("proofFile", proofFile);
        policy.put("proofFileFingerprint", sha256(bytes(root.resolve(proofFile))));
    }

    private static ObjectNode proof(
            String version,
            String role,
            String keyId,
            Instant signedAt,
            Instant expiresAt,
            String signedFactsFingerprint,
            KeyPair pair) throws Exception {
        return JSON.createObjectNode()
                .put("schemaVersion", version)
                .put("role", role)
                .put("algorithm", "Ed25519")
                .put("keyId", keyId)
                .put("signedAt", signedAt.toString())
                .put("expiresAt", expiresAt.toString())
                .put("signedFactsFingerprint", signedFactsFingerprint)
                .put("signature", sign(pair, signedFactsFingerprint));
    }

    private static ObjectNode keySet(
            KeyPair pair, String keyId, String provider, Instant now) throws Exception {
        Instant generatedAt = now.minusSeconds(1800);
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        material.put("provider", provider);
        material.put("generatedAt", generatedAt.toString());
        material.put("expiresAt", now.plusSeconds(3600).toString());
        material.put("activeKeyId", keyId);
        material.put("policyCompleteness", "COMPLETE");
        material.putArray("keys").addObject()
                .put("keyId", keyId)
                .put("algorithm", "Ed25519")
                .put("encodedPublicKey", Base64.getEncoder().encodeToString(
                        pair.getPublic().getEncoded()))
                .put("createdAt", now.minusSeconds(3600).toString())
                .put("notBefore", now.minusSeconds(3600).toString())
                .putNull("notAfter")
                .put("state", "ACTIVE")
                .put("providerKeyVersion", "v1");
        ArrayNode events = material.putArray("events");
        event(events, 1, keyId + "-created", keyId, "CREATED",
                now.minusSeconds(3600), now);
        event(events, 2, keyId + "-activated", keyId, "ACTIVATED", generatedAt, now);
        String snapshotFingerprint = canonicalSha256(material);
        ObjectNode keySet = material.deepCopy();
        keySet.put("snapshotFingerprint", snapshotFingerprint);
        keySet.putObject("attestation")
                .put("schemaVersion", "bloge.visualRunEvidenceSeal.v1")
                .put("materialFingerprint", snapshotFingerprint)
                .put("algorithm", "Ed25519")
                .put("keyId", keyId)
                .put("signedAt", generatedAt.plusSeconds(1).toString())
                .put("signature", sign(pair, snapshotFingerprint));
        return keySet;
    }

    private static void event(
            ArrayNode events,
            long sequence,
            String eventId,
            String keyId,
            String type,
            Instant effectiveAt,
            Instant now) {
        events.addObject()
                .put("sequence", sequence)
                .put("eventId", eventId)
                .put("keyId", keyId)
                .put("type", type)
                .put("occurredAt", now.minusSeconds(1800).toString())
                .put("effectiveAt", effectiveAt.toString())
                .putNull("revocationMode")
                .putNull("invalidFrom")
                .put("reasonCode", "KEY_LIFECYCLE");
    }

    private static ObjectNode candidateAttestation(Instant now) {
        ObjectNode candidate = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .CANDIDATE_ATTESTATION_SCHEMA_VERSION)
                .put("candidateRef", "candidate:mounted-provider")
                .put("attestationRevision", 1)
                .put("role", "CANDIDATE_AUTHORITY")
                .put("buildRef", "build:mounted-provider")
                .put("revision", "rev-1")
                .put("sourceCommit", "abcdef1234567")
                .put("sourceTreeStatus", "CLEAN")
                .put("artifactDigest", fingerprint('5'))
                .put("executionIntentFingerprint", fingerprint('4'))
                .put("scope", SCOPE)
                .put("issuer", "issuer:candidate-authority")
                .put("issuedAt", now.minusSeconds(600).toString())
                .put("expiresAt", now.plusSeconds(1800).toString());
        reference(candidate, "baselineRef", "baseline:mounted-provider:v1", '1');
        reference(candidate, "demoPackRef", "demo-pack:mounted-provider:v1", '2');
        return candidate;
    }

    private static ObjectNode environmentAttestation(
            Instant now, ObjectNode candidate, String candidateRaw) {
        ObjectNode environment = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .ENVIRONMENT_ATTESTATION_SCHEMA_VERSION)
                .put("environmentRef", "environment:mounted-provider")
                .put("attestationRevision", 1)
                .put("role", "ENVIRONMENT_AUTHORITY")
                .put("executionLeaseId", LEASE)
                .put("environmentFingerprint", fingerprint('8'))
                .put("targetProfile", "profile:acceptance")
                .put("scope", SCOPE)
                .put("region", "region:sg1")
                .put("runtimeIdentity", IDENTITY)
                .put("networkPolicy", "network-policy:acceptance")
                .put("logicalClock", now.minusSeconds(300).toString())
                .put("issuer", "issuer:deployment-control-plane")
                .put("issuedAt", now.minusSeconds(600).toString())
                .put("expiresAt", now.plusSeconds(1800).toString());
        environment.putObject("candidateAttestation")
                .put("candidateRef", candidate.path("candidateRef").textValue())
                .put("attestationRevision", 1)
                .put("fingerprint", candidateRaw);
        reference(environment, "featureFlagsRef", "feature-flags:mounted-provider:v1", '6');
        environment.putObject("admissionWindow")
                .put("from", now.minusSeconds(600).toString())
                .put("through", now.plusSeconds(1800).toString());
        environment.putArray("trustedTargetIdentities").add(IDENTITY);
        return environment;
    }

    private static ObjectNode targetBinding(
            ObjectNode candidate,
            String candidateRaw,
            ObjectNode environment,
            String environmentRaw) {
        ObjectNode target = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .TARGET_BINDING_SCHEMA_VERSION)
                .put("resultId", "SAR-mounted-provider")
                .put("resultRevision", 1)
                .put("contractId", "contract:mounted-provider")
                .put("contractRevision", "1")
                .put("executionLeaseId", LEASE);
        target.putObject("candidateAttestation")
                .put("candidateRef", candidate.path("candidateRef").textValue())
                .put("attestationRevision", 1)
                .put("fingerprint", candidateRaw);
        target.putObject("environmentAttestation")
                .put("environmentRef", environment.path("environmentRef").textValue())
                .put("attestationRevision", 1)
                .put("fingerprint", environmentRaw);
        target.putArray("trustedTargetIdentities").add(IDENTITY);
        target.put("fingerprint", fingerprint('0'));
        target.put("fingerprint", CapabilityStudioStageAcceptanceTargetBindingVerifier
                .targetBindingFingerprint(target));
        return target;
    }

    private static CandidateAttestationFacts candidateFacts(
            ObjectNode candidate, CandidateCoordinate coordinate) {
        return new CandidateAttestationFacts(coordinate,
                candidate.path("buildRef").textValue(), candidate.path("revision").textValue(),
                candidate.path("sourceCommit").textValue(),
                candidate.path("sourceTreeStatus").textValue(),
                candidate.path("artifactDigest").textValue(),
                exactReference(candidate.path("baselineRef")),
                exactReference(candidate.path("demoPackRef")),
                candidate.path("executionIntentFingerprint").textValue(), SCOPE,
                "CANDIDATE_AUTHORITY", candidate.path("issuer").textValue(),
                Instant.parse(candidate.path("issuedAt").textValue()),
                Instant.parse(candidate.path("expiresAt").textValue()));
    }

    private static EnvironmentAttestationFacts environmentFacts(
            ObjectNode environment, EnvironmentCoordinate coordinate) {
        JsonNode candidate = environment.path("candidateAttestation");
        CandidateCoordinate boundCandidate = new CandidateCoordinate(
                candidate.path("candidateRef").textValue(),
                candidate.path("attestationRevision").longValue(),
                candidate.path("fingerprint").textValue());
        JsonNode window = environment.path("admissionWindow");
        return new EnvironmentAttestationFacts(coordinate,
                environment.path("executionLeaseId").textValue(), boundCandidate,
                environment.path("environmentFingerprint").textValue(),
                environment.path("targetProfile").textValue(), SCOPE,
                environment.path("region").textValue(),
                environment.path("runtimeIdentity").textValue(),
                environment.path("networkPolicy").textValue(),
                exactReference(environment.path("featureFlagsRef")),
                Instant.parse(environment.path("logicalClock").textValue()),
                new AdmissionWindow(Instant.parse(window.path("from").textValue()),
                        Instant.parse(window.path("through").textValue())),
                Set.of(IDENTITY), "ENVIRONMENT_AUTHORITY",
                environment.path("issuer").textValue(),
                Instant.parse(environment.path("issuedAt").textValue()),
                Instant.parse(environment.path("expiresAt").textValue()));
    }

    private static ExactReference exactReference(JsonNode value) {
        return new ExactReference(value.path("exactRef").textValue(),
                value.path("fingerprint").textValue());
    }

    private static void reference(ObjectNode parent, String field, String exactRef, char seed) {
        parent.set(field, JSON.createObjectNode().put("exactRef", exactRef)
                .put("fingerprint", fingerprint(seed)));
    }

    private static void write(Path path, JsonNode value) throws Exception {
        Files.write(path, JSON.writeValueAsBytes(value));
    }

    private static byte[] bytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String sign(KeyPair pair, String fingerprint) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String canonicalSha256(JsonNode value) {
        try {
            return sha256(JSON.writeValueAsBytes(canonical(value)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    record Fixture(Path authorityRoot, Path targetRoot, Path stateRoot) { }
}
