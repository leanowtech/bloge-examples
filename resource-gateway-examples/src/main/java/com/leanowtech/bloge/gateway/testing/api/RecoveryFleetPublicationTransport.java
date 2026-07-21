package com.leanowtech.bloge.gateway.testing.api;

/**
 * Creates bounded HTTP clients for recovery-fleet publication sources.
 *
 * <p>This compatibility specialization preserves the original recovery-fleet API while inheriting
 * the reusable {@link ControlPlaneHttpTransport} contract. New protocol adapters should depend on
 * the general contract; recovery-fleet consumers may retain this narrower type.</p>
 */
public interface RecoveryFleetPublicationTransport extends ControlPlaneHttpTransport {
}
