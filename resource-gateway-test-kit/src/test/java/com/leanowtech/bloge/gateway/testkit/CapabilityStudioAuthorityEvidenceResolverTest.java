package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioAuthorityEvidenceResolver.ArtifactRead;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioAuthorityEvidenceResolverTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINGERPRINT_A = "sha256:" + "a".repeat(64);
    private static final String FINGERPRINT_B = "sha256:" + "b".repeat(64);
    private static final String FINGERPRINT_C = "sha256:" + "c".repeat(64);
    private static final String FINGERPRINT_D = "sha256:" + "d".repeat(64);
    private static final String EXACT_REF = "evidence://store/run-001/check-01";
    private static final ResolutionRequest REQUEST = new ResolutionRequest(
            ReferenceKind.EVIDENCE, "check-01",
            new EvidenceCoordinate(EXACT_REF, FINGERPRINT_A));

    @Test
    void resolvesStrictPayloadFreeEnvelopeIntoTypedFacts() {
        var result = resolver(ArtifactRead.available(validEnvelope())).resolve(REQUEST);

        assertThat(result.status()).isEqualTo(ResolutionStatus.AVAILABLE);
        assertThat(result.evidence().coordinate()).isEqualTo(REQUEST.coordinate());
        assertThat(result.evidence().evidenceKind())
                .isEqualTo(EvidenceKind.ENVIRONMENT_ATTESTATION);
        assertThat(result.evidence().issuerRef()).isEqualTo("ci:acceptance-authority");
        assertThat(result.evidence().scope()).isEqualTo("environment:staging-sg");
        assertThat(result.evidence().candidateArtifactFingerprint()).isEqualTo(FINGERPRINT_B);
        assertThat(result.evidence().candidateIntentFingerprint()).isEqualTo(FINGERPRINT_C);
        assertThat(result.evidence().environmentFingerprint()).isEqualTo(FINGERPRINT_D);
        assertThat(result.evidence().evidenceClosureFingerprint()).isEqualTo(FINGERPRINT_A);
        assertThat(result.evidence().keyId()).isEqualTo("ci-key-2026-01");
        assertThat(result.evidence().algorithm()).isEqualTo("Ed25519");
        assertThat(result.evidence().signature()).isEqualTo("c2lnbmF0dXJl");
    }

    @Test
    void resolvesOwnerSignatureReferenceWithoutWeakeningIdentityCheck() {
        ResolutionRequest signatureRequest = new ResolutionRequest(
                ReferenceKind.SIGNATURE, "QA_OWNER", REQUEST.coordinate());
        ObjectNode envelope = validEnvelope();
        envelope.put("referenceKind", "SIGNATURE");
        envelope.put("referenceKey", "QA_OWNER");
        envelope.put("evidenceKind", "OWNER_SIGNATURE");

        var result = resolver(ArtifactRead.available(envelope)).resolve(signatureRequest);

        assertThat(result.status()).isEqualTo(ResolutionStatus.AVAILABLE);
        assertThat(result.evidence().evidenceKind()).isEqualTo(EvidenceKind.OWNER_SIGNATURE);
    }

    @Test
    void preservesDeterministicNotFoundAndTransientUnavailable() {
        assertThat(resolver(ArtifactRead.notFound()).resolve(REQUEST).status())
                .isEqualTo(ResolutionStatus.NOT_FOUND);
        assertThat(resolver(ArtifactRead.unavailable()).resolve(REQUEST).status())
                .isEqualTo(ResolutionStatus.UNAVAILABLE);
    }

    @Test
    void sourceExceptionNullReadAndNullRequestFailClosedAsUnavailable() {
        var throwing = new CapabilityStudioAuthorityEvidenceResolver(request -> {
            throw new IllegalStateException("backend unavailable with sensitive detail");
        });
        var nullRead = new CapabilityStudioAuthorityEvidenceResolver(request -> null);

        assertThat(throwing.resolve(REQUEST).status()).isEqualTo(ResolutionStatus.UNAVAILABLE);
        assertThat(nullRead.resolve(REQUEST).status()).isEqualTo(ResolutionStatus.UNAVAILABLE);
        assertThat(nullRead.resolve(null).status()).isEqualTo(ResolutionStatus.UNAVAILABLE);
    }

    @Test
    void rejectsMalformedSchemaAndUnexpectedPayloadAsNotFound() {
        ObjectNode malformed = validEnvelope();
        malformed.remove("seal");
        ObjectNode payloadBearing = validEnvelope();
        payloadBearing.putObject("payload").put("customerPhone", "secret");

        assertThat(resolver(ArtifactRead.available(malformed)).resolve(REQUEST).status())
                .isEqualTo(ResolutionStatus.NOT_FOUND);
        assertThat(resolver(ArtifactRead.available(payloadBearing)).resolve(REQUEST).status())
                .isEqualTo(ResolutionStatus.NOT_FOUND);
    }

    @Test
    void rejectsOversizedEnvelopeBeforeProjection() {
        ObjectNode envelope = validEnvelope();
        envelope.put("signaturePadding", "x".repeat(
                CapabilityStudioAuthorityEvidenceResolver.MAXIMUM_ENVELOPE_BYTES));

        assertThat(resolver(ArtifactRead.available(envelope)).resolve(REQUEST).status())
                .isEqualTo(ResolutionStatus.NOT_FOUND);
    }

    @Test
    void rejectsReferenceKindKeyCoordinateAndSchemaVersionDrift() {
        for (ObjectNode drifting : new ObjectNode[]{
                validEnvelope().put("referenceKind", "SIGNATURE"),
                validEnvelope().put("referenceKey", "check-02"),
                withCoordinate(validEnvelope(), "evidence://store/run-002/check-01", FINGERPRINT_A),
                withCoordinate(validEnvelope(), EXACT_REF, FINGERPRINT_B),
                validEnvelope().put("schemaVersion", "resource-gateway.capability-studio.unknown")
        }) {
            assertThat(resolver(ArtifactRead.available(drifting)).resolve(REQUEST).status())
                    .isEqualTo(ResolutionStatus.NOT_FOUND);
        }
    }

    @Test
    void rejectsInvalidObservationAndSealWindows() {
        ObjectNode observationReversed = validEnvelope();
        observationReversed.withObject("observationWindow")
                .put("from", "2026-01-01T00:02:00Z")
                .put("through", "2026-01-01T00:01:00Z");
        ObjectNode sealReversed = validEnvelope();
        sealReversed.withObject("seal")
                .put("signedAt", "2026-01-01T00:04:00Z")
                .put("expiresAt", "2026-01-01T00:03:00Z");
        ObjectNode invalidInstant = validEnvelope();
        invalidInstant.withObject("seal").put("signedAt", "not-an-instant");

        for (ObjectNode invalid : new ObjectNode[]{
                observationReversed, sealReversed, invalidInstant
        }) {
            assertThat(resolver(ArtifactRead.available(invalid)).resolve(REQUEST).status())
                    .isEqualTo(ResolutionStatus.NOT_FOUND);
        }
    }

    @Test
    void snapshotsEnvelopeOnInputAndOutput() {
        ObjectNode original = validEnvelope();
        ArtifactRead read = ArtifactRead.available(original);
        original.put("referenceKey", "mutated-input");
        ((ObjectNode) read.envelope()).put("referenceKey", "mutated-output");

        assertThat(new CapabilityStudioAuthorityEvidenceResolver(request -> read)
                .resolve(REQUEST).status()).isEqualTo(ResolutionStatus.AVAILABLE);
    }

    @Test
    void sourceReceivesTheExactTypedRequest() {
        AtomicReference<ResolutionRequest> observed = new AtomicReference<>();
        var resolver = new CapabilityStudioAuthorityEvidenceResolver(request -> {
            observed.set(request);
            return ArtifactRead.available(validEnvelope());
        });

        resolver.resolve(REQUEST);

        assertThat(observed.get()).isSameAs(REQUEST);
    }

    @Test
    void publicDescriptionsDoNotLeakEnvelopeCoordinatesOrSignature() {
        ArtifactRead read = ArtifactRead.available(validEnvelope());
        var result = resolver(read).resolve(REQUEST);

        assertThat(read.toString())
                .doesNotContain(EXACT_REF, "c2lnbmF0dXJl")
                .contains("envelope=REDACTED");
        assertThat(result.evidence().toString())
                .doesNotContain(EXACT_REF, "c2lnbmF0dXJl")
                .contains("authorityFacts=REDACTED");
    }

    @Test
    void artifactReadRejectsIncoherentStates() {
        assertThatThrownBy(() -> new ArtifactRead(null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ArtifactRead.available(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ArtifactRead(
                CapabilityStudioAuthorityEvidenceResolver.ArtifactStatus.NOT_FOUND,
                validEnvelope()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CapabilityStudioAuthorityEvidenceResolver resolver(ArtifactRead read) {
        return new CapabilityStudioAuthorityEvidenceResolver(request -> read);
    }

    private static ObjectNode validEnvelope() {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("schemaVersion", CapabilityStudioAuthorityEvidenceResolver.SCHEMA_VERSION);
        envelope.put("referenceKind", "EVIDENCE");
        envelope.put("referenceKey", "check-01");
        envelope.putObject("coordinate")
                .put("exactRef", EXACT_REF)
                .put("fingerprint", FINGERPRINT_A);
        envelope.put("evidenceKind", "ENVIRONMENT_ATTESTATION");
        envelope.put("issuerRef", "ci:acceptance-authority");
        envelope.put("scope", "environment:staging-sg");
        envelope.putObject("bindings")
                .put("candidateArtifactFingerprint", FINGERPRINT_B)
                .put("candidateIntentFingerprint", FINGERPRINT_C)
                .put("environmentFingerprint", FINGERPRINT_D)
                .put("evidenceClosureFingerprint", FINGERPRINT_A);
        envelope.putObject("observationWindow")
                .put("from", "2026-01-01T00:00:00Z")
                .put("through", "2026-01-01T00:01:00Z");
        envelope.putObject("seal")
                .put("keyId", "ci-key-2026-01")
                .put("algorithm", "Ed25519")
                .put("materialFingerprint", FINGERPRINT_B)
                .put("signedAt", "2026-01-01T00:02:00Z")
                .put("expiresAt", "2026-01-02T00:02:00Z")
                .put("signature", "c2lnbmF0dXJl");
        return envelope;
    }

    private static ObjectNode withCoordinate(
            ObjectNode envelope, String exactRef, String fingerprint) {
        envelope.withObject("coordinate")
                .put("exactRef", exactRef)
                .put("fingerprint", fingerprint);
        return envelope;
    }
}
