package com.leanowtech.bloge.gateway.testing.world.migration;

/** Read-only authority for exact, versioned legacy assets used by a migration. */
public interface WorldMigrationSource {
    WorldMigrationInput read(Access access, Request request);

    record Access(String tenantId, String actorId, String purpose) {
        public Access {
            tenantId = MigrationSupport.text(tenantId);
            actorId = MigrationSupport.text(actorId);
            purpose = MigrationSupport.text(purpose);
        }
    }

    record Request(String fixtureBundleId, long fixtureRevision, String fixtureFingerprint,
                   String suiteId, long suiteRevision, String suiteFingerprint,
                   String targetFingerprint) {
        public Request {
            fixtureBundleId = MigrationSupport.text(fixtureBundleId);
            suiteId = MigrationSupport.text(suiteId);
            fixtureFingerprint = MigrationSupport.fingerprint(fixtureFingerprint);
            suiteFingerprint = MigrationSupport.fingerprint(suiteFingerprint);
            targetFingerprint = MigrationSupport.fingerprint(targetFingerprint);
            if (fixtureRevision < 1 || suiteRevision < 1) {
                throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
            }
        }
    }
}
