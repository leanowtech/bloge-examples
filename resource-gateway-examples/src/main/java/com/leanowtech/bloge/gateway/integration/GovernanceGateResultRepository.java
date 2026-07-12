package com.leanowtech.bloge.gateway.integration;

import java.util.List;
import java.util.Optional;

/** Immutable repository for governance gate results received from ANEKE. */
public interface GovernanceGateResultRepository {
    Optional<GovernanceGateResult> find(String gateResultId);
    List<GovernanceGateResult> forDraft(String draftId);
    GovernanceGateResult create(GovernanceGateResult result);
}
