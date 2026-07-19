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
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(24);

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

    /** Per-mutant scheduling strategy for an immutable mutation-suite run. */
    public enum MutationStrategy {
        /** Execute every oracle case for every planned mutant. */
        COLLECT_ALL,
        /** Stop only the current mutant's remaining cases after a signed assertion kill. */
        STOP_AFTER_KILL
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
     * Plans deterministic validator-proven property inputs for one exact graph contract.
     *
     * @param graphName registered graph name
     * @param seed deterministic generation seed
     * @param trials requested root trials from 1 through 16
     * @param maxShrinkSteps precomputed shrink steps per root from 0 through 5
     * @return defensive schema-validated property-plan JSON
     */
    public JsonNode planGraphPropertyCases(
            String graphName, long seed, int trials, int maxShrinkSteps) {
        return planPropertyCases("graphs", graphName, seed, trials, maxShrinkSteps);
    }

    /**
     * Plans bounded independently compiling pure-DSL mutants for one exact graph.
     *
     * <p>The returned value is an authoring plan. It contains fingerprints and structural AST
     * coordinates, not executable source, mutation-run evidence, or a mutation score. Callers
     * must inspect {@code status} and {@code gaps}; a schema-valid partial plan does not claim full
     * mutation coverage.</p>
     *
     * @param graphName registered graph name
     * @param maxMutants caller-selected result bound from 1 through 128
     * @return defensive schema-validated mutation-plan JSON
     */
    public JsonNode planGraphMutationCases(String graphName, int maxMutants) {
        if (maxMutants < 1 || maxMutants > 128) {
            throw new IllegalArgumentException("Mutation planning requires 1..128 mutants");
        }
        String exactGraphName = requiredIdentifier(graphName, "graphName", 512);
        JsonNode response = exchange("GET", "/api/testing/targets/graphs/"
                        + segment(exactGraphName) + "/mutation-cases",
                "maxMutants=" + maxMutants, "TEST_EXECUTION", null);
        requireVersion(response, TestingProtocol.TEST_MUTATION_CASE_PLAN_V1);
        TestingProtocolSchemaValidator.require(response, "testMutationCasePlan");
        if (!exactGraphName.equals(response.path("target").path("id").asText())) {
            throw responseContractInvalid(
                    "The server returned a mutation plan for a different graph target.");
        }
        return response.deepCopy();
    }

    /**
     * Materializes a caller-reviewed graph property plan as an immutable V4 suite.
     *
     * <p>The request must reference an existing assertion-bearing fixture. A successful response
     * does not imply execution support; clients must still inspect the server's
     * {@code propertySuiteExecution} capability.</p>
     *
     * @param graphName registered graph name
     * @param request schema-complete exact-plan materialization request
     * @return defensive schema-validated materialization JSON
     */
    public JsonNode materializeGraphPropertySuite(String graphName, JsonNode request) {
        return materializePropertySuite("graphs", graphName, request);
    }

    /**
     * Materializes one reviewed graph mutation plan and exact business oracle as a V5 suite.
     *
     * @param graphName registered graph name
     * @param request schema-complete exact-plan and exact-oracle materialization request
     * @return defensive schema-validated mutation materialization JSON
     */
    public JsonNode materializeGraphMutationSuite(String graphName, JsonNode request) {
        String exactGraphName = requiredIdentifier(graphName, "graphName", 512);
        JsonNode exactRequest = requiredObject(request, "request");
        TestingProtocolSchemaValidator.require(
                exactRequest, "testMutationSuiteMaterializationRequest");
        JsonNode response = exchange("POST", "/api/testing/targets/graphs/"
                        + segment(exactGraphName) + "/mutation-suites", "",
                "TEST_SUITE_WRITE", exactRequest);
        requireVersion(response, TestingProtocol.TEST_MUTATION_SUITE_MATERIALIZATION_V1);
        TestingProtocolSchemaValidator.require(response, "testMutationSuiteMaterialization");
        boolean exactIdentity = exactGraphName.equals(response.path("target").path("id").asText())
                && exactRequest.path("suiteId").asText().equals(
                response.path("suiteRef").path("suiteId").asText())
                && exactRequest.path("expectedTargetFingerprint").asText().equals(
                response.path("target").path("fingerprint").asText())
                && exactRequest.path("expectedSourceFingerprint").asText().equals(
                response.path("baselineSourceFingerprint").asText())
                && exactRequest.path("expectedGraphArtifactFingerprint").asText().equals(
                response.path("baselineGraphArtifactFingerprint").asText())
                && exactRequest.path("expectedPlanFingerprint").asText().equals(
                response.path("mutationPlanFingerprint").asText())
                && exactRequest.path("maxMutants").asInt()
                == response.path("mutationPolicy").path("maxMutants").asInt()
                && exactRequest.path("oracleSuiteRef").equals(response.path("oracleSuiteRef"));
        if (!exactIdentity) {
            throw responseContractInvalid(
                    "The server materialized a mutation suite for a different plan or oracle identity.");
        }
        return response.deepCopy();
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
     * Plans deterministic validator-proven property inputs for one exact operator binding.
     *
     * @param operatorRef registered operator reference
     * @param seed deterministic generation seed
     * @param trials requested root trials from 1 through 16
     * @param maxShrinkSteps precomputed shrink steps per root from 0 through 5
     * @return defensive schema-validated property-plan JSON
     */
    public JsonNode planOperatorPropertyCases(
            String operatorRef, long seed, int trials, int maxShrinkSteps) {
        return planPropertyCases("operators", operatorRef, seed, trials, maxShrinkSteps);
    }

    /**
     * Materializes a caller-reviewed operator property plan as an immutable V4 suite.
     *
     * @param operatorRef registered operator reference
     * @param request schema-complete exact-plan materialization request
     * @return defensive schema-validated materialization JSON
     */
    public JsonNode materializeOperatorPropertySuite(String operatorRef, JsonNode request) {
        return materializePropertySuite("operators", operatorRef, request);
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
     * Executes a bounded idempotent stability analysis over one exact immutable suite revision.
     *
     * <p>The response is schema-validated and its semantic aggregate, evidence fingerprint, and
     * ordered source closure are independently re-derived. Call
     * {@link #verifySuiteStability(String)} or the pinned-key-set overload before using the result
     * as release evidence.</p>
     *
     * @param suiteId exact suite id
     * @param revision exact immutable revision
     * @param fingerprint full SHA-256 suite fingerprint
     * @param clientRequestId caller-stable parent idempotency key
     * @param attempts independent rerun count from 3 through 20
     * @param metadata bounded scalar provenance metadata
     * @return typed terminal stability result
     */
    public TestSuiteStabilityRun executeSuiteStability(
            String suiteId,
            long revision,
            String fingerprint,
            String clientRequestId,
            int attempts,
            Map<String, ?> metadata) {
        if (revision < 1 || attempts < 3 || attempts > 20) {
            throw new IllegalArgumentException(
                    "revision must be positive and stability attempts must be 3..20");
        }
        return executeSuiteStabilityRequest(suiteId, revision, fingerprint, clientRequestId,
                attempts, null, metadata,
                TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V1,
                TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V2);
    }

    /**
     * Executes a precommitted fixed-horizon or anytime-valid analysis of one suite revision.
     *
     * <p>The test-kit rejects an insufficient horizon before network I/O. It selects the exact
     * request and response generation from the policy model, independently reconstructs the
     * attempt-by-case closure, and binds the result to the request. Cryptographic verification is
     * still required before the result can enter a release gate.</p>
     *
     * @param suiteId exact suite id
     * @param revision exact immutable revision
     * @param fingerprint full SHA-256 suite fingerprint
     * @param clientRequestId caller-stable parent idempotency key
     * @param attempts precommitted rerun horizon from 3 through 1000
     * @param policy exact supported statistical policy
     * @param metadata bounded scalar provenance metadata
     * @return typed terminal statistical stability result
     */
    public TestSuiteStabilityRun executeStatisticalSuiteStability(
            String suiteId,
            long revision,
            String fingerprint,
            String clientRequestId,
            int attempts,
            TestSuiteStabilityStatisticalPolicy policy,
            Map<String, ?> metadata) {
        Objects.requireNonNull(policy, "statistical stability policy is required");
        if (revision < 1 || attempts < TestSuiteStabilityStatisticalPolicy.MIN_ATTEMPTS
                || attempts > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "revision must be positive and statistical stability attempts must be 3..1000");
        }
        int minimum = policy.minimumRequiredAttempts();
        if (attempts < minimum || !policy.horizonSufficient(attempts)) {
            throw new IllegalArgumentException(
                    "statistical stability attempts must satisfy the precommitted horizon; minimum="
                            + minimum);
        }
        String requestVersion = switch (policy.model()) {
            case ZERO_INSTABILITY_EXACT_BINOMIAL ->
                    TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V2;
            case BASELINE_CONDITIONAL_EXACT_BINOMIAL ->
                    TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V3;
            case BASELINE_CONDITIONAL_ANYTIME_VALID_E_PROCESS ->
                    TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_REQUEST_V4;
        };
        String responseVersion = switch (policy.model()) {
            case ZERO_INSTABILITY_EXACT_BINOMIAL ->
                    TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V3;
            case BASELINE_CONDITIONAL_EXACT_BINOMIAL ->
                    TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V4;
            case BASELINE_CONDITIONAL_ANYTIME_VALID_E_PROCESS ->
                    TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V5;
        };
        return executeSuiteStabilityRequest(suiteId, revision, fingerprint, clientRequestId,
                attempts, policy, metadata, requestVersion, responseVersion);
    }

    private TestSuiteStabilityRun executeSuiteStabilityRequest(
            String suiteId,
            long revision,
            String fingerprint,
            String clientRequestId,
            int attempts,
            TestSuiteStabilityStatisticalPolicy policy,
            Map<String, ?> metadata,
            String requestVersion,
            String responseVersion) {
        String id = requiredIdentifier(clientRequestId, "clientRequestId", 255);
        String exactSuiteId = requiredIdentifier(suiteId, "suiteId", 255);
        String exactFingerprint = requiredFingerprint(fingerprint);
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion", requestVersion);
        ObjectNode suiteRef = request.putObject("suiteRef");
        suiteRef.put("suiteId", exactSuiteId);
        suiteRef.put("revision", revision);
        suiteRef.put("fingerprint", exactFingerprint);
        request.put("clientRequestId", id);
        request.put("attempts", attempts);
        if (policy != null) {
            ObjectNode statistical = request.putObject("statisticalPolicy");
            statistical.put("model", policy.model().name());
            statistical.put("claimScope", policy.claimScope().name());
            statistical.put("stoppingRule", policy.stoppingRule().name());
            statistical.put("censoringPolicy", policy.censoringPolicy().name());
            statistical.put("confidenceLevelBps", policy.confidenceLevelBps());
            statistical.put("maximumInstabilityRateBps",
                    policy.maximumInstabilityRateBps());
            if (policy.alternativeInstabilityRateBps() != null) {
                statistical.put("alternativeInstabilityRateBps",
                        policy.alternativeInstabilityRateBps());
            }
        }
        request.set("metadata", metadata == null
                ? JSON.createObjectNode() : JSON.valueToTree(metadata));
        TestingProtocolSchemaValidator.require(
                request, "testSuiteStabilityExecutionRequest");
        JsonNode response = exchange("POST", "/api/testing/suites/"
                        + segment(exactSuiteId) + "/stability-executions", "",
                "TEST_EXECUTION", request);
        requireVersion(response, responseVersion);
        TestSuiteStabilityRun run = projectStabilityRun(response);
        try {
            run.requireExecutionIdentity(
                    exactSuiteId, revision, exactFingerprint, id, attempts);
            if (!EvidenceVerificationSupport.sha256(request)
                    .equals(run.attestation().requestFingerprint())) {
                throw new IllegalArgumentException(
                        "Stability request fingerprint does not match the request");
            }
            if (policy != null && (!run.statisticalConfidenceAvailable()
                    || !policy.equals(run.statisticalAssessment().policy()))) {
                throw new IllegalArgumentException(
                        "Statistical stability policy does not match the request");
            }
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned a mismatched stability execution identity.");
        }
        return run;
    }

    /**
     * Requests one signed retained-window trend over an exact immutable suite revision.
     *
     * <p>The response is validated against the packaged protocol Schema and bound back to every
     * request field. Parsing does not establish source or signature trust; call one of the
     * {@code verifySuiteStabilityTrend} overloads for offline reconstruction.</p>
     *
     * @param request exact bounded trend intent
     * @return strict payload-free trend projection
     */
    public TestSuiteStabilityTrendAnalysis analyzeSuiteStabilityTrend(
            TestSuiteStabilityTrendRequest request) {
        TestSuiteStabilityTrendRequest exactRequest = Objects.requireNonNull(
                request, "suite-stability trend request is required");
        JsonNode response = exchange("POST", "/api/testing/suites/"
                        + segment(exactRequest.suiteId()) + "/stability-trend-analyses", "",
                "TEST_EXECUTION", exactRequest.toJson());
        requireVersion(response,
                TestingProtocol.TEST_SUITE_STABILITY_TREND_ANALYSIS_RESPONSE_V1);
        TestSuiteStabilityTrendAnalysis analysis = projectStabilityTrend(response);
        if (!exactRequest.equals(analysis.request())) {
            throw responseContractInvalid(
                    "The server returned a trend for a different suite or window.");
        }
        return analysis;
    }

    /**
     * Fetches all retained source evidence and independently verifies one new trend analysis.
     *
     * <p>Public keys are resolved by exact attestation key id. Known key-provider absence is
     * returned as a bounded verification result; transport and unrelated protocol failures remain
     * exceptions.</p>
     *
     * @param request exact bounded trend intent
     * @return source-closed offline verification result
     */
    public TestSuiteStabilityTrendEvidenceVerifier.VerificationResult
            verifySuiteStabilityTrend(TestSuiteStabilityTrendRequest request) {
        TestSuiteStabilityTrendAnalysis analysis = analyzeSuiteStabilityTrend(request);
        List<TestSuiteStabilityRun> sources = fetchTrendSources(analysis);
        Map<String, EvidenceVerificationKey> keys = new LinkedHashMap<>();
        Set<String> keyIds = new LinkedHashSet<>();
        sources.forEach(source -> keyIds.add(source.attestation().keyId()));
        keyIds.add(analysis.attestation().keyId());
        for (String keyId : keyIds) {
            try {
                keys.put(keyId, findEvidenceVerificationKey(keyId));
            } catch (ResourceGatewayTestException failure) {
                if (!"RG.INTEGRATION.EVIDENCE_KEY_NOT_FOUND".equals(failure.code())
                        && !"RG.INTEGRATION.EVIDENCE_KEY_PROVIDER_UNAVAILABLE"
                        .equals(failure.code())) {
                    throw failure;
                }
            }
        }
        return new TestSuiteStabilityTrendEvidenceVerifier().verify(
                analysis, sources, keys);
    }

    /**
     * Performs release-grade trend verification against an independently pinned key-set snapshot.
     *
     * @param request exact bounded trend intent
     * @param trustedKeySetFingerprint key-set fingerprint pinned outside Gateway output
     * @return lifecycle-aware source-closed verification result
     */
    public TestSuiteStabilityTrendEvidenceVerifier.VerificationResult
            verifySuiteStabilityTrend(
            TestSuiteStabilityTrendRequest request,
            String trustedKeySetFingerprint) {
        TestSuiteStabilityTrendAnalysis analysis = analyzeSuiteStabilityTrend(request);
        List<TestSuiteStabilityRun> sources = fetchTrendSources(analysis);
        EvidenceVerificationKeySet keySet;
        try {
            keySet = findEvidenceVerificationKeySet();
        } catch (ResourceGatewayTestException failure) {
            if ("RG.INTEGRATION.EVIDENCE_KEY_SET_PROVIDER_UNAVAILABLE".equals(failure.code())
                    || "RG.INTEGRATION.EVIDENCE_KEY_SET_ATTESTATION_UNAVAILABLE"
                    .equals(failure.code())) {
                keySet = null;
            } else {
                throw failure;
            }
        }
        return new TestSuiteStabilityTrendEvidenceVerifier().verify(
                analysis, sources, keySet, trustedKeySetFingerprint);
    }

    /**
     * Requests one signed trend over a head-pinned compact-observation ledger range.
     *
     * <p>The response embeds the exact request and contains no business payload. Parsing validates
     * strict Schema and canonical closure but does not establish signature or derived-label trust;
     * call one of the {@code verifySuiteStabilityCrossRetentionTrend} overloads before using the
     * result as governance evidence.</p>
     *
     * @param request exact bounded cursor and head-pin intent
     * @return strict payload-free compact-range projection
     */
    public TestSuiteStabilityCrossRetentionTrendAnalysis
            analyzeSuiteStabilityCrossRetentionTrend(
            TestSuiteStabilityCrossRetentionTrendRequest request) {
        TestSuiteStabilityCrossRetentionTrendRequest exactRequest = Objects.requireNonNull(
                request, "cross-retention trend request is required");
        JsonNode response = exchange("POST", "/api/testing/suites/"
                        + segment(exactRequest.suiteId())
                        + "/stability-cross-retention-trend-analyses", "",
                "TEST_EXECUTION", exactRequest.toJson());
        requireVersion(response,
                TestingProtocol.TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_RESPONSE_V1);
        TestSuiteStabilityCrossRetentionTrendAnalysis analysis =
                projectStabilityCrossRetentionTrend(response);
        if (!exactRequest.equals(analysis.request())) {
            throw responseContractInvalid(
                    "The server returned a compact trend for a different suite or cursor.");
        }
        return analysis;
    }

    /**
     * Independently verifies compact observations without fetching retained source runs.
     *
     * <p>Every observation and outer public key is resolved by exact key id. Known authority
     * absence becomes a bounded verification outcome; transport and unrelated protocol failures
     * remain exceptions.</p>
     *
     * @param request exact bounded cursor and head-pin intent
     * @return compact-range verification result
     */
    public TestSuiteStabilityCrossRetentionTrendEvidenceVerifier.VerificationResult
            verifySuiteStabilityCrossRetentionTrend(
            TestSuiteStabilityCrossRetentionTrendRequest request) {
        TestSuiteStabilityCrossRetentionTrendAnalysis analysis =
                analyzeSuiteStabilityCrossRetentionTrend(request);
        Set<String> keyIds = new LinkedHashSet<>();
        analysis.range().entries().forEach(entry ->
                keyIds.add(entry.observation().attestation().keyId()));
        keyIds.add(analysis.attestation().keyId());
        Map<String, EvidenceVerificationKey> keys = new LinkedHashMap<>();
        for (String keyId : keyIds) {
            try {
                keys.put(keyId, findEvidenceVerificationKey(keyId));
            } catch (ResourceGatewayTestException failure) {
                if (!"RG.INTEGRATION.EVIDENCE_KEY_NOT_FOUND".equals(failure.code())
                        && !"RG.INTEGRATION.EVIDENCE_KEY_PROVIDER_UNAVAILABLE"
                        .equals(failure.code())) {
                    throw failure;
                }
            }
        }
        return new TestSuiteStabilityCrossRetentionTrendEvidenceVerifier().verify(
                analysis, keys);
    }

    /**
     * Performs release-grade compact-range verification against an independently pinned key set.
     *
     * <p>The verifier applies signing-time lifecycle policy to every compact observation and the
     * outer range signature. It never depends on source stability records surviving retention.</p>
     *
     * @param request exact bounded cursor and head-pin intent
     * @param trustedKeySetFingerprint key-set fingerprint pinned outside Gateway output
     * @return lifecycle-aware compact-range verification result
     */
    public TestSuiteStabilityCrossRetentionTrendEvidenceVerifier.VerificationResult
            verifySuiteStabilityCrossRetentionTrend(
            TestSuiteStabilityCrossRetentionTrendRequest request,
            String trustedKeySetFingerprint) {
        TestSuiteStabilityCrossRetentionTrendAnalysis analysis =
                analyzeSuiteStabilityCrossRetentionTrend(request);
        EvidenceVerificationKeySet keySet;
        try {
            keySet = findEvidenceVerificationKeySet();
        } catch (ResourceGatewayTestException failure) {
            if ("RG.INTEGRATION.EVIDENCE_KEY_SET_PROVIDER_UNAVAILABLE".equals(failure.code())
                    || "RG.INTEGRATION.EVIDENCE_KEY_SET_ATTESTATION_UNAVAILABLE"
                    .equals(failure.code())) {
                keySet = null;
            } else {
                throw failure;
            }
        }
        return new TestSuiteStabilityCrossRetentionTrendEvidenceVerifier().verify(
                analysis, keySet, trustedKeySetFingerprint);
    }

    /**
     * Reads one signed floor-retirement lifecycle page for an exact suite revision.
     *
     * <p>Parsing enforces strict Schema and response/request binding but does not establish
     * cryptographic trust. Verify the page and its checkpoint before constructing a continuation
     * or using the returned floor to seed a cross-retention range request.</p>
     *
     * @param request exact generation cursor, page bound, and snapshot pins
     * @return strict payload-free lifecycle page
     */
    public TestSuiteStabilityObservationLedgerLifecyclePage
            readSuiteStabilityObservationLedgerLifecyclePage(
            TestSuiteStabilityObservationLedgerLifecycleRequest request) {
        TestSuiteStabilityObservationLedgerLifecycleRequest exactRequest =
                Objects.requireNonNull(request, "observation lifecycle request is required");
        JsonNode response = exchange("POST", "/api/testing/suites/"
                        + segment(exactRequest.suiteId())
                        + "/stability-observation-ledger-lifecycle-pages", "",
                "TEST_EXECUTION", exactRequest.toJson());
        requireVersion(response,
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_RESPONSE_V1);
        TestSuiteStabilityObservationLedgerLifecyclePage page =
                projectStabilityObservationLifecyclePage(response);
        if (!exactRequest.equals(page.request())) {
            throw responseContractInvalid(
                    "The server returned a lifecycle page for a different suite or cursor.");
        }
        return page;
    }

    /**
     * Independently verifies one first lifecycle page using exact public keys from Gateway.
     *
     * @param request exact generation-zero request
     * @return bounded page and transition verification result
     */
    public TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.VerificationResult
            verifySuiteStabilityObservationLedgerLifecyclePage(
            TestSuiteStabilityObservationLedgerLifecycleRequest request) {
        return verifySuiteStabilityObservationLedgerLifecyclePage(request, null);
    }

    /**
     * Independently verifies one continuation lifecycle page using an already verified checkpoint.
     *
     * @param request exact first or continuation request
     * @param previous null for rollout; verified previous-page checkpoint otherwise
     * @return bounded page and transition verification result
     */
    public TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.VerificationResult
            verifySuiteStabilityObservationLedgerLifecyclePage(
            TestSuiteStabilityObservationLedgerLifecycleRequest request,
            TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.LifecycleCheckpoint
                    previous) {
        TestSuiteStabilityObservationLedgerLifecyclePage page =
                readSuiteStabilityObservationLedgerLifecyclePage(request);
        Map<String, EvidenceVerificationKey> keys = new LinkedHashMap<>();
        for (String keyId : TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier
                .requiredKeyIds(page)) {
            try {
                keys.put(keyId, findEvidenceVerificationKey(keyId));
            } catch (ResourceGatewayTestException failure) {
                if (!"RG.INTEGRATION.EVIDENCE_KEY_NOT_FOUND".equals(failure.code())
                        && !"RG.INTEGRATION.EVIDENCE_KEY_PROVIDER_UNAVAILABLE"
                        .equals(failure.code())) {
                    throw failure;
                }
            }
        }
        return new TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier().verify(
                page, previous, keys);
    }

    /**
     * Performs release-grade lifecycle-page verification against an independently pinned key set.
     *
     * @param request exact first or continuation request
     * @param previous null for rollout; verified previous-page checkpoint otherwise
     * @param trustedKeySetFingerprint key-set fingerprint pinned outside Gateway output
     * @return lifecycle-aware page verification result
     */
    public TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.VerificationResult
            verifySuiteStabilityObservationLedgerLifecyclePage(
            TestSuiteStabilityObservationLedgerLifecycleRequest request,
            TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.LifecycleCheckpoint
                    previous,
            String trustedKeySetFingerprint) {
        TestSuiteStabilityObservationLedgerLifecyclePage page =
                readSuiteStabilityObservationLedgerLifecyclePage(request);
        EvidenceVerificationKeySet keySet;
        try {
            keySet = findEvidenceVerificationKeySet();
        } catch (ResourceGatewayTestException failure) {
            if ("RG.INTEGRATION.EVIDENCE_KEY_SET_PROVIDER_UNAVAILABLE".equals(failure.code())
                    || "RG.INTEGRATION.EVIDENCE_KEY_SET_ATTESTATION_UNAVAILABLE"
                    .equals(failure.code())) {
                keySet = null;
            } else {
                throw failure;
            }
        }
        return new TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier().verify(
                page, previous, keySet, trustedKeySetFingerprint);
    }

    /**
     * Submits one asynchronous suite-stability job without implicit retry.
     *
     * <p>The client requires {@code 202}, validates the response against the packaged Schema,
     * binds every immutable response field to the request, and requires the canonical relative
     * query location. The method returns after durable admission and never waits for execution.</p>
     *
     * @param request exact schema-validated queue intent
     * @return typed payload-free durable admission result
     */
    public TestSuiteStabilityJobSubmission submitSuiteStabilityJob(
            TestSuiteStabilityJobRequest request) {
        return submitSuiteStabilityJobOnce(
                Objects.requireNonNull(request, "stability job request is required"));
    }

    /**
     * Submits one idempotent asynchronous job with dual-bounded retry.
     *
     * <p>Only server-declared retryable {@code 429} and {@code 503} failures are retried. The same
     * immutable request and idempotency key are reused for every attempt. A server delay that
     * exceeds a policy bound stops retry instead of violating {@code Retry-After}.</p>
     *
     * @param request exact schema-validated queue intent
     * @param retryPolicy request-count, delay, and elapsed-time bounds
     * @return typed payload-free durable admission result
     */
    public TestSuiteStabilityJobSubmission submitSuiteStabilityJob(
            TestSuiteStabilityJobRequest request,
            TestSuiteStabilityJobRetryPolicy retryPolicy) {
        TestSuiteStabilityJobRequest exactRequest = Objects.requireNonNull(
                request, "stability job request is required");
        return retryStabilityOperation(
                () -> submitSuiteStabilityJobOnce(exactRequest), retryPolicy);
    }

    /**
     * Retrieves one retained payload-free asynchronous job lifecycle.
     *
     * @param jobId deterministic durable job identity
     * @return strict typed lifecycle projection
     */
    public TestSuiteStabilityJob findSuiteStabilityJob(String jobId) {
        String exactJobId = requiredStabilityJobId(jobId);
        JsonNode response = exchange("GET", "/api/testing/stability-jobs/"
                + segment(exactJobId), "", "TEST_EXECUTION", null);
        TestSuiteStabilityJob job = projectStabilityJob(response);
        requireStabilityJobIdentity(job, exactJobId);
        return job;
    }

    /**
     * Requests idempotent queued or cooperative running cancellation.
     *
     * <p>A returned {@code COMMITTING} or terminal state means cancellation did not retroactively
     * win. Callers must inspect the typed lifecycle instead of assuming cancellation succeeded.</p>
     *
     * @param jobId deterministic durable job identity
     * @param clientRequestId caller-stable cancellation command identity
     * @return resulting strict typed lifecycle projection
     */
    public TestSuiteStabilityJob cancelSuiteStabilityJob(
            String jobId,
            String clientRequestId) {
        String exactJobId = requiredStabilityJobId(jobId);
        String cancellationId = requiredProtocolIdentifier(
                clientRequestId, "clientRequestId");
        return cancelSuiteStabilityJobOnce(exactJobId, cancellationId);
    }

    /**
     * Retries one idempotent cancellation command inside explicit dual bounds.
     *
     * <p>Every attempt carries the same job and cancellation identities. Only server-declared
     * retryable {@code 429}/{@code 503} responses are retried, with the same fail-closed
     * {@code Retry-After} behavior as asynchronous submission.</p>
     *
     * @param jobId deterministic durable job identity
     * @param clientRequestId caller-stable cancellation command identity
     * @param retryPolicy request-count, delay, and elapsed-time bounds
     * @return resulting strict typed lifecycle projection
     */
    public TestSuiteStabilityJob cancelSuiteStabilityJob(
            String jobId,
            String clientRequestId,
            TestSuiteStabilityJobRetryPolicy retryPolicy) {
        String exactJobId = requiredStabilityJobId(jobId);
        String cancellationId = requiredProtocolIdentifier(
                clientRequestId, "clientRequestId");
        return retryStabilityOperation(
                () -> cancelSuiteStabilityJobOnce(exactJobId, cancellationId), retryPolicy);
    }

    private TestSuiteStabilityJob cancelSuiteStabilityJobOnce(
            String exactJobId,
            String cancellationId) {
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_JOB_CANCEL_REQUEST_V1);
        request.put("clientRequestId", cancellationId);
        TestingProtocolSchemaValidator.require(
                request, "testSuiteStabilityJobCancelRequest");
        JsonNode response = exchange("POST", "/api/testing/stability-jobs/"
                        + segment(exactJobId) + "/cancellations", "",
                "TEST_EXECUTION", request);
        TestSuiteStabilityJob job = projectStabilityJob(response);
        requireStabilityJobIdentity(job, exactJobId);
        return job;
    }

    /**
     * Polls one asynchronous job until any closed terminal state is retained.
     *
     * <p>The method does not translate {@code FAILED}, {@code CANCELLED}, {@code EXPIRED}, or
     * {@code QUARANTINED} into success. It returns the exact terminal lifecycle for caller policy.
     * Query retry is limited to server-declared retryable {@code 429}/{@code 503} responses and
     * honors only bounded {@code Retry-After} values.</p>
     *
     * @param jobId deterministic durable job identity
     * @param pollingPolicy request-count, interval, server-delay, and elapsed-time bounds
     * @return first observed terminal lifecycle
     */
    public TestSuiteStabilityJob awaitSuiteStabilityJob(
            String jobId,
            TestSuiteStabilityJobPollingPolicy pollingPolicy) {
        String exactJobId = requiredStabilityJobId(jobId);
        TestSuiteStabilityJobPollingPolicy policy = Objects.requireNonNull(
                pollingPolicy, "stability job polling policy is required");
        long startedAt = System.nanoTime();
        for (int poll = 1; poll <= policy.maximumPolls(); poll++) {
            if (!fitsElapsedBound(startedAt, Duration.ZERO, policy.maximumElapsed())) {
                throw pollExhausted();
            }
            Duration delay;
            try {
                TestSuiteStabilityJob job = findSuiteStabilityJob(exactJobId);
                if (!fitsElapsedBound(startedAt, Duration.ZERO, policy.maximumElapsed())) {
                    throw pollExhausted();
                }
                if (job.terminal()) {
                    return job;
                }
                if (poll == policy.maximumPolls()) {
                    throw pollExhausted();
                }
                delay = policy.interval();
            } catch (ResourceGatewayTestException failure) {
                if (!retryableStabilityOperation(failure)
                        || poll == policy.maximumPolls()) {
                    throw failure;
                }
                requireUsableRetryDirective(failure);
                delay = failure.retryAfter().orElse(policy.interval());
                if (delay.compareTo(policy.maximumServerDelay()) > 0) {
                    throw failure;
                }
            }
            if (!fitsElapsedBound(startedAt, delay, policy.maximumElapsed())) {
                throw pollExhausted();
            }
            pause(delay);
        }
        throw pollExhausted();
    }

    private TestSuiteStabilityJobSubmission submitSuiteStabilityJobOnce(
            TestSuiteStabilityJobRequest request) {
        ExchangeResponse response = exchangeResponse(
                "POST", "/api/testing/suites/" + segment(request.suiteId())
                        + "/stability-jobs", "", "TEST_EXECUTION", request.rawRequest());
        if (response.status() != 202) {
            throw responseContractInvalid(
                    "The stability-job endpoint did not return durable admission status.");
        }
        requireVersion(response.body(),
                TestingProtocol.TEST_SUITE_STABILITY_JOB_SUBMIT_RESPONSE_V1);
        TestSuiteStabilityJobSubmission submission = projectStabilityJobSubmission(
                response.body());
        try {
            submission.requireSubmission(request);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned a stability job for a different submission intent.");
        }
        String expectedLocation = "/api/testing/stability-jobs/" + submission.job().jobId();
        if (!expectedLocation.equals(response.headers().firstValue("Location").orElse(""))) {
            throw responseContractInvalid(
                    "The server returned a non-canonical stability-job query location.");
        }
        return submission;
    }

    private <T> T retryStabilityOperation(
            Supplier<T> operation,
            TestSuiteStabilityJobRetryPolicy retryPolicy) {
        TestSuiteStabilityJobRetryPolicy policy = Objects.requireNonNull(
                retryPolicy, "stability job retry policy is required");
        long startedAt = System.nanoTime();
        Duration localDelay = policy.initialDelay();
        for (int attempt = 1; attempt <= policy.maximumAttempts(); attempt++) {
            try {
                return operation.get();
            } catch (ResourceGatewayTestException failure) {
                if (!retryableStabilityOperation(failure)
                        || attempt == policy.maximumAttempts()) {
                    throw failure;
                }
                requireUsableRetryDirective(failure);
                Duration delay = failure.retryAfter().orElse(localDelay);
                if (delay.compareTo(policy.maximumDelay()) > 0
                        || !fitsElapsedBound(startedAt, delay, policy.maximumElapsed())) {
                    throw failure;
                }
                pause(delay);
                localDelay = doubled(localDelay, policy.maximumDelay());
            }
        }
        throw new IllegalStateException("Unreachable stability-job retry state");
    }

    /**
     * Executes one exact immutable V5 suite in the isolated pure-DSL mutation runtime.
     *
     * <p>The strategy is scoped to each mutant; it can never truncate later mutants. The returned
     * projection independently re-derives baseline, mutant classification, and score closure
     * before it can be consumed by CI.</p>
     *
     * @param suiteId exact V5 suite id
     * @param revision exact immutable revision
     * @param fingerprint full SHA-256 suite fingerprint
     * @param clientRequestId stable idempotency key for this mutation intent
     * @param strategy per-mutant scheduling strategy, defaulting to COLLECT_ALL
     * @param metadata bounded provenance metadata
     * @return typed pure-DSL mutation run projection
     */
    public TestSuiteRun executeMutationSuite(
            String suiteId, long revision, String fingerprint, String clientRequestId,
            MutationStrategy strategy, Map<String, ?> metadata) {
        String id = requiredIdentifier(clientRequestId, "clientRequestId", 255);
        String exactSuiteId = requiredIdentifier(suiteId, "suiteId", 255);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        String exactFingerprint = requiredFingerprint(fingerprint);
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion", TestingProtocol.TEST_MUTATION_SUITE_EXECUTION_REQUEST_V1);
        ObjectNode suiteRef = request.putObject("suiteRef");
        suiteRef.put("suiteId", exactSuiteId);
        suiteRef.put("revision", revision);
        suiteRef.put("fingerprint", exactFingerprint);
        request.put("clientRequestId", id);
        request.put("strategy", (strategy == null
                ? MutationStrategy.COLLECT_ALL : strategy).name());
        request.set("metadata", metadata == null
                ? JSON.createObjectNode() : JSON.valueToTree(metadata));
        TestingProtocolSchemaValidator.require(request, "testMutationSuiteExecutionRequest");
        JsonNode response = exchange("POST", "/api/testing/suites/"
                        + segment(exactSuiteId) + "/mutation-executions", "",
                "TEST_EXECUTION", request);
        requireSuiteExecutionResponseVersion(response);
        TestSuiteRun run = projectSuiteRun(response);
        requireSuiteRunIdentity(run, exactSuiteId, revision, exactFingerprint, id);
        if (run.evaluationMode() != TestSuiteRun.EvaluationMode.PURE_DSL_MUTATION) {
            throw responseContractInvalid(
                    "The mutation endpoint returned a non-mutation suite run.");
        }
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
     * Retrieves one retained terminal suite-stability analysis.
     *
     * @param stabilityRunId deterministic stability analysis id
     * @return typed result with independently re-derived semantics and source closure
     */
    public TestSuiteStabilityRun findSuiteStability(String stabilityRunId) {
        String exactId = requiredIdentifier(stabilityRunId, "stabilityRunId", 255);
        JsonNode response = exchange("GET", "/api/testing/stability-executions/"
                + segment(exactId), "", "TEST_EXECUTION", null);
        requireStabilityResponseVersion(response);
        TestSuiteStabilityRun run = projectStabilityRun(response);
        try {
            run.requireRunIdentity(exactId);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned a mismatched stability analysis identity.");
        }
        return run;
    }

    /**
     * Retrieves payload-free durable progress for an active, takeover-ready, or completed parent.
     *
     * <p>This operational projection is suitable for polling and recovery coordination. It is not
     * signed release evidence and intentionally omits source attempt and lease coordinates.</p>
     *
     * @param stabilityRunId deterministic stability parent id
     * @return strict typed progress projection
     */
    public TestSuiteStabilityProgress findSuiteStabilityProgress(String stabilityRunId) {
        String exactId = requiredIdentifier(stabilityRunId, "stabilityRunId", 255);
        JsonNode response = exchange("GET", "/api/testing/stability-executions/"
                + segment(exactId) + "/progress", "", "TEST_EXECUTION", null);
        TestSuiteStabilityProgress progress = projectStabilityProgress(response);
        try {
            progress.requireRunIdentity(exactId);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned a mismatched suite-stability progress identity.");
        }
        return progress;
    }

    /**
     * Fetches and independently verifies one retained stability analysis with its public key.
     *
     * @param stabilityRunId deterministic stability analysis id
     * @return payload-free offline verification result
     */
    public TestSuiteStabilityEvidenceVerifier.VerificationResult verifySuiteStability(
            String stabilityRunId) {
        TestSuiteStabilityRun run = findSuiteStability(stabilityRunId);
        EvidenceVerificationKey key;
        try {
            key = findEvidenceVerificationKey(run.attestation().keyId());
        } catch (ResourceGatewayTestException failure) {
            if ("RG.INTEGRATION.EVIDENCE_KEY_NOT_FOUND".equals(failure.code())
                    || "RG.INTEGRATION.EVIDENCE_KEY_PROVIDER_UNAVAILABLE".equals(
                    failure.code())) {
                return new TestSuiteStabilityEvidenceVerifier().verify(run, null);
            }
            throw failure;
        }
        return new TestSuiteStabilityEvidenceVerifier().verify(run, key);
    }

    /**
     * Performs release-grade stability verification against an independently pinned key set.
     *
     * @param stabilityRunId deterministic stability analysis id
     * @param trustedKeySetFingerprint key-set fingerprint pinned outside the Gateway response
     * @return payload-free lifecycle-aware verification result
     */
    public TestSuiteStabilityEvidenceVerifier.VerificationResult verifySuiteStability(
            String stabilityRunId,
            String trustedKeySetFingerprint) {
        TestSuiteStabilityRun run = findSuiteStability(stabilityRunId);
        EvidenceVerificationKeySet keySet;
        try {
            keySet = findEvidenceVerificationKeySet();
        } catch (ResourceGatewayTestException failure) {
            if ("RG.INTEGRATION.EVIDENCE_KEY_SET_PROVIDER_UNAVAILABLE".equals(failure.code())
                    || "RG.INTEGRATION.EVIDENCE_KEY_SET_ATTESTATION_UNAVAILABLE".equals(
                    failure.code())) {
                return new TestSuiteStabilityEvidenceVerifier().verify(
                        run, null, trustedKeySetFingerprint);
            }
            throw failure;
        }
        return new TestSuiteStabilityEvidenceVerifier().verify(
                run, keySet, trustedKeySetFingerprint);
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

    private JsonNode planPropertyCases(
            String targetCollection,
            String targetId,
            long seed,
            int trials,
            int maxShrinkSteps) {
        if (trials < 1 || trials > 16 || maxShrinkSteps < 0 || maxShrinkSteps > 5) {
            throw new IllegalArgumentException(
                    "Property planning requires 1..16 trials and 0..5 shrink steps");
        }
        String query = "seed=" + seed + "&trials=" + trials
                + "&maxShrinkSteps=" + maxShrinkSteps;
        JsonNode response = exchange("GET", "/api/testing/targets/" + targetCollection + "/"
                + segment(targetId) + "/property-cases", query, "TEST_EXECUTION", null);
        requireVersion(response, TestingProtocol.TEST_PROPERTY_CASE_PLAN_V1);
        TestingProtocolSchemaValidator.require(response, "testPropertyCasePlan");
        return response.deepCopy();
    }

    private JsonNode materializePropertySuite(
            String targetCollection,
            String targetId,
            JsonNode requestValue) {
        JsonNode request = requiredObject(requestValue, "request");
        TestingProtocolSchemaValidator.require(
                request, "testPropertySuiteMaterializationRequest");
        JsonNode response = exchange("POST", "/api/testing/targets/" + targetCollection + "/"
                + segment(targetId) + "/property-suites", "", "TEST_SUITE_WRITE", request);
        requireVersion(response, TestingProtocol.TEST_PROPERTY_SUITE_MATERIALIZATION_V1);
        TestingProtocolSchemaValidator.require(response, "testPropertySuiteMaterialization");
        return response.deepCopy();
    }

    private JsonNode exchange(String method, String path, String query, String purpose, JsonNode body) {
        return exchangeResponse(method, path, query, purpose, body).body();
    }

    private ExchangeResponse exchangeResponse(
            String method,
            String path,
            String query,
            String purpose,
            JsonNode body) {
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
                throw problem(response.statusCode(), decoded, response.headers());
            }
            if (!decoded.isObject()) {
                throw ResourceGatewayTestException.local("RG.TESTKIT.RESPONSE_MALFORMED",
                        "The server returned a non-object JSON response.", null);
            }
            return new ExchangeResponse(response.statusCode(), response.headers(), decoded);
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

    private static ResourceGatewayTestException problem(
            int status,
            JsonNode body,
            HttpHeaders headers) {
        RetryAfterDirective retryDirective = retryAfter(headers);
        if (body != null && body.isObject()) {
            return new ResourceGatewayTestException(status,
                    bounded(body.path("code").asText("RG.TESTKIT.HTTP_ERROR"), 160),
                    bounded(body.path("title").asText("The Resource Gateway rejected the test request."), 512),
                    body.path("retryable").asBoolean(false),
                    bounded(body.path("correlationId").asText(), 128),
                    retryDirective.specified(), retryDirective.delay(), null);
        }
        return new ResourceGatewayTestException(status, "RG.TESTKIT.HTTP_ERROR",
                "The Resource Gateway rejected the test request.", status >= 500, "",
                retryDirective.specified(), retryDirective.delay(), null);
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

    private static TestSuiteStabilityRun projectStabilityRun(JsonNode response) {
        try {
            return TestSuiteStabilityRun.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid suite-stability projection.");
        }
    }

    private static TestSuiteStabilityTrendAnalysis projectStabilityTrend(JsonNode response) {
        try {
            return TestSuiteStabilityTrendAnalysis.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid suite-stability trend projection.");
        }
    }

    private static TestSuiteStabilityCrossRetentionTrendAnalysis
            projectStabilityCrossRetentionTrend(JsonNode response) {
        try {
            return TestSuiteStabilityCrossRetentionTrendAnalysis.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid cross-retention stability trend projection.");
        }
    }

    private static TestSuiteStabilityObservationLedgerLifecyclePage
            projectStabilityObservationLifecyclePage(JsonNode response) {
        try {
            return TestSuiteStabilityObservationLedgerLifecyclePage.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid stability observation lifecycle page.");
        }
    }

    private List<TestSuiteStabilityRun> fetchTrendSources(
            TestSuiteStabilityTrendAnalysis analysis) {
        List<TestSuiteStabilityRun> sources = new ArrayList<>();
        analysis.attestation().sourceEvidenceRefs().forEach(source ->
                sources.add(findSuiteStability(source.stabilityRunId())));
        return List.copyOf(sources);
    }

    private static TestSuiteStabilityProgress projectStabilityProgress(JsonNode response) {
        try {
            return TestSuiteStabilityProgress.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid suite-stability progress projection.");
        }
    }

    private static TestSuiteStabilityJob projectStabilityJob(JsonNode response) {
        try {
            return TestSuiteStabilityJob.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid suite-stability job projection.");
        }
    }

    private static TestSuiteStabilityJobSubmission projectStabilityJobSubmission(
            JsonNode response) {
        try {
            return TestSuiteStabilityJobSubmission.from(response);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned an invalid suite-stability job admission response.");
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

    private static void requireStabilityJobIdentity(
            TestSuiteStabilityJob job,
            String jobId) {
        try {
            job.requireJobIdentity(jobId);
        } catch (IllegalArgumentException failure) {
            throw responseContractInvalid(
                    "The server returned a mismatched suite-stability job identity.");
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

    private static void requireStabilityResponseVersion(JsonNode response) {
        String actual = response.path("schemaVersion").asText();
        if (!TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V1.equals(actual)
                && !TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V2.equals(actual)
                && !TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V3.equals(actual)
                && !TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V4.equals(actual)
                && !TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V5.equals(actual)) {
            throw ResourceGatewayTestException.local(
                    "RG.TESTKIT.PROTOCOL_VERSION_MISMATCH",
                    "The server returned an unsupported suite-stability response version.", null);
        }
    }

    private static void requireSuiteExecutionResponseVersion(JsonNode response) {
        String actual = response.path("schemaVersion").asText();
        if (!TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V1.equals(actual)
                && !TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V2.equals(actual)
                && !TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V3.equals(actual)
                && !TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V4.equals(actual)
                && !TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V5.equals(actual)
                && !TestingProtocol.TEST_SUITE_EXECUTION_RESPONSE_V6.equals(actual)) {
            throw ResourceGatewayTestException.local("RG.TESTKIT.PROTOCOL_VERSION_MISMATCH",
                    "The server returned an unsupported suite execution response version.", null);
        }
    }

    private static void requireSuiteEvidenceBundleVersion(JsonNode response) {
        String actual = response.path("schemaVersion").asText();
        if (!TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V1.equals(actual)
                && !TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V2.equals(actual)
                && !TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V3.equals(actual)
                && !TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V4.equals(actual)
                && !TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V5.equals(actual)) {
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

    private static String requiredProtocolIdentifier(String value, String field) {
        String exact = requiredIdentifier(value, field, 255);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")) {
            throw new IllegalArgumentException(field + " is outside protocol bounds");
        }
        return exact;
    }

    private static String requiredStabilityJobId(String value) {
        String exact = normalized(value);
        if (!exact.matches("stability-job-[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "jobId must be a deterministic suite-stability job identity");
        }
        return exact;
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

    private static boolean retryableStabilityOperation(
            ResourceGatewayTestException failure) {
        return failure.retryable() && (failure.status() == 429 || failure.status() == 503);
    }

    private static void requireUsableRetryDirective(ResourceGatewayTestException failure) {
        if (failure.retryAfterSpecified() && failure.retryAfter().isEmpty()) {
            throw failure;
        }
    }

    private static boolean fitsElapsedBound(
            long startedAt,
            Duration delay,
            Duration maximumElapsed) {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedAt);
        return Duration.ofNanos(elapsedNanos).plus(delay).compareTo(maximumElapsed) <= 0;
    }

    private static Duration doubled(Duration value, Duration maximum) {
        Duration candidate = value.multipliedBy(2);
        return candidate.compareTo(maximum) > 0 ? maximum : candidate;
    }

    private static void pause(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ResourceGatewayTestException.local("RG.TESTKIT.REQUEST_INTERRUPTED",
                    "Suite-stability job waiting was interrupted.", failure);
        }
    }

    private static ResourceGatewayTestException pollExhausted() {
        return ResourceGatewayTestException.local(
                "RG.TESTKIT.STABILITY_JOB_POLL_EXHAUSTED",
                "Suite-stability job polling exhausted its configured bounds.", null);
    }

    private static RetryAfterDirective retryAfter(HttpHeaders headers) {
        String value = headers == null ? ""
                : headers.firstValue("Retry-After").orElse("").trim();
        if (value.isEmpty()) {
            return new RetryAfterDirective(false, null);
        }
        if (value.matches("[0-9]{1,6}")) {
            try {
                Duration parsed = Duration.ofSeconds(Long.parseLong(value));
                return new RetryAfterDirective(true,
                        parsed.compareTo(MAX_RETRY_AFTER) <= 0 ? parsed : null);
            } catch (NumberFormatException ignored) {
                return new RetryAfterDirective(true, null);
            }
        }
        try {
            Instant retryAt = ZonedDateTime.parse(
                    value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration parsed = Duration.between(Instant.now(), retryAt);
            if (parsed.isNegative()) {
                parsed = Duration.ZERO;
            }
            return new RetryAfterDirective(true,
                    parsed.compareTo(MAX_RETRY_AFTER) <= 0 ? parsed : null);
        } catch (DateTimeParseException ignored) {
            return new RetryAfterDirective(true, null);
        }
    }

    private record ExchangeResponse(
            int status,
            HttpHeaders headers,
            JsonNode body) {
    }

    private record RetryAfterDirective(
            boolean specified,
            Duration delay) {
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
