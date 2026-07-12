package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadRedactionManifest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Deterministic, sanitized workbook seed for ANEKE correctness governance. */
public record CorrectnessWorkbookBundle(
        String schemaVersion,
        Target target,
        String dependencySnapshotFingerprint,
        List<Suite> suites,
        List<EvidenceRef> evidence,
        VisualPayloadRedactionManifest redaction,
        Manifest manifest
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.correctnessWorkbookBundle.v1";

    public CorrectnessWorkbookBundle {
        schemaVersion = normalize(schemaVersion, SCHEMA_VERSION);
        target = target == null ? Target.empty() : target;
        dependencySnapshotFingerprint = normalize(dependencySnapshotFingerprint, "");
        suites = suites == null ? List.of() : List.copyOf(suites);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        redaction = redaction == null ? VisualPayloadRedactionManifest.empty() : redaction;
        manifest = manifest == null
                ? Manifest.from(target, dependencySnapshotFingerprint, suites, evidence, redaction)
                : manifest;
    }

    public boolean fingerprintVerified() {
        return manifest.bundleFingerprint().equals(Manifest.fingerprint(
                target, dependencySnapshotFingerprint, suites, evidence, redaction));
    }

    public record Target(String kind, String draftId, long revision, String draftFingerprint) {
        public Target {
            kind = normalize(kind, "GRAPH_DRAFT");
            draftId = normalize(draftId, "");
            revision = Math.max(0, revision);
            draftFingerprint = normalize(draftFingerprint, "");
        }

        static Target empty() {
            return new Target("", "", 0, "");
        }
    }

    public record Suite(String suiteId,
                        long revision,
                        String suiteFingerprint,
                        String operatorRef,
                        List<String> nodeIds,
                        List<Case> cases) {
        public Suite {
            suiteId = normalize(suiteId, "");
            revision = Math.max(0, revision);
            suiteFingerprint = normalize(suiteFingerprint, "");
            operatorRef = normalize(operatorRef, "");
            nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    public record Case(String caseId,
                       String caseKind,
                       String mappingStatus,
                       String name,
                       String description,
                       Map<String, Object> inputs,
                       Map<String, Object> config,
                       Map<String, Object> mockedOutputs,
                       List<Assertion> assertions) {
        public Case {
            caseId = normalize(caseId, "");
            caseKind = normalize(caseKind, "REGRESSION");
            mappingStatus = normalize(mappingStatus, "DEFAULTED");
            name = normalize(name, caseId);
            description = normalize(description, "");
            inputs = copy(inputs);
            config = copy(config);
            mockedOutputs = copy(mockedOutputs);
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
        }
    }

    public record Assertion(String assertionId,
                            String assertionKind,
                            String scope,
                            String port,
                            String path,
                            String operator,
                            Object expected) {
        public Assertion {
            assertionId = normalize(assertionId, "");
            assertionKind = normalize(assertionKind, "PATH");
            scope = normalize(scope, "OPERATOR_OUTPUT");
            port = normalize(port, "");
            path = normalize(path, "");
            operator = normalize(operator, "EQUALS");
        }
    }

    public record EvidenceRef(String runId,
                              String evidenceSchemaVersion,
                              String evidenceFingerprint,
                              String signatureStatus,
                              String caseType,
                              Instant createdAt,
                              String endpoint) {
        public EvidenceRef {
            runId = normalize(runId, "");
            evidenceSchemaVersion = normalize(evidenceSchemaVersion, "");
            evidenceFingerprint = normalize(evidenceFingerprint, "");
            signatureStatus = normalize(signatureStatus, "UNVERIFIED");
            caseType = normalize(caseType, "UNASSIGNED");
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
            endpoint = normalize(endpoint, "");
        }
    }

    public record Manifest(String schemaVersion,
                           String bundleFingerprint,
                           int suiteCount,
                           int caseCount,
                           int assertionCount,
                           int evidenceCount,
                           boolean complete) {
        public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.correctnessWorkbookManifest.v1";

        public Manifest {
            schemaVersion = normalize(schemaVersion, SCHEMA_VERSION);
            bundleFingerprint = normalize(bundleFingerprint, "");
            suiteCount = Math.max(0, suiteCount);
            caseCount = Math.max(0, caseCount);
            assertionCount = Math.max(0, assertionCount);
            evidenceCount = Math.max(0, evidenceCount);
        }

        static Manifest from(Target target,
                             String dependencySnapshotFingerprint,
                             List<Suite> suites,
                             List<EvidenceRef> evidence,
                             VisualPayloadRedactionManifest redaction) {
            List<Suite> safeSuites = suites == null ? List.of() : suites;
            List<EvidenceRef> safeEvidence = evidence == null ? List.of() : evidence;
            int cases = safeSuites.stream().mapToInt(suite -> suite.cases().size()).sum();
            int assertions = safeSuites.stream().flatMap(suite -> suite.cases().stream())
                    .mapToInt(row -> row.assertions().size()).sum();
            return new Manifest("", fingerprint(target, dependencySnapshotFingerprint, safeSuites,
                    safeEvidence, redaction), safeSuites.size(), cases, assertions, safeEvidence.size(),
                    !target.draftFingerprint().isBlank() && !dependencySnapshotFingerprint.isBlank());
        }

        static String fingerprint(Target target,
                                  String dependencySnapshotFingerprint,
                                  List<Suite> suites,
                                  List<EvidenceRef> evidence,
                                  VisualPayloadRedactionManifest redaction) {
            LinkedHashMap<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", CorrectnessWorkbookBundle.SCHEMA_VERSION);
            material.put("target", target);
            material.put("dependencySnapshotFingerprint", dependencySnapshotFingerprint);
            material.put("suites", suites == null ? List.of() : suites);
            material.put("evidence", evidence == null ? List.of() : evidence);
            material.put("redaction", redaction == null ? VisualPayloadRedactionManifest.empty() : redaction);
            return VisualBundleFingerprint.fromMaterial(material);
        }
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null || source.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
