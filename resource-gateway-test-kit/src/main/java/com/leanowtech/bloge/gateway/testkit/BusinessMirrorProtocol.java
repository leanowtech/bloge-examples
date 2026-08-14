package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Public wire constants and strict offline validation entry points for Business Mirror v1.
 *
 * <p>The API has no Resource Gateway server or Spring dependency. Governance adapters, build
 * plugins, and customer repositories can therefore reject malformed Package and Proposal values
 * before attempting a network integration.</p>
 */
public final class BusinessMirrorProtocol {
    /** Business Mirror schema resource root packaged in the test-kit JAR. */
    public static final String SCHEMA_RESOURCE_ROOT =
            "/schemas/resource-gateway-business-mirror/";
    /** Business Asset Link v1 wire version. */
    public static final String BUSINESS_ASSET_LINK_V1 =
            "resourceGateway.businessAssetLink.v1";
    /** Mutable Domain Capability Package v1 wire version. */
    public static final String DOMAIN_CAPABILITY_PACKAGE_DRAFT_V1 =
            "bloge.domainCapabilityPackageDraft.v1";
    /** Immutable compiled Domain Capability Package v1 wire version. */
    public static final String DOMAIN_CAPABILITY_PACKAGE_SNAPSHOT_V1 =
            "resourceGateway.domainCapabilityPackageSnapshot.v1";
    /** Compiler-owned Package readiness report v1 wire version. */
    public static final String PACKAGE_READINESS_REPORT_V1 =
            "resourceGateway.packageReadinessReport.v1";
    /** Mutable Capability Proposal v1 wire version. */
    public static final String CAPABILITY_PROPOSAL_DRAFT_V1 =
            "bloge.capabilityProposalDraft.v1";
    /** Immutable evidence-derived Capability Proposal v1 wire version. */
    public static final String CAPABILITY_PROPOSAL_SNAPSHOT_V1 =
            "resourceGateway.capabilityProposalSnapshot.v1";

    /** Complete cancellation-fee Package example packaged with the client. */
    public static final String PACKAGE_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "cancellation-fee-package-stage1-v1.fixture.json";
    /** Complete simulation-only cancellation-attribution Proposal example. */
    public static final String PROPOSAL_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "cancellation-attribution-proposal-stage1-v1.fixture.json";

    static final String BUSINESS_ASSET_LINK_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "business-asset-link-v1.schema.json";
    static final String PACKAGE_DRAFT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "domain-capability-package-draft-v1.schema.json";
    static final String PACKAGE_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "domain-capability-package-snapshot-v1.schema.json";
    static final String PACKAGE_READINESS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "package-readiness-report-v1.schema.json";
    static final String PROPOSAL_DRAFT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-proposal-draft-v1.schema.json";
    static final String PROPOSAL_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-proposal-snapshot-v1.schema.json";

    private BusinessMirrorProtocol() {
    }

    /**
     * Requires a strict Business Asset Link v1 value.
     *
     * @param value decoded Business Asset Link
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requireBusinessAssetLink(JsonNode value) {
        require(value, BUSINESS_ASSET_LINK_SCHEMA_RESOURCE, "BUSINESS_ASSET_LINK_INVALID");
    }

    /**
     * Requires a strict Domain Capability Package Draft v1 value.
     *
     * @param value decoded Package Draft
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requirePackageDraft(JsonNode value) {
        require(value, PACKAGE_DRAFT_SCHEMA_RESOURCE, "PACKAGE_DRAFT_INVALID");
    }

    /**
     * Requires a strict immutable Domain Capability Package Snapshot v1 value.
     *
     * @param value decoded Package Snapshot
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requirePackageSnapshot(JsonNode value) {
        require(value, PACKAGE_SNAPSHOT_SCHEMA_RESOURCE, "PACKAGE_SNAPSHOT_INVALID");
    }

    /**
     * Requires a strict compiler-owned Package Readiness Report v1 value.
     *
     * @param value decoded Package Readiness Report
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requirePackageReadinessReport(JsonNode value) {
        require(value, PACKAGE_READINESS_SCHEMA_RESOURCE, "PACKAGE_READINESS_INVALID");
    }

    /**
     * Requires a strict Capability Proposal Draft v1 value.
     *
     * @param value decoded Proposal Draft
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requireProposalDraft(JsonNode value) {
        require(value, PROPOSAL_DRAFT_SCHEMA_RESOURCE, "PROPOSAL_DRAFT_INVALID");
    }

    /**
     * Requires a strict evidence-derived Capability Proposal Snapshot v1 value.
     *
     * @param value decoded Proposal Snapshot
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requireProposalSnapshot(JsonNode value) {
        require(value, PROPOSAL_SNAPSHOT_SCHEMA_RESOURCE, "PROPOSAL_SNAPSHOT_INVALID");
    }

    private static void require(JsonNode value, String schema, String failureCode) {
        BusinessMirrorSchemaValidator.require(value, schema,
                "RG.BUSINESS_MIRROR.CLIENT." + failureCode);
    }
}
