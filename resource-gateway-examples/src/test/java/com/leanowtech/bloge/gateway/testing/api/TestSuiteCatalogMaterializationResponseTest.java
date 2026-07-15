package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Constructor-level fail-closed checks for exact catalog materialization references. */
class TestSuiteCatalogMaterializationResponseTest {

    @Test
    void rejectsUnsupportedProtocolVersionAndAggregateDrift() {
        assertThatThrownBy(() -> response("bloge.testSuiteCatalogMaterialization.v2", suiteAsset()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");

        assertThatThrownBy(() -> new TestSuiteCatalogMaterializationResponse(
                "", "catalog-a", fingerprint('a'), "tenant-a", "test", 1, 2,
                List.of(suiteAsset())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counters");
    }

    @Test
    void rejectsIncompleteSuiteAndFixtureReferences() {
        assertThatThrownBy(() -> response("",
                new TestSuiteCatalogMaterializationResponse.SuiteAsset(
                        "source-a", "graph-a", 1,
                        new TestSuiteExecutionRequest.SuiteRef("suite-a", 0, fingerprint('b')),
                        List.of(fixtureRef()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact fixture reference");

        assertThatThrownBy(() -> response("",
                new TestSuiteCatalogMaterializationResponse.SuiteAsset(
                        "source-a", "graph-a", 1, suiteRef(),
                        List.of(new TestSuite.FixtureBundleRef(
                                "fixture-a", 1, "truncated")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact fixture reference");
    }

    @Test
    void rejectsFixtureIdentityReusedAcrossSuites() {
        var first = suiteAsset();
        var second = new TestSuiteCatalogMaterializationResponse.SuiteAsset(
                "source-b", "graph-b", 1,
                new TestSuiteExecutionRequest.SuiteRef("suite-b", 1, fingerprint('d')),
                List.of(fixtureRef()));

        assertThatThrownBy(() -> new TestSuiteCatalogMaterializationResponse(
                "", "catalog-a", fingerprint('a'), "tenant-a", "test", 2, 2,
                List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique exact fixture");
    }

    private static TestSuiteCatalogMaterializationResponse response(
            String schemaVersion, TestSuiteCatalogMaterializationResponse.SuiteAsset asset) {
        return new TestSuiteCatalogMaterializationResponse(schemaVersion, "catalog-a",
                fingerprint('a'), "tenant-a", "test", 1, 1, List.of(asset));
    }

    private static TestSuiteCatalogMaterializationResponse.SuiteAsset suiteAsset() {
        return new TestSuiteCatalogMaterializationResponse.SuiteAsset(
                "source-a", "graph-a", 1, suiteRef(), List.of(fixtureRef()));
    }

    private static TestSuiteExecutionRequest.SuiteRef suiteRef() {
        return new TestSuiteExecutionRequest.SuiteRef("suite-a", 1, fingerprint('b'));
    }

    private static TestSuite.FixtureBundleRef fixtureRef() {
        return new TestSuite.FixtureBundleRef("fixture-a", 1, fingerprint('c'));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
