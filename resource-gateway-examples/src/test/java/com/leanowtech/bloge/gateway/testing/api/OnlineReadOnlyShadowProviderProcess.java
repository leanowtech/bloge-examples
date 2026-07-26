package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineCommand;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineObservation;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineObservationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowBaselineProtocol;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowCandidateCommand;
import com.leanowtech.bloge.gateway.integration.mirror.OnlineReadOnlyShadowCandidateProtocol;
import com.leanowtech.bloge.gateway.integration.mirror.SyntheticRegionalReadOnlyShadowProvider;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only isolated JVM provider for online read-only Shadow network certification.
 *
 * <p>The process terminates at the TLS boundary and owns one role, one server certificate, one
 * client trust root, and one evidence-signing key. Candidate state is durably committed before an
 * optional forced process halt, allowing the parent certification test to prove response-loss
 * recovery without a second physical candidate generation.</p>
 */
public final class OnlineReadOnlyShadowProviderProcess {
    /** Exit status used after the candidate commit is forced to lose its HTTP response. */
    public static final int COMMITTED_RESPONSE_LOSS_EXIT = 86;
    private static final int MAXIMUM_REQUEST_BYTES = 1024 * 1024;
    private static final int URI_SAN = 6;
    private static final ObjectMapper MAPPER = mapper();

    private OnlineReadOnlyShadowProviderProcess() {
    }

    /**
     * Starts one bounded provider process from a strict JSON configuration file.
     *
     * @param args exactly one absolute configuration path
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "online Shadow provider process requires one configuration path");
        }
        Configuration configuration = MAPPER.readValue(
                Path.of(args[0]).toFile(), Configuration.class);
        new Runtime(configuration).run();
    }

    /** Provider role owned by one process and one TLS trust domain. */
    public enum Role {
        BASELINE,
        CANDIDATE
    }

    /**
     * Private test signing material passed only to the isolated child process.
     *
     * @param keyId stable evidence key identity
     * @param encodedPublicKey base64 X.509 SubjectPublicKeyInfo
     * @param encodedPrivateKey base64 PKCS#8 private key
     * @param createdAt key creation instant
     */
    public record EvidenceKeyMaterial(
            String keyId,
            String encodedPublicKey,
            String encodedPrivateKey,
            Instant createdAt
    ) {
        /** Rejects missing or malformed Ed25519 key material. */
        public EvidenceKeyMaterial {
            keyId = required(keyId, "keyId");
            encodedPublicKey = required(
                    encodedPublicKey, "encodedPublicKey");
            encodedPrivateKey = required(
                    encodedPrivateKey, "encodedPrivateKey");
            createdAt = Objects.requireNonNull(
                    createdAt, "createdAt");
            try {
                publicKey(encodedPublicKey);
                privateKey(encodedPrivateKey);
            } catch (Exception invalid) {
                throw new IllegalArgumentException(
                        "provider evidence key material is invalid",
                        invalid);
            }
        }
    }

    /**
     * Strict child-process configuration.
     *
     * @param schemaVersion configuration protocol version
     * @param role exact process role
     * @param port fixed loopback port; zero requests an ephemeral port
     * @param serverKeyStore absolute PKCS#12 server identity path
     * @param trustStore absolute PKCS#12 client trust path
     * @param keyStorePassword test-only PKCS#12 password
     * @param expectedClientSubjectDn exact admitted client subject
     * @param expectedClientUriSan exact admitted client URI SAN
     * @param clockInstant deterministic provider clock
     * @param readyFile process readiness file
     * @param auditFile durable payload-free audit file
     * @param candidateStateFile durable candidate idempotency state
     * @param crashMarkerFile one-shot committed-response-loss marker
     * @param crashAfterFirstCandidateCommit whether to halt after the first durable candidate write
     * @param evidenceKey role-owned Ed25519 signing key
     * @param baselineCommand exact baseline command admitted by this fixture
     * @param baselineFixture exact payload-free baseline fixture
     * @param candidateCommand exact candidate command admitted by this fixture
     * @param candidatePlan exact sealed candidate plan
     * @param candidateResultFingerprint deterministic candidate result fingerprint
     */
    public record Configuration(
            String schemaVersion,
            Role role,
            int port,
            String serverKeyStore,
            String trustStore,
            String keyStorePassword,
            String expectedClientSubjectDn,
            String expectedClientUriSan,
            Instant clockInstant,
            String readyFile,
            String auditFile,
            String candidateStateFile,
            String crashMarkerFile,
            boolean crashAfterFirstCandidateCommit,
            EvidenceKeyMaterial evidenceKey,
            OnlineReadOnlyShadowBaselineCommand baselineCommand,
            SyntheticRegionalReadOnlyShadowProvider.BaselineFixture
                    baselineFixture,
            OnlineReadOnlyShadowCandidateCommand candidateCommand,
            MirrorPlan candidatePlan,
            String candidateResultFingerprint
    ) {
        /** Configuration protocol version. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.onlineReadOnlyShadowProviderProcess.v1";

        /** Validates role-specific material and local-only paths. */
        public Configuration {
            schemaVersion = required(
                    schemaVersion, "schemaVersion");
            role = Objects.requireNonNull(role, "role");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || port < 0 || port > 65_535) {
                throw new IllegalArgumentException(
                        "provider process configuration is invalid");
            }
            serverKeyStore = absoluteFile(
                    serverKeyStore, "serverKeyStore");
            trustStore = absoluteFile(
                    trustStore, "trustStore");
            keyStorePassword = required(
                    keyStorePassword, "keyStorePassword");
            expectedClientSubjectDn = required(
                    expectedClientSubjectDn,
                    "expectedClientSubjectDn");
            expectedClientUriSan = absoluteUri(
                    expectedClientUriSan,
                    "expectedClientUriSan");
            clockInstant = Objects.requireNonNull(
                    clockInstant, "clockInstant");
            readyFile = absolutePath(
                    readyFile, "readyFile");
            auditFile = absolutePath(
                    auditFile, "auditFile");
            candidateStateFile = absolutePath(
                    candidateStateFile,
                    "candidateStateFile");
            crashMarkerFile = absolutePath(
                    crashMarkerFile,
                    "crashMarkerFile");
            evidenceKey = Objects.requireNonNull(
                    evidenceKey, "evidenceKey");
            if (role == Role.BASELINE
                    && (baselineCommand == null
                    || baselineFixture == null
                    || candidateCommand != null
                    || candidatePlan != null)) {
                throw new IllegalArgumentException(
                        "baseline process configuration is incomplete");
            }
            if (role == Role.CANDIDATE
                    && (candidateCommand == null
                    || candidatePlan == null
                    || baselineCommand != null
                    || baselineFixture != null)) {
                throw new IllegalArgumentException(
                        "candidate process configuration is incomplete");
            }
            candidateResultFingerprint =
                    role == Role.CANDIDATE
                            ? fingerprint(
                            candidateResultFingerprint,
                            "candidateResultFingerprint")
                            : "";
        }
    }

    /**
     * Payload-free readiness record written only after the HTTPS socket is accepting requests.
     *
     * @param schemaVersion readiness protocol version
     * @param role child process role
     * @param pid operating-system process id
     * @param port bound loopback port
     */
    public record Ready(
            String schemaVersion,
            Role role,
            long pid,
            int port
    ) {
        /** Readiness protocol version. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.onlineReadOnlyShadowProviderReady.v1";
    }

    /**
     * Payload-free process audit used by the parent certification test.
     *
     * @param schemaVersion audit protocol version
     * @param role exact process role
     * @param pid operating-system process id
     * @param requests authenticated HTTP requests admitted to routing
     * @param executions execution POST requests
     * @param exactReads content-addressed artifact reads
     * @param candidateGenerations physical candidate evidence generations
     * @param peerSubject last mutual-TLS client subject
     * @param peerUriSan last mutual-TLS client URI SAN
     * @param failureCode last bounded request rejection
     * @param committedBeforeCrash whether candidate state was committed before forced halt
     */
    public record Audit(
            String schemaVersion,
            Role role,
            long pid,
            int requests,
            int executions,
            int exactReads,
            int candidateGenerations,
            String peerSubject,
            String peerUriSan,
            String failureCode,
            boolean committedBeforeCrash
    ) {
        /** Audit protocol version. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.onlineReadOnlyShadowProviderAudit.v1";
    }

    private static final class Runtime {
        private final Configuration configuration;
        private final Clock clock;
        private final FileEvidenceSigner signer;
        private final AtomicInteger requests =
                new AtomicInteger();
        private final AtomicInteger executions =
                new AtomicInteger();
        private final AtomicInteger exactReads =
                new AtomicInteger();
        private final AtomicInteger candidateGenerations =
                new AtomicInteger();
        private final AtomicReference<String> peerSubject =
                new AtomicReference<>("");
        private final AtomicReference<String> peerUriSan =
                new AtomicReference<>("");
        private final AtomicReference<String> failureCode =
                new AtomicReference<>("");
        private final AtomicBoolean committedBeforeCrash =
                new AtomicBoolean();
        private final SyntheticRegionalReadOnlyShadowProvider
                baselineProvider;
        private final MirrorEvidenceIntegrityService
                candidateIntegrity;
        private final AtomicReference<CandidateState>
                candidateState;

        private Runtime(
                Configuration configuration) throws Exception {
            this.configuration = Objects.requireNonNull(
                    configuration, "configuration");
            this.clock = Clock.fixed(
                    configuration.clockInstant(),
                    ZoneOffset.UTC);
            this.signer = new FileEvidenceSigner(
                    configuration.evidenceKey(),
                    clock);
            if (configuration.role() == Role.BASELINE) {
                OnlineReadOnlyShadowBaselineObservationIntegrity
                        baselineIntegrity =
                        new OnlineReadOnlyShadowBaselineObservationIntegrity(
                                MAPPER,
                                com.leanowtech.bloge.gateway.integration.mirror
                                        .OnlineReadOnlyShadowBaselineEvidenceAuthority
                                        .from(signer),
                                clock);
                this.candidateIntegrity =
                        new MirrorEvidenceIntegrityService(
                                MAPPER,
                                VisualEvidenceSigner.unavailable(),
                                clock);
                this.baselineProvider =
                        new SyntheticRegionalReadOnlyShadowProvider(
                                List.of(
                                        configuration
                                                .baselineFixture()),
                                ignored -> {
                                    throw new IllegalStateException(
                                            "baseline process cannot execute candidates");
                                },
                                baselineIntegrity,
                                candidateIntegrity,
                                MAPPER,
                                clock,
                                8);
                this.candidateState =
                        new AtomicReference<>();
            } else {
                this.baselineProvider = null;
                this.candidateIntegrity =
                        new MirrorEvidenceIntegrityService(
                                MAPPER,
                                signer,
                                clock);
                CandidateState restored =
                        readCandidateState(
                                Path.of(configuration
                                        .candidateStateFile()));
                if (restored != null
                        && (!configuration.candidateCommand()
                        .commandFingerprint(MAPPER)
                        .equals(restored
                                .commandFingerprint())
                        || candidateIntegrity.verify(
                        restored.bundle())
                        != MirrorEvidenceIntegrityService
                        .Verification.VERIFIED)) {
                    throw new IllegalStateException(
                            "candidate durable state is invalid");
                }
                this.candidateState =
                        new AtomicReference<>(restored);
                if (restored != null) {
                    candidateGenerations.set(
                            restored.generationCount());
                }
            }
        }

        private void run() throws Exception {
            SSLContext tls =
                    RecoveryFleetPublicationTlsFixture
                            .serverContext(
                                    Path.of(configuration
                                            .serverKeyStore()),
                                    Path.of(configuration
                                            .trustStore()),
                                    configuration
                                            .keyStorePassword()
                                            .toCharArray());
            HttpsServer server = HttpsServer.create(
                    new InetSocketAddress(
                            "127.0.0.1",
                            configuration.port()),
                    0);
            server.setHttpsConfigurator(
                    new HttpsConfigurator(tls) {
                        @Override
                        public void configure(
                                com.sun.net.httpserver
                                        .HttpsParameters parameters) {
                            var ssl = getSSLContext()
                                    .getDefaultSSLParameters();
                            ssl.setNeedClientAuth(true);
                            ssl.setProtocols(
                                    new String[]{
                                            "TLSv1.3",
                                            "TLSv1.2"});
                            parameters.setSSLParameters(ssl);
                        }
                    });
            var executor =
                    Executors
                            .newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    handle((HttpsExchange) exchange);
                } catch (ClientIdentityRejected rejected) {
                    failureCode.set(
                            "CLIENT_IDENTITY_REJECTED");
                    writeAudit();
                    respond(
                            exchange, 403,
                            new byte[0]);
                } catch (IllegalArgumentException rejected) {
                    failureCode.set(
                            "PROTOCOL_REQUEST_REJECTED");
                    writeAudit();
                    respond(
                            exchange, 400,
                            new byte[0]);
                } catch (Throwable failed) {
                    failureCode.set(
                            "PROVIDER_INTERNAL_FAILURE");
                    writeAudit();
                    respond(
                            exchange, 500,
                            new byte[0]);
                }
            });
            java.lang.Runtime.getRuntime()
                    .addShutdownHook(new Thread(() -> {
                        server.stop(0);
                        executor.close();
                    }, "online-shadow-provider-shutdown"));
            server.start();
            writeAudit();
            durableWrite(
                    Path.of(configuration.readyFile()),
                    MAPPER.writeValueAsBytes(
                            new Ready(
                                    Ready.SCHEMA_VERSION,
                                    configuration.role(),
                                    ProcessHandle.current()
                                            .pid(),
                                    server.getAddress()
                                            .getPort())));
            new CountDownLatch(1).await();
        }

        private void handle(
                HttpsExchange exchange) throws Exception {
            verifyPeer(exchange);
            requests.incrementAndGet();
            writeAudit();
            verifyRequestHeaders(exchange);
            String path = exchange.getRequestURI()
                    .getPath();
            if (path.equals(capabilityPath())) {
                if (!"GET".equals(
                        exchange.getRequestMethod())) {
                    throw new IllegalArgumentException(
                            "capability method is invalid");
                }
                respondJson(
                        exchange, capability());
                return;
            }
            if (path.equals(executionPath())) {
                if (!"POST".equals(
                        exchange.getRequestMethod())) {
                    throw new IllegalArgumentException(
                            "execution method is invalid");
                }
                executions.incrementAndGet();
                writeAudit();
                if (configuration.role()
                        == Role.BASELINE) {
                    executeBaseline(exchange);
                } else {
                    executeCandidate(exchange);
                }
                return;
            }
            if (!path.startsWith(evidencePathPrefix())
                    || !"GET".equals(
                    exchange.getRequestMethod())) {
                throw new IllegalArgumentException(
                        "exact-read route is invalid");
            }
            exactReads.incrementAndGet();
            writeAudit();
            if (configuration.role()
                    == Role.BASELINE) {
                resolveBaseline(exchange);
            } else {
                resolveCandidate(exchange);
            }
        }

        private void executeBaseline(
                HttpExchange exchange) throws Exception {
            OnlineReadOnlyShadowBaselineCommand command =
                    MAPPER.readValue(
                            boundedRequest(exchange),
                            OnlineReadOnlyShadowBaselineCommand
                                    .class);
            requireExecutionHeader(
                    exchange,
                    command.executionId());
            if (!configuration.baselineCommand()
                    .equals(command)) {
                throw new IllegalArgumentException(
                        "baseline command is not admitted");
            }
            respondJson(
                    exchange,
                    baselineProvider.baselineAuthority()
                            .observe(command));
        }

        private void executeCandidate(
                HttpExchange exchange) throws Exception {
            OnlineReadOnlyShadowCandidateCommand command =
                    MAPPER.readValue(
                            boundedRequest(exchange),
                            OnlineReadOnlyShadowCandidateCommand
                                    .class);
            requireExecutionHeader(
                    exchange,
                    command.executionId());
            if (!configuration.candidateCommand()
                    .equals(command)) {
                throw new IllegalArgumentException(
                        "candidate command is not admitted");
            }
            CandidateState state =
                    candidateState.get();
            if (state == null) {
                MirrorEvidenceBundle generated =
                        candidateBundle(command);
                state = new CandidateState(
                        CandidateState.SCHEMA_VERSION,
                        command.commandFingerprint(
                                MAPPER),
                        1,
                        generated);
                durableWrite(
                        Path.of(configuration
                                .candidateStateFile()),
                        MAPPER.writeValueAsBytes(state));
                candidateState.set(state);
                candidateGenerations.set(1);
                writeAudit();
            }
            if (configuration
                    .crashAfterFirstCandidateCommit()
                    && !Files.exists(
                    Path.of(configuration
                            .crashMarkerFile()))) {
                durableWrite(
                        Path.of(configuration
                                .crashMarkerFile()),
                        "committed-response-lost"
                                .getBytes(
                                        StandardCharsets.UTF_8));
                committedBeforeCrash.set(true);
                writeAudit();
                java.lang.Runtime.getRuntime()
                        .halt(COMMITTED_RESPONSE_LOSS_EXIT);
            }
            respondJson(
                    exchange, state.bundle());
        }

        private void resolveBaseline(
                HttpExchange exchange) throws Exception {
            MirrorArtifactRef reference =
                    requestedReference(
                            exchange.getRequestURI(),
                            OnlineReadOnlyShadowBaselineObservation
                                    .ARTIFACT_KIND);
            requireScope(
                    exchange.getRequestURI(),
                    configuration.baselineCommand()
                            .scope());
            respondJson(
                    exchange,
                    baselineProvider.baselineAuthority()
                            .resolve(
                                    configuration
                                            .baselineCommand()
                                            .scope(),
                                    reference));
        }

        private void resolveCandidate(
                HttpExchange exchange) throws Exception {
            CandidateState state =
                    Objects.requireNonNull(
                            candidateState.get(),
                            "candidate state");
            MirrorArtifactRef requested =
                    requestedReference(
                            exchange.getRequestURI(),
                            "MIRROR_EVIDENCE_BUNDLE");
            requireScope(
                    exchange.getRequestURI(),
                    configuration.candidateCommand()
                            .scope());
            MirrorArtifactRef stored =
                    candidateReference(
                            state.bundle());
            if (!stored.equals(requested)) {
                throw new IllegalArgumentException(
                        "candidate exact-read reference is invalid");
            }
            respondJson(
                    exchange, state.bundle());
        }

        private MirrorEvidenceBundle candidateBundle(
                OnlineReadOnlyShadowCandidateCommand
                        command) {
            MirrorPlan plan =
                    configuration.candidatePlan();
            Instant completedAt =
                    clock.instant().minusSeconds(1);
            Instant startedAt =
                    completedAt.minusSeconds(1);
            MirrorPlan.ExternalBinding binding =
                    plan.externalBindings()
                            .getFirst();
            MirrorRunEvidence evidence =
                    new MirrorRunEvidence(
                            MirrorRunEvidence
                                    .SCHEMA_VERSION_V1,
                            "candidate-process-"
                                    + command.executionId(),
                            command.commandFingerprint(
                                    MAPPER),
                            command
                                    .requestContextFingerprint(),
                            plan.planId(),
                            plan.planFingerprint(),
                            plan.capabilityClosureFingerprint(),
                            plan.executionControlFingerprint(),
                            plan.rootCapability(),
                            plan.fixtureBundleRef(),
                            List.of(
                                    new MirrorRunEvidence
                                            .ExternalBinding(
                                            plan.rootCapability(),
                                            binding
                                                    .dependencyNodeId(),
                                            binding
                                                    .capabilityRef(),
                                            binding
                                                    .invocationSiteId(),
                                            binding.graphPath())),
                            plan.scope(),
                            plan.policy()
                                    .authorizedPurpose(),
                            MirrorRunEvidence.Status.PASSED,
                            MirrorRunEvidence
                                    .EvidenceClass
                                    .EXPLORATORY,
                            configuration
                                    .candidateResultFingerprint(),
                            startedAt,
                            completedAt,
                            List.of(),
                            List.of(),
                            List.of(),
                            new MirrorRunEvidence
                                    .IsolationFacts(
                                    MirrorRunEvidence
                                            .IsolationFacts
                                            .EngineMode
                                            .INDEPENDENT_TEST_ENGINE,
                                    List.of(),
                                    List.of(
                                            "InvocationRecorder"),
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    null,
                                    List.of(
                                            "DEPLOYMENT_EGRESS_NOT_ATTESTED")),
                            List.of(
                                    "DEPLOYMENT_EGRESS_NOT_ATTESTED"));
            MirrorEvidenceIntegrityService.SealResult
                    sealed =
                    candidateIntegrity.seal(
                            evidence);
            if (sealed.bundle() == null) {
                throw new IllegalStateException(
                        "candidate evidence could not be sealed");
            }
            return sealed.bundle();
        }

        private void verifyPeer(
                HttpsExchange exchange)
                throws Exception {
            var certificates =
                    exchange.getSSLSession()
                            .getPeerCertificates();
            if (certificates.length == 0
                    || !(certificates[0]
                    instanceof X509Certificate client)) {
                throw new ClientIdentityRejected();
            }
            String subject =
                    client.getSubjectX500Principal()
                            .getName();
            String uriSan =
                    onlyUriSan(client);
            peerSubject.set(subject);
            peerUriSan.set(uriSan);
            if (!new X500Principal(
                    configuration
                            .expectedClientSubjectDn())
                    .equals(
                            client.getSubjectX500Principal())
                    || !configuration
                    .expectedClientUriSan()
                    .equals(uriSan)) {
                throw new ClientIdentityRejected();
            }
        }

        private void verifyRequestHeaders(
                HttpExchange exchange) {
            String authorization =
                    exchange.getRequestHeaders()
                            .getFirst("Authorization");
            if (!mediaType().equals(
                    exchange.getRequestHeaders()
                            .getFirst("Accept"))
                    || !version().equals(
                    exchange.getRequestHeaders()
                            .getFirst(versionHeader()))
                    || authorization == null
                    || authorization.isBlank()) {
                throw new IllegalArgumentException(
                        "provider request headers are invalid");
            }
            if ("POST".equals(
                    exchange.getRequestMethod())
                    && !mediaType().equals(
                    exchange.getRequestHeaders()
                            .getFirst("Content-Type"))) {
                throw new IllegalArgumentException(
                        "provider request content type is invalid");
            }
        }

        private void requireExecutionHeader(
                HttpExchange exchange,
                String executionId) {
            if (!executionId.equals(
                    exchange.getRequestHeaders()
                            .getFirst(
                                    executionHeader()))) {
                throw new IllegalArgumentException(
                        "provider execution header is invalid");
            }
        }

        private Object capability() {
            Instant now = clock.instant();
            if (configuration.role()
                    == Role.BASELINE) {
                return new OnlineReadOnlyShadowBaselineProtocol
                        .Capability(
                        OnlineReadOnlyShadowBaselineProtocol
                                .Capability.SCHEMA_VERSION,
                        OnlineReadOnlyShadowBaselineProtocol
                                .VERSION,
                        now,
                        now.plusSeconds(60),
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true);
            }
            return new OnlineReadOnlyShadowCandidateProtocol
                    .Capability(
                    OnlineReadOnlyShadowCandidateProtocol
                            .Capability.SCHEMA_VERSION,
                    OnlineReadOnlyShadowCandidateProtocol
                            .VERSION,
                    now,
                    now.plusSeconds(60),
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true);
        }

        private void respondJson(
                HttpExchange exchange,
                Object value) throws IOException {
            byte[] body =
                    MAPPER.writeValueAsBytes(value);
            exchange.getResponseHeaders()
                    .set("Content-Type", mediaType());
            exchange.getResponseHeaders()
                    .set(versionHeader(), version());
            respond(exchange, 200, body);
        }

        private void writeAudit() {
            try {
                durableWrite(
                        Path.of(configuration.auditFile()),
                        MAPPER.writeValueAsBytes(
                                new Audit(
                                        Audit.SCHEMA_VERSION,
                                        configuration.role(),
                                        ProcessHandle
                                                .current()
                                                .pid(),
                                        requests.get(),
                                        executions.get(),
                                        exactReads.get(),
                                        candidateGenerations
                                                .get(),
                                        peerSubject.get(),
                                        peerUriSan.get(),
                                        failureCode.get(),
                                        committedBeforeCrash
                                                .get())));
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "provider audit cannot be persisted",
                        failure);
            }
        }

        private String capabilityPath() {
            return configuration.role() == Role.BASELINE
                    ? "/api/mirror/shadow/online-baseline/capabilities"
                    : "/api/mirror/shadow/online-candidate/capabilities";
        }

        private String executionPath() {
            return configuration.role() == Role.BASELINE
                    ? "/api/mirror/shadow/online-baseline/observations"
                    : "/api/mirror/shadow/online-candidate/executions";
        }

        private String evidencePathPrefix() {
            return configuration.role() == Role.BASELINE
                    ? "/api/mirror/shadow/online-baseline/observations/"
                    : "/api/mirror/shadow/online-candidate/evidence/";
        }

        private String mediaType() {
            return configuration.role() == Role.BASELINE
                    ? OnlineReadOnlyShadowBaselineProtocol
                    .MEDIA_TYPE
                    : OnlineReadOnlyShadowCandidateProtocol
                    .MEDIA_TYPE;
        }

        private String version() {
            return configuration.role() == Role.BASELINE
                    ? OnlineReadOnlyShadowBaselineProtocol
                    .VERSION
                    : OnlineReadOnlyShadowCandidateProtocol
                    .VERSION;
        }

        private String versionHeader() {
            return configuration.role() == Role.BASELINE
                    ? OnlineReadOnlyShadowBaselineProtocol
                    .VERSION_HEADER
                    : OnlineReadOnlyShadowCandidateProtocol
                    .VERSION_HEADER;
        }

        private String executionHeader() {
            return configuration.role() == Role.BASELINE
                    ? OnlineReadOnlyShadowBaselineProtocol
                    .EXECUTION_ID_HEADER
                    : OnlineReadOnlyShadowCandidateProtocol
                    .EXECUTION_ID_HEADER;
        }
    }

    private record CandidateState(
            String schemaVersion,
            String commandFingerprint,
            int generationCount,
            MirrorEvidenceBundle bundle
    ) {
        private static final String SCHEMA_VERSION =
                "resourceGateway.onlineReadOnlyShadowCandidateProcessState.v1";

        private CandidateState {
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || generationCount != 1) {
                throw new IllegalArgumentException(
                        "candidate process state is invalid");
            }
            commandFingerprint = fingerprint(
                    commandFingerprint,
                    "commandFingerprint");
            bundle = Objects.requireNonNull(
                    bundle, "bundle");
        }
    }

    private static final class FileEvidenceSigner
            implements VisualEvidenceSigner {
        private final EvidenceKeyMaterial material;
        private final KeyPair keyPair;
        private final Clock clock;

        private FileEvidenceSigner(
                EvidenceKeyMaterial material,
                Clock clock) throws Exception {
            this.material = Objects.requireNonNull(
                    material, "material");
            this.keyPair = new KeyPair(
                    publicKey(
                            material
                                    .encodedPublicKey()),
                    privateKey(
                            material
                                    .encodedPrivateKey()));
            this.clock = Objects.requireNonNull(
                    clock, "clock");
        }

        @Override
        public VisualRunEvidenceSeal seal(
                String materialFingerprint) {
            try {
                Signature signature =
                        Signature.getInstance(
                                "Ed25519");
                signature.initSign(
                        keyPair.getPrivate());
                signature.update(
                        materialFingerprint.getBytes(
                                StandardCharsets.UTF_8));
                return new VisualRunEvidenceSeal(
                        "",
                        materialFingerprint,
                        "Ed25519",
                        material.keyId(),
                        clock.instant(),
                        Base64.getEncoder()
                                .encodeToString(
                                        signature.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "provider evidence signing failed",
                        failure);
            }
        }

        @Override
        public Verification verify(
                VisualRunEvidenceSeal seal,
                String actualMaterialFingerprint) {
            try {
                if (seal == null
                        || !material.keyId()
                        .equals(seal.keyId())
                        || !"Ed25519".equals(
                        seal.algorithm())
                        || !actualMaterialFingerprint
                        .equals(
                                seal.materialFingerprint())) {
                    return new Verification(
                            false,
                            "INVALID",
                            "Evidence seal coordinates are invalid.");
                }
                Signature verifier =
                        Signature.getInstance(
                                "Ed25519");
                verifier.initVerify(
                        keyPair.getPublic());
                verifier.update(
                        actualMaterialFingerprint
                                .getBytes(
                                        StandardCharsets.UTF_8));
                return verifier.verify(
                        Base64.getDecoder()
                                .decode(
                                        seal.signature()))
                        ? new Verification(
                        true, "VERIFIED", "")
                        : new Verification(
                        false,
                        "INVALID",
                        "Evidence signature is invalid.");
            } catch (Exception failure) {
                return new Verification(
                        false,
                        "INVALID",
                        "Evidence signature cannot be verified.");
            }
        }

        @Override
        public Optional<VerificationKey> key(
                String keyId) {
            if (!material.keyId()
                    .equals(keyId)) {
                return Optional.empty();
            }
            return Optional.of(
                    new VerificationKey(
                            "",
                            material.keyId(),
                            "Ed25519",
                            material.encodedPublicKey(),
                            material.createdAt(),
                            "ACTIVE",
                            "ISOLATED_TEST_PROCESS"));
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static CandidateState readCandidateState(
            Path path) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        return MAPPER.readValue(
                path.toFile(),
                CandidateState.class);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(
                        SerializationFeature
                                .WRITE_DATES_AS_TIMESTAMPS)
                .enable(
                        JsonParser.Feature
                                .STRICT_DUPLICATE_DETECTION)
                .enable(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(
                        DeserializationFeature
                                .FAIL_ON_TRAILING_TOKENS);
    }

    private static byte[] boundedRequest(
            HttpExchange exchange) throws IOException {
        try (var input =
                     exchange.getRequestBody();
             var output =
                     new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAXIMUM_REQUEST_BYTES) {
                    throw new IllegalArgumentException(
                            "provider request exceeds body bound");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void requireScope(
            URI uri,
            CapabilitySnapshot.Scope scope) {
        Map<String, String> query =
                query(uri);
        if (!scope.tenantId().equals(
                query.get("tenantId"))
                || !scope.organizationId().equals(
                query.get("organizationId"))
                || !scope.projectId().equals(
                query.get("projectId"))
                || !scope.environmentId().equals(
                query.get("environmentId"))
                || !scope.region().equals(
                query.get("region"))) {
            throw new IllegalArgumentException(
                    "provider exact-read scope is invalid");
        }
    }

    private static MirrorArtifactRef requestedReference(
            URI uri,
            String kind) {
        String[] segments =
                uri.getPath().split("/");
        if (segments.length < 4
                || !"revisions".equals(
                segments[segments.length - 2])) {
            throw new IllegalArgumentException(
                    "provider exact-read path is invalid");
        }
        return new MirrorArtifactRef(
                kind,
                segments[segments.length - 3],
                Integer.parseInt(
                        segments[segments.length - 1]),
                query(uri).get("fingerprint"));
    }

    private static MirrorArtifactRef candidateReference(
            MirrorEvidenceBundle bundle) {
        return new MirrorArtifactRef(
                "MIRROR_EVIDENCE_BUNDLE",
                bundle.evidence().runId(),
                1,
                bundle.bundleFingerprint());
    }

    private static Map<String, String> query(
            URI uri) {
        LinkedHashMap<String, String> values =
                new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        for (String pair : raw.split("&")) {
            String[] parts =
                    pair.split("=", 2);
            String key =
                    URLDecoder.decode(
                            parts[0],
                            StandardCharsets.UTF_8);
            String value =
                    URLDecoder.decode(
                            parts.length == 2
                                    ? parts[1] : "",
                            StandardCharsets.UTF_8);
            if (values.putIfAbsent(
                    key, value) != null) {
                throw new IllegalArgumentException(
                        "provider query contains duplicates");
            }
        }
        return Map.copyOf(values);
    }

    private static String onlyUriSan(
            X509Certificate certificate)
            throws Exception {
        Collection<List<?>>
                names =
                certificate
                        .getSubjectAlternativeNames();
        String result = "";
        int count = 0;
        if (names != null) {
            for (List<?> name : names) {
                if (name.size() >= 2
                        && Objects.equals(
                        name.getFirst(),
                        URI_SAN)
                        && name.get(1)
                        instanceof String value) {
                    result = value;
                    count++;
                }
            }
        }
        if (count != 1) {
            throw new ClientIdentityRejected();
        }
        return result;
    }

    private static PublicKey publicKey(
            String encoded) throws Exception {
        return KeyFactory.getInstance(
                "Ed25519").generatePublic(
                new X509EncodedKeySpec(
                        Base64.getDecoder()
                                .decode(encoded)));
    }

    private static PrivateKey privateKey(
            String encoded) throws Exception {
        return KeyFactory.getInstance(
                "Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(
                        Base64.getDecoder()
                                .decode(encoded)));
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            byte[] body) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(
                    status, body.length);
            exchange.getResponseBody()
                    .write(body);
        }
    }

    private static void durableWrite(
            Path target,
            byte[] value) throws IOException {
        Path parent =
                Objects.requireNonNull(
                        target.getParent(),
                        "target parent");
        Files.createDirectories(parent);
        Path temporary =
                parent.resolve(
                        target.getFileName()
                                + ".tmp-"
                                + ProcessHandle.current()
                                .pid());
        try (FileChannel channel =
                     FileChannel.open(
                             temporary,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE)) {
            ByteBuffer buffer =
                    ByteBuffer.wrap(value);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String required(
            String value,
            String field) {
        String normalized =
                Objects.requireNonNullElse(
                        value, "").trim();
        if (normalized.isEmpty()
                || normalized.length() > 16_384) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String fingerprint(
            String value,
            String field) {
        String normalized =
                required(value, field);
        if (!normalized.matches(
                "sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String absolutePath(
            String value,
            String field) {
        Path path =
                Path.of(required(
                        value, field))
                        .normalize();
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return path.toString();
    }

    private static String absoluteFile(
            String value,
            String field) {
        String normalized =
                absolutePath(value, field);
        if (!Files.isRegularFile(
                Path.of(normalized))) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String absoluteUri(
            String value,
            String field) {
        String normalized =
                required(value, field);
        URI uri = URI.create(normalized);
        if (!uri.isAbsolute()
                || uri.getScheme() == null) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static final class ClientIdentityRejected
            extends RuntimeException {
    }
}
