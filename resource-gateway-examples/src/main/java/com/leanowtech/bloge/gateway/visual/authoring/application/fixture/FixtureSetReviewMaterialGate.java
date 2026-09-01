package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;

import java.util.List;

/** Reviewer-only boundary that advances protected Fixture Assets to exact ACTIVE revisions. */
@FunctionalInterface
public interface FixtureSetReviewMaterialGate {
    /** Verifies, approves, and activates every exact proposed asset. */
    List<FixtureSetCommand.Material.FixtureAsset> reviewAndActivate(
            Request request, FixtureShareIdentity reviewer);

    /** Fail-closed gate for deployments without governed review capability. */
    static FixtureSetReviewMaterialGate unavailable() {
        return (request, reviewer) -> {
            throw new ApiFixtureSetAuthoringFailure(
                    ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        };
    }

    /** Exact proposed assets plus bounded reviewer evidence; contains no material payload. */
    record Request(String reviewRequestId,
                   List<FixtureSetCommand.Material.FixtureAsset> proposedAssets,
                   FixtureReviewCommand.Attestations attestations,
                   String idempotencyKey) {
        public Request {
            proposedAssets = proposedAssets == null ? List.of() : List.copyOf(proposedAssets);
            if (reviewRequestId == null || reviewRequestId.isBlank() || proposedAssets.isEmpty()
                    || attestations == null || idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("Fixture review material request is incomplete");
            }
        }

        @Override public List<FixtureSetCommand.Material.FixtureAsset> proposedAssets() {
            return List.copyOf(proposedAssets);
        }
    }
}
