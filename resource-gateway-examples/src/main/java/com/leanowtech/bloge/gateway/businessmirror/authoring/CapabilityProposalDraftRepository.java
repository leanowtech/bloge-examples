package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.List;
import java.util.Optional;

/** Optimistically locked Capability Proposal store with immutable revision history. */
public interface CapabilityProposalDraftRepository {
    Optional<StoredCapabilityProposalDraft> find(CapabilitySnapshot.Scope scope, String proposalId);

    Optional<StoredCapabilityProposalDraft> findRevision(
            CapabilitySnapshot.Scope scope, String proposalId, long revision);

    List<StoredCapabilityProposalDraft> revisions(
            CapabilitySnapshot.Scope scope, String proposalId);

    List<StoredCapabilityProposalDraft> list(
            CapabilitySnapshot.Scope scope, String afterProposalId, int limit);

    Optional<StoredCapabilityProposalDraft> saveIfRevision(
            long expectedRevision, CapabilityProposalDraft candidate, String actor);
}
