package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadRedactionManifest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadSanitizer;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestCase;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorTestAssertion;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Projects immutable authoring/test/run facts into the ANEKE workbook protocol. */
@Service
public class CorrectnessWorkbookProjectionService {
    private static final Set<String> CASE_KINDS = Set.of("GOLDEN", "NEGATIVE", "BOUNDARY", "REGRESSION");

    private final VisualOperatorContractTestSuiteRepository suites;
    private final VisualGraphRunRepository runs;

    public CorrectnessWorkbookProjectionService(VisualOperatorContractTestSuiteRepository suites,
                                                VisualGraphRunRepository runs) {
        this.suites = suites;
        this.runs = runs;
    }

    CorrectnessWorkbookBundle project(GraphDraft draft,
                                      String draftFingerprint,
                                      GraphDraftDependencySnapshotService.Snapshot snapshot) {
        RedactionAccumulator redaction = new RedactionAccumulator();
        List<CorrectnessWorkbookBundle.Suite> projectedSuites = projectSuites(draft, snapshot, redaction);
        List<CorrectnessWorkbookBundle.EvidenceRef> evidence = projectEvidence(draft, draftFingerprint);
        CorrectnessWorkbookBundle.Target target = new CorrectnessWorkbookBundle.Target(
                "GRAPH_DRAFT", draft.draftId(), draft.revision(), draftFingerprint);
        return new CorrectnessWorkbookBundle("", target, snapshot.fingerprint(), projectedSuites, evidence,
                redaction.manifest(), null);
    }

    private List<CorrectnessWorkbookBundle.Suite> projectSuites(
            GraphDraft draft,
            GraphDraftDependencySnapshotService.Snapshot snapshot,
            RedactionAccumulator redaction) {
        if (suites == null) return List.of();
        Map<String, CorrectnessWorkbookBundle.Suite> projected = new LinkedHashMap<>();
        snapshot.assets().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(asset -> {
            String operatorRef = asset.getKey();
            List<String> nodeIds = draft.nodes().stream().filter(node -> operatorRef.equals(node.operatorRef()))
                    .map(node -> node.id()).sorted().toList();
            for (GraphDraftDependencyProfile.ContractSuiteRef ref : asset.getValue().contractSuites()) {
                VisualOperatorContractTestSuite suite = suites.findRevision(ref.suiteId(), ref.revision())
                        .orElseThrow(() -> new ProjectionException("CONTRACT_SUITE_REVISION_MISSING",
                                "Contract suite revision is no longer available: " + ref.suiteId()
                                        + "@" + ref.revision()));
                String actualFingerprint = VisualBundleFingerprint.fromMaterial(Map.of("suite", suite));
                if (!actualFingerprint.equals(ref.fingerprint())) {
                    throw new ProjectionException("CONTRACT_SUITE_FINGERPRINT_MISMATCH",
                            "Contract suite revision does not match the dependency snapshot: " + ref.suiteId());
                }
                projected.putIfAbsent(ref.suiteId() + "@" + ref.revision(), new CorrectnessWorkbookBundle.Suite(
                        ref.suiteId(), ref.revision(), ref.fingerprint(), operatorRef, nodeIds,
                        projectCases(suite, redaction)));
            }
        });
        return List.copyOf(projected.values());
    }

    private static List<CorrectnessWorkbookBundle.Case> projectCases(
            VisualOperatorContractTestSuite suite,
            RedactionAccumulator redaction) {
        List<CorrectnessWorkbookBundle.Case> cases = new ArrayList<>();
        List<VisualOperatorContractTestCase> source = suite.request().cases();
        for (int caseIndex = 0; caseIndex < source.size(); caseIndex++) {
            VisualOperatorContractTestCase row = source.get(caseIndex);
            CaseKind kind = caseKind(suite.tags());
            String caseId = stableId("case", suite.suiteId(), caseIndex, row.name());
            List<Object> expectedValues = row.outputAssertions().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .flatMap(entry -> entry.getValue().stream())
                    .map(VisualOperatorTestAssertion::expectedValue).toList();
            VisualPayloadSanitizer.Capture sanitized = VisualPayloadSanitizer.capture(row.inputs(), row.config(),
                    Map.of("mockedOutputs", row.mockedOutputs(), "expected", expectedValues));
            redaction.add(suite.suiteId(), caseId, sanitized.redaction());
            Map<String, Object> mockedOutputs = objectMap(sanitized.results().get("mockedOutputs"));
            List<?> sanitizedExpected = sanitized.results().get("expected") instanceof List<?> values
                    ? values : List.of();
            List<CorrectnessWorkbookBundle.Assertion> assertions = new ArrayList<>();
            int assertionIndex = 0;
            for (Map.Entry<String, List<VisualOperatorTestAssertion>> port : row.outputAssertions().entrySet()
                    .stream().sorted(Map.Entry.comparingByKey()).toList()) {
                for (VisualOperatorTestAssertion assertion : port.getValue()) {
                    Object expected = assertionIndex < sanitizedExpected.size()
                            ? sanitizedExpected.get(assertionIndex) : null;
                    assertions.add(assertion(caseId, assertionIndex++, port.getKey(), assertion, expected));
                }
            }
            cases.add(new CorrectnessWorkbookBundle.Case(caseId, kind.kind(), kind.mappingStatus(), row.name(),
                    row.description(), sanitized.context(), objectMap(sanitized.output()), mockedOutputs, assertions));
        }
        return List.copyOf(cases);
    }

    private List<CorrectnessWorkbookBundle.EvidenceRef> projectEvidence(GraphDraft draft, String draftFingerprint) {
        if (runs == null) return List.of();
        return runs.all().stream()
                .filter(run -> draft.tenantId().equals(run.tenantId())
                        && draft.environment().equals(run.environment())
                        && draft.draftId().equals(run.draftId())
                        && draft.revision() == run.draftRevision()
                        && draftFingerprint.equals(run.draftFingerprint()))
                .sorted(Comparator.comparing(VisualGraphRunRecord::createdAt).reversed()
                        .thenComparing(VisualGraphRunRecord::runId))
                .limit(100)
                .map(run -> evidenceRef(run, runs))
                .toList();
    }

    private static CorrectnessWorkbookBundle.EvidenceRef evidenceRef(
            VisualGraphRunRecord run,
            VisualGraphRunRepository repository) {
        boolean verified = repository.evidenceSigner()
                .verify(run.evidenceSeal(), run.evidenceMaterialFingerprint()).valid();
        String caseType = run.replay().replay() ? run.replay().caseType() : "UNASSIGNED";
        return new CorrectnessWorkbookBundle.EvidenceRef(run.runId(), RunEvidenceBundle.SCHEMA_VERSION,
                run.evidenceMaterialFingerprint(), verified ? "VERIFIED" : "UNVERIFIED", caseType,
                run.createdAt(), "/api/integration/runs/" + run.runId() + "/evidence");
    }

    private static CorrectnessWorkbookBundle.Assertion assertion(
            String caseId,
            int index,
            String port,
            VisualOperatorTestAssertion assertion,
            Object sanitizedExpected) {
        String mode = assertion.mode().name();
        String kind = mode.contains("SCHEMA") ? "SCHEMA" : "PATH";
        String operator = switch (assertion.mode()) {
            case OUTPUT_EQUALS, PATH_EQUALS -> "EQUALS";
            case OUTPUT_MATCHES_SCHEMA -> "MATCHES_SCHEMA";
            case PATH_EXISTS -> "EXISTS";
            case PATH_ABSENT -> "ABSENT";
        };
        return new CorrectnessWorkbookBundle.Assertion(
                stableId("assertion", caseId, index, port + ":" + mode), kind, "OPERATOR_OUTPUT",
                port, assertion.path(), operator, sanitizedExpected);
    }

    private static CaseKind caseKind(List<String> tags) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        (tags == null ? List.<String>of() : tags).stream().filter(tag -> tag != null)
                .map(tag -> tag.trim().toUpperCase(Locale.ROOT).replace("CASE-KIND:", ""))
                .forEach(normalized::add);
        String explicit = normalized.stream().filter(CASE_KINDS::contains).findFirst().orElse("");
        return explicit.isBlank() ? new CaseKind("REGRESSION", "DEFAULTED")
                : new CaseKind(explicit, "EXPLICIT");
    }

    private static String stableId(String prefix, Object... material) {
        return prefix + "-" + VisualBundleFingerprint.fromMaterial(Map.of("material", List.of(material)))
                .substring(7, 23);
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private record CaseKind(String kind, String mappingStatus) {}

    private static final class RedactionAccumulator {
        private final List<String> paths = new ArrayList<>();
        private boolean truncated;

        void add(String suiteId, String caseId, VisualPayloadRedactionManifest manifest) {
            manifest.redactedPaths().forEach(path -> paths.add("/suites/" + suiteId + "/cases/" + caseId + path));
            truncated |= manifest.truncated();
        }

        VisualPayloadRedactionManifest manifest() {
            return new VisualPayloadRedactionManifest("", paths.size(), truncated, paths);
        }
    }

    static final class ProjectionException extends RuntimeException {
        private final String code;

        ProjectionException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
