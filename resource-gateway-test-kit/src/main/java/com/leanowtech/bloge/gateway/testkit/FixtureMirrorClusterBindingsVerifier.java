package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Independent verifier for fixture-level reviewed recorded-cluster bindings.
 *
 * <p>The verifier proves strict schema conformance, canonical capability/cluster order, exact
 * cluster-coordinate uniqueness, and optional equality with the fixture's {@code mirrorCorpus}
 * selection. It cannot prove live cluster heads, policies, validation authority, source grants,
 * retention, tombstones, or payload content; Resource Gateway revalidates those facts for each
 * materialization.</p>
 */
public final class FixtureMirrorClusterBindingsVerifier {
    private static final Comparator<BindingCoordinate> ORDER =
            Comparator.comparing(
                            (BindingCoordinate value) -> value.capability().id())
                    .thenComparingLong(
                            value -> value.capability().revision())
                    .thenComparing(
                            value -> value.capability().fingerprint())
                    .thenComparing(value -> value.cluster().id())
                    .thenComparingLong(value -> value.cluster().revision())
                    .thenComparing(value -> value.cluster().fingerprint());

    /** Creates a dependency-free verifier. */
    public FixtureMirrorClusterBindingsVerifier() {
    }

    /**
     * Verifies one nested {@code fixtureBundle.metadata.mirrorClusters} object.
     *
     * @param bindings nested cluster-binding JSON
     * @return stable payload-free verification result
     */
    public VerificationResult verify(JsonNode bindings) {
        return verify(bindings, null);
    }

    /**
     * Verifies cluster bindings and optionally cross-checks the sibling corpus selection.
     *
     * @param bindings nested cluster-binding JSON
     * @param corpusBindings optional sibling {@code mirrorCorpus} JSON
     * @return stable payload-free verification result
     */
    public VerificationResult verify(
            JsonNode bindings, JsonNode corpusBindings) {
        if (bindings == null) {
            return result(
                    Outcome.SCHEMA_INVALID,
                    "CLUSTER_BINDINGS_MISSING",
                    0);
        }
        try {
            CapabilityMirrorSchemaValidator.require(
                    bindings,
                    CapabilityMirrorProtocol
                            .FIXTURE_MIRROR_CLUSTER_BINDINGS_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.CLUSTER_BINDINGS_SCHEMA_INVALID");
            if (corpusBindings != null) {
                CapabilityMirrorSchemaValidator.require(
                        corpusBindings,
                        CapabilityMirrorProtocol
                                .FIXTURE_MIRROR_CORPUS_BINDINGS_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.CORPUS_BINDINGS_SCHEMA_INVALID");
            }
        } catch (IllegalArgumentException invalid) {
            return result(
                    Outcome.SCHEMA_INVALID,
                    "CLUSTER_BINDINGS_SCHEMA_INVALID",
                    boundedCount(bindings.path("clusters").size()));
        }
        Set<String> clusters = new HashSet<>();
        Set<String> selectedCorpora = selectedCorpora(corpusBindings);
        BindingCoordinate previous = null;
        int count = 0;
        for (JsonNode binding : bindings.path("clusters")) {
            count++;
            JsonNode capability = binding.path("capabilityRef");
            JsonNode corpus = binding.path("corpusPublicationRef");
            JsonNode cluster = binding.path("clusterPublicationRef");
            BindingCoordinate coordinate = new BindingCoordinate(
                    coordinate(capability), coordinate(cluster));
            if (!clusters.add(logicalCoordinate(cluster))) {
                return result(
                        Outcome.DUPLICATE_CLUSTER,
                        "CLUSTER_BINDINGS_CLUSTER_DUPLICATE",
                        count);
            }
            if (previous != null
                    && ORDER.compare(coordinate, previous) <= 0) {
                return result(
                        Outcome.ORDER_INVALID,
                        "CLUSTER_BINDINGS_ORDER_INVALID",
                        count);
            }
            if (corpusBindings != null
                    && !selectedCorpora.contains(
                    identity(capability) + "\u0001" + identity(corpus))) {
                return result(
                        Outcome.CORPUS_BINDING_MISMATCH,
                        "CLUSTER_BINDINGS_CORPUS_MISMATCH",
                        count);
            }
            previous = coordinate;
        }
        return result(Outcome.VERIFIED, "VERIFIED", count);
    }

    /** Closed verifier outcome. */
    public enum Outcome {
        /** Strict schema and semantic invariants passed. */
        VERIFIED,
        /** The nested JSON failed its strict schema. */
        SCHEMA_INVALID,
        /** Bindings are not strictly ordered by capability and cluster. */
        ORDER_INVALID,
        /** More than one binding names the same exact cluster coordinate. */
        DUPLICATE_CLUSTER,
        /** A cluster does not name one exact sibling corpus selection. */
        CORPUS_BINDING_MISMATCH
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
         * Reports whether every offline cluster-binding check passed.
         *
         * @return true only when all offline checks passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private static Set<String> selectedCorpora(JsonNode corpusBindings) {
        if (corpusBindings == null) {
            return Set.of();
        }
        Set<String> selected = new HashSet<>();
        for (JsonNode binding : corpusBindings.path("publications")) {
            selected.add(identity(binding.path("capabilityRef"))
                    + "\u0001"
                    + identity(binding.path("publicationRef")));
        }
        return Set.copyOf(selected);
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
                + "\u0000" + ref.path("revision").asLong()
                + "\u0000" + ref.path("fingerprint").asText();
    }

    private static String logicalCoordinate(JsonNode ref) {
        return ref.path("kind").asText()
                + "\u0000" + ref.path("id").asText()
                + "\u0000" + ref.path("revision").asLong();
    }

    private static VerificationResult result(
            Outcome outcome, String reason, int count) {
        return new VerificationResult(outcome, reason, boundedCount(count));
    }

    private static int boundedCount(int count) {
        return Math.max(0, Math.min(1_000, count));
    }

    private record BindingCoordinate(
            ArtifactCoordinate capability,
            ArtifactCoordinate cluster
    ) {
    }

    private record ArtifactCoordinate(
            String id,
            long revision,
            String fingerprint
    ) {
    }
}
