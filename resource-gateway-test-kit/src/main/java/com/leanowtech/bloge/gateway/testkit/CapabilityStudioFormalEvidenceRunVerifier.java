package com.leanowtech.bloge.gateway.testkit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only A0 facade for exact evidence closure and real typed replay. */
public final class CapabilityStudioFormalEvidenceRunVerifier {
    private CapabilityStudioFormalEvidenceRunVerifier() {
    }

    /** Closed failure categories emitted without payload or path details. */
    public enum FailureKind {
        /** Evidence or metadata violates the closed A0 contract. */
        INVALID,
        /** Required evidence or stable filesystem metadata cannot be read. */
        UNAVAILABLE
    }

    /** Payload-free failure raised when A0 verification cannot complete. */
    public static final class VerificationException extends RuntimeException {
        /** Closed failure category retained without evidence or path details. */
        private final FailureKind failureKind;

        VerificationException(FailureKind failureKind) {
            super("formal evidence run verification failed");
            this.failureKind = failureKind;
        }

        /**
         * Returns the closed failure category.
         *
         * @return invalid or unavailable
         */
        public FailureKind failureKind() {
            return failureKind;
        }

        @Override
        public String toString() {
            return "VerificationException[failureKind=" + failureKind + "]";
        }
    }

    /**
     * Payload-free projection of a completed A0 candidate replay.
     *
     * @param verificationLevel structure-verified or incomplete
     * @param typedReplayCount number of real typed adapters that verified their subject
     * @param passed formal pass count; always zero in A0
     * @param failed number of failed FELT obligations
     * @param blocked number of blocked FELT obligations
     * @param notRun number of FELT obligations not run
     * @param evidenceCount number of files in the exact inventory
     * @param evidenceByteSize aggregate byte size of the exact inventory
     */
    public record Verification(
            String verificationLevel,
            int typedReplayCount,
            int passed,
            int failed,
            int blocked,
            int notRun,
            int evidenceCount,
            long evidenceByteSize) {
        /** Validates that the public projection cannot be forged outside the A0 contract. */
        public Verification {
            if (!("STRUCTURE_VERIFIED".equals(verificationLevel)
                    || "INCOMPLETE".equals(verificationLevel))
                    || typedReplayCount < 0 || typedReplayCount > 3 || passed != 0
                    || failed < 0 || blocked < 0 || notRun < 0
                    || failed + blocked + notRun != 14
                    || evidenceCount < 0 || evidenceByteSize < 0
                    || evidenceCount == 0 && evidenceByteSize != 0
                    || "INCOMPLETE".equals(verificationLevel) && typedReplayCount != 0
                    || "STRUCTURE_VERIFIED".equals(verificationLevel)
                    && typedReplayCount == 0) {
                throw new IllegalArgumentException("formal evidence verification is invalid");
            }
        }
    }

    /** Complete internal A0 facts retained for the strict candidate-result projection. */
    record ReplayRun(
            CapabilityStudioFormalEvidenceRunManifest.Compiled plan,
            Map<CapabilityStudioTypedEvidenceReplayRegistry.Slot,
                    CapabilityStudioTypedEvidenceReplayRegistry.ReplayObservation> observations,
            CapabilityStudioCandidateReplayDeriver.Decision decision) {
        ReplayRun {
            if (plan == null || observations == null || decision == null) {
                throw invalid();
            }
            EnumMap<CapabilityStudioTypedEvidenceReplayRegistry.Slot,
                    CapabilityStudioTypedEvidenceReplayRegistry.ReplayObservation> copy =
                    new EnumMap<>(CapabilityStudioTypedEvidenceReplayRegistry.Slot.class);
            copy.putAll(observations);
            observations = Map.copyOf(copy);
        }
    }

    /** Package-private deterministic mutation seam for closure tests. */
    interface VerificationObserver {
        VerificationObserver NONE = new VerificationObserver() {
        };

        default void afterInitialManifestRead(Path manifest) {
        }

        default void beforeInventoryRead(String relativePath, Path file) {
        }

        default void beforeTypedReplay(String replayId) {
        }

        default void beforeFinalManifestRead(Path manifest) {
        }
    }

    /**
     * Verifies one canonical manifest and its immutable Evidence Root.
     *
     * @param manifestFile absolute normalized path to the manifest
     * @param bundleRoot absolute normalized path to the Evidence Root
     * @return payload-free A0 projection
     * @throws VerificationException if evidence is invalid or unavailable
     */
    public static Verification verify(Path manifestFile, Path bundleRoot)
            throws VerificationException {
        return verify(manifestFile, bundleRoot, VerificationObserver.NONE);
    }

    static Verification verify(
            Path manifestFile, Path bundleRoot, VerificationObserver observer)
            throws VerificationException {
        return project(verifyDetailed(manifestFile, bundleRoot, observer));
    }

    static ReplayRun verifyDetailed(
            Path manifestFile, Path bundleRoot, VerificationObserver observer)
            throws VerificationException {
        if (observer == null) {
            throw invalid();
        }
        try {
            var session = CapabilityStudioFormalEvidenceBundleCollector.Session.open(
                    manifestFile, bundleRoot, collectorObserver(observer, manifestFile));
            var plan = CapabilityStudioFormalEvidenceRunManifest.compile(
                    session.manifestBytes());
            Map<CapabilityStudioTypedEvidenceReplayRegistry.Slot,
                    CapabilityStudioCandidateReplayDeriver.ReplayOutcome> outcomes =
                    initialOutcomes();
            Map<CapabilityStudioTypedEvidenceReplayRegistry.Slot,
                    CapabilityStudioTypedEvidenceReplayRegistry.ReplayObservation> observations =
                    new EnumMap<>(CapabilityStudioTypedEvidenceReplayRegistry.Slot.class);
            Set<String> hardlinkScopes = new HashSet<>();
            CapabilityStudioCandidateReplayDeriver.ClosureOutcome closure =
                    CapabilityStudioCandidateReplayDeriver.ClosureOutcome.VERIFIED;
            try {
                session.afterManifestCompiled();
                session.sealInventory(plan.inventory());
            } catch (CapabilityStudioFormalEvidenceBundleCollector.CollectorException failure) {
                closure = closureOutcome(failure);
            }
            for (var request : plan.replayPlan()) {
                if (closure != CapabilityStudioCandidateReplayDeriver.ClosureOutcome.VERIFIED) {
                    break;
                }
                var slot = request.slot();
                try {
                    session.beforeReplay(
                            request.id(), request.subjectPath(), slot.fileSubject());
                    var subject = subject(session, plan, request);
                    var replay = CapabilityStudioTypedEvidenceReplayRegistry.replay(
                            request, subject);
                    observations.put(slot, replay);
                    outcomes.put(slot, replayOutcome(replay.status()));
                    session.afterReplay(
                            request.id(), request.subjectPath(), slot.fileSubject());
                    if (replay.hardlinkScope() != null) {
                        hardlinkScopes.add(replay.hardlinkScope());
                    }
                } catch (CapabilityStudioFormalEvidenceBundleCollector.CollectorException failure) {
                    closure = closureOutcome(failure);
                    observations.remove(slot);
                    outcomes.put(slot,
                            closure == CapabilityStudioCandidateReplayDeriver.ClosureOutcome.UNAVAILABLE
                                    ? CapabilityStudioCandidateReplayDeriver.ReplayOutcome.UNAVAILABLE
                                    : CapabilityStudioCandidateReplayDeriver.ReplayOutcome.INVALID);
                    break;
                }
            }
            if (closure == CapabilityStudioCandidateReplayDeriver.ClosureOutcome.VERIFIED) {
                try {
                    session.finish(Set.copyOf(hardlinkScopes));
                } catch (CapabilityStudioFormalEvidenceBundleCollector.CollectorException failure) {
                    closure = closureOutcome(failure);
                }
            }

            var counts = plan.obligationCounts();
            var decision = CapabilityStudioCandidateReplayDeriver.derive(
                    orderedOutcomes(outcomes), closure, plan.verificationLevel(),
                    counts.failed(), counts.blocked(), counts.notRun(),
                    plan.inventory().size(), plan.evidenceByteSize());
            return new ReplayRun(plan, observations, decision);
        } catch (CapabilityStudioFormalEvidenceBundleCollector.CollectorException failure) {
            throw failure.failureKind()
                    == CapabilityStudioFormalEvidenceBundleCollector.FailureKind.UNAVAILABLE
                    ? unavailable() : invalid();
        } catch (CapabilityStudioFormalEvidenceRunManifest.CompileException failure) {
            throw failure.failureKind()
                    == CapabilityStudioFormalEvidenceRunManifest.FailureKind.UNAVAILABLE
                    ? unavailable() : invalid();
        } catch (CapabilityStudioTypedEvidenceReplayRegistry.ReplayException
                | CapabilityStudioCandidateReplayDeriver.DerivationException failure) {
            throw invalid();
        } catch (VerificationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static Verification project(ReplayRun run) {
        var plan = run.plan();
        var decision = run.decision();
        if (decision.terminal() == CapabilityStudioCandidateReplayDeriver.Terminal.UNAVAILABLE) {
            throw unavailable();
        }
        if (decision.terminal() == CapabilityStudioCandidateReplayDeriver.Terminal.INVALID) {
            throw invalid();
        }
        String level = decision.terminal().name();
        return new Verification(
                level, decision.typedReplayCount(), decision.passed(),
                decision.failed(), decision.blocked(), decision.notRun(),
                decision.evidenceCount(), decision.evidenceByteSize());
    }

    private static CapabilityStudioCandidateReplayDeriver.ClosureOutcome closureOutcome(
            CapabilityStudioFormalEvidenceBundleCollector.CollectorException failure) {
        return failure.failureKind()
                == CapabilityStudioFormalEvidenceBundleCollector.FailureKind.UNAVAILABLE
                ? CapabilityStudioCandidateReplayDeriver.ClosureOutcome.UNAVAILABLE
                : CapabilityStudioCandidateReplayDeriver.ClosureOutcome.INVALID;
    }

    private static CapabilityStudioTypedEvidenceReplayRegistry.Subject subject(
            CapabilityStudioFormalEvidenceBundleCollector.Session session,
            CapabilityStudioFormalEvidenceRunManifest.Compiled plan,
            CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest request) {
        var observation = session.observation();
        var slot = request.slot();
        var entry = (slot.fileSubject()
                ? observation.files() : observation.directories()).get(request.subjectPath());
        if (entry == null) {
            throw invalid();
        }
        String fingerprint = slot.fileSubject()
                ? plan.inventory().get(request.subjectPath()).rawFingerprint() : null;
        return new CapabilityStudioTypedEvidenceReplayRegistry.Subject(
                session.root().resolve(request.subjectPath()).normalize(),
                request.subjectPath(), slot.fileSubject(), entry.size(), fingerprint);
    }

    private static Map<CapabilityStudioTypedEvidenceReplayRegistry.Slot,
            CapabilityStudioCandidateReplayDeriver.ReplayOutcome> initialOutcomes() {
        Map<CapabilityStudioTypedEvidenceReplayRegistry.Slot,
                CapabilityStudioCandidateReplayDeriver.ReplayOutcome> result =
                new EnumMap<>(CapabilityStudioTypedEvidenceReplayRegistry.Slot.class);
        for (var slot : CapabilityStudioTypedEvidenceReplayRegistry.Slot.values()) {
            result.put(slot, CapabilityStudioCandidateReplayDeriver.ReplayOutcome.NOT_RUN);
        }
        return result;
    }

    private static List<CapabilityStudioCandidateReplayDeriver.ReplayOutcome> orderedOutcomes(
            Map<CapabilityStudioTypedEvidenceReplayRegistry.Slot,
                    CapabilityStudioCandidateReplayDeriver.ReplayOutcome> outcomes) {
        List<CapabilityStudioCandidateReplayDeriver.ReplayOutcome> result =
                new ArrayList<>(CapabilityStudioCandidateReplayDeriver.ADAPTER_SLOT_COUNT);
        for (var slot : CapabilityStudioTypedEvidenceReplayRegistry.Slot.values()) {
            result.add(outcomes.get(slot));
        }
        return List.copyOf(result);
    }

    private static CapabilityStudioCandidateReplayDeriver.ReplayOutcome replayOutcome(
            CapabilityStudioTypedEvidenceReplayRegistry.ReplayStatus status) {
        return switch (status) {
            case VERIFIED -> CapabilityStudioCandidateReplayDeriver.ReplayOutcome.VERIFIED;
            case INVALID -> CapabilityStudioCandidateReplayDeriver.ReplayOutcome.INVALID;
            case UNAVAILABLE -> CapabilityStudioCandidateReplayDeriver.ReplayOutcome.UNAVAILABLE;
        };
    }

    private static CapabilityStudioFormalEvidenceBundleCollector.Observer collectorObserver(
            VerificationObserver observer, Path manifestFile) {
        return new CapabilityStudioFormalEvidenceBundleCollector.Observer() {
            @Override
            public void manifestRead(Path ignored) {
                observer.afterInitialManifestRead(manifestFile);
            }

            @Override
            public void beforeInventoryRead(String relativePath, Path file) {
                observer.beforeInventoryRead(relativePath, file);
            }

            @Override
            public void beforeReplay(
                    String id, String relativePath, boolean fileSubject) {
                observer.beforeTypedReplay(id);
            }

            @Override
            public void beforeFinalSnapshot() {
                observer.beforeFinalManifestRead(manifestFile);
            }
        };
    }

    private static VerificationException invalid() {
        return new VerificationException(FailureKind.INVALID);
    }

    private static VerificationException unavailable() {
        return new VerificationException(FailureKind.UNAVAILABLE);
    }
}
