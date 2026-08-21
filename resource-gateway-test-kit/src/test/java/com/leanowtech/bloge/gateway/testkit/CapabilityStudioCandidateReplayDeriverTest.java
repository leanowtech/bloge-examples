package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioCandidateReplayDeriver.ReplayOutcome.INVALID;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioCandidateReplayDeriver.ReplayOutcome.NOT_RUN;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioCandidateReplayDeriver.ReplayOutcome.UNAVAILABLE;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioCandidateReplayDeriver.ReplayOutcome.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioCandidateReplayDeriverTest {
    @Test
    void appliesTheFrozenTerminalPrecedenceAcrossThreeSlots() {
        assertDecision(List.of(NOT_RUN, NOT_RUN, NOT_RUN),
                CapabilityStudioCandidateReplayDeriver.Terminal.INCOMPLETE, 0);
        assertDecision(List.of(VERIFIED, NOT_RUN, NOT_RUN),
                CapabilityStudioCandidateReplayDeriver.Terminal.STRUCTURE_VERIFIED, 1);
        assertDecision(List.of(VERIFIED, INVALID, NOT_RUN),
                CapabilityStudioCandidateReplayDeriver.Terminal.INVALID, 1);
        assertDecision(List.of(INVALID, UNAVAILABLE, VERIFIED),
                CapabilityStudioCandidateReplayDeriver.Terminal.UNAVAILABLE, 1);
    }

    @Test
    void preservesFormalGapAndEvidenceProjectionsWithoutPromotingPass() {
        var decision = CapabilityStudioCandidateReplayDeriver.derive(
                List.of(VERIFIED, VERIFIED, VERIFIED), 4, 5, 5, 2, 17);

        assertThat(decision.typedReplayCount()).isEqualTo(3);
        assertThat(decision.invalidReplayCount()).isZero();
        assertThat(decision.unavailableReplayCount()).isZero();
        assertThat(decision.adapterNotRunCount()).isZero();
        assertThat(decision.failed()).isEqualTo(4);
        assertThat(decision.blocked()).isEqualTo(5);
        assertThat(decision.notRun()).isEqualTo(5);
        assertThat(decision.evidenceCount()).isEqualTo(2);
        assertThat(decision.evidenceByteSize()).isEqualTo(17);
        assertThat(decision.passed()).isZero();
    }

    @Test
    void derivesOneTerminalFromAdapterAndClosureFactsWithoutExceptionOverride() {
        var invalidClosure = CapabilityStudioCandidateReplayDeriver.derive(
                List.of(VERIFIED, NOT_RUN, NOT_RUN),
                CapabilityStudioCandidateReplayDeriver.ClosureOutcome.INVALID,
                0, 0, 14, 1, 8);
        var unavailableClosure = CapabilityStudioCandidateReplayDeriver.derive(
                List.of(INVALID, VERIFIED, NOT_RUN),
                CapabilityStudioCandidateReplayDeriver.ClosureOutcome.UNAVAILABLE,
                0, 0, 14, 1, 8);

        assertThat(invalidClosure.terminal())
                .isEqualTo(CapabilityStudioCandidateReplayDeriver.Terminal.INVALID);
        assertThat(unavailableClosure.terminal())
                .isEqualTo(CapabilityStudioCandidateReplayDeriver.Terminal.UNAVAILABLE);
        assertThat(unavailableClosure.invalidReplayCount()).isEqualTo(1);
        assertThat(unavailableClosure.typedReplayCount()).isEqualTo(1);
    }

    @Test
    void rejectsSlotCountObligationAndEvidenceDrift() {
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> invalidFacts = List.of(
                () -> CapabilityStudioCandidateReplayDeriver.derive(
                        List.of(NOT_RUN, NOT_RUN), 0, 0, 14, 0, 0),
                () -> CapabilityStudioCandidateReplayDeriver.derive(
                        List.of(NOT_RUN, NOT_RUN, NOT_RUN, NOT_RUN), 0, 0, 14, 0, 0),
                () -> CapabilityStudioCandidateReplayDeriver.derive(
                        List.of(NOT_RUN, NOT_RUN, NOT_RUN), 0, 0, 13, 0, 0),
                () -> CapabilityStudioCandidateReplayDeriver.derive(
                        List.of(NOT_RUN, NOT_RUN, NOT_RUN), -1, 1, 14, 0, 0),
                () -> CapabilityStudioCandidateReplayDeriver.derive(
                        List.of(NOT_RUN, NOT_RUN, NOT_RUN), 0, 0, 14, -1, 0),
                () -> CapabilityStudioCandidateReplayDeriver.derive(
                        List.of(NOT_RUN, NOT_RUN, NOT_RUN), 0, 0, 14, 0, 1));

        for (var operation : invalidFacts) {
            assertThatThrownBy(operation)
                    .isInstanceOf(
                            CapabilityStudioCandidateReplayDeriver.DerivationException.class)
                    .hasMessage("candidate replay derivation input is invalid");
        }
    }

    private static void assertDecision(
            List<CapabilityStudioCandidateReplayDeriver.ReplayOutcome> outcomes,
            CapabilityStudioCandidateReplayDeriver.Terminal terminal,
            int verified) {
        var decision = CapabilityStudioCandidateReplayDeriver.derive(
                outcomes, 0, 0, 14, 0, 0);
        assertThat(decision.terminal()).isEqualTo(terminal);
        assertThat(decision.typedReplayCount()).isEqualTo(verified);
        assertThat(decision.reasonCode()).isEqualTo(switch (terminal) {
            case UNAVAILABLE -> CapabilityStudioCandidateReplayDeriver.UNAVAILABLE_REASON_CODE;
            case INVALID -> CapabilityStudioCandidateReplayDeriver.INVALID_REASON_CODE;
            case STRUCTURE_VERIFIED ->
                    CapabilityStudioCandidateReplayDeriver.STRUCTURE_VERIFIED_REASON_CODE;
            case INCOMPLETE -> CapabilityStudioCandidateReplayDeriver.INCOMPLETE_REASON_CODE;
        });
        assertThat(decision.passed()).isZero();
    }
}
