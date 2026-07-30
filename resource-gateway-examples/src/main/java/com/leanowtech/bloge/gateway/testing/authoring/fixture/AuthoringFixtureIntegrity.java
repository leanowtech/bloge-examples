package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Immutable storage and lookup verification for encrypted authoring fixtures. */
final class AuthoringFixtureIntegrity {

    private AuthoringFixtureIntegrity() {
    }

    static StoredAuthoringFixture attach(
            ObjectMapper mapper, StoredAuthoringFixture value) {
        StoredAuthoringFixture detached = detached(mapper, value);
        validate(detached);
        return detached.withRecordFingerprint(recordFingerprint(mapper, detached));
    }

    static StoredAuthoringFixture verify(
            ObjectMapper mapper, StoredAuthoringFixture value) {
        StoredAuthoringFixture detached = detached(mapper, value);
        validate(detached);
        if (!Objects.equals(
                detached.recordFingerprint(),
                recordFingerprint(mapper, detached))) {
            throw new AuthoringFixtureIntegrityException();
        }
        return detached;
    }

    static StoredAuthoringFixture verifyLookup(
            ObjectMapper mapper,
            StoredAuthoringFixture value,
            TestingArtifactScope scope,
            String fixtureId,
            long revision) {
        StoredAuthoringFixture verified = verify(mapper, value);
        if (!Objects.equals(scope, verified.scope())
                || !fixtureId.equals(verified.descriptor().fixtureId())
                || revision != verified.descriptor().revision()) {
            throw new AuthoringFixtureIntegrityException();
        }
        return verified;
    }

    static String associatedData(StoredAuthoringFixture value) {
        return associatedData(value.scope(), value.descriptor());
    }

    static String associatedData(
            TestingArtifactScope scope,
            AuthoringFixtureProtocol.FixtureReceipt descriptor) {
        return String.join("\n",
                "bloge.visualAuthoringFixture.aad.v1",
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                descriptor.fixtureId(),
                Long.toString(descriptor.revision()),
                descriptor.draftId(),
                Long.toString(descriptor.authoringRevision()),
                descriptor.artifactFingerprint(),
                descriptor.payloadFingerprint());
    }

    private static String recordFingerprint(
            ObjectMapper mapper, StoredAuthoringFixture value) {
        return ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", value.schemaVersion(),
                "scope", value.scope(),
                "descriptor", value.descriptor(),
                "state", value.state(),
                "payloadAvailable", value.payloadAvailable(),
                "protectedPayload", value.protectedPayload()));
    }

    private static void validate(StoredAuthoringFixture value) {
        if (!StoredAuthoringFixture.SCHEMA_VERSION.equals(value.schemaVersion())
                || value.scope() == null
                || value.descriptor() == null
                || !scopeMatches(value)
                || value.descriptor().revision() <= 0
                || value.descriptor().expiresAt() == null
                || value.descriptor().payloadFingerprint().isBlank()
                || value.descriptor().artifactFingerprint().isBlank()) {
            throw new AuthoringFixtureIntegrityException();
        }
        boolean available = StoredAuthoringFixture.AVAILABLE.equals(value.state());
        boolean expired = StoredAuthoringFixture.EXPIRED.equals(value.state());
        if ((!available && !expired)
                || available != value.payloadAvailable()
                || available == value.protectedPayload().isBlank()
                || expired && !value.protectedPayload().isBlank()) {
            throw new AuthoringFixtureIntegrityException();
        }
    }

    private static boolean scopeMatches(StoredAuthoringFixture value) {
        var scope = value.scope();
        var descriptor = value.descriptor();
        return scope.tenantId().equals(descriptor.tenantId())
                && scope.organizationId().equals(descriptor.organizationId())
                && scope.projectId().equals(descriptor.projectId())
                && scope.environmentId().equals(descriptor.environmentId())
                && scope.region().equals(descriptor.region());
    }

    private static StoredAuthoringFixture detached(
            ObjectMapper mapper, StoredAuthoringFixture value) {
        try {
            return mapper.readValue(
                    mapper.writeValueAsBytes(value), StoredAuthoringFixture.class);
        } catch (IOException | IllegalArgumentException failure) {
            throw new AuthoringFixtureIntegrityException(failure);
        }
    }
}
