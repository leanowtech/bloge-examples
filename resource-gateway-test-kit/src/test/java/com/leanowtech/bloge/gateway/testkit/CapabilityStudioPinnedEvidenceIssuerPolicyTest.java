package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AcceptanceContext;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceReference;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioPinnedEvidenceIssuerPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISSUER = "business-evidence-authority";
    private static final String SCOPE = "customer-support";
    private static final String OTHER_ISSUER = "other-authority";
    private static final String CANDIDATE = fingerprint('a');
    private static final String INTENT = fingerprint('b');
    private static final String ENVIRONMENT = fingerprint('c');
    private static final String CLOSURE = fingerprint('d');

    @Test
    void acceptsHappyPathAndRedactsItsDescription() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedEvidenceIssuerPolicy policy = policy(fixture, Duration.ofMinutes(30));
        ResolvedEvidence evidence = signedEvidence(fixture, context(), rawEvidence());

        AuthorityDecision decision = policy.verify(reference(evidence), evidence, context());

        assertThat(decision.status()).isEqualTo(AuthorityDecision.Decision.VERIFIED);
        assertThat(decision.reasonCode()).endsWith(".VERIFIED");
        assertThat(policy.toString()).doesNotContain(
                fixture.key().encodedPublicKey(), evidence.signature(), evidence.coordinate().exactRef());
        assertThat(CapabilityStudioPinnedEvidenceIssuerPolicy.canonicalMessage(
                evidence, context(), fixture.keySet().snapshotFingerprint()))
                .contains("RG.CAPABILITY_STUDIO.EVIDENCE_PROOF_V1")
                .contains(evidence.coordinate().exactRef())
                .contains(context().evidenceClosureFingerprint());
    }

    @Test
    void rejectsIncompleteAndUnknownIssuerOrScope() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedEvidenceIssuerPolicy policy = policy(fixture, Duration.ofMinutes(30));

        assertRejected(policy, rawEvidence(), "EVIDENCE_SIGNATURE_FACTS_INCOMPLETE");
        assertRejected(policy, rawEvidence(OTHER_ISSUER, SCOPE),
                "EVIDENCE_ISSUER_OR_SCOPE_UNKNOWN");
        assertRejected(policy, rawEvidence(ISSUER, "other-scope"),
                "EVIDENCE_ISSUER_OR_SCOPE_UNKNOWN");
    }

    @Test
    void rejectsKindOutsideIssuerAllowList() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedEvidenceIssuerPolicy policy = new CapabilityStudioPinnedEvidenceIssuerPolicy(
                CLOCK, List.of(new CapabilityStudioPinnedEvidenceIssuerPolicy.TrustedIssuer(
                        ISSUER, SCOPE, Set.of(EvidenceKind.ENVIRONMENT_ATTESTATION),
                        fixture.keySet().snapshotFingerprint(), fixture.keySet(), Duration.ofMinutes(30))));

        assertRejected(policy, rawEvidence(), "EVIDENCE_KIND_NOT_ALLOWED");
    }

    @Test
    void rejectsIncompleteDuplicateAndPinnedDriftConfiguration() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedEvidenceIssuerPolicy.TrustedIssuer issuer = trusted(fixture);

        assertThatThrownBy(() -> new CapabilityStudioPinnedEvidenceIssuerPolicy(CLOCK, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioPinnedEvidenceIssuerPolicy(
                CLOCK, List.of(issuer, issuer)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioPinnedEvidenceIssuerPolicy(CLOCK, List.of(
                new CapabilityStudioPinnedEvidenceIssuerPolicy.TrustedIssuer(
                        ISSUER, SCOPE, Set.of(EvidenceKind.ACCEPTANCE_EVIDENCE),
                        fingerprint('f'), fixture.keySet(), Duration.ofMinutes(30)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownKeyAlgorithmMaterialAndSignatureDrift() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedEvidenceIssuerPolicy policy = policy(fixture, Duration.ofMinutes(30));
        ResolvedEvidence valid = signedEvidence(fixture, context(), rawEvidence());

        assertRejected(policy, copy(valid, valid.coordinate(), EvidenceKind.ACCEPTANCE_EVIDENCE,
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), valid.observedFrom(), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), "unknown-key", valid.algorithm(),
                valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(), valid.signature()),
                "EVIDENCE_KEY_NOT_IN_PINNED_SET");
        assertRejected(policy, copy(valid, valid.coordinate(), EvidenceKind.ACCEPTANCE_EVIDENCE,
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), valid.observedFrom(), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), valid.keyId(), "RSA", valid.materialFingerprint(),
                valid.signedAt(), valid.expiresAt(), valid.signature()),
                "EVIDENCE_SIGNATURE_ALGORITHM_REJECTED");
        assertRejected(policy, copy(valid, valid.coordinate(), EvidenceKind.ACCEPTANCE_EVIDENCE,
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), valid.observedFrom(), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), valid.keyId(), valid.algorithm(), fingerprint('e'),
                valid.signedAt(), valid.expiresAt(), valid.signature()),
                "EVIDENCE_MATERIAL_FINGERPRINT_MISMATCH");
        assertRejected(policy, copy(valid, valid.coordinate(), EvidenceKind.ACCEPTANCE_EVIDENCE,
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), valid.observedFrom(), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), valid.keyId(), valid.algorithm(),
                valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(), "bad-signature"),
                "EVIDENCE_SIGNATURE_INVALID");
    }

    @Test
    void rejectsExpiredAndOverlongProofs() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedEvidenceIssuerPolicy policy = policy(fixture, Duration.ofSeconds(30));
        ResolvedEvidence expired = signedEvidence(fixture, context(),
                rawEvidence(ISSUER, SCOPE, NOW.minusSeconds(120), NOW.minusSeconds(1)));
        ResolvedEvidence overlong = signedEvidence(fixture, context(),
                rawEvidence(ISSUER, SCOPE, NOW.minusSeconds(60), NOW.plusSeconds(600)));

        assertRejected(policy, expired, "EVIDENCE_EXPIRED");
        assertRejected(policy, overlong, "EVIDENCE_TTL_EXCEEDED");
    }

    @Test
    void rejectsContextAndCanonicalBindingDrift() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedEvidenceIssuerPolicy policy = policy(fixture, Duration.ofMinutes(30));
        AcceptanceContext context = context();
        ResolvedEvidence valid = signedEvidence(fixture, context, rawEvidence());

        assertRejected(policy, copy(valid, valid.coordinate(), valid.evidenceKind(), ISSUER, SCOPE,
                fingerprint('e'), valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), valid.evidenceClosureFingerprint(),
                valid.keyId(), valid.algorithm(), valid.materialFingerprint(), valid.signedAt(),
                valid.expiresAt(), valid.signature()), "EVIDENCE_CONTEXT_BINDING_MISMATCH");
        assertRejected(policy, copy(valid, valid.coordinate(), valid.evidenceKind(), ISSUER, SCOPE,
                valid.candidateArtifactFingerprint(), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), valid.observedFrom(),
                valid.observedThrough().minusSeconds(1), valid.evidenceClosureFingerprint(),
                valid.keyId(), valid.algorithm(), valid.materialFingerprint(), valid.signedAt(),
                valid.expiresAt(), valid.signature()), "EVIDENCE_MATERIAL_FINGERPRINT_MISMATCH");
        assertRejected(policy, copy(valid, valid.coordinate(), valid.evidenceKind(), ISSUER, SCOPE,
                valid.candidateArtifactFingerprint(), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), NOW.minusSeconds(10_000), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), valid.keyId(), valid.algorithm(),
                valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(), valid.signature()),
                "EVIDENCE_OBSERVED_WINDOW_INVALID");
        assertRejected(policy, copy(valid, valid.coordinate(), valid.evidenceKind(), ISSUER, SCOPE,
                null, valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), valid.evidenceClosureFingerprint(),
                valid.keyId(), valid.algorithm(), valid.materialFingerprint(), valid.signedAt(),
                valid.expiresAt(), valid.signature()), "EVIDENCE_CONTEXT_FACTS_INCOMPLETE");
    }

    @Test
    void acceptsProspectiveRevocationForHistoricalProof() throws Exception {
        Instant effective = NOW.minusSeconds(30);
        Fixture fixture = fixture(List.of(event(3, EvidenceVerificationKeySet.EventType.REVOKED,
                effective, EvidenceVerificationKeySet.RevocationMode.PROSPECTIVE, null)));
        CapabilityStudioPinnedEvidenceIssuerPolicy policy = policy(fixture, Duration.ofMinutes(30));
        ResolvedEvidence evidence = signedEvidence(fixture, context(), rawEvidence());

        assertThat(policy.verify(reference(evidence), evidence, context()).status())
                .isEqualTo(AuthorityDecision.Decision.VERIFIED);
    }

    @Test
    void rejectsRetroactiveRevocationForHistoricalProof() throws Exception {
        Instant effective = NOW.minusSeconds(30);
        Fixture fixture = fixture(List.of(event(3, EvidenceVerificationKeySet.EventType.REVOKED,
                effective, EvidenceVerificationKeySet.RevocationMode.RETROACTIVE,
                NOW.minusSeconds(90))));
        CapabilityStudioPinnedEvidenceIssuerPolicy policy = policy(fixture, Duration.ofMinutes(30));
        ResolvedEvidence evidence = signedEvidence(fixture, context(), rawEvidence());

        assertRejected(policy, evidence, "EVIDENCE_KEY_REVOKED_AT_SIGNING_TIME");
    }

    @Test
    void convertsMalformedCryptographyToStableRedactedRejection() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedEvidenceIssuerPolicy policy = policy(fixture, Duration.ofMinutes(30));
        ResolvedEvidence valid = signedEvidence(fixture, context(), rawEvidence());
        String secret = "definitely-not-a-public-key-or-payload";
        ResolvedEvidence malformed = copy(valid, valid.coordinate(), valid.evidenceKind(), ISSUER,
                SCOPE, valid.candidateArtifactFingerprint(), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), valid.observedFrom(), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), valid.keyId(), valid.algorithm(),
                valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(), secret);

        AuthorityDecision decision = policy.verify(reference(malformed), malformed, context());

        assertThat(decision.status()).isEqualTo(AuthorityDecision.Decision.REJECTED);
        assertThat(decision.reasonCode()).endsWith(".EVIDENCE_SIGNATURE_INVALID");
        assertThat(decision.toString()).doesNotContain(secret, fixture.key().encodedPublicKey());
    }

    private static CapabilityStudioPinnedEvidenceIssuerPolicy policy(
            Fixture fixture, Duration ttl) {
        return new CapabilityStudioPinnedEvidenceIssuerPolicy(CLOCK,
                List.of(new CapabilityStudioPinnedEvidenceIssuerPolicy.TrustedIssuer(
                        ISSUER, SCOPE, Set.of(EvidenceKind.ACCEPTANCE_EVIDENCE),
                        fixture.keySet().snapshotFingerprint(), fixture.keySet(), ttl)));
    }

    private static CapabilityStudioPinnedEvidenceIssuerPolicy.TrustedIssuer trusted(Fixture fixture) {
        return new CapabilityStudioPinnedEvidenceIssuerPolicy.TrustedIssuer(
                ISSUER, SCOPE, Set.of(EvidenceKind.ACCEPTANCE_EVIDENCE),
                fixture.keySet().snapshotFingerprint(), fixture.keySet(), Duration.ofMinutes(30));
    }

    private static void assertRejected(
            CapabilityStudioPinnedEvidenceIssuerPolicy policy,
            ResolvedEvidence evidence,
            String suffix) {
        AuthorityDecision decision = policy.verify(reference(evidence), evidence, context());
        assertThat(decision.status()).isEqualTo(AuthorityDecision.Decision.REJECTED);
        assertThat(decision.reasonCode()).endsWith("." + suffix);
    }

    private static EvidenceReference reference(ResolvedEvidence evidence) {
        return new EvidenceReference("evidence-1", evidence.coordinate());
    }

    private static ResolvedEvidence signedEvidence(
            Fixture fixture, AcceptanceContext context, ResolvedEvidence raw) throws Exception {
        String material = CapabilityStudioPinnedEvidenceIssuerPolicy.canonicalFingerprint(
                raw, context, fixture.keySet().snapshotFingerprint());
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(fixture.keyPair().getPrivate());
        signer.update(material.getBytes(StandardCharsets.UTF_8));
        return copy(raw, raw.coordinate(), raw.evidenceKind(), raw.issuerRef(), raw.scope(),
                raw.candidateArtifactFingerprint(), raw.candidateIntentFingerprint(),
                raw.environmentFingerprint(), raw.observedFrom(), raw.observedThrough(),
                raw.evidenceClosureFingerprint(), raw.keyId(), raw.algorithm(), material,
                raw.signedAt(), raw.expiresAt(), Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static ResolvedEvidence rawEvidence() {
        return rawEvidence(ISSUER, SCOPE, NOW.minusSeconds(60), NOW.plusSeconds(600));
    }

    private static ResolvedEvidence rawEvidence(String issuer, String scope) {
        return rawEvidence(issuer, scope, NOW.minusSeconds(60), NOW.plusSeconds(600));
    }

    private static ResolvedEvidence rawEvidence(
            String issuer, String scope, Instant signedAt, Instant expiresAt) {
        return new ResolvedEvidence(new EvidenceCoordinate(
                "evidence://capability-studio/evidence-1", fingerprint('1')),
                EvidenceKind.ACCEPTANCE_EVIDENCE, issuer, scope, CANDIDATE, INTENT, ENVIRONMENT,
                NOW.minusSeconds(500), NOW.minusSeconds(100), CLOSURE, "evidence-key", "Ed25519",
                null, signedAt, expiresAt, null);
    }

    private static AcceptanceContext context() {
        return new AcceptanceContext("SAR-policy-test", 1, "contract:acceptance", "1",
                CANDIDATE, INTENT, ENVIRONMENT, NOW.minusSeconds(500), NOW.minusSeconds(100), NOW,
                CLOSURE, "staging", SCOPE, ISSUER);
    }

    private static ResolvedEvidence copy(
            ResolvedEvidence source, EvidenceCoordinate coordinate, EvidenceKind kind,
            String issuer, String scope, String candidate, String intent, String environment,
            Instant observedFrom, Instant observedThrough, String closure, String keyId,
            String algorithm, String material, Instant signedAt, Instant expiresAt,
            String signature) {
        return new ResolvedEvidence(coordinate, kind, issuer, scope, candidate, intent, environment,
                observedFrom, observedThrough, closure, keyId, algorithm, material, signedAt,
                expiresAt, signature);
    }

    private static Fixture fixture(
            List<EvidenceVerificationKeySet.LifecycleEvent> additionalEvents) throws Exception {
        KeyPair evidencePair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        boolean revoked = !additionalEvents.isEmpty();
        KeyPair signingPair = revoked
                ? KeyPairGenerator.getInstance("Ed25519").generateKeyPair() : evidencePair;
        Instant createdAt = NOW.minusSeconds(3600);
        Instant generatedAt = revoked ? NOW.minusSeconds(10) : NOW.minusSeconds(120);
        String evidenceKeyId = "evidence-key";
        String signingKeyId = revoked ? "snapshot-key" : evidenceKeyId;
        ArrayNode keys = JSON.createArrayNode();
        keys.add(keyPolicy(evidenceKeyId, evidencePair,
                revoked ? EvidenceVerificationKeySet.KeyState.REVOKED
                        : EvidenceVerificationKeySet.KeyState.ACTIVE, createdAt));
        if (revoked) {
            keys.add(keyPolicy(signingKeyId, signingPair,
                    EvidenceVerificationKeySet.KeyState.ACTIVE, generatedAt));
        }
        ArrayNode events = JSON.createArrayNode();
        long sequence = 1;
        events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence++,
                "created-evidence", evidenceKeyId, EvidenceVerificationKeySet.EventType.CREATED,
                createdAt, createdAt, null, null, "KEY_CREATED")));
        events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence++,
                "activated-evidence", evidenceKeyId, EvidenceVerificationKeySet.EventType.ACTIVATED,
                createdAt, createdAt, null, null, "KEY_ACTIVATED")));
        for (EvidenceVerificationKeySet.LifecycleEvent event : additionalEvents) {
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence++,
                    event.eventId(), evidenceKeyId, event.type(), event.occurredAt(),
                    event.effectiveAt(), event.revocationMode(), event.invalidFrom(),
                    event.reasonCode())));
        }
        if (revoked) {
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence++,
                    "created-snapshot", signingKeyId, EvidenceVerificationKeySet.EventType.CREATED,
                    generatedAt, generatedAt, null, null, "KEY_CREATED")));
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence,
                    "activated-snapshot", signingKeyId, EvidenceVerificationKeySet.EventType.ACTIVATED,
                    generatedAt, generatedAt, null, null, "KEY_ACTIVATED")));
        }
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        material.put("provider", "capability-studio-test");
        material.put("generatedAt", generatedAt.toString());
        material.put("expiresAt", NOW.plusSeconds(86_400).toString());
        material.put("activeKeyId", signingKeyId);
        material.put("policyCompleteness", "COMPLETE");
        material.set("keys", keys);
        material.set("events", events);
        String snapshotFingerprint = EvidenceVerificationSupport.sha256(material);
        Signature attestation = Signature.getInstance("Ed25519");
        attestation.initSign(signingPair.getPrivate());
        attestation.update(snapshotFingerprint.getBytes(StandardCharsets.UTF_8));
        ObjectNode payload = material.deepCopy();
        payload.put("snapshotFingerprint", snapshotFingerprint);
        ObjectNode seal = payload.putObject("attestation");
        seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", snapshotFingerprint);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", signingKeyId);
        seal.put("signedAt", generatedAt.plusSeconds(1).toString());
        seal.put("signature", Base64.getEncoder().encodeToString(attestation.sign()));
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("payloadKind", "EVIDENCE_VERIFICATION_KEY_SET");
        envelope.put("payloadSchemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        envelope.set("payload", payload);
        return new Fixture(evidencePair, EvidenceVerificationKeySet.fromEnvelope(envelope));
    }

    private static ObjectNode keyPolicy(
            String keyId, KeyPair keyPair, EvidenceVerificationKeySet.KeyState state,
            Instant createdAt) {
        ObjectNode key = JSON.createObjectNode();
        key.put("keyId", keyId);
        key.put("algorithm", "Ed25519");
        key.put("encodedPublicKey", Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded()));
        key.put("createdAt", createdAt.toString());
        key.put("notBefore", createdAt.toString());
        key.putNull("notAfter");
        key.put("state", state.name());
        key.put("providerKeyVersion", "v1-" + keyId);
        return key;
    }

    private static ObjectNode eventJson(EvidenceVerificationKeySet.LifecycleEvent event) {
        ObjectNode value = JSON.createObjectNode();
        value.put("sequence", event.sequence());
        value.put("eventId", event.eventId());
        value.put("keyId", event.keyId());
        value.put("type", event.type().name());
        value.put("occurredAt", event.occurredAt().toString());
        value.put("effectiveAt", event.effectiveAt().toString());
        if (event.revocationMode() == null) {
            value.putNull("revocationMode");
        } else {
            value.put("revocationMode", event.revocationMode().name());
        }
        if (event.invalidFrom() == null) {
            value.putNull("invalidFrom");
        } else {
            value.put("invalidFrom", event.invalidFrom().toString());
        }
        value.put("reasonCode", event.reasonCode());
        return value;
    }

    private static EvidenceVerificationKeySet.LifecycleEvent event(
            long sequence, EvidenceVerificationKeySet.EventType type, Instant effectiveAt,
            EvidenceVerificationKeySet.RevocationMode mode, Instant invalidFrom) {
        return new EvidenceVerificationKeySet.LifecycleEvent(sequence, "revocation-" + mode,
                "evidence-key", type, effectiveAt, effectiveAt, mode, invalidFrom,
                "KEY_REVOKED");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(KeyPair keyPair, EvidenceVerificationKeySet keySet) {
        private EvidenceVerificationKey key() {
            EvidenceVerificationKeySet.KeyPolicy policy = keySet.keys().stream()
                    .filter(value -> value.keyId().equals("evidence-key"))
                    .findFirst().orElseThrow();
            return new EvidenceVerificationKey(TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                    policy.keyId(), policy.algorithm(), policy.encodedPublicKey(), policy.createdAt(),
                    policy.state().name(), keySet.provider());
        }
    }
}
