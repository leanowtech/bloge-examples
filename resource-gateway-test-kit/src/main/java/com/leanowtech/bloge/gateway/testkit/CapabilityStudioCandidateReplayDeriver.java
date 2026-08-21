package com.leanowtech.bloge.gateway.testkit;

import java.util.List;

/** Pure A0 terminal derivation from three adapter slots and fourteen obligations. */
final class CapabilityStudioCandidateReplayDeriver {
    static final int ADAPTER_SLOT_COUNT = 3;
    static final int OBLIGATION_COUNT = 14;
    static final String INCOMPLETE_REASON_CODE = "A0_INCOMPLETE";
    static final String STRUCTURE_VERIFIED_REASON_CODE = "A0_STRUCTURE_VERIFIED";
    static final String INVALID_REASON_CODE = "A0_INVALID";
    static final String UNAVAILABLE_REASON_CODE = "A0_UNAVAILABLE";

    private CapabilityStudioCandidateReplayDeriver() {
    }

    enum ReplayOutcome {
        VERIFIED,
        INVALID,
        UNAVAILABLE,
        NOT_RUN
    }

    enum ClosureOutcome {
        VERIFIED,
        INVALID,
        UNAVAILABLE
    }

    enum Terminal {
        UNAVAILABLE,
        INVALID,
        STRUCTURE_VERIFIED,
        INCOMPLETE
    }

    /**
     * Applies the frozen precedence:
     * unavailable, invalid, any verified, then all not-run.
     */
    static Decision derive(
            List<ReplayOutcome> adapterOutcomes,
            int failed,
            int blocked,
            int notRun,
            int evidenceCount,
            long evidenceByteSize) {
        return derive(adapterOutcomes, ClosureOutcome.VERIFIED,
                adapterOutcomes != null && adapterOutcomes.contains(ReplayOutcome.VERIFIED)
                        ? Terminal.STRUCTURE_VERIFIED.name() : Terminal.INCOMPLETE.name(),
                failed, blocked, notRun, evidenceCount, evidenceByteSize);
    }

    /** Applies the same precedence to adapter facts and the final closure fact exactly once. */
    static Decision derive(
            List<ReplayOutcome> adapterOutcomes,
            ClosureOutcome closureOutcome,
            int failed,
            int blocked,
            int notRun,
            int evidenceCount,
            long evidenceByteSize) {
        return derive(adapterOutcomes, closureOutcome,
                adapterOutcomes != null && adapterOutcomes.contains(ReplayOutcome.VERIFIED)
                        ? Terminal.STRUCTURE_VERIFIED.name() : Terminal.INCOMPLETE.name(),
                failed, blocked, notRun, evidenceCount, evidenceByteSize);
    }

    /** Also rejects a manifest verification-level projection that drifts from replay facts. */
    static Decision derive(
            List<ReplayOutcome> adapterOutcomes,
            ClosureOutcome closureOutcome,
            String declaredVerificationLevel,
            int failed,
            int blocked,
            int notRun,
            int evidenceCount,
            long evidenceByteSize) {
        if (adapterOutcomes == null || adapterOutcomes.size() != ADAPTER_SLOT_COUNT
                || adapterOutcomes.stream().anyMatch(outcome -> outcome == null)
                || closureOutcome == null
                || !(Terminal.STRUCTURE_VERIFIED.name().equals(declaredVerificationLevel)
                || Terminal.INCOMPLETE.name().equals(declaredVerificationLevel))) {
            throw invalid();
        }
        requireNonNegative(failed);
        requireNonNegative(blocked);
        requireNonNegative(notRun);
        if ((long) failed + blocked + notRun != OBLIGATION_COUNT) {
            throw invalid();
        }
        requireNonNegative(evidenceCount);
        requireNonNegative(evidenceByteSize);
        if (evidenceCount == 0 && evidenceByteSize != 0) {
            throw invalid();
        }

        int verified = count(adapterOutcomes, ReplayOutcome.VERIFIED);
        int invalid = count(adapterOutcomes, ReplayOutcome.INVALID);
        int unavailable = count(adapterOutcomes, ReplayOutcome.UNAVAILABLE);
        int adapterNotRun = count(adapterOutcomes, ReplayOutcome.NOT_RUN);
        Terminal terminal = unavailable > 0 || closureOutcome == ClosureOutcome.UNAVAILABLE
                ? Terminal.UNAVAILABLE
                : invalid > 0 || closureOutcome == ClosureOutcome.INVALID ? Terminal.INVALID
                : verified > 0 ? Terminal.STRUCTURE_VERIFIED
                : Terminal.INCOMPLETE;
        if ((terminal == Terminal.STRUCTURE_VERIFIED || terminal == Terminal.INCOMPLETE)
                && !terminal.name().equals(declaredVerificationLevel)) {
            terminal = Terminal.INVALID;
        }
        return new Decision(
                terminal, closureOutcome, declaredVerificationLevel,
                List.copyOf(adapterOutcomes), verified, invalid, unavailable,
                adapterNotRun, failed, blocked, notRun, evidenceCount, evidenceByteSize,
                reasonCode(terminal));
    }

    private static int count(List<ReplayOutcome> outcomes, ReplayOutcome expected) {
        return Math.toIntExact(outcomes.stream().filter(expected::equals).count());
    }

    private static String reasonCode(Terminal terminal) {
        return switch (terminal) {
            case UNAVAILABLE -> UNAVAILABLE_REASON_CODE;
            case INVALID -> INVALID_REASON_CODE;
            case STRUCTURE_VERIFIED -> STRUCTURE_VERIFIED_REASON_CODE;
            case INCOMPLETE -> INCOMPLETE_REASON_CODE;
        };
    }

    private static void requireNonNegative(int value) {
        if (value < 0) {
            throw invalid();
        }
    }

    private static void requireNonNegative(long value) {
        if (value < 0) {
            throw invalid();
        }
    }

    private static DerivationException invalid() {
        return new DerivationException();
    }

    /** Immutable, fully recomputed A0 decision. */
    record Decision(
            Terminal terminal,
            ClosureOutcome closureOutcome,
            String declaredVerificationLevel,
            List<ReplayOutcome> adapterOutcomes,
            int typedReplayCount,
            int invalidReplayCount,
            int unavailableReplayCount,
            int adapterNotRunCount,
            int failed,
            int blocked,
            int notRun,
            int evidenceCount,
            long evidenceByteSize,
            String reasonCode) {
        Decision {
            adapterOutcomes = adapterOutcomes == null ? null : List.copyOf(adapterOutcomes);
            if (terminal == null || closureOutcome == null || adapterOutcomes == null
                    || adapterOutcomes.size() != ADAPTER_SLOT_COUNT
                    || !CapabilityStudioCandidateReplayDeriver.reasonCode(terminal)
                    .equals(reasonCode)
                    || !(Terminal.STRUCTURE_VERIFIED.name().equals(declaredVerificationLevel)
                    || Terminal.INCOMPLETE.name().equals(declaredVerificationLevel))
                    || typedReplayCount != count(adapterOutcomes, ReplayOutcome.VERIFIED)
                    || invalidReplayCount != count(adapterOutcomes, ReplayOutcome.INVALID)
                    || unavailableReplayCount != count(adapterOutcomes, ReplayOutcome.UNAVAILABLE)
                    || adapterNotRunCount != count(adapterOutcomes, ReplayOutcome.NOT_RUN)
                    || failed < 0 || blocked < 0 || notRun < 0
                    || failed + blocked + notRun != OBLIGATION_COUNT
                    || evidenceCount < 0 || evidenceByteSize < 0
                    || evidenceCount == 0 && evidenceByteSize != 0
                    || terminal != expectedTerminal(
                    adapterOutcomes, closureOutcome, declaredVerificationLevel)) {
                throw invalid();
            }
        }

        int passed() {
            return 0;
        }
    }

    private static Terminal expectedTerminal(
            List<ReplayOutcome> outcomes,
            ClosureOutcome closure,
            String declaredVerificationLevel) {
        Terminal expected = outcomes.contains(ReplayOutcome.UNAVAILABLE)
                || closure == ClosureOutcome.UNAVAILABLE ? Terminal.UNAVAILABLE
                : outcomes.contains(ReplayOutcome.INVALID) || closure == ClosureOutcome.INVALID
                ? Terminal.INVALID
                : outcomes.contains(ReplayOutcome.VERIFIED)
                ? Terminal.STRUCTURE_VERIFIED : Terminal.INCOMPLETE;
        return (expected == Terminal.STRUCTURE_VERIFIED || expected == Terminal.INCOMPLETE)
                && !expected.name().equals(declaredVerificationLevel)
                ? Terminal.INVALID : expected;
    }

    /** Payload-free rejection of facts outside the frozen A0 contract. */
    static final class DerivationException extends IllegalArgumentException {
        private DerivationException() {
            super("candidate replay derivation input is invalid");
        }
    }
}
