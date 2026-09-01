package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Optional;

/** Read-only bridge to the exact current Flow draft that will own newly authored Fixture material. */
@FunctionalInterface
public interface LegacyReusableFlowDraftSource {
    /** Returns only a committed head in the trusted scope; never guesses a historical or cross-scope target. */
    Optional<ReusableFlowDraft> findHead(AuthoringScope scope, String flowId);
}
