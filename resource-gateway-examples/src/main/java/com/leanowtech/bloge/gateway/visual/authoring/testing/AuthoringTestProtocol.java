package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestDraftRequest;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRequest;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Versioned draft-scoped operator and function test protocol.
 */
public final class AuthoringTestProtocol {

    private AuthoringTestProtocol() {
    }

    public record OperatorDraftRequest(
            String schemaVersion,
            VisualOperatorContractTestDraftRequest draft
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringOperatorTestDraftRequest.v1";

        public OperatorDraftRequest {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        }
    }

    public record OperatorDraft(
            String schemaVersion,
            String draftId,
            long authoringRevision,
            String authoringFingerprint,
            String canonicalFingerprint,
            String artifactFingerprint,
            String suiteFingerprint,
            VisualOperatorContractTestSuiteRequest suite,
            List<VisualDiagnostic> diagnostics,
            boolean payloadPersisted
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringOperatorTestDraft.v1";

        public OperatorDraft {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            draftId = normalized(draftId, "");
            authoringRevision = Math.max(0, authoringRevision);
            authoringFingerprint = normalized(authoringFingerprint, "");
            canonicalFingerprint = normalized(canonicalFingerprint, "");
            artifactFingerprint = normalized(artifactFingerprint, "");
            suiteFingerprint = normalized(suiteFingerprint, "");
            diagnostics = immutable(diagnostics);
            payloadPersisted = false;
        }
    }

    public record OperatorRunRequest(
            String schemaVersion,
            VisualOperatorContractTestSuiteRequest suite
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringOperatorTestRunRequest.v1";

        public OperatorRunRequest {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        }
    }

    public record OperatorRunEvidence(
            String schemaVersion,
            String runId,
            String draftId,
            long authoringRevision,
            String authoringFingerprint,
            String canonicalFingerprint,
            String artifactFingerprint,
            String suiteFingerprint,
            String evidenceFingerprint,
            Instant executedAt,
            VisualOperatorContractTestSuiteResult result,
            List<VisualDiagnostic> diagnostics,
            boolean payloadPersisted
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringOperatorTestRunEvidence.v1";

        public OperatorRunEvidence {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            runId = normalized(runId, "");
            draftId = normalized(draftId, "");
            authoringRevision = Math.max(0, authoringRevision);
            authoringFingerprint = normalized(authoringFingerprint, "");
            canonicalFingerprint = normalized(canonicalFingerprint, "");
            artifactFingerprint = normalized(artifactFingerprint, "");
            suiteFingerprint = normalized(suiteFingerprint, "");
            evidenceFingerprint = normalized(evidenceFingerprint, "");
            executedAt = executedAt == null ? Instant.EPOCH : executedAt;
            diagnostics = immutable(diagnostics);
            payloadPersisted = false;
        }
    }

    public record FunctionDraftRequest(
            String schemaVersion,
            String functionRef
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringFunctionTestDraftRequest.v1";

        public FunctionDraftRequest {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            functionRef = normalized(functionRef, "");
        }
    }

    public record FunctionRunRequest(
            String schemaVersion,
            FunctionSuite suite
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringFunctionTestRunRequest.v1";

        public FunctionRunRequest {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        }
    }

    public record FunctionSuite(
            String schemaVersion,
            String functionRef,
            List<FunctionCase> cases
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringFunctionTestSuite.v1";

        public FunctionSuite {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            functionRef = normalized(functionRef, "");
            cases = immutable(cases);
        }
    }

    public record FunctionCase(
            String schemaVersion,
            String id,
            FunctionCaseKind kind,
            List<Object> args,
            FunctionAssertion assertion,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            Object expect,
            ExpectedError expectError
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringFunctionTestCase.v1";

        public FunctionCase {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            id = normalized(id, "function-test-case");
            kind = kind == null ? FunctionCaseKind.GOLDEN : kind;
            args = immutableAllowingNull(args);
            assertion = assertion == null ? FunctionAssertion.EQUALS : assertion;
            expectError = expectError == null ? null : expectError;
        }
    }

    public enum FunctionCaseKind {
        GOLDEN,
        NEGATIVE,
        BOUNDARY,
        REGRESSION
    }

    public enum FunctionAssertion {
        EQUALS,
        RETURN_TYPE,
        EXPECT_ERROR
    }

    public record ExpectedError(String code) {
        public ExpectedError {
            code = normalized(code, "FUNCTION_INVOCATION_FAILED");
        }
    }

    public enum FunctionBindingStatus {
        BOUND,
        UNBOUND,
        BLOCKED_BY_POLICY
    }

    public enum FunctionCaseStatus {
        PASSED,
        ASSERTION_FAILED,
        CONTRACT_REJECTED,
        INVOCATION_FAILED,
        TIMEOUT,
        RESOURCE_EXHAUSTED,
        WORKER_UNAVAILABLE,
        WORKER_FAILED,
        NOT_RUN
    }

    public record FunctionDraft(
            String schemaVersion,
            String draftId,
            long authoringRevision,
            String authoringFingerprint,
            String canonicalFingerprint,
            String functionFingerprint,
            String runtimeFingerprint,
            String executionProfile,
            FunctionBindingStatus bindingStatus,
            String suiteFingerprint,
            FunctionSuite suite,
            List<VisualDiagnostic> diagnostics,
            boolean payloadPersisted
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringFunctionTestDraft.v1";

        public FunctionDraft {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            draftId = normalized(draftId, "");
            authoringRevision = Math.max(0, authoringRevision);
            authoringFingerprint = normalized(authoringFingerprint, "");
            canonicalFingerprint = normalized(canonicalFingerprint, "");
            functionFingerprint = normalized(functionFingerprint, "");
            runtimeFingerprint = normalized(runtimeFingerprint, "");
            executionProfile = normalized(executionProfile, "");
            bindingStatus = bindingStatus == null ? FunctionBindingStatus.UNBOUND : bindingStatus;
            suiteFingerprint = normalized(suiteFingerprint, "");
            diagnostics = immutable(diagnostics);
            payloadPersisted = false;
        }
    }

    public record FunctionCaseResult(
            String id,
            FunctionCaseKind kind,
            boolean passed,
            FunctionCaseStatus status,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            Object actual,
            String actualType,
            String errorCode,
            long durationMicros,
            List<VisualDiagnostic> diagnostics
    ) {
        public FunctionCaseResult {
            id = normalized(id, "function-test-case");
            kind = kind == null ? FunctionCaseKind.GOLDEN : kind;
            status = status == null ? FunctionCaseStatus.NOT_RUN : status;
            actualType = normalized(actualType, "null");
            errorCode = normalized(errorCode, "");
            durationMicros = Math.max(0, durationMicros);
            diagnostics = immutable(diagnostics);
        }
    }

    public record FunctionRunEvidence(
            String schemaVersion,
            String runId,
            String draftId,
            long authoringRevision,
            String authoringFingerprint,
            String canonicalFingerprint,
            String functionFingerprint,
            String runtimeFingerprint,
            String executionProfile,
            FunctionBindingStatus bindingStatus,
            String suiteFingerprint,
            String evidenceFingerprint,
            Instant executedAt,
            boolean passed,
            int totalCases,
            int passedCases,
            int failedCases,
            List<FunctionCaseResult> results,
            List<VisualDiagnostic> diagnostics,
            boolean payloadPersisted
    ) {
        public static final String SCHEMA_VERSION = "bloge.visualAuthoringFunctionTestRunEvidence.v1";

        public FunctionRunEvidence {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            runId = normalized(runId, "");
            draftId = normalized(draftId, "");
            authoringRevision = Math.max(0, authoringRevision);
            authoringFingerprint = normalized(authoringFingerprint, "");
            canonicalFingerprint = normalized(canonicalFingerprint, "");
            functionFingerprint = normalized(functionFingerprint, "");
            runtimeFingerprint = normalized(runtimeFingerprint, "");
            executionProfile = normalized(executionProfile, "");
            bindingStatus = bindingStatus == null ? FunctionBindingStatus.UNBOUND : bindingStatus;
            suiteFingerprint = normalized(suiteFingerprint, "");
            evidenceFingerprint = normalized(evidenceFingerprint, "");
            executedAt = executedAt == null ? Instant.EPOCH : executedAt;
            totalCases = Math.max(0, totalCases);
            passedCases = Math.max(0, passedCases);
            failedCases = Math.max(0, failedCases);
            results = immutable(results);
            diagnostics = immutable(diagnostics);
            payloadPersisted = false;
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static <T> List<T> immutable(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private static List<Object> immutableAllowingNull(List<Object> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
