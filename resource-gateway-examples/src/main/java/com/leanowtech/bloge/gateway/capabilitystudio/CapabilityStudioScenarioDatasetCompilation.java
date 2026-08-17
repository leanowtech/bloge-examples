package com.leanowtech.bloge.gateway.capabilitystudio;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

import java.util.Objects;

/** Deterministic Dataset-to-ScenarioDraftSet compilation output. */
public record CapabilityStudioScenarioDatasetCompilation(
        ScenarioDraftSet draftSet,
        CapabilityStudioScenarioDatasetSourceMap sourceMap,
        ContractDraft.Target target,
        String contractFingerprint,
        String semanticFingerprint) {

    public CapabilityStudioScenarioDatasetCompilation {
        Objects.requireNonNull(draftSet, "draftSet");
        Objects.requireNonNull(sourceMap, "sourceMap");
        Objects.requireNonNull(target, "target");
        contractFingerprint = contractFingerprint == null ? "" : contractFingerprint.trim();
        semanticFingerprint = semanticFingerprint == null ? "" : semanticFingerprint.trim();
    }
}
