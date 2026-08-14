package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic projection from immutable Package facts and the existing Fidelity kernel. */
public final class PackageEvidenceProjector {
    private static final Duration DEFAULT_VALIDITY = Duration.ofDays(30);

    private PackageEvidenceProjector() {
    }

    /**
     * Builds one index without resolving mutable heads inside the projection kernel.
     *
     * @param receipt exact successful Package compilation
     * @param inventory exact Package-selected Fidelity denominator, when resolvable
     * @param latestProfile newest signed profile observed for the business domain
     * @param projectedAt deterministic projection cut
     * @param mapper canonical protocol mapper
     * @return sealed package evidence index
     */
    public static PackageEvidenceIndex project(
            PackageCompilationReceipt receipt,
            Optional<DomainFidelityInventory> inventory,
            Optional<DomainFidelityProfile> latestProfile,
            Instant projectedAt,
            ObjectMapper mapper) {
        return project(receipt, inventory, latestProfile, 1, projectedAt, mapper);
    }

    /** Builds a projection at one repository-fenced independent evidence revision. */
    public static PackageEvidenceIndex project(
            PackageCompilationReceipt receipt,
            Optional<DomainFidelityInventory> inventory,
            Optional<DomainFidelityProfile> latestProfile,
            long projectionRevision,
            Instant projectedAt,
            ObjectMapper mapper) {
        if (projectionRevision < 1) {
            throw new IllegalArgumentException("projectionRevision must be positive");
        }
        PackageCompilationReceipt exact = Objects.requireNonNull(receipt, "receipt");
        DomainCapabilityPackageSnapshot snapshot = Objects.requireNonNull(
                exact.snapshot(), "successful Package snapshot");
        Instant cut = Objects.requireNonNull(projectedAt, "projectedAt")
                .truncatedTo(ChronoUnit.MICROS);
        PackageEvidenceIndex.EvidenceSource snapshotSource =
                PackageEvidenceIndex.EvidenceSource.from(snapshot.artifactRef());
        PackageEvidenceIndex.EvidenceSource readinessSource =
                PackageEvidenceIndex.EvidenceSource.from(exact.readiness().artifactRef());
        PackageEvidenceIndex.EvidenceSource closureSource =
                PackageEvidenceIndex.EvidenceSource.from(
                        exact.businessAssetLinkClosure().artifactRef());
        MirrorArtifactRef selectedInventoryRef = snapshot.dependencyManifest().stream()
                .filter(value -> DomainFidelityInventory.ARTIFACT_KIND.equals(value.kind()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Package snapshot has no Fidelity inventory dependency"));
        Optional<DomainFidelityInventory> exactInventory = inventory.filter(value -> {
            value.verify(mapper);
            return value.artifactRef().equals(selectedInventoryRef)
                    && value.scope().equals(snapshot.scope())
                    && value.domainId().equals(snapshot.businessDefinition().domainId());
        });
        Optional<DomainFidelityProfile> profile = latestProfile.map(value -> {
            value.verify(mapper);
            if (!value.scope().equals(snapshot.scope())
                    || !value.domainId().equals(snapshot.businessDefinition().domainId())
                    || !value.profileSeal().signed()) {
                throw new IllegalArgumentException(
                        "Fidelity profile must be signed and match the Package scope/domain");
            }
            return value;
        });

        Map<String, ConclusionSeed> seeds = new LinkedHashMap<>();
        add(seeds, snapshot.packageContractRef(), PackageEvidenceIndex.ProofStrength.COMPILED,
                snapshotSource, null, null, "");
        snapshot.dependencyManifest().forEach(ref -> add(seeds, ref,
                PackageEvidenceIndex.ProofStrength.COMPILED,
                snapshotSource, null, null, ""));
        snapshot.evidenceRefs().forEach(ref -> add(seeds, ref, proofForEvidence(ref),
                snapshotSource, null, null, ""));
        exact.businessAssetLinkClosure().assets().forEach(asset -> add(seeds, artifactRef(asset),
                PackageEvidenceIndex.ProofStrength.COMPILED,
                snapshotSource, null, null, ""));

        PackageEvidenceIndex.EvidenceSource inventorySource =
                PackageEvidenceIndex.EvidenceSource.from(selectedInventoryRef);
        if (exactInventory.isPresent()) {
            DomainFidelityInventory value = exactInventory.orElseThrow();
            add(seeds, value.artifactRef(),
                    PackageEvidenceIndex.ProofStrength.OWNER_APPROVED_DENOMINATOR,
                    snapshotSource, value.effectiveAt(), value.expiresAt(),
                    value.expiresAt().isAfter(cut) ? "" : "FIDELITY_INVENTORY_EXPIRED");
        } else {
            replace(seeds, selectedInventoryRef,
                    PackageEvidenceIndex.ProofStrength.COMPILED,
                    PackageEvidenceIndex.ConclusionState.MISSING,
                    snapshotSource, null, null, "FIDELITY_INVENTORY_UNRESOLVED");
        }

        FidelityProjection fidelity = fidelity(
                selectedInventoryRef, exactInventory, profile, snapshotSource, cut);
        fidelity.profileConclusion().ifPresent(seed -> seeds.put(seed.key(), seed));

        Map<PackageEvidenceIndex.EvidenceLayer.Layer,
                List<PackageEvidenceIndex.EvidenceConclusion>> byLayer =
                new EnumMap<>(PackageEvidenceIndex.EvidenceLayer.Layer.class);
        for (ConclusionSeed seed : seeds.values()) {
            byLayer.computeIfAbsent(seed.layer(), ignored -> new ArrayList<>())
                    .add(seed.toConclusion());
        }
        List<PackageEvidenceIndex.EvidenceLayer> layers =
                List.of(PackageEvidenceIndex.EvidenceLayer.Layer.values()).stream()
                        .map(layer -> new PackageEvidenceIndex.EvidenceLayer(
                                layer,
                                byLayer.getOrDefault(layer, List.of()).stream()
                                        .sorted(Comparator.comparing(
                                                PackageEvidenceIndex.EvidenceConclusion::conclusionId))
                                        .toList()))
                        .toList();
        List<PackageEvidenceIndex.DriftSignal> signals = driftSignals(
                snapshot, inventorySource, exactInventory, profile,
                fidelity.view(), snapshotSource, cut);
        Instant validUntil = validity(snapshot, exactInventory, profile, cut);
        return new PackageEvidenceIndex("", "", snapshot.scope(), snapshot.packageId(),
                snapshot.revision(), projectionRevision, snapshotSource, readinessSource, closureSource,
                snapshot.businessDefinition().domainId(),
                snapshot.businessDefinition().problemCode(), layers, fidelity.view(),
                signals, cut, validUntil).seal(mapper);
    }

    private static FidelityProjection fidelity(
            MirrorArtifactRef inventoryRef,
            Optional<DomainFidelityInventory> inventory,
            Optional<DomainFidelityProfile> profile,
            PackageEvidenceIndex.EvidenceSource snapshotSource,
            Instant cut) {
        PackageEvidenceIndex.EvidenceSource inventorySource =
                PackageEvidenceIndex.EvidenceSource.from(inventoryRef);
        List<PackageEvidenceIndex.EvidenceSource> baseLineage =
                sources(inventorySource, snapshotSource);
        if (inventory.isEmpty()) {
            return new FidelityProjection(missingView(
                    PackageEvidenceIndex.FidelityState.MISSING,
                    inventorySource, baseLineage), Optional.empty());
        }
        if (profile.isEmpty()) {
            ConclusionSeed missing = profileSeed(inventorySource,
                    PackageEvidenceIndex.ConclusionState.MISSING,
                    PackageEvidenceIndex.ProofStrength.OWNER_APPROVED_DENOMINATOR,
                    baseLineage, null, null, "FIDELITY_PROFILE_MISSING");
            return new FidelityProjection(missingView(
                    PackageEvidenceIndex.FidelityState.MISSING,
                    inventorySource, baseLineage), Optional.of(missing));
        }
        DomainFidelityProfile exact = profile.orElseThrow();
        PackageEvidenceIndex.EvidenceSource profileSource =
                PackageEvidenceIndex.EvidenceSource.from(exact);
        List<PackageEvidenceIndex.EvidenceSource> profileLineage = new ArrayList<>(baseLineage);
        profileLineage.add(profileSource);
        exact.unitAssessments().stream()
                .map(DomainFidelityProfile.UnitAssessment::sourceRef)
                .filter(Objects::nonNull)
                .map(PackageEvidenceIndex.EvidenceSource::from)
                .forEach(profileLineage::add);
        profileLineage = canonical(profileLineage);
        if (!exact.inventoryRef().equals(inventoryRef)) {
            ConclusionSeed drifted = profileSeed(profileSource,
                    PackageEvidenceIndex.ConclusionState.DRIFTED,
                    PackageEvidenceIndex.ProofStrength.SIGNED_FIDELITY_PROFILE,
                    profileLineage, exact.measuredAt(), exact.validUntil(),
                    "FIDELITY_PROFILE_INVENTORY_DRIFT");
            return new FidelityProjection(missingView(
                    PackageEvidenceIndex.FidelityState.INVENTORY_DRIFT,
                    inventorySource, profileLineage), Optional.of(drifted));
        }

        PackageEvidenceIndex.FidelityState state = fidelityState(exact, cut);
        Map<DomainFidelityProfile.Dimension, DomainFidelityProfile.DimensionMetric> metrics =
                new EnumMap<>(DomainFidelityProfile.Dimension.class);
        exact.dimensions().forEach(value -> metrics.put(value.dimension(), value));
        List<PackageEvidenceIndex.DimensionEvidence> dimensions =
                List.of(DomainFidelityProfile.Dimension.values()).stream()
                        .map(dimension -> {
                            DomainFidelityProfile.DimensionMetric metric = metrics.get(dimension);
                            List<PackageEvidenceIndex.EvidenceSource> lineage =
                                    dimensionLineage(exact, dimension, inventorySource, profileSource);
                            return new PackageEvidenceIndex.DimensionEvidence(dimension,
                                    metric == null
                                            ? PackageEvidenceIndex.DimensionState.NOT_IN_DENOMINATOR
                                            : dimensionState(metric),
                                    metric, lineage);
                        }).toList();
        PackageEvidenceIndex.FidelityView view = new PackageEvidenceIndex.FidelityView(
                state, inventorySource, profileSource, exact.measuredAt(), exact.validUntil(),
                exact.denominator(), dimensions, exact.abstentionDebt(),
                exact.sourceComposition(), exact.assessment(), exact.limitations(), profileLineage);
        PackageEvidenceIndex.ConclusionState conclusionState = switch (state) {
            case CURRENT -> PackageEvidenceIndex.ConclusionState.AVAILABLE;
            case PARTIAL -> PackageEvidenceIndex.ConclusionState.INSUFFICIENT;
            case ABSTAINED -> PackageEvidenceIndex.ConclusionState.ABSTAINED;
            case STALE -> PackageEvidenceIndex.ConclusionState.STALE;
            case INSUFFICIENT -> PackageEvidenceIndex.ConclusionState.INSUFFICIENT;
            case MISSING -> PackageEvidenceIndex.ConclusionState.MISSING;
            case INVENTORY_DRIFT -> PackageEvidenceIndex.ConclusionState.DRIFTED;
        };
        String limitation = conclusionState == PackageEvidenceIndex.ConclusionState.AVAILABLE
                ? "" : switch (state) {
            case ABSTAINED -> "FIDELITY_ABSTENTION_DEBT";
            case STALE -> "FIDELITY_PROFILE_STALE";
            case PARTIAL, INSUFFICIENT -> "FIDELITY_EVIDENCE_INSUFFICIENT";
            default -> "FIDELITY_PROFILE_UNAVAILABLE";
        };
        return new FidelityProjection(view, Optional.of(profileSeed(profileSource,
                conclusionState, PackageEvidenceIndex.ProofStrength.SIGNED_FIDELITY_PROFILE,
                profileLineage, exact.measuredAt(), exact.validUntil(), limitation)));
    }

    private static PackageEvidenceIndex.FidelityView missingView(
            PackageEvidenceIndex.FidelityState state,
            PackageEvidenceIndex.EvidenceSource inventorySource,
            List<PackageEvidenceIndex.EvidenceSource> lineage) {
        return new PackageEvidenceIndex.FidelityView(state, inventorySource, null,
                null, null, null, List.of(), null, null, null, List.of(), lineage);
    }

    private static List<PackageEvidenceIndex.EvidenceSource> dimensionLineage(
            DomainFidelityProfile profile,
            DomainFidelityProfile.Dimension dimension,
            PackageEvidenceIndex.EvidenceSource inventory,
            PackageEvidenceIndex.EvidenceSource profileSource) {
        List<PackageEvidenceIndex.EvidenceSource> result = new ArrayList<>();
        result.add(inventory);
        result.add(profileSource);
        profile.unitAssessments().stream()
                .filter(unit -> unit.results().stream().anyMatch(value -> value.dimension() == dimension))
                .map(DomainFidelityProfile.UnitAssessment::sourceRef)
                .filter(Objects::nonNull)
                .map(PackageEvidenceIndex.EvidenceSource::from)
                .forEach(result::add);
        return canonical(result);
    }

    private static PackageEvidenceIndex.DimensionState dimensionState(
            DomainFidelityProfile.DimensionMetric metric) {
        if (metric.staleUnits() > 0) {
            return PackageEvidenceIndex.DimensionState.STALE;
        }
        if (metric.missingUnits() > 0) {
            return PackageEvidenceIndex.DimensionState.MISSING;
        }
        if (metric.abstainedUnits() > 0) {
            return PackageEvidenceIndex.DimensionState.ABSTAINED;
        }
        return switch (metric.sufficiency()) {
            case MEASURED -> PackageEvidenceIndex.DimensionState.MEASURED;
            case PARTIAL_COVERAGE -> PackageEvidenceIndex.DimensionState.PARTIAL;
            case BELOW_MINIMUM_SAMPLE, NO_ASSESSED_EVIDENCE ->
                    PackageEvidenceIndex.DimensionState.INSUFFICIENT;
        };
    }

    private static PackageEvidenceIndex.FidelityState fidelityState(
            DomainFidelityProfile profile, Instant cut) {
        if (!profile.validUntil().isAfter(cut)
                || profile.assessment() == DomainFidelityProfile.Assessment.STALE) {
            return PackageEvidenceIndex.FidelityState.STALE;
        }
        if (profile.assessment() == DomainFidelityProfile.Assessment.INSUFFICIENT_EVIDENCE) {
            return PackageEvidenceIndex.FidelityState.INSUFFICIENT;
        }
        if (profile.abstentionDebt().abstainedObligations() > 0) {
            return PackageEvidenceIndex.FidelityState.ABSTAINED;
        }
        if (profile.assessment() == DomainFidelityProfile.Assessment.PARTIAL) {
            return PackageEvidenceIndex.FidelityState.PARTIAL;
        }
        return PackageEvidenceIndex.FidelityState.CURRENT;
    }

    private static List<PackageEvidenceIndex.DriftSignal> driftSignals(
            DomainCapabilityPackageSnapshot snapshot,
            PackageEvidenceIndex.EvidenceSource inventorySource,
            Optional<DomainFidelityInventory> inventory,
            Optional<DomainFidelityProfile> profile,
            PackageEvidenceIndex.FidelityView view,
            PackageEvidenceIndex.EvidenceSource snapshotSource,
            Instant cut) {
        List<PackageEvidenceIndex.DriftReason> reasons = new ArrayList<>();
        if (inventory.isEmpty()) {
            reasons.add(PackageEvidenceIndex.DriftReason.FIDELITY_INVENTORY_UNRESOLVED);
        } else if (!inventory.orElseThrow().expiresAt().isAfter(cut)) {
            reasons.add(PackageEvidenceIndex.DriftReason.FIDELITY_INVENTORY_EXPIRED);
        }
        switch (view.state()) {
            case MISSING -> reasons.add(PackageEvidenceIndex.DriftReason.FIDELITY_PROFILE_MISSING);
            case INVENTORY_DRIFT -> reasons.add(
                    PackageEvidenceIndex.DriftReason.FIDELITY_PROFILE_INVENTORY_DRIFT);
            case STALE -> reasons.add(PackageEvidenceIndex.DriftReason.FIDELITY_PROFILE_STALE);
            case INSUFFICIENT -> reasons.add(
                    PackageEvidenceIndex.DriftReason.FIDELITY_EVIDENCE_INSUFFICIENT);
            case ABSTAINED -> reasons.add(
                    PackageEvidenceIndex.DriftReason.FIDELITY_ABSTENTION_DEBT);
            default -> {
            }
        }
        if (profile.isEmpty()
                || profile.orElseThrow().dimensions().stream().noneMatch(
                value -> value.dimension() == DomainFidelityProfile.Dimension.OUTCOME)
                || profile.orElseThrow().limitations().contains(
                DomainFidelityProfile.Limitation.OUTCOME_UNCALIBRATED)) {
            reasons.add(PackageEvidenceIndex.DriftReason.OUTCOME_UNCALIBRATED);
        }
        PackageEvidenceIndex.SignalSeverity severity = severity(
                snapshot.businessDefinition().riskClass());
        Instant dueAt = cut.plus(dueWithin(snapshot.businessDefinition().riskClass()));
        List<PackageEvidenceIndex.EvidenceSource> lineage = new ArrayList<>();
        lineage.add(snapshotSource);
        lineage.add(inventorySource);
        if (profile.isPresent()) {
            lineage.add(PackageEvidenceIndex.EvidenceSource.from(profile.orElseThrow()));
        }
        List<PackageEvidenceIndex.EvidenceSource> exactLineage = canonical(lineage);
        return reasons.stream().distinct().sorted(Comparator.comparing(Enum::name))
                .map(reason -> new PackageEvidenceIndex.DriftSignal(
                        signalId(snapshot.packageId(), snapshot.revision(), reason, exactLineage),
                        reason, severity, snapshot.businessDefinition().accountableOwner(),
                        exactLineage, cut, dueAt))
                .sorted(Comparator.comparing(PackageEvidenceIndex.DriftSignal::signalId))
                .toList();
    }

    private static Instant validity(
            DomainCapabilityPackageSnapshot snapshot,
            Optional<DomainFidelityInventory> inventory,
            Optional<DomainFidelityProfile> profile,
            Instant cut) {
        Instant validUntil = cut.plus(DEFAULT_VALIDITY);
        if (snapshot.provenance().expiresAt() != null
                && snapshot.provenance().expiresAt().isBefore(validUntil)) {
            validUntil = snapshot.provenance().expiresAt();
        }
        if (inventory.isPresent() && inventory.orElseThrow().expiresAt().isBefore(validUntil)) {
            validUntil = inventory.orElseThrow().expiresAt();
        }
        if (profile.isPresent() && profile.orElseThrow().validUntil().isBefore(validUntil)) {
            validUntil = profile.orElseThrow().validUntil();
        }
        return validUntil.isBefore(cut) ? cut : validUntil;
    }

    private static void add(
            Map<String, ConclusionSeed> seeds,
            MirrorArtifactRef ref,
            PackageEvidenceIndex.ProofStrength proof,
            PackageEvidenceIndex.EvidenceSource snapshotSource,
            Instant observedAt,
            Instant validUntil,
            String limitation) {
        PackageEvidenceIndex.EvidenceSource subject =
                PackageEvidenceIndex.EvidenceSource.from(ref);
        String key = key(subject);
        ConclusionSeed candidate = new ConclusionSeed(key, layer(ref.kind()),
                evidenceKind(ref.kind()), proof,
                limitation.isBlank() ? PackageEvidenceIndex.ConclusionState.AVAILABLE
                        : PackageEvidenceIndex.ConclusionState.STALE,
                subject, sources(subject, snapshotSource), observedAt, validUntil, limitation);
        seeds.merge(key, candidate, PackageEvidenceProjector::stronger);
    }

    private static void replace(
            Map<String, ConclusionSeed> seeds,
            MirrorArtifactRef ref,
            PackageEvidenceIndex.ProofStrength proof,
            PackageEvidenceIndex.ConclusionState state,
            PackageEvidenceIndex.EvidenceSource snapshotSource,
            Instant observedAt,
            Instant validUntil,
            String limitation) {
        PackageEvidenceIndex.EvidenceSource subject =
                PackageEvidenceIndex.EvidenceSource.from(ref);
        String key = key(subject);
        seeds.put(key, new ConclusionSeed(key, layer(ref.kind()), evidenceKind(ref.kind()),
                proof, state, subject, sources(subject, snapshotSource), observedAt,
                validUntil, limitation));
    }

    private static ConclusionSeed stronger(ConclusionSeed left, ConclusionSeed right) {
        return left.proof().ordinal() >= right.proof().ordinal() ? left : right;
    }

    private static ConclusionSeed profileSeed(
            PackageEvidenceIndex.EvidenceSource subject,
            PackageEvidenceIndex.ConclusionState state,
            PackageEvidenceIndex.ProofStrength proof,
            List<PackageEvidenceIndex.EvidenceSource> lineage,
            Instant observedAt,
            Instant validUntil,
            String limitation) {
        String key = key(subject);
        return new ConclusionSeed(key,
                PackageEvidenceIndex.EvidenceLayer.Layer.CALIBRATION,
                PackageEvidenceIndex.EvidenceKind.FIDELITY_PROFILE,
                proof, state, subject, canonical(lineage), observedAt, validUntil, limitation);
    }

    private static PackageEvidenceIndex.ProofStrength proofForEvidence(MirrorArtifactRef ref) {
        if (ref.kind().contains("OUTCOME") && !"OUTCOME_DEFINITION".equals(ref.kind())) {
            return PackageEvidenceIndex.ProofStrength.INDEPENDENT_OUTCOME;
        }
        if ("DOMAIN_FIDELITY_PROFILE".equals(ref.kind())) {
            return PackageEvidenceIndex.ProofStrength.SIGNED_FIDELITY_PROFILE;
        }
        return PackageEvidenceIndex.ProofStrength.EXECUTION_EVIDENCE;
    }

    private static PackageEvidenceIndex.EvidenceLayer.Layer layer(String kind) {
        String exact = kind.toUpperCase(java.util.Locale.ROOT);
        if (List.of("SOP", "WORKFLOW", "AGENT").contains(exact)) {
            return PackageEvidenceIndex.EvidenceLayer.Layer.L2_SERVICE_CARRIER;
        }
        if ("CHANNEL_APPLICATION".equals(exact)) {
            return PackageEvidenceIndex.EvidenceLayer.Layer.L3_APPLICATION;
        }
        if (exact.contains("FIDELITY") || exact.contains("OUTCOME")) {
            return PackageEvidenceIndex.EvidenceLayer.Layer.CALIBRATION;
        }
        if (List.of("SCENARIO_INVENTORY", "SCENARIO_PACK", "SCENARIO_CASE",
                "SOLUTION", "FEATURE").contains(exact)) {
            return PackageEvidenceIndex.EvidenceLayer.Layer.L1_SERVICE_DESIGN;
        }
        return PackageEvidenceIndex.EvidenceLayer.Layer.L0_RESOURCE;
    }

    private static PackageEvidenceIndex.EvidenceKind evidenceKind(String kind) {
        String exact = kind.toUpperCase(java.util.Locale.ROOT);
        if (exact.contains("CONTRACT") || exact.contains("SCHEMA")) {
            return PackageEvidenceIndex.EvidenceKind.CONTRACT;
        }
        if (exact.contains("STATE") || exact.contains("EFFECT") || exact.contains("WRITE")) {
            return PackageEvidenceIndex.EvidenceKind.STATE_EFFECT;
        }
        if (exact.contains("SCENARIO") || exact.contains("FIXTURE")
                || exact.contains("TEST_SUITE")) {
            return PackageEvidenceIndex.EvidenceKind.SCENARIO;
        }
        if ("SOLUTION".equals(exact)) {
            return PackageEvidenceIndex.EvidenceKind.SOLUTION;
        }
        if (List.of("SOP", "WORKFLOW", "AGENT").contains(exact)) {
            return PackageEvidenceIndex.EvidenceKind.CARRIER;
        }
        if ("CHANNEL_APPLICATION".equals(exact)) {
            return PackageEvidenceIndex.EvidenceKind.CHANNEL;
        }
        if (DomainFidelityInventory.ARTIFACT_KIND.equals(exact)) {
            return PackageEvidenceIndex.EvidenceKind.FIDELITY_DENOMINATOR;
        }
        if ("DOMAIN_FIDELITY_PROFILE".equals(exact)) {
            return PackageEvidenceIndex.EvidenceKind.FIDELITY_PROFILE;
        }
        if (exact.contains("OUTCOME")) {
            return PackageEvidenceIndex.EvidenceKind.OUTCOME;
        }
        if (exact.contains("GRAPH") || exact.contains("CAPABILITY")
                || exact.contains("OPERATOR") || exact.contains("FUNCTION")
                || exact.contains("RESOURCE") || exact.contains("MIRROR_PLAN")) {
            return PackageEvidenceIndex.EvidenceKind.EXECUTABLE;
        }
        return PackageEvidenceIndex.EvidenceKind.OTHER;
    }

    private static MirrorArtifactRef artifactRef(BusinessAssetRef ref) {
        return new MirrorArtifactRef(ref.kind().name(), ref.id(), ref.revision(), ref.fingerprint());
    }

    private static String key(PackageEvidenceIndex.EvidenceSource source) {
        String coordinate = source.coordinate().replace(':', '-');
        return source.kind().toLowerCase(java.util.Locale.ROOT) + ":" + source.id()
                + ":" + coordinate + ":" + source.fingerprint().substring(7, 19);
    }

    private static String signalId(
            String packageId,
            long revision,
            PackageEvidenceIndex.DriftReason reason,
            List<PackageEvidenceIndex.EvidenceSource> lineage) {
        String material = lineage.stream()
                .map(value -> value.kind() + "|" + value.id() + "|" + value.coordinate()
                        + "|" + value.fingerprint())
                .reduce((left, right) -> left + '\n' + right).orElseThrow();
        return packageId + ":r" + revision + ":" + reason.name().toLowerCase(
                java.util.Locale.ROOT) + ":" + sha256(material).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static PackageEvidenceIndex.SignalSeverity severity(
            DomainCapabilityPackageDraft.RiskClass risk) {
        return switch (risk) {
            case LOW -> PackageEvidenceIndex.SignalSeverity.WARNING;
            case MEDIUM, HIGH -> PackageEvidenceIndex.SignalSeverity.ERROR;
            case CRITICAL -> PackageEvidenceIndex.SignalSeverity.CRITICAL;
        };
    }

    private static Duration dueWithin(DomainCapabilityPackageDraft.RiskClass risk) {
        return switch (risk) {
            case LOW -> Duration.ofDays(7);
            case MEDIUM -> Duration.ofDays(3);
            case HIGH -> Duration.ofDays(1);
            case CRITICAL -> Duration.ofHours(4);
        };
    }

    private static List<PackageEvidenceIndex.EvidenceSource> sources(
            PackageEvidenceIndex.EvidenceSource... values) {
        return canonical(List.of(values));
    }

    private static List<PackageEvidenceIndex.EvidenceSource> canonical(
            List<PackageEvidenceIndex.EvidenceSource> values) {
        return values.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private record FidelityProjection(
            PackageEvidenceIndex.FidelityView view,
            Optional<ConclusionSeed> profileConclusion) {
    }

    private record ConclusionSeed(
            String key,
            PackageEvidenceIndex.EvidenceLayer.Layer layer,
            PackageEvidenceIndex.EvidenceKind evidenceKind,
            PackageEvidenceIndex.ProofStrength proof,
            PackageEvidenceIndex.ConclusionState state,
            PackageEvidenceIndex.EvidenceSource subject,
            List<PackageEvidenceIndex.EvidenceSource> lineage,
            Instant observedAt,
            Instant validUntil,
            String limitation) {
        private PackageEvidenceIndex.EvidenceConclusion toConclusion() {
            return new PackageEvidenceIndex.EvidenceConclusion(key, layer, evidenceKind,
                    proof, state, subject, lineage, observedAt, validUntil, limitation);
        }
    }
}
