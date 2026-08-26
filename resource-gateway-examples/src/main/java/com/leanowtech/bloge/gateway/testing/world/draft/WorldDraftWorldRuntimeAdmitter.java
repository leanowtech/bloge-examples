package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;

/** Server-owned admission port for an executable redacted World slice. */
@FunctionalInterface
public interface WorldDraftWorldRuntimeAdmitter {
    ResourceWorldModel admit(ResourceWorldModel draftWorld, WorldDraftRule rule,
                             Object redactedRequest, Object redactedResponse,
                             WorldDraftCandidateService.Access access);
}
