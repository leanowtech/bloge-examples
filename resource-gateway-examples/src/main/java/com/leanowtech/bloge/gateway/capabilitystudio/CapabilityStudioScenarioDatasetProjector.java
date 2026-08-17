package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Projects the verified golden pack into the payload-free Scenario Dataset v1 contract.
 *
 * <p>This is an immutable business projection, not a new execution authority. It deliberately
 * carries no fixture, mock, replay, request, or response material. A later compiler adapter will
 * lower this projection into {@code ScenarioDraftSetV2}; this class does not implement that
 * runtime boundary.</p>
 */
public final class CapabilityStudioScenarioDatasetProjector {
    private static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.scenario-dataset.v1";
    private static final String DATASET_ID = "dataset-cancellation-fee-golden-v1";
    private static final String DATASET_AUTHORITY = "capability-studio-stage0";
    private static final String PACK_AUTHORITY = "capability-studio-demo-pack";
    private static final Scope SCOPE = new Scope(
            "demo-tenant", "customer-service", "capability-studio", "test", "local");
    private static final ObjectMapper FINGERPRINT_JSON = new ObjectMapper();

    private final ObjectMapper mapper;
    private final ScenarioDatasetProjection projection;

    /** Builds and freezes one projection from one already validated golden pack. */
    public CapabilityStudioScenarioDatasetProjector(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.projection = projectPack(Objects.requireNonNull(pack, "pack"));
    }

    /** Convenience constructor retained for the existing browser fixture composition. */
    public CapabilityStudioScenarioDatasetProjector(CapabilityStudioGoldenDemoPack pack) {
        this(pack, new ObjectMapper());
    }

    /** Returns the immutable golden-pack projection. */
    public ScenarioDatasetProjection project() {
        return projection;
    }

    private ScenarioDatasetProjection projectPack(CapabilityStudioGoldenDemoPack pack) {
        CapabilityStudioGoldenDemoPack.Capability target = pack.toolCapabilities().stream()
                .sorted(Comparator.comparing(CapabilityStudioGoldenDemoPack.Capability::id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Golden pack has no Tool target"));

        List<DataCase> cases = pack.scenarios().stream()
                .sorted(Comparator.comparing(CapabilityStudioGoldenDemoPack.TestScenario::id))
                .map(this::dataCase)
                .toList();
        List<ExactRef> contractRefs = contractRefs(pack);
        Quality quality = quality(cases);
        ScenarioDatasetProjection material = new ScenarioDatasetProjection(
                SCHEMA_VERSION,
                datasetRef(pack, null),
                target.name(),
                target.description(),
                "REVIEW_READY",
                "INTERNAL",
                owner(pack.owner()),
                exactRef(target.ref()),
                contractRefs,
                cases,
                quality);
        return new ScenarioDatasetProjection(
                material.schemaVersion(),
                withFingerprint(material.datasetRef(), fingerprint(material, "datasetRef")),
                material.name(),
                material.description(),
                material.lifecycle(),
                material.classification(),
                material.owner(),
                material.targetRef(),
                material.contractRefs(),
                material.cases(),
                material.quality());
    }

    private DataCase dataCase(CapabilityStudioGoldenDemoPack.TestScenario scenario) {
        List<BehaviorProfile> profiles = behaviorProfiles(scenario);
        DataCase material = new DataCase(
                caseRef(scenario, null),
                scenario.name(),
                scenario.expectedResult(),
                scenario.category(),
                caseLifecycle(scenario.lifecycle()),
                scenario.qualityState(),
                owner(scenario.owner()),
                exactRef(scenario.sourceRef()),
                source(scenario.source()),
                exactRef(scenario.oracleRef()),
                oracle(scenario.oracle()),
                scenario.applicableContractRefs().stream()
                        .map(this::exactRef)
                        .sorted(REF_ORDER)
                        .toList(),
                profiles);
        return new DataCase(
                withFingerprint(material.caseRef(), fingerprint(material, "caseRef")),
                material.name(),
                material.businessIntent(),
                material.category(),
                material.lifecycle(),
                material.qualityState(),
                material.owner(),
                material.sourceRef(),
                material.source(),
                material.oracleRef(),
                material.oracle(),
                material.applicableContractRefs(),
                material.behaviorProfiles());
    }

    private List<BehaviorProfile> behaviorProfiles(
            CapabilityStudioGoldenDemoPack.TestScenario scenario) {
        Map<String, Integer> occurrences = new HashMap<>();
        return scenario.dependencyBehaviors().stream()
                .sorted(Comparator.comparing((CapabilityStudioGoldenDemoPack.DependencyBehavior value)
                                -> value.dependencyRef().kind())
                        .thenComparing(value -> value.dependencyRef().id())
                        .thenComparing(CapabilityStudioGoldenDemoPack.DependencyBehavior::behavior)
                        .thenComparing(CapabilityStudioGoldenDemoPack.DependencyBehavior::summary))
                .map(behavior -> {
                    CapabilityStudioGoldenDemoPack.ExactRef dependency = behavior.dependencyRef();
                    String baseId = "behavior-profile-" + scenario.id() + "-"
                            + dependency.kind().toLowerCase() + "-" + dependency.id();
                    int occurrence = occurrences.merge(baseId, 1, Integer::sum);
                    String id = occurrence == 1 ? baseId : baseId + "-" + occurrence;
                    BehaviorProfile material = new BehaviorProfile(
                            new ExactRef("BEHAVIOR_PROFILE", id, scenario.ref().revision(), null,
                                    PACK_AUTHORITY, SCOPE),
                            exactRef(dependency),
                            behaviorKind(behavior.behavior()),
                            behavior.summary());
                    return new BehaviorProfile(
                            withFingerprint(material.behaviorRef(), fingerprint(material, "behaviorRef")),
                            material.dependencyRef(),
                            material.behavior(),
                            material.summary());
                })
                .toList();
    }

    private List<ExactRef> contractRefs(CapabilityStudioGoldenDemoPack pack) {
        Map<String, ExactRef> unique = new LinkedHashMap<>();
        pack.apiCapabilities().forEach(capability -> addContract(unique, capability.contractRef()));
        pack.featureCapabilities().forEach(capability -> addContract(unique, capability.contractRef()));
        pack.toolCapabilities().forEach(capability -> addContract(unique, capability.contractRef()));
        pack.scenarios().forEach(scenario -> {
            addContract(unique, scenario.contractRef());
            scenario.applicableContractRefs().forEach(ref -> addContract(unique, ref));
        });
        return unique.values().stream().sorted(REF_ORDER).toList();
    }

    private void addContract(
            Map<String, ExactRef> unique,
            CapabilityStudioGoldenDemoPack.ExactRef ref) {
        ExactRef projected = exactRef(ref);
        unique.putIfAbsent(refIdentity(projected), projected);
    }

    private Quality quality(List<DataCase> cases) {
        int total = cases.size();
        int active = (int) cases.stream().filter(value -> "ACTIVE".equals(value.lifecycle())).count();
        int stale = (int) cases.stream().filter(value -> "STALE".equals(value.lifecycle())).count();
        return new Quality(
                "BLOCKED",
                total,
                active,
                stale,
                coverage(cases, DataCase::owner),
                coverage(cases, DataCase::sourceRef),
                coverage(cases, DataCase::oracleRef),
                coverage(cases, value -> value.applicableContractRefs().isEmpty() ? null : value),
                coverage(cases, value -> value.behaviorProfiles().isEmpty() ? null : value));
    }

    private static int coverage(List<DataCase> cases, java.util.function.Function<DataCase, Object> value) {
        if (cases.isEmpty()) {
            return 0;
        }
        long covered = cases.stream().filter(item -> value.apply(item) != null).count();
        return (int) Math.round(covered * 100.0 / cases.size());
    }

    private ExactRef datasetRef(CapabilityStudioGoldenDemoPack pack, String fingerprint) {
        return new ExactRef("DATASET", DATASET_ID, pack.revision(), fingerprint,
                DATASET_AUTHORITY, SCOPE);
    }

    private ExactRef caseRef(
            CapabilityStudioGoldenDemoPack.TestScenario scenario,
            String fingerprint) {
        return new ExactRef("DATA_CASE", scenario.ref().id(), scenario.ref().revision(), fingerprint,
                PACK_AUTHORITY, SCOPE);
    }

    private ExactRef exactRef(CapabilityStudioGoldenDemoPack.ExactRef ref) {
        if (ref == null) {
            return null;
        }
        return new ExactRef(ref.kind(), ref.id(), ref.revision(), ref.fingerprint(), PACK_AUTHORITY, SCOPE);
    }

    private static ExactRef withFingerprint(ExactRef ref, String fingerprint) {
        return new ExactRef(ref.kind(), ref.id(), ref.revision(), fingerprint, ref.authority(), ref.scope());
    }

    private Owner owner(CapabilityStudioGoldenDemoPack.Owner value) {
        return value == null ? null : new Owner(value.id(), value.name());
    }

    private Source source(CapabilityStudioGoldenDemoPack.SourceSummary value) {
        return value == null ? null : new Source(value.displayName(), value.type());
    }

    private Oracle oracle(CapabilityStudioGoldenDemoPack.OracleSummary value) {
        return value == null ? null : new Oracle(value.displayName(), value.summary());
    }

    private static String behaviorKind(String value) {
        return switch (value) {
            case "RETURN", "RETURN_EMPTY", "RETURN_VERSIONED", "IDEMPOTENT" -> "RETURN";
            case "MUST_NOT_CALL", "MUST_NOT_CALL_WRITE" -> "MUST_NOT_CALL";
            case "ERROR", "DELAY", "TIMEOUT", "REPLAY", "OBSERVE" -> value;
            default -> throw new IllegalArgumentException("Unsupported golden dependency behavior: " + value);
        };
    }

    private static String caseLifecycle(String value) {
        // Golden pack scenarios are authored facts, not runtime-admitted cases.
        return "ACTIVE".equals(value) ? "DRAFT" : value;
    }

    private String fingerprint(Object value, String refField) {
        try {
            ObjectNode material = mapper.valueToTree(value);
            ((ObjectNode) material.path(refField)).putNull("fingerprint");
            byte[] canonical = FINGERPRINT_JSON.writeValueAsBytes(canonical(material));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder result = new StringBuilder("sha256:");
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException | ClassCastException failure) {
            throw new IllegalStateException("Unable to fingerprint Capability Studio projection", failure);
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = FINGERPRINT_JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = FINGERPRINT_JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private static String refIdentity(ExactRef ref) {
        return ref.kind() + "|" + ref.id() + "|" + ref.revision() + "|" + ref.fingerprint();
    }

    private static final Comparator<ExactRef> REF_ORDER = Comparator
            .comparing(ExactRef::kind)
            .thenComparing(ExactRef::id)
            .thenComparingInt(ExactRef::revision)
            .thenComparing(ExactRef::fingerprint);

    public record ScenarioDatasetProjection(
            String schemaVersion,
            ExactRef datasetRef,
            String name,
            String description,
            String lifecycle,
            String classification,
            Owner owner,
            ExactRef targetRef,
            List<ExactRef> contractRefs,
            List<DataCase> cases,
            Quality quality) {
        public ScenarioDatasetProjection {
            contractRefs = List.copyOf(contractRefs);
            cases = List.copyOf(cases);
        }
    }

    public record ExactRef(
            String kind,
            String id,
            int revision,
            String fingerprint,
            String authority,
            Scope scope) {
    }

    public record Scope(
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String region) {
    }

    public record Owner(String id, String name) {
    }

    public record Source(String displayName, String type) {
    }

    public record Oracle(String displayName, String summary) {
    }

    public record DataCase(
            ExactRef caseRef,
            String name,
            String businessIntent,
            String category,
            String lifecycle,
            String qualityState,
            Owner owner,
            ExactRef sourceRef,
            Source source,
            ExactRef oracleRef,
            Oracle oracle,
            List<ExactRef> applicableContractRefs,
            List<BehaviorProfile> behaviorProfiles) {
        public DataCase {
            applicableContractRefs = List.copyOf(applicableContractRefs);
            behaviorProfiles = List.copyOf(behaviorProfiles);
        }
    }

    public record BehaviorProfile(
            ExactRef behaviorRef,
            ExactRef dependencyRef,
            String behavior,
            String summary) {
    }

    public record Quality(
            String status,
            int totalCaseCount,
            int activeCaseCount,
            int staleCaseCount,
            int ownerCoveragePercent,
            int sourceCoveragePercent,
            int oracleCoveragePercent,
            int contractCoveragePercent,
            int behaviorClosurePercent) {
    }
}
