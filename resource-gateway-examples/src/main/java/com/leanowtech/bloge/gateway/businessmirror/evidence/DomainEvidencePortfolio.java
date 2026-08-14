package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded domain portfolio that keeps each Package/layer/dimension independent. */
public record DomainEvidencePortfolio(
        String schemaVersion,
        String portfolioFingerprint,
        CapabilitySnapshot.Scope scope,
        String domainId,
        List<PackageView> packages,
        String nextCursor,
        Instant generatedAt
) {
    public static final String SCHEMA_VERSION = "resourceGateway.domainEvidencePortfolio.v1";
    private static final int MAXIMUM_CANONICAL_BYTES = 16 * 1024 * 1024;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects hidden aggregate scoring and non-canonical pagination. */
    public DomainEvidencePortfolio {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        portfolioFingerprint = portfolioFingerprint == null ? "" : portfolioFingerprint.trim();
        scope = Objects.requireNonNull(scope, "scope");
        domainId = required(domainId, "domainId");
        packages = packages == null ? List.of() : List.copyOf(packages);
        nextCursor = nextCursor == null ? "" : nextCursor.trim();
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        List<String> ids = packages.stream().map(PackageView::packageId).toList();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !portfolioFingerprint.isBlank()
                && !FINGERPRINT.matcher(portfolioFingerprint).matches()
                || packages.size() > 200
                || !ids.equals(ids.stream().sorted().distinct().toList())) {
            throw new IllegalArgumentException("domain evidence portfolio is non-canonical");
        }
    }

    public DomainEvidencePortfolio seal(ObjectMapper mapper) {
        if (!portfolioFingerprint.isBlank()) {
            verify(mapper);
            return this;
        }
        return withFingerprint(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"), withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES));
    }

    public void verify(ObjectMapper mapper) {
        if (portfolioFingerprint.isBlank()
                || !portfolioFingerprint.equals(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"), withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES))) {
            throw new IllegalArgumentException("Domain evidence portfolio fingerprint mismatch");
        }
    }

    public DomainEvidencePortfolio withFingerprint(String value) {
        return new DomainEvidencePortfolio(schemaVersion, value, scope, domainId,
                packages, nextCursor, generatedAt);
    }

    /** One current Package projection with no total grade. */
    public record PackageView(
            String packageId,
            long compilationRevision,
            long projectionRevision,
            String evidenceIndexFingerprint,
            String problemCode,
            Freshness freshness,
            List<LayerView> layers,
            PackageEvidenceIndex.FidelityView fidelity,
            List<EvidenceOwnerTask> ownerTasks,
            String deepLink
    ) {
        public PackageView {
            packageId = required(packageId, "packageId");
            evidenceIndexFingerprint = fingerprint(evidenceIndexFingerprint);
            problemCode = required(problemCode, "problemCode");
            freshness = Objects.requireNonNull(freshness, "freshness");
            layers = layers == null ? List.of() : List.copyOf(layers);
            fidelity = Objects.requireNonNull(fidelity, "fidelity");
            ownerTasks = ownerTasks == null ? List.of() : List.copyOf(ownerTasks);
            deepLink = required(deepLink, "deepLink");
            if (compilationRevision < 1 || projectionRevision < 1
                    || layers.size() != PackageEvidenceIndex.EvidenceLayer.Layer.values().length
                    || !layers.stream().map(LayerView::layer).toList()
                    .equals(List.of(PackageEvidenceIndex.EvidenceLayer.Layer.values()))) {
                throw new IllegalArgumentException("portfolio Package view is inconsistent");
            }
        }
    }

    /** Per-layer counts; proof classes remain separate and no total pass is derived. */
    public record LayerView(
            PackageEvidenceIndex.EvidenceLayer.Layer layer,
            int conclusionCount,
            List<StateCount> states,
            List<ProofCount> proofComposition
    ) {
        public LayerView {
            layer = Objects.requireNonNull(layer, "layer");
            states = states == null ? List.of() : List.copyOf(states);
            proofComposition = proofComposition == null ? List.of() : List.copyOf(proofComposition);
            if (conclusionCount < 0
                    || states.stream().mapToInt(StateCount::count).sum() != conclusionCount
                    || proofComposition.stream().mapToInt(ProofCount::count).sum()
                    != conclusionCount) {
                throw new IllegalArgumentException("portfolio layer arithmetic is invalid");
            }
        }
    }

    public record StateCount(PackageEvidenceIndex.ConclusionState state, int count) {
        public StateCount {
            state = Objects.requireNonNull(state, "state");
            if (count < 1) {
                throw new IllegalArgumentException("state count must be positive");
            }
        }
    }

    public record ProofCount(PackageEvidenceIndex.ProofStrength proof, int count) {
        public ProofCount {
            proof = Objects.requireNonNull(proof, "proof");
            if (count < 1) {
                throw new IllegalArgumentException("proof count must be positive");
            }
        }
    }

    public enum Freshness {
        CURRENT,
        STALE
    }

    /** Creates a deterministic Package summary while preserving the full Fidelity view. */
    public static PackageView summarize(
            PackageEvidenceIndex index,
            List<EvidenceOwnerTask> tasks,
            Instant now,
            String deepLink) {
        PackageEvidenceIndex exact = Objects.requireNonNull(index, "index");
        List<LayerView> layers = exact.layers().stream().map(layer -> new LayerView(
                layer.layer(), layer.conclusions().size(),
                counts(layer.conclusions().stream().map(
                        PackageEvidenceIndex.EvidenceConclusion::state).toList()),
                proofCounts(layer.conclusions().stream().map(
                        PackageEvidenceIndex.EvidenceConclusion::proofStrength).toList())))
                .toList();
        return new PackageView(exact.packageId(), exact.compilationRevision(),
                exact.projectionRevision(), exact.indexFingerprint(), exact.problemCode(),
                exact.validUntil().isAfter(Objects.requireNonNull(now, "now"))
                        ? Freshness.CURRENT : Freshness.STALE,
                layers, exact.fidelity(), tasks, deepLink);
    }

    private static List<StateCount> counts(List<PackageEvidenceIndex.ConclusionState> values) {
        return values.stream().distinct().sorted(Comparator.comparing(Enum::name))
                .map(value -> new StateCount(value,
                        (int) values.stream().filter(item -> item == value).count()))
                .toList();
    }

    private static List<ProofCount> proofCounts(List<PackageEvidenceIndex.ProofStrength> values) {
        return values.stream().distinct().sorted(Comparator.comparing(Enum::name))
                .map(value -> new ProofCount(value,
                        (int) values.stream().filter(item -> item == value).count()))
                .toList();
    }

    private static String fingerprint(String value) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException("fingerprint must be canonical SHA-256");
        }
        return exact;
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 1024) {
            throw new IllegalArgumentException(field + " must not be blank or oversized");
        }
        return exact;
    }
}
