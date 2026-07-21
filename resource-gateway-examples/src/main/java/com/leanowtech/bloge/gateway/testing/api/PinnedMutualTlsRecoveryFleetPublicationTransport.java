package com.leanowtech.bloge.gateway.testing.api;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticates recovery-fleet publication sources with SPKI pinning and mutual TLS.
 *
 * <p>The adapter first performs normal PKIX validation against either a deployment-owned PKCS#12
 * trust store or the JVM system roots, then requires at least one certificate in the verified chain
 * to match the configured SHA-256 SubjectPublicKeyInfo pin set. It also loads one deployment-owned
 * PKCS#12 client identity. Passwords are obtained only through opaque secret references, retained as
 * characters for the minimum initialization window, and erased in every success or failure path.</p>
 *
 * <p>Hostname verification remains enabled by the JDK HTTP client. Pins supplement PKIX and hostname
 * verification; they never replace either check.</p>
 */
public final class PinnedMutualTlsRecoveryFleetPublicationTransport
        implements RecoveryFleetPublicationTransport {

    private static final String KEY_STORE_TYPE = "PKCS12";
    private static final Pattern PIN = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern SECRET_SCHEME = Pattern.compile("[a-z][a-z0-9+.-]{1,31}");

    private final SSLContext sslContext;
    private final Descriptor descriptor;

    /**
     * Loads and freezes one pinned mutual-TLS transport.
     *
     * @param settings public paths, opaque credential references, and accepted SPKI pins
     * @param secretResolver credential resolver returning caller-owned characters
     */
    public PinnedMutualTlsRecoveryFleetPublicationTransport(
            Settings settings,
            SecretResolver secretResolver) {
        Settings required = Objects.requireNonNull(settings, "settings").validated();
        SecretResolver resolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.sslContext = buildContext(required, resolver);
        this.descriptor = new Descriptor(Descriptor.SCHEMA_VERSION,
                required.trustStorePath() == null,
                required.trustStorePath() != null, true, true);
    }

    /** {@inheritDoc} */
    @Override
    public HttpClient client(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(SystemTrustRecoveryFleetPublicationTransport.bounded(
                        connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(sslContext)
                .sslParameters(SystemTrustRecoveryFleetPublicationTransport.httpsParameters())
                .build();
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    /**
     * Public-only transport settings. Credential values are deliberately absent.
     *
     * @param trustStorePath optional absolute deployment-owned PKCS#12 trust-store path
     * @param trustStorePasswordRef opaque password reference, required with a trust store
     * @param clientKeyStorePath absolute deployment-owned PKCS#12 client-key path
     * @param clientKeyStorePasswordRef opaque client-key-store password reference
     * @param serverSpkiPins one to sixteen canonical SHA-256 SPKI pins
     */
    public record Settings(
            Path trustStorePath,
            String trustStorePasswordRef,
            Path clientKeyStorePath,
            String clientKeyStorePasswordRef,
            Set<String> serverSpkiPins) {

        /** Validates and canonicalizes all public transport configuration. */
        public Settings {
            trustStorePath = normalizedPath(trustStorePath, true);
            trustStorePasswordRef = secretReference(trustStorePasswordRef,
                    trustStorePath != null);
            clientKeyStorePath = normalizedPath(clientKeyStorePath, false);
            clientKeyStorePasswordRef = secretReference(clientKeyStorePasswordRef, true);
            LinkedHashSet<String> pins = new LinkedHashSet<>();
            if (serverSpkiPins != null) {
                for (String pin : serverSpkiPins) {
                    String normalized = Objects.requireNonNullElse(pin, "").trim()
                            .toLowerCase(Locale.ROOT);
                    if (!PIN.matcher(normalized).matches() || !pins.add(normalized)) {
                        throw invalid();
                    }
                }
            }
            if (pins.isEmpty() || pins.size() > 16) {
                throw invalid();
            }
            serverSpkiPins = Set.copyOf(pins);
        }

        /** @return this already validated immutable value */
        public Settings validated() {
            return this;
        }

        private static Path normalizedPath(Path path, boolean optional) {
            if (path == null && optional) {
                return null;
            }
            if (path == null) {
                throw invalid();
            }
            Path normalized = path.normalize();
            if (!normalized.isAbsolute() || !Files.isRegularFile(normalized)
                    || !Files.isReadable(normalized)) {
                throw invalid();
            }
            return normalized;
        }

        private static String secretReference(String value, boolean required) {
            String normalized = Objects.requireNonNullElse(value, "").trim();
            if (!required && normalized.isEmpty()) {
                return "";
            }
            try {
                URI reference = URI.create(normalized);
                if (normalized.length() > 512 || !reference.isAbsolute()
                        || !reference.isOpaque() || reference.getScheme() == null
                        || !SECRET_SCHEME.matcher(reference.getScheme()).matches()
                        || reference.getRawSchemeSpecificPart() == null
                        || reference.getRawSchemeSpecificPart().isBlank()
                        || reference.getRawFragment() != null) {
                    throw invalid();
                }
            } catch (IllegalArgumentException invalid) {
                throw invalid();
            }
            return normalized;
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "Pinned recovery-fleet publication transport configuration is invalid");
        }
    }

    /** Resolves only {@code env:VARIABLE_NAME} references without caching secret values. */
    public static final class EnvironmentSecretResolver implements SecretResolver {
        private static final Pattern VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

        /** Creates one stateless environment resolver. */
        public EnvironmentSecretResolver() {
        }

        /** {@inheritDoc} */
        @Override
        public char[] resolve(String reference) {
            URI parsed;
            try {
                parsed = URI.create(Objects.requireNonNullElse(reference, ""));
            } catch (IllegalArgumentException invalid) {
                throw unavailable();
            }
            String variable = parsed.getRawSchemeSpecificPart();
            if (!"env".equals(parsed.getScheme()) || variable == null
                    || !VARIABLE.matcher(variable).matches()) {
                throw unavailable();
            }
            String value = System.getenv(variable);
            if (value == null || value.isEmpty()) {
                throw unavailable();
            }
            return value.toCharArray();
        }

        private static IllegalStateException unavailable() {
            return new IllegalStateException(
                    "Recovery-fleet publication transport credential is unavailable");
        }
    }

    private static SSLContext buildContext(Settings settings, SecretResolver resolver) {
        char[] trustPassword = null;
        char[] clientPassword = null;
        try {
            TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            if (settings.trustStorePath() == null) {
                trustFactory.init((KeyStore) null);
            } else {
                trustPassword = requiredSecret(resolver, settings.trustStorePasswordRef());
                KeyStore trustStore = loadStore(settings.trustStorePath(), trustPassword);
                if (trustStore.size() == 0) {
                    throw invalidMaterial();
                }
                trustFactory.init(trustStore);
            }
            X509ExtendedTrustManager baseTrust = x509TrustManager(
                    trustFactory.getTrustManagers());
            TrustManager[] trustManagers = new TrustManager[]{
                    new PinningTrustManager(baseTrust, settings.serverSpkiPins())};

            clientPassword = requiredSecret(resolver, settings.clientKeyStorePasswordRef());
            KeyStore clientStore = loadStore(settings.clientKeyStorePath(), clientPassword);
            if (!containsPrivateKey(clientStore)) {
                throw invalidMaterial();
            }
            KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyFactory.init(clientStore, clientPassword);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyFactory.getKeyManagers(), trustManagers, new SecureRandom());
            return context;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Pinned recovery-fleet publication transport material is unavailable",
                    failure);
        } finally {
            erase(trustPassword);
            erase(clientPassword);
        }
    }

    private static char[] requiredSecret(SecretResolver resolver, String reference) {
        char[] resolved = resolver.resolve(reference);
        if (resolved == null || resolved.length == 0) {
            erase(resolved);
            throw new IllegalStateException(
                    "Recovery-fleet publication transport credential is unavailable");
        }
        return resolved;
    }

    private static KeyStore loadStore(Path path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance(KEY_STORE_TYPE);
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private static boolean containsPrivateKey(KeyStore store) throws Exception {
        var aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            if (store.isKeyEntry(aliases.nextElement())) {
                return true;
            }
        }
        return false;
    }

    private static X509ExtendedTrustManager x509TrustManager(TrustManager[] managers) {
        for (TrustManager manager : managers) {
            if (manager instanceof X509ExtendedTrustManager x509) {
                return x509;
            }
        }
        throw invalidMaterial();
    }

    private static IllegalArgumentException invalidMaterial() {
        return new IllegalArgumentException(
                "Pinned recovery-fleet publication transport material is invalid");
    }

    private static void erase(char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }

    private static final class PinningTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final Set<String> pins;

        private PinningTrustManager(X509ExtendedTrustManager delegate, Set<String> pins) {
            this.delegate = delegate;
            this.pins = pins;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            requirePin(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, socket);
            requirePin(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, engine);
            requirePin(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers().clone();
        }

        private void requirePin(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Server certificate chain is unavailable");
            }
            for (X509Certificate certificate : chain) {
                if (pins.contains(spkiPin(certificate))) {
                    return;
                }
            }
            throw new CertificateException("Server certificate SPKI pin is not accepted");
        }
    }

    /**
     * Computes the canonical SHA-256 pin for one certificate public key.
     *
     * @param certificate certificate whose SubjectPublicKeyInfo is pinned
     * @return lowercase {@code sha256:} fingerprint
     */
    public static String spkiPin(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    Objects.requireNonNull(certificate, "certificate")
                            .getPublicKey().getEncoded());
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Certificate SPKI pin cannot be computed", failure);
        }
    }
}
