package com.leanowtech.bloge.gateway.testing.world.migration;

/** CAS boundary for writing unpublished migration drafts; it has no publication operation. */
public interface WorldMigrationDraftSink {
    Commit save(WorldMigrationSource.Access access, WorldMigrationDraftPackage draft,
                String expectedFingerprint);

    record Commit(boolean created, String packageFingerprint) {
        public Commit {
            if (packageFingerprint == null || !MigrationSupport.FINGERPRINT.matcher(packageFingerprint).matches()) {
                throw MigrationSupport.fail(WorldMigrationException.Code.SINK_FAILURE);
            }
        }
    }
}
