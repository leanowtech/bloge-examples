package com.leanowtech.bloge.gateway.testing.runtime;

import java.util.Map;

/**
 * Optional composability contract for configured operators that need certifiable micro-graph tests.
 *
 * <p>An implementation returns only deterministic, credential-free configuration facts that affect
 * behavior. The control plane fingerprints the map but never returns or stores its values. Secrets
 * must be represented by stable opaque key/version references, never by credential material.</p>
 */
public interface OperatorRuntimeBindingSnapshotProvider {

    /**
     * Returns a bounded JSON-serializable snapshot of behavior-relevant binding configuration.
     * Implementations must be deterministic, non-blocking and free of network, filesystem or
     * other external I/O because target discovery invokes this method synchronously.
     *
     * @return immutable-style configuration facts; maximum encoded size is 64 KiB
     */
    Map<String, ?> runtimeBindingSnapshot();
}
