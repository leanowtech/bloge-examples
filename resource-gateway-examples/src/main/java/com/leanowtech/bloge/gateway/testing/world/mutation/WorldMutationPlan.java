package com.leanowtech.bloge.gateway.testing.world.mutation;

import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Payload-free, content-addressed mutation inventory for one exact World slice. */
public final class WorldMutationPlan {
    public static final String SCHEMA_VERSION = "bloge.worldMutationPlan.v1";
    public static final String PLANNER_VERSION = "1.0.0-S3-E";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> GAP_CODES = Set.of(
            "NO_SUPPORTED_MUTATION_SITE", "MUTANT_LIMIT_REACHED", "MUTANT_COMPILATION_REJECTED",
            "MUTANT_DUPLICATE_REJECTED", "BASELINE_COMPILATION_REJECTED", "SLICE_DRIFT");

    public enum MutationKind {
        RULE_DELETED,
        DECISION_CONDITION_REVERSED,
        BOUNDARY_VALUE_REPLACED,
        RESULT_CHANGED,
        STATE_WRITE_DROPPED,
        DEFAULT_RULE_PRIORITY_CHANGED
    }

    public record Policy(int maxMutants, boolean requireAllKinds) {
        public Policy {
            if (maxMutants < 1 || maxMutants > 512) throw new IllegalArgumentException("maxMutants out of range");
        }
        public static Policy defaults() { return new Policy(128, false); }
    }

    /** A source coordinate only; the plan never embeds executable DSL. */
    public record Site(MutationKind kind, String astPath, int line, int column) {
        public Site {
            kind = Objects.requireNonNull(kind, "kind");
            astPath = required(astPath);
            if (line < 1 || column < 1) throw new IllegalArgumentException("source coordinate is invalid");
        }
    }

    public record PlannedMutant(
            String mutantId,
            MutationKind kind,
            Site site,
            String baselineFragmentFingerprint,
            String mutantSourceFingerprint,
            String mutantGraphFingerprint,
            String mutantTargetFingerprint,
            String contentFingerprint
    ) {
        public PlannedMutant {
            mutantId = required(mutantId);
            kind = Objects.requireNonNull(kind, "kind");
            site = Objects.requireNonNull(site, "site");
            baselineFragmentFingerprint = fingerprint(baselineFragmentFingerprint);
            mutantSourceFingerprint = fingerprint(mutantSourceFingerprint);
            mutantGraphFingerprint = fingerprint(mutantGraphFingerprint);
            mutantTargetFingerprint = fingerprint(mutantTargetFingerprint);
            contentFingerprint = fingerprint(contentFingerprint);
        }
    }

    public record PlanningGap(MutationKind kind, String code, String astPath) {
        public PlanningGap {
            kind = Objects.requireNonNull(kind, "kind");
            code = required(code);
            astPath = required(astPath);
            if (!GAP_CODES.contains(code)) throw new IllegalArgumentException("unknown planning gap");
        }
    }

    private final String tenantId;
    private final String worldModelId;
    private final long worldRevision;
    private final String worldFingerprint;
    private final String sliceFingerprint;
    private final String fragmentArtifactId;
    private final long fragmentRevision;
    private final String baselineFragmentFingerprint;
    private final String baselineGraphFingerprint;
    private final String plannerVersion;
    private final Policy policy;
    private final List<PlannedMutant> mutants;
    private final List<PlanningGap> gaps;
    private final String planFingerprint;

    public WorldMutationPlan(String tenantId, String worldModelId, long worldRevision,
                             String worldFingerprint, String sliceFingerprint,
                             String fragmentArtifactId, long fragmentRevision,
                             String baselineFragmentFingerprint, String baselineGraphFingerprint,
                             String plannerVersion, Policy policy, List<PlannedMutant> mutants,
                             List<PlanningGap> gaps) {
        this.tenantId = required(tenantId);
        this.worldModelId = required(worldModelId);
        this.worldRevision = positive(worldRevision);
        this.worldFingerprint = fingerprint(worldFingerprint);
        this.sliceFingerprint = fingerprint(sliceFingerprint);
        this.fragmentArtifactId = required(fragmentArtifactId);
        this.fragmentRevision = positive(fragmentRevision);
        this.baselineFragmentFingerprint = fingerprint(baselineFragmentFingerprint);
        this.baselineGraphFingerprint = fingerprint(baselineGraphFingerprint);
        this.plannerVersion = required(plannerVersion);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.mutants = ordered(mutants);
        this.gaps = orderedGaps(gaps);
        if (this.mutants.size() > policy.maxMutants()) throw new IllegalArgumentException("mutant limit exceeded");
        if (this.mutants.stream().map(PlannedMutant::mutantId).distinct().count() != this.mutants.size()) {
            throw new IllegalArgumentException("duplicate mutant id");
        }
        for (PlannedMutant mutant : this.mutants) {
            if (!baselineFragmentFingerprint.equals(mutant.baselineFragmentFingerprint())
                    || mutant.kind() != mutant.site().kind()
                    || !targetFingerprintFor(worldFingerprint, sliceFingerprint,
                    mutant.mutantGraphFingerprint()).equals(mutant.mutantTargetFingerprint())
                    || !expectedContent(mutant, baselineGraphFingerprint).equals(mutant.contentFingerprint())) {
                throw new IllegalArgumentException("mutant content address is inconsistent");
            }
        }
        this.planFingerprint = VisualBundleFingerprint.fromMaterial(material());
    }

    public static WorldMutationPlan empty(WorldSlice slice, String baselineGraphFingerprint, Policy policy,
                                          List<PlanningGap> gaps) {
        Objects.requireNonNull(slice, "slice");
        return new WorldMutationPlan(slice.tenantId(), "slice:" + slice.logicalContractId(), 1,
                slice.fingerprint(), slice.fingerprint(), slice.behavior().artifactId(),
                slice.behavior().revision(), slice.behavior().fingerprint(), baselineGraphFingerprint,
                PLANNER_VERSION, policy, List.of(), gaps);
    }

    public String tenantId() { return tenantId; }
    public String worldModelId() { return worldModelId; }
    public long worldRevision() { return worldRevision; }
    public String worldFingerprint() { return worldFingerprint; }
    public String sliceFingerprint() { return sliceFingerprint; }
    public String fragmentArtifactId() { return fragmentArtifactId; }
    public long fragmentRevision() { return fragmentRevision; }
    public String baselineFragmentFingerprint() { return baselineFragmentFingerprint; }
    public String baselineGraphFingerprint() { return baselineGraphFingerprint; }
    public String plannerVersion() { return plannerVersion; }
    public Policy policy() { return policy; }
    public List<PlannedMutant> mutants() { return mutants; }
    public List<PlanningGap> gaps() { return gaps; }
    public String planFingerprint() { return planFingerprint; }
    public boolean hasStableGap() { return !gaps.isEmpty(); }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorldMutationPlan plan && planFingerprint.equals(plan.planFingerprint);
    }

    @Override
    public int hashCode() {
        return planFingerprint.hashCode();
    }

    @Override
    public String toString() {
        return "WorldMutationPlan[tenantId=" + tenantId + ", worldModelId=" + worldModelId
                + ", worldRevision=" + worldRevision + ", planFingerprint=" + planFingerprint
                + ", mutantCount=" + mutants.size() + ", gapCount=" + gaps.size() + "]";
    }

    public WorldMutationPlan verifyAgainst(WorldSlice slice) {
        if (slice == null || !tenantId.equals(slice.tenantId()) || !sliceFingerprint.equals(slice.fingerprint())
                || !fragmentArtifactId.equals(slice.behavior().artifactId())
                || fragmentRevision != slice.behavior().revision()
                || !baselineFragmentFingerprint.equals(slice.behavior().fingerprint())) {
            throw new IllegalArgumentException("World mutation plan is stale or crosses tenant/slice scope");
        }
        return this;
    }

    private Map<String, Object> material() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("tenantId", tenantId);
        material.put("worldModelId", worldModelId);
        material.put("worldRevision", worldRevision);
        material.put("worldFingerprint", worldFingerprint);
        material.put("sliceFingerprint", sliceFingerprint);
        material.put("fragmentArtifactId", fragmentArtifactId);
        material.put("fragmentRevision", fragmentRevision);
        material.put("baselineFragmentFingerprint", baselineFragmentFingerprint);
        material.put("baselineGraphFingerprint", baselineGraphFingerprint);
        material.put("plannerVersion", plannerVersion);
        material.put("policy", policy);
        material.put("mutants", mutants);
        material.put("gaps", gaps);
        return material;
    }

    private static List<PlannedMutant> ordered(List<PlannedMutant> values) {
        if (values == null) throw new IllegalArgumentException("mutants are required");
        return values.stream().sorted(Comparator.comparing(PlannedMutant::mutantId)).toList();
    }
    private static List<PlanningGap> orderedGaps(List<PlanningGap> values) {
        if (values == null) throw new IllegalArgumentException("gaps are required");
        return values.stream().sorted(Comparator.comparing((PlanningGap gap) -> gap.kind().ordinal())
                .thenComparing(PlanningGap::astPath).thenComparing(PlanningGap::code)).toList();
    }
    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 512) throw new IllegalArgumentException("required text missing");
        return value.trim();
    }
    private static String fingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) throw new IllegalArgumentException("invalid fingerprint");
        return value;
    }
    private static long positive(long value) { if (value < 1) throw new IllegalArgumentException("positive value required"); return value; }

    private static String expectedContent(PlannedMutant mutant, String baselineGraphFingerprint) {
        return ProtocolFingerprint.ofText(baselineGraphFingerprint + "\n" + mutant.kind()
                + "\n" + mutant.site().astPath() + "\n" + mutant.mutantSourceFingerprint()
                + "\n" + mutant.mutantGraphFingerprint());
    }

    public static String targetFingerprintFor(String worldFingerprint, String sliceFingerprint,
                                              String mutantGraphFingerprint) {
        return ProtocolFingerprint.ofText(worldFingerprint + "\n" + sliceFingerprint
                + "\n" + mutantGraphFingerprint);
    }
}
