package com.leanowtech.bloge.examples.common;

import com.leanowtech.bloge.ext.engine.SessionSnapshotCallback;
import com.leanowtech.bloge.ext.model.SessionStateSnapshot;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory snapshot sink used by example applications and tests.
 *
 * <p>The examples now stay entirely on the pure {@code bloge-session-ext} API by collecting
 * {@link SessionStateSnapshot} values emitted through {@link SessionSnapshotCallback} instead of
 * depending on the removed legacy checkpoint/store types.</p>
 */
public final class ExampleSessionSnapshotStore implements SessionSnapshotCallback {

    private final ConcurrentHashMap<String, SessionStateSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void onSnapshot(SessionStateSnapshot snapshot, Map<String, Object> contextData) {
        snapshots.put(snapshot.sessionId(), snapshot);
    }

    /**
     * Returns the latest captured snapshot for the given session.
     *
     * @param sessionId session identifier
     * @return latest snapshot when one has been captured
     */
    public Optional<SessionStateSnapshot> load(String sessionId) {
        return Optional.ofNullable(snapshots.get(sessionId));
    }
}
