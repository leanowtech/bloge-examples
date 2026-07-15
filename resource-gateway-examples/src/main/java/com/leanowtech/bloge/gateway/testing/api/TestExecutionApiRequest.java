package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.util.Map;

/**
 * Versioned caller contract for one controlled graph execution.
 *
 * <p>The caller may state {@link #executionPurpose()}, but the runtime never trusts it as an
 * authorization fact. The controller combines a verified workload identity, endpoint policy,
 * and server policy to mint the internal purpose.</p>
 *
 * @param schemaVersion request schema version
 * @param target immutable graph target intent
 * @param executionPurpose caller-declared intent; only {@code GRAPH_CONTRACT_TEST} is accepted
 * @param context business graph context; control fields are rejected
 * @param fixtureBundle inline exploratory fixture, mutually exclusive with {@code fixtureBundleRef}
 * @param fixtureBundleRef stored governed fixture reference
 * @param verbosity response trace verbosity
 * @param metadata bounded caller provenance such as suite and case references
 */
public record TestExecutionApiRequest(
        String schemaVersion,
        Target target,
        String executionPurpose,
        Map<String, Object> context,
        FixtureBundle fixtureBundle,
        FixtureBundleRef fixtureBundleRef,
        Verbosity verbosity,
        Map<String, Object> metadata
) {
    /** Current public execution request version. */
    public static final String SCHEMA_VERSION = "bloge.testExecutionRequest.v1";

    /** Supported trace projections. Full evidence is always persisted after sanitization. */
    public enum Verbosity {
        SUMMARY,
        STANDARD,
        FULL
    }

    /**
     * @param kind target kind; Stage 2 supports {@code GRAPH}
     * @param id registered graph name
     * @param fingerprint optional optimistic target fingerprint supplied by the caller
     */
    public record Target(String kind, String id, String fingerprint) {
        public Target {
            kind = normalized(kind).toUpperCase(java.util.Locale.ROOT);
            id = normalized(id);
            fingerprint = normalized(fingerprint);
        }
    }

    /**
     * @param fixtureBundleId stable fixture id
     * @param revision immutable revision
     * @param fingerprint optional optimistic fixture fingerprint
     */
    public record FixtureBundleRef(String fixtureBundleId, long revision, String fingerprint) {
        public FixtureBundleRef {
            fixtureBundleId = normalized(fixtureBundleId);
            fingerprint = normalized(fingerprint);
        }
    }

    /** Normalizes nullable collections while preserving the caller-declared intent for validation. */
    public TestExecutionApiRequest {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        executionPurpose = normalized(executionPurpose).toUpperCase(java.util.Locale.ROOT);
        context = context == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(context));
        verbosity = verbosity == null ? Verbosity.STANDARD : verbosity;
        metadata = metadata == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
