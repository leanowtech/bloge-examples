package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static com.leanowtech.bloge.gateway.testkit.EvidenceTrustTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceKeySetTrustVerifierTest {
    private final EvidenceKeySetTrustVerifier verifier = new EvidenceKeySetTrustVerifier(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void verifiesGenesisQuorumCurrentPinAndNestedKeySetThenRevalidatesAnEmptyPage() {
        Fixture fixture = fixture();
        ObjectNode genesis = publication(1, "", 0, NOW.minusSeconds(60),
                List.of(active(fixture.keySetFingerprint())),
                List.of(fixture.security(), fixture.release()));
        EvidenceKeySetTrustVerifier.VerificationResult first = verifier.verify(
                bundle(0, 1, false, List.of(genesis), genesis, fixture.keySet()),
                policy(fixture, 2), null);

        assertThat(first.verified()).isTrue();
        assertThat(first.trustedSnapshotFingerprint()).isEqualTo(fixture.keySetFingerprint());
        assertThat(first.checkpoint()).satisfies(checkpoint -> {
            assertThat(checkpoint.sequence()).isEqualTo(1);
            assertThat(checkpoint.publicationFingerprint())
                    .isEqualTo(genesis.path("publicationFingerprint").asText());
            assertThat(checkpoint.permanentlyRevokedPins()).isEmpty();
        });

        EvidenceKeySetTrustVerifier.VerificationResult unchanged = verifier.verify(
                bundle(1, 1, false, List.of(), genesis, fixture.keySet()),
                policy(fixture, 2), first.checkpoint());
        assertThat(unchanged.verified()).isTrue();
        assertThat(unchanged.checkpoint()).isEqualTo(first.checkpoint());
    }

    @Test
    void boundedCatchUpReturnsCheckpointWithoutTrustingHeadUntilEveryLinkIsConsumed() {
        Fixture fixture = fixture();
        ObjectNode first = publication(1, "", 0, NOW.minusSeconds(120),
                List.of(active(PIN_A)), List.of(fixture.security(), fixture.release()));
        ObjectNode second = publication(2, first.path("publicationFingerprint").asText(), 0,
                NOW.minusSeconds(60), List.of(overlap(PIN_A), active(fixture.keySetFingerprint())),
                List.of(fixture.security(), fixture.release()));

        EvidenceKeySetTrustVerifier.VerificationResult catchUp = verifier.verify(
                bundle(0, 2, true, List.of(first), second, fixture.keySet()),
                policy(fixture, 2), null);
        assertThat(catchUp.outcome())
                .isEqualTo(EvidenceKeySetTrustVerifier.Outcome.CATCH_UP_REQUIRED);
        assertThat(catchUp.trustedSnapshotFingerprint()).isEmpty();
        assertThat(catchUp.checkpoint().sequence()).isEqualTo(1);

        EvidenceKeySetTrustVerifier.VerificationResult completed = verifier.verify(
                bundle(1, 2, false, List.of(second), second, fixture.keySet()),
                policy(fixture, 2), catchUp.checkpoint());
        assertThat(completed.verified()).isTrue();
        assertThat(completed.checkpoint().sequence()).isEqualTo(2);
    }

    @Test
    void detectsRollbackSameSequenceSplitViewForkAndSequenceGap() {
        Fixture fixture = fixture();
        ObjectNode first = publication(1, "", 0, NOW.minusSeconds(120),
                List.of(active(fixture.keySetFingerprint())),
                List.of(fixture.security(), fixture.release()));
        EvidenceTrustCheckpoint checkpoint = verifier.verify(
                bundle(0, 1, false, List.of(first), first, fixture.keySet()),
                policy(fixture, 2), null).checkpoint();
        ObjectNode alternateFirst = publication(1, "", 0, NOW.minusSeconds(110),
                List.of(active(fixture.keySetFingerprint())),
                List.of(fixture.security(), fixture.release()));
        assertThat(verifier.verify(bundle(1, 1, false, List.of(), alternateFirst, fixture.keySet()),
                policy(fixture, 2), checkpoint).reasonCode())
                .isEqualTo("TRUST_LOG_SPLIT_VIEW_DETECTED");

        ObjectNode wrongPrevious = publication(2, "sha256:" + "f".repeat(64), 0,
                NOW.minusSeconds(60), List.of(active(fixture.keySetFingerprint())),
                List.of(fixture.security(), fixture.release()));
        assertThat(verifier.verify(bundle(1, 2, false, List.of(wrongPrevious), wrongPrevious,
                fixture.keySet()), policy(fixture, 2), checkpoint).reasonCode())
                .isEqualTo("TRUST_LOG_FORK_DETECTED");

        ObjectNode sequenceThree = publication(3, first.path("publicationFingerprint").asText(), 0,
                NOW.minusSeconds(60), List.of(active(fixture.keySetFingerprint())),
                List.of(fixture.security(), fixture.release()));
        assertThat(verifier.verify(bundle(1, 3, false, List.of(sequenceThree), sequenceThree,
                fixture.keySet()), policy(fixture, 2), checkpoint).reasonCode())
                .isEqualTo("TRUST_LOG_SEQUENCE_GAP");

        EvidenceTrustCheckpoint futureCheckpoint = new EvidenceTrustCheckpoint(DOMAIN, LOG, 2,
                "sha256:" + "e".repeat(64), 0, NOW.minusSeconds(30), java.util.Set.of());
        assertThat(verifier.verify(bundle(2, 1, false, List.of(), first, fixture.keySet()),
                policy(fixture, 2), futureCheckpoint).reasonCode())
                .isEqualTo("TRUST_LOG_ROLLBACK_DETECTED");
    }

    @Test
    void rejectsMissingQuorumMaterialTamperStaleHeadAndWrongExternalIdentity() {
        Fixture fixture = fixture();
        ObjectNode oneSignature = publication(1, "", 0, NOW.minusSeconds(60),
                List.of(active(fixture.keySetFingerprint())), List.of(fixture.security()));
        assertThat(verifier.verify(bundle(0, 1, false, List.of(oneSignature), oneSignature,
                fixture.keySet()), policy(fixture, 2), null).reasonCode())
                .isEqualTo("TRUST_AUTHORITY_QUORUM_NOT_MET");

        ObjectNode tampered = publication(1, "", 0, NOW.minusSeconds(60),
                List.of(active(fixture.keySetFingerprint())),
                List.of(fixture.security(), fixture.release()));
        ((ObjectNode) tampered.withArray("pins").get(0))
                .put("snapshotFingerprint", PIN_C);
        assertThat(verifier.verify(bundle(0, 1, false, List.of(tampered), tampered,
                fixture.keySet()), policy(fixture, 2), null).reasonCode())
                .isEqualTo("TRUST_PUBLICATION_MATERIAL_INVALID");

        ObjectNode stale = publication(1, "", 0, NOW.minusSeconds(3600),
                List.of(new Pin(fixture.keySetFingerprint(), "ACTIVE",
                        NOW.minusSeconds(3660), null, null, "")),
                List.of(fixture.security(), fixture.release()));
        assertThat(verifier.verify(bundle(0, 1, false, List.of(stale), stale, fixture.keySet()),
                policy(fixture, 2), null).reasonCode()).isEqualTo("TRUST_PUBLICATION_STALE");

        EvidenceTrustPolicy wrong = new EvidenceTrustPolicy("another-domain", LOG, 1,
                policy(fixture, 2).authorities());
        assertThat(verifier.verify(bundle(0, 1, false, List.of(oneSignature), oneSignature,
                fixture.keySet()), wrong, null).reasonCode())
                .isEqualTo("TRUST_LOG_IDENTITY_MISMATCH");
    }

    @Test
    void revokedPinAdvancesRecoveryEpochAndCannotBeResurrectedAcrossPages() {
        Fixture fixture = fixture();
        ObjectNode first = publication(1, "", 0, NOW.minusSeconds(180),
                List.of(active(PIN_A), overlap(PIN_B)),
                List.of(fixture.security(), fixture.release()));
        ObjectNode recovered = publication(2, first.path("publicationFingerprint").asText(), 1,
                NOW.minusSeconds(120), List.of(active(fixture.keySetFingerprint()),
                        overlap(PIN_A), revoked(PIN_B, NOW.minusSeconds(120))),
                List.of(fixture.security(), fixture.release()));
        EvidenceKeySetTrustVerifier.VerificationResult recoveredResult = verifier.verify(
                bundle(0, 2, false, List.of(first, recovered), recovered, fixture.keySet()),
                policy(fixture, 2), null);
        assertThat(recoveredResult.verified()).isTrue();
        assertThat(recoveredResult.checkpoint().permanentlyRevokedPins()).containsExactly(PIN_B);

        ObjectNode resurrection = publication(3, recovered.path("publicationFingerprint").asText(), 1,
                NOW.minusSeconds(60), List.of(active(PIN_B), overlap(fixture.keySetFingerprint())),
                List.of(fixture.security(), fixture.release()));
        EvidenceKeySetTrustVerifier.VerificationResult rejected = verifier.verify(
                bundle(2, 3, false, List.of(resurrection), resurrection, fixture.keySet()),
                policy(fixture, 2), recoveredResult.checkpoint());
        assertThat(rejected.reasonCode()).isEqualTo("TRUST_REVOKED_PIN_REACTIVATED");
    }

    @Test
    void schemaGuardRejectsUnknownWireFieldsBeforeAnyCryptographicDecision() {
        Fixture fixture = fixture();
        ObjectNode publication = publication(1, "", 0, NOW.minusSeconds(60),
                List.of(active(fixture.keySetFingerprint())),
                List.of(fixture.security(), fixture.release()));
        ObjectNode envelope = JSON.createObjectNode();
        ObjectNode payload = (ObjectNode) bundle(0, 1, false, List.of(publication), publication,
                fixture.keySet()).rawBundle();
        payload.put("producerVerified", true);
        envelope.put("payloadKind", "EVIDENCE_KEY_SET_TRUST_BUNDLE");
        envelope.put("payloadSchemaVersion", TestingProtocol.EVIDENCE_KEY_SET_TRUST_BUNDLE_V1);
        envelope.set("payload", payload);

        assertThatThrownBy(() -> EvidenceKeySetTrustBundle.fromEnvelope(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema validation");
    }
}
