package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.solution.PublishedSolutionSnapshot;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.journey.BusinessJourneyService;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verifies that one GREEN Solution evidence line still names the executable closure that exists
 * now, rather than only the top-level Solution revision observed when the line was produced.
 *
 * <p>The verifier is shared by readiness, governed write execution and journey navigation. It
 * treats evidence as current only when the Solution, case set, compiler policy, controlled plan,
 * frozen Feature/Instruction contracts, complete Scenario/Instruction implementation identity,
 * and optional journey context all remain unchanged.</p>
 */
public final class SolutionEvidenceCurrentness {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private SolutionEvidenceCurrentness() { }

    /** Returns whether the evidence remains current against all authoritative stores. */
    public static boolean isCurrent(
            AgentTddStateRepository states,
            SolutionEntityRegistry registry,
            ObjectMapper mapper,
            String scope,
            String solutionRef,
            AgentTddStoredAsset evidence) {
        if (evidence == null || !"GREEN".equals(evidence.data().path("side").asText())
                || !evidence.data().path("businessBacklog").isArray()
                || !evidence.data().path("businessBacklog").isEmpty()
                || !SolutionTestingService.COMPILER_VERSION.equals(
                        evidence.data().path("compilerVersion").asText())
                || !SolutionTestingService.EGRESS_POLICY.equals(
                        evidence.data().path("egressPolicy").asText())
                || !scopeFingerprint(mapper, scope).equals(
                        evidence.data().path("scopeFingerprint").asText())) return false;
        SolutionEntityRegistry.RegisteredEntity registered;
        SolutionContract solution;
        try {
            registered = registry.requireRegisteredSolution(scope, solutionRef);
            solution = registry.requireSolution(scope, solutionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            return false;
        }
        PublishedSolutionSnapshot snapshot;
        try {
            snapshot = SolutionImplementationIdentity.snapshot(registry, scope, solution);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            return false;
        }
        return isCurrent(states, mapper, scope, solutionRef, evidence, registered, snapshot);
    }

    /**
     * Returns whether evidence names the supplied immutable executable closure.
     *
     * <p>This overload is used by governed effect boundaries that must validate, reserve and
     * execute one identical set of contracts. It deliberately performs no Solution, Scenario or
     * Instruction registry lookup; mutable-store coordinates unrelated to execution remain
     * checked through the state repository.</p>
     */
    public static boolean isCurrent(
            AgentTddStateRepository states,
            ObjectMapper mapper,
            String scope,
            String solutionRef,
            AgentTddStoredAsset evidence,
            SolutionEntityRegistry.RegisteredEntity registered,
            PublishedSolutionSnapshot snapshot) {
        if (evidence == null || registered == null || snapshot == null
                || !solutionRef.equals(snapshot.solution().solutionRef())
                || !"GREEN".equals(evidence.data().path("side").asText())
                || !evidence.data().path("businessBacklog").isArray()
                || !evidence.data().path("businessBacklog").isEmpty()
                || !SolutionTestingService.COMPILER_VERSION.equals(
                evidence.data().path("compilerVersion").asText())
                || !SolutionTestingService.EGRESS_POLICY.equals(
                evidence.data().path("egressPolicy").asText())
                || !scopeFingerprint(mapper, scope).equals(
                evidence.data().path("scopeFingerprint").asText())) return false;
        if (registered.revision() != evidence.data().path("solutionRevision").asLong(-1)
                || !registered.contractFingerprint().equals(
                        evidence.data().path("solutionContractFingerprint").asText())) return false;
        String implementation = SolutionImplementationIdentity.fingerprint(mapper, snapshot);
        if (!implementation.equals(evidence.data().path("implementationFingerprint").asText())) return false;
        if (!currentCaseSet(states, scope, evidence) || !currentContractVector(
                states, scope, evidence.data().path("frozenFeatureContracts"),
                SolutionEntityRegistry.FEATURE)
                || !currentContractVector(states, scope,
                evidence.data().path("frozenInstructionContracts"),
                SolutionEntityRegistry.INSTRUCTION)
                || !currentPlan(mapper, evidence.data())) return false;
        return currentJourneyContext(states, mapper, scope, evidence.data());
    }

    /**
     * Verifies the journey coordinate when evidence was produced through the business surface.
     * Evidence from the compatible lower-level surface has no journey coordinate and passes this
     * check only after the remaining executable coordinates have been verified by {@link
     * #isCurrent(AgentTddStateRepository, SolutionEntityRegistry, ObjectMapper, String, String,
     * AgentTddStoredAsset)}.
     */
    public static boolean currentJourneyContext(
            AgentTddStateRepository states, ObjectMapper mapper, String scope, JsonNode evidence) {
        String journeyRef = evidence.path("journeyRef").asText();
        String expected = evidence.path("solutionContextFingerprint").asText();
        if (journeyRef.isBlank() && expected.isBlank()) return true;
        if (journeyRef.isBlank() || expected.isBlank()) return false;
        return states.find(scope, BusinessJourneyService.JOURNEY, journeyRef)
                .map(journey -> expected.equals(journeyContextFingerprint(states, mapper, journey)))
                .orElse(false);
    }

    /** Computes the current four-entity context associated with one journey. */
    public static String journeyContextFingerprint(
            AgentTddStateRepository states, ObjectMapper mapper, AgentTddStoredAsset journey) {
        List<Map<String, Object>> vector = new ArrayList<>();
        for (JsonNode association : journey.data().path("associations")) {
            String kind = association.path("assetKind").asText();
            String storageKind = storageKind(kind);
            if (storageKind.isBlank()) continue;
            String ref = association.path("assetRef").asText();
            states.find(journey.scopeKey(), storageKind, ref).ifPresent(asset -> vector.add(Map.of(
                    "kind", kind, "ref", ref, "revision", asset.revision(),
                    "contractFingerprint", asset.data().path("contractFingerprint").asText(""))));
        }
        return VisualBundleFingerprint.fromCanonicalValue(mapper, vector, MAX_BYTES);
    }

    private static boolean currentCaseSet(
            AgentTddStateRepository states, String scope, AgentTddStoredAsset evidence) {
        String caseSetRef = evidence.data().path("caseSetRef").asText();
        return !caseSetRef.isBlank() && states.find(scope, AgentTddMutationService.CASE_SET, caseSetRef)
                .map(asset -> asset.revision() == evidence.data().path("caseSetRevision").asLong(-1))
                .orElse(false);
    }

    private static boolean currentContractVector(
            AgentTddStateRepository states, String scope, JsonNode vector, String kind) {
        if (!vector.isArray()) return false;
        for (JsonNode coordinate : vector) {
            String ref = coordinate.path("assetRef").asText();
            boolean current = !ref.isBlank() && states.find(scope, kind, ref)
                    .map(asset -> asset.revision() == coordinate.path("revision").asLong(-1)
                            && asset.data().path("contractFingerprint").asText()
                            .equals(coordinate.path("contractFingerprint").asText()))
                    .orElse(false);
            if (!current) return false;
        }
        return true;
    }

    private static boolean currentPlan(ObjectMapper mapper, JsonNode evidence) {
        JsonNode plans = evidence.path("controlledAssumptionPlanFingerprints");
        if (!plans.isArray() || !evidence.path("orderedGoldenCaseFingerprints").isArray()) return false;
        String plan = VisualBundleFingerprint.fromCanonicalValue(mapper, plans, MAX_BYTES);
        String golden = VisualBundleFingerprint.fromCanonicalValue(
                mapper, evidence.path("orderedGoldenCaseFingerprints"), MAX_BYTES);
        return plan.equals(evidence.path("planFingerprint").asText())
                && golden.equals(evidence.path("goldenSetId").asText());
    }

    private static String storageKind(String associationKind) {
        return switch (associationKind) {
            case "FEATURE" -> SolutionEntityRegistry.FEATURE;
            case "SCENARIO" -> SolutionEntityRegistry.SCENARIO;
            case "INSTRUCTION" -> SolutionEntityRegistry.INSTRUCTION;
            case "SOLUTION" -> SolutionEntityRegistry.SOLUTION;
            default -> "";
        };
    }

    private static String scopeFingerprint(ObjectMapper mapper, String scope) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, scope, MAX_BYTES);
    }
}
