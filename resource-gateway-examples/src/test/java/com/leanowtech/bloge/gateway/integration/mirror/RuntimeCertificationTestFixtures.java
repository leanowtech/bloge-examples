package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RuntimeCertificationTestFixtures {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final RuntimeCertificationIntegrity integrity = new RuntimeCertificationIntegrity(mapper);
    final InMemoryVisualEvidenceSigner authorizationSigner =
            new InMemoryVisualEvidenceSigner();
    final InMemoryVisualEvidenceSigner reportSigner = new InMemoryVisualEvidenceSigner();
    final RegionalDataPlaneCertificationTestFixtures regional =
            new RegionalDataPlaneCertificationTestFixtures();
    final CapabilitySnapshot.Scope scope = regional.scope;
    final MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment =
            regional.deployment;
    final Instant now = Instant.now();
    final RuntimeCertificationManifest manifest = integrity.addressManifest(
            new RuntimeCertificationIntegrity.ManifestMaterial(
                    "runtime-certification:sg", 3, scope, "ap-southeast-1", deployment,
                    RuntimeCertificationManifest.EnvironmentClass.SANDBOX,
                    fingerprint('a'), components(), requirements(), now.minusSeconds(60),
                    now.plusSeconds(86_400), "sre:mirror-runtime"));
    final RuntimeCertificationExecutionAuthorization authorization =
            integrity.sealAuthorization(
                    new RuntimeCertificationIntegrity.AuthorizationMaterial(
                            "runtime-authorization:sg:3", 7, manifest.artifactRef(), scope,
                            manifest.environmentClass(), manifest.environmentFingerprint(),
                            deployment, scenarios(), true, true, true, fingerprint('b'),
                            now.minusSeconds(10), now.minusSeconds(5), now.plusSeconds(900),
                            "change-authority:runtime-certification",
                            List.of(ref("CHANGE_APPROVAL", "chg-103", 4, 'c'))),
                    authorizationSigner);
    final RuntimeCertificationReport.AdapterDescriptor adapter =
            new RuntimeCertificationReport.AdapterDescriptor("", "adapter:chaos-lab",
                    fingerprint('d'), "customer-platform:sandbox", manifest.environmentClass(),
                    manifest.environmentFingerprint(), scenarios(), true, true, true, true, true);
    final RuntimeCertificationReport report = report(results(null));

    RuntimeCertificationReport report(
            List<RuntimeCertificationReport.ScenarioResult> results) {
        long writeAttempts = results.stream().mapToLong(
                RuntimeCertificationReport.ScenarioResult
                        ::externalBusinessWriteAttemptCount).sum();
        long writeEscapes = results.stream().mapToLong(
                RuntimeCertificationReport.ScenarioResult::writeEscapeCount).sum();
        RuntimeCertificationReport.Verdict verdict = results.stream().allMatch(
                value -> value.status() == RuntimeCertificationReport.ScenarioStatus.PASSED)
                ? RuntimeCertificationReport.Verdict.CERTIFIED
                : results.stream().anyMatch(value -> value.status()
                == RuntimeCertificationReport.ScenarioStatus.FAILED)
                ? RuntimeCertificationReport.Verdict.FAILED
                : RuntimeCertificationReport.Verdict.BLOCKED;
        return integrity.sealReport(new RuntimeCertificationIntegrity.ReportMaterial(
                "runtime-report:sg:3", 5, manifest.artifactRef(), authorization.artifactRef(),
                ref("RUNTIME_CERTIFICATION_AUTHORIZATION_CONSUMPTION",
                        "runtime-authorization:sg:3", 1, 'e'),
                regional.certification.artifactRef(), scope, manifest.region(), deployment,
                manifest.environmentClass(), manifest.environmentFingerprint(), adapter,
                components(), now, now.plusSeconds(120), results, verdict, writeAttempts,
                writeEscapes, "runtime-certification-authority:sg",
                List.of(ref("RUNTIME_CERTIFICATION_EVIDENCE_INDEX", "index:sg", 5, 'f'))),
                reportSigner);
    }

    List<RuntimeCertificationReport.ScenarioResult> results(
            RuntimeCertificationManifest.Scenario blocked) {
        List<RuntimeCertificationReport.ScenarioResult> values = new ArrayList<>();
        for (int index = 0; index < manifest.scenarios().size(); index++) {
            int scenarioIndex = index;
            RuntimeCertificationManifest.ScenarioRequirement requirement =
                    manifest.scenarios().get(index);
            RuntimeCertificationReport.ScenarioStatus status =
                    requirement.scenario() == blocked
                            ? RuntimeCertificationReport.ScenarioStatus.BLOCKED
                            : RuntimeCertificationReport.ScenarioStatus.PASSED;
            List<RuntimeCertificationReport.InvariantObservation> observations =
                    requirement.requiredInvariantCodes().stream()
                            .map(code -> new RuntimeCertificationReport.InvariantObservation(
                                    code, status
                                    == RuntimeCertificationReport.ScenarioStatus.PASSED
                                    ? RuntimeCertificationReport.InvariantStatus.PASSED
                                    : RuntimeCertificationReport.InvariantStatus.NOT_OBSERVED,
                                    List.of(ref("RUNTIME_INVARIANT_PROOF",
                                            requirement.scenario().name().toLowerCase()
                                                    + ":" + code.toLowerCase(),
                                            1, digit(scenarioIndex)))))
                            .toList();
            values.add(new RuntimeCertificationReport.ScenarioResult(
                    requirement.scenario(), "attempt:" + index, status,
                    now.plusSeconds(index * 5L), now.plusSeconds(index * 5L + 4),
                    status == RuntimeCertificationReport.ScenarioStatus.PASSED,
                    status == RuntimeCertificationReport.ScenarioStatus.PASSED,
                    0, 0, fingerprint(digit(index + 1)), fingerprint(digit(index + 2)),
                    observations, List.of(ref("RUNTIME_SCENARIO_PROOF",
                    requirement.scenario().name().toLowerCase(), 1, digit(index + 3))),
                    status == RuntimeCertificationReport.ScenarioStatus.PASSED
                            ? "PASSED" : "ADAPTER_DEPENDENCY_UNAVAILABLE"));
        }
        return List.copyOf(values);
    }

    List<RuntimeCertificationManifest.ComponentCoordinate> components() {
        return Arrays.stream(RuntimeCertificationManifest.ComponentKind.values())
                .map(kind -> new RuntimeCertificationManifest.ComponentCoordinate(kind,
                        "component:" + kind.name().toLowerCase(),
                        switch (kind) {
                            case RESOURCE_GATEWAY -> "1.0.0";
                            case BLOGE_ENGINE -> "0.8.9-RC2";
                            case DATABASE -> "postgresql-16.4";
                            case JVM -> "openjdk-25.0.3";
                        }, fingerprint(digit(kind.ordinal() + 1))))
                .toList();
    }

    List<RuntimeCertificationManifest.ScenarioRequirement> requirements() {
        return Arrays.stream(RuntimeCertificationManifest.Scenario.values())
                .map(value -> new RuntimeCertificationManifest.ScenarioRequirement(
                        value, 300, 120, value.mandatoryInvariantCodes()))
                .toList();
    }

    static List<RuntimeCertificationManifest.Scenario> scenarios() {
        return List.of(RuntimeCertificationManifest.Scenario.values());
    }

    static MirrorArtifactRef ref(String kind, String id, long revision, char value) {
        return new MirrorArtifactRef(kind, id, revision, fingerprint(value));
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static char digit(int value) {
        return Character.forDigit(Math.floorMod(value, 10), 10);
    }
}
