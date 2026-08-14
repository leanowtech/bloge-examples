package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Server-owned deep-link coordinates for the current Business Mirror workspace. */
public final class BusinessMirrorDeepLinks {
    private BusinessMirrorDeepLinks() {
    }

    public static String packageLink(String packageId, long compilationRevision) {
        return "/business-mirror/?packageId=" + encoded(packageId)
                + "&compilationRevision=" + compilationRevision
                + "&task=capabilities";
    }

    public static String assetLink(
            String packageId, long compilationRevision, BusinessAssetRef asset) {
        BusinessAssetRef exact = java.util.Objects.requireNonNull(asset, "asset");
        return packageLink(packageId, compilationRevision)
                + "&assetKind=" + encoded(exact.kind().name())
                + "&assetId=" + encoded(exact.id())
                + "&assetRevision=" + exact.revision()
                + "&assetAuthority=" + encoded(exact.authority());
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
