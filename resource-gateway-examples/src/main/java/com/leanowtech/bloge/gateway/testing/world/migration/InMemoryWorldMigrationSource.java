package com.leanowtech.bloge.gateway.testing.world.migration;

/** Test-only exact source adapter; it has no write or mutation operation. */
final class InMemoryWorldMigrationSource implements WorldMigrationSource {
    private final WorldMigrationInput input;

    public InMemoryWorldMigrationSource(WorldMigrationInput input) {
        this.input = input;
    }

    @Override
    public WorldMigrationInput read(Access access, Request request) {
        if (access == null || request == null || input == null) {
            throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        if (!access.tenantId().equals(input.tenantId())) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TENANT_MISMATCH);
        }
        if (!request.fixtureBundleId().equals(input.fixtureBundle().fixtureBundleId())
                || request.fixtureRevision() != input.fixtureBundle().revision()
                || !request.fixtureFingerprint().equals(input.fixtureBundle().fingerprint())
                || !request.suiteId().equals(input.testSuite().suiteId())
                || request.suiteRevision() != input.testSuite().revision()
                || !request.suiteFingerprint().equals(input.testSuite().fingerprint())
                || !request.targetFingerprint().equals(input.fixtureBundle().bundle().targetFingerprint())) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TAMPERED);
        }
        return input;
    }
}
