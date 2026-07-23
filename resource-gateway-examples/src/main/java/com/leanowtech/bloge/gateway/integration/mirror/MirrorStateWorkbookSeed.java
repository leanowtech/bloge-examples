package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Deterministic payload-free ANEKE correctness-workbook seed for one stateful mirror run.
 *
 * <p>The seed does not replace the signed evidence bundle and does not make a governance
 * decision. It gives a consumer stable run, plan, bundle, Session head, state-model, and state
 * evidence coordinates plus bounded publication blockers. ANEKE can use those coordinates to
 * create or update its own workbook without parsing arbitrary server maps or retaining business
 * payloads.</p>
 *
 * @param schemaVersion workbook-seed protocol version
 * @param seedFingerprint canonical fingerprint with this field blanked
 * @param runId exact terminal mirror run
 * @param planFingerprint exact sealed mirror plan
 * @param evidenceBundleFingerprint exact signed source bundle
 * @param stateEvidenceRef exact payload-free state-evidence artifact
 * @param sessionStateRef exact immutable Session state head
 * @param stateModelRef exact state model used by the run
 * @param stateRevision zero-based committed state revision
 * @param worldFingerprint canonical business-world identity
 * @param logicalClock deterministic Session logical time
 * @param mode state-access mode observed by the run
 * @param runStatus terminal run status
 * @param evidenceClass exploratory or certifiable evidence class
 * @param bindingCount number of state-backed invocation sites
 * @param accessCount number of observed state accesses
 * @param liveEntityCount number of live entity hits
 * @param absentCount number of absent keys that permitted controlled fallback
 * @param tombstonedCount number of terminal tombstone observations
 * @param gateReady whether this seed has no publication blocker
 * @param blockers deterministic bounded publication blockers
 */
public record MirrorStateWorkbookSeed(
        String schemaVersion,
        String seedFingerprint,
        String runId,
        String planFingerprint,
        String evidenceBundleFingerprint,
        MirrorArtifactRef stateEvidenceRef,
        MirrorArtifactRef sessionStateRef,
        MirrorArtifactRef stateModelRef,
        long stateRevision,
        String worldFingerprint,
        Instant logicalClock,
        MirrorStateRunEvidence.Mode mode,
        MirrorRunEvidence.Status runStatus,
        MirrorRunEvidence.EvidenceClass evidenceClass,
        int bindingCount,
        int accessCount,
        int liveEntityCount,
        int absentCount,
        int tombstonedCount,
        boolean gateReady,
        List<String> blockers
) {
    /** Current state-workbook seed version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorStateWorkbookSeed.v1";
    /** Maximum canonical seed bytes admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES = 1024 * 1024;
    /** Maximum state-backed invocation sites represented by one seed. */
    public static final int MAXIMUM_BINDINGS =
            MirrorStateRunEvidence.MAXIMUM_BINDINGS;
    /** Maximum state accesses represented by one seed. */
    public static final int MAXIMUM_ACCESSES =
            MirrorStateRunEvidence.MAXIMUM_ACCESSES;
    /** Maximum deterministic governance blockers represented by one seed. */
    public static final int MAXIMUM_BLOCKERS = 16;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates exact references, outcome arithmetic, and conservative gate readiness. */
    public MirrorStateWorkbookSeed {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror state workbook seed version");
        }
        seedFingerprint = optionalFingerprint(
                seedFingerprint, "seedFingerprint");
        runId = required(runId, "runId", 512);
        planFingerprint = fingerprint(
                planFingerprint, "planFingerprint");
        evidenceBundleFingerprint = fingerprint(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint");
        stateEvidenceRef = requireKind(
                stateEvidenceRef, "MIRROR_STATE_RUN_EVIDENCE",
                "stateEvidenceRef");
        sessionStateRef = requireKind(
                sessionStateRef, "SESSION_STATE",
                "sessionStateRef");
        stateModelRef = requireKind(
                stateModelRef, "STATE_MODEL", "stateModelRef");
        if (stateRevision < 0
                || sessionStateRef.revision()
                != Math.addExact(stateRevision, 1)) {
            throw new IllegalArgumentException(
                    "sessionStateRef revision must encode stateRevision + 1");
        }
        worldFingerprint = fingerprint(
                worldFingerprint, "worldFingerprint");
        logicalClock = Objects.requireNonNull(
                logicalClock, "logicalClock");
        mode = Objects.requireNonNull(mode, "mode");
        runStatus = Objects.requireNonNull(
                runStatus, "runStatus");
        evidenceClass = Objects.requireNonNull(
                evidenceClass, "evidenceClass");
        if (bindingCount < 1 || bindingCount > MAXIMUM_BINDINGS
                || accessCount < 0 || accessCount > MAXIMUM_ACCESSES
                || liveEntityCount < 0 || absentCount < 0
                || tombstonedCount < 0
                || liveEntityCount > MAXIMUM_ACCESSES
                || absentCount > MAXIMUM_ACCESSES
                || tombstonedCount > MAXIMUM_ACCESSES
                || accessCount != Math.addExact(
                Math.addExact(liveEntityCount, absentCount),
                tombstonedCount)) {
            throw new IllegalArgumentException(
                    "mirror state workbook counts are inconsistent");
        }
        blockers = orderedBlockers(blockers);
        if (gateReady != blockers.isEmpty()
                || gateReady
                && (runStatus != MirrorRunEvidence.Status.PASSED
                || evidenceClass
                != MirrorRunEvidence.EvidenceClass.CERTIFIABLE)) {
            throw new IllegalArgumentException(
                    "mirror state workbook gate readiness is inconsistent");
        }
    }

    /**
     * Projects one signed stateful bundle into a deterministic governance seed.
     *
     * <p>The caller must obtain the bundle from a repository that has already performed
     * cryptographic verification. The projection independently verifies the nested state
     * fingerprint and all cross-object identities before exposing coordinates.</p>
     *
     * @param mapper canonical protocol mapper
     * @param bundle verified stateful evidence bundle
     * @return sealed payload-free workbook seed
     */
    public static MirrorStateWorkbookSeed project(
            ObjectMapper mapper, MirrorEvidenceBundle bundle) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(bundle, "bundle");
        MirrorRunEvidence run = bundle.evidence();
        MirrorStateRunEvidence state = run.stateEvidence();
        if (!MirrorEvidenceBundle.STATEFUL_SCHEMA_VERSION.equals(
                bundle.schemaVersion())
                || !MirrorEvidenceAttestation.STATEFUL_SCHEMA_VERSION.equals(
                bundle.attestation().schemaVersion())
                || !MirrorRunEvidence.STATEFUL_SCHEMA_VERSION.equals(
                run.schemaVersion())
                || state == null
                || !bundle.attestation().independentlyVerifiable()
                || !run.runId().equals(state.runId())
                || !run.planFingerprint().equals(
                state.planFingerprint())) {
            throw new IllegalArgumentException(
                    "state workbook seed requires one verified stateful bundle");
        }
        MirrorStateRunEvidenceIntegrity.verify(mapper, state);

        int live = 0;
        int absent = 0;
        int tombstoned = 0;
        for (MirrorStateRunEvidence.StateAccess access
                : state.accesses()) {
            switch (access.outcome()) {
                case LIVE_ENTITY -> live++;
                case ABSENT -> absent++;
                case TOMBSTONED -> tombstoned++;
            }
        }
        TreeSet<String> blockers = new TreeSet<>();
        if (run.status() != MirrorRunEvidence.Status.PASSED) {
            blockers.add("RUN_NOT_PASSED");
        }
        if (run.evidenceClass()
                != MirrorRunEvidence.EvidenceClass.CERTIFIABLE) {
            blockers.add("EVIDENCE_NOT_CERTIFIABLE");
        }
        if (!run.limitations().isEmpty()
                || !run.isolation().limitations().isEmpty()) {
            blockers.add("RUN_EVIDENCE_LIMITED");
        }
        if (!state.limitations().isEmpty()) {
            blockers.add("STATE_EVIDENCE_LIMITED");
        }
        MirrorArtifactRef stateEvidenceRef =
                MirrorStateRunEvidenceIntegrity.reference(state);
        MirrorStateWorkbookSeed unsealed =
                new MirrorStateWorkbookSeed(
                        SCHEMA_VERSION, "", run.runId(),
                        run.planFingerprint(),
                        bundle.bundleFingerprint(),
                        stateEvidenceRef,
                        state.sessionStateRef(),
                        state.stateModelRef(),
                        state.stateRevision(),
                        state.worldFingerprint(),
                        state.logicalClock(), state.mode(),
                        run.status(), run.evidenceClass(),
                        state.statefulBindings().size(),
                        state.accesses().size(),
                        live, absent, tombstoned,
                        blockers.isEmpty(), List.copyOf(blockers));
        return unsealed.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper, unsealed,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Recomputes this seed's self-fingerprint.
     *
     * @param mapper canonical protocol mapper
     * @throws IllegalArgumentException when the seed was changed after projection
     */
    public void verify(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        MirrorStateWorkbookSeed material =
                withFingerprint("");
        if (!ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_CANONICAL_BYTES)
                .equals(seedFingerprint)) {
            throw new IllegalArgumentException(
                    "mirror state workbook seed fingerprint mismatch");
        }
    }

    /** @return copy carrying a replacement self-fingerprint */
    public MirrorStateWorkbookSeed withFingerprint(
            String fingerprint) {
        return new MirrorStateWorkbookSeed(
                schemaVersion, fingerprint, runId,
                planFingerprint, evidenceBundleFingerprint,
                stateEvidenceRef, sessionStateRef, stateModelRef,
                stateRevision, worldFingerprint, logicalClock,
                mode, runStatus, evidenceClass, bindingCount,
                accessCount, liveEntityCount, absentCount,
                tombstonedCount, gateReady, blockers);
    }

    /** Keeps exact fingerprints and business-world coordinates out of generic logs. */
    @Override
    public String toString() {
        return "MirrorStateWorkbookSeed[runId=" + runId
                + ", stateRevision=" + stateRevision
                + ", accessCount=" + accessCount
                + ", gateReady=" + gateReady + "]";
    }

    private static List<String> orderedBlockers(
            List<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = required(
                        value, "blocker", 256);
                if (!normalized.matches(
                        "[A-Z][A-Z0-9_.-]{0,255}")
                        || !result.add(normalized)) {
                    throw new IllegalArgumentException(
                            "mirror state workbook blockers are invalid");
                }
            }
        }
        if (result.size() > MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "mirror state workbook blockers exceed the protocol bound");
        }
        return List.copyOf(result);
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value, String kind,
            String field) {
        MirrorArtifactRef required =
                Objects.requireNonNull(value, field);
        if (!kind.equals(required.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return required;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!normalized.isEmpty()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String required(
            String value, String field, int maximum) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return normalized;
    }
}
