package com.leanowtech.bloge.gateway.testing.world;

/** Explicit, content-addressed selection of one world slice for one logical contract. */
public record WorldSliceSelection(String provider, String apiVersion, String sliceFingerprint) {
    public WorldSliceSelection {
        provider = normalize(provider);
        apiVersion = normalize(apiVersion);
        sliceFingerprint = normalize(sliceFingerprint);
        if (provider.isBlank() || apiVersion.isBlank()
                || !sliceFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw invalid();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static WorldScenarioCompilationException invalid() {
        return new WorldScenarioCompilationException(
                WorldScenarioCompilationException.Code.INVALID_SELECTION);
    }
}
