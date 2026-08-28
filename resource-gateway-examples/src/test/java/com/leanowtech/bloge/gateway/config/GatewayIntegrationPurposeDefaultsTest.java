package com.leanowtech.bloge.gateway.config;

import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the profile defaults required by the governed Fixture authoring UI. */
class GatewayIntegrationPurposeDefaultsTest {
    private static final Set<String> GOVERNED_FIXTURE_PURPOSES = Set.of(
            "CORRECTNESS_READ",
            "CORRECTNESS_FIXTURE_MATERIAL_READ",
            "CORRECTNESS_FIXTURE_MATERIAL_WRITE");
    private static final Pattern PROFILE_DEFAULT_PURPOSES = Pattern.compile(
            "allowed-purposes:\\s*\\$\\{[^:}]+:([^}\\n]+)}");
    private static final Pattern JAVA_FALLBACK_PURPOSES = Pattern.compile(
            "allowed-purposes:([^\\n\"]+)");

    @Test
    void allProfileDefaultsAndJavaFallbackAuthorizeGovernedFixtureUi() throws IOException {
        List<Path> profiles = List.of(
                moduleFile("src/main/resources/application.yml"),
                moduleFile("src/main/resources/application-test.yml"),
                moduleFile("src/main/resources/application-staging.yml"));
        for (Path profile : profiles) {
            assertThat(extractDefaults(Files.readString(profile)))
                    .as(profile.toString())
                    .containsAll(GOVERNED_FIXTURE_PURPOSES);
        }

        Set<String> javaFallback = extractDefaults(Files.readString(moduleFile(
                "src/main/java/com/leanowtech/bloge/gateway/config/GatewayConfiguration.java")));
        IntegrationWorkloadIdentity demoIdentity = new IntegrationWorkloadIdentity(
                "demo", "tenant", "organization", "project", "test", "local", "WORKLOAD",
                "actor", "", javaFallback, Instant.MAX, true);
        for (String purpose : GOVERNED_FIXTURE_PURPOSES) {
            assertThat(demoIdentity.allowsPurpose(purpose)).as(purpose).isTrue();
        }
    }

    private static Set<String> extractDefaults(String source) {
        Matcher matcher = PROFILE_DEFAULT_PURPOSES.matcher(source);
        boolean found = matcher.find();
        if (!found) {
            matcher = JAVA_FALLBACK_PURPOSES.matcher(source);
            found = matcher.find();
        }
        assertThat(found).as("allowed-purposes default").isTrue();
        return Arrays.stream(matcher.group(1).replace("}", "").split(","))
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Path moduleFile(String relativePath) {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path direct = workingDirectory.resolve(relativePath);
        return Files.exists(direct)
                ? direct
                : workingDirectory.resolve("resource-gateway-examples").resolve(relativePath);
    }
}
