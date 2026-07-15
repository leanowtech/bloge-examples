package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

/** Request used to register an immutable fixture revision against a frozen graph target. */
public record FixtureBundleRegistrationRequest(
        String schemaVersion,
        TestExecutionApiRequest.Target target,
        FixtureBundle fixtureBundle
) {
    public static final String SCHEMA_VERSION = "bloge.fixtureBundleRegistrationRequest.v1";

    public FixtureBundleRegistrationRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
    }
}
