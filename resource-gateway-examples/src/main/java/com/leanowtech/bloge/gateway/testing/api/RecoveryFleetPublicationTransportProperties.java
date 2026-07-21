package com.leanowtech.bloge.gateway.testing.api;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Strict public configuration for one recovery-fleet publication source transport.
 *
 * <p>Password values are deliberately absent. Keystore credentials are represented only by opaque
 * secret references and are resolved while constructing the immutable TLS context. A disabled
 * transport rejects all residual settings so profile mistakes cannot silently fall back to JVM
 * trust. Enabled mode always combines PKIX, hostname verification, SPKI pinning, and mutual TLS.</p>
 *
 * @param enabled selects pinned mutual TLS instead of the compatibility system-trust adapter
 * @param required prevents a deployment profile from downgrading this source to system trust
 * @param trustStorePath optional absolute private PKCS#12 trust-store path
 * @param trustStorePasswordRef opaque secret reference required with a private trust store
 * @param clientKeyStorePath absolute PKCS#12 client-identity path
 * @param clientKeyStorePasswordRef opaque client-keystore credential reference
 * @param serverSpkiPins comma-separated canonical SHA-256 SubjectPublicKeyInfo pins
 * @param certificateIdentityRequired prevents an enabled transport from omitting workload binding
 * @param expectedClientSubjectDn exact client certificate subject distinguished name
 * @param expectedClientUriSan exact client workload URI subject alternative name
 * @param clientIssuerSpkiPins comma-separated accepted client issuer SPKI pins
 * @param expectedServerUriSan exact server workload URI subject alternative name
 * @param serverIssuerSpkiPins comma-separated accepted server trust-anchor SPKI pins
 */
public record RecoveryFleetPublicationTransportProperties(
        Boolean enabled,
        Boolean required,
        String trustStorePath,
        String trustStorePasswordRef,
        String clientKeyStorePath,
        String clientKeyStorePasswordRef,
        String serverSpkiPins,
        Boolean certificateIdentityRequired,
        String expectedClientSubjectDn,
        String expectedClientUriSan,
        String clientIssuerSpkiPins,
        String expectedServerUriSan,
        String serverIssuerSpkiPins) {

    /** Normalizes public values and rejects disabled, partial, or downgrade-prone settings. */
    public RecoveryFleetPublicationTransportProperties {
        enabled = Boolean.TRUE.equals(enabled);
        required = Boolean.TRUE.equals(required);
        trustStorePath = normalized(trustStorePath);
        trustStorePasswordRef = normalized(trustStorePasswordRef);
        clientKeyStorePath = normalized(clientKeyStorePath);
        clientKeyStorePasswordRef = normalized(clientKeyStorePasswordRef);
        serverSpkiPins = normalized(serverSpkiPins);
        certificateIdentityRequired = Boolean.TRUE.equals(certificateIdentityRequired);
        expectedClientSubjectDn = normalized(expectedClientSubjectDn);
        expectedClientUriSan = normalized(expectedClientUriSan);
        clientIssuerSpkiPins = normalized(clientIssuerSpkiPins);
        expectedServerUriSan = normalized(expectedServerUriSan);
        serverIssuerSpkiPins = normalized(serverIssuerSpkiPins);
        boolean privateTrustPartial = trustStorePath.isBlank()
                != trustStorePasswordRef.isBlank();
        boolean identityConfigured = configured(expectedClientSubjectDn,
                expectedClientUriSan, clientIssuerSpkiPins, expectedServerUriSan,
                serverIssuerSpkiPins);
        boolean identityComplete = !expectedClientSubjectDn.isBlank()
                && !expectedClientUriSan.isBlank() && !clientIssuerSpkiPins.isBlank()
                && !expectedServerUriSan.isBlank() && !serverIssuerSpkiPins.isBlank();
        if (required && !enabled
                || !enabled && configured(trustStorePath, trustStorePasswordRef,
                clientKeyStorePath, clientKeyStorePasswordRef, serverSpkiPins,
                expectedClientSubjectDn, expectedClientUriSan, clientIssuerSpkiPins,
                expectedServerUriSan, serverIssuerSpkiPins)
                || !enabled && certificateIdentityRequired
                || enabled && (privateTrustPartial || clientKeyStorePath.isBlank()
                || clientKeyStorePasswordRef.isBlank() || serverSpkiPins.isBlank())
                || certificateIdentityRequired && !identityComplete
                || identityConfigured && !identityComplete) {
            throw invalid();
        }
    }

    /**
     * Builds the immutable transport and resolves each credential for only its load window.
     *
     * @param secretResolver unique deployment secret resolver
     * @return pinned mutual-TLS adapter, or the explicit compatibility adapter when disabled
     */
    public RecoveryFleetPublicationTransport create(
            RecoveryFleetPublicationTransport.SecretResolver secretResolver) {
        if (!enabled) {
            return new SystemTrustRecoveryFleetPublicationTransport();
        }
        return new PinnedMutualTlsRecoveryFleetPublicationTransport(
                pinnedSettings(),
                Objects.requireNonNull(secretResolver, "secretResolver"));
    }

    /**
     * Returns complete public-only settings for an enabled pinned transport.
     *
     * <p>The value retains opaque credential references but never resolved password characters.
     * Rotation runtimes use it to load immutable generations and compute material fingerprints.</p>
     *
     * @return validated pinned mutual-TLS settings
     */
    public PinnedMutualTlsRecoveryFleetPublicationTransport.Settings pinnedSettings() {
        if (!enabled) {
            throw invalid();
        }
        return new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                path(trustStorePath), trustStorePasswordRef,
                path(clientKeyStorePath), clientKeyStorePasswordRef,
                pins(serverSpkiPins), identityPolicy());
    }

    /** @return whether any non-default field was supplied */
    public boolean configured() {
        return required || enabled || configured(trustStorePath, trustStorePasswordRef,
                clientKeyStorePath, clientKeyStorePasswordRef, serverSpkiPins,
                expectedClientSubjectDn, expectedClientUriSan, clientIssuerSpkiPins,
                expectedServerUriSan, serverIssuerSpkiPins)
                || certificateIdentityRequired;
    }

    /** @return whether exact client and server workload identities are configured */
    public boolean certificateIdentityBound() {
        return !expectedClientSubjectDn.isBlank();
    }

    /** Returns the canonical disabled compatibility policy. */
    public static RecoveryFleetPublicationTransportProperties disabled() {
        return new RecoveryFleetPublicationTransportProperties(
                false, false, "", "", "", "", "", false,
                "", "", "", "", "");
    }

    /**
     * Checks whether two sources reuse the exact client-keystore and credential reference.
     *
     * @param other second source policy
     * @return true only when both enabled sources share one client identity configuration
     */
    public boolean sharesClientIdentityWith(
            RecoveryFleetPublicationTransportProperties other) {
        RecoveryFleetPublicationTransportProperties compared = Objects.requireNonNull(
                other, "other");
        return enabled && compared.enabled
                && clientKeyStorePath.equals(compared.clientKeyStorePath)
                && clientKeyStorePasswordRef.equals(compared.clientKeyStorePasswordRef);
    }

    private ControlPlaneCertificateIdentityPolicy identityPolicy() {
        if (!certificateIdentityBound()) {
            return ControlPlaneCertificateIdentityPolicy.unbound();
        }
        return new ControlPlaneCertificateIdentityPolicy(
                expectedClientSubjectDn, expectedClientUriSan, pins(clientIssuerSpkiPins),
                expectedServerUriSan, pins(serverIssuerSpkiPins));
    }

    private static Set<String> pins(String configured) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : configured.split(",", -1)) {
            if (!result.add(value.trim())) {
                throw invalid();
            }
        }
        return Set.copyOf(result);
    }

    private static Path path(String value) {
        return value.isBlank() ? null : Path.of(value);
    }

    private static boolean configured(String... values) {
        for (String value : values) {
            if (!value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Recovery-fleet publication transport configuration is invalid");
    }
}
