package com.leanowtech.bloge.gateway.testing.function;

import java.util.List;

/** Deterministic, payload-free result of one function-control runtime. */
public record FunctionControlRunEvidence(
        String planFingerprint,
        FunctionEvidenceCeiling evidenceCeiling,
        List<FunctionControlEvidenceBinding> bindings,
        List<FunctionControlConsumption> consumptions,
        List<FunctionControlObservation> observations,
        String evidenceFingerprint
) {

    public FunctionControlRunEvidence {
        if (planFingerprint == null || !planFingerprint.matches("sha256:[0-9a-f]{64}")
                || evidenceCeiling == null || bindings == null
                || bindings.stream().anyMatch(java.util.Objects::isNull) || observations == null
                || consumptions == null || consumptions.stream().anyMatch(java.util.Objects::isNull)
                || observations.stream().anyMatch(java.util.Objects::isNull)
                || evidenceFingerprint == null
                || !evidenceFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        bindings = bindings.stream().sorted(java.util.Comparator.comparing(
                binding -> binding.site().structuralKey())).toList();
        consumptions = consumptions.stream().sorted(java.util.Comparator.comparing(
                FunctionControlConsumption::ruleId)).toList();
        observations = observations.stream().sorted().toList();
    }
}
