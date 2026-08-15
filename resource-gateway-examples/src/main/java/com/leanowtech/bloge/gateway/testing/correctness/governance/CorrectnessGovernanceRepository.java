package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;

import java.util.Optional;

/** Scope-exact immutable store for proposed calibration and external governance projections. */
public interface CorrectnessGovernanceRepository {

    Optional<StoredOutcomeCalibrationProposal> findProposal(
            EnterpriseScope scope, String proposalId);

    StoredOutcomeCalibrationProposal saveProposalIfAbsent(
            EnterpriseScope scope,
            StoredOutcomeCalibrationProposal proposal,
            OutcomeCalibrationProposed event);

    Optional<StoredCorrectnessGovernanceFeedback> findFeedback(
            EnterpriseScope scope, String feedbackId);

    Optional<StoredCorrectnessGovernanceFeedback> findLatestFeedback(
            EnterpriseScope scope, String publicationId, String publicationFingerprint);

    StoredCorrectnessGovernanceFeedback saveFeedbackIfAbsent(
            EnterpriseScope scope,
            StoredCorrectnessGovernanceFeedback feedback,
            CorrectnessGovernanceFeedbackReceived event);
}
