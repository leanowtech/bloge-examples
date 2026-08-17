package com.leanowtech.bloge.gateway.testing.correctness.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.CorrectnessAuthoringRuntimeAvailability;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Components;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CasePage;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CaseSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CommandAvailability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CommandPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CoverageSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.FixtureCatalogSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.FixtureSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.OracleAssertionSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.PublicationSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.ReviewSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.RunSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceQuery;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceCandidateContributor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explicit, read-only Correctness Studio sample for local test and staging demonstrations.
 *
 * <p>The sample still uses the production workspace query, exact coordinates, authenticated
 * enterprise scope, capability probe, and payload-free projection. It does not install command,
 * publication, or run services and therefore cannot be mistaken for an executable runtime.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.correctness.demo",
        name = "enabled",
        havingValue = "true")
public class CorrectnessDemoRuntimeConfiguration {

    public static final String TARGET_ID = "loan-decision-with-fallback";
    public static final String TARGET_FINGERPRINT = "sha256:" + "a".repeat(64);
    public static final String DEFINITION_ID = "loan-correctness-demo";

    private static final Instant CREATED_AT = Instant.parse("2026-08-15T08:00:00Z");
    private static final PrincipalRef OWNER = new PrincipalRef(
            "credit-service-design", PrincipalKind.TEAM, "Credit Service Design");
    private static final PrincipalRef REVIEWER = new PrincipalRef(
            "risk-policy-owner", PrincipalKind.USER, "Risk Policy Owner");
    private static final EnterpriseScope SCOPE = new EnterpriseScope(
            "tenant-a", "knowledge-governance", "tool-studio", "test", "local");
    private static final ExactTargetRef TARGET = new ExactTargetRef(
            TargetKind.GRAPH, TARGET_ID, 7, TARGET_FINGERPRINT);
    private static final ExactAssetRef INVENTORY_REF = asset(
            "COVERAGE_INVENTORY", "loan-policy-obligations", 4, 'b');
    private static final ExactAssetRef SCENARIO_REF = asset(
            "SCENARIO_DRAFT_SET", "loan-policy-regression", 6, 'd');
    private static final ExactAssetRef PUBLICATION_REF = asset(
            "CORRECTNESS_PUBLICATION", "loan-policy-publication", 1, 'e');

    @Bean
    @ConditionalOnMissingBean
    CorrectnessDefinitionRepository correctnessDemoDefinitionRepository(ObjectMapper mapper) {
        CorrectnessDefinition definition = new CorrectnessDefinition(
                "", DEFINITION_ID, 3, SCOPE, TARGET,
                "Loan decision correctness",
                "Eligible applicants receive a policy-compliant decision while dependency faults "
                        + "degrade safely to secondary credit or manual review.",
                List.of(
                        "No ineligible applicant is automatically approved",
                        "Primary credit timeout follows the reviewed fallback policy",
                        "A double dependency failure produces manual review without write effects",
                        "Every publishable result binds the current policy and frozen denominator"),
                RiskLevel.CRITICAL,
                OWNER,
                List.of(new ExactBasisRef(
                        "POLICY", "credit-decision-policy", 5, fingerprint('c'))),
                null,
                INVENTORY_REF,
                CorrectnessDefinition.DefinitionLifecycle.ACTIVE,
                new ReviewRecord(
                        ReviewStatus.APPROVED, REVIEWER,
                        Instant.parse("2026-08-15T09:10:00Z"),
                        "Approved for the isolated correctness demonstration."),
                new AuditMetadata(CREATED_AT, CREATED_AT, OWNER, OWNER));
        return new ReadOnlyDefinitionRepository(StoredCorrectnessDefinition.verified(
                mapper, definition));
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessWorkspaceComponentSource correctnessDemoWorkspaceComponentSource() {
        return (coordinate, page) -> {
            if (!SCOPE.equals(coordinate.scope())
                    || !TARGET.equals(coordinate.target())
                    || !INVENTORY_REF.equals(coordinate.activeInventoryRef())) {
                return unavailable(page.queryFingerprint());
            }
            List<CaseSummary> allCases = cases();
            int start = cursorOffset(page.cursor(), allCases.size());
            int end = Math.min(allCases.size(), start + page.limit());
            String nextCursor = end < allCases.size() ? "case:" + end : "";
            return new Components(
                    new CoverageSummary(
                            Availability.AVAILABLE, INVENTORY_REF, "FROZEN", 9, 7, 0, 2),
                    new OracleAssertionSummary(
                            Availability.AVAILABLE, 6, 1, 5, 0, 8, 1, 7, 0, 0),
                    new CasePage(
                            Availability.AVAILABLE, SCENARIO_REF, allCases.size(),
                            allCases.subList(start, end), nextCursor, page.queryFingerprint()),
                    fixtures(),
                    new ReviewSummary(2, 19, 0, 0),
                    new PublicationSummary(
                            PUBLICATION_REF, "COMMITTED",
                            Instant.parse("2026-08-15T09:24:00Z")),
                    new RunSummary(
                            "run-loan-regression-018",
                            Instant.parse("2026-08-15T09:31:42Z"),
                            "SUCCESS", "PASSED",
                            asset("CORRECTNESS_EVIDENCE", "run-loan-regression-018", 1, 'f')),
                    verdict(),
                    List.of(),
                    List.of(
                            "CORRECTNESS_CASE_SUMMARY_V1",
                            "CORRECTNESS_COVERAGE_SUMMARY_V1",
                            "CORRECTNESS_FIXTURE_SUMMARY_V1",
                            "CORRECTNESS_ORACLE_ASSERTION_SUMMARY_V1"),
                    new CommandPolicy(Map.of(
                            "RUN", new CommandAvailability(false, "DEMO_READ_ONLY"),
                            "PUBLISH", new CommandAvailability(false, "DEMO_READ_ONLY"))));
        };
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessWorkspaceQuery correctnessDemoWorkspaceQuery(
            CorrectnessDefinitionRepository definitions,
            CorrectnessWorkspaceComponentSource components,
            ObjectMapper mapper
    ) {
        return new CorrectnessWorkspaceQuery(definitions, components, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrectnessAuthoringRuntimeAvailability correctnessDemoRuntimeAvailability() {
        return new CorrectnessAuthoringRuntimeAvailability(
                true, false, false, false, false, false,
                false, false, false, false, false, false, false);
    }

    @Bean
    @ConditionalOnMissingBean(name = "correctnessDemoReferenceCandidateContributor")
    ReferenceCandidateContributor correctnessDemoReferenceCandidateContributor() {
        return new LoanDecisionReferenceCandidateContributor();
    }

    private static List<CaseSummary> cases() {
        return List.of(
                testCase("prime-auto-approve", '1', "Prime applicant auto approval",
                        "Proves the reviewed high-credit happy path.", "GOLDEN", RiskLevel.HIGH,
                        1, 1, 1, 1, "APPROVED", List.of("golden", "policy")),
                testCase("risk-reject", '2', "Ineligible applicant rejection",
                        "Rejects a low-credit application without invoking fallback.", "NEGATIVE",
                        RiskLevel.CRITICAL, 1, 1, 1, 1, "APPROVED", List.of("negative", "risk")),
                testCase("score-680-boundary", '3', "Score 680 lower boundary",
                        "Pins the lower approval threshold exactly.", "BOUNDARY", RiskLevel.HIGH,
                        1, 1, 1, 1, "APPROVED", List.of("boundary", "680")),
                testCase("score-720-boundary", '4', "Score 720 upper boundary",
                        "Pins the premium policy threshold exactly.", "BOUNDARY", RiskLevel.HIGH,
                        1, 1, 1, 1, "APPROVED", List.of("boundary", "720")),
                testCase("primary-timeout-fallback", '5', "Primary credit timeout fallback",
                        "Routes to secondary credit after a bounded primary timeout.", "REGRESSION",
                        RiskLevel.CRITICAL, 1, 1, 1, 2, "APPROVED", List.of("fallback", "timeout")),
                testCase("double-timeout-manual", '6', "Double timeout manual review",
                        "Degrades to manual review when both credit providers time out.", "REGRESSION",
                        RiskLevel.CRITICAL, 1, 1, 1, 2, "APPROVED", List.of("partial", "manual")),
                testCase("missing-applicant-data", '7', "Missing applicant information",
                        "Produces a stable validation error before resource calls.", "NEGATIVE",
                        RiskLevel.HIGH, 1, 1, 1, 0, "APPROVED", List.of("data-quality")),
                testCase("forbidden-write-effect", '8', "Decision graph forbids write effects",
                        "Proves the decision-only workflow never invokes a write binding.", "SECURITY",
                        RiskLevel.CRITICAL, 1, 1, 1, 2, "PENDING", List.of("effect", "governance")));
    }

    private static CaseSummary testCase(
            String id,
            char fingerprintSeed,
            String name,
            String intent,
            String type,
            RiskLevel risk,
            int obligationCount,
            int oracleCount,
            int assertionCount,
            int dependencyCount,
            String review,
            List<String> tags
    ) {
        return new CaseSummary(
                SCENARIO_REF, id, fingerprint(fingerprintSeed), name, intent, type, risk,
                OWNER, "CANONICAL", obligationCount, oracleCount, assertionCount,
                dependencyCount, review, tags);
    }

    private static FixtureCatalogSummary fixtures() {
        return new FixtureCatalogSummary(
                Availability.AVAILABLE, 5, 5, 0,
                List.of(
                        fixture("applicant-profile-prime", "Prime applicant", "prime", '1', 'a'),
                        fixture("applicant-profile-risk", "Risk applicant", "risk", '2', 'b'),
                        fixture("primary-credit-timeout", "Primary credit", "timeout", '3', 'c'),
                        fixture("secondary-credit-success", "Secondary credit", "success", '4', 'd'),
                        fixture("logical-time-policy-edge", "Logical time", "policy-edge", '5', 'e')));
    }

    private static FixtureSummary fixture(
            String id,
            String name,
            String variant,
            char descriptorSeed,
            char materialSeed
    ) {
        return new FixtureSummary(
                asset("FIXTURE_ASSET", id, 2, descriptorSeed),
                name, variant, "ACTIVE", "INTERNAL",
                new ExactSchemaRef(id + "-schema", 3, fingerprint(descriptorSeed)),
                fingerprint(materialSeed), 2);
    }

    private static CorrectnessVerdict verdict() {
        return new CorrectnessVerdict(
                CorrectnessVerdict.ExecutionVerdict.SUCCESS,
                CorrectnessVerdict.AssertionVerdict.PASSED,
                CorrectnessVerdict.CoverageVerdict.INCOMPLETE,
                CorrectnessVerdict.EvidenceVerdict.CURRENT,
                CorrectnessVerdict.GateVerdict.REVIEW,
                CorrectnessVerdict.ProofLevel.SIMULATED_BUSINESS,
                List.of(new CorrectnessVerdict.Reason(
                        "COVERAGE_GAPS_REMAIN", "COVERAGE", "correctness.coverage.incomplete")),
                List.of(new CorrectnessVerdict.Remediation(
                        "CREATE_CASE_FROM_GAP", "COVERAGE_GAPS_REMAIN")));
    }

    private static Components unavailable(String queryFingerprint) {
        return new Components(
                CoverageSummary.unavailable(), OracleAssertionSummary.unavailable(),
                new CasePage(
                        Availability.UNAVAILABLE, null, 0, List.of(), "", queryFingerprint),
                FixtureCatalogSummary.unavailable(), ReviewSummary.empty(), null, null,
                new CorrectnessVerdict(
                        CorrectnessVerdict.ExecutionVerdict.NOT_RUN,
                        CorrectnessVerdict.AssertionVerdict.NOT_EVALUATED,
                        CorrectnessVerdict.CoverageVerdict.NOT_EVALUATED,
                        CorrectnessVerdict.EvidenceVerdict.NONE,
                        CorrectnessVerdict.GateVerdict.BLOCKED,
                        CorrectnessVerdict.ProofLevel.STRUCTURAL,
                        List.of(new CorrectnessVerdict.Reason(
                                "DEMO_COORDINATE_MISMATCH", "GATE", "correctness.demo.coordinate")),
                        List.of()),
                List.of(), List.of(), CommandPolicy.readOnly());
    }

    private static int cursorOffset(String cursor, int size) {
        if (cursor == null || cursor.isBlank()) return 0;
        if (!cursor.matches("case:[0-9]+")) {
            throw new IllegalArgumentException("Demo Case cursor is invalid");
        }
        int value = Integer.parseInt(cursor.substring("case:".length()));
        if (value < 0 || value > size) {
            throw new IllegalArgumentException("Demo Case cursor is out of range");
        }
        return value;
    }

    private static ExactAssetRef asset(String kind, String id, long revision, char seed) {
        return new ExactAssetRef(kind, id, revision, fingerprint(seed));
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static final class ReadOnlyDefinitionRepository
            implements CorrectnessDefinitionRepository {
        private final StoredCorrectnessDefinition stored;

        private ReadOnlyDefinitionRepository(StoredCorrectnessDefinition stored) {
            this.stored = stored;
        }

        @Override
        public boolean supportsHeadListing() {
            return true;
        }

        @Override
        public List<StoredCorrectnessDefinition> listHeads(EnterpriseScope scope, int limit) {
            return matchesScope(scope) && limit > 0 ? List.of(stored) : List.of();
        }

        @Override
        public Optional<StoredCorrectnessDefinition> findHead(
                EnterpriseScope scope,
                String definitionId
        ) {
            return matchesScope(scope) && stored.definition().definitionId().equals(definitionId)
                    ? Optional.of(stored) : Optional.empty();
        }

        @Override
        public List<StoredCorrectnessDefinition> findHeadCandidatesByTarget(
                EnterpriseScope scope,
                TargetKind targetKind,
                String targetId,
                String targetFingerprint
        ) {
            return matchesScope(scope)
                    && stored.definition().target().kind() == targetKind
                    && stored.definition().target().id().equals(targetId)
                    && stored.definition().target().fingerprint().equals(targetFingerprint)
                    ? List.of(stored) : List.of();
        }

        @Override
        public Optional<StoredCorrectnessDefinition> findRevision(
                EnterpriseScope scope,
                String definitionId,
                long revision
        ) {
            return findHead(scope, definitionId)
                    .filter(value -> value.definition().revision() == revision);
        }

        @Override
        public List<StoredCorrectnessDefinition> revisions(
                EnterpriseScope scope,
                String definitionId
        ) {
            return findHead(scope, definitionId).stream().toList();
        }

        @Override
        public Optional<StoredCorrectnessDefinition> saveIfRevision(
                long expectedRevision,
                CorrectnessDefinition candidate,
                PrincipalRef actor
        ) {
            return Optional.empty();
        }

        private boolean matchesScope(EnterpriseScope scope) {
            return stored.definition().scope().equals(scope);
        }
    }
}
