package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExampleServicesScriptTest {

    private static final Path SCRIPT = Path.of("..", "scripts", "example-services.sh")
            .toAbsolutePath().normalize();

    @Test
    void standardLauncherDefaultsAuthoringFeaturesOnWithoutRemovingExplicitOverrides()
            throws Exception {
        String source = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        assertThat(source).contains(
                "RG_API_RESOURCE_AUTHORING_ENABLED=\"${RG_API_RESOURCE_AUTHORING_ENABLED:-true}\"",
                "RG_REUSABLE_FLOW_AUTHORING_ENABLED=\"${RG_REUSABLE_FLOW_AUTHORING_ENABLED:-true}\"",
                "export RG_API_RESOURCE_AUTHORING_ENABLED",
                "export RG_REUSABLE_FLOW_AUTHORING_ENABLED");

        Process process = new ProcessBuilder("bash", SCRIPT.toString(), "--help")
                .redirectErrorStream(true)
                .start();

        assertThat(process.waitFor()).isZero();
        assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .contains(
                        "RG_API_RESOURCE_AUTHORING_ENABLED default: true",
                        "RG_REUSABLE_FLOW_AUTHORING_ENABLED default: true",
                        "Set either variable to false to disable that authoring surface");
    }
}
