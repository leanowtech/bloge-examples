package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExampleServicesScriptTest {

    private static final Path SCRIPT = Path.of("..", "scripts", "example-services.sh")
            .toAbsolutePath().normalize();
    private static final Path AGENT_TDD_GUIDE = Path.of("..", "docs", "resource-gateway-agent-tdd-mcp.md")
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
                "RESOURCE_GATEWAY_ADDRESS=\"${RESOURCE_GATEWAY_ADDRESS:-127.0.0.1}\"",
                "export RG_API_RESOURCE_AUTHORING_ENABLED",
                "export RG_REUSABLE_FLOW_AUTHORING_ENABLED",
                "export RG_AUTHORING_LOCAL_SCHEMA_BOOTSTRAP_ENABLED",
                "export RG_INTEGRATION_ENVIRONMENT_ID",
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
                        "RESOURCE_GATEWAY_ADDRESS default: 127.0.0.1",
                        "Set either variable to false to disable that authoring surface");
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
                "node wallet : \"resource:wallet-service.getBalance\"",
                "人工停点一：批准 Oracle",
                "人工停点二：签署发布证据",
                "realExternalCalls=0",
                "artifactKind=EXECUTABLE");
    }
}
