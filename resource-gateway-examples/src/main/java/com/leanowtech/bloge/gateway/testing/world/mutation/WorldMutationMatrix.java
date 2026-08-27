package com.leanowtech.bloge.gateway.testing.world.mutation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Payload-free, bounded Scenario x mutant execution closure. */
public final class WorldMutationMatrix {
    public static final int MAX_SCENARIOS = 128;

    public record ScenarioRef(String scenarioId, String scenarioFingerprint) {
        public ScenarioRef {
            scenarioId = text(scenarioId);
            scenarioFingerprint = fingerprint(scenarioFingerprint);
        }
    }

    public enum ObservationStatus {
        PASSED, ASSERTION_FAILED, EXECUTION_FAILED, TIMEOUT, SKIPPED, CANCELLED, MOCKED
    }

    /** Only stable codes and fingerprints cross the evidence boundary; no payload is retained. */
    public record Observation(String scenarioId, String scenarioFingerprint, String mutantId,
                              String mutantTargetFingerprint, ObservationStatus status,
                              String evidenceFingerprint, String diagnosticCode) {
        public Observation {
            scenarioId = text(scenarioId);
            scenarioFingerprint = fingerprint(scenarioFingerprint);
            mutantId = text(mutantId);
            mutantTargetFingerprint = fingerprint(mutantTargetFingerprint);
            status = Objects.requireNonNull(status, "status");
            evidenceFingerprint = optionalFingerprint(evidenceFingerprint);
            diagnosticCode = optionalCode(diagnosticCode);
        }
    }

    public record ScenarioMutantMatrix(List<ScenarioRef> scenarios,
                                       List<Observation> observations) {
        public ScenarioMutantMatrix {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
            observations = observations == null ? List.of() : List.copyOf(observations);
            if (scenarios.isEmpty() || scenarios.size() > MAX_SCENARIOS) {
                throw new IllegalArgumentException("scenario closure is out of bounds");
            }
            Set<String> scenarioIds = new LinkedHashSet<>();
            scenarios.forEach(scenario -> {
                Objects.requireNonNull(scenario, "scenario");
                if (!scenarioIds.add(scenario.scenarioId())) {
                    throw new IllegalArgumentException("duplicate scenario id");
                }
            });
            if (observations.size() > MAX_SCENARIOS * 512) {
                throw new IllegalArgumentException("observation closure is out of bounds");
            }
            Set<String> keys = new LinkedHashSet<>();
            observations.forEach(observation -> {
                Objects.requireNonNull(observation, "observation");
                if (!scenarioIds.contains(observation.scenarioId())) {
                    throw new IllegalArgumentException("observation references an unknown scenario");
                }
                if (!keys.add(key(observation.scenarioId(), observation.mutantId()))) {
                    throw new IllegalArgumentException("duplicate scenario mutant observation");
                }
            });
            scenarios = scenarios.stream().sorted(Comparator.comparing(ScenarioRef::scenarioId)).toList();
            observations = observations.stream().sorted(Comparator.comparing(Observation::scenarioId)
                    .thenComparing(Observation::mutantId)).toList();
        }

        public static ScenarioMutantMatrix of(List<ScenarioRef> scenarios,
                                              List<Observation> observations) {
            return new ScenarioMutantMatrix(scenarios, observations);
        }

        public boolean has(String scenarioId, String mutantId) {
            return observations.stream().anyMatch(observation ->
                    observation.scenarioId().equals(scenarioId) && observation.mutantId().equals(mutantId));
        }

        private static String key(String scenarioId, String mutantId) {
            return scenarioId + "\u0000" + mutantId;
        }
    }

    private WorldMutationMatrix() {
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("invalid matrix identity");
        }
        return value.trim();
    }

    private static String fingerprint(String value) {
        if (value == null || !value.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid fingerprint");
        }
        return value;
    }

    private static String optionalFingerprint(String value) {
        return value == null || value.isBlank() ? "" : fingerprint(value);
    }

    private static String optionalCode(String value) {
        if (value == null || value.isBlank()) return "";
        if (!value.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid diagnostic code");
        }
        return value;
    }
}
