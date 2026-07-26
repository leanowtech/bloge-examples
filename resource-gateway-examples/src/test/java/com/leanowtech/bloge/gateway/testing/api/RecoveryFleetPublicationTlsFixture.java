package com.leanowtech.bloge.gateway.testing.api;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reusable real-TLS fixture for control-plane and isolated provider certification tests.
 *
 * <p>The fixture is test-only. Public visibility lets protocol tests in another package reuse the
 * same private-CA, mutual-TLS, SPKI, and workload-identity material without copying certificate
 * generation code into each trust domain.</p>
 */
public final class RecoveryFleetPublicationTlsFixture {

    private static final char[] PASSWORD = "test-transport-password".toCharArray();

    private RecoveryFleetPublicationTlsFixture() {
    }

    /** Returns a caller-owned copy of the deterministic test-store password. */
    public static char[] password() {
        return PASSWORD.clone();
    }

    /**
     * Loads one server identity and private trust root for an isolated child process.
     *
     * @param serverKeyStore PKCS#12 server identity
     * @param trustStore PKCS#12 client trust root
     * @return mutual-TLS server context
     */
    public static SSLContext serverContext(
            Path serverKeyStore,
            Path trustStore) throws Exception {
        return serverContext(serverKeyStore, trustStore, PASSWORD);
    }

    /**
     * Loads one server identity with an explicitly supplied store password.
     *
     * @param serverKeyStore PKCS#12 server identity
     * @param trustStore PKCS#12 client trust root
     * @param password caller-owned store password
     * @return mutual-TLS server context
     */
    public static SSLContext serverContext(
            Path serverKeyStore,
            Path trustStore,
            char[] password) throws Exception {
        char[] exactPassword = Objects.requireNonNull(
                password, "password").clone();
        KeyStore keys = Material.load(serverKeyStore, exactPassword);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, exactPassword);
        KeyStore trust = Material.load(trustStore, exactPassword);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(),
                new SecureRandom());
        return context;
    }

    static Server startPublication(Material material, AtomicReference<String> peer)
            throws Exception {
        return start(material, "/publication",
                "publication".getBytes(StandardCharsets.UTF_8), Map.of(), peer);
    }

    static Server start(
            Material material,
            String path,
            byte[] body,
            Map<String, String> headers,
            AtomicReference<String> peer) throws Exception {
        SSLContext context = material.serverContext();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(com.sun.net.httpserver.HttpsParameters parameters) {
                var ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                ssl.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                parameters.setSSLParameters(ssl);
            }
        });
        AtomicInteger requests = new AtomicInteger();
        server.createContext(path, exchange -> {
            try (exchange) {
                peer.set(((HttpsExchange) exchange).getSSLSession()
                        .getPeerPrincipal().getName());
                requests.incrementAndGet();
                headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        return new Server(server, URI.create(
                "https://localhost:" + server.getAddress().getPort() + path), requests);
    }

    static Server startRoutes(
            Material material,
            Map<String, Response> routes,
            AtomicReference<String> peer) throws Exception {
        SSLContext context = material.serverContext();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(com.sun.net.httpserver.HttpsParameters parameters) {
                var ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                ssl.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                parameters.setSSLParameters(ssl);
            }
        });
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            try (exchange) {
                peer.set(((HttpsExchange) exchange).getSSLSession()
                        .getPeerPrincipal().getName());
                requests.incrementAndGet();
                Response response = routes.get(exchange.getRequestURI().getPath());
                if (response == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                response.headers().forEach(
                        (name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(response.status(), response.body().length);
                exchange.getResponseBody().write(response.body());
            }
        });
        server.start();
        return new Server(server, URI.create(
                "https://localhost:" + server.getAddress().getPort()), requests);
    }

    record Response(int status, byte[] body, Map<String, String> headers) {
        Response {
            body = body == null ? new byte[0] : body.clone();
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record Server(HttpsServer server, URI uri, AtomicInteger requestCount)
            implements AutoCloseable {

        int requests() {
            return requestCount.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Complete private-CA material for one isolated test trust domain. */
    public record Material(
            Path serverKeyStore,
            Path clientKeyStore,
            Path trustStore,
            X509Certificate serverCertificate,
            KeyPair certificateAuthorityKey,
            X509Certificate certificateAuthority,
            KeyPair clientKey,
            X509Certificate clientCertificate,
            String serverUriSan,
            String clientUriSan) {

        /** Creates one server/client trust domain with exact URI SAN identities. */
        public static Material create(Path directory, String suffix) throws Exception {
            return create(directory, suffix, true);
        }

        static Material createWithoutClientExtendedKeyUsage(
                Path directory,
                String suffix) throws Exception {
            return create(directory, suffix, false);
        }

        private static Material create(
                Path directory,
                String suffix,
                boolean clientExtendedKeyUsage) throws Exception {
            Files.createDirectories(directory);
            String serverUriSan = "spiffe://bloge.test/control-plane/server/" + suffix;
            String clientUriSan = "spiffe://bloge.test/control-plane/client/" + suffix;
            KeyPair caKey = keyPair();
            X509Certificate ca = certificate("recovery-ca-" + suffix, caKey, null, null,
                    true, false, false, false, "");
            KeyPair serverKey = keyPair();
            X509Certificate server = certificate("localhost", serverKey, ca, caKey,
                    false, true, false, true, serverUriSan);
            KeyPair clientKey = keyPair();
            X509Certificate client = certificate("recovery-client-" + suffix, clientKey,
                    ca, caKey, false, false, true, clientExtendedKeyUsage, clientUriSan);

            Path serverStore = directory.resolve("server-" + suffix + ".p12");
            Path clientStore = directory.resolve("client-" + suffix + ".p12");
            Path trustStore = directory.resolve("trust-" + suffix + ".p12");
            writeKeyStore(serverStore, "server", serverKey, server, ca);
            writeKeyStore(clientStore, "client", clientKey, client, ca);
            writeTrustStore(trustStore, ca);
            return new Material(serverStore, clientStore, trustStore, server,
                    caKey, ca, clientKey, client, serverUriSan, clientUriSan);
        }

        /**
         * Issues a replacement server leaf under the same CA and URI SAN.
         *
         * <p>The replacement carries a fresh key pair while retaining the client identity and
         * trust store. This models a rolling leaf rotation without weakening the trust-domain or
         * workload-identity boundaries.</p>
         *
         * @param directory destination for the replacement PKCS#12 key store
         * @param suffix unique key-store and certificate label
         * @return copied material carrying the replacement server leaf and key
         */
        public Material rotateServer(Path directory, String suffix) throws Exception {
            Files.createDirectories(directory);
            KeyPair nextServerKey = keyPair();
            X509Certificate nextServer = certificate(
                    "localhost", nextServerKey, certificateAuthority,
                    certificateAuthorityKey, false, true, false, true, serverUriSan);
            Path nextServerStore = directory.resolve("server-" + suffix + ".p12");
            writeKeyStore(nextServerStore, "server", nextServerKey, nextServer,
                    certificateAuthority);
            return new Material(nextServerStore, clientKeyStore, trustStore, nextServer,
                    certificateAuthorityKey, certificateAuthority, clientKey,
                    clientCertificate, serverUriSan, clientUriSan);
        }

        /**
         * Issues a new client identity under the same CA while preserving the server identity.
         *
         * @return copied material carrying the replacement client key and URI SAN
         */
        public Material rotateClient(Path directory, String suffix) throws Exception {
            String nextClientUriSan = "spiffe://bloge.test/control-plane/client/" + suffix;
            KeyPair nextClientKey = keyPair();
            X509Certificate nextClient = certificate(
                    "recovery-client-" + suffix, nextClientKey, certificateAuthority,
                    certificateAuthorityKey, false, false, true, true, nextClientUriSan);
            Path nextClientStore = directory.resolve("client-" + suffix + ".p12");
            writeKeyStore(nextClientStore, "client", nextClientKey, nextClient,
                    certificateAuthority);
            return new Material(serverKeyStore, nextClientStore, trustStore,
                    serverCertificate, certificateAuthorityKey, certificateAuthority,
                    nextClientKey, nextClient, serverUriSan, nextClientUriSan);
        }

        Material addClientUriSan(Path directory, String suffix, String additionalUriSan)
                throws Exception {
            KeyPair nextClientKey = keyPair();
            X509Certificate nextClient = certificate(
                    "recovery-client-" + suffix, nextClientKey, certificateAuthority,
                    certificateAuthorityKey, false, false, true, true,
                    clientUriSan, additionalUriSan);
            Path nextClientStore = directory.resolve("client-" + suffix + ".p12");
            writeKeyStore(nextClientStore, "client", nextClientKey, nextClient,
                    certificateAuthority);
            return new Material(serverKeyStore, nextClientStore, trustStore,
                    serverCertificate, certificateAuthorityKey, certificateAuthority,
                    nextClientKey, nextClient, serverUriSan, clientUriSan);
        }

        private SSLContext serverContext() throws Exception {
            return RecoveryFleetPublicationTlsFixture.serverContext(
                    serverKeyStore, trustStore);
        }

        private static KeyStore load(
                Path path,
                char[] password) throws Exception {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(path)) {
                store.load(input, password);
            }
            return store;
        }

        private static void writeKeyStore(
                Path path,
                String alias,
                KeyPair key,
                X509Certificate certificate,
                X509Certificate ca) throws Exception {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, PASSWORD);
            store.setKeyEntry(alias, key.getPrivate(), PASSWORD,
                    new java.security.cert.Certificate[]{certificate, ca});
            try (var output = Files.newOutputStream(path)) {
                store.store(output, PASSWORD);
            }
        }

        private static void writeTrustStore(Path path, X509Certificate ca) throws Exception {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, PASSWORD);
            store.setCertificateEntry("ca", ca);
            try (var output = Files.newOutputStream(path)) {
                store.store(output, PASSWORD);
            }
        }

        private static KeyPair keyPair() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }

        private static X509Certificate certificate(
                String commonName,
                KeyPair subjectKey,
                X509Certificate issuerCertificate,
                KeyPair issuerKey,
                boolean certificateAuthority,
                boolean server,
                boolean client,
                boolean includeExtendedKeyUsage,
                String... uriSans) throws Exception {
            X500Name subject = new X500Name("CN=" + commonName);
            X500Name issuer = issuerCertificate == null
                    ? subject : new X500Name(issuerCertificate.getSubjectX500Principal().getName());
            KeyPair signerKey = issuerKey == null ? subjectKey : issuerKey;
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            var builder = new JcaX509v3CertificateBuilder(
                    issuer, new BigInteger(160, new SecureRandom()),
                    Date.from(now.minus(1, ChronoUnit.DAYS)),
                    Date.from(now.plus(2, ChronoUnit.DAYS)), subject, subjectKey.getPublic());
            JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, true,
                    new BasicConstraints(certificateAuthority));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    extensions.createSubjectKeyIdentifier(subjectKey.getPublic()));
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                    extensions.createAuthorityKeyIdentifier(
                            issuerCertificate == null ? subjectKey.getPublic()
                                    : issuerCertificate.getPublicKey()));
            if (certificateAuthority) {
                builder.addExtension(Extension.keyUsage, true,
                        new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            } else {
                builder.addExtension(Extension.keyUsage, true,
                        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
                if (server && includeExtendedKeyUsage) {
                    builder.addExtension(Extension.extendedKeyUsage, false,
                            new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
                }
                if (server) {
                    List<GeneralName> subjectNames = new java.util.ArrayList<>(List.of(
                            new GeneralName(GeneralName.dNSName, "localhost"),
                            new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
                    for (String uriSan : uriSans) {
                        subjectNames.add(new GeneralName(
                                GeneralName.uniformResourceIdentifier, uriSan));
                    }
                    builder.addExtension(Extension.subjectAlternativeName, false,
                            new GeneralNames(subjectNames.toArray(GeneralName[]::new)));
                } else if (client && includeExtendedKeyUsage) {
                    builder.addExtension(Extension.extendedKeyUsage, false,
                            new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
                }
                if (client) {
                    GeneralName[] subjectNames = java.util.Arrays.stream(uriSans)
                            .map(uriSan -> new GeneralName(
                                    GeneralName.uniformResourceIdentifier, uriSan))
                            .toArray(GeneralName[]::new);
                    builder.addExtension(Extension.subjectAlternativeName, false,
                            new GeneralNames(subjectNames));
                }
            }
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                            .build(signerKey.getPrivate())));
            certificate.checkValidity();
            certificate.verify(issuerCertificate == null
                    ? subjectKey.getPublic() : issuerCertificate.getPublicKey());
            return certificate;
        }
    }
}
