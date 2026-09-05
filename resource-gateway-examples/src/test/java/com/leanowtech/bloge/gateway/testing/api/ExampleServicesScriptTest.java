package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class ExampleServicesScriptTest {

    private static final Path SCRIPT = Path.of("..", "scripts", "example-services.sh")
            .toAbsolutePath().normalize();
    private static final Path AGENT_TDD_GUIDE = Path.of("..", "docs", "resource-gateway-agent-tdd-mcp.md")
            .toAbsolutePath().normalize();
    private static final Path CODEX_CONFIG = Path.of("..", ".codex", "config.toml")
            .toAbsolutePath().normalize();

    @Test
    void standardLauncherDefaultsAuthoringFeaturesOnWithoutRemovingExplicitOverrides()
            throws Exception {
        String source = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        assertThat(source).contains(
                "RG_API_RESOURCE_AUTHORING_ENABLED=\"${RG_API_RESOURCE_AUTHORING_ENABLED:-true}\"",
                "RG_REUSABLE_FLOW_AUTHORING_ENABLED=\"${RG_REUSABLE_FLOW_AUTHORING_ENABLED:-true}\"",
                "RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED=\"${RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED:-true}\"",
                "RG_INTEGRATION_ENVIRONMENT_ID=\"${RG_INTEGRATION_ENVIRONMENT_ID:-local}\"",
                "RG_CORRECTNESS_AUTHORING_ENABLED=\"${RG_CORRECTNESS_AUTHORING_ENABLED:-false}\"",
                "RG_CORRECTNESS_FIXTURE_MATERIAL_ENABLED=\"${RG_CORRECTNESS_FIXTURE_MATERIAL_ENABLED:-false}\"",
                "RG_AGENT_TDD_CANCEL_DISPUTE_DEMO_ENABLED=\"${RG_AGENT_TDD_CANCEL_DISPUTE_DEMO_ENABLED:-false}\"",
                "prepare_local_fixture_material_key",
                "openssl rand -base64 32",
                "target/example-secrets",
                "RESOURCE_GATEWAY_ADDRESS=\"${RESOURCE_GATEWAY_ADDRESS:-127.0.0.1}\"",
                "export RG_API_RESOURCE_AUTHORING_ENABLED",
                "export RG_REUSABLE_FLOW_AUTHORING_ENABLED",
                "export RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED",
                "export RG_INTEGRATION_ENVIRONMENT_ID",
                "export RG_CORRECTNESS_AUTHORING_ENABLED",
                "export RG_CORRECTNESS_FIXTURE_MATERIAL_ENABLED",
                "export RG_CORRECTNESS_FIXTURE_MATERIAL_KEY_RING",
                "export RG_AGENT_TDD_CANCEL_DISPUTE_DEMO_ENABLED",
                "--server.address=${RESOURCE_GATEWAY_ADDRESS}");

        Process process = new ProcessBuilder("bash", SCRIPT.toString(), "--help")
                .redirectErrorStream(true)
                .start();

        assertThat(process.waitFor()).isZero();
        assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .contains(
                        "RG_API_RESOURCE_AUTHORING_ENABLED default: true",
                        "RG_REUSABLE_FLOW_AUTHORING_ENABLED default: true",
                        "RG_INTEGRATION_ENVIRONMENT_ID default: local",
                        "RG_CORRECTNESS_AUTHORING_ENABLED default: false",
                        "RG_CORRECTNESS_FIXTURE_MATERIAL_ENABLED default: false",
                        "RG_AGENT_TDD_CANCEL_DISPUTE_DEMO_ENABLED default: false",
                        "RESOURCE_GATEWAY_ADDRESS default: 127.0.0.1",
                        "Set an authoring variable to false to disable that surface");
    }

    @Test
    void reusedLocalFixtureKeyIsAlwaysRestrictedToOwnerReadWrite() throws Exception {
        Path secretDirectory = Files.createTempDirectory("rg-fixture-secret-test-");
        Path key = secretDirectory.resolve("resource-gateway-fixture-material.key");
        Files.writeString(key, "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        try {
            Process process = new ProcessBuilder("bash", "-c", """
                    source "$1"
                    SECRET_DIR="$2"
                    RG_CORRECTNESS_FIXTURE_MATERIAL_ENABLED=true
                    RG_CORRECTNESS_AUTHORING_ENABLED=true
                    RG_CORRECTNESS_FIXTURE_MATERIAL_KEY_RING=
                    prepare_local_fixture_material_key
                    stat -f '%Lp' "$2/resource-gateway-fixture-material.key" 2>/dev/null \
                      || stat -c '%a' "$2/resource-gateway-fixture-material.key"
                    """, "fixture-key-test", SCRIPT.toString(), secretDirectory.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();

            assertThat(process.waitFor()).isZero();
            assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim())
                    .isEqualTo("600");
            assertThat(Files.getPosixFilePermissions(key))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        } finally {
            Files.deleteIfExists(key);
            Files.deleteIfExists(secretDirectory);
        }
    }

    @Test
    void localFixtureKeyRejectsASymlinkedSecretDirectory() throws Exception {
        Path parent = Files.createTempDirectory("rg-fixture-secret-parent-");
        Path outside = Files.createTempDirectory("rg-fixture-secret-outside-");
        Path secretLink = parent.resolve("example-secrets");
        Files.createSymbolicLink(secretLink, outside);
        try {
            Process process = new ProcessBuilder("bash", "-c", """
                    source "$1"
                    SECRET_DIR="$2"
                    RG_CORRECTNESS_FIXTURE_MATERIAL_ENABLED=true
                    RG_CORRECTNESS_AUTHORING_ENABLED=true
                    RG_CORRECTNESS_FIXTURE_MATERIAL_KEY_RING=
                    prepare_local_fixture_material_key
                    """, "fixture-key-symlink-test", SCRIPT.toString(), secretLink.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();

            assertThat(process.waitFor()).isNotZero();
            assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("secret directory must be a regular non-symlink directory");
            assertThat(outside.resolve("resource-gateway-fixture-material.key")).doesNotExist();
        } finally {
            Files.deleteIfExists(outside.resolve("resource-gateway-fixture-material.key"));
            Files.deleteIfExists(secretLink);
            Files.deleteIfExists(parent);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void codexGuideKeepsLeastPrivilegeSetupAndGovernedHumanStopsExecutable() throws Exception {
        String guide = Files.readString(AGENT_TDD_GUIDE, StandardCharsets.UTF_8);

        assertThat(guide).contains(
                "[mcp_servers.rg_read]",
                "[mcp_servers.rg_author]",
                "[mcp_servers.rg_execute]",
                "[mcp_servers.rg_govern]",
                "bearer_token_env_var = \"RG_MCP_TOKEN\"",
                "RG_INTEGRATION_ENVIRONMENT_ID=local",
                "RG_CORRECTNESS_AUTHORING_ENABLED=true",
                "RG_CORRECTNESS_FIXTURE_MATERIAL_ENABLED=true",
                "RG_AGENT_TDD_CANCEL_DISPUTE_DEMO_ENABLED=true",
                "业务提示词不应包含 BLOGE DSL、Schema、binding、节点、端口或 MCP 参数",
                "请把“按用户编号查询用户姓名和会员等级”做成客服助手可用的业务能力",
                "请自行完成平台需要的工作和检查，不要向我展示过程或真实用户资料",
                "人工停点一：批准 Oracle",
                "人工停点二：签署发布证据",
                "`realExternalCalls` 必须为 `0`",
                "artifactKind=EXECUTABLE");
    }

    @Test
    void projectCodexConfigExposesTheCompleteBusinessFrontDoorWithoutHumanSecrets() throws Exception {
        String config = Files.readString(CODEX_CONFIG, StandardCharsets.UTF_8);

        assertThat(config).contains(
                "[mcp_servers.rg_read]", "[mcp_servers.rg_author]",
                "[mcp_servers.rg_execute]", "[mcp_servers.rg_govern]",
                "bearer_token_env_var = \"RG_MCP_TOKEN\"",
                "\"rg.library.overview.get\"", "\"rg.feature.handoff\"",
                "\"rg.scenario.define\"", "\"rg.instruction.define\"",
                "\"rg.solution.compose\"", "\"rg.solution.baseline\"",
                "\"rg.solution.readiness\"", "\"rg.solution.publish\"");
        assertThat(config).doesNotContain(
                "bloge-aneke-demo-token", "bloge-reviewer-demo-token",
                "RG_INTEGRATION_DEMO_REVIEW_TOKEN", "FEATURE_ENGINEER_TOKEN",
                "INSTRUCTION_ENGINEER_TOKEN");
    }
}
