package com.leanowtech.bloge.gateway.testkit.mounted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MountedCapabilityStudioStageAcceptanceAuthorityProviderTest {
    private static final String PROPERTY =
            "bloge.capabilityStudio.authorityBundleRoot";
    private static final String PROVIDER_CLASS =
            "com.leanowtech.bloge.gateway.testkit.mounted.MountedCapabilityStudioStageAcceptanceAuthorityProvider";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void missingPropertyFailsWithPayloadFreeStableCode() {
        System.clearProperty(PROPERTY);

        assertThatThrownBy(MountedCapabilityStudioStageAcceptanceAuthorityProvider::new)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_REQUIRED_CODE)
                .hasMessageNotContaining("/")
                .hasMessageNotContaining("\\")
                .hasMessageNotContaining("authorityBundleRoot");
    }

    @Test
    void blankPropertyFailsWithPayloadFreeStableCode() {
        System.setProperty(PROPERTY, " \t\n");

        assertThatThrownBy(MountedCapabilityStudioStageAcceptanceAuthorityProvider::new)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_REQUIRED_CODE)
                .hasMessageNotContaining("/")
                .hasMessageNotContaining("\\")
                .hasMessageNotContaining("authorityBundleRoot");
    }

    @Test
    void invalidPathFailsWithPayloadFreeStableCode() {
        System.setProperty(PROPERTY, "\u0000/authority-bundle");

        assertThatThrownBy(MountedCapabilityStudioStageAcceptanceAuthorityProvider::new)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_LOAD_FAILED_CODE)
                .hasMessageNotContaining("authority-bundle")
                .hasMessageNotContaining("/");
    }

    @Test
    void serviceDescriptorContainsExactlyTheMountedProvider() throws IOException {
        String resourceName = "META-INF/services/" +
                CapabilityStudioStageAcceptanceAuthorityProvider.class.getName();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(stream).as("service descriptor").isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(PROVIDER_CLASS + "\n");
        }

        List<String> implementations;
        try (Stream<ServiceLoader.Provider<CapabilityStudioStageAcceptanceAuthorityProvider>> providers =
                     ServiceLoader.load(CapabilityStudioStageAcceptanceAuthorityProvider.class)
                             .stream()) {
            implementations = providers.map(ServiceLoader.Provider::type)
                    .map(Class::getName)
                    .toList();
        }
        assertThat(implementations).containsExactly(PROVIDER_CLASS);
    }

    @Test
    void sourceContainsOneProviderAndOnlyDirectBundleDelegation() throws IOException {
        Path source = Path.of("src/main/java/com/leanowtech/bloge/gateway/testkit/mounted/"
                + "MountedCapabilityStudioStageAcceptanceAuthorityProvider.java");
        String text = Files.readString(source);

        assertThat(count(text, "implements CapabilityStudioStageAcceptanceAuthorityProvider"))
                .isEqualTo(1);
        assertThat(text).contains("return binding.resolver();")
                .contains("return binding.issuerPolicy();")
                .contains("return binding.ownerAuthority();")
                .contains("return binding.fingerprint();")
                .contains("new CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding(");
        assertThat(text).contains("CapabilityStudioMountedAuthorityBundle.load(root, Clock.systemUTC())")
                .contains("Path.of(configuredRoot).toAbsolutePath().normalize()");
        assertThat(text).doesNotContain("System.getenv(")
                .doesNotContain("PrivateKey")
                .doesNotContain("KeyPair")
                .doesNotContain("Signature.getInstance")
                .doesNotContain("sign(")
                .doesNotContain("return null")
                .doesNotContain("defaultTrust")
                .doesNotContain("TRUST_ROOT");
    }

    @Test
    void toStringIsRedactedByConstruction() throws IOException {
        Path source = Path.of("src/main/java/com/leanowtech/bloge/gateway/testkit/mounted/"
                + "MountedCapabilityStudioStageAcceptanceAuthorityProvider.java");
        assertThat(Files.readString(source)).contains("authorityBundleRoot=<redacted>")
                .contains("bundleFingerprint=<redacted>")
                .doesNotContain("configuredRoot}");
    }

    private static int count(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
