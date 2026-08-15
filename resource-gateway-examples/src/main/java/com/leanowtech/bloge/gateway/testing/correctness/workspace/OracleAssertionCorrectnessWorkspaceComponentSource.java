package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository.AssertionTargetSummary;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository.OracleTargetSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.OracleAssertionSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.ReviewSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adds payload-free Oracle and Assertion Set readiness counts for one exact target. */
public final class OracleAssertionCorrectnessWorkspaceComponentSource
        implements CorrectnessWorkspaceComponentSource {

    private final CorrectnessWorkspaceComponentSource delegate;
    private final BusinessOracleRepository oracles;
    private final AssertionSetRepository assertionSets;

    public OracleAssertionCorrectnessWorkspaceComponentSource(
            CorrectnessWorkspaceComponentSource delegate,
            BusinessOracleRepository oracles,
            AssertionSetRepository assertionSets
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.oracles = Objects.requireNonNull(oracles, "oracles");
        this.assertionSets = Objects.requireNonNull(assertionSets, "assertionSets");
    }

    @Override
    public Components load(Coordinate coordinate, PageRequest pageRequest) {
        Components base = delegate.load(coordinate, pageRequest);
        OracleTargetSummary oracle = oracles.summarize(coordinate.scope(), coordinate.target());
        AssertionTargetSummary assertions = assertionSets.summarize(
                coordinate.scope(), coordinate.target());
        OracleAssertionSummary summary = new OracleAssertionSummary(
                Availability.AVAILABLE,
                oracle.total(), oracle.proposed(), oracle.approved(), oracle.superseded(),
                assertions.total(), assertions.draft(), assertions.valid(), assertions.stale(),
                assertions.unsupported());
        ReviewSummary reviews = new ReviewSummary(
                base.reviews().pending() + oracle.proposed(),
                base.reviews().approved() + oracle.approved(),
                base.reviews().rejected(),
                base.reviews().stale() + oracle.superseded() + assertions.stale());
        List<String> capabilities = new ArrayList<>(base.capabilities());
        capabilities.add("BUSINESS_ORACLE_SUMMARY_V1");
        capabilities.add("ASSERTION_SET_SUMMARY_V1");
        return new Components(
                base.coverage(), summary, base.cases(), base.fixtures(), reviews,
                base.lastPublication(), base.lastRun(),
                verdict(base.verdict(), oracle, assertions), base.staleReasons(),
                List.copyOf(capabilities), base.commandPolicy());
    }

    private static CorrectnessVerdict verdict(
            CorrectnessVerdict base,
            OracleTargetSummary oracle,
            AssertionTargetSummary assertions
    ) {
        String reasonCode;
        String action;
        if (oracle.approved() == 0) {
            reasonCode = "ORACLE_APPROVAL_REQUIRED";
            action = "OPEN_ORACLE_BUILDER";
        } else if (assertions.valid() == 0) {
            reasonCode = "ASSERTION_SET_REQUIRED";
            action = "OPEN_ASSERTION_BUILDER";
        } else {
            return base;
        }
        List<CorrectnessVerdict.Reason> reasons = new ArrayList<>(base.reasons());
        reasons.add(new CorrectnessVerdict.Reason(
                reasonCode, "GATE", "correctness.oracleAssertion."
                        + reasonCode.toLowerCase()));
        List<CorrectnessVerdict.Remediation> actions = new ArrayList<>(base.nextActions());
        actions.add(new CorrectnessVerdict.Remediation(action, reasonCode));
        return new CorrectnessVerdict(
                base.execution(), base.assertions(), base.coverage(), base.evidence(),
                base.gate(), base.proofLevel(), List.copyOf(reasons), List.copyOf(actions));
    }
}
