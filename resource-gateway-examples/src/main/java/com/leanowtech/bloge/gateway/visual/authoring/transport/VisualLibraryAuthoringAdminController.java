package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringFixtureCapability;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.compile.OperatorArchetypeRegistry;
import com.leanowtech.bloge.gateway.visual.authoring.inference.SampleSchemaInferencer;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.authoring.parse.AuthoringDocumentDecoder;
import com.leanowtech.bloge.gateway.visual.authoring.parse.CompactTypeParser;
import com.leanowtech.bloge.gateway.visual.authoring.parse.FunctionSignatureParser;
import com.leanowtech.bloge.gateway.visual.authoring.parse.SampleInferenceRequestDecoder;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionTestWorker;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stateless Stage 0 authoring API. Draft persistence and commit fencing are separate lifecycle endpoints.
 */
@RestController
@RequestMapping("/admin/visual-operator-library-authoring")
public final class VisualLibraryAuthoringAdminController {

    private final AuthoringPreviewService previewService;
    private final AuthoringDocumentDecoder decoder;
    private final FunctionSignatureParser signatureParser;
    private final OperatorArchetypeRegistry archetypes;
    private final ObjectMapper objectMapper;
    private final boolean governedFixturePersistence;
    private final boolean isolatedFunctionTestWorker;

    public VisualLibraryAuthoringAdminController(AuthoringPreviewService previewService,
                                                 ObjectMapper objectMapper) {
        this(previewService, objectMapper, new AuthoringDocumentDecoder(),
                new FunctionSignatureParser(), new OperatorArchetypeRegistry(), false, false);
    }

    @Autowired
    public VisualLibraryAuthoringAdminController(
            AuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            ObjectProvider<AuthoringFixtureCapability> fixtures,
            ObjectProvider<AuthoringFunctionTestWorker> functionWorkers) {
        this(previewService, objectMapper, new AuthoringDocumentDecoder(),
                new FunctionSignatureParser(), new OperatorArchetypeRegistry(),
                fixtures.getIfAvailable() != null,
                functionWorkers.getIfAvailable() != null);
    }

    VisualLibraryAuthoringAdminController(AuthoringPreviewService previewService,
                                          ObjectMapper objectMapper,
                                          AuthoringDocumentDecoder decoder,
                                          FunctionSignatureParser signatureParser,
                                          OperatorArchetypeRegistry archetypes) {
        this(previewService, objectMapper, decoder, signatureParser, archetypes, false, false);
    }

    VisualLibraryAuthoringAdminController(AuthoringPreviewService previewService,
                                          ObjectMapper objectMapper,
                                          AuthoringDocumentDecoder decoder,
                                          FunctionSignatureParser signatureParser,
                                          OperatorArchetypeRegistry archetypes,
                                          boolean governedFixturePersistence,
                                          boolean isolatedFunctionTestWorker) {
        this.previewService = java.util.Objects.requireNonNull(previewService, "previewService");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.decoder = decoder == null ? new AuthoringDocumentDecoder() : decoder;
        this.signatureParser = signatureParser == null
                ? new FunctionSignatureParser() : signatureParser;
        this.archetypes = archetypes == null ? new OperatorArchetypeRegistry() : archetypes;
        this.governedFixturePersistence = governedFixturePersistence;
        this.isolatedFunctionTestWorker = isolatedFunctionTestWorker;
    }

    @PostMapping(
            value = "/preview",
            consumes = {
                    MediaType.APPLICATION_JSON_VALUE,
                    MediaType.TEXT_PLAIN_VALUE,
                    "application/yaml",
                    "application/x-yaml",
                    "text/yaml"
            },
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> preview(@RequestBody(required = false) byte[] source) {
        AuthoringDocumentDecoder.DecodeResult decoded = decoder.decode(source);
        if (!decoded.successful()) {
            AuthoringDocumentDecoder.DecodeFailure failure = decoded.failure();
            AuthoringDiagnostic diagnostic = AuthoringDiagnostic.compiler(
                    "ERROR",
                    failure.code(),
                    failure.message(),
                    "/",
                    -1,
                    Map.of(
                            "line", failure.line(),
                            "column", failure.column()
                    )
            );
            return ResponseEntity.status(failure.status()).body(AuthoringProblem.of(
                    failure.code(),
                    failure.message(),
                    failure.status(),
                    List.of(diagnostic)
            ));
        }
        return ResponseEntity.ok(previewService.preview(decoded.document()));
    }

    @PostMapping(
            value = "/signature/parse",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public FunctionSignatureParser.ParseResult parseSignature(
            @RequestBody(required = false) SignatureParseRequest request) {
        return signatureParser.parse(request == null ? "" : request.signature());
    }

    @GetMapping(value = "/catalogs", produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthoringCatalogs catalogs() {
        return new AuthoringCatalogs(
                "bloge.visualLibraryAuthoringCatalogs.v1",
                VisualLibraryAuthoringDocument.SCHEMA_VERSION,
                AuthoringCompileResult.SCHEMA_VERSION,
                AuthoringCompiler.COMPILER_VERSION,
                CompactTypeParser.GRAMMAR_VERSION,
                FunctionSignatureParser.GRAMMAR_VERSION,
                OperatorArchetypeRegistry.VERSION,
                archetypes.fingerprint(objectMapper),
                previewService.catalogFingerprint(),
                CompactTypeParser.primitives(),
                archetypes.all(),
                Map.ofEntries(
                        Map.entry("maximumAuthoringBytes", AuthoringCompiler.MAX_AUTHORING_BYTES),
                        Map.entry("maximumTypes", AuthoringCompiler.MAX_TYPES),
                        Map.entry("maximumOperators", AuthoringCompiler.MAX_OPERATORS),
                        Map.entry("maximumFunctions", AuthoringCompiler.MAX_FUNCTIONS),
                        Map.entry("maximumSignaturesPerFunction",
                                AuthoringCompiler.MAX_SIGNATURES_PER_FUNCTION),
                        Map.entry("maximumFieldsPerObject",
                                AuthoringCompiler.MAX_FIELDS_PER_OBJECT),
                        Map.entry("maximumTypeDepth", AuthoringCompiler.MAX_TYPE_DEPTH),
                        Map.entry("maximumYamlAliases",
                                AuthoringDocumentDecoder.MAXIMUM_ALIASES),
                        Map.entry("maximumSampleInferenceBytes",
                                SampleSchemaInferencer.MAXIMUM_REQUEST_BYTES),
                        Map.entry("maximumDiscoverySourceBytes",
                                com.leanowtech.bloge.gateway.visual.authoring.discovery
                                        .AuthoringFactProjectionService.MAXIMUM_SOURCE_BYTES),
                        Map.entry("maximumSampleInferenceApplyBytes",
                                SampleInferenceRequestDecoder.MAXIMUM_APPLY_REQUEST_BYTES),
                        Map.entry("maximumInferenceSamples",
                                SampleSchemaInferencer.MAXIMUM_SAMPLES),
                        Map.entry("maximumInferenceNodes",
                                SampleSchemaInferencer.MAXIMUM_TOTAL_NODES),
                        Map.entry("maximumInferenceDepth", SampleSchemaInferencer.MAXIMUM_DEPTH),
                        Map.entry("maximumAuthoringTestSuiteBytes",
                                AuthoringTestService.MAXIMUM_SUITE_BYTES),
                        Map.entry("maximumAuthoringTestResultBytes",
                                AuthoringTestService.MAXIMUM_RESULT_BYTES),
                        Map.entry("maximumAuthoringTestCases",
                                AuthoringTestService.MAXIMUM_CASES),
                        Map.entry("maximumFunctionTestArguments",
                                AuthoringTestService.MAXIMUM_ARGUMENTS),
                        Map.entry("functionTestTimeoutMillis",
                                AuthoringTestService.FUNCTION_TIMEOUT_MILLIS),
                        Map.entry("functionTestSupervisorTimeoutMillis",
                                AuthoringFunctionWorkerProtocol.SUPERVISOR_TIMEOUT_MILLIS),
                        Map.entry("functionTestSuiteTimeoutMillis",
                                AuthoringFunctionWorkerProtocol.SUITE_TIMEOUT_MILLIS),
                        Map.entry("functionTestWorkerHeapMib",
                                AuthoringFunctionWorkerProtocol.WORKER_HEAP_MIB),
                        Map.entry("maximumConcurrentFunctionTestWorkers",
                                AuthoringFunctionWorkerProtocol.MAXIMUM_CONCURRENT_WORKERS),
                        Map.entry("maximumAuthoringFixtureRequestBytes",
                                AuthoringFixtureCapability.MAXIMUM_REQUEST_BYTES),
                        Map.entry("maximumAuthoringFixturePayloadBytes",
                                AuthoringFixtureCapability.MAXIMUM_PAYLOAD_BYTES),
                        Map.entry("maximumAuthoringFixturePayloadDepth",
                                AuthoringFixtureCapability.MAXIMUM_PAYLOAD_DEPTH),
                        Map.entry("maximumAuthoringFixturePayloadNodes",
                                AuthoringFixtureCapability.MAXIMUM_PAYLOAD_NODES),
                        Map.entry("maximumAuthoringFixtureRedactionPaths",
                                AuthoringFixtureCapability.MAXIMUM_REDACTION_PATHS),
                        Map.entry("maximumAuthoringFixtureRetentionDays",
                                AuthoringFixtureCapability.MAXIMUM_RETENTION_DAYS)
                ),
                Map.ofEntries(
                        Map.entry("functionOnlyLibrary", true),
                        Map.entry("statelessPreview", true),
                        Map.entry("crossLibraryTypeImports", false),
                        Map.entry("sampleInference", true),
                        Map.entry("sampleInferenceApply", true),
                        Map.entry("unifiedDiscoveryFacts", true),
                        Map.entry("runtimeParity", true),
                        Map.entry("frameworkFunctionInventory", true),
                        Map.entry("draftLifecycle", true),
                        Map.entry("etagConcurrency", true),
                        Map.entry("previewFencedCommit", true),
                        Map.entry("operatorTestDraftRunner", true),
                        Map.entry("functionTestDraftRunner", true),
                        Map.entry("governedFixturePersistence",
                                governedFixturePersistence),
                        Map.entry("isolatedFunctionTestWorker",
                                isolatedFunctionTestWorker),
                        Map.entry("signedTestEvidence", true),
                        Map.entry("testEvidenceGate", true)
                )
        );
    }

    public record SignatureParseRequest(String signature) {
    }

    public record AuthoringCatalogs(
            String schemaVersion,
            String authoringSchemaVersion,
            String compileResultSchemaVersion,
            String compilerVersion,
            String compactTypeGrammarVersion,
            String functionSignatureGrammarVersion,
            String archetypeCatalogVersion,
            String archetypeCatalogFingerprint,
            String effectiveCatalogFingerprint,
            List<String> primitiveTypes,
            Collection<OperatorArchetypeRegistry.Archetype> archetypes,
            Map<String, Integer> limits,
            Map<String, Boolean> features
    ) {
        public AuthoringCatalogs {
            primitiveTypes = primitiveTypes == null ? List.of() : List.copyOf(primitiveTypes);
            archetypes = archetypes == null ? List.of() : List.copyOf(archetypes);
            limits = limits == null ? Map.of() : Map.copyOf(limits);
            features = features == null ? Map.of() : Map.copyOf(features);
        }
    }
}
