package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
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
    private static final Path BUSINESS_CERTIFICATE = REPOSITORY.resolve(
            "docs/acceptance/agent-tdd/business-solution-codex-certification-v1.json");
    private static final Path BUSINESS_SCHEMA = REPOSITORY.resolve(
            "docs/schemas/resource-gateway-business-recall-certification-v1.schema.json");
    private static final Path BUSINESS_REPORT = REPOSITORY.resolve(
            "docs/acceptance/agent-tdd/business-solution-codex-certification-v1.html");
    private static final Path BUSINESS_SCREENSHOT = REPOSITORY.resolve(
            "docs/acceptance/agent-tdd/business-solution-codex-certification-v1.png");
    private static final Path BUSINESS_PROCESS_MANIFEST = REPOSITORY.resolve(
            "docs/acceptance/agent-tdd/business-solution-codex-process-v1.json");
    private static final Path BUSINESS_PROCESS_DIRECTORY = REPOSITORY.resolve(
            "docs/acceptance/agent-tdd");
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
    void checkedInBusinessCertificateProvesOneCorrelatedHumanReviewJourney() throws Exception {
        JsonNode certificate = mapper.readTree(BUSINESS_CERTIFICATE.toFile());
        JsonNode schema = mapper.readTree(BUSINESS_SCHEMA.toFile());

        assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schema).validate(certificate))
                .as("the business certificate must satisfy its complete Draft 2020-12 schema")
                .isEmpty();
        assertThat(certificate.path("schemaVersion").asText())
                .isEqualTo("rg.businessRecallCertification.v1");
        assertThat(certificate.path("result").asText()).isEqualTo("CERTIFIED");
        String certifiedCommit = certificate.path("repositoryCommit").asText();
        assertThat(certifiedCommit)
                .isEqualTo(certificate.at("/runtimeIdentity/repositoryCommit").asText());
        assertCommitExists(certifiedCommit);
        assertThat(certificate.path("productionTreeFingerprint").asText())
                .isEqualTo(productionTreeFingerprint(certifiedCommit));
        assertThat(certificate.path("certificationInputsFingerprint").asText())
                .isEqualTo(certificationInputsFingerprint(certifiedCommit));
        assertThat(gitObject(certifiedCommit + ":resource-gateway-examples/src/main"))
                .isEqualTo(gitObject("HEAD:resource-gateway-examples/src/main"));
        assertThat(gitObject(certifiedCommit + ":scripts"))
                .isEqualTo(gitObject("HEAD:scripts"));
        assertThat(gitObject(certifiedCommit
                + ":docs/schemas/resource-gateway-business-recall-certification-v1.schema.json"))
                .isEqualTo(gitObject(
                        "HEAD:docs/schemas/resource-gateway-business-recall-certification-v1.schema.json"));
        assertThat(textValues(certificate.at("/journey/requiredSequence"))).containsExactly(
                "rg.feature.define", "rg.scenario.define", "rg.instruction.define",
                "rg.solution.compose", "rg.solution.golden.propose");
        assertThat(certificate.at("/journey/observedCalls")).filteredOn(call ->
                call.path("tool").asText().equals("rg.feature.define")
                        && call.path("status").asText().equals("completed")).hasSize(2);
        assertThat(certificate.at("/journey/observedCalls")).filteredOn(call ->
                call.path("tool").asText().equals("rg.instruction.define")
                        && call.path("status").asText().equals("completed")).hasSize(3);
        assertThat(certificate.at("/journey/observedCalls"))
                .as("the checked-in main authoring journey must retain all 26 correlated calls")
                .hasSize(26);
        assertThat(certificate.at("/journey/observedCalls")).anySatisfy(call -> {
            assertThat(call.path("tool").asText()).isEqualTo("rg.solution.golden.list");
            assertThat(call.path("status").asText()).isEqualTo("completed");
        });
        assertThat(certificate.at("/assertions").properties()).allSatisfy(entry ->
                assertThat(entry.getValue().asBoolean()).as(entry.getKey()).isTrue());
        assertThat(certificate.at("/metrics/recallAt3").asDouble()).isEqualTo(1.0);
        assertThat(certificate.at("/metrics/top1").asDouble()).isGreaterThanOrEqualTo(0.95);
        assertThat(certificate.at("/metrics/clarificationRate").asDouble()).isEqualTo(1.0);
        assertThat(certificate.at("/metrics/recallCases").asInt()).isEqualTo(2);
        assertThat(certificate.at("/metrics/clarificationCases").asInt()).isEqualTo(7);
        assertThat(certificate.at("/familyEvidence")).hasSize(15);
        assertThat(certificate.at("/familyEvidence")).allSatisfy(family ->
                assertThat(family.path("passed").asBoolean()).isTrue());
        assertThat(certificate.at("/familyEvidence")).allSatisfy(family -> {
            assertThat(family.path("firstTool").asText()).startsWith("rg.");
            assertThat(family.path("toolRecallPassed").asBoolean()).isTrue();
        });
        List<String> familyIds = new ArrayList<>();
        certificate.at("/familyEvidence").forEach(value ->
                familyIds.add(value.path("familyId").asText()));
        assertThat(familyIds).containsExactlyInAnyOrder(
                "synonym-rewrite", "near-meaning-distractor", "boundary-unspecified",
                "unknown-policy-unspecified", "authority-source-unspecified", "multiple-exact",
                "legacy-feature-partial", "surface-interference", "cross-session-rediscovery",
                "semantic-drift", "fact-assumption", "dependency-unavailable", "action-stubbing",
                "forbidden-dependency", "assumption-ambiguity");
        assertThat(certificate.at("/cases")).hasSize(3);
        List<String> intentKinds = new ArrayList<>();
        certificate.at("/cases").forEach(value ->
                intentKinds.add(value.path("expectedIntentKind").asText()));
        assertThat(intentKinds)
                .containsExactlyInAnyOrder("CREATE_SOLUTION", "RECALL_CAPABILITY", "DEFINE_FEATURE");
        assertThat(certificate.at("/correlation/cases")).hasSize(2);
        assertThat(certificate.at("/correlation/librarySnapshot").asText())
                .matches("hmac-sha256:[0-9a-f]{64}");
        assertThat(certificate.at("/correlation/authoringPatterns").asText())
                .matches("hmac-sha256:[0-9a-f]{64}");
        assertThat(textValues(certificate.at("/correlation/sessions")))
                .hasSize(16).doesNotHaveDuplicates()
                .allMatch(value -> value.matches("hmac-sha256:[0-9a-f]{64}"));
        assertThat(certificate.at("/runtimeIdentity/codexPhaseCount").asInt()).isEqualTo(4);
        assertThat(textValues(certificate.at("/runtimeIdentity/instanceNonceFingerprints")))
                .hasSize(4).doesNotHaveDuplicates()
                .allMatch(value -> value.matches("sha256:[0-9a-f]{64}"));
        assertThat(certificate.at("/setupIdentity/credentialSeparationVerified").asBoolean()).isTrue();
        assertThat(certificate.at("/runtimeIdentity/codexExecutableSha256").asText())
                .matches("sha256:[0-9a-f]{64}");
        assertThat(certificate.at("/runtimeIdentity/codexCodeDirectoryHash").asText())
                .matches("sha256:[0-9a-f]{64}");
        assertThat(certificate.at("/transport/serverListFiltered").asBoolean()).isTrue();
        assertThat(certificate.at("/transport/directHiddenCallRejected").asBoolean()).isTrue();
        assertThat(certificate.at(
                "/assertions/compilerValidatedAuthoringPatternsObservedBeforeCreation").asBoolean())
                .isTrue();
        assertThat(certificate.at(
                "/assertions/fourEntityWritesBoundToAuthoringPatterns").asBoolean())
                .isTrue();
        assertThat(certificate.at(
                "/assertions/fourEntityBusinessDisplaysDeclared").asBoolean())
                .isTrue();

        String serialized = mapper.writeValueAsString(certificate);
        assertThat(serialized).doesNotContain(
                "arguments", "structured_content", "messages", "featureYaml", "scenarioYaml",
                "instructionYaml", "solutionYaml", "prompt.txt", "trace.jsonl");
        JsonNode unsigned = certificate.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned).remove("certificateFingerprint");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical(unsigned).getBytes(StandardCharsets.UTF_8)));
        assertThat(certificate.path("certificateFingerprint").asText()).isEqualTo("sha256:" + digest);

        String report = Files.readString(BUSINESS_REPORT, StandardCharsets.UTF_8);
        assertThat(report).contains(
                certificate.path("repositoryCommit").asText(),
                "<b>26</b><span>主创作链 MCP 调用</span>", "2 + 1 + 3", "15 / 15",
                "两条完整标准案例", "服务端模板先读", "写入已绑定",
                "未批准、未执行、未发布", "Recall@3", "Top-1",
                "16 个会话身份互不相同", "4 个实例身份互不相同", "UNAVAILABLE");
        var screenshot = ImageIO.read(BUSINESS_SCREENSHOT.toFile());
        assertThat(screenshot).isNotNull();
        assertThat(screenshot.getWidth()).isEqualTo(1440);
        assertThat(screenshot.getHeight()).isEqualTo(1440);

        JsonNode processManifest = mapper.readTree(BUSINESS_PROCESS_MANIFEST.toFile());
        assertThat(toFieldSet(processManifest)).containsExactlyInAnyOrder(
                "schemaVersion", "evidenceKind", "repositoryCommit",
                "certificateFingerprint", "images");
        assertThat(processManifest.path("schemaVersion").asText())
                .isEqualTo("rg.businessCodexProcessEvidence.v1");
        assertThat(processManifest.path("evidenceKind").asText())
                .isEqualTo("REDACTED_REAL_CODEX_TRACE_RENDER");
        assertThat(processManifest.path("repositoryCommit").asText()).isEqualTo(certifiedCommit);
        assertThat(processManifest.path("certificateFingerprint").asText())
                .isEqualTo(certificate.path("certificateFingerprint").asText());
        assertThat(processManifest.path("images")).hasSize(6);
        assertThat(textFieldValues(processManifest.path("images"), "stage"))
                .containsExactly("01", "02", "03", "04", "05", "06");
        assertThat(textFieldValues(processManifest.path("images"), "tool")).containsExactly(
                "rg.library.overview.get", "rg.feature.define", "rg.scenario.define",
                "rg.instruction.define", "rg.solution.compose", "rg.solution.golden.propose");
        assertThat(textFieldValues(processManifest.path("images"), "title")).containsExactly(
                "先读业务积木", "定义业务事实", "定义业务规则", "定义业务动作",
                "组合业务解法", "提交标准案例");
        processManifest.path("images").forEach(image -> {
            assertThat(toFieldSet(image)).containsExactlyInAnyOrder(
                    "stage", "title", "tool", "traceOrdinal", "file", "sha256");
            int traceOrdinal = image.path("traceOrdinal").asInt();
            JsonNode traceCall = observedCallAt(certificate, traceOrdinal);
            assertThat(traceCall.path("tool").asText())
                    .as("process image %s must name the tool recorded at trace ordinal %s",
                            image.path("stage").asText(), traceOrdinal)
                    .isEqualTo(image.path("tool").asText());
            assertThat(traceCall.path("status").asText())
                    .as("process image %s must bind to a completed trace call",
                            image.path("stage").asText())
                    .isEqualTo("completed");
            Path imageFile = BUSINESS_PROCESS_DIRECTORY.resolve(image.path("file").asText());
            try {
                assertThat(sha256(imageFile)).isEqualTo(image.path("sha256").asText());
                var rendered = ImageIO.read(imageFile.toFile());
                assertThat(rendered).isNotNull();
                assertThat(rendered.getWidth()).isEqualTo(1440);
                assertThat(rendered.getHeight()).isEqualTo(900);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private JsonNode observedCallAt(JsonNode certificate, int ordinal) {
        List<JsonNode> matches = new ArrayList<>();
        certificate.at("/journey/observedCalls").forEach(call -> {
            if (call.path("ordinal").asInt() == ordinal) {
                matches.add(call);
            }
        });
        assertThat(matches)
                .as("trace ordinal %s must identify exactly one observed call", ordinal)
                .hasSize(1);
        return matches.getFirst();
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
                "codesign --verify --strict", "TeamIdentifier=2DC432GLL2",
                "OpenAI OpCo, LLC (2DC432GLL2)", "CODEX_EXECUTABLE_SHA256",
                "verify_runtime_identity", "--runtime-instance-nonce", "--runtime-jar-sha256",
                "business_surface_certification_probe.py", "--surface-proof",
                "--spring.datasource.url=jdbc:h2:file:${PRIVATE_DIR}/recall-certification",
                "${PRIVATE_DIR}/recall-certification.mv.db",
                "did not open the required private persistent state store",
                "--production-tree-fingerprint", "PRODUCTION_TREE_FINGERPRINT",
                "--family-manifest", "FAMILY_SUITE_FILE", "FAMILY_RUN_INDEX",
                "near-meaning-distractor", "--phase ambiguity", "assumption-ambiguity",
                "--board-projection", "/api/agent-tdd/board", "X-Purpose: AGENT_TDD_READ",
                "X-RG-Surface\"=\"BUSINESS_SOLUTION",
                "exec env", "SERVICE_PID=$!", "openssl rand -hex 32",
                "RG_CORRECTNESS_AUTHORING_ENABLED=true",
                "PRIVATE_JAR", "chflags uchg", "expected-jar-sha256");
        assertThat(script).doesNotContain("enabled_tools=");
        assertThat(script).doesNotContain("RG_CORRECTNESS_ENABLED=true");
        assertThat(cleanupTrap).isGreaterThanOrEqualTo(0).isLessThan(firstTemporaryDirectory);
        assertThat(cleanupTrap).isLessThan(authenticationCopy);
        assertThat(script).doesNotContain(
                "--sandbox danger-full-access", "example-services.sh\" start resource-gateway",
                "example-services.sh\" stop resource-gateway");
        assertThat(prompt).contains(
                "取消费争议", "平台已经具备哪些业务积木", "取消责任方",
                "取消归责", "谁导致取消", "谁造成了取消",
                "不要复用名称相近但业务含义不同", "乘客超时取消", "司机导致取消",
                "等待我确认的标准案例", "重新查看待确认案例清单",
                "两条标准案例成功提交并从待确认清单读回之前，不要结束",
                "不要替我批准案例", "不要开始验证、签署或发布");
        assertThat(prompt).doesNotContain(
                "DSL", "Schema", "binding", "MCP", "operator", "toolRef", "caseSetRef", "代码", "节点", "端口");
    }

    @Test
    void certificationReducerExecutesMismatchAcceptanceAndBoundedRepairNegativeCases() throws Exception {
        Process process = new ProcessBuilder("python3", "-m", "unittest",
                "agent_tdd_codex_trace_certificate_test",
                "business_solution_codex_trace_certificate_test",
                "business_surface_certification_probe_test",
                "render_business_codex_process_evidence_test")
                .directory(REPOSITORY.resolve("scripts").toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("Ran 72 tests", "OK");
    }

    private String productionTreeFingerprint(String commit) throws Exception {
        return gitObjectFingerprint(commit + ":resource-gateway-examples/src/main");
    }

    private String certificationInputsFingerprint(String commit) throws Exception {
        String scripts = gitObject(commit + ":scripts");
        String schema = gitObject(
                commit + ":docs/schemas/resource-gateway-business-recall-certification-v1.schema.json");
        return sha256(scripts + "\\n" + schema);
    }

    private void assertCommitExists(String commit) throws Exception {
        Process process = new ProcessBuilder("git", "cat-file", "-e", commit + "^{commit}")
                .directory(REPOSITORY.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }

    private String gitObjectFingerprint(String revisionPath) throws Exception {
        return sha256(gitObject(revisionPath));
    }

    private String gitObject(String revisionPath) throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", revisionPath)
                .directory(REPOSITORY.toFile()).redirectErrorStream(true).start();
        String object = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.waitFor()).as(object).isZero();
        return object;
    }

    private static String sha256(String value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(Path path) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
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

    private static List<String> textFieldValues(JsonNode array, String field) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.path(field).asText()));
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
