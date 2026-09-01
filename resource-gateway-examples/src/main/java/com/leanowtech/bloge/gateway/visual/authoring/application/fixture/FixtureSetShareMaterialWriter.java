package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

/** Protected material and governed catalog boundary used by Fixture sharing. */
@FunctionalInterface
public interface FixtureSetShareMaterialWriter {
    /** Persists one Inline Return and returns its asset reference plus exact safe material output. */
    Result write(
            Request request, FixtureShareIdentity identity);

    /** Returns a fail-closed port for deployments without protected material governance. */
    static FixtureSetShareMaterialWriter unavailable() {
        return (request, identity) -> {
            throw new ApiFixtureSetAuthoringFailure(
                    ApiFixtureSetAuthoringFailure.Code.CAPABILITY_UNAVAILABLE);
        };
    }

    /** Exact payload-free asset coordinate and the redacted output safe for derived assertions. */
    record Result(FixtureSetCommand.Material.FixtureAsset material,
                  com.fasterxml.jackson.databind.JsonNode safeOutput) {
        public Result {
            if (material == null || safeOutput == null) {
                throw new IllegalArgumentException("Fixture share material result is incomplete");
            }
            safeOutput = safeOutput.deepCopy();
        }

        @Override public com.fasterxml.jackson.databind.JsonNode safeOutput() {
            return safeOutput.deepCopy();
        }
    }

    /** Exact non-secret coordinates and the only payload allowed across the write boundary. */
    record Request(String fixtureAssetId, FixtureSetView source, int derivedRevision,
                   String reviewRequestId, String caseId, String caseName,
                   SchemaEnvelope outputSchema, FixtureShareCommand.Policy policy,
                   com.fasterxml.jackson.databind.JsonNode payload) {
        public Request {
            if (fixtureAssetId == null || fixtureAssetId.isBlank() || source == null
                    || derivedRevision <= source.revision() || reviewRequestId == null
                    || reviewRequestId.isBlank() || caseId == null || caseId.isBlank()
                    || caseName == null || caseName.isBlank() || outputSchema == null
                    || policy == null || payload == null) {
                throw new IllegalArgumentException("Fixture share material request is incomplete");
            }
            outputSchema = new SchemaEnvelope(
                    outputSchema.format(), outputSchema.version(), outputSchema.schema());
            payload = payload.deepCopy();
        }

        @Override public SchemaEnvelope outputSchema() {
            return new SchemaEnvelope(
                    outputSchema.format(), outputSchema.version(), outputSchema.schema());
        }

        @Override public com.fasterxml.jackson.databind.JsonNode payload() { return payload.deepCopy(); }

        /** Keeps the protected payload out of diagnostics. */
        @Override public String toString() {
            return "Request[fixtureAssetId=" + fixtureAssetId + ", source="
                    + source.fixtureSetId() + "@" + source.revision() + ", caseId=" + caseId
                    + ", payload=protected]";
        }
    }
}
