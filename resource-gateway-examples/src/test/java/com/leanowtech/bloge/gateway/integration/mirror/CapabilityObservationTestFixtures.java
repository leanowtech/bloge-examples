package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

final class CapabilityObservationTestFixtures {
    static final String ISSUER = "support-observation-producer";

    private CapabilityObservationTestFixtures() {
    }

    static CapabilitySnapshot.Scope scope(String organization) {
        return new CapabilitySnapshot.Scope(
                "tenant-a", organization, "support", "test", "sg");
    }

    static IntegrationRequestContext identity(String organization) {
        return new IntegrationRequestContext(
                "tenant-a", organization, "support", "test", "sg",
                "SERVICE", "observation-producer", "",
                CapabilityObservationAdmissionService.AUTHORIZED_PURPOSE,
                "corr-observation", Set.of("mirror-producers"), "CONFIDENTIAL", "");
    }

    static CapabilitySnapshot capability(
            ObjectMapper mapper, CapabilitySnapshot.Scope scope) {
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                mapper, scope, "observation-plan", '7');
        return plan.capabilityClosure().stream()
                .filter(snapshot -> snapshot.capabilityId().equals(
                        plan.rootCapability().id()))
                .findFirst()
                .orElseThrow();
    }

    static CapabilityObservationEnvelope envelope(
            ObjectMapper mapper,
            InMemoryVisualEvidenceSigner signer,
            CapabilitySnapshot capability,
            String observationId) {
        Instant occurredAt = Instant.now().minusSeconds(2);
        CapabilityObservationEnvelope.PayloadReference request =
                payload("request-" + observationId, '1', occurredAt.plus(Duration.ofDays(30)));
        CapabilityObservationEnvelope.PayloadReference response =
                payload("response-" + observationId, '5', occurredAt.plus(Duration.ofDays(30)));
        CapabilityObservationEnvelope.DataUseGrant grant =
                new CapabilityObservationEnvelope.DataUseGrant(
                        ref("DATA_USE_GRANT", "grant-support", 1, '9'),
                        CapabilityObservationAdmissionService.AUTHORIZED_PURPOSE,
                        List.of(
                                CapabilityObservationEnvelope.AllowedUse.CORPUS_CURATION,
                                CapabilityObservationEnvelope.AllowedUse.EXACT_REPLAY),
                        occurredAt.minus(Duration.ofDays(1)),
                        occurredAt.plus(Duration.ofDays(20)));
        CapabilityObservationEnvelope.Material material =
                new CapabilityObservationEnvelope.Material(
                        observationId,
                        capability.scope(),
                        new MirrorArtifactRef(
                                "CAPABILITY",
                                capability.capabilityId(),
                                capability.revision(),
                                capability.fingerprint()),
                        occurredAt,
                        new CapabilityObservationEnvelope.TraceCoordinates(
                                "trace-" + observationId, "span-" + observationId, 1),
                        request,
                        response,
                        null,
                        42,
                        new CapabilityObservationEnvelope.StateCorrelation(
                                "support-case",
                                fingerprint('a'),
                                fingerprint('b'),
                                fingerprint('c')),
                        ref("OUTCOME_CORRELATION", "outcome-" + observationId, 1, 'd'),
                        grant);
        return new CapabilityObservationIntegrity(mapper).seal(
                material, signer, ISSUER);
    }

    static CapabilityObservationIntegrity.AuthorityKey authorityKey(
            CapabilityObservationEnvelope envelope,
            VisualEvidenceSigner signer,
            CapabilityObservationIntegrity.KeyState state) {
        VisualEvidenceSigner.VerificationKey key =
                signer.key(envelope.seal().keyId()).orElseThrow();
        return new CapabilityObservationIntegrity.AuthorityKey(
                ref("OBSERVATION_AUTHORITY_KEY", key.keyId(), 1, 'e'),
                key.algorithm(),
                key.encodedPublicKey(),
                ISSUER,
                key.createdAt().minusSeconds(1),
                key.createdAt().plus(Duration.ofDays(365)),
                state);
    }

    static CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy policy(
            CapabilityObservationEnvelope envelope,
            CapabilityObservationIntegrity.AuthorityKey authorityKey) {
        return new CapabilityObservationAdmissionPolicyProvider.AdmissionPolicy(
                envelope.material().scope(),
                envelope.material().capabilityRef(),
                ref("OBSERVATION_ADMISSION_POLICY", "support-policy", 3, 'f'),
                envelope.material().dataUseGrant().grantRef(),
                authorityKey,
                Set.of(CapabilityObservationEnvelope.Classification.CONFIDENTIAL),
                Set.of("sg"),
                Set.of(
                        CapabilityObservationEnvelope.AllowedUse.CORPUS_CURATION,
                        CapabilityObservationEnvelope.AllowedUse.EXACT_REPLAY),
                Duration.ofDays(7),
                Duration.ofMinutes(1),
                1024 * 1024,
                Duration.ofDays(1));
    }

    static CapabilityObservationEnvelope.PayloadReference payload(
            String id, char material, Instant retentionUntil) {
        return new CapabilityObservationEnvelope.PayloadReference(
                ref("SANITIZED_PAYLOAD", id, 1, material),
                ref("PAYLOAD_SANITIZATION_PROOF", id + "-proof", 1,
                        (char) (material + 1)),
                ref("JSON_SCHEMA", id + "-schema", 2, (char) (material + 2)),
                512,
                "application/json",
                CapabilityObservationEnvelope.Classification.CONFIDENTIAL,
                "sg",
                retentionUntil);
    }

    static MirrorArtifactRef ref(
            String kind, String id, long revision, char material) {
        return new MirrorArtifactRef(kind, id, revision, fingerprint(material));
    }

    static String fingerprint(char material) {
        return "sha256:" + String.valueOf(material).repeat(64);
    }
}
