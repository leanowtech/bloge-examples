package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.time.Clock;
import java.util.Map;

/** Conservative local authorities; production deployments replace these with receipt services. */
final class ServerOwnedWorldDraftAuthorities {
    private ServerOwnedWorldDraftAuthorities() { }

    static WorldDraftApprovalAuthority approval(Clock clock) {
        return (candidate, access) -> {
            if (candidate == null || access == null || clock == null) throw invalid();
            String ticket = "server-approval:" + candidate.candidateId() + ":" + candidate.revision();
            return new WorldDraftApproval(candidate.candidateId(), candidate.revision(),
                    candidate.source().fingerprint(), candidate.schemaFingerprint(),
                    candidate.redactionPolicyFingerprint(), ticket, access.actorId(), clock.instant());
        };
    }

    static WorldDraftPublicationAuthority publication() {
        return (candidate, access) -> {
            if (candidate == null || access == null || candidate.materializationFingerprint().isBlank()) throw invalid();
            String ticket = VisualBundleFingerprint.fromMaterial(Map.of(
                    "candidate", candidate.candidateId(), "revision", candidate.revision(),
                    "materialization", candidate.materializationFingerprint(), "actor", access.actorId()));
            return new WorldDraftPublicationReceipt(candidate.candidateId(), candidate.revision(),
                    candidate.materializationFingerprint(), "server-publication:" + ticket);
        };
    }

    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.APPROVAL_INVALID);
    }
}
