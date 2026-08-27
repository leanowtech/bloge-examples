package com.leanowtech.bloge.gateway.testing.world.migration;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Test-only idempotent CAS sink. It stores only immutable, unpublished projections. */
final class InMemoryWorldMigrationDraftSink implements WorldMigrationDraftSink {
    private final Map<String, WorldMigrationDraftPackage> values = new HashMap<>();
    private boolean fail;

    public synchronized void failNextWrite() {
        fail = true;
    }

    @Override
    public synchronized Commit save(WorldMigrationSource.Access access, WorldMigrationDraftPackage draft,
                                    String expectedFingerprint) {
        if (access == null || draft == null || !access.tenantId().equals(draft.tenantId())
                || expectedFingerprint == null || !expectedFingerprint.equals(draft.fingerprint())) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SINK_CONFLICT);
        }
        if (fail) {
            fail = false;
            throw MigrationSupport.fail(WorldMigrationException.Code.SINK_FAILURE);
        }
        String key = access.tenantId() + "\u0000" + draft.fingerprint();
        WorldMigrationDraftPackage previous = values.putIfAbsent(key, draft);
        if (previous != null && !previous.equals(draft)) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SINK_CONFLICT);
        }
        return new Commit(previous == null, draft.fingerprint());
    }

    public synchronized Optional<WorldMigrationDraftPackage> find(String tenantId, String fingerprint) {
        if (tenantId == null || fingerprint == null) return Optional.empty();
        return Optional.ofNullable(values.get(tenantId + "\u0000" + fingerprint));
    }
}
