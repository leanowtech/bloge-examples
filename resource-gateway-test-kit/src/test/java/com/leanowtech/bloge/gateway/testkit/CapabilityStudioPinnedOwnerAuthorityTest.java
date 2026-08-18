package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AcceptanceContext;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerSignoff;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.SignoffDecision;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioPinnedOwnerAuthorityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ROLE = "CORRECTNESS_OWNER";
    private static final String ACTOR = "owner:alice";
    private static final String ISSUER = "owner-signing-authority";
    private static final String SCOPE = "capability-studio";
    private static final String CANDIDATE = fingerprint('a');
    private static final String INTENT = fingerprint('b');
    private static final String ENVIRONMENT = fingerprint('c');
    private static final String CLOSURE = fingerprint('d');

    @Test
    void verifiesPinnedApprovedOwnerSignature() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedOwnerAuthority authority = authority(fixture, Duration.ofMinutes(30));
        OwnerSignoff signoff = signoff();
        ResolvedEvidence signature = signedSignature(fixture, signoff, context(), rawSignature());

        AuthorityDecision decision = authority.verify(signoff, signature, context());

        assertThat(decision.status()).isEqualTo(AuthorityDecision.Decision.VERIFIED);
        assertThat(decision.reasonCode()).endsWith(".VERIFIED");
        assertThat(authority.toString()).doesNotContain(
                ACTOR, signature.coordinate().exactRef(), signature.signature(),
                fixture.ownerKey().encodedPublicKey());
        String canonical = CapabilityStudioPinnedOwnerAuthority.canonicalMessage(
                signoff, signature, context(), fixture.keySet().snapshotFingerprint());
        assertThat(canonical)
                .contains("RG.CAPABILITY_STUDIO.OWNER_SIGNATURE_V1", "SAR-owner-test",
                        "contract:stage-acceptance", ROLE, ACTOR, ISSUER, SCOPE, CLOSURE)
                .doesNotContain(signature.materialFingerprint(), signature.signature());
        assertThat(CapabilityStudioPinnedOwnerAuthority.canonicalMessage(
                signoff, signature, context(), fixture.keySet().snapshotFingerprint()))
                .isEqualTo(canonical);
        assertThat(CapabilityStudioPinnedOwnerAuthority.canonicalFingerprint(
                signoff, signature, context(), fixture.keySet().snapshotFingerprint()))
                .isEqualTo("sha256:" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(canonical.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsUnknownRoleActorIssuerScopeAndNonApprovedDecision() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedOwnerAuthority authority = authority(fixture, Duration.ofMinutes(30));
        ResolvedEvidence valid = signedSignature(fixture, signoff(), context(), rawSignature());

        assertRejected(authority, new OwnerSignoff("QA_OWNER", ACTOR, SignoffDecision.APPROVED,
                signoff().signedAt(), coordinate(), CLOSURE), valid, context(),
                "OWNER_ROLE_UNKNOWN");
        assertRejected(authority, new OwnerSignoff(ROLE, "owner:bob", SignoffDecision.APPROVED,
                signoff().signedAt(), coordinate(), CLOSURE), valid, context(),
                "OWNER_ACTOR_NOT_ALLOWED");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                "other-issuer", SCOPE, valid.candidateArtifactFingerprint(),
                valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), valid.evidenceClosureFingerprint(),
                valid.keyId(), valid.algorithm(), valid.materialFingerprint(), valid.signedAt(),
                valid.expiresAt(), valid.signature()), context(),
                "OWNER_SIGNATURE_ISSUER_SCOPE_MISMATCH");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                ISSUER, "other-scope", valid.candidateArtifactFingerprint(),
                valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), valid.evidenceClosureFingerprint(),
                valid.keyId(), valid.algorithm(), valid.materialFingerprint(), valid.signedAt(),
                valid.expiresAt(), valid.signature()), context(),
                "OWNER_SIGNATURE_ISSUER_SCOPE_MISMATCH");
        assertRejected(authority, new OwnerSignoff(ROLE, ACTOR, SignoffDecision.REJECTED,
                signoff().signedAt(), coordinate(), CLOSURE), valid, context(),
                "OWNER_DECISION_NOT_APPROVED");
    }

    @Test
    void rejectsWrongKindCoordinateClosureCandidateAndContextWindow() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedOwnerAuthority authority = authority(fixture, Duration.ofMinutes(30));
        ResolvedEvidence valid = signedSignature(fixture, signoff(), context(), rawSignature());

        assertRejected(authority, signoff(), copy(valid, valid.coordinate(),
                EvidenceKind.ACCEPTANCE_EVIDENCE, ISSUER, SCOPE,
                valid.candidateArtifactFingerprint(), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), valid.observedFrom(), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), valid.keyId(), valid.algorithm(),
                valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(), valid.signature()),
                context(), "OWNER_SIGNATURE_KIND_MISMATCH");
        OwnerSignoff wrongCoordinate = new OwnerSignoff(ROLE, ACTOR, SignoffDecision.APPROVED,
                signoff().signedAt(), new EvidenceCoordinate(
                "signature://capability-studio/other-owner", fingerprint('2')), CLOSURE);
        assertRejected(authority, wrongCoordinate, valid, context(),
                "OWNER_SIGNATURE_COORDINATE_MISMATCH");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(),
                valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), fingerprint('e'), valid.keyId(),
                valid.algorithm(), valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(),
                valid.signature()), context(), "OWNER_SIGNATURE_CLOSURE_MISMATCH");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                ISSUER, SCOPE, fingerprint('e'), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), valid.observedFrom(), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), valid.keyId(), valid.algorithm(),
                valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(), valid.signature()),
                context(), "OWNER_SIGNATURE_CONTEXT_BINDING_MISMATCH");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(),
                valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom().plusSeconds(1), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), valid.keyId(), valid.algorithm(),
                valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(), valid.signature()),
                context(), "OWNER_SIGNATURE_CONTEXT_WINDOW_MISMATCH");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(),
                valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), null, valid.keyId(),
                valid.algorithm(), valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(),
                valid.signature()), context(), "OWNER_SIGNATURE_CONTEXT_FACTS_INCOMPLETE");
    }

    @Test
    void rejectsIncompleteUnknownOrDriftingCryptography() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedOwnerAuthority authority = authority(fixture, Duration.ofMinutes(30));
        ResolvedEvidence valid = signedSignature(fixture, signoff(), context(), rawSignature());

        assertRejected(authority, signoff(), rawSignature(), context(),
                "OWNER_SIGNATURE_FACTS_INCOMPLETE");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(),
                valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), valid.evidenceClosureFingerprint(),
                "unknown-key", valid.algorithm(), valid.materialFingerprint(), valid.signedAt(),
                valid.expiresAt(), valid.signature()), context(),
                "OWNER_KEY_NOT_IN_PINNED_SET");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(),
                valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), valid.evidenceClosureFingerprint(),
                valid.keyId(), "RSA", valid.materialFingerprint(), valid.signedAt(),
                valid.expiresAt(), valid.signature()), context(),
                "OWNER_SIGNATURE_ALGORITHM_REJECTED");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(),
                valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), valid.evidenceClosureFingerprint(),
                valid.keyId(), valid.algorithm(), fingerprint('f'), valid.signedAt(),
                valid.expiresAt(), valid.signature()), context(),
                "OWNER_SIGNATURE_MATERIAL_FINGERPRINT_MISMATCH");
        assertRejected(authority, signoff(), copy(valid, valid.coordinate(), valid.evidenceKind(),
                ISSUER, SCOPE, valid.candidateArtifactFingerprint(),
                valid.candidateIntentFingerprint(), valid.environmentFingerprint(),
                valid.observedFrom(), valid.observedThrough(), valid.evidenceClosureFingerprint(),
                valid.keyId(), valid.algorithm(), valid.materialFingerprint(), valid.signedAt(),
                valid.expiresAt(), "bad-signature"), context(), "OWNER_SIGNATURE_INVALID");
    }

    @Test
    void rejectsExpiredOverlongAndSignoffTimeDrift() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedOwnerAuthority authority = authority(fixture, Duration.ofSeconds(30));
        ResolvedEvidence expired = signedSignature(fixture, signoff(), context(),
                copy(rawSignature(), coordinate(), EvidenceKind.OWNER_SIGNATURE, ISSUER, SCOPE,
                        CANDIDATE, INTENT, ENVIRONMENT, context().executionStartedAt(),
                        context().evidenceCompletedAt(), CLOSURE, "owner-key", "Ed25519", null,
                        signoff().signedAt(), NOW.minusSeconds(1), null));
        ResolvedEvidence overlong = signedSignature(fixture, signoff(), context(), rawSignature());

        assertRejected(authority, signoff(), expired, context(), "OWNER_SIGNATURE_EXPIRED");
        assertRejected(authority, signoff(), overlong, context(), "OWNER_SIGNATURE_TTL_EXCEEDED");
        OwnerSignoff drifted = new OwnerSignoff(ROLE, ACTOR, SignoffDecision.APPROVED,
                signoff().signedAt().minusSeconds(1), coordinate(), CLOSURE);
        assertRejected(authority, drifted, overlong, context(),
                "OWNER_SIGNATURE_SIGNED_AT_MISMATCH");
    }

    @Test
    void acceptsProspectiveRevocationButRejectsRetroactiveRevocation() throws Exception {
        Instant effective = NOW.minusSeconds(30);
        Fixture prospective = fixture(List.of(event(
                EvidenceVerificationKeySet.RevocationMode.PROSPECTIVE, effective, null)));
        CapabilityStudioPinnedOwnerAuthority prospectiveAuthority = authority(
                prospective, Duration.ofMinutes(30));
        ResolvedEvidence historical = signedSignature(
                prospective, signoff(), context(), rawSignature());

        assertThat(prospectiveAuthority.verify(signoff(), historical, context()).status())
                .isEqualTo(AuthorityDecision.Decision.VERIFIED);

        Fixture retroactive = fixture(List.of(event(
                EvidenceVerificationKeySet.RevocationMode.RETROACTIVE, effective,
                NOW.minusSeconds(90))));
        CapabilityStudioPinnedOwnerAuthority retroactiveAuthority = authority(
                retroactive, Duration.ofMinutes(30));
        ResolvedEvidence invalidated = signedSignature(
                retroactive, signoff(), context(), rawSignature());
        assertRejected(retroactiveAuthority, signoff(), invalidated, context(),
                "OWNER_KEY_REVOKED_AT_SIGNING_TIME");
    }

    @Test
    void rejectsEmptyDuplicateIncompleteAndUnpinnedConfiguration() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedOwnerAuthority.TrustedOwnerRole trusted = trusted(fixture);

        assertThatThrownBy(() -> new CapabilityStudioPinnedOwnerAuthority(CLOCK, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioPinnedOwnerAuthority(
                CLOCK, List.of(trusted, trusted)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioPinnedOwnerAuthority.TrustedOwnerRole(
                ROLE, Set.of(), ISSUER, SCOPE, fixture.keySet().snapshotFingerprint(),
                fixture.keySet(), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioPinnedOwnerAuthority(CLOCK, List.of(
                new CapabilityStudioPinnedOwnerAuthority.TrustedOwnerRole(
                        ROLE, Set.of(ACTOR), ISSUER, SCOPE, fingerprint('f'), fixture.keySet(),
                        Duration.ofMinutes(30)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reverifiesPinnedKeySetOnEveryCall() throws Exception {
        Fixture fixture = fixture(List.of());
        MutableClock clock = new MutableClock(NOW);
        CapabilityStudioPinnedOwnerAuthority authority = new CapabilityStudioPinnedOwnerAuthority(
                clock, List.of(trusted(fixture)));
        ResolvedEvidence signature = signedSignature(fixture, signoff(), context(), rawSignature());

        clock.advance(Duration.ofDays(2));

        assertRejected(authority, signoff(), signature, context(), "OWNER_KEY_SET_NOT_TRUSTED");
    }

    @Test
    void malformedCryptographyReturnsRedactedStableDecision() throws Exception {
        Fixture fixture = fixture(List.of());
        CapabilityStudioPinnedOwnerAuthority authority = authority(fixture, Duration.ofMinutes(30));
        ResolvedEvidence valid = signedSignature(fixture, signoff(), context(), rawSignature());
        String secret = "definitely-not-signature-or-payload";
        ResolvedEvidence malformed = copy(valid, valid.coordinate(), valid.evidenceKind(), ISSUER,
                SCOPE, valid.candidateArtifactFingerprint(), valid.candidateIntentFingerprint(),
                valid.environmentFingerprint(), valid.observedFrom(), valid.observedThrough(),
                valid.evidenceClosureFingerprint(), valid.keyId(), valid.algorithm(),
                valid.materialFingerprint(), valid.signedAt(), valid.expiresAt(), secret);

        AuthorityDecision decision = authority.verify(signoff(), malformed, context());

        assertThat(decision.status()).isEqualTo(AuthorityDecision.Decision.REJECTED);
        assertThat(decision.reasonCode()).endsWith(".OWNER_SIGNATURE_INVALID");
        assertThat(decision.toString()).doesNotContain(
                secret, ACTOR, coordinate().exactRef(), fixture.ownerKey().encodedPublicKey());
    }

    private static CapabilityStudioPinnedOwnerAuthority authority(
            Fixture fixture, Duration ttl) {
        return new CapabilityStudioPinnedOwnerAuthority(CLOCK, List.of(
                new CapabilityStudioPinnedOwnerAuthority.TrustedOwnerRole(
                        ROLE, Set.of(ACTOR), ISSUER, SCOPE, fixture.keySet().snapshotFingerprint(),
                        fixture.keySet(), ttl)));
    }

    private static CapabilityStudioPinnedOwnerAuthority.TrustedOwnerRole trusted(Fixture fixture) {
        return new CapabilityStudioPinnedOwnerAuthority.TrustedOwnerRole(
                ROLE, Set.of(ACTOR), ISSUER, SCOPE, fixture.keySet().snapshotFingerprint(),
                fixture.keySet(), Duration.ofMinutes(30));
    }

    private static void assertRejected(
            CapabilityStudioPinnedOwnerAuthority authority,
            OwnerSignoff signoff,
            ResolvedEvidence signature,
            AcceptanceContext context,
            String suffix) {
        AuthorityDecision decision = authority.verify(signoff, signature, context);
        assertThat(decision.status()).isEqualTo(AuthorityDecision.Decision.REJECTED);
        assertThat(decision.reasonCode()).endsWith("." + suffix);
    }

    private static OwnerSignoff signoff() {
        return new OwnerSignoff(ROLE, ACTOR, SignoffDecision.APPROVED, NOW.minusSeconds(60),
                coordinate(), CLOSURE);
    }

    private static ResolvedEvidence rawSignature() {
        return new ResolvedEvidence(coordinate(), EvidenceKind.OWNER_SIGNATURE, ISSUER, SCOPE,
                CANDIDATE, INTENT, ENVIRONMENT, NOW.minusSeconds(600), NOW.minusSeconds(120),
                CLOSURE, "owner-key", "Ed25519", null, NOW.minusSeconds(60),
                NOW.plusSeconds(600), null);
    }

    private static AcceptanceContext context() {
        return new AcceptanceContext("SAR-owner-test", 2, "contract:stage-acceptance", "2026-01",
                CANDIDATE, INTENT, ENVIRONMENT, NOW.minusSeconds(600), NOW.minusSeconds(120), NOW,
                CLOSURE, "staging", "environment-scope", "environment-authority");
    }

    private static EvidenceCoordinate coordinate() {
        return new EvidenceCoordinate("signature://capability-studio/correctness-owner",
                fingerprint('1'));
    }

    private static ResolvedEvidence signedSignature(
            Fixture fixture, OwnerSignoff signoff, AcceptanceContext context,
            ResolvedEvidence raw) throws Exception {
        String material = CapabilityStudioPinnedOwnerAuthority.canonicalFingerprint(
                signoff, raw, context, fixture.keySet().snapshotFingerprint());
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(fixture.ownerKeyPair().getPrivate());
        signer.update(material.getBytes(StandardCharsets.UTF_8));
        return copy(raw, raw.coordinate(), raw.evidenceKind(), raw.issuerRef(), raw.scope(),
                raw.candidateArtifactFingerprint(), raw.candidateIntentFingerprint(),
                raw.environmentFingerprint(), raw.observedFrom(), raw.observedThrough(),
                raw.evidenceClosureFingerprint(), raw.keyId(), raw.algorithm(), material,
                raw.signedAt(), raw.expiresAt(), Base64.getEncoder().encodeToString(signer.sign()));
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
        KeyPair ownerPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        boolean revoked = !additionalEvents.isEmpty();
        KeyPair snapshotPair = revoked
                ? KeyPairGenerator.getInstance("Ed25519").generateKeyPair() : ownerPair;
        Instant createdAt = NOW.minusSeconds(3600);
        Instant generatedAt = revoked ? NOW.minusSeconds(10) : NOW.minusSeconds(120);
        String snapshotKeyId = revoked ? "snapshot-key" : "owner-key";
        ArrayNode keys = JSON.createArrayNode();
        keys.add(keyPolicy("owner-key", ownerPair,
                revoked ? EvidenceVerificationKeySet.KeyState.REVOKED
                        : EvidenceVerificationKeySet.KeyState.ACTIVE, createdAt));
        if (revoked) {
            keys.add(keyPolicy(snapshotKeyId, snapshotPair,
                    EvidenceVerificationKeySet.KeyState.ACTIVE, generatedAt));
        }
        ArrayNode events = JSON.createArrayNode();
        long sequence = 1;
        events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence++,
                "created-owner", "owner-key", EvidenceVerificationKeySet.EventType.CREATED,
                createdAt, createdAt, null, null, "KEY_CREATED")));
        events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence++,
                "activated-owner", "owner-key", EvidenceVerificationKeySet.EventType.ACTIVATED,
                createdAt, createdAt, null, null, "KEY_ACTIVATED")));
        for (EvidenceVerificationKeySet.LifecycleEvent event : additionalEvents) {
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence++,
                    event.eventId(), "owner-key", event.type(), event.occurredAt(),
                    event.effectiveAt(), event.revocationMode(), event.invalidFrom(),
                    event.reasonCode())));
        }
        if (revoked) {
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence++,
                    "created-snapshot", snapshotKeyId, EvidenceVerificationKeySet.EventType.CREATED,
                    generatedAt, generatedAt, null, null, "KEY_CREATED")));
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(sequence,
                    "activated-snapshot", snapshotKeyId,
                    EvidenceVerificationKeySet.EventType.ACTIVATED, generatedAt, generatedAt,
                    null, null, "KEY_ACTIVATED")));
        }
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        material.put("provider", "capability-studio-owner-test");
        material.put("generatedAt", generatedAt.toString());
        material.put("expiresAt", NOW.plusSeconds(86_400).toString());
        material.put("activeKeyId", snapshotKeyId);
        material.put("policyCompleteness", "COMPLETE");
        material.set("keys", keys);
        material.set("events", events);
        String snapshotFingerprint = EvidenceVerificationSupport.sha256(material);
        Signature attestation = Signature.getInstance("Ed25519");
        attestation.initSign(snapshotPair.getPrivate());
        attestation.update(snapshotFingerprint.getBytes(StandardCharsets.UTF_8));
        ObjectNode payload = material.deepCopy();
        payload.put("snapshotFingerprint", snapshotFingerprint);
        ObjectNode seal = payload.putObject("attestation");
        seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", snapshotFingerprint);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", snapshotKeyId);
        seal.put("signedAt", generatedAt.plusSeconds(1).toString());
        seal.put("signature", Base64.getEncoder().encodeToString(attestation.sign()));
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("payloadKind", "EVIDENCE_VERIFICATION_KEY_SET");
        envelope.put("payloadSchemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        envelope.set("payload", payload);
        return new Fixture(ownerPair, EvidenceVerificationKeySet.fromEnvelope(envelope));
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
            EvidenceVerificationKeySet.RevocationMode mode,
            Instant effectiveAt,
            Instant invalidFrom) {
        return new EvidenceVerificationKeySet.LifecycleEvent(3, "owner-revocation-" + mode,
                "owner-key", EvidenceVerificationKeySet.EventType.REVOKED, effectiveAt,
                effectiveAt, mode, invalidFrom, "KEY_REVOKED");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(KeyPair ownerKeyPair, EvidenceVerificationKeySet keySet) {
        private EvidenceVerificationKey ownerKey() {
            EvidenceVerificationKeySet.KeyPolicy policy = keySet.keys().stream()
                    .filter(value -> value.keyId().equals("owner-key"))
                    .findFirst().orElseThrow();
            return new EvidenceVerificationKey(TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                    policy.keyId(), policy.algorithm(), policy.encodedPublicKey(), policy.createdAt(),
                    policy.state().name(), keySet.provider());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
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
            return instant;
        }
    }
}
