package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;

/** Payload-free executable fragment identity admitted into a draft world. */
public record WorldDraftFragmentRef(BlogeFragmentRef blogeFragment) {
    public WorldDraftFragmentRef {
        if (blogeFragment == null) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
    }

    public String fingerprint() { return blogeFragment.fingerprint(); }
}
