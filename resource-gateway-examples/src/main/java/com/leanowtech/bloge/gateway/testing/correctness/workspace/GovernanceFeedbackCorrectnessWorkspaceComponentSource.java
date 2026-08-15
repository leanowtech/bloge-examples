package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.GateVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.Reason;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.Remediation;
import com.leanowtech.bloge.gateway.testing.correctness.governance.CorrectnessGovernanceFeedback;
import com.leanowtech.bloge.gateway.testing.correctness.governance.CorrectnessGovernanceRepository;
import com.leanowtech.bloge.gateway.testing.correctness.governance.StoredCorrectnessGovernanceFeedback;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Maps ANEKE feedback into the Gate axis without allowing it to forge local proof axes. */
public final class GovernanceFeedbackCorrectnessWorkspaceComponentSource
        implements CorrectnessWorkspaceComponentSource {

    private final CorrectnessWorkspaceComponentSource delegate;
    private final CorrectnessGovernanceRepository governance;
    private final Clock clock;

    public GovernanceFeedbackCorrectnessWorkspaceComponentSource(
            CorrectnessWorkspaceComponentSource delegate,
            CorrectnessGovernanceRepository governance,
            Clock clock
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Components load(Coordinate coordinate, PageRequest pageRequest) {
        Components base = delegate.load(coordinate, pageRequest);
        List<String> capabilities = new ArrayList<>(base.capabilities());
        capabilities.add("CORRECTNESS_GOVERNANCE_FEEDBACK_V1");
        if (base.lastPublication() == null) {
            return copy(base, base.verdict(), capabilities);
        }
        var publicationRef = base.lastPublication().publicationRef();
        StoredCorrectnessGovernanceFeedback stored = governance.findLatestFeedback(
                coordinate.scope(), publicationRef.id(), publicationRef.fingerprint()).orElse(null);
        if (stored == null) return copy(base, base.verdict(), capabilities);
        return copy(base, project(base.verdict(), stored.feedback()), capabilities);
    }

    private CorrectnessVerdict project(
            CorrectnessVerdict local, CorrectnessGovernanceFeedback feedback) {
        List<Reason> reasons = new ArrayList<>(local.reasons());
        List<Remediation> actions = new ArrayList<>(local.nextActions());
        GateVerdict gate = local.gate();
        if (feedback.expiredAt(clock.instant())) {
            gate = gate == GateVerdict.BLOCKED ? gate : GateVerdict.REVIEW;
            reasons.add(new Reason(
                    "ANEKE_FEEDBACK_EXPIRED", "GATE", "correctness.governance.feedbackExpired"));
            actions.add(new Remediation(
                    "REFRESH_GOVERNANCE_FEEDBACK", "ANEKE_FEEDBACK_EXPIRED"));
        } else {
            gate = switch (feedback.decision()) {
                case BLOCKED -> GateVerdict.BLOCKED;
                case REVIEW_REQUIRED, NOT_EVALUATED ->
                        gate == GateVerdict.BLOCKED ? gate : GateVerdict.REVIEW;
                // External acceptance cannot override missing local execution, assertions,
                // coverage, or evidence.
                case ACCEPTED -> gate;
            };
            for (CorrectnessGovernanceFeedback.Finding finding : feedback.findings()) {
                reasons.add(new Reason(
                        "ANEKE_" + finding.code(), "GATE",
                        "correctness.governance."
                                + finding.code().toLowerCase(Locale.ROOT)));
                actions.add(new Remediation(
                        "OPEN_GOVERNANCE_FEEDBACK", "ANEKE_" + finding.code()));
            }
        }
        return new CorrectnessVerdict(
                local.execution(), local.assertions(), local.coverage(), local.evidence(), gate,
                local.proofLevel(), reasons.stream().distinct().toList(),
                actions.stream().distinct().toList());
    }

    private static Components copy(
            Components base, CorrectnessVerdict verdict, List<String> capabilities) {
        return new Components(
                base.coverage(), base.oracleAssertions(), base.cases(), base.fixtures(),
                base.reviews(), base.lastPublication(), base.lastRun(), verdict,
                base.staleReasons(), capabilities.stream().distinct().sorted().toList(),
                base.commandPolicy());
    }
}
