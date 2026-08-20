package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.AdmissionWindow;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateAttestationFacts;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAttestationFacts;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.ExactReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioMountedTargetAdmissionBundleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:12:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String LEASE = "lease:stage-acceptance:1";
    private static final String IDENTITY = "runtime:capability-studio";
    private static final String SCOPE = "tenant:demo/environment:acceptance";

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsImmutableSnapshotAndVerifiesTheCompleteTargetPath() throws Exception {
        Fixture fixture = Fixture.write(temporaryDirectory.resolve("valid"));

        CapabilityStudioMountedTargetAdmissionBundle bundle =
                CapabilityStudioMountedTargetAdmissionBundle.load(fixture.root, CLOCK);
        var admission = bundle.targetAdmissionBinding();
        var verification = verify(fixture.stageBytes, admission);

        assertThat(verification.verified()).as(verification.toString()).isTrue();
        assertThat(bundle.bundleFingerprint()).isEqualTo(bundle.lifecycleMaterial()
                .bundleFingerprint());
        assertThat(bundle.targetRawFingerprint()).isEqualTo(sha256(fixture.targetBytes));
        assertThat(bundle.targetCanonicalFingerprint())
                .isEqualTo(fixture.target.path("fingerprint").textValue());
        assertThat(bundle.toString()).doesNotContain(
                fixture.candidateProof.path("signature").asText(), LEASE, IDENTITY);
        assertThat(admission.toString()).doesNotContain(
                fixture.candidateProof.path("signature").asText(), LEASE, IDENTITY);

        byte[] targetCopy = admission.targetBindingBytes();
        targetCopy[0] ^= 1;
        assertThat(admission.targetBindingBytes()).containsExactly(fixture.targetBytes);
        try (var files = Files.list(fixture.root)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        assertThat(verify(fixture.stageBytes, admission).verified()).isTrue();
    }

    @Test
    void rejectsRawManifestSignatureAndKeyPinTamper() throws Exception {
        Fixture raw = Fixture.write(temporaryDirectory.resolve("raw"));
        Files.writeString(raw.root.resolve("target.json"), "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertLoadFails(raw.root, "FILE_FINGERPRINT_MISMATCH");

        Fixture manifest = Fixture.write(temporaryDirectory.resolve("manifest"));
        mutateManifest(manifest.root,
                value -> value.put("bundleFingerprint", fingerprint('0')), false);
        assertLoadFails(manifest.root, "MANIFEST_FINGERPRINT_INVALID");

        Fixture signature = Fixture.write(temporaryDirectory.resolve("signature"));
        ObjectNode candidateProof = read(signature.root.resolve("candidate-proof.json"));
        candidateProof.put("signature", flipBase64(candidateProof.path("signature").asText()));
        write(signature.root.resolve("candidate-proof.json"), candidateProof);
        refreshFileFingerprint(signature.root, "candidate", "policy",
                "proofFile", "proofFileFingerprint");
        assertLoadFails(signature.root, "DETACHED_PROOF_INVALID");

        Fixture pin = Fixture.write(temporaryDirectory.resolve("pin"));
        mutateManifest(pin.root, value -> ((ObjectNode) value.path("candidate").path("policy"))
                .put("pinnedKeySetFingerprint", fingerprint('f')), true);
        assertLoadFails(pin.root, "KEY_SET_ADMISSION_REJECTED");
    }

    @Test
    void rejectsManifestAndProofLifecycleDrift() throws Exception {
        Fixture inactive = Fixture.write(temporaryDirectory.resolve("inactive"));
        mutateManifest(inactive.root,
                value -> value.put("lifecycleState", "RETIRED"), false);
        assertLoadFails(inactive.root, "MANIFEST_SCHEMA_INVALID");

        Fixture staleRevocation = Fixture.write(
                temporaryDirectory.resolve("stale-revocation"));
        mutateManifest(staleRevocation.root, value -> ((ObjectNode) value
                .path("revocationAuthority"))
                .put("expiresAt", NOW.minusSeconds(1).toString()), true);
        assertLoadFails(staleRevocation.root, "REVOCATION_SNAPSHOT_NOT_CURRENT");

        Fixture predecessor = Fixture.write(temporaryDirectory.resolve("predecessor"));
        mutateManifest(predecessor.root,
                value -> value.put("predecessorBundleFingerprint", fingerprint('6')), true);
        assertThat(CapabilityStudioMountedTargetAdmissionBundle.load(predecessor.root, CLOCK)
                .lifecycleMaterial().predecessorBundleFingerprint())
                .isEqualTo(fingerprint('6'));

        Fixture future = Fixture.write(temporaryDirectory.resolve("future"));
        mutateManifest(future.root, value -> {
            value.put("generatedAt", NOW.plusSeconds(1).toString());
            value.put("expiresAt", NOW.plusSeconds(3601).toString());
        }, true);
        assertLoadFails(future.root, "MANIFEST_NOT_YET_VALID");

        Fixture expired = Fixture.write(temporaryDirectory.resolve("expired"));
        mutateManifest(expired.root, value -> {
            value.put("generatedAt", NOW.minusSeconds(3601).toString());
            value.put("expiresAt", NOW.minusSeconds(1).toString());
        }, true);
        assertLoadFails(expired.root, "MANIFEST_EXPIRED");

        Fixture proofExpired = Fixture.write(temporaryDirectory.resolve("proof-expired"));
        mutateProof(proofExpired, "candidate-proof.json",
                value -> value.put("expiresAt", NOW.minusSeconds(1).toString()), "candidate");
        assertLoadFails(proofExpired.root, "DETACHED_PROOF_INVALID");

        Fixture proofFuture = Fixture.write(temporaryDirectory.resolve("proof-future"));
        mutateProof(proofFuture, "environment-proof.json",
                value -> value.put("signedAt", NOW.plusSeconds(1).toString()), "environment");
        assertLoadFails(proofFuture.root, "DETACHED_PROOF_INVALID");

        Fixture ttl = Fixture.write(temporaryDirectory.resolve("proof-ttl"));
        mutateManifest(ttl.root, value -> ((ObjectNode) value.path("candidate").path("policy"))
                .put("maximumProofTtlSeconds", 1799), true);
        assertLoadFails(ttl.root, "DETACHED_PROOF_INVALID");
    }

    @Test
    void rejectsUnknownAndRevokedProofKeys() throws Exception {
        Fixture unknown = Fixture.write(temporaryDirectory.resolve("unknown-key"));
        ObjectNode proof = read(unknown.root.resolve("candidate-proof.json"));
        proof.put("keyId", "candidate-key-unknown");
        proof.put("signedFactsFingerprint",
                CapabilityStudioMountedTargetAdmissionBundle.candidateProofFingerprint(
                        unknown.candidateFacts, unknown.proofContext, "policy:candidate",
                        unknown.candidateKeySet.path("snapshotFingerprint").asText(),
                        "candidate-key-unknown", unknown.candidateFacts.issuedAt(),
                        unknown.candidateFacts.expiresAt()));
        proof.put("signature", sign(unknown.candidatePair,
                proof.path("signedFactsFingerprint").asText()));
        write(unknown.root.resolve("candidate-proof.json"), proof);
        refreshFileFingerprint(unknown.root, "candidate", "policy",
                "proofFile", "proofFileFingerprint");
        assertLoadFails(unknown.root, "DETACHED_PROOF_INVALID");

        Fixture revoked = Fixture.write(temporaryDirectory.resolve("revoked-key"));
        ObjectNode keySet = read(revoked.root.resolve("candidate-keys.json"));
        ((ObjectNode) keySet.path("keys").get(0)).put("state", "REVOKED");
        keySet.withArray("events").addObject()
                .put("sequence", 3)
                .put("eventId", "candidate-key-revoked")
                .put("keyId", "candidate-key")
                .put("type", "REVOKED")
                .put("occurredAt", NOW.minusSeconds(120).toString())
                .put("effectiveAt", NOW.minusSeconds(600).toString())
                .put("revocationMode", "RETROACTIVE")
                .put("invalidFrom", NOW.minusSeconds(1800).toString())
                .put("reasonCode", "KEY_REVOKED");
        resealKeySet(keySet, revoked.candidatePair);
        write(revoked.root.resolve("candidate-keys.json"), keySet);
        mutateManifest(revoked.root, value -> {
            ObjectNode policy = (ObjectNode) value.path("candidate").path("policy");
            policy.put("keySetFileFingerprint", sha256(bytes(
                    revoked.root.resolve("candidate-keys.json"))));
            policy.put("pinnedKeySetFingerprint",
                    keySet.path("snapshotFingerprint").asText());
        }, true);
        assertLoadFails(revoked.root, "KEY_SET_ADMISSION_REJECTED");
    }

    @Test
    void rejectsAuthorityCollapseAndRoleConfusion() throws Exception {
        Fixture issuer = Fixture.write(temporaryDirectory.resolve("issuer-collapse"));
        mutateManifest(issuer.root, value -> ((ObjectNode) value.path("environment").path("policy"))
                .put("issuer", "issuer:candidate-authority"), true);
        assertLoadFails(issuer.root, "AUTHORITY_POLICY_COLLAPSE");

        Fixture role = Fixture.write(temporaryDirectory.resolve("role-confusion"));
        mutateManifest(role.root, value -> ((ObjectNode) value.path("candidate").path("policy"))
                .put("role", "ENVIRONMENT_AUTHORITY"), false);
        assertLoadFails(role.root, "MANIFEST_SCHEMA_INVALID");

        Fixture shared = Fixture.write(temporaryDirectory.resolve("shared-policy"));
        mutateManifest(shared.root, value -> {
            ObjectNode candidate = (ObjectNode) value.path("candidate").path("policy");
            ObjectNode environment = (ObjectNode) value.path("environment").path("policy");
            environment.put("keySetFile", candidate.path("keySetFile").textValue());
            environment.put("keySetFileFingerprint",
                    candidate.path("keySetFileFingerprint").textValue());
            environment.put("pinnedKeySetFingerprint",
                    candidate.path("pinnedKeySetFingerprint").textValue());
        }, true);
        assertLoadFails(shared.root, "DUPLICATE_FILE_BINDING");

        Fixture scope = Fixture.write(temporaryDirectory.resolve("scope-drift"));
        mutateManifest(scope.root, value -> ((ObjectNode) value.path("candidate").path("policy"))
                .put("scope", "tenant:other/environment:acceptance"), true);
        assertLoadFails(scope.root, "DETACHED_PROOF_INVALID");
    }

    @Test
    void candidateProofDomainDriftsAcrossTargetLeaseAndIdentityCoordinates() throws Exception {
        Fixture fixture = Fixture.write(temporaryDirectory.resolve("proof-domain"));
        var context = fixture.proofContext;
        String pin = fixture.candidateKeySet.path("snapshotFingerprint").asText();
        Instant signedAt = fixture.candidateFacts.issuedAt();
        Instant expiresAt = fixture.candidateFacts.expiresAt();

        assertThat(Set.of(
                candidateProofFingerprint(fixture, context, pin, signedAt, expiresAt),
                candidateProofFingerprint(fixture, new CapabilityStudioMountedTargetAdmissionBundle
                        .ProofBindingContext(fingerprint('a'),
                        context.targetCanonicalFingerprint(), context.candidateCoordinate(),
                        context.environmentCoordinate(), context.executionLeaseId(),
                        context.trustedTargetIdentities()), pin, signedAt, expiresAt),
                candidateProofFingerprint(fixture, new CapabilityStudioMountedTargetAdmissionBundle
                        .ProofBindingContext(context.targetRawFingerprint(), fingerprint('b'),
                        context.candidateCoordinate(), context.environmentCoordinate(),
                        context.executionLeaseId(), context.trustedTargetIdentities()),
                        pin, signedAt, expiresAt),
                candidateProofFingerprint(fixture, new CapabilityStudioMountedTargetAdmissionBundle
                        .ProofBindingContext(context.targetRawFingerprint(),
                        context.targetCanonicalFingerprint(),
                        new CandidateCoordinate("candidate:other", 1, fingerprint('d')),
                        context.environmentCoordinate(), context.executionLeaseId(),
                        context.trustedTargetIdentities()), pin, signedAt, expiresAt),
                candidateProofFingerprint(fixture, new CapabilityStudioMountedTargetAdmissionBundle
                        .ProofBindingContext(context.targetRawFingerprint(),
                        context.targetCanonicalFingerprint(), context.candidateCoordinate(),
                        new EnvironmentCoordinate("attestation:environment:other", 1,
                                fingerprint('c')),
                        context.executionLeaseId(), context.trustedTargetIdentities()),
                        pin, signedAt, expiresAt),
                candidateProofFingerprint(fixture, new CapabilityStudioMountedTargetAdmissionBundle
                        .ProofBindingContext(context.targetRawFingerprint(),
                        context.targetCanonicalFingerprint(), context.candidateCoordinate(),
                        context.environmentCoordinate(), "lease:other",
                        context.trustedTargetIdentities()), pin, signedAt, expiresAt),
                candidateProofFingerprint(fixture, new CapabilityStudioMountedTargetAdmissionBundle
                        .ProofBindingContext(context.targetRawFingerprint(),
                        context.targetCanonicalFingerprint(), context.candidateCoordinate(),
                        context.environmentCoordinate(), context.executionLeaseId(),
                        Set.of("runtime:other")), pin, signedAt, expiresAt)))
                .hasSize(7);
    }

    @Test
    void environmentProofDomainDriftsAcrossEveryRuntimeAndReplayCoordinate() throws Exception {
        Fixture fixture = Fixture.write(temporaryDirectory.resolve("environment-proof-domain"));
        ObjectNode source = read(fixture.root.resolve("environment.json"));
        String keyPin = read(fixture.root.resolve("environment-keys.json"))
                .path("snapshotFingerprint").textValue();
        var coordinate = fixture.proofContext.environmentCoordinate();
        var candidate = fixture.proofContext.candidateCoordinate();

        List<ObjectNode> variants = new java.util.ArrayList<>();
        for (String field : List.of("runtimeIdentity", "region", "networkPolicy")) {
            ObjectNode drift = source.deepCopy();
            drift.put(field, field.toLowerCase() + ":other");
            variants.add(drift);
        }
        ObjectNode flags = source.deepCopy();
        ((ObjectNode) flags.path("featureFlagsRef")).put("fingerprint", fingerprint('e'));
        variants.add(flags);
        ObjectNode candidateDrift = source.deepCopy();
        ((ObjectNode) candidateDrift.path("candidateAttestation"))
                .put("candidateRef", "candidate:other");
        variants.add(candidateDrift);
        ObjectNode lease = source.deepCopy();
        lease.put("executionLeaseId", "lease:other");
        variants.add(lease);
        ObjectNode identities = source.deepCopy();
        identities.putArray("trustedTargetIdentities").add("runtime:other");
        variants.add(identities);

        Set<String> fingerprints = new HashSet<>();
        var originalFacts = environmentFacts(source, coordinate, candidate);
        fingerprints.add(CapabilityStudioMountedTargetAdmissionBundle
                .environmentProofFingerprint(originalFacts, fixture.proofContext,
                "policy:environment", keyPin, "environment-key",
                originalFacts.issuedAt(), originalFacts.expiresAt()));
        for (ObjectNode variant : variants) {
            var facts = environmentFacts(variant, coordinate, candidate);
            fingerprints.add(CapabilityStudioMountedTargetAdmissionBundle
                    .environmentProofFingerprint(facts, fixture.proofContext,
                    "policy:environment", keyPin, "environment-key",
                    facts.issuedAt(), facts.expiresAt()));
        }
        assertThat(fingerprints).hasSize(8);
    }

    @Test
    void mountedCallbacksRejectExpiryAndUnexpectedRuntimeFailure() throws Exception {
        Fixture fixture = Fixture.write(temporaryDirectory.resolve("callback-failure"));
        MutableClock clock = new MutableClock(NOW);
        var admission = CapabilityStudioMountedTargetAdmissionBundle
                .load(fixture.root, clock).targetAdmissionBinding();

        clock.current = NOW.plusSeconds(901);
        var expired = admission.candidateAuthority().verify(fixture.candidateFacts);
        clock.current = NOW;
        clock.fail = true;
        var failed = admission.candidateAuthority().verify(fixture.candidateFacts);

        assertThat(expired.status()).isEqualTo(
                CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.Decision
                        .REJECTED);
        assertThat(failed.status()).isEqualTo(
                CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.Decision
                        .UNAVAILABLE);
        assertThat(failed.toString()).doesNotContain(LEASE, IDENTITY, "candidate:capability");
    }

    @Test
    void rejectsLeaseIdentityAndFilesystemBoundaryDrift() throws Exception {
        Fixture lease = Fixture.write(temporaryDirectory.resolve("lease"));
        mutateManifest(lease.root,
                value -> value.put("executionLeaseId", "lease:other"), true);
        assertLoadFails(lease.root, "TARGET_CONTEXT_MISMATCH");

        Fixture identity = Fixture.write(temporaryDirectory.resolve("identity"));
        mutateManifest(identity.root, value -> value.putArray("trustedTargetIdentities")
                .add("runtime:other"), true);
        assertLoadFails(identity.root, "TARGET_CONTEXT_MISMATCH");

        Fixture symlink = Fixture.write(temporaryDirectory.resolve("symlink"));
        Files.delete(symlink.root.resolve("candidate.json"));
        Files.createSymbolicLink(symlink.root.resolve("candidate.json"),
                symlink.root.resolve("environment.json"));
        assertLoadFails(symlink.root, "FILE_INVALID");

        Fixture traversal = Fixture.write(temporaryDirectory.resolve("traversal"));
        mutateManifest(traversal.root, value -> ((ObjectNode) value.path("candidate")
                .path("attestation")).put("file", "../candidate.json"), false);
        assertLoadFails(traversal.root, "MANIFEST_SCHEMA_INVALID");

        Fixture unlisted = Fixture.write(temporaryDirectory.resolve("unlisted"));
        Files.writeString(unlisted.root.resolve("extra.json"), "{}");
        assertLoadFails(unlisted.root, "UNLISTED_FILE");

        Fixture hardLink = Fixture.write(temporaryDirectory.resolve("hard-link"));
        Files.delete(hardLink.root.resolve("environment-proof.json"));
        Files.createLink(hardLink.root.resolve("environment-proof.json"),
                hardLink.root.resolve("candidate-proof.json"));
        mutateManifest(hardLink.root, value -> ((ObjectNode) value.path("environment")
                .path("policy")).put("proofFileFingerprint", sha256(bytes(
                hardLink.root.resolve("environment-proof.json")))), true);
        assertLoadFails(hardLink.root, "FILE_IDENTITY_COLLISION");
    }

    @Test
    void rejectsFormattingEquivalentTargetRawDrift() throws Exception {
        Fixture fixture = Fixture.write(temporaryDirectory.resolve("formatting"));
        String canonicalBefore = CapabilityStudioStageAcceptanceTargetBindingVerifier
                .targetBindingFingerprint(fixture.target);
        byte[] reformatted = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(fixture.target);
        JsonNode decoded = JSON.readTree(reformatted);

        assertThat(CapabilityStudioStageAcceptanceTargetBindingVerifier
                .targetBindingFingerprint(decoded)).isEqualTo(canonicalBefore);
        assertThat(sha256(reformatted)).isNotEqualTo(sha256(fixture.targetBytes));

        Files.write(fixture.root.resolve("target.json"), reformatted);
        assertLoadFails(fixture.root, "FILE_FINGERPRINT_MISMATCH");
    }

    @Test
    void packagesTheClosedManifestSchema() throws Exception {
        String resource = CapabilityStudioSchemaSupport
                .MOUNTED_TARGET_ADMISSION_BUNDLE_V1_RESOURCE;
        try (var input = CapabilityStudioMountedTargetAdmissionBundle.class
                .getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            assertThat(JSON.readTree(input).path("additionalProperties").booleanValue()).isFalse();
        }
        for (String proofResource : List.of(
                CapabilityStudioSchemaSupport.CANDIDATE_TARGET_ADMISSION_PROOF_V1_RESOURCE,
                CapabilityStudioSchemaSupport.ENVIRONMENT_TARGET_ADMISSION_PROOF_V1_RESOURCE)) {
            try (var input = CapabilityStudioMountedTargetAdmissionBundle.class
                    .getResourceAsStream(proofResource)) {
                assertThat(input).isNotNull();
                assertThat(JSON.readTree(input).path("additionalProperties").booleanValue())
                        .isFalse();
            }
        }

        Fixture fixture = Fixture.write(temporaryDirectory.resolve("schema-closed"));
        mutateManifest(fixture.root,
                value -> value.put("unexpected", "forbidden"), false);
        assertLoadFails(fixture.root, "MANIFEST_SCHEMA_INVALID");

        Fixture duplicate = Fixture.write(temporaryDirectory.resolve("duplicate-field"));
        Path manifest = duplicate.root.resolve(
                CapabilityStudioMountedTargetAdmissionBundle.MANIFEST_FILE);
        String wire = Files.readString(manifest);
        Files.writeString(manifest, wire.replaceFirst(
                "\\\"bundleId\\\":", "\\\"bundleId\\\":\\\"duplicate\\\",\\\"bundleId\\\":"));
        assertLoadFails(duplicate.root, "JSON_INVALID");

        Fixture unknownProof = Fixture.write(temporaryDirectory.resolve("unknown-proof-field"));
        ObjectNode candidateProof = read(unknownProof.root.resolve("candidate-proof.json"));
        candidateProof.put("unexpected", "forbidden");
        write(unknownProof.root.resolve("candidate-proof.json"), candidateProof);
        refreshFileFingerprint(unknownProof.root, "candidate", "policy",
                "proofFile", "proofFileFingerprint");
        assertLoadFails(unknownProof.root, "PROOF_INVALID");

        Fixture duplicateProof = Fixture.write(temporaryDirectory.resolve("duplicate-proof-field"));
        Path proofPath = duplicateProof.root.resolve("environment-proof.json");
        String proofWire = Files.readString(proofPath);
        Files.writeString(proofPath, proofWire.replaceFirst(
                "\\\"role\\\":", "\\\"role\\\":\\\"duplicate\\\",\\\"role\\\":"));
        refreshFileFingerprint(duplicateProof.root, "environment", "policy",
                "proofFile", "proofFileFingerprint");
        assertLoadFails(duplicateProof.root, "JSON_INVALID");
    }

    @Test
    void locallyValidBundleStillRequiresTheIndependentFormalOuterPin() throws Exception {
        Fixture fixture = Fixture.write(temporaryDirectory.resolve("attacker-self-consistent"));
        CapabilityStudioMountedTargetAdmissionBundle bundle =
                CapabilityStudioMountedTargetAdmissionBundle.load(fixture.root, CLOCK);
        AtomicInteger postRunCalls = new AtomicInteger();
        var resolver = (CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver)
                request -> {
                    postRunCalls.incrementAndGet();
                    return CapabilityStudioStageAcceptanceAuthorityVerifier
                            .EvidenceResolution.unavailable();
                };
        var issuer = (CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy)
                (reference, evidence, context) -> {
                    postRunCalls.incrementAndGet();
                    return CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                            .unavailable();
                };
        var owner = (CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority)
                (signoff, signature, context) -> {
                    postRunCalls.incrementAndGet();
                    return CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                            .unavailable();
                };
        var authorityBinding = new CapabilityStudioStageAcceptanceAuthorityProvider
                .AuthorityBinding(fingerprint('a'), resolver, issuer, owner);
        String clockFingerprint = fingerprint('6');
        String lifecycleAuthorityFingerprint = fingerprint('7');
        String leaseAuthorityFingerprint = fingerprint('8');
        var deploymentAuthority = new CapabilityStudioStageAcceptanceAuthorityProvider
                .DeploymentAdmissionAuthorityBinding(
                new CapabilityStudioStageAcceptanceAuthorityProvider
                        .TrustedVerificationClockBinding(clockFingerprint, () -> NOW),
                new CapabilityStudioStageAcceptanceAuthorityProvider
                        .AdmissionLifecycleAuthorityBinding(
                        lifecycleAuthorityFingerprint,
                        request -> CapabilityStudioStageAcceptanceAuthorityProvider
                                .DeploymentAuthorityDecision.verified("LIFECYCLE_VERIFIED")),
                new CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExecutionLeaseAuthorityBinding(leaseAuthorityFingerprint, request -> {
                    var revocation = request.lifecycleMaterial().revocationAuthority();
                    var lifecycleReceipt = new CapabilityStudioStageAcceptanceAuthorityProvider
                            .AtomicAdmissionLifecycleCommitReceipt(
                            request.deploymentAdmissionAuthorityMaterialFingerprint(),
                            request.lifecycleMaterial().fingerprint(), revocation.registryRef(),
                            revocation.revision(), revocation.snapshotFingerprint(), 1, NOW,
                            request.commitIdentityFingerprint());
                    var receipt = new CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExecutionLeaseReceipt(request.commitIdentityFingerprint(),
                            request.lifecycleMaterial(), lifecycleReceipt);
                    return CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExecutionLeaseCommitResult.committed(receipt, "LEASE_COMMITTED");
                }));
        var admission = bundle.formalTargetAdmissionBinding(deploymentAuthority);
        var outer = new CapabilityStudioStageAcceptanceAuthorityProvider
                .FormalTargetBoundAuthorityBinding(authorityBinding, admission);
        CapabilityStudioStageAcceptanceAuthorityProvider provider =
                new CapabilityStudioStageAcceptanceAuthorityProvider() {
                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityProvider
                            .FormalTargetBoundAuthorityBinding
                            formalTargetBoundAuthorityBinding() {
                        return outer;
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver
                            evidenceResolver() {
                        return resolver;
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                            evidenceIssuerPolicy() {
                        return issuer;
                    }

                    @Override
                    public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                            ownerAuthority() {
                        return owner;
                    }
                };
        Path stage = temporaryDirectory.resolve("attacker-stage.json");
        Files.write(stage, fixture.stageBytes);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(wire, true, StandardCharsets.UTF_8);

        int exit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{stage.toString()}, output, output, NOW,
                () -> List.of(provider), fingerprint('f'));

        assertThat(exit).isEqualTo(CapabilityStudioStageAcceptanceCli.EXIT_INVALID);
        assertThat(wire.toString(StandardCharsets.UTF_8))
                .contains("PROVIDER_CONFIGURATION")
                .doesNotContain(bundle.bundleFingerprint());
        assertThat(postRunCalls).hasValue(0);
    }

    private static CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult verify(
            byte[] stageBytes,
            CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding admission) {
        return new CapabilityStudioStageAcceptanceTargetBindingVerifier().verify(
                stageBytes, admission.targetBindingBytes(), admission.candidateAttestationBytes(),
                admission.environmentAttestationBytes(), admission.verificationContext(), NOW,
                admission.candidateAuthority(), admission.environmentAuthority());
    }

    private static String candidateProofFingerprint(
            Fixture fixture,
            CapabilityStudioMountedTargetAdmissionBundle.ProofBindingContext context,
            String pin,
            Instant signedAt,
            Instant expiresAt) {
        return CapabilityStudioMountedTargetAdmissionBundle.candidateProofFingerprint(
                fixture.candidateFacts, context, "policy:candidate", pin,
                "candidate-key", signedAt, expiresAt);
    }

    private static void mutateProof(
            Fixture fixture, String file, Consumer<ObjectNode> mutation, String authority)
            throws Exception {
        ObjectNode proof = read(fixture.root.resolve(file));
        mutation.accept(proof);
        write(fixture.root.resolve(file), proof);
        refreshFileFingerprint(fixture.root, authority, "policy",
                "proofFile", "proofFileFingerprint");
    }

    private static void refreshFileFingerprint(
            Path root, String authority, String policyField,
            String fileField, String fingerprintField) throws Exception {
        mutateManifest(root, manifest -> {
            ObjectNode policy = (ObjectNode) manifest.path(authority).path(policyField);
            Path file = root.resolve(policy.path(fileField).textValue());
            policy.put(fingerprintField, sha256(bytes(file)));
        }, true);
    }

    private static void mutateManifest(
            Path root, Consumer<ObjectNode> mutation, boolean refreshFingerprint) throws Exception {
        Path path = root.resolve(CapabilityStudioMountedTargetAdmissionBundle.MANIFEST_FILE);
        ObjectNode manifest = read(path);
        mutation.accept(manifest);
        if (refreshFingerprint) {
            manifest.put("bundleFingerprint", fingerprint('0'));
            manifest.put("bundleFingerprint",
                    CapabilityStudioMountedTargetAdmissionBundle
                            .canonicalManifestFingerprint(manifest));
        }
        write(path, manifest);
    }

    private static void resealKeySet(ObjectNode keySet, KeyPair pair) throws Exception {
        ObjectNode material = keySet.deepCopy();
        material.remove(List.of("snapshotFingerprint", "attestation"));
        String fingerprint = EvidenceVerificationSupport.sha256(material);
        keySet.put("snapshotFingerprint", fingerprint);
        ObjectNode attestation = keySet.with("attestation");
        attestation.put("materialFingerprint", fingerprint);
        attestation.put("signature", sign(pair, fingerprint));
    }

    private static void assertLoadFails(Path root, String suffix) {
        assertThatThrownBy(() -> CapabilityStudioMountedTargetAdmissionBundle.load(root, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.TARGET_ADMISSION_BUNDLE." + suffix);
    }

    private static ObjectNode read(Path path) throws Exception {
        return (ObjectNode) JSON.readTree(path.toFile());
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

    private static String flipBase64(String value) {
        return (value.charAt(0) == 'A' ? "B" : "A") + value.substring(1);
    }

    private static String sign(KeyPair pair, String fingerprint) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
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

    private static final class Fixture {
        private final Path root;
        private final byte[] stageBytes;
        private final ObjectNode target;
        private final byte[] targetBytes;
        private final ObjectNode candidateProof;
        private final ObjectNode candidateKeySet;
        private final KeyPair candidatePair;
        private final CandidateAttestationFacts candidateFacts;
        private final CapabilityStudioMountedTargetAdmissionBundle.ProofBindingContext proofContext;

        private Fixture(
                Path root,
                byte[] stageBytes,
                ObjectNode target,
                byte[] targetBytes,
                ObjectNode candidateProof,
                ObjectNode candidateKeySet,
                KeyPair candidatePair,
                CandidateAttestationFacts candidateFacts,
                CapabilityStudioMountedTargetAdmissionBundle.ProofBindingContext proofContext) {
            this.root = root;
            this.stageBytes = stageBytes;
            this.target = target;
            this.targetBytes = targetBytes;
            this.candidateProof = candidateProof;
            this.candidateKeySet = candidateKeySet;
            this.candidatePair = candidatePair;
            this.candidateFacts = candidateFacts;
            this.proofContext = proofContext;
        }

        private static Fixture write(Path root) throws Exception {
            Files.createDirectories(root);
            ObjectNode stage = CapabilityStudioStageAcceptanceAuthorityVerifierTest
                    .validStagePass();
            ObjectNode candidate = candidateAttestation();
            byte[] candidateBytes = JSON.writeValueAsBytes(candidate);
            String candidateRaw = sha256(candidateBytes);
            ObjectNode environment = environmentAttestation(stage, candidate, candidateRaw);
            byte[] environmentBytes = JSON.writeValueAsBytes(environment);
            String environmentRaw = sha256(environmentBytes);
            stage.with("environmentAttestation").put("fingerprint", environmentRaw);
            ((ObjectNode) stage.path("evidenceRefs").path(0))
                    .put("fingerprint", environmentRaw);
            refreshClosure(stage);
            ObjectNode target = targetBinding(stage, candidate, candidateRaw,
                    environment, environmentRaw);
            byte[] targetBytes = JSON.writeValueAsBytes(target);
            String targetRaw = sha256(targetBytes);
            String targetCanonical = target.path("fingerprint").textValue();

            CandidateCoordinate candidateCoordinate = new CandidateCoordinate(
                    candidate.path("candidateRef").textValue(), 1, candidateRaw);
            EnvironmentCoordinate environmentCoordinate = new EnvironmentCoordinate(
                    environment.path("environmentRef").textValue(), 1, environmentRaw);
            CandidateAttestationFacts candidateFacts = candidateFacts(
                    candidate, candidateCoordinate);
            EnvironmentAttestationFacts environmentFacts = environmentFacts(
                    environment, environmentCoordinate, candidateCoordinate);
            var proofContext = new CapabilityStudioMountedTargetAdmissionBundle
                    .ProofBindingContext(targetRaw, targetCanonical, candidateCoordinate,
                    environmentCoordinate, LEASE, Set.of(IDENTITY));

            KeyPair candidatePair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            KeyPair environmentPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            ObjectNode candidateKeySet = keySet(candidatePair, "candidate-key",
                    "candidate-provider");
            ObjectNode environmentKeySet = keySet(environmentPair, "environment-key",
                    "environment-provider");
            ObjectNode candidateProof = proof(
                    CapabilityStudioMountedTargetAdmissionBundle.CANDIDATE_PROOF_VERSION,
                    "CANDIDATE_AUTHORITY", "candidate-key", candidateFacts.issuedAt(),
                    candidateFacts.expiresAt(),
                    CapabilityStudioMountedTargetAdmissionBundle.candidateProofFingerprint(
                            candidateFacts, proofContext, "policy:candidate",
                            candidateKeySet.path("snapshotFingerprint").asText(),
                            "candidate-key", candidateFacts.issuedAt(),
                            candidateFacts.expiresAt()), candidatePair);
            ObjectNode environmentProof = proof(
                    CapabilityStudioMountedTargetAdmissionBundle.ENVIRONMENT_PROOF_VERSION,
                    "ENVIRONMENT_AUTHORITY", "environment-key", environmentFacts.issuedAt(),
                    environmentFacts.expiresAt(),
                    CapabilityStudioMountedTargetAdmissionBundle.environmentProofFingerprint(
                            environmentFacts, proofContext, "policy:environment",
                            environmentKeySet.path("snapshotFingerprint").asText(),
                            "environment-key", environmentFacts.issuedAt(),
                            environmentFacts.expiresAt()), environmentPair);

            CapabilityStudioMountedTargetAdmissionBundleTest.write(
                    root.resolve("target.json"), target);
            CapabilityStudioMountedTargetAdmissionBundleTest.write(
                    root.resolve("candidate.json"), candidate);
            CapabilityStudioMountedTargetAdmissionBundleTest.write(
                    root.resolve("environment.json"), environment);
            CapabilityStudioMountedTargetAdmissionBundleTest.write(
                    root.resolve("candidate-keys.json"), candidateKeySet);
            CapabilityStudioMountedTargetAdmissionBundleTest.write(
                    root.resolve("environment-keys.json"), environmentKeySet);
            CapabilityStudioMountedTargetAdmissionBundleTest.write(
                    root.resolve("candidate-proof.json"), candidateProof);
            CapabilityStudioMountedTargetAdmissionBundleTest.write(
                    root.resolve("environment-proof.json"), environmentProof);

            ObjectNode manifest = manifest(root, target, candidate, environment,
                    candidateKeySet, environmentKeySet);
            CapabilityStudioMountedTargetAdmissionBundleTest.write(
                    root.resolve(CapabilityStudioMountedTargetAdmissionBundle.MANIFEST_FILE),
                    manifest);
            return new Fixture(root, JSON.writeValueAsBytes(stage), target, targetBytes,
                    candidateProof, candidateKeySet, candidatePair, candidateFacts, proofContext);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;
        private boolean fail;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (fail) {
                throw new IllegalStateException("sensitive clock failure");
            }
            return current;
        }
    }

    private static ObjectNode manifest(
            Path root,
            ObjectNode target,
            ObjectNode candidate,
            ObjectNode environment,
            ObjectNode candidateKeySet,
            ObjectNode environmentKeySet) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schemaVersion",
                "resource-gateway.capability-studio.mounted-target-admission-bundle.v1");
        manifest.put("bundleId", "target-admission:demo:1");
        manifest.put("revision", 1);
        manifest.put("lifecycleState", "ACTIVE");
        manifest.putNull("predecessorBundleFingerprint");
        manifest.putObject("revocationAuthority")
                .put("registryRef", "registry:target-admission")
                .put("revision", 1)
                .put("snapshotFingerprint", fingerprint('7'))
                .put("observedAt", NOW.minusSeconds(60).toString())
                .put("expiresAt", NOW.plusSeconds(600).toString());
        manifest.put("generatedAt", NOW.minusSeconds(60).toString());
        manifest.put("expiresAt", NOW.plusSeconds(900).toString());
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
                .put("revision", candidate.path("attestationRevision").longValue());
        policy(candidateNode.putObject("policy"), "policy:candidate",
                "CANDIDATE_AUTHORITY", "issuer:candidate-authority", "candidate-keys.json",
                candidateKeySet.path("snapshotFingerprint").asText(), "candidate-proof.json",
                root);
        ObjectNode environmentNode = manifest.putObject("environment");
        environmentNode.putObject("attestation")
                .put("file", "environment.json")
                .put("fileFingerprint", sha256(bytes(root.resolve("environment.json"))))
                .put("reference", environment.path("environmentRef").textValue())
                .put("revision", environment.path("attestationRevision").longValue());
        policy(environmentNode.putObject("policy"), "policy:environment",
                "ENVIRONMENT_AUTHORITY", "issuer:deployment-control-plane",
                "environment-keys.json",
                environmentKeySet.path("snapshotFingerprint").asText(),
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
        policy.put("maximumProofTtlSeconds", 1800);
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

    private static ObjectNode keySet(KeyPair pair, String keyId, String provider)
            throws Exception {
        Instant generatedAt = NOW.minusSeconds(1800);
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        material.put("provider", provider);
        material.put("generatedAt", generatedAt.toString());
        material.put("expiresAt", NOW.plusSeconds(3600).toString());
        material.put("activeKeyId", keyId);
        material.put("policyCompleteness", "COMPLETE");
        material.putArray("keys").addObject()
                .put("keyId", keyId)
                .put("algorithm", "Ed25519")
                .put("encodedPublicKey", Base64.getEncoder().encodeToString(
                        pair.getPublic().getEncoded()))
                .put("createdAt", NOW.minusSeconds(3600).toString())
                .put("notBefore", NOW.minusSeconds(3600).toString())
                .putNull("notAfter")
                .put("state", "ACTIVE")
                .put("providerKeyVersion", "v1");
        ArrayNode events = material.putArray("events");
        event(events, 1, keyId + "-created", keyId, "CREATED",
                NOW.minusSeconds(3600));
        event(events, 2, keyId + "-activated", keyId, "ACTIVATED", generatedAt);
        String snapshotFingerprint = EvidenceVerificationSupport.sha256(material);
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
            Instant effectiveAt) {
        events.addObject()
                .put("sequence", sequence)
                .put("eventId", eventId)
                .put("keyId", keyId)
                .put("type", type)
                .put("occurredAt", NOW.minusSeconds(1800).toString())
                .put("effectiveAt", effectiveAt.toString())
                .putNull("revocationMode")
                .putNull("invalidFrom")
                .put("reasonCode", "KEY_LIFECYCLE");
    }

    private static ObjectNode candidateAttestation() {
        ObjectNode candidate = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .CANDIDATE_ATTESTATION_SCHEMA_VERSION)
                .put("candidateRef", "candidate:capability-studio:2026-01")
                .put("attestationRevision", 1)
                .put("role", "CANDIDATE_AUTHORITY")
                .put("buildRef", "build:capability-studio")
                .put("revision", "rev-2")
                .put("sourceCommit", "abcdef1234567")
                .put("sourceTreeStatus", "CLEAN")
                .put("artifactDigest", fingerprint('5'))
                .put("executionIntentFingerprint", fingerprint('4'))
                .put("scope", SCOPE)
                .put("issuer", "issuer:candidate-authority")
                .put("issuedAt", "2026-01-01T00:00:00Z")
                .put("expiresAt", "2026-01-01T00:30:00Z");
        reference(candidate, "baselineRef", "baseline:capability-studio:v2", '1');
        reference(candidate, "demoPackRef", "demo-pack:capability-studio:v2", '2');
        return candidate;
    }

    private static ObjectNode environmentAttestation(
            ObjectNode stage, ObjectNode candidate, String candidateRaw) {
        ObjectNode environment = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .ENVIRONMENT_ATTESTATION_SCHEMA_VERSION)
                .put("environmentRef", stage.path("environmentAttestation")
                        .path("exactRef").textValue())
                .put("attestationRevision", 1)
                .put("role", "ENVIRONMENT_AUTHORITY")
                .put("executionLeaseId", LEASE)
                .put("environmentFingerprint", stage.path("environmentAttestation")
                        .path("environmentFingerprint").textValue())
                .put("targetProfile", stage.path("environmentAttestation")
                        .path("profile").textValue())
                .put("scope", SCOPE)
                .put("region", "region:sg1")
                .put("runtimeIdentity", IDENTITY)
                .put("networkPolicy", stage.path("deploymentEgressObservation")
                        .path("networkPolicyRef").textValue())
                .put("logicalClock", "2026-01-01T00:00:00Z")
                .put("issuer", stage.path("environmentAttestation").path("issuer").textValue())
                .put("issuedAt", "2026-01-01T00:00:00Z")
                .put("expiresAt", "2026-01-01T00:30:00Z");
        environment.putObject("candidateAttestation")
                .put("candidateRef", candidate.path("candidateRef").textValue())
                .put("attestationRevision", 1)
                .put("fingerprint", candidateRaw);
        reference(environment, "featureFlagsRef", "feature-flags:capability-studio:v1", '6');
        environment.putObject("admissionWindow")
                .put("from", "2026-01-01T00:00:00Z")
                .put("through", "2026-01-01T00:30:00Z");
        environment.putArray("trustedTargetIdentities").add(IDENTITY);
        return environment;
    }

    private static ObjectNode targetBinding(
            ObjectNode stage,
            ObjectNode candidate,
            String candidateRaw,
            ObjectNode environment,
            String environmentRaw) {
        ObjectNode target = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .TARGET_BINDING_SCHEMA_VERSION)
                .put("resultId", stage.path("resultId").textValue())
                .put("resultRevision", stage.path("revision").longValue())
                .put("contractId", stage.path("contractId").textValue())
                .put("contractRevision", stage.path("contractRevision").textValue())
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
            ObjectNode environment,
            EnvironmentCoordinate coordinate,
            CandidateCoordinate candidateCoordinate) {
        JsonNode candidate = environment.path("candidateAttestation");
        CandidateCoordinate boundCandidate = new CandidateCoordinate(
                candidate.path("candidateRef").textValue(),
                candidate.path("attestationRevision").longValue(),
                candidate.path("fingerprint").textValue());
        Set<String> identities = new HashSet<>();
        environment.path("trustedTargetIdentities").forEach(
                identity -> identities.add(identity.textValue()));
        JsonNode window = environment.path("admissionWindow");
        return new EnvironmentAttestationFacts(coordinate,
                environment.path("executionLeaseId").textValue(), boundCandidate,
                environment.path("environmentFingerprint").textValue(),
                environment.path("targetProfile").textValue(),
                environment.path("scope").textValue(),
                environment.path("region").textValue(),
                environment.path("runtimeIdentity").textValue(),
                environment.path("networkPolicy").textValue(),
                exactReference(environment.path("featureFlagsRef")),
                Instant.parse(environment.path("logicalClock").textValue()),
                new AdmissionWindow(Instant.parse(window.path("from").textValue()),
                        Instant.parse(window.path("through").textValue())),
                identities, environment.path("role").textValue(),
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

    private static void refreshClosure(ObjectNode stage) {
        String closure = CapabilityStudioStageAcceptanceResultV2Verifier
                .closureFingerprint(stage);
        stage.put("evidenceClosureFingerprint", closure);
        stage.path("signoffs").forEach(signoff -> ((ObjectNode) signoff)
                .put("evidenceClosureFingerprint", closure));
    }
}
