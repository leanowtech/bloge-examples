package com.leanowtech.bloge.gateway.visual.authoring.link;

import com.leanowtech.bloge.gateway.businessmirror.compilation.BuiltInGraphAssetAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.List;

/** Narrow source-authority port kept separate so the resolver can be truth-table tested. */
public interface AuthoringLinkSourceAuthority {
    List<String> graphNames();

    BuiltInGraphAssetAuthority.Snapshot resolve(
            CapabilitySnapshot.Scope scope, String graphName);
}
