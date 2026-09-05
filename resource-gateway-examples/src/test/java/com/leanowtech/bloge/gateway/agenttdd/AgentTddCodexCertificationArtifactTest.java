package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Certifies that the checked-in real-Codex proof is strict, payload-free and reproducible. */
class AgentTddCodexCertificationArtifactTest {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path CERTIFICATE = REPOSITORY.resolve(
            "docs/acceptance/agent-tdd/codex-certification-v1.json");
    private static final Path SCHEMA = REPOSITORY.resolve(
            "docs/schemas/resource-gateway-agent-tdd-codex-certification-v1.schema.json");
    private static final Path SCRIPT = REPOSITORY.resolve("scripts/certify-agent-tdd-codex.sh");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void checkedInCertificateProvesTheBusinessJourneyWithoutCarryingTracePayloads() throws Exception {
        JsonNode certificate = mapper.readTree(CERTIFICATE.toFile());
        JsonNode schema = mapper.readTree(SCHEMA.toFile());

        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schema).validate(certificate))
                .as("the complete checked-in certificate must satisfy its Draft 2020-12 schema")
                .isEmpty();
        assertThat(certificate.path("schemaVersion").asText())
                .isEqualTo("rg.agentTddCodexCertification.v1");
        assertThat(certificate.path("result").asText()).isEqualTo("CERTIFIED");
        assertThat(certificate.path("repositoryCommit").asText()).matches("[0-9a-f]{40}");
        assertThat(certificate.at("/runtimeIdentity/schemaVersion").asText())
                .isEqualTo("rg.agentTddCertificationInstance.v1");
        assertThat(certificate.at("/runtimeIdentity/instanceNonceFingerprint").asText())
                .matches("sha256:[0-9a-f]{64}");
        assertThat(certificate.at("/runtimeIdentity/repositoryCommit").asText())
                .isEqualTo(certificate.path("repositoryCommit").asText());
        assertThat(certificate.at("/runtimeIdentity/jarSha256").asText())
                .matches("sha256:[0-9a-f]{64}");
        assertThat(certificate.at("/runtimeIdentity/processOwnershipVerified").asBoolean()).isTrue();
        assertThat(certificate.at("/runtimeIdentity/verifiedBeforeAndAfterTurn").asBoolean()).isTrue();
        assertThat(certificate.path("certificateFingerprint").asText())
                .matches("sha256:[0-9a-f]{64}");
        assertThat(textValues(certificate.at("/journey/requiredSequence"))).containsExactly(
                "rg.capability.list", "rg.contract.get", "rg.dsl.reference.get", "rg.dsl.preview",
                "rg.gate.check", "rg.tool.compose", "rg.tool.setInstruction", "rg.scenario.upsertCases");
        assertThat(certificate.at("/assertions/requiredAuthoringOrder").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/onlyMcpExternalActionsObserved").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/caseSetBoundToTool").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/dependencyBehaviorDefined").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/businessOracleProposed").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/stoppedBeforeExecutionGovernanceAndPublication").asBoolean())
                .isTrue();
        assertThat(certificate.at("/assertions/finalSummaryBusinessOnly").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/boundedRepairPolicyRespected").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/sameCandidateReceiptAndAssets").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/businessDecisionTableAuthored").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/sameToolBoardProjectionObserved").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/businessFlowSummaryProjected").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/sameCasesVisibleOnBoard").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/spawnedRuntimeIdentityVerified").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/selfRepairOrFirstPassAccepted").asBoolean()).isTrue();
        assertThat(certificate.at("/assertions/selfRepairObserved").asBoolean()
                || certificate.at("/assertions/firstPassAccepted").asBoolean()).isTrue();
        assertThat(certificate.at("/correlation/method").asText())
                .isEqualTo("EPHEMERAL_HMAC_SHA256");
        assertThat(certificate.at("/correlation/cases")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(textValues(certificate.at("/correlation/cases")))
                .allMatch(value -> value.matches("hmac-sha256:[0-9a-f]{64}"));
        assertThat(certificate.at("/journey/observedCalls")).allSatisfy(call ->
                assertThat(toFieldSet(call)).containsExactlyInAnyOrder(
                        "ordinal", "server", "tool", "status"));

        String serialized = mapper.writeValueAsString(certificate);
        assertThat(serialized).doesNotContain(
                "arguments", "structured_content", "messages", "source", "Alice", "u-100", "premium",
                "secret", "private-tool");

        JsonNode unsigned = certificate.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned).remove("certificateFingerprint");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical(unsigned).getBytes(StandardCharsets.UTF_8)));
        assertThat(certificate.path("certificateFingerprint").asText()).isEqualTo("sha256:" + digest);
    }

    @Test
    void certificationScriptUsesAnIsolatedBusinessOnlyPromptAndPrivateTraceLifecycle() throws Exception {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        int promptStart = script.indexOf("cat > \"${PROMPT_FILE}\" <<EOF");
        String prompt = script.substring(promptStart, script.indexOf("\nEOF\n", promptStart));
        int cleanupTrap = script.indexOf("trap cleanup EXIT");
        int firstTemporaryDirectory = script.indexOf("PRIVATE_DIR=\"$(mktemp");
        int authenticationCopy = script.indexOf("cp \"${SOURCE_AUTH_FILE}\"");

        assertThat(script).contains(
                "--ephemeral", "--ignore-user-config", "--ignore-rules", "--sandbox read-only",
                "--disable apps", "--disable plugins", "--disable skill_search", "--disable shell_tool",
                "--disable unified_exec", "--disable browser_use", "--disable computer_use",
                "mktemp -d", "chmod 700 \"${PRIVATE_DIR}\"", "chmod 600 \"${TEMP_OUTPUT}\"",
                "trap cleanup EXIT", "business_solution_codex_trace_certificate.py",
                "repository_is_clean", "ls-files --others --exclude-standard",
                "clean package -DskipTests", "cd \"${WORKSPACE_DIR}\"",
                "(deny process-exec)", "codex-code-mode-host", "(deny file-write*)",
                "did not deny non-Codex process execution", "ISOLATED_CODEX_DIR",
                "sandbox-exec -f \"${SANDBOX_PROFILE}\"",
                "verify_runtime_identity", "--runtime-instance-nonce", "--runtime-jar-sha256",
                "--board-projection", "/api/agent-tdd/board", "X-Purpose: AGENT_TDD_READ",
                "X-RG-Surface\"=\"BUSINESS_SOLUTION",
                "exec env", "SERVICE_PID=$!", "openssl rand -hex 32",
                "PRIVATE_JAR", "chflags uchg", "expected-jar-sha256");
        assertThat(cleanupTrap).isGreaterThanOrEqualTo(0).isLessThan(firstTemporaryDirectory);
        assertThat(cleanupTrap).isLessThan(authenticationCopy);
        assertThat(script).doesNotContain(
                "--sandbox danger-full-access", "example-services.sh\" start resource-gateway",
                "example-services.sh\" stop resource-gateway");
        assertThat(prompt).contains(
                "取消费争议", "平台已经具备哪些业务积木", "取消责任方",
                "不要复用名称相近但业务含义不同", "乘客超时取消", "司机导致取消",
                "等待我确认的标准案例", "不要替我批准案例", "不要开始验证、签署或发布");
        assertThat(prompt).doesNotContain(
                "DSL", "Schema", "binding", "MCP", "operator", "toolRef", "caseSetRef", "代码", "节点", "端口");
    }

    @Test
    void certificationReducerExecutesMismatchAcceptanceAndBoundedRepairNegativeCases() throws Exception {
        Process process = new ProcessBuilder("python3", "-m", "unittest",
                "agent_tdd_codex_trace_certificate_test",
                "business_solution_codex_trace_certificate_test")
                .directory(REPOSITORY.resolve("scripts").toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("Ran 26 tests", "OK");
    }

    private static Set<String> toFieldSet(JsonNode node) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.asText()));
        return result;
    }

    private String canonical(JsonNode node) throws Exception {
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            List<String> members = new ArrayList<>();
            for (String field : fields) {
                members.add(mapper.writeValueAsString(field) + ":" + canonical(node.get(field)));
            }
            return "{" + String.join(",", members) + "}";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(value -> {
                try {
                    values.add(canonical(value));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            return "[" + String.join(",", values) + "]";
        }
        return mapper.writeValueAsString(node);
    }
}
