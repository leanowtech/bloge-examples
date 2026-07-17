package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Synchronous, dependency-light client for the profile-isolated Resource Gateway testing API.
 *
 * <p>The client sends a fresh credential and correlation id per request, rejects redirects and
 * unknown protocol versions, bounds request/response bodies, and never includes credentials or
 * payload bodies in thrown exception messages.</p>
 */
public final class ResourceGatewayTestClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** Public evidence projection verbosity. */
    public enum Verbosity {
        /** Terminal state, fingerprints, fixture consumption, and assertions. */
        SUMMARY,
        /** Summary plus payload-free node and edge observations. */
        STANDARD,
        /** Full sanitized evidence projection. */
        FULL
    }

    /** Scheduling strategy for one immutable governed suite run. */
    public enum SuiteStrategy {
        /** Execute every case and aggregate all failures. */
        COLLECT_ALL,
        /** Stop scheduling new cases after the first terminal case failure. */
        FAIL_FAST
    }

    /** Supplies a short-lived bearer credential at request time. */
    @FunctionalInterface
    public interface BearerTokenProvider {
        /** Supplies the current credential.
         * @return current bearer token without the {@code Bearer} prefix
         */
        String bearerToken();
    }

    private final URI baseUri;
    private final HttpClient httpClient;
    private final BearerTokenProvider tokenProvider;
    private final Supplier<String> correlationIdProvider;
    private final Duration requestTimeout;
    private final int maxRequestBytes;
    private final int maxResponseBytes;

    private ResourceGatewayTestClient(Builder builder) {
        baseUri = validateBaseUri(builder.baseUri);
        tokenProvider = Objects.requireNonNull(builder.tokenProvider, "bearer token provider is required");
        correlationIdProvider = builder.correlationIdProvider;
        requestTimeout = builder.requestTimeout;
        maxRequestBytes = builder.maxRequestBytes;
        maxResponseBytes = builder.maxResponseBytes;
        httpClient = builder.httpClient == null
                ? HttpClient.newBuilder().connectTimeout(builder.connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build()
                : builder.httpClient;
    }

    /**
     * Starts a client builder for one Resource Gateway base URI.
     * @param baseUri absolute HTTP(S) server base URI
     * @return new client builder
     */
    public static Builder builder(URI baseUri) {
        return new Builder(baseUri);
    }

    /**
     * Discovers a graph contract and its frozen dependency fingerprint.
     * @param graphName registered graph name
     * @return typed target descriptor
     */
    public GraphTargetDescriptor describeGraphTarget(String graphName) {
        JsonNode response = exchange("GET", "/api/testing/targets/graphs/" + segment(graphName), "",
                "TEST_EXECUTION", null);
        requireVersion(response, TestingProtocol.GRAPH_TARGET_DESCRIPTOR_V1);
        return GraphTargetDescriptor.from(response);
    }

    /**
     * Discovers one frozen operator binding, schemas and executable testability classification.
     * @param operatorRef registered operator reference
     * @return typed operator target descriptor
     */
    public OperatorTargetDescriptor describeOperatorTarget(String operatorRef) {
        JsonNode response = exchange("GET", "/api/testing/targets/operators/" + segment(operatorRef), "",
                "TEST_EXECUTION", null);
        requireVersion(response, TestingProtocol.OPERATOR_TARGET_DESCRIPTOR_V2);
        return OperatorTargetDescriptor.from(response);
    }

    /**
     * Registers one immutable fixture revision.
     * @param fixtureBundleId fixture id used in the endpoint
     * @param registrationRequest schema-complete registration request
     * @return committed immutable revision
     */
    public FixtureBundleRevision registerFixture(String fixtureBundleId, JsonNode registrationRequest) {
        JsonNode response = exchange("PUT", "/api/testing/fixture-bundles/" + segment(fixtureBundleId), "",
                "TEST_FIXTURE_WRITE", requiredObject(registrationRequest, "registrationRequest"));
        requireVersion(response, TestingProtocol.STORED_FIXTURE_BUNDLE_V1);
        return FixtureBundleRevision.from(response);
    }

    /**
     * Reads one tenant- and environment-scoped immutable fixture revision.
     * @param fixtureBundleId fixture id
     * @param revision positive immutable revision
     * @return stored fixture projection
     */
    public FixtureBundleRevision findFixture(String fixtureBundleId, long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        JsonNode response = exchange("GET", "/api/testing/fixture-bundles/" + segment(fixtureBundleId),
                "revision=" + revision, "TEST_FIXTURE_READ", null);
        requireVersion(response, TestingProtocol.STORED_FIXTURE_BUNDLE_V1);
        return FixtureBundleRevision.from(response);
    }

    /**
     * Registers one dependency-closed immutable test-suite revision.
     *
     * @param suiteId suite id used in the endpoint
     * @param registrationRequest schema-complete registration request
     * @return committed immutable suite revision
     */
    public TestSuiteRevision registerSuite(String suiteId, JsonNode registrationRequest) {
        String exactSuiteId = requiredIdentifier(suiteId, "suiteId", 255);
        JsonNode request = requiredObject(registrationRequest, "registrationRequest");
        TestingProtocolSchemaValidator.require(request, "testSuiteRegistrationRequest");
        if (!exactSuiteId.equals(request.at("/testSuite/suiteId").asText())) {
            throw new IllegalArgumentException("Path and registration suite identity must match");
        }
        long revision = request.at("/testSuite/revision").asLong();
        JsonNode response = exchange("PUT", "/api/testing/suites/" + segment(exactSuiteId), "",
                "TEST_SUITE_WRITE", request);
        requireVersion(response, TestingProtocol.STORED_TEST_SUITE_V1);
        TestSuiteRevision stored = projectSuiteRevision(response);
        requireSuiteRevisionIdentity(stored, exactSuiteId, revision);
        return stored;
    }

    /**
     * Reads one exact tenant- and environment-scoped suite revision.
     *
     * @param suiteId stable suite id
     * @param revision positive immutable revision
     * @return stored suite identity projection
     */
    public TestSuiteRevision findSuite(String suiteId, long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        String exactSuiteId = requiredIdentifier(suiteId, "suiteId", 255);
        JsonNode response = exchange("GET", "/api/testing/suites/" + segment(exactSuiteId),
                "revision=" + revision, "TEST_SUITE_READ", null);
        requireVersion(response, TestingProtocol.STORED_TEST_SUITE_V1);
        TestSuiteRevision stored = projectSuiteRevision(response);
        requireSuiteRevisionIdentity(stored, exactSuiteId, revision);
        return stored;
    }

    /**
     * Materializes the trusted built-in graph contract catalog into the caller's immutable scope.
     *
     * <p>The returned exact references can be passed directly to {@link #executeSuite(String, long,
     * String, String, SuiteStrategy, Map)} or to the suite CLI. Repeating this call over unchanged
     * source and dependencies returns the same projection.</p>
     *
     * @return typed payload-free source-to-destination reference inventory
     */
    public TestSuiteCatalogMaterialization materializeBuiltInGraphContractCatalog() {
        JsonNode response = exchange("PUT", "/api/testing/catalogs/gateway-graph-contract-v1", "",
                "TEST_SUITE_WRITE", null);
        requireVersion(response, TestingProtocol.TEST_SUITE_CATALOG_MATERIALIZATION_V1);
        try {
            return TestSuiteCatalogMaterialization.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid test-suite catalog materialization projection.");
        }
    }

    /**
     * Executes one exact immutable suite revision with a caller-owned idempotency key.
     *
     * @param suiteId exact suite id
     * @param revision exact immutable revision
     * @param fingerprint full SHA-256 suite fingerprint
     * @param clientRequestId stable idempotency key for this execution intent
     * @param strategy scheduling strategy, defaulting to COLLECT_ALL
     * @param metadata bounded provenance metadata
     * @return aggregate suite-run projection
     */
    public TestSuiteRun executeSuite(String suiteId, long revision, String fingerprint,
                                     String clientRequestId, SuiteStrategy strategy,
                                     Map<String, ?> metadata) {
        String id = requiredIdentifier(clientRequestId, "clientRequestId", 255);
        String exactSuiteId = requiredIdentifier(suiteId, "suiteId", 255);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        String exactFingerprint = requiredFingerprint(fingerprint);
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion", TestingProtocol.TEST_SUITE_EXECUTION_REQUEST_V1);
        ObjectNode suiteRef = request.putObject("suiteRef");
        suiteRef.put("suiteId", exactSuiteId);
        suiteRef.put("revision", revision);
        suiteRef.put("fingerprint", exactFingerprint);
        request.put("clientRequestId", id);
        request.put("strategy", (strategy == null ? SuiteStrategy.COLLECT_ALL : strategy).name());
        request.set("metadata", metadata == null ? JSON.createObjectNode() : JSON.valueToTree(metadata));
        TestingProtocolSchemaValidator.require(request, "testSuiteExecutionRequest");
        JsonNode response = exchange("POST", "/api/testing/suites/" + segment(exactSuiteId) + "/executions",
                "", "TEST_EXECUTION", request);
        requireSuiteExecutionResponseVersion(response);
        TestSuiteRun run = projectSuiteRun(response);
        requireSuiteRunIdentity(run, exactSuiteId, revision, exactFingerprint, id);
        return run;
    }

    /**
     * Retrieves the latest durable checkpoint or terminal evidence for one suite run.
     *
     * @param suiteRunId durable aggregate run id
     * @return aggregate suite-run projection
     */
    public TestSuiteRun findSuiteRun(String suiteRunId) {
        String exactSuiteRunId = requiredIdentifier(suiteRunId, "suiteRunId", 255);
        JsonNode response = exchange("GET", "/api/testing/suite-executions/" + segment(exactSuiteRunId),
                "", "TEST_EXECUTION", null);
        requireSuiteExecutionResponseVersion(response);
        TestSuiteRun run = projectSuiteRun(response);
        try {
            run.requireRunIdentity(exactSuiteRunId);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid("The server returned a mismatched suite-run identity.");
        }
        return run;
    }

    /**
     * Retrieves one verified terminal, payload-free suite evidence bundle.
     *
     * @param suiteRunId durable aggregate run id
     * @return typed portable evidence bundle
     */
    public TestSuiteEvidenceBundle findSuiteEvidenceBundle(String suiteRunId) {
        String exactSuiteRunId = requiredIdentifier(suiteRunId, "suiteRunId", 255);
        JsonNode response = exchange("GET", "/api/testing/suite-executions/"
                + segment(exactSuiteRunId) + "/evidence-bundle", "", "TEST_EXECUTION", null);
        requireSuiteEvidenceBundleVersion(response);
        try {
            TestSuiteEvidenceBundle bundle = TestSuiteEvidenceBundle.from(response);
            if (!exactSuiteRunId.equals(bundle.suiteRunId())) {
                throw new IllegalArgumentException("Suite evidence bundle identity is inconsistent");
            }
            return bundle;
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid portable suite evidence bundle.");
        }
    }

    /**
     * Retrieves the payload-free ANEKE workbook seed for one exact semantic suite revision.
     *
     * @param suiteId stable semantic suite id
     * @param revision exact immutable revision
     * @return schema-validated semantic correctness workbook
     */
    public SemanticCorrectnessWorkbook findSemanticCorrectnessWorkbook(
            String suiteId, long revision) {
        String exactSuiteId = requiredIdentifier(suiteId, "suiteId", 255);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        JsonNode response = exchange("GET", "/api/integration/test-suites/"
                        + segment(exactSuiteId) + "/revisions/" + revision
                        + "/semantic-correctness-workbook", "", "WORKBOOK_SYNC", null);
        try {
            SemanticCorrectnessWorkbook workbook =
                    SemanticCorrectnessWorkbook.fromEnvelope(response);
            if (!exactSuiteId.equals(workbook.suiteId())
                    || revision != workbook.suiteRevision()) {
                throw new IllegalArgumentException("Semantic workbook suite identity is inconsistent");
            }
            return workbook;
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid semantic correctness workbook.");
        }
    }

    /**
     * Submits one schema-complete v3 governance decision with semantic workbook basis.
     *
     * <p>The independent schema is applied before the request leaves the process and again to the
     * acknowledged payload. The response must preserve both immutable decision id and fingerprint.</p>
     *
     * @param gateResult complete {@code toolStudio.resourceGateway.gateResult.v3} value
     * @return schema-validated immutable acknowledgement
     */
    public GovernanceGateReceipt submitGovernanceGateResult(JsonNode gateResult) {
        JsonNode request = requiredObject(gateResult, "gateResult");
        TestingProtocolSchemaValidator.requireRoot(request,
                TestingProtocol.GOVERNANCE_GATE_V3_SCHEMA_RESOURCE);
        String expectedId = request.path("gateResultId").asText();
        String expectedFingerprint = request.path("resultFingerprint").asText();
        JsonNode response = exchange("POST", "/api/integration/gate-results", "",
                "GOVERNANCE_GATE_FEEDBACK", request);
        GovernanceGateReceipt receipt;
        try {
            receipt = GovernanceGateReceipt.fromEnvelope(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid governance gate acknowledgement.");
        }
        if (!expectedId.equals(receipt.gateResultId())
                || !expectedFingerprint.equals(receipt.resultFingerprint())) {
            throw responseContractInvalid(
                    "The server acknowledged a different governance gate decision.");
        }
        return receipt;
    }

    /**
     * Resolves one public evidence verification key from the integration protocol.
     *
     * @param keyId attestation verification key id
     * @return typed public verification key
     */
    public EvidenceVerificationKey findEvidenceVerificationKey(String keyId) {
        String exactKeyId = requiredIdentifier(keyId, "keyId", 512);
        JsonNode response = exchange("GET", "/api/integration/evidence-keys/" + segment(exactKeyId),
                "", "TEST_EXECUTION", null);
        try {
            return EvidenceVerificationKey.fromEnvelope(response, exactKeyId);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid evidence verification key envelope.");
        }
    }

    /**
     * Retrieves one atomic signed evidence-key lifecycle snapshot.
     *
     * <p>Retrieval alone does not establish trust. Callers must verify the returned value against
     * a fingerprint pinned outside the response.</p>
     *
     * @return typed multi-key lifecycle snapshot
     */
    public EvidenceVerificationKeySet findEvidenceVerificationKeySet() {
        JsonNode response = exchange("GET", "/api/integration/evidence-keys", "",
                "TEST_EXECUTION", null);
        try {
            return EvidenceVerificationKeySet.fromEnvelope(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid evidence verification key-set envelope.");
        }
    }

    /**
     * Challenges one directly addressed Resource Gateway process for signed rollout facts.
     *
     * <p>This call validates request/response shape and binds the response to the exact challenge
     * and target. It does not establish signature or fleet trust. Collect one proof from every
     * independently inventoried serving instance, then use
     * {@link WorkerQuarantineRequestIndexFleetGateVerifier#verify}.</p>
     *
     * @param challenge fresh deployment-gate nonce shared by the intended fleet cohort
     * @param targetMode immediate keyed rollout target
     * @return strict typed signed proof from the addressed process
     */
    public WorkerQuarantineRequestIndexReplicaProof
            requestWorkerQuarantineRequestIndexReplicaProof(
            String challenge, WorkerQuarantineRequestIndexReplicaProof.Mode targetMode) {
        String exactChallenge = normalized(challenge);
        if (targetMode == null
                || targetMode == WorkerQuarantineRequestIndexReplicaProof.Mode.LEGACY_READ_WRITE) {
            throw new IllegalArgumentException("An immediate keyed request-index target is required");
        }
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion",
                TestingProtocol.WORKER_QUARANTINE_REQUEST_INDEX_REPLICA_PROOF_REQUEST_V1);
        request.put("challenge", exactChallenge);
        request.put("targetMode", targetMode.name());
        TestingProtocolSchemaValidator.require(
                request, "workerQuarantineRequestIndexReplicaProofRequest");
        JsonNode response = exchange("POST",
                "/api/testing/durable-state/worker-quarantines/request-index/replica-proofs",
                "", "TEST_RUNTIME_MAINTENANCE", request);
        requireVersion(response,
                TestingProtocol.WORKER_QUARANTINE_REQUEST_INDEX_REPLICA_PROOF_V1);
        try {
            WorkerQuarantineRequestIndexReplicaProof proof =
                    WorkerQuarantineRequestIndexReplicaProof.from(response);
            if (!exactChallenge.equals(proof.material().challenge())
                    || targetMode != proof.material().targetMode()) {
                throw new IllegalArgumentException("Replica proof request identity is inconsistent");
            }
            return proof;
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid request-index replica proof.");
        }
    }

    /**
     * Retrieves one bounded externally authorized key-set trust consistency page.
     *
     * <p>The response is schema-validated but remains untrusted until
     * {@link EvidenceKeySetTrustVerifier#verify} succeeds against caller-owned anchors and a durable
     * checkpoint.</p>
     *
     * @param afterSequence caller's last durable trust-log sequence, zero for genesis
     * @param limit bounded page size from 1 through 256
     * @return typed trust page and current evidence key set
     */
    public EvidenceKeySetTrustBundle findEvidenceKeySetTrustBundle(long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 256) {
            throw new IllegalArgumentException("Evidence trust cursor and limit are invalid");
        }
        JsonNode response = exchange("GET", "/api/integration/evidence-keys/trust-bundle",
                "afterSequence=" + afterSequence + "&limit=" + limit,
                "TEST_EXECUTION", null);
        try {
            return EvidenceKeySetTrustBundle.fromEnvelope(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid evidence key-set trust bundle.");
        }
    }

    /**
     * Fetches and independently verifies one bounded trust-log page.
     *
     * @param policy caller-owned external authority policy
     * @param checkpoint caller's durable prior state, null only for genesis bootstrap
     * @param limit bounded page size
     * @return trust decision and next checkpoint
     */
    public EvidenceKeySetTrustVerifier.VerificationResult verifyEvidenceKeySetTrust(
            EvidenceTrustPolicy policy, EvidenceTrustCheckpoint checkpoint, int limit) {
        long afterSequence = checkpoint == null ? 0 : checkpoint.sequence();
        EvidenceKeySetTrustBundle bundle = findEvidenceKeySetTrustBundle(afterSequence, limit);
        return new EvidenceKeySetTrustVerifier().verify(bundle, policy, checkpoint);
    }

    /**
     * Combined trust-log and suite-evidence decision for one terminal trust page.
     *
     * @param trust trust-log verification result
     * @param evidence suite evidence verification result; null until trust is terminally verified
     */
    public record TrustAnchoredSuiteVerification(
            EvidenceKeySetTrustVerifier.VerificationResult trust,
            TestSuiteEvidenceVerifier.VerificationResult evidence
    ) {
        /** Requires a trust decision and keeps evidence absent until the trust root passed. */
        public TrustAnchoredSuiteVerification {
            Objects.requireNonNull(trust, "trust verification is required");
            if (!trust.verified() && evidence != null) {
                throw new IllegalArgumentException("Untrusted key material cannot produce evidence verification");
            }
        }

        /**
         * Tests whether trust and suite evidence independently passed.
         *
         * @return true only when both decisions are verified
         */
        public boolean verified() {
            return trust.verified() && evidence != null && evidence.verified();
        }
    }

    /**
     * Verifies one suite only after a terminal transparency page selects its exact key-set pin.
     *
     * <p>When the result requests catch-up, persist its checkpoint and call again before making a
     * release decision. This method intentionally performs one bounded page per invocation.</p>
     *
     * @param suiteRunId durable aggregate run id
     * @param policy caller-owned external authority policy
     * @param checkpoint caller's durable prior state, null only for genesis
     * @param limit bounded trust page size
     * @return composed trust and evidence decision
     */
    public TrustAnchoredSuiteVerification verifySuiteEvidence(
            String suiteRunId, EvidenceTrustPolicy policy,
            EvidenceTrustCheckpoint checkpoint, int limit) {
        long afterSequence = checkpoint == null ? 0 : checkpoint.sequence();
        EvidenceKeySetTrustBundle trustBundle = findEvidenceKeySetTrustBundle(afterSequence, limit);
        EvidenceKeySetTrustVerifier.VerificationResult trust =
                new EvidenceKeySetTrustVerifier().verify(trustBundle, policy, checkpoint);
        if (!trust.verified()) {
            return new TrustAnchoredSuiteVerification(trust, null);
        }
        TestSuiteEvidenceBundle evidenceBundle = findSuiteEvidenceBundle(suiteRunId);
        TestSuiteEvidenceVerifier.VerificationResult evidence =
                new TestSuiteEvidenceVerifier().verify(evidenceBundle, trustBundle.keySet(),
                        trust.trustedSnapshotFingerprint());
        return new TrustAnchoredSuiteVerification(trust, evidence);
    }

    /**
     * Fetches and independently verifies one terminal suite evidence bundle.
     *
     * @param suiteRunId durable aggregate run id
     * @return payload-free offline verification result
     */
    public TestSuiteEvidenceVerifier.VerificationResult verifySuiteEvidence(String suiteRunId) {
        TestSuiteEvidenceBundle bundle = findSuiteEvidenceBundle(suiteRunId);
        EvidenceVerificationKey key;
        try {
            key = findEvidenceVerificationKey(bundle.attestation().keyId());
        } catch (ResourceGatewayTestException failure) {
            if ("RG.INTEGRATION.EVIDENCE_KEY_NOT_FOUND".equals(failure.code())
                    || "RG.INTEGRATION.EVIDENCE_KEY_PROVIDER_UNAVAILABLE".equals(failure.code())) {
                return new TestSuiteEvidenceVerifier().verify(bundle, null);
            }
            throw failure;
        }
        return new TestSuiteEvidenceVerifier().verify(bundle, key);
    }

    /**
     * Performs release-grade suite verification against an independently pinned key-set snapshot.
     *
     * @param suiteRunId durable aggregate run id
     * @param trustedKeySetFingerprint key-set material fingerprint pinned by governance configuration
     * @return lifecycle-aware offline verification result
     */
    public TestSuiteEvidenceVerifier.VerificationResult verifySuiteEvidence(
            String suiteRunId, String trustedKeySetFingerprint) {
        TestSuiteEvidenceBundle bundle = findSuiteEvidenceBundle(suiteRunId);
        EvidenceVerificationKeySet keySet;
        try {
            keySet = findEvidenceVerificationKeySet();
        } catch (ResourceGatewayTestException failure) {
            if ("RG.INTEGRATION.EVIDENCE_KEY_SET_PROVIDER_UNAVAILABLE".equals(failure.code())
                    || "RG.INTEGRATION.EVIDENCE_KEY_SET_ATTESTATION_UNAVAILABLE".equals(failure.code())) {
                return new TestSuiteEvidenceVerifier().verify(bundle, null,
                        trustedKeySetFingerprint);
            }
            throw failure;
        }
        return new TestSuiteEvidenceVerifier().verify(bundle, keySet, trustedKeySetFingerprint);
    }

    /**
     * Executes one inline or stored-fixture request through the controlled graph runtime.
     * @param executionRequest schema-complete execution request
     * @return persisted run projection
     */
    public TestRun execute(JsonNode executionRequest) {
        JsonNode response = exchange("POST", "/api/testing/executions", "", "TEST_EXECUTION",
                requiredObject(executionRequest, "executionRequest"));
        requireExecutionResponseVersion(response);
        return projectRun(response);
    }

    /**
     * Executes one operator request through the server's one-node BLOGE test kernel.
     * @param operatorRef path-bound registered operator reference
     * @param executionRequest schema-complete operator execution request
     * @return persisted run projection
     */
    public TestRun executeOperator(String operatorRef, JsonNode executionRequest) {
        JsonNode response = exchange("POST", "/api/testing/targets/operators/" + segment(operatorRef)
                        + "/executions", "", "TEST_EXECUTION",
                requiredObject(executionRequest, "executionRequest"));
        requireExecutionResponseVersion(response);
        return projectRun(response);
    }

    /**
     * Executes one bounded independent batch through the same server kernel.
     * @param executionRequests 1 to 100 schema-complete execution requests
     * @return ordered batch projection
     */
    public TestRunBatch executeBatch(List<? extends JsonNode> executionRequests) {
        if (executionRequests == null || executionRequests.isEmpty() || executionRequests.size() > 100) {
            throw new IllegalArgumentException("A test batch must contain 1 to 100 executions");
        }
        ObjectNode batch = JSON.createObjectNode();
        batch.put("schemaVersion", TestingProtocol.TEST_EXECUTION_BATCH_REQUEST_V1);
        ArrayNode executions = batch.putArray("executions");
        executionRequests.forEach(request -> executions.add(requiredObject(request, "executionRequest").deepCopy()));
        JsonNode response = exchange("POST", "/api/testing/executions/batch", "", "TEST_EXECUTION", batch);
        requireVersion(response, TestingProtocol.TEST_EXECUTION_BATCH_RESPONSE_V1);
        List<TestRun> runs = new ArrayList<>();
        response.path("executions").forEach(item -> {
            requireExecutionResponseVersion(item);
            runs.add(projectRun(item));
        });
        if (runs.size() != executionRequests.size()) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.BATCH_CARDINALITY_MISMATCH",
                    "The server returned a different number of batch results.", null);
        }
        return new TestRunBatch(runs);
    }

    /**
     * Retrieves one persisted, authorization-scoped test run at the requested verbosity.
     * @param runId persisted run id
     * @param verbosity response projection, defaulting to STANDARD
     * @return run projection
     */
    public TestRun findRun(String runId, Verbosity verbosity) {
        String selected = (verbosity == null ? Verbosity.STANDARD : verbosity).name();
        JsonNode response = exchange("GET", "/api/testing/executions/" + segment(runId),
                "verbosity=" + selected, "TEST_EXECUTION", null);
        requireExecutionResponseVersion(response);
        return projectRun(response);
    }

    private JsonNode exchange(String method, String path, String query, String purpose, JsonNode body) {
        byte[] requestBody = serialize(body);
        URI uri = endpoint(path, query);
        String token = normalized(tokenProvider.bearerToken());
        if (token.isBlank() || token.length() > 4096 || token.contains("\r") || token.contains("\n")) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.CREDENTIAL_UNAVAILABLE",
                    "A bounded bearer credential is required.", null);
        }
        String correlationId = bounded(normalized(correlationIdProvider.get()), 128);
        if (correlationId.isBlank() || correlationId.contains("\r") || correlationId.contains("\n")) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.CORRELATION_ID_INVALID",
                    "The correlation id provider returned an invalid value.", null);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(requestBody);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Purpose", purpose)
                .header("X-Correlation-Id", correlationId)
                .method(method, publisher);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(request.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes = readBounded(response.body(), response.headers()
                    .firstValueAsLong("Content-Length").orElse(-1));
            JsonNode decoded = decode(bytes);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw problem(response.statusCode(), decoded);
            }
            if (!decoded.isObject()) {
                throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_MALFORMED",
                        "The server returned a non-object JSON response.", null);
            }
            return decoded;
        } catch (ResourceGatewayTestException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ResourceGatewayTestException.local("RG.TESTKIT.REQUEST_INTERRUPTED",
                    "The Resource Gateway test request was interrupted.", failure);
        } catch (IOException failure) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.TRANSPORT_FAILURE",
                    "The Resource Gateway test endpoint could not be reached.", failure);
        }
    }

    private byte[] serialize(JsonNode body) {
        if (body == null) {
            return new byte[0];
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(body);
            if (bytes.length > maxRequestBytes) {
                throw ResourceGatewayTestException.local("RG.TESTKIT.REQUEST_TOO_LARGE",
                        "The testing request exceeds the configured body limit.", null);
            }
            return bytes;
        } catch (JsonProcessingException failure) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.REQUEST_MALFORMED",
                    "The testing request could not be encoded as JSON.", failure);
        }
    }

    private byte[] readBounded(InputStream input, long contentLength) throws IOException {
        if (contentLength > maxResponseBytes) {
            input.close();
            throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_TOO_LARGE",
                    "The testing response exceeds the configured body limit.", null);
        }
        try (input) {
            byte[] bytes = input.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_TOO_LARGE",
                        "The testing response exceeds the configured body limit.", null);
            }
            return bytes;
        }
    }

    private static JsonNode decode(byte[] bytes) {
        try {
            if (bytes.length == 0) {
                throw new JsonProcessingException("empty response") { };
            }
            return JSON.readTree(bytes);
        } catch (JsonProcessingException failure) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_MALFORMED",
                    "The server returned malformed JSON.", failure);
        } catch (IOException failure) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_MALFORMED",
                    "The server response could not be decoded.", failure);
        }
    }

    private static ResourceGatewayTestException problem(int status, JsonNode body) {
        if (body != null && body.isObject()) {
            return new ResourceGatewayTestException(status,
                    bounded(body.path("code").asText("RG.TESTKIT.HTTP_ERROR"), 160),
                    bounded(body.path("title").asText("The Resource Gateway rejected the test request."), 512),
                    body.path("retryable").asBoolean(false),
                    bounded(body.path("correlationId").asText(), 128), null);
        }
        return new ResourceGatewayTestException(status, "RG.TESTKIT.HTTP_ERROR",
                "The Resource Gateway rejected the test request.", status >= 500, "", null);
    }

    private static TestRun projectRun(JsonNode response) {
        try {
            return TestRun.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid("The server returned an invalid test-run projection.");
        }
    }

    private static TestSuiteRevision projectSuiteRevision(JsonNode response) {
        try {
            return TestSuiteRevision.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid("The server returned an invalid test-suite revision projection.");
        }
    }

    private static TestSuiteRun projectSuiteRun(JsonNode response) {
        try {
            return TestSuiteRun.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid("The server returned an invalid suite-run projection.");
        }
    }

    private static void requireSuiteRevisionIdentity(TestSuiteRevision stored, String suiteId, long revision) {
        try {
            stored.requireIdentity(suiteId, revision);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid("The server returned a mismatched test-suite revision identity.");
        }
    }

    private static void requireSuiteRunIdentity(TestSuiteRun run, String suiteId, long revision,
                                                String fingerprint, String clientRequestId) {
        try {
            run.requireExecutionIdentity(suiteId, revision, fingerprint, clientRequestId);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid("The server returned a mismatched suite-run response identity.");
        }
    }

    private static ResourceGatewayTestException responseContractInvalid(String title) {
        return ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_CONTRACT_INVALID", title, null);
    }

    private static void requireVersion(JsonNode response, String expected) {
        String actual = response.path("schemaVersion").asText();
        if (!expected.equals(actual)) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.PROTOCOL_VERSION_MISMATCH",
                    "The server returned an unsupported protocol version; expected " + expected + ".", null);
        }
    }

    private static void requireExecutionResponseVersion(JsonNode response) {
        String actual = response.path("schemaVersion").asText();
        if (!TestingProtocol.TEST_EXECUTION_RESPONSE_V1.equals(actual)
                && !TestingProtocol.TEST_EXECUTION_RESPONSE_V2.equals(actual)) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.PROTOCOL_VERSION_MISMATCH",
                    "The server returned an unsupported test execution response version.", null);
        }
    }

    private static void requireSuiteExecutionResponseVersion(JsonNode response) {
        String actual = response.path("schemaVersion").asText();
        if (!TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V1.equals(actual)
                && !TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V2.equals(actual)
                && !TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V3.equals(actual)
                && !TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V4.equals(actual)) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.PROTOCOL_VERSION_MISMATCH",
                    "The server returned an unsupported suite execution response version.", null);
        }
    }

    private static void requireSuiteEvidenceBundleVersion(JsonNode response) {
        String actual = response.path("schemaVersion").asText();
        if (!TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V1.equals(actual)
                && !TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V2.equals(actual)
                && !TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V3.equals(actual)) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.PROTOCOL_VERSION_MISMATCH",
                    "The server returned an unsupported suite evidence bundle version.", null);
        }
    }

    private URI endpoint(String path, String query) {
        String base = baseUri.toString();
        String value = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
        return URI.create(query == null || query.isBlank() ? value : value + "?" + query);
    }

    private static JsonNode requiredObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return value;
    }

    private static String segment(String value) {
        String normalized = normalized(value);
        if (normalized.isBlank() || normalized.length() > 512) {
            throw new IllegalArgumentException("URI path identifiers must contain 1 to 512 characters");
        }
        StringBuilder encoded = new StringBuilder();
        for (byte current : normalized.getBytes(StandardCharsets.UTF_8)) {
            int octet = current & 0xff;
            if ((octet >= 'a' && octet <= 'z') || (octet >= 'A' && octet <= 'Z')
                    || (octet >= '0' && octet <= '9') || octet == '-' || octet == '.'
                    || octet == '_' || octet == '~') {
                encoded.append((char) octet);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((octet >>> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(octet & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static String requiredIdentifier(String value, String field, int maximum) {
        String normalized = normalized(value);
        if (normalized.isBlank() || normalized.length() > maximum
                || normalized.contains("\r") || normalized.contains("\n")) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maximum + " safe characters");
        }
        return normalized;
    }

    private static String requiredFingerprint(String value) {
        String normalized = normalized(value);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be a full lowercase SHA-256 fingerprint");
        }
        return normalized;
    }

    private static URI validateBaseUri(URI value) {
        Objects.requireNonNull(value, "baseUri is required");
        String scheme = normalized(value.getScheme()).toLowerCase(java.util.Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || value.getHost() == null
                || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must be an absolute HTTP(S) URI without credentials, query, or fragment");
        }
        return value;
    }

    private static String bounded(String value, int maximum) {
        String normalized = normalized(value);
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /** Mutable construction options for one immutable test client. */
    public static final class Builder {
        private final URI baseUri;
        private HttpClient httpClient;
        private BearerTokenProvider tokenProvider;
        private Supplier<String> correlationIdProvider = () -> UUID.randomUUID().toString();
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private int maxRequestBytes = DEFAULT_MAX_BODY_BYTES;
        private int maxResponseBytes = DEFAULT_MAX_BODY_BYTES;

        private Builder(URI baseUri) {
            this.baseUri = baseUri;
        }

        /**
         * Uses the supplied JDK client, for example one configured with enterprise TLS.
         * @param value configured HTTP client
         * @return this builder
         */
        public Builder httpClient(HttpClient value) {
            httpClient = Objects.requireNonNull(value, "httpClient");
            return this;
        }

        /**
         * Configures a request-time bearer token provider.
         * @param value credential provider
         * @return this builder
         */
        public Builder bearerToken(BearerTokenProvider value) {
            tokenProvider = Objects.requireNonNull(value, "tokenProvider");
            return this;
        }

        /**
         * Configures request correlation ids, useful when the calling test owns trace context.
         * @param value correlation-id supplier
         * @return this builder
         */
        public Builder correlationId(Supplier<String> value) {
            correlationIdProvider = Objects.requireNonNull(value, "correlationIdProvider");
            return this;
        }

        /**
         * Sets the connection timeout used when the builder creates the JDK client.
         * @param value positive timeout
         * @return this builder
         */
        public Builder connectTimeout(Duration value) {
            connectTimeout = positive(value, "connectTimeout");
            return this;
        }

        /**
         * Sets the per-request timeout.
         * @param value positive timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration value) {
            requestTimeout = positive(value, "requestTimeout");
            return this;
        }

        /**
         * Sets the encoded JSON request-body limit.
         * @param value positive byte limit
         * @return this builder
         */
        public Builder maxRequestBytes(int value) {
            maxRequestBytes = positive(value, "maxRequestBytes");
            return this;
        }

        /**
         * Sets the response-body limit enforced before JSON decoding.
         * @param value positive byte limit
         * @return this builder
         */
        public Builder maxResponseBytes(int value) {
            maxResponseBytes = positive(value, "maxResponseBytes");
            return this;
        }

        /**
         * Builds one thread-safe immutable client.
         * @return configured client
         */
        public ResourceGatewayTestClient build() {
            return new ResourceGatewayTestClient(this);
        }

        private static Duration positive(Duration value, String field) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }

        private static int positive(int value, String field) {
            if (value < 1) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }
    }
}
