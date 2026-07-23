package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityCorpusVerifierTest {
    private final CapabilityCorpusVerifier verifier =
            new CapabilityCorpusVerifier();

    @Test
    void packagedFixtureVerifiesWithoutServerSpringOrPayloadAuthorities() {
        CapabilityCorpusCompatibilityFixture fixture =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();

        CapabilityCorpusVerifier.VerificationResult result =
                verifier.verify(fixture);

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.corpusId()).isEqualTo("support-refund-corpus");
        assertThat(result.revisionFingerprint())
                .isEqualTo(
                        "sha256:5823cbe123386aa74fec0946c31a99aac51860b08275891b5ec2a2c8c96703de");
        assertThat(result.publicationFingerprint())
                .isEqualTo(
                        "sha256:bf53f609cdd9379ee9539c971eecd80126bcc68654a074f266f3fbe1a167b2e3");
    }

    @Test
    void rejectsUnknownFieldsAndPayloadShapedAdditionsAtSchemaBoundary() {
        CapabilityCorpusCompatibilityFixture unknown =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        ((ObjectNode) unknown.reviewRequest()).put("comment", "unbounded");

        assertThat(verifier.verify(unknown))
                .extracting(
                        CapabilityCorpusVerifier.VerificationResult::outcome,
                        CapabilityCorpusVerifier.VerificationResult::reasonCode)
                .containsExactly(
                        CapabilityCorpusVerifier.Outcome.SCHEMA_INVALID,
                        "CORPUS_REVIEW_REQUEST_SCHEMA_INVALID");

        CapabilityCorpusCompatibilityFixture payload =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        ((ObjectNode) payload.revision()).put(
                "requestBody", "must-never-enter-control-plane");
        assertThat(verifier.verify(payload).reasonCode())
                .isEqualTo("CORPUS_REVISION_SCHEMA_INVALID");
    }

    @Test
    void rejectsCommandTamperingBeforeTrustingStoredArtifact() {
        CapabilityCorpusCompatibilityFixture fixture =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        ((ObjectNode) fixture.candidateRequest().at(
                "/sources/0/admissionRef"))
                .put("fingerprint", fingerprint('4'));

        assertThat(verifier.verify(fixture))
                .extracting(
                        CapabilityCorpusVerifier.VerificationResult::outcome,
                        CapabilityCorpusVerifier.VerificationResult::reasonCode)
                .containsExactly(
                        CapabilityCorpusVerifier.Outcome.INTEGRITY_INVALID,
                        "CORPUS_CANDIDATE_COMMAND_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsSelfConsistentlyResealedButFalseRiskStatistics() {
        CapabilityCorpusCompatibilityFixture fixture =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        ObjectNode revision = (ObjectNode) fixture.revision();
        ((ObjectNode) revision.path("riskSummary")).put("sampleCount", 2);
        reseal(revision, "revisionFingerprint");

        assertThat(verifier.verify(fixture))
                .extracting(
                        CapabilityCorpusVerifier.VerificationResult::outcome,
                        CapabilityCorpusVerifier.VerificationResult::reasonCode)
                .containsExactly(
                        CapabilityCorpusVerifier.Outcome.RISK_INVALID,
                        "CORPUS_RISK_STATISTICS_INVALID");
    }

    @Test
    void rejectsLocalScopeDriftAndExpiredPublicationSeparately() {
        CapabilityCorpusCompatibilityFixture drifted =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        CapabilityObservationScope wrongScope = new CapabilityObservationScope(
                drifted.expectedScope().tenantId(),
                "another-organization",
                drifted.expectedScope().projectId(),
                drifted.expectedScope().environmentId(),
                drifted.expectedScope().region());
        CapabilityCorpusCompatibilityFixture wrongExpectation =
                new CapabilityCorpusCompatibilityFixture(
                        drifted.reviewRequest(),
                        drifted.review(),
                        drifted.candidateRequest(),
                        drifted.revision(),
                        drifted.publishRequest(),
                        drifted.publication(),
                        wrongScope,
                        drifted.verificationTime());
        assertThat(verifier.verify(wrongExpectation).reasonCode())
                .isEqualTo("CORPUS_SCOPE_MISMATCH");

        CapabilityCorpusCompatibilityFixture source =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        CapabilityCorpusCompatibilityFixture expired =
                new CapabilityCorpusCompatibilityFixture(
                        source.reviewRequest(),
                        source.review(),
                        source.candidateRequest(),
                        source.revision(),
                        source.publishRequest(),
                        source.publication(),
                        source.expectedScope(),
                        Instant.parse("2030-02-01T00:00:00Z"));
        assertThat(verifier.verify(expired).reasonCode())
                .isEqualTo("CORPUS_PUBLICATION_WINDOW_INVALID");
    }

    @Test
    void acceptsCandidateAndPublicationAtTheSameTrustedClockTick() {
        CapabilityCorpusCompatibilityFixture fixture =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        ObjectNode publication = (ObjectNode) fixture.publication();
        publication.put(
                "publishedAt",
                fixture.revision().path("createdAt").asText());
        reseal(publication, "publicationFingerprint");

        assertThat(verifier.verify(fixture).verified()).isTrue();
    }

    @Test
    void returnsDetachedCopiesOfEveryMutableFixtureComponent() {
        CapabilityCorpusCompatibilityFixture first =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        ((ObjectNode) first.review()).put("mutated", true);
        ((ObjectNode) first.revision()).put("mutated", true);
        ((ObjectNode) first.publication()).put("mutated", true);

        CapabilityCorpusCompatibilityFixture second =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        assertThat(second.review().has("mutated")).isFalse();
        assertThat(second.revision().has("mutated")).isFalse();
        assertThat(second.publication().has("mutated")).isFalse();
        assertThat(verifier.verify(second).verified()).isTrue();
    }

    private static void reseal(ObjectNode value, String field) {
        value.put(field, fingerprint('0'));
        value.put(
                field,
                EvidenceVerificationSupport.sha256Bounded(
                        value,
                        CapabilityCorpusVerifier.MAXIMUM_CANONICAL_BYTES));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
