package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.core.spi.ExecutionServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureExecutionServicesTest {

    private static final String TARGET = "sha256:" + "a".repeat(64);

    @Test
    void parsesStrictScalarIdentityAndBooleanFlagMapsInCanonicalOrder() {
        FixtureExecutionServices controls = FixtureExecutionServices.from(fixture(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", Map.of(
                        "tenant", "acme", "authenticated", true, "riskLevel", 7),
                "featureFlags", Map.of("pricing-v2", true, "legacy-path", false))));

        assertThat(controls.configured()).isTrue();
        assertThat(controls.identityAttributes()).containsExactly(
                Map.entry("authenticated", true), Map.entry("riskLevel", 7),
                Map.entry("tenant", "acme"));
        assertThat(controls.featureFlags()).containsExactly(
                Map.entry("legacy-path", false), Map.entry("pricing-v2", true));
        assertThat(controls.configures(ExecutionServiceKind.IDENTITY)).isTrue();
        assertThat(controls.configures(ExecutionServiceKind.FEATURE_FLAG)).isTrue();
        assertThat(controls.configures(ExecutionServiceKind.SECRET)).isFalse();
        assertThatThrownBy(() -> controls.identityAttributes().put("subject", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void v2CarriesOnlyOpaqueSecretReferencesInCanonicalOrder() {
        FixtureExecutionServices controls = FixtureExecutionServices.from(fixture(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION_V2,
                "identityAttributes", Map.of("tenant", "acme"),
                "featureFlags", Map.of("pricing-v2", true),
                "secretRefs", Map.of(
                        "payment-key", "vault://test/payments/key@v3",
                        "audit-token", "test-secret://risk/audit@2026-07"))));

        assertThat(controls.secretRefs()).containsExactly(
                Map.entry("audit-token", "test-secret://risk/audit@2026-07"),
                Map.entry("payment-key", "vault://test/payments/key@v3"));
        assertThat(controls.configures(ExecutionServiceKind.SECRET)).isTrue();
        assertThat(controls.configuration(ExecutionServiceKind.SECRET))
                .isEqualTo(controls.secretRefs());
        assertThatThrownBy(() -> controls.secretRefs().put(
                "late", "vault://test/late@v1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void absentReservedMetadataLeavesEveryAmbientAuthorityUnconfigured() {
        FixtureExecutionServices controls = FixtureExecutionServices.from(fixtureWithoutControls());

        assertThat(controls.configured()).isFalse();
        assertThat(controls.identityAttributes()).isEmpty();
        assertThat(controls.featureFlags()).isEmpty();
        assertThat(controls.configures(ExecutionServiceKind.IDENTITY)).isFalse();
    }

    @Test
    void configuredNamespacesAdvertiseAvailabilityIndependently() {
        FixtureExecutionServices controls = FixtureExecutionServices.from(fixture(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", Map.of(),
                "featureFlags", Map.of("pricing-v2", true))));

        assertThat(controls.configured()).isTrue();
        assertThat(controls.configures(ExecutionServiceKind.IDENTITY)).isFalse();
        assertThat(controls.configures(ExecutionServiceKind.FEATURE_FLAG)).isTrue();
    }

    @Test
    void rejectsUnknownFieldsWrongValueTypesAndNeverEchoesPayloads() {
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", Map.of(),
                "featureFlags", Map.of(),
                "secretValues", Map.of()), "exactly");
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", Map.of("subject", Map.of("raw-secret-47", true)),
                "featureFlags", Map.of()), "non-null JSON strings");
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", Map.of(),
                "featureFlags", Map.of("pricing", "raw-secret-47")), "booleans");

        assertThatThrownBy(() -> FixtureExecutionServices.from(fixture(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", Map.of("subject", Map.of("raw-secret-47", true)),
                "featureFlags", Map.of()))))
                .hasMessageNotContaining("raw-secret-47")
                .hasMessageNotContaining("subject");

        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION_V2,
                "identityAttributes", Map.of(),
                "featureFlags", Map.of(),
                "secretRefs", Map.of()), "at least one");
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION_V2,
                "identityAttributes", Map.of(),
                "featureFlags", Map.of(),
                "secretRefs", Map.of("payment-key", "raw-secret-47")),
                "absolute opaque URI");
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION_V2,
                "identityAttributes", Map.of(),
                "featureFlags", Map.of(),
                "secretRefs", Map.of("payment-key", "secret:raw-secret-47")),
                "absolute opaque URI");
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION_V2,
                "identityAttributes", Map.of(),
                "featureFlags", Map.of(),
                "secretRefs", Map.of("payment-key", "https://user:password@vault.test/key")),
                "must not contain user info");
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION_V2,
                "identityAttributes", Map.of(),
                "featureFlags", Map.of(),
                "secretRefs", Map.of("payment-key", "vault://test/key?token=raw-secret-47")),
                "query or fragment");
    }

    @Test
    void enforcesEntryKeyStringAndAggregateByteBounds() {
        Map<String, Boolean> tooManyFlags = new LinkedHashMap<>();
        for (int index = 0; index <= FixtureExecutionServices.MAX_ENTRIES; index++) {
            tooManyFlags.put("flag-" + index, true);
        }
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", Map.of(),
                "featureFlags", tooManyFlags), "at most 100");
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", Map.of("invalid key", "value"),
                "featureFlags", Map.of()), "must match");

        Map<String, Object> oversized = new LinkedHashMap<>();
        for (int index = 0; index < 17; index++) {
            oversized.put("attribute-" + index,
                    "x".repeat(FixtureExecutionServices.MAX_IDENTITY_STRING_CHARACTERS));
        }
        assertRejected(Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "identityAttributes", oversized,
                "featureFlags", Map.of()), "65536-byte");
    }

    private static void assertRejected(Map<String, Object> controls, String message) {
        assertThatThrownBy(() -> FixtureExecutionServices.from(fixture(controls)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata.executionServices", message);
    }

    private static FixtureBundle fixture(Map<String, Object> controls) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1, TARGET,
                "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), 42L,
                List.of(), List.of(), Map.of(FixtureExecutionServices.METADATA_KEY, controls));
    }

    private static FixtureBundle fixtureWithoutControls() {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1, TARGET,
                "INTERNAL", null, null, List.of(), List.of(), Map.of("owner", "quality"));
    }
}
