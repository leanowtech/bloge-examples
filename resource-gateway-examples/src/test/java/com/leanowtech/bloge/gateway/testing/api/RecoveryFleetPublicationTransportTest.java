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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryFleetPublicationTransportTest {

    private static final char[] PASSWORD = "test-transport-password".toCharArray();

    @TempDir
    private Path temporaryDirectory;

    @Test
    void pinnedMutualTlsAuthenticatesBothPeersAndDoesNotProjectSensitiveMaterial()
            throws Exception {
        TlsMaterial material = TlsMaterial.create(temporaryDirectory, "trusted");
        AtomicReference<String> peer = new AtomicReference<>();
        try (RunningServer server = RunningServer.start(material, peer)) {
            var transport = transport(material.clientKeyStore(), material.trustStore(),
                    material.serverCertificate());
            var response = transport.client(Duration.ofSeconds(2)).send(
                    HttpRequest.newBuilder(server.uri()).GET()
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-trusted");
            assertThat(transport.descriptor()).isEqualTo(
                    new RecoveryFleetPublicationTransport.Descriptor(
                            RecoveryFleetPublicationTransport.Descriptor.SCHEMA_VERSION,
                            false, true, true, true));
            assertThat(transport.descriptor().toString())
                    .doesNotContain(temporaryDirectory.toString(), "test:trust", "test:client",
                            PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                                    material.serverCertificate()));
        }
    }

    @Test
    void validPkixChainWithWrongSpkiPinFailsClosed() throws Exception {
        TlsMaterial material = TlsMaterial.create(temporaryDirectory, "pin-mismatch");
        try (RunningServer server = RunningServer.start(material, new AtomicReference<>())) {
            var settings = new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                    material.trustStore(), "test:trust", material.clientKeyStore(),
                    "test:client", Set.of("sha256:" + "0".repeat(64)));
            var transport = new PinnedMutualTlsRecoveryFleetPublicationTransport(
                    settings, secretResolver());

            assertThatThrownBy(() -> transport.client(Duration.ofSeconds(2)).send(
                    HttpRequest.newBuilder(server.uri()).GET()
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOfAny(java.io.IOException.class, InterruptedException.class)
                    .hasMessageNotContaining(PASSWORD.toString());
        }
    }

    @Test
    void untrustedClientIdentityFailsEvenWhenServerPinAndTrustAreValid() throws Exception {
        TlsMaterial serverMaterial = TlsMaterial.create(temporaryDirectory, "server-domain");
        TlsMaterial rogueMaterial = TlsMaterial.create(temporaryDirectory, "rogue-domain");
        try (RunningServer server = RunningServer.start(
                serverMaterial, new AtomicReference<>())) {
            var transport = transport(rogueMaterial.clientKeyStore(),
                    serverMaterial.trustStore(), serverMaterial.serverCertificate());

            assertThatThrownBy(() -> transport.client(Duration.ofSeconds(2)).send(
                    HttpRequest.newBuilder(server.uri()).GET()
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOfAny(java.io.IOException.class, InterruptedException.class);
        }
    }

    @Test
    void resolvedCredentialCharactersAreErasedAfterContextInitialization() throws Exception {
        TlsMaterial material = TlsMaterial.create(temporaryDirectory, "erase");
        List<char[]> issued = new ArrayList<>();
        var settings = settings(material.clientKeyStore(), material.trustStore(),
                material.serverCertificate());

        new PinnedMutualTlsRecoveryFleetPublicationTransport(settings, reference -> {
            char[] secret = PASSWORD.clone();
            issued.add(secret);
            return secret;
        });

        assertThat(issued).hasSize(2).allSatisfy(
                secret -> assertThat(secret).containsOnly('\0'));
    }

    @Test
    void publicSettingsRejectRawPartialRelativeAndDuplicateTrustConfiguration()
            throws Exception {
        Path file = Files.createFile(temporaryDirectory.resolve("placeholder.p12"));
        String pin = "sha256:" + "a".repeat(64);

        assertThatThrownBy(() -> newSettings(null, "", file, "", Set.of(pin)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSettings(null, "", Path.of("relative.p12"),
                "test:client", Set.of(pin)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSettings(file, "raw-password", file,
                "test:client", Set.of(pin)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSettings(null, "", file,
                "test:client", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSettings(null, "", file,
                "test:client", new java.util.AbstractSet<>() {
                    @Override
                    public java.util.Iterator<String> iterator() {
                        return List.of(pin, pin).iterator();
                    }

                    @Override
                    public int size() {
                        return 2;
                    }
                }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemTrustAdapterRemainsExplicitlyUnpinnedAndRejectsUnboundedTimeouts() {
        var transport = new SystemTrustRecoveryFleetPublicationTransport();

        assertThat(transport.descriptor()).isEqualTo(
                new RecoveryFleetPublicationTransport.Descriptor(
                        RecoveryFleetPublicationTransport.Descriptor.SCHEMA_VERSION,
                        true, false, false, false));
        assertThat(transport.client(Duration.ofSeconds(1)).followRedirects())
                .isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
        assertThatThrownBy(() -> transport.client(Duration.ofMillis(99)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.client(Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport transport(
            Path clientKeyStore,
            Path trustStore,
            X509Certificate serverCertificate) {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport(
                settings(clientKeyStore, trustStore, serverCertificate), secretResolver());
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings(
            Path clientKeyStore,
            Path trustStore,
            X509Certificate serverCertificate) {
        return newSettings(trustStore, "test:trust", clientKeyStore, "test:client",
                Set.of(PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        serverCertificate)));
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings newSettings(
            Path trustStore,
            String trustSecret,
            Path clientKeyStore,
            String clientSecret,
            Set<String> pins) {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                trustStore, trustSecret, clientKeyStore, clientSecret, pins);
    }

    private static RecoveryFleetPublicationTransport.SecretResolver secretResolver() {
        return reference -> switch (reference) {
            case "test:trust", "test:client" -> PASSWORD.clone();
            default -> throw new IllegalStateException("unexpected test secret reference");
        };
    }

    private record RunningServer(HttpsServer server, URI uri) implements AutoCloseable {

        private static RunningServer start(
                TlsMaterial material,
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
            server.createContext("/publication", exchange -> {
                try (exchange) {
                    peer.set(((HttpsExchange) exchange).getSSLSession()
                            .getPeerPrincipal().getName());
                    byte[] body = "publication".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            return new RunningServer(server, URI.create(
                    "https://localhost:" + server.getAddress().getPort() + "/publication"));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record TlsMaterial(
            Path serverKeyStore,
            Path clientKeyStore,
            Path trustStore,
            X509Certificate serverCertificate) {

        private static TlsMaterial create(Path directory, String suffix) throws Exception {
            KeyPair caKey = keyPair();
            X509Certificate ca = certificate("recovery-ca-" + suffix, caKey, null, null,
                    true, false, false);
            KeyPair serverKey = keyPair();
            X509Certificate server = certificate("localhost", serverKey, ca, caKey,
                    false, true, false);
            KeyPair clientKey = keyPair();
            X509Certificate client = certificate("recovery-client-" + suffix, clientKey,
                    ca, caKey, false, false, true);

            Path serverStore = directory.resolve("server-" + suffix + ".p12");
            Path clientStore = directory.resolve("client-" + suffix + ".p12");
            Path trustStore = directory.resolve("trust-" + suffix + ".p12");
            writeKeyStore(serverStore, "server", serverKey, server, ca);
            writeKeyStore(clientStore, "client", clientKey, client, ca);
            writeTrustStore(trustStore, ca);
            return new TlsMaterial(serverStore, clientStore, trustStore, server);
        }

        private SSLContext serverContext() throws Exception {
            KeyStore keys = load(serverKeyStore);
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, PASSWORD);
            KeyStore trust = load(trustStore);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(),
                    new SecureRandom());
            return context;
        }

        private static KeyStore load(Path path) throws Exception {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(path)) {
                store.load(input, PASSWORD);
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
                boolean client) throws Exception {
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
                if (server) {
                    builder.addExtension(Extension.extendedKeyUsage, false,
                            new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
                    builder.addExtension(Extension.subjectAlternativeName, false,
                            new GeneralNames(new GeneralName[]{
                                    new GeneralName(GeneralName.dNSName, "localhost"),
                                    new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
                } else if (client) {
                    builder.addExtension(Extension.extendedKeyUsage, false,
                            new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
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
