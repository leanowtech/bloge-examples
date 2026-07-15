package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Versioned caller contract for one controlled operator micro-graph execution.
 *
 * <p>The path and {@link #target()} must identify the same frozen registry binding. The arbitrary
 * JSON {@link #input()} is converted to the binding's declared Java input type only after identity,
 * target fingerprint and fixture provenance have been verified.</p>
 *
 * @param schemaVersion request schema version
 * @param target immutable operator target intent
 * @param executionPurpose caller-declared intent; only {@code OPERATOR_UNIT_TEST} is accepted
 * @param input formal operator input
 * @param fixtureBundle inline exploratory fixture, mutually exclusive with {@code fixtureBundleRef}
 * @param fixtureBundleRef governed immutable fixture reference
 * @param verbosity response trace verbosity
 * @param metadata bounded suite and case provenance
 */
public record TestOperatorExecutionApiRequest(
        String schemaVersion,
        TestExecutionApiRequest.Target target,
        String executionPurpose,
        Object input,
        FixtureBundle fixtureBundle,
        TestExecutionApiRequest.FixtureBundleRef fixtureBundleRef,
        TestExecutionApiRequest.Verbosity verbosity,
        Map<String, Object> metadata
) {
    /** Current public operator execution request version. */
    public static final String SCHEMA_VERSION = "bloge.testOperatorExecutionRequest.v1";
    /** Fixed server-authorized purpose for this adapter. */
    public static final String EXECUTION_PURPOSE = "OPERATOR_UNIT_TEST";

    /** Normalizes identifiers and defensively freezes caller metadata. */
    public TestOperatorExecutionApiRequest {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        executionPurpose = normalized(executionPurpose).toUpperCase(Locale.ROOT);
        verbosity = verbosity == null ? TestExecutionApiRequest.Verbosity.STANDARD : verbosity;
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
