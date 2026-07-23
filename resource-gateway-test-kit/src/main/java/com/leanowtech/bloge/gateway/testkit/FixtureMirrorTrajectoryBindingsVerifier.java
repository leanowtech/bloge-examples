package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Independent verifier for fixture-level reviewed retry-trajectory bindings.
 *
 * <p>The verifier proves strict schema conformance, canonical capability/trajectory order, and
 * exact trajectory-coordinate uniqueness. It deliberately does not claim that the referenced
 * trajectory is current, matches the fixture's {@code mirrorCorpus} object, or still has valid
 * retry policy, grants, retention, tombstone, source, and payload authorities. Resource Gateway
 * revalidates those live facts for every materialization.</p>
 */
public final class FixtureMirrorTrajectoryBindingsVerifier {
    private static final Comparator<BindingCoordinate> ORDER =
            Comparator.comparing(
                            (BindingCoordinate value) -> value.capability().id())
                    .thenComparingLong(
                            value -> value.capability().revision())
                    .thenComparing(
                            value -> value.capability().fingerprint())
                    .thenComparing(
                            value -> value.trajectory().id())
                    .thenComparingLong(
                            value -> value.trajectory().revision())
                    .thenComparing(
                            value -> value.trajectory().fingerprint());

    /** Creates a dependency-free verifier. */
    public FixtureMirrorTrajectoryBindingsVerifier() {
    }

    /**
     * Verifies one nested {@code fixtureBundle.metadata.mirrorTrajectories} object.
     *
     * @param bindings nested trajectory-binding JSON
     * @return stable payload-free verification result
     */
    public VerificationResult verify(JsonNode bindings) {
        if (bindings == null) {
            return new VerificationResult(
                    Outcome.SCHEMA_INVALID,
                    "TRAJECTORY_BINDINGS_MISSING",
                    0);
        }
        try {
            CapabilityMirrorSchemaValidator.require(
                    bindings,
                    CapabilityMirrorProtocol
                            .FIXTURE_MIRROR_TRAJECTORY_BINDINGS_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.TRAJECTORY_BINDINGS_SCHEMA_INVALID");
        } catch (IllegalArgumentException invalid) {
            return new VerificationResult(
                    Outcome.SCHEMA_INVALID,
                    "TRAJECTORY_BINDINGS_SCHEMA_INVALID",
                    boundedCount(bindings.path("trajectories").size()));
        }
        Set<String> trajectories = new HashSet<>();
        BindingCoordinate previous = null;
        int count = 0;
        for (JsonNode binding : bindings.path("trajectories")) {
            count++;
            JsonNode capability = binding.path("capabilityRef");
            JsonNode trajectory =
                    binding.path("trajectoryPublicationRef");
            BindingCoordinate coordinate = new BindingCoordinate(
                    coordinate(capability), coordinate(trajectory));
            if (!trajectories.add(identity(trajectory))) {
                return new VerificationResult(
                        Outcome.DUPLICATE_TRAJECTORY,
                        "TRAJECTORY_BINDINGS_TRAJECTORY_DUPLICATE",
                        count);
            }
            if (previous != null
                    && ORDER.compare(coordinate, previous) <= 0) {
                return new VerificationResult(
                        Outcome.ORDER_INVALID,
                        "TRAJECTORY_BINDINGS_ORDER_INVALID",
                        count);
            }
            previous = coordinate;
        }
        return new VerificationResult(
                Outcome.VERIFIED, "VERIFIED", count);
    }

    /** Closed verifier outcome. */
    public enum Outcome {
        /** Strict schema and semantic invariants passed. */
        VERIFIED,
        /** The nested JSON failed its strict schema. */
        SCHEMA_INVALID,
        /** Bindings are not strictly ordered by capability and trajectory. */
        ORDER_INVALID,
        /** More than one binding names the same exact trajectory coordinate. */
        DUPLICATE_TRAJECTORY
    }

    /**
     * Payload-free offline verification result.
     *
     * @param outcome closed outcome
     * @param reasonCode stable low-cardinality reason
     * @param checkedBindings bindings examined before the terminal decision
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            int checkedBindings
    ) {
        /** Validates a bounded machine-readable result. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException(
                        "reasonCode is invalid");
            }
            if (checkedBindings < 0 || checkedBindings > 1_000) {
                throw new IllegalArgumentException(
                        "checkedBindings is invalid");
            }
        }

        /**
         * Reports whether all offline structural checks passed.
         *
         * @return true only for {@link Outcome#VERIFIED}
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private static ArtifactCoordinate coordinate(JsonNode ref) {
        return new ArtifactCoordinate(
                ref.path("id").asText(),
                ref.path("revision").asLong(),
                ref.path("fingerprint").asText());
    }

    private static String identity(JsonNode ref) {
        return ref.path("kind").asText()
                + "\u0000" + ref.path("id").asText()
                + "\u0000" + ref.path("revision").asLong();
    }

    private static int boundedCount(int count) {
        return Math.max(0, Math.min(1_000, count));
    }

    private record BindingCoordinate(
            ArtifactCoordinate capability,
            ArtifactCoordinate trajectory
    ) {
    }

    private record ArtifactCoordinate(
            String id,
            long revision,
            String fingerprint
    ) {
    }
}
