package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.api.ControlPlaneHttpTransport;

/**
 * Role-separated private-PKI transport dedicated to one online candidate sidecar authority.
 *
 * <p>The distinct type prevents the production-read baseline identity, a general control-plane
 * identity, or another candidate region from being reused without an explicit deployment
 * assignment.</p>
 */
public interface OnlineReadOnlyShadowCandidateTransport
        extends ControlPlaneHttpTransport {

    /**
     * Adapts one exact transport after deployment code has assigned it to this trust domain.
     *
     * @param delegate exact private-PKI transport
     * @return role-separated online candidate transport
     */
    static OnlineReadOnlyShadowCandidateTransport from(
            ControlPlaneHttpTransport delegate) {
        java.util.Objects.requireNonNull(
                delegate, "delegate");
        return new OnlineReadOnlyShadowCandidateTransport() {
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
