package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeCertificationIntegrityTest {
    private final RuntimeCertificationTestFixtures fixtures =
            new RuntimeCertificationTestFixtures();

    @Test
    void verifiesCompleteSignedCertificationClosure() {
        assertThat(fixtures.integrity.canonicalManifestVerified(fixtures.manifest)).isTrue();
        assertThat(fixtures.integrity.canonicalAuthorizationVerified(fixtures.authorization))
                .isTrue();
        assertThat(fixtures.integrity.canonicalReportVerified(fixtures.report)).isTrue();

        assertThat(verify(fixtures.report).outcome())
                .isEqualTo(RuntimeCertificationIntegrity.Outcome.VERIFIED);
    }

    @Test
    void cannotBuildManifestWithMissingScenarioOrWeakenedInvariant() {
        ArrayList<RuntimeCertificationManifest.ScenarioRequirement> missing =
                new ArrayList<>(fixtures.requirements());
        missing.removeLast();
        assertThatThrownBy(() -> fixtures.integrity.addressManifest(
                new RuntimeCertificationIntegrity.ManifestMaterial(
                        "runtime-certification:weak", 1, fixtures.scope,
                        fixtures.manifest.region(), fixtures.deployment,
                        RuntimeCertificationManifest.EnvironmentClass.SANDBOX,
                        RuntimeCertificationTestFixtures.fingerprint('1'),
                        fixtures.components(), missing, fixtures.now,
                        fixtures.now.plusSeconds(60), "sre:mirror-runtime")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every runtime certification scenario");

        assertThatThrownBy(() -> new RuntimeCertificationManifest.ScenarioRequirement(
                RuntimeCertificationManifest.Scenario.NETWORK_PARTITION,
                300, 120, java.util.List.of("EVENTUAL_RECOVERY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot remove mandatory invariants");
    }

    @Test
    void productionCannotReceiveExecutableAuthorization() {
        assertThatThrownBy(() -> new RuntimeCertificationExecutionAuthorization(
                "", RuntimeCertificationTestFixtures.fingerprint('1'), "auth:production", 1,
                fixtures.manifest.artifactRef(), fixtures.scope,
                RuntimeCertificationManifest.EnvironmentClass.PRODUCTION,
                fixtures.manifest.environmentFingerprint(), fixtures.deployment,
                RuntimeCertificationTestFixtures.scenarios(), true, true, true,
                RuntimeCertificationTestFixtures.fingerprint('2'), fixtures.now,
                fixtures.now, fixtures.now.plusSeconds(60), "change-authority:production",
                java.util.List.of(RuntimeCertificationTestFixtures.ref(
                        "CHANGE_APPROVAL", "change", 1, '3')),
                fixtures.authorization.authorizationSeal()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("production runtime certification execution is forbidden");
    }

    @Test
    void rejectsAuthorizationBoundToAnotherEnvironment() {
        RuntimeCertificationExecutionAuthorization drifted =
                fixtures.integrity.sealAuthorization(
                        new RuntimeCertificationIntegrity.AuthorizationMaterial(
                                "runtime-authorization:drifted", 1,
                                fixtures.manifest.artifactRef(), fixtures.scope,
                                fixtures.manifest.environmentClass(),
                                RuntimeCertificationTestFixtures.fingerprint('4'),
                                fixtures.deployment, RuntimeCertificationTestFixtures.scenarios(),
                                true, true, true,
                                RuntimeCertificationTestFixtures.fingerprint('5'),
                                fixtures.now.minusSeconds(1), fixtures.now,
                                fixtures.now.plusSeconds(60), "change-authority:runtime",
                                java.util.List.of(RuntimeCertificationTestFixtures.ref(
                                        "CHANGE_APPROVAL", "change", 1, '6'))),
                        fixtures.authorizationSigner);

        assertThat(fixtures.integrity.verifyAuthorization(fixtures.manifest, drifted,
                fixtures.authorizationSigner, fixtures.now).outcome())
                .isEqualTo(RuntimeCertificationIntegrity.Outcome.IDENTITY_MISMATCH);
    }

    @Test
    void detectsReportAddressTampering() {
        RuntimeCertificationReport source = fixtures.report;
        RuntimeCertificationReport tampered = new RuntimeCertificationReport(
                source.schemaVersion(), source.reportFingerprint(), "runtime-report:tampered",
                source.revision(), source.manifestRef(), source.authorizationRef(),
                source.authorizationConsumptionRef(), source.regionalDataPlaneCertificationRef(),
                source.scope(), source.region(), source.deployment(), source.environmentClass(),
                source.environmentFingerprint(), source.adapter(), source.observedComponents(),
                source.startedAt(), source.completedAt(), source.scenarioResults(), source.verdict(),
                source.externalBusinessWriteAttemptCount(), source.writeEscapeCount(),
                source.issuer(), source.proofRefs(), source.reportSeal());

        assertThat(fixtures.integrity.canonicalReportVerified(tampered)).isFalse();
        assertThat(verify(tampered).outcome())
                .isEqualTo(RuntimeCertificationIntegrity.Outcome.INVALID);
    }

    @Test
    void blockedScenarioRemainsVisibleAndCannotCertify() {
        RuntimeCertificationReport blocked = fixtures.report(fixtures.results(
                RuntimeCertificationManifest.Scenario.BACKUP_RESTORE));

        assertThat(blocked.verdict()).isEqualTo(RuntimeCertificationReport.Verdict.BLOCKED);
        assertThat(blocked.scenarioResults()).hasSize(
                RuntimeCertificationManifest.Scenario.values().length);
        assertThat(verify(blocked).outcome())
                .isEqualTo(RuntimeCertificationIntegrity.Outcome.NOT_CERTIFIED);
    }

    private RuntimeCertificationIntegrity.VerificationResult verify(
            RuntimeCertificationReport report) {
        return fixtures.integrity.verifyReport(fixtures.manifest, fixtures.authorization, report,
                fixtures.regional.certification.artifactRef(), fixtures.authorizationSigner,
                fixtures.reportSigner);
    }
}
