package com.leanowtech.bloge.gateway.testkit.ept;

/**
 * Package-private lifecycle states for EPT execute.
 *
 * <p>These are internal states, not exposed as CLI parameters or public outcomes.</p>
 *
 * <ul>
 *   <li>PREPARED: active/ created; owner + header durable</li>
 *   <li>LEASE_COMMITTED: lock + epoch acquired; bounded child executing</li>
 *   <li>LOCAL_COMMITTED: active→committed/ atomic rename durable; B0 installed</li>
 *   <li>EXTERNAL_PENDING: B0 durable; B1 request in-flight</li>
 *   <li>COMPLETE: B0 + B1 + R1 all durable</li>
 *   <li>ABORTED: durable abort closure present; explicit recovery required</li>
 * </ul>
 */
enum LifecycleState {
    PREPARED,
    LEASE_COMMITTED,
    LOCAL_COMMITTED,
    EXTERNAL_PENDING,
    COMPLETE,
    ABORTED
}
