package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;

/**
 * Versioned request for registering one immutable test-suite revision.
 *
 * @param schemaVersion registration protocol version
 * @param testSuite immutable suite content
 */
public record TestSuiteRegistrationRequest(String schemaVersion, TestSuiteProtocol testSuite) {
    /** Current registration request protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteRegistrationRequest.v1";

    /** Applies the current protocol version when constructed inside trusted Java adapters. */
    public TestSuiteRegistrationRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
    }
}
