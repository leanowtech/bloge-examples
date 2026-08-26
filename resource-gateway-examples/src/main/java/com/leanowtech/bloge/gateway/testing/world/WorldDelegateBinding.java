package com.leanowtech.bloge.gateway.testing.world;

/** Internal compilation binding; it is deliberately not part of the FixtureBundle wire value. */
public record WorldDelegateBinding(
        String ruleId,
        String logicalContractId,
        String contractFingerprint,
        BlogeFragmentRef fragment,
        WorldStateSpec stateSpec
) {
    public WorldDelegateBinding(String ruleId,
                                String logicalContractId,
                                String contractFingerprint,
                                BlogeFragmentRef fragment) {
        this(ruleId, logicalContractId, contractFingerprint, fragment, StateSpec.empty());
    }

    public WorldDelegateBinding {
        ruleId = required(ruleId);
        logicalContractId = required(logicalContractId);
        contractFingerprint = required(contractFingerprint);
        if (!contractFingerprint.matches("sha256:[0-9a-f]{64}") || fragment == null
                || stateSpec == null) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.INVALID_BINDING);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.INVALID_BINDING);
        }
        return value.trim();
    }
}
