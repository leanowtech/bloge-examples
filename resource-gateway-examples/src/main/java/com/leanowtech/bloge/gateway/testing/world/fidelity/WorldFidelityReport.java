package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable payload-free result of one real-versus-published-World calibration. */
public record WorldFidelityReport(
        String algorithmVersion,
        String requestFingerprint,
        String targetFingerprint,
        String sampleSetFingerprint,
        String implementationFingerprint,
        String worldSliceFingerprint,
        String contractFingerprint,
        String comparatorFingerprint,
        List<Observation> observations,
        Outcome outcome,
        String reportFingerprint
) {
    public enum Outcome { EQUIVALENT, DIFFERENT, UNKNOWN }

    public WorldFidelityReport {
        algorithmVersion = WorldFidelityRequest.text(algorithmVersion, 128);
        requestFingerprint = WorldFidelityRunner.fingerprint(requestFingerprint);
        targetFingerprint = WorldFidelityRunner.fingerprint(targetFingerprint);
        sampleSetFingerprint = WorldFidelityRunner.fingerprint(sampleSetFingerprint);
        implementationFingerprint = WorldFidelityRunner.fingerprint(implementationFingerprint);
        worldSliceFingerprint = WorldFidelityRunner.fingerprint(worldSliceFingerprint);
        contractFingerprint = WorldFidelityRunner.fingerprint(contractFingerprint);
        comparatorFingerprint = WorldFidelityRunner.fingerprint(comparatorFingerprint);
        observations = observations == null ? List.of() : observations.stream()
                .sorted(Comparator.comparing(Observation::sampleId)).toList();
        if (observations.isEmpty() || observations.stream().map(Observation::sampleId).distinct().count()
                != observations.size()) {
            throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
        }
        outcome = Objects.requireNonNull(outcome, "outcome");
        reportFingerprint = reportFingerprint == null ? "" : reportFingerprint;
        if (!reportFingerprint.isBlank() && !reportFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
        }
    }

    public WorldFidelityReport seal(ObjectMapper mapper) {
        String fingerprint = ProtocolFingerprint.of(mapper, new WorldFidelityReport(
                algorithmVersion, requestFingerprint, targetFingerprint, sampleSetFingerprint, implementationFingerprint,
                worldSliceFingerprint, contractFingerprint, comparatorFingerprint, observations, outcome, ""));
        return new WorldFidelityReport(algorithmVersion, requestFingerprint, targetFingerprint, sampleSetFingerprint,
                implementationFingerprint, worldSliceFingerprint, contractFingerprint, comparatorFingerprint,
                observations, outcome, fingerprint);
    }

    public boolean verify(ObjectMapper mapper) {
        return !reportFingerprint.isBlank() && reportFingerprint.equals(seal(mapper).reportFingerprint());
    }

    /** A failed or unknown calibration is always blocked from certifiable use. */
    public boolean gateBlocked() {
        return outcome != Outcome.EQUIVALENT;
    }

    @Override
    public String toString() {
        return "WorldFidelityReport{outcome=" + outcome + ", observations=" + observations.size()
                + ", reportFingerprint=" + reportFingerprint + '}';
    }

    public record Observation(
            String sampleId,
            String sampleFingerprint,
            String realResponseFingerprint,
            String worldResponseFingerprint,
            int realStatus,
            int worldStatus,
            String realErrorClass,
            String worldErrorClass,
            boolean realRetryable,
            boolean worldRetryable,
            List<String> differencePaths,
            String realTransitionFingerprint,
            String worldTransitionFingerprint,
            long latencyDeltaMillis
    ) {
        public Observation {
            sampleId = WorldFidelityRequest.text(sampleId, 256);
            sampleFingerprint = WorldFidelityRunner.fingerprint(sampleFingerprint);
            realResponseFingerprint = optionalFingerprint(realResponseFingerprint);
            worldResponseFingerprint = optionalFingerprint(worldResponseFingerprint);
            realErrorClass = safeLabel(realErrorClass);
            worldErrorClass = safeLabel(worldErrorClass);
            differencePaths = differencePaths == null ? List.of() : differencePaths.stream()
                    .map(value -> WorldFidelityRequest.text(value, 512)).distinct().sorted().toList();
            realTransitionFingerprint = optionalFingerprint(realTransitionFingerprint);
            worldTransitionFingerprint = optionalFingerprint(worldTransitionFingerprint);
            if (latencyDeltaMillis < 0) throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
        }
        private static String optionalFingerprint(String value) {
            if (value == null || value.isBlank()) return "";
            return WorldFidelityRunner.fingerprint(value);
        }
        private static String safeLabel(String value) {
            if (value == null || value.isBlank()) return "";
            if (!java.util.Set.of("TIMEOUT", "EXECUTION_FAILED", "NOT_FOUND", "VALIDATION", "UNKNOWN")
                    .contains(value)) {
                throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
            }
            return value;
        }
    }
}
