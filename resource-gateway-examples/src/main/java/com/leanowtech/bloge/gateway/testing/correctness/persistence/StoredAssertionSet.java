package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;

/** Scope-bound, integrity-addressed persisted Assertion Set revision. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredAssertionSet(
        String schemaVersion,
        EnterpriseScope scope,
        String assertionSetFingerprint,
        AssertionSet assertionSet
) {
    public static final String SCHEMA_VERSION = "bloge.storedAssertionSet.v1";

    public StoredAssertionSet {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stored Assertion Set schemaVersion");
        }
        if (scope == null) throw new IllegalArgumentException("Assertion Set scope is required");
        if (assertionSetFingerprint == null
                || !assertionSetFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact Assertion Set fingerprint is required");
        }
        if (assertionSet == null || assertionSet.revision() < 1) {
            throw new IllegalArgumentException("Persisted Assertion Set revision is required");
        }
    }

    public static StoredAssertionSet verified(
            ObjectMapper mapper,
            EnterpriseScope scope,
            AssertionSet assertionSet
    ) {
        return new StoredAssertionSet(
                SCHEMA_VERSION, scope,
                CorrectnessProtocolFingerprint.fingerprint(mapper, assertionSet),
                assertionSet);
    }
}
