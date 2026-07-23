package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Independent verifier for fixture-level exact corpus publication bindings.
 *
 * <p>The verifier proves strict schema conformance, canonical capability order, exact artifact
 * kinds, and uniqueness of capability and publication references. It deliberately does not claim
 * that a publication is the current serving head or that its policy, grants, source payloads,
 * deletion state, and regional vault are usable; those facts require the live Resource Gateway
 * serving authorities.</p>
 */
public final class FixtureMirrorCorpusBindingsVerifier {
    private static final Comparator<CapabilityCoordinate> CAPABILITY_ORDER =
            Comparator.comparing(CapabilityCoordinate::id)
                    .thenComparingLong(CapabilityCoordinate::revision)
                    .thenComparing(CapabilityCoordinate::fingerprint);

    /** Creates a dependency-free verifier. */
    public FixtureMirrorCorpusBindingsVerifier() {
    }

    /**
     * Verifies one nested {@code fixtureBundle.metadata.mirrorCorpus} object.
     *
     * @param bindings nested corpus-binding JSON
     * @return stable payload-free verification result
     */
    public VerificationResult verify(JsonNode bindings) {
        if (bindings == null) {
            return new VerificationResult(
                    Outcome.SCHEMA_INVALID,
                    "CORPUS_BINDINGS_MISSING",
                    0);
        }
        try {
            CapabilityMirrorSchemaValidator.require(
                    bindings,
                    CapabilityMirrorProtocol
                            .FIXTURE_MIRROR_CORPUS_BINDINGS_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.CORPUS_BINDINGS_SCHEMA_INVALID");
        } catch (IllegalArgumentException invalid) {
            return new VerificationResult(
                    Outcome.SCHEMA_INVALID,
                    "CORPUS_BINDINGS_SCHEMA_INVALID",
                    bindings.path("publications").size());
        }
        Set<String> capabilities = new HashSet<>();
        Set<String> publications = new HashSet<>();
        CapabilityCoordinate previous = null;
        int count = 0;
        for (JsonNode binding : bindings.path("publications")) {
            count++;
            JsonNode capability = binding.path("capabilityRef");
            JsonNode publication = binding.path("publicationRef");
            CapabilityCoordinate capabilityCoordinate = coordinate(capability);
            if (!capabilities.add(identity(capability))) {
                return new VerificationResult(
                        Outcome.DUPLICATE_CAPABILITY,
                        "CORPUS_BINDINGS_CAPABILITY_DUPLICATE",
                        count);
            }
            if (!publications.add(identity(publication))) {
                return new VerificationResult(
                        Outcome.DUPLICATE_PUBLICATION,
                        "CORPUS_BINDINGS_PUBLICATION_DUPLICATE",
                        count);
            }
            if (previous != null
                    && CAPABILITY_ORDER.compare(capabilityCoordinate, previous) <= 0) {
                return new VerificationResult(
                        Outcome.ORDER_INVALID,
                        "CORPUS_BINDINGS_CAPABILITY_ORDER_INVALID",
                        count);
            }
            previous = capabilityCoordinate;
        }
        return new VerificationResult(Outcome.VERIFIED, "VERIFIED", count);
    }

    /** Closed verifier outcome. */
    public enum Outcome {
        /** Strict schema and semantic invariants passed. */
        VERIFIED,
        /** The nested JSON failed its strict schema. */
        SCHEMA_INVALID,
        /** Bindings are not strictly ordered by capability coordinate. */
        ORDER_INVALID,
        /** More than one binding names the same exact capability. */
        DUPLICATE_CAPABILITY,
        /** More than one binding names the same exact publication. */
        DUPLICATE_PUBLICATION
    }

    /**
     * Payload-free verification result.
     *
     * @param outcome closed outcome
     * @param reasonCode stable low-cardinality reason
     * @param checkedBindings number of bindings examined before the terminal decision
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
                throw new IllegalArgumentException("reasonCode is invalid");
            }
            if (checkedBindings < 0 || checkedBindings > 1_000) {
                throw new IllegalArgumentException("checkedBindings is invalid");
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

    private static CapabilityCoordinate coordinate(JsonNode ref) {
        return new CapabilityCoordinate(
                ref.path("id").asText(),
                ref.path("revision").asLong(),
                ref.path("fingerprint").asText());
    }

    private static String identity(JsonNode ref) {
        return ref.path("kind").asText()
                + "\u0000" + ref.path("id").asText()
                + "\u0000" + ref.path("revision").asLong();
    }

    private record CapabilityCoordinate(
            String id,
            long revision,
            String fingerprint
    ) {
    }
}
