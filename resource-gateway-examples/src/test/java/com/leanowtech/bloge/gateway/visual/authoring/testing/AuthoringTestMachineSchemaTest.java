package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.AssetGate;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.AssetKind;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.CaseSummary;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.Coverage;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.DraftGate;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceRecord;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceView;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.FreshnessStatus;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.GateStatus;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionAssertion;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCase;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionCaseKind;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionSuite;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationOutcome;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringFunctionWorkerProtocol.InvocationResponse;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestCase;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestDraftRequest;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRequest;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringTestMachineSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void operatorDraftAndRunRequestsMatchTheirMachineContracts() throws Exception {
        OperatorDraftRequest draft = new OperatorDraftRequest(
                OperatorDraftRequest.SCHEMA_VERSION,
                new VisualOperatorContractTestDraftRequest(
                        VisualOperatorContractTestDraftRequest.SCHEMA_VERSION,
                        "demo:echo",
                        "generated contract case",
                        true,
                        Map.of(),
                        Map.of(),
                        Map.of()));
        OperatorRunRequest run = new OperatorRunRequest(
                OperatorRunRequest.SCHEMA_VERSION,
                new VisualOperatorContractTestSuiteRequest(
                        "demo:echo",
                        List.of(new VisualOperatorContractTestCase(
                                "echoes a value",
                                Map.of("request", "hello"),
                                Map.of(),
                                Map.of("result", "hello"),
                                Map.of()))));

        assertThat(validate("bloge-visual-authoring-operator-test-draft-request-v1.schema.json",
                mapper.convertValue(draft, Object.class))).isEmpty();
        assertThat(validate("bloge-visual-authoring-operator-test-run-request-v1.schema.json",
                mapper.convertValue(run, Object.class))).isEmpty();

        Map<String, Object> invalid = mapper.convertValue(run, Map.class);
        Map<String, Object> suite = new LinkedHashMap<>((Map<String, Object>) invalid.get("suite"));
        suite.put("cases", List.of());
        invalid.put("suite", suite);
        assertThat(validate("bloge-visual-authoring-operator-test-run-request-v1.schema.json", invalid))
                .extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("/suite/cases"));
    }

    @Test
    void functionRunRequestSupportsNullFixturesButRejectsTooManyArguments() throws Exception {
        FunctionRunRequest run = new FunctionRunRequest(
                FunctionRunRequest.SCHEMA_VERSION,
                new FunctionSuite(
                        FunctionSuite.SCHEMA_VERSION,
                        "coalesce",
                        List.of(new FunctionCase(
                                FunctionCase.SCHEMA_VERSION,
                                "null boundary",
                                FunctionCaseKind.BOUNDARY,
                                java.util.Arrays.asList(null, "fallback"),
                                FunctionAssertion.EQUALS,
                                "fallback",
                                null))));

        assertThat(validate("bloge-visual-authoring-function-test-run-request-v1.schema.json",
                mapper.convertValue(run, Object.class))).isEmpty();

        Map<String, Object> invalid = mapper.convertValue(run, Map.class);
        Map<String, Object> suite = new LinkedHashMap<>((Map<String, Object>) invalid.get("suite"));
        List<Map<String, Object>> cases =
                (List<Map<String, Object>>) suite.get("cases");
        Map<String, Object> testCase = new LinkedHashMap<>(cases.getFirst());
        testCase.put("args", java.util.Collections.nCopies(33, "value"));
        suite.put("cases", List.of(testCase));
        invalid.put("suite", suite);
        assertThat(validate("bloge-visual-authoring-function-test-run-request-v1.schema.json", invalid))
                .extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("/args"));
    }

    @Test
    void isolatedWorkerRequestAndResponseMatchTheirClosedMachineContracts() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String fingerprint = "sha256:" + "a".repeat(64);
        InvocationRequest request = new InvocationRequest(
                InvocationRequest.SCHEMA_VERSION,
                requestId,
                "trim",
                fingerprint,
                java.util.Arrays.asList(null, "value"));
        InvocationResponse response = new InvocationResponse(
                InvocationResponse.SCHEMA_VERSION,
                requestId,
                AuthoringFunctionWorkerProtocol.EXECUTION_PROFILE,
                fingerprint,
                InvocationOutcome.SUCCESS,
                "value",
                "",
                125);

        assertThat(validate(
                "bloge-visual-authoring-function-worker-invocation-request-v1.schema.json",
                mapper.convertValue(request, Object.class))).isEmpty();
        assertThat(validate(
                "bloge-visual-authoring-function-worker-invocation-response-v1.schema.json",
                mapper.convertValue(response, Object.class))).isEmpty();

        Map<String, Object> invalid = mapper.convertValue(response, Map.class);
        invalid.put("executionProfile", "in-process");
        assertThat(validate(
                "bloge-visual-authoring-function-worker-invocation-response-v1.schema.json",
                invalid)).extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("/executionProfile"));
    }

    @Test
    void signedEvidenceViewAndDraftGateMatchClosedMachineContracts() throws Exception {
        String fingerprint = "sha256:" + "a".repeat(64);
        AuthoringTestScope scope =
                new AuthoringTestScope("tenant-a", "org-a", "project-a", "test", "sg");
        EvidenceRecord signed = new InMemoryAuthoringTestEvidenceRepository(
                mapper, new InMemoryVisualEvidenceSigner()).create(new EvidenceRecord(
                EvidenceRecord.SCHEMA_VERSION,
                scope,
                "run-1",
                AssetKind.OPERATOR,
                "demo:echo",
                "draft-1",
                3,
                fingerprint,
                fingerprint,
                fingerprint,
                "",
                "",
                fingerprint,
                fingerprint,
                AuthoringTestEvidenceProtocol.POLICY_VERSION,
                "SCHEMA_CONTRACT",
                "",
                true,
                1,
                1,
                0,
                1,
                new Coverage(1, 1, 1, 1, 1),
                List.of(new CaseSummary(
                        "customer-42 golden payload",
                        "CONTRACT",
                        "PASSED",
                        true,
                        1,
                        10,
                        "",
                        List.of())),
                List.of("suite://customer-42/private-golden"),
                List.of(),
                Instant.parse("2026-07-31T00:00:00Z"),
                "quality-bot",
                false,
                "",
                null));
        EvidenceView view = new EvidenceView(
                EvidenceView.SCHEMA_VERSION,
                signed,
                "VERIFIED",
                FreshnessStatus.CURRENT,
                List.of(),
                3,
                fingerprint,
                fingerprint,
                Instant.parse("2026-07-31T00:00:01Z"));
        DraftGate gate = new DraftGate(
                DraftGate.SCHEMA_VERSION,
                scope,
                "draft-1",
                3,
                fingerprint,
                fingerprint,
                AuthoringTestEvidenceProtocol.POLICY_VERSION,
                GateStatus.PASSED,
                "TEST_EVIDENCED",
                1,
                1,
                List.of(),
                List.of(new AssetGate(
                        AssetKind.OPERATOR,
                        "demo:echo",
                        GateStatus.PASSED,
                        List.of(),
                        signed.runId(),
                        signed.materialFingerprint(),
                        FreshnessStatus.CURRENT,
                        1,
                        1,
                        1,
                        "SCHEMA_CONTRACT")),
                Instant.parse("2026-07-31T00:00:01Z"));

        assertThat(validate(
                "bloge-visual-authoring-test-evidence-view-v1.schema.json",
                mapper.convertValue(view, Object.class))).isEmpty();
        assertThat(validate(
                "bloge-visual-authoring-test-evidence-gate-v1.schema.json",
                mapper.convertValue(gate, Object.class))).isEmpty();
        String encoded = mapper.writeValueAsString(view);
        assertThat(signed.cases().getFirst().caseId())
                .matches("^case:sha256:[a-f0-9]{64}$");
        assertThat(signed.declaredTestRefs()).singleElement()
                .satisfies(reference -> assertThat(reference)
                        .matches("^test-ref:sha256:[a-f0-9]{64}$"));
        assertThat(encoded)
                .doesNotContain("customer-42", "private-golden");

        Map<String, Object> invalid = mapper.convertValue(view, Map.class);
        Map<String, Object> evidenceBody =
                new LinkedHashMap<>((Map<String, Object>) invalid.get("evidence"));
        evidenceBody.put("payloadPersisted", true);
        invalid.put("evidence", evidenceBody);
        assertThat(validate(
                "bloge-visual-authoring-test-evidence-view-v1.schema.json",
                invalid)).extracting(VisualDiagnostic::target)
                .anyMatch(target -> target.contains("/payloadPersisted"));
    }

    @SuppressWarnings("unchecked")
    private List<VisualDiagnostic> validate(String schemaFile, Object value) throws Exception {
        Path path = Path.of("..", "docs", "schemas", schemaFile);
        Map<String, Object> schema = mapper.readValue(Files.readString(path), Map.class);
        return VisualSchemaValidator.validateValue(
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema),
                value,
                "/authoringTest");
    }
}
