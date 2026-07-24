package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStateWriteAttemptVerifierTest {
    private final MirrorStateWriteAttemptVerifier verifier =
            new MirrorStateWriteAttemptVerifier();

    @Test
    void independentlyVerifiesIdentityFingerprintAndReplayClosure() {
        ObjectNode attempt =
                ResourceGatewayMirrorSessionClientTest
                        .writeAttempt();

        MirrorStateWriteAttemptVerifier.VerifiedWriteAttempt
                verified = verifier.verify(attempt);

        assertThat(verified.outcome())
                .isEqualTo("REPLAYED");
        assertThat(verified.initialStateRevision())
                .isZero();
        assertThat(verified.resultingStateRevision())
                .isZero();
        assertThat(verified.fingerprint())
                .startsWith("sha256:");
    }

    @Test
    void rejectsTamperedCoordinatesAndResealedSemanticDrift() {
        ObjectNode tampered =
                ResourceGatewayMirrorSessionClientTest
                        .writeAttempt();
        tampered.withObject("coordinate")
                .put("executionLeaseEpoch", 2);

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(
                        IllegalArgumentException.class);

        ObjectNode drifted =
                ResourceGatewayMirrorSessionClientTest
                        .writeAttempt();
        drifted.put("outcome", "COMMITTED");
        ObjectNode material = drifted.deepCopy();
        material.putNull("reconciledAt");
        material.put("fingerprint", "");
        drifted.put("fingerprint",
                EvidenceVerificationSupport.sha256(material));

        assertThatThrownBy(() -> verifier.verify(drifted))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "STATE_WRITE_ATTEMPT_CLOSURE_INVALID");
    }
}
