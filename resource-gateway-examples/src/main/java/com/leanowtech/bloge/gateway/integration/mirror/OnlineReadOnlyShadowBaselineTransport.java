package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.api.ControlPlaneHttpTransport;

/**
 * Role-separated private-PKI transport dedicated to one online baseline sidecar authority.
 *
 * <p>The separate type prevents a general control-plane client identity from being silently reused
 * across independently governed TEE regions.</p>
 */
public interface OnlineReadOnlyShadowBaselineTransport
        extends ControlPlaneHttpTransport {

    /**
     * Adapts one exact transport after deployment code has assigned it to this trust domain.
     *
     * @param delegate exact private-PKI transport
     * @return role-separated online baseline transport
     */
    static OnlineReadOnlyShadowBaselineTransport from(
            ControlPlaneHttpTransport delegate) {
        java.util.Objects.requireNonNull(
                delegate, "delegate");
        return new OnlineReadOnlyShadowBaselineTransport() {
            @Override
            public java.net.http.HttpClient client(
                    java.time.Duration connectTimeout) {
                return delegate.client(connectTimeout);
            }

            @Override
            public Descriptor descriptor() {
                return delegate.descriptor();
            }

            @Override
            public boolean certificateIdentityBound() {
                return delegate.certificateIdentityBound();
            }
        };
    }
}
