package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionAssertion;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionBindingStatus;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCase;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCaseResult;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCaseStatus;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionDraft;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunEvidence;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionSuite;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorDraft;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunEvidence;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationOutcome;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationResponse;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionContract;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestDraftRequest;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestDraftResponse;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestService;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRequest;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs bounded, ephemeral tests against one exact progressive-library draft revision.
 */
@Service
public final class AuthoringTestService {

    public static final int MAXIMUM_SUITE_BYTES = 256 * 1024;
    public static final int MAXIMUM_RESULT_BYTES = 512 * 1024;
    public static final int MAXIMUM_CASES = 50;
    public static final int MAXIMUM_ARGUMENTS = 32;
    public static final int FUNCTION_TIMEOUT_MILLIS =
            AuthoringFunctionWorkerProtocol.INVOCATION_TIMEOUT_MILLIS;
    public static final String RUNTIME_PROFILE =
            AuthoringFunctionWorkerProtocol.EXECUTION_PROFILE;

    private final AuthoringDraftService drafts;
    private final VisualOperatorContractTestService operatorTests;
    private final ObjectMapper objectMapper;
    private final AuthoringFunctionTestWorker functionWorker;

    @Autowired
    public AuthoringTestService(AuthoringDraftService drafts,
                                VisualOperatorContractTestService operatorTests,
                                ObjectMapper objectMapper,
                                AuthoringFunctionTestWorker functionWorker) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.operatorTests = Objects.requireNonNull(operatorTests, "operatorTests");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.functionWorker = Objects.requireNonNull(functionWorker, "functionWorker");
    }

    void close() {
        // One-shot workers have no persistent execution state to close.
    }

    public OperatorDraft draftOperator(String draftId,
                                       long expectedRevision,
                                       OperatorDraftRequest request) {
        requireVersion(
                request == null ? "" : request.schemaVersion(),
                OperatorDraftRequest.SCHEMA_VERSION,
                draftId,
                expectedRevision,
                "/schemaVersion");
        VisualOperatorContractTestDraftRequest draftRequest = request == null ? null : request.draft();
        if (draftRequest == null) {
            throw failure(
                    400,
                    "RG.AUTHORING.OPERATOR_TEST_DRAFT_REQUIRED",
                    "An operator test draft request is required.",
                    draftId,
                    expectedRevision,
                    "/draft");
        }
        AuthoringCompileResult preview = exactPreview(draftId, expectedRevision);
        OperatorDefinition operator = requireOperator(
                preview, draftRequest.operatorRef(), draftId, expectedRevision);
        VisualOperatorContractTestDraftResponse generated = operatorTests.draft(operator, draftRequest);
        String artifactFingerprint = operator.fingerprint();
        String suiteFingerprint = suiteFingerprint(
                generated.suite(), draftId, expectedRevision);
        return new OperatorDraft(
                OperatorDraft.SCHEMA_VERSION,
                draftId,
                expectedRevision,
                preview.authoringFingerprint(),
                preview.canonicalFingerprint(),
                artifactFingerprint,
                suiteFingerprint,
                generated.suite(),
                generated.diagnostics(),
                false);
    }

    public OperatorRunEvidence runOperator(String draftId,
                                           long expectedRevision,
                                           OperatorRunRequest request) {
        requireVersion(
                request == null ? "" : request.schemaVersion(),
                OperatorRunRequest.SCHEMA_VERSION,
                draftId,
                expectedRevision,
                "/schemaVersion");
        VisualOperatorContractTestSuiteRequest suite = request == null ? null : request.suite();
        requireBoundedSuite(suite, draftId, expectedRevision);
        AuthoringCompileResult preview = exactPreview(draftId, expectedRevision);
        OperatorDefinition operator = requireOperator(
                preview, suite.operatorRef(), draftId, expectedRevision);
        String suiteFingerprint = suiteFingerprint(suite, draftId, expectedRevision);
        VisualOperatorContractTestSuiteResult result = operatorTests.run(operator, suite);
        String runId = UUID.randomUUID().toString();
        Instant executedAt = Instant.now();
        String evidenceFingerprint = evidenceFingerprint(Map.of(
                "protocol", OperatorRunEvidence.SCHEMA_VERSION,
                "draftId", draftId,
                "authoringRevision", expectedRevision,
                "authoringFingerprint", preview.authoringFingerprint(),
                "canonicalFingerprint", preview.canonicalFingerprint(),
                "artifactFingerprint", operator.fingerprint(),
                "suiteFingerprint", suiteFingerprint,
                "result", result
        ), draftId, expectedRevision);
        return new OperatorRunEvidence(
                OperatorRunEvidence.SCHEMA_VERSION,
                runId,
                draftId,
                expectedRevision,
                preview.authoringFingerprint(),
                preview.canonicalFingerprint(),
                operator.fingerprint(),
                suiteFingerprint,
                evidenceFingerprint,
                executedAt,
                result,
                List.of(),
                false);
    }

    public FunctionDraft draftFunction(String draftId,
                                       long expectedRevision,
                                       FunctionDraftRequest request) {
        requireVersion(
                request == null ? "" : request.schemaVersion(),
                FunctionDraftRequest.SCHEMA_VERSION,
                draftId,
                expectedRevision,
                "/schemaVersion");
        String functionRef = request == null ? "" : request.functionRef();
        AuthoringCompileResult preview = exactPreview(draftId, expectedRevision);
        OperatorLibrary.BuiltInFunction function = requireFunction(
                preview, functionRef, draftId, expectedRevision);
        RuntimeResolution runtime = resolveRuntime(function);
        List<VisualDiagnostic> diagnostics = new ArrayList<>(runtime.diagnostics());
        FunctionSuite suite = generatedFunctionSuite(function, diagnostics);
        return new FunctionDraft(
                FunctionDraft.SCHEMA_VERSION,
                draftId,
                expectedRevision,
                preview.authoringFingerprint(),
                preview.canonicalFingerprint(),
                BuiltInFunctionContract.callableFingerprint(function),
                runtime.runtimeFingerprint(),
                functionWorker.executionProfile(),
                runtime.status(),
                suiteFingerprint(suite, draftId, expectedRevision),
                suite,
                diagnostics,
                false);
    }

    public FunctionRunEvidence runFunction(String draftId,
                                           long expectedRevision,
                                           FunctionRunRequest request) {
        requireVersion(
                request == null ? "" : request.schemaVersion(),
                FunctionRunRequest.SCHEMA_VERSION,
                draftId,
                expectedRevision,
                "/schemaVersion");
        FunctionSuite suite = request == null ? null : request.suite();
        requireBoundedSuite(suite, draftId, expectedRevision);
        AuthoringCompileResult preview = exactPreview(draftId, expectedRevision);
        OperatorLibrary.BuiltInFunction function = requireFunction(
                preview, suite.functionRef(), draftId, expectedRevision);
        RuntimeResolution runtime = resolveRuntime(function);
        String suiteFingerprint = suiteFingerprint(suite, draftId, expectedRevision);
        List<FunctionCaseResult> results = runtime.status() == FunctionBindingStatus.BOUND
                ? runFunctionCases(function, runtime.runtimeFingerprint(), suite.cases())
                : notRunFunctionCases(suite.cases(), runtime);
        int passedCases = (int) results.stream().filter(FunctionCaseResult::passed).count();
        int failedCases = results.size() - passedCases;
        boolean passed = runtime.status() == FunctionBindingStatus.BOUND
                && !results.isEmpty()
                && failedCases == 0;
        String evidenceFingerprint = evidenceFingerprint(Map.of(
                "protocol", FunctionRunEvidence.SCHEMA_VERSION,
                "draftId", draftId,
                "authoringRevision", expectedRevision,
                "authoringFingerprint", preview.authoringFingerprint(),
                "canonicalFingerprint", preview.canonicalFingerprint(),
                "functionFingerprint", BuiltInFunctionContract.callableFingerprint(function),
                "runtimeFingerprint", runtime.runtimeFingerprint(),
                "executionProfile", functionWorker.executionProfile(),
                "suiteFingerprint", suiteFingerprint,
                "results", results
        ), draftId, expectedRevision);
        return new FunctionRunEvidence(
                FunctionRunEvidence.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                draftId,
                expectedRevision,
                preview.authoringFingerprint(),
                preview.canonicalFingerprint(),
                BuiltInFunctionContract.callableFingerprint(function),
                runtime.runtimeFingerprint(),
                functionWorker.executionProfile(),
                runtime.status(),
                suiteFingerprint,
                evidenceFingerprint,
                Instant.now(),
                passed,
                results.size(),
                passedCases,
                failedCases,
                results,
                runtime.diagnostics(),
                false);
    }

    private List<FunctionCaseResult> runFunctionCases(OperatorLibrary.BuiltInFunction function,
                                                      String runtimeFingerprint,
                                                      List<FunctionCase> cases) {
        List<FunctionCaseResult> results = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                AuthoringFunctionWorkerProtocol.SUITE_TIMEOUT_MILLIS);
        for (int index = 0; index < cases.size(); index++) {
            if (!hasWorkerBudget(System.nanoTime(), deadline)) {
                results.add(workerFailureResult(
                        cases.get(index),
                        new InvocationResponse(
                                InvocationResponse.SCHEMA_VERSION,
                                "suite-deadline",
                                functionWorker.executionProfile(),
                                runtimeFingerprint,
                                InvocationOutcome.WORKER_UNAVAILABLE,
                                null,
                                "SUITE_DEADLINE_EXCEEDED",
                                0),
                        "/suite/cases/" + index));
                continue;
            }
            results.add(runFunctionCase(
                    function, runtimeFingerprint, cases.get(index), index));
        }
        return results;
    }

    static boolean hasWorkerBudget(long nowNanos, long deadlineNanos) {
        long requiredNanos = TimeUnit.MILLISECONDS.toNanos(
                AuthoringFunctionWorkerProtocol.SUPERVISOR_TIMEOUT_MILLIS);
        return deadlineNanos - nowNanos >= requiredNanos;
    }

    private FunctionCaseResult runFunctionCase(OperatorLibrary.BuiltInFunction function,
                                               String runtimeFingerprint,
                                               FunctionCase testCase,
                                               int index) {
        String target = "/suite/cases/" + index;
        List<VisualDiagnostic> diagnostics = validateFunctionCase(function, testCase, target);
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return functionCaseResult(
                    testCase,
                    false,
                    FunctionCaseStatus.CONTRACT_REJECTED,
                    null,
                    "",
                    0,
                    diagnostics);
        }

        InvocationResponse invocation = functionWorker.invoke(
                function.name(), runtimeFingerprint, testCase.args());
        if (invocation.outcome() == InvocationOutcome.INVOCATION_FAILED) {
            String errorCode = invocation.errorCode();
            boolean expected = testCase.assertion() == FunctionAssertion.EXPECT_ERROR
                    && testCase.expectError() != null
                    && testCase.expectError().code().equals(errorCode);
            return functionCaseResult(
                    testCase,
                    expected,
                    expected ? FunctionCaseStatus.PASSED : FunctionCaseStatus.INVOCATION_FAILED,
                    null,
                    errorCode,
                    invocation.durationMicros(),
                    expected ? List.of() : List.of(VisualDiagnostic.error(
                            "visual.authoring.functionTest.unexpectedError",
                            "Function execution returned error code '%s'.".formatted(errorCode),
                            target + "/expectError")));
        }
        if (invocation.outcome() != InvocationOutcome.SUCCESS) {
            return workerFailureResult(testCase, invocation, target);
        }

        Object actual = invocation.actual();
        long durationMicros = invocation.durationMicros();
        if (testCase.assertion() == FunctionAssertion.EXPECT_ERROR) {
            return functionCaseResult(
                    testCase,
                    false,
                    FunctionCaseStatus.ASSERTION_FAILED,
                    boundedActual(actual, target, diagnostics),
                    "",
                    durationMicros,
                    List.of(VisualDiagnostic.error(
                            "visual.authoring.functionTest.expectedErrorMissing",
                            "Function execution completed but the case expected an error.",
                            target + "/expectError")));
        }
        Object boundedActual = boundedActual(actual, target, diagnostics);
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return functionCaseResult(
                    testCase,
                    false,
                    FunctionCaseStatus.INVOCATION_FAILED,
                    null,
                    "RESULT_TOO_LARGE",
                    durationMicros,
                    diagnostics);
        }

        boolean assertionPassed;
        if (testCase.assertion() == FunctionAssertion.RETURN_TYPE) {
            assertionPassed = matchingReturnType(function, testCase.args(), boundedActual);
        } else {
            JsonNode expected = objectMapper.valueToTree(testCase.expect());
            JsonNode actualNode = objectMapper.valueToTree(boundedActual);
            assertionPassed = expected.equals(actualNode);
        }
        return functionCaseResult(
                testCase,
                assertionPassed,
                assertionPassed ? FunctionCaseStatus.PASSED : FunctionCaseStatus.ASSERTION_FAILED,
                boundedActual,
                "",
                durationMicros,
                assertionPassed ? List.of() : List.of(VisualDiagnostic.error(
                        "visual.authoring.functionTest.assertionFailed",
                        testCase.assertion() == FunctionAssertion.RETURN_TYPE
                                ? "Function result does not match the declared return type."
                                : "Function result does not equal the expected value.",
                        target + "/expect")));
    }

    private FunctionCaseResult workerFailureResult(FunctionCase testCase,
                                                   InvocationResponse invocation,
                                                   String target) {
        FunctionCaseStatus status;
        String diagnosticCode;
        String message;
        switch (invocation.outcome()) {
            case TIMEOUT -> {
                status = FunctionCaseStatus.TIMEOUT;
                diagnosticCode = "visual.authoring.functionTest.timeout";
                message = "The isolated worker exceeded the function execution time limit.";
            }
            case RESOURCE_EXHAUSTED -> {
                status = FunctionCaseStatus.RESOURCE_EXHAUSTED;
                diagnosticCode = "visual.authoring.functionTest.resourceExhausted";
                message = "The isolated worker exceeded its bounded memory or process resources.";
            }
            case WORKER_UNAVAILABLE -> {
                status = FunctionCaseStatus.WORKER_UNAVAILABLE;
                diagnosticCode = "visual.authoring.functionTest.workerUnavailable";
                message = "The isolated worker is unavailable or at its concurrency limit.";
            }
            default -> {
                status = FunctionCaseStatus.WORKER_FAILED;
                diagnosticCode = "visual.authoring.functionTest.workerFailed";
                message = "The isolated worker failed protocol or launch attestation.";
            }
        }
        return functionCaseResult(
                testCase,
                false,
                status,
                null,
                invocation.errorCode(),
                invocation.durationMicros(),
                List.of(VisualDiagnostic.error(diagnosticCode, message, target)));
    }

    private List<VisualDiagnostic> validateFunctionCase(OperatorLibrary.BuiltInFunction function,
                                                        FunctionCase testCase,
                                                        String target) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (testCase == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.authoring.functionTest.caseRequired",
                    "A function test case is required.",
                    target));
            return diagnostics;
        }
        if (!FunctionCase.SCHEMA_VERSION.equals(testCase.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.authoring.functionTest.caseVersionUnsupported",
                    "Function test case schemaVersion is unsupported.",
                    target + "/schemaVersion"));
        }
        if (testCase.args().size() > MAXIMUM_ARGUMENTS) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.authoring.functionTest.argumentsTooMany",
                    "A function test case supports at most %d arguments.".formatted(MAXIMUM_ARGUMENTS),
                    target + "/args"));
        }
        if (matchingSignature(function, testCase.args()) == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.authoring.functionTest.signatureMismatch",
                    "Arguments do not match any declared function signature.",
                    target + "/args"));
        }
        if (testCase.assertion() == FunctionAssertion.EXPECT_ERROR
                && testCase.expectError() == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.authoring.functionTest.expectedErrorRequired",
                    "EXPECT_ERROR requires a stable expected error code.",
                    target + "/expectError"));
        }
        if (testCase.assertion() != FunctionAssertion.EXPECT_ERROR
                && testCase.expectError() != null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.authoring.functionTest.expectedErrorUnexpected",
                    "expectError is only valid with the EXPECT_ERROR assertion.",
                    target + "/expectError"));
        }
        return diagnostics;
    }

    private List<FunctionCaseResult> notRunFunctionCases(List<FunctionCase> cases,
                                                         RuntimeResolution runtime) {
        VisualDiagnostic diagnostic = runtime.diagnostics().isEmpty()
                ? VisualDiagnostic.error(
                        "visual.authoring.functionTest.runtimeUnavailable",
                        "The declared function is not runnable in this test profile.",
                        "/suite/functionRef")
                : runtime.diagnostics().getFirst();
        return cases.stream()
                .map(testCase -> functionCaseResult(
                        testCase,
                        false,
                        FunctionCaseStatus.NOT_RUN,
                        null,
                        "",
                        0,
                        List.of(diagnostic)))
                .toList();
    }

    private FunctionCaseResult functionCaseResult(FunctionCase testCase,
                                                  boolean passed,
                                                  FunctionCaseStatus status,
                                                  Object actual,
                                                  String errorCode,
                                                  long durationMicros,
                                                  List<VisualDiagnostic> diagnostics) {
        FunctionCase safeCase = testCase == null
                ? new FunctionCase("", "", null, List.of(), null, null, null)
                : testCase;
        return new FunctionCaseResult(
                safeCase.id(),
                safeCase.kind(),
                passed,
                status,
                actual,
                valueType(actual),
                errorCode,
                durationMicros,
                diagnostics);
    }

    private Object boundedActual(Object actual,
                                 String target,
                                 List<VisualDiagnostic> diagnostics) {
        try {
            VisualBundleFingerprint.fromCanonicalValue(objectMapper, actual, MAXIMUM_RESULT_BYTES);
            return actual;
        } catch (IllegalArgumentException exception) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.authoring.functionTest.resultTooLarge",
                    "Function result exceeds the bounded test response limit.",
                    target));
            return null;
        }
    }

    private FunctionSuite generatedFunctionSuite(OperatorLibrary.BuiltInFunction function,
                                                 List<VisualDiagnostic> diagnostics) {
        if (function.signatures().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.authoring.functionTest.signatureMissing",
                    "At least one parsed function signature is required to generate a test.",
                    "/suite/functionRef"));
            return new FunctionSuite(
                    FunctionSuite.SCHEMA_VERSION,
                    function.name(),
                    List.of());
        }
        OperatorLibrary.Signature signature = function.signatures().getFirst();
        List<Object> args = new ArrayList<>();
        for (OperatorLibrary.Parameter parameter : signature.parameters()) {
            if (!parameter.optional() && !parameter.variadic()) {
                args.add(sampleValue(parameter.type()));
            }
        }
        FunctionCase generated = new FunctionCase(
                FunctionCase.SCHEMA_VERSION,
                "returns-declared-type",
                AuthoringTestProtocol.FunctionCaseKind.BOUNDARY,
                args,
                FunctionAssertion.RETURN_TYPE,
                null,
                null);
        return new FunctionSuite(
                FunctionSuite.SCHEMA_VERSION,
                function.name(),
                List.of(generated));
    }

    private RuntimeResolution resolveRuntime(OperatorLibrary.BuiltInFunction function) {
        TrustedCoreFunctionRuntime.Resolution runtime =
                TrustedCoreFunctionRuntime.resolve(function.name());
        if (runtime.state() == TrustedCoreFunctionRuntime.State.UNBOUND) {
            return new RuntimeResolution(
                    FunctionBindingStatus.UNBOUND,
                    "",
                    List.of(VisualDiagnostic.warning(
                            "visual.authoring.functionTest.runtimeUnbound",
                            "No exact callable was found in the BLOGE runtime inventory.",
                            "/suite/functionRef")));
        }
        if (runtime.state() == TrustedCoreFunctionRuntime.State.BLOCKED_BY_POLICY) {
            return new RuntimeResolution(
                    FunctionBindingStatus.BLOCKED_BY_POLICY,
                    runtime.runtimeFingerprint(),
                    List.of(VisualDiagnostic.warning(
                            "visual.authoring.functionTest.runtimeBlocked",
                            "The runtime callable requires execution services that are not "
                                    + "available to the isolated worker.",
                            "/suite/functionRef")));
        }
        return new RuntimeResolution(
                FunctionBindingStatus.BOUND,
                runtime.runtimeFingerprint(),
                List.of());
    }

    private OperatorDefinition requireOperator(AuthoringCompileResult preview,
                                               String operatorRef,
                                               String draftId,
                                               long revision) {
        String ref = normalized(operatorRef);
        if (ref.isBlank()) {
            throw failure(
                    400,
                    "RG.AUTHORING.OPERATOR_TEST_REF_REQUIRED",
                    "operatorRef is required.",
                    draftId,
                    revision,
                    "/operatorRef");
        }
        return preview.canonicalLibrary().operators().stream()
                .filter(Objects::nonNull)
                .filter(operator -> operator.operatorRef().equals(ref))
                .findFirst()
                .orElseThrow(() -> failure(
                        404,
                        "RG.AUTHORING.OPERATOR_TEST_TARGET_NOT_FOUND",
                        "The operator is not present in this exact draft revision.",
                        draftId,
                        revision,
                        "/operatorRef"));
    }

    private OperatorLibrary.BuiltInFunction requireFunction(AuthoringCompileResult preview,
                                                            String functionRef,
                                                            String draftId,
                                                            long revision) {
        String ref = normalized(functionRef);
        if (ref.isBlank()) {
            throw failure(
                    400,
                    "RG.AUTHORING.FUNCTION_TEST_REF_REQUIRED",
                    "functionRef is required.",
                    draftId,
                    revision,
                    "/functionRef");
        }
        return preview.canonicalLibrary().builtInFunctions().stream()
                .filter(Objects::nonNull)
                .filter(function -> function.name().equals(ref))
                .findFirst()
                .orElseThrow(() -> failure(
                        404,
                        "RG.AUTHORING.FUNCTION_TEST_TARGET_NOT_FOUND",
                        "The function is not present in this exact draft revision.",
                        draftId,
                        revision,
                        "/functionRef"));
    }

    private AuthoringCompileResult exactPreview(String draftId, long revision) {
        AuthoringCompileResult preview = drafts.preview(draftId, revision);
        if (preview.canonicalLibrary() == null) {
            throw failure(
                    422,
                    "RG.AUTHORING.TEST_TARGET_NOT_COMPILED",
                    "The exact draft revision must compile before tests can run.",
                    draftId,
                    revision,
                    "/");
        }
        return preview;
    }

    private void requireBoundedSuite(VisualOperatorContractTestSuiteRequest suite,
                                     String draftId,
                                     long revision) {
        if (suite == null) {
            throw failure(
                    400,
                    "RG.AUTHORING.OPERATOR_TEST_SUITE_REQUIRED",
                    "An operator test suite is required.",
                    draftId,
                    revision,
                    "/suite");
        }
        if (suite.cases().isEmpty() || suite.cases().size() > MAXIMUM_CASES) {
            throw failure(
                    400,
                    "RG.AUTHORING.OPERATOR_TEST_CASE_COUNT_INVALID",
                    "An operator test suite requires 1-%d cases.".formatted(MAXIMUM_CASES),
                    draftId,
                    revision,
                    "/suite/cases");
        }
        suiteFingerprint(suite, draftId, revision);
    }

    private void requireBoundedSuite(FunctionSuite suite,
                                     String draftId,
                                     long revision) {
        if (suite == null) {
            throw failure(
                    400,
                    "RG.AUTHORING.FUNCTION_TEST_SUITE_REQUIRED",
                    "A function test suite is required.",
                    draftId,
                    revision,
                    "/suite");
        }
        if (!FunctionSuite.SCHEMA_VERSION.equals(suite.schemaVersion())) {
            throw failure(
                    400,
                    "RG.AUTHORING.FUNCTION_TEST_SUITE_VERSION_UNSUPPORTED",
                    "Function test suite schemaVersion is unsupported.",
                    draftId,
                    revision,
                    "/suite/schemaVersion");
        }
        if (suite.cases().isEmpty() || suite.cases().size() > MAXIMUM_CASES) {
            throw failure(
                    400,
                    "RG.AUTHORING.FUNCTION_TEST_CASE_COUNT_INVALID",
                    "A function test suite requires 1-%d cases.".formatted(MAXIMUM_CASES),
                    draftId,
                    revision,
                    "/suite/cases");
        }
        suiteFingerprint(suite, draftId, revision);
    }

    private void requireVersion(String actual,
                                String expected,
                                String draftId,
                                long revision,
                                String path) {
        if (!expected.equals(actual)) {
            throw failure(
                    400,
                    "RG.AUTHORING.TEST_PROTOCOL_VERSION_UNSUPPORTED",
                    "Test protocol schemaVersion is unsupported; expected '%s'.".formatted(expected),
                    draftId,
                    revision,
                    path);
        }
    }

    private String suiteFingerprint(Object value, String draftId, long revision) {
        try {
            return VisualBundleFingerprint.fromCanonicalValue(
                    objectMapper, value, MAXIMUM_SUITE_BYTES);
        } catch (IllegalArgumentException exception) {
            throw failure(
                    413,
                    "RG.AUTHORING.TEST_SUITE_TOO_LARGE",
                    "The test suite exceeds the bounded request limit.",
                    draftId,
                    revision,
                    "/suite");
        }
    }

    private String evidenceFingerprint(Object value, String draftId, long revision) {
        try {
            return VisualBundleFingerprint.fromCanonicalValue(
                    objectMapper, value, MAXIMUM_RESULT_BYTES);
        } catch (IllegalArgumentException exception) {
            throw failure(
                    413,
                    "RG.AUTHORING.TEST_RESULT_TOO_LARGE",
                    "The test result exceeds the bounded response limit.",
                    draftId,
                    revision,
                    "/result");
        }
    }

    private OperatorLibrary.Signature matchingSignature(OperatorLibrary.BuiltInFunction function,
                                                        List<Object> args) {
        for (OperatorLibrary.Signature signature : function.signatures()) {
            if (signatureMatches(signature, args)) {
                return signature;
            }
        }
        return null;
    }

    private boolean matchingReturnType(OperatorLibrary.BuiltInFunction function,
                                       List<Object> args,
                                       Object actual) {
        OperatorLibrary.Signature signature = matchingSignature(function, args);
        return signature != null && matchesType(signature.returns().type(), actual, false);
    }

    private boolean signatureMatches(OperatorLibrary.Signature signature, List<Object> args) {
        List<OperatorLibrary.Parameter> parameters = signature.parameters();
        int required = (int) parameters.stream()
                .filter(parameter -> !parameter.optional() && !parameter.variadic())
                .count();
        boolean variadic = !parameters.isEmpty() && parameters.getLast().variadic();
        if (args.size() < required || (!variadic && args.size() > parameters.size())) {
            return false;
        }
        for (int index = 0; index < args.size(); index++) {
            OperatorLibrary.Parameter parameter = index < parameters.size()
                    ? parameters.get(index)
                    : parameters.getLast();
            if (!matchesType(parameter.type(), args.get(index), parameter.variadic())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesType(String declaredType, Object value, boolean variadicElement) {
        String type = normalized(declaredType).toLowerCase(Locale.ROOT);
        while (type.endsWith("?")) {
            type = type.substring(0, type.length() - 1);
        }
        if (variadicElement && type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2);
        }
        if (value == null) {
            return true;
        }
        if (type.isBlank() || Set.of("any", "unknown", "json").contains(type)) {
            return true;
        }
        if (type.endsWith("[]")) {
            if (!(value instanceof List<?> values)) {
                return false;
            }
            String itemType = type.substring(0, type.length() - 2);
            return values.stream().allMatch(item -> matchesType(itemType, item, false));
        }
        return switch (type) {
            case "string", "date", "datetime", "date-time" -> value instanceof String;
            case "number", "double", "float" -> value instanceof Number;
            case "integer", "int", "long" -> integral(value);
            case "boolean", "bool" -> value instanceof Boolean;
            case "object", "map" -> value instanceof Map<?, ?>;
            case "array", "list" -> value instanceof List<?>;
            case "null" -> false;
            default -> true;
        };
    }

    private static boolean integral(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        double asDouble = number.doubleValue();
        return Double.isFinite(asDouble) && Math.rint(asDouble) == asDouble;
    }

    private static Object sampleValue(String declaredType) {
        String type = normalized(declaredType).toLowerCase(Locale.ROOT);
        while (type.endsWith("?")) {
            type = type.substring(0, type.length() - 1);
        }
        if (type.endsWith("[]") || Set.of("array", "list").contains(type)) {
            return List.of();
        }
        return switch (type) {
            case "string" -> "sample";
            case "date" -> "2026-01-01";
            case "datetime", "date-time" -> "2026-01-01T00:00:00Z";
            case "number", "double", "float" -> 1.5;
            case "integer", "int", "long" -> 1;
            case "boolean", "bool" -> true;
            case "object", "map" -> Map.of();
            default -> null;
        };
    }

    private static String valueType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number number) {
            return integral(number) ? "integer" : "number";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        return value.getClass().getSimpleName();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static AuthoringLifecycleException failure(int status,
                                                       String code,
                                                       String message,
                                                       String draftId,
                                                       long revision,
                                                       String path) {
        AuthoringDiagnostic diagnostic = AuthoringDiagnostic.compiler(
                "ERROR",
                code,
                message,
                path,
                -1,
                Map.of()
        );
        return new AuthoringLifecycleException(AuthoringProblem.of(
                code,
                message,
                status,
                normalized(draftId),
                Math.max(0, revision),
                List.of(diagnostic)
        ));
    }

    private record RuntimeResolution(
            FunctionBindingStatus status,
            String runtimeFingerprint,
            List<VisualDiagnostic> diagnostics
    ) {
        private RuntimeResolution {
            status = status == null ? FunctionBindingStatus.UNBOUND : status;
            runtimeFingerprint = normalized(runtimeFingerprint);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }
}
