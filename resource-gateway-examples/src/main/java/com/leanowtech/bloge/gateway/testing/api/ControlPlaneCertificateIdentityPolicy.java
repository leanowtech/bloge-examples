package com.leanowtech.bloge.gateway.testing.api;

import javax.security.auth.x500.X500Principal;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Binds a control-plane TLS connection to exact client and server workload identities.
 *
 * <p>PKIX, hostname verification, and leaf-key pinning answer whether a certificate is trusted for
 * one network endpoint. This policy closes the separate authorization question: whether the local
 * key is the intended client workload and whether the remote leaf is the intended server workload.
 * A bound policy requires exact client subject and URI SAN values, an exact server URI SAN, role
 * EKUs, digital-signature key usage, and independently pinned client/server issuing authorities.
 * It contains public certificate selectors only and is safe to retain with transport metadata.</p>
 *
 * @param expectedClientSubjectDn exact RFC 2253 client subject distinguished name
 * @param expectedClientUriSan exact absolute URI subject alternative name for the client workload
 * @param clientIssuerSpkiPins accepted SHA-256 SPKI pins for the client identity chain
 * @param expectedServerUriSan exact absolute URI subject alternative name for the server workload
 * @param serverIssuerSpkiPins accepted SHA-256 SPKI pins for server PKIX trust anchors
 */
public record ControlPlaneCertificateIdentityPolicy(
        String expectedClientSubjectDn,
        String expectedClientUriSan,
        Set<String> clientIssuerSpkiPins,
        String expectedServerUriSan,
        Set<String> serverIssuerSpkiPins) {

    private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";
    private static final String SERVER_AUTH_EKU = "1.3.6.1.5.5.7.3.1";
    private static final int URI_SAN = 6;
    private static final Pattern PIN = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern URI_SCHEME = Pattern.compile("[a-z][a-z0-9+.-]{1,31}");

    /** Canonicalizes a complete policy or the single all-empty compatibility policy. */
    public ControlPlaneCertificateIdentityPolicy {
        expectedClientSubjectDn = normalized(expectedClientSubjectDn);
        expectedClientUriSan = normalized(expectedClientUriSan);
        expectedServerUriSan = normalized(expectedServerUriSan);
        clientIssuerSpkiPins = pins(clientIssuerSpkiPins);
        serverIssuerSpkiPins = pins(serverIssuerSpkiPins);
        boolean empty = expectedClientSubjectDn.isEmpty() && expectedClientUriSan.isEmpty()
                && clientIssuerSpkiPins.isEmpty() && expectedServerUriSan.isEmpty()
                && serverIssuerSpkiPins.isEmpty();
        if (!empty) {
            if (expectedClientSubjectDn.isEmpty() || expectedClientUriSan.isEmpty()
                    || clientIssuerSpkiPins.isEmpty() || expectedServerUriSan.isEmpty()
                    || serverIssuerSpkiPins.isEmpty()) {
                throw invalid();
            }
            expectedClientSubjectDn = subject(expectedClientSubjectDn);
            expectedClientUriSan = uriSan(expectedClientUriSan);
            expectedServerUriSan = uriSan(expectedServerUriSan);
        }
    }

    /** Returns the explicit compatibility policy with no workload-identity assertions. */
    public static ControlPlaneCertificateIdentityPolicy unbound() {
        return new ControlPlaneCertificateIdentityPolicy("", "", Set.of(), "", Set.of());
    }

    /** @return whether exact client and server workload identities are enforced */
    public boolean bound() {
        return !expectedClientSubjectDn.isEmpty();
    }

    /**
     * Verifies that a keystore contains exactly one usable and policy-bound client identity.
     *
     * @param keyStore loaded client PKCS#12 keystore
     * @throws CertificateException when key selection or certificate identity is ambiguous
     */
    void verifyClientKeyStore(KeyStore keyStore) throws CertificateException {
        verifyClientKeyStore(keyStore, Instant.now());
    }

    /**
     * Verifies one client identity at its declared transport activation instant.
     *
     * <p>The explicit instant lets a rotation controller pre-load a successor certificate before
     * it becomes active without weakening validity checking. Static transports continue to verify
     * against the current wall clock through {@link #verifyClientKeyStore(KeyStore)}.</p>
     *
     * @param keyStore loaded client PKCS#12 keystore
     * @param validAt instant at which the identity will first be eligible for requests
     * @throws CertificateException when the identity is ambiguous, invalid, or not usable then
     */
    void verifyClientKeyStore(KeyStore keyStore, Instant validAt) throws CertificateException {
        if (!bound()) {
            return;
        }
        try {
            Instant requiredInstant = Objects.requireNonNull(validAt, "validAt");
            String keyAlias = null;
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String candidate = aliases.nextElement();
                if (keyStore.isKeyEntry(candidate)) {
                    if (keyAlias != null) {
                        throw invalidCertificate();
                    }
                    keyAlias = candidate;
                }
            }
            if (keyAlias == null) {
                throw invalidCertificate();
            }
            Certificate[] stored = keyStore.getCertificateChain(keyAlias);
            if (stored == null || stored.length < 2) {
                throw invalidCertificate();
            }
            X509Certificate[] chain = new X509Certificate[stored.length];
            for (int index = 0; index < stored.length; index++) {
                if (!(stored[index] instanceof X509Certificate certificate)) {
                    throw invalidCertificate();
                }
                chain[index] = certificate;
            }
            X509Certificate leaf = chain[0];
            validateClientPath(chain, requiredInstant);
            requireRole(leaf, CLIENT_AUTH_EKU);
            if (!subject(leaf.getSubjectX500Principal().getName()).equals(
                    expectedClientSubjectDn)
                    || !hasUriSan(leaf, expectedClientUriSan)) {
                throw invalidCertificate();
            }
        } catch (CertificateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CertificateException("Client certificate identity is invalid", failure);
        }
    }

    /**
     * Returns only trust anchors admitted by the server issuer policy.
     *
     * @param acceptedIssuers PKIX trust-manager anchors
     * @return one or more issuer-pinned anchors, or the original anchors when unbound
     * @throws CertificateException when no configured issuer is trusted locally
     */
    X509Certificate[] admittedServerIssuers(X509Certificate[] acceptedIssuers)
            throws CertificateException {
        X509Certificate[] available = Objects.requireNonNullElseGet(
                acceptedIssuers, () -> new X509Certificate[0]);
        if (!bound()) {
            return available.clone();
        }
        List<X509Certificate> admitted = java.util.Arrays.stream(available)
                .filter(certificate -> serverIssuerSpkiPins.contains(
                        PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(certificate)))
                .filter(ControlPlaneCertificateIdentityPolicy::certificateAuthority)
                .toList();
        if (admitted.isEmpty()) {
            throw new CertificateException("Server certificate authority is not admitted");
        }
        return admitted.toArray(X509Certificate[]::new);
    }

    /** Verifies the workload role and URI SAN after constrained PKIX validation succeeds. */
    void verifyServerChain(X509Certificate[] chain) throws CertificateException {
        if (!bound()) {
            return;
        }
        if (chain == null || chain.length == 0) {
            throw invalidCertificate();
        }
        X509Certificate leaf = chain[0];
        requireRole(leaf, SERVER_AUTH_EKU);
        if (!hasUriSan(leaf, expectedServerUriSan)) {
            throw invalidCertificate();
        }
    }

    private static void requireRole(X509Certificate certificate, String requiredEku)
            throws CertificateException {
        boolean[] keyUsage = certificate.getKeyUsage();
        List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
        if (keyUsage == null || keyUsage.length == 0 || !keyUsage[0]
                || extendedKeyUsage == null || !extendedKeyUsage.contains(requiredEku)) {
            throw invalidCertificate();
        }
    }

    private static boolean hasUriSan(X509Certificate certificate, String expected)
            throws CertificateException {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        if (names == null) {
            return false;
        }
        int uriNames = 0;
        boolean matched = false;
        for (List<?> name : names) {
            if (name.size() >= 2 && Objects.equals(name.get(0), URI_SAN)
                    && name.get(1) instanceof String value) {
                uriNames++;
                try {
                    if (uriSan(value).equals(expected)) {
                        matched = true;
                    }
                } catch (IllegalArgumentException malformed) {
                    return false;
                }
            }
        }
        return matched && uriNames == 1;
    }

    private void validateClientPath(X509Certificate[] chain, Instant validAt)
            throws CertificateException {
        Exception lastFailure = null;
        for (int anchorIndex = 1; anchorIndex < chain.length; anchorIndex++) {
            X509Certificate anchor = chain[anchorIndex];
            if (!clientIssuerSpkiPins.contains(
                    PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(anchor))
                    || !certificateAuthority(anchor)) {
                continue;
            }
            try {
                anchor.checkValidity(Date.from(validAt));
                var pathCertificates = Arrays.asList(chain).subList(0, anchorIndex);
                var path = CertificateFactory.getInstance("X.509")
                        .generateCertPath(pathCertificates);
                var parameters = new PKIXParameters(Set.of(new TrustAnchor(anchor, null)));
                parameters.setDate(Date.from(validAt));
                parameters.setRevocationEnabled(false);
                CertPathValidator.getInstance("PKIX").validate(path, parameters);
                return;
            } catch (Exception invalidPath) {
                lastFailure = invalidPath;
            }
        }
        throw new CertificateException("Client certificate path is not admitted", lastFailure);
    }

    private static boolean certificateAuthority(X509Certificate certificate) {
        boolean[] keyUsage = certificate.getKeyUsage();
        return certificate.getBasicConstraints() >= 0 && keyUsage != null
                && keyUsage.length > 5 && keyUsage[5];
    }

    private static Set<String> pins(Set<String> configured) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (configured != null) {
            for (String value : configured) {
                String normalized = normalized(value).toLowerCase(Locale.ROOT);
                if (!PIN.matcher(normalized).matches() || !result.add(normalized)) {
                    throw invalid();
                }
            }
        }
        if (result.size() > 16) {
            throw invalid();
        }
        return Set.copyOf(result);
    }

    private static String subject(String value) {
        try {
            return new X500Principal(value).getName(X500Principal.CANONICAL);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static String uriSan(String value) {
        try {
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme();
            if (value.length() > 512 || scheme == null
                    || !URI_SCHEME.matcher(scheme.toLowerCase(Locale.ROOT)).matches()
                    || uri.getRawFragment() != null || uri.getRawQuery() != null
                    || uri.getRawUserInfo() != null) {
                throw invalid();
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static CertificateException invalidCertificate() {
        return new CertificateException("Control-plane certificate identity is not admitted");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate identity policy is invalid");
    }
}
