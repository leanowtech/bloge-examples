package com.leanowtech.bloge.gateway.testkit.ept;

/**
 * Closed failure category for EPT CLOSED outcomes.
 *
 * <p>Synchronized with ENGINE-DESIGN §473:</p>
 * <ul>
 *   <li>INVALID: caller configuration or pin mismatch</li>
 *   <li>CONFLICT: same stableRequestId + different boundedChildDigest</li>
 *   <li>UNAVAILABLE: Store 503, timeout, receipt mismatch</li>
 *   <li>BLOCKED: missing required authority, capability incompatible, capacity exceeded</li>
 *   <li>ABORTED: durable abort closure present; explicit recovery required</li>
 *   <li>INTERNAL: unexpected internal failure</li>
 * </ul>
 */
public enum ClosedCategory {
    /** Caller configuration or pin mismatch. */
    INVALID,
    /** Same stableRequestId + different boundedChildDigest. */
    CONFLICT,
    /** Store 503, timeout, or receipt mismatch. */
    UNAVAILABLE,
    /** Missing authority, incompatible capability, or capacity exceeded. */
    BLOCKED,
    /** Durable abort closure present; explicit recovery required. */
    ABORTED,
    /** Unexpected internal failure. */
    INTERNAL
}
