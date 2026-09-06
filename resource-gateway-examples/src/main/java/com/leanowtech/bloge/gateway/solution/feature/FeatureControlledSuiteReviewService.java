package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.journey.BusinessFixtureIndexService;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenReviewAuditRepository;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenReviewService;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenReviewAuditRepository.BusinessGoldenReviewAccess;

/**
 * Human-only review boundary for protected Feature controlled suites.
 *
 * <p>Discovery returns aggregate state only. Material reads first prove that the Feature belongs to
 * the requested frozen Solution closure, then enforce HUMAN reviewer authority and receipt
 * clearance before decrypting the suite. Every accepted or denied request appends a separate,
 * payload-free human audit record; an unavailable audit fails the request closed.</p>
 */
@Service
@ConditionalOnBean({BusinessFixtureIndexService.class, BusinessGoldenReviewAuditRepository.class})
public final class FeatureControlledSuiteReviewService {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final AgentTddStateRepository states;
    private final BusinessFixtureIndexService solutionIndex;
    private final FeatureControlledMaterialStore materials;
    private final BusinessGoldenReviewAuditRepository audits;
    private final ObjectMapper mapper;

    /** Creates the human projection over suite metadata, encrypted material and review audit. */
    public FeatureControlledSuiteReviewService(
            AgentTddStateRepository states,
            BusinessFixtureIndexService solutionIndex,
            FeatureControlledMaterialStore materials,
            BusinessGoldenReviewAuditRepository audits,
            ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.solutionIndex = Objects.requireNonNull(solutionIndex, "solutionIndex");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.audits = Objects.requireNonNull(audits, "audits");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Lists payload-free suite summaries for every Feature in one current Solution closure. */
    public List<SuiteReviewSummary> listForSolution(
            String solutionRef, IntegrationRequestContext identity) {
        requireComplete(identity);
        String solution = required(solutionRef, "solutionRef");
        try {
            requireReviewer(identity);
            String scope = AgentTddMutationService.scopeKey(identity);
            List<SuiteReviewSummary> result = new ArrayList<>();
            for (String featureRef : featureRefs(solution, identity)) {
                states.find(scope, FeatureControlledSuiteService.FEATURE_CONTROLLED_SUITE, featureRef)
                        .map(stored -> summary(stored, identity))
                        .ifPresent(result::add);
            }
            audit(identity, solution, "*", "FEATURE_SUITE_LIST", "ACCEPTED");
            return List.copyOf(result);
        } catch (AgentTddToolException failure) {
            auditDenied(identity, solution, "*", "FEATURE_SUITE_LIST", failure);
            throw failure;
        }
    }

    /** Returns one protected suite as review-only case material after exact Solution membership. */
    public SuiteMaterialView readMaterial(
            String solutionRef, String featureRef, IntegrationRequestContext identity) {
        requireComplete(identity);
        String solution = required(solutionRef, "solutionRef");
        String feature = required(featureRef, "featureRef");
        try {
            requireReviewer(identity);
            if (!featureRefs(solution, identity).contains(feature)) {
                throw new AgentTddToolException(
                        "REFERENCE_UNRESOLVED", "Feature suite is not part of the requested Solution.");
            }
            AgentTddStoredAsset suite = states.find(
                            AgentTddMutationService.scopeKey(identity),
                            FeatureControlledSuiteService.FEATURE_CONTROLLED_SUITE, feature)
                    .orElseThrow(() -> new AgentTddToolException(
                            "REFERENCE_UNRESOLVED", "Feature controlled suite is unavailable."));
            requireClearance(suite.data().path("materialReceipt"), identity);
            FeatureControlledSuiteDefinition definition = materials.read(
                    suite.data().path("materialReceipt"), identity);
            String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                    mapper, mapper.convertValue(definition.protectedMaterial(), Object.class), MAX_BYTES);
            if (!feature.equals(definition.featureRef())
                    || !fingerprint.equals(suite.data().path("definitionFingerprint").asText())) {
                throw new AgentTddToolException(
                        "FIXTURE_MATERIAL_UNAVAILABLE", "Protected Feature suite does not match its metadata.");
            }
            SuiteMaterialView result = new SuiteMaterialView(
                    feature, definition.evaluationRef(), definition.cases());
            audit(identity, feature, "*", "FEATURE_SUITE_MATERIAL_REVIEW", "ACCEPTED");
            return result;
        } catch (AgentTddToolException failure) {
            auditDenied(identity, feature, "*", "FEATURE_SUITE_MATERIAL_REVIEW", failure);
            throw failure;
        }
    }

    private List<String> featureRefs(String solutionRef, IntegrationRequestContext identity) {
        try {
            return solutionIndex.listForSolution(solutionRef, identity).stream()
                    .filter(group -> "FEATURE".equals(group.capabilityKind()))
                    .map(BusinessFixtureIndexService.CapabilityFixtures::capabilityRef)
                    .distinct().sorted().toList();
        } catch (RuntimeException unavailable) {
            if (unavailable instanceof AgentTddToolException known) throw known;
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "Solution Feature closure is unavailable.");
        }
    }

    private static SuiteReviewSummary summary(
            AgentTddStoredAsset stored, IntegrationRequestContext identity) {
        JsonNode data = stored.data();
        return new SuiteReviewSummary(stored.assetRef(), stored.revision(), data.path("status").asText(),
                data.path("caseCount").asInt(), data.path("coverageTargetCount").asInt(),
                data.path("evidenceFingerprint").asText(),
                canRead(data.path("materialReceipt"), identity));
    }

    private static void requireComplete(IntegrationRequestContext identity) {
        if (identity == null) throw new AgentTddToolException(
                "FEATURE_SUITE_REVIEW_AUTH_REQUIRED", "Authenticated human review identity is required.");
        identity.requireComplete();
    }

    private static void requireReviewer(IntegrationRequestContext identity) {
        boolean reviewer = "HUMAN".equals(identity.actorType())
                && identity.groups().stream().anyMatch(
                BusinessGoldenReviewService.REVIEWER_GROUP::equalsIgnoreCase);
        if (!reviewer) throw new AgentTddToolException(
                "FEATURE_SUITE_REVIEW_ROLE_FORBIDDEN", "An authorized human reviewer is required.");
        if (!IntegrationOperation.SOLUTION_GOLDEN_REVIEW.accepts(identity.purpose())) {
            throw new AgentTddToolException(
                    "FEATURE_SUITE_REVIEW_PURPOSE_FORBIDDEN", "The protected review purpose is required.");
        }
    }

    private static boolean canRead(JsonNode receipt, IntegrationRequestContext identity) {
        return receipt.isObject()
                && identity.hasClearanceAtLeast(receipt.path("classification").asText());
    }

    private static void requireClearance(JsonNode receipt, IntegrationRequestContext identity) {
        if (!canRead(receipt, identity)) throw new AgentTddToolException(
                "FEATURE_SUITE_REVIEW_CLEARANCE_FORBIDDEN",
                "The human clearance is insufficient for this protected Feature suite.");
    }

    private void auditDenied(IntegrationRequestContext identity, String assetRef, String caseId,
                             String action, AgentTddToolException failure) {
        if (!"GOLDEN_REVIEW_AUDIT_UNAVAILABLE".equals(failure.code())) {
            audit(identity, assetRef, caseId, action, failure.code());
        }
    }

    private void audit(IntegrationRequestContext identity, String assetRef, String caseId,
                       String action, String outcome) {
        try {
            audits.append(new BusinessGoldenReviewAccess(
                    UUID.randomUUID().toString(), identity, assetRef, caseId, action,
                    outcome == null ? "DENIED" : outcome, null));
        } catch (RuntimeException unavailable) {
            AgentTddToolException failure = new AgentTddToolException(
                    "GOLDEN_REVIEW_AUDIT_UNAVAILABLE",
                    "Protected business asset review is unavailable because audit could not commit.",
                    Map.of(), true);
            failure.initCause(unavailable);
            throw failure;
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new AgentTddToolException(
                "SCHEMA_NONCONFORMANT", field + " is required.");
        return normalized;
    }

    /** Payload-free human discovery row for one protected Feature suite. */
    public record SuiteReviewSummary(String featureRef, long revision, String status,
                                     int caseCount, int coverageTargetCount,
                                     String evidenceFingerprint, boolean materialViewable) { }

    /** Authorized review projection. Receipt and vault coordinates never cross this boundary. */
    public record SuiteMaterialView(String featureRef, String evaluationRef,
                                    List<FeatureControlledSuiteDefinition.Case> cases) {
        /** Freezes case material for response serialization. */
        public SuiteMaterialView {
            featureRef = required(featureRef, "featureRef");
            evaluationRef = required(evaluationRef, "evaluationRef");
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }
}
