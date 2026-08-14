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
    /** Immutable Business Asset Link Closure v1 wire version. */
    public static final String BUSINESS_ASSET_LINK_CLOSURE_V1 =
            "resourceGateway.businessAssetLinkClosure.v1";
    /** Mutable Domain Capability Package v1 wire version. */
    public static final String DOMAIN_CAPABILITY_PACKAGE_DRAFT_V1 =
            "bloge.domainCapabilityPackageDraft.v1";
    /** Repository-owned stored Package revision v1 wire version. */
    public static final String STORED_DOMAIN_CAPABILITY_PACKAGE_DRAFT_V1 =
            "resourceGateway.storedDomainCapabilityPackageDraft.v1";
    /** Exact idempotent Package save receipt v1 wire version. */
    public static final String DOMAIN_CAPABILITY_PACKAGE_SAVE_RECEIPT_V1 =
            "resourceGateway.domainCapabilityPackageSaveReceipt.v1";
    /** Bounded Package index page v1 wire version. */
    public static final String DOMAIN_CAPABILITY_PACKAGE_PAGE_V1 =
            "resourceGateway.domainCapabilityPackagePage.v1";
    /** Exact idempotent Package compilation receipt v1 wire version. */
    public static final String PACKAGE_COMPILATION_RECEIPT_V1 =
            "resourceGateway.packageCompilationReceipt.v1";
    /** Fail-closed Legacy Graph Package migration projection v1 wire version. */
    public static final String LEGACY_GRAPH_PACKAGE_PROJECTION_V1 =
            "resourceGateway.legacyGraphPackageProjection.v1";
    /** Complete bounded Legacy Graph migration catalog v1 wire version. */
    public static final String LEGACY_GRAPH_PACKAGE_PROJECTION_CATALOG_V1 =
            "resourceGateway.legacyGraphPackageProjectionCatalog.v1";
    /** Immutable compiled Domain Capability Package v1 wire version. */
    public static final String DOMAIN_CAPABILITY_PACKAGE_SNAPSHOT_V1 =
            "resourceGateway.domainCapabilityPackageSnapshot.v1";
    /** Compiler-owned Package readiness report v1 wire version. */
    public static final String PACKAGE_READINESS_REPORT_V1 =
            "resourceGateway.packageReadinessReport.v1";
    /** Mutable Capability Proposal v1 wire version. */
    public static final String CAPABILITY_PROPOSAL_DRAFT_V1 =
            "bloge.capabilityProposalDraft.v1";
    /** Repository-owned stored Capability Proposal revision v1 wire version. */
    public static final String STORED_CAPABILITY_PROPOSAL_DRAFT_V1 =
            "resourceGateway.storedCapabilityProposalDraft.v1";
    /** Exact idempotent Capability Proposal save receipt v1 wire version. */
    public static final String CAPABILITY_PROPOSAL_SAVE_RECEIPT_V1 =
            "resourceGateway.capabilityProposalSaveReceipt.v1";
    /** Bounded Capability Proposal index page v1 wire version. */
    public static final String CAPABILITY_PROPOSAL_PAGE_V1 =
            "resourceGateway.capabilityProposalPage.v1";
    /** Immutable evidence-derived Capability Proposal v1 wire version. */
    public static final String CAPABILITY_PROPOSAL_SNAPSHOT_V1 =
            "resourceGateway.capabilityProposalSnapshot.v1";
    /** Payload-free exact Proposal simulation command v1 wire version. */
    public static final String CAPABILITY_PROPOSAL_SIMULATION_REQUEST_V1 =
            "resourceGateway.capabilityProposalSimulationRequest.v1";
    /** Content-addressed aggregate Proposal simulation evidence v1 wire version. */
    public static final String CAPABILITY_PROPOSAL_SIMULATION_EVIDENCE_V1 =
            "resourceGateway.capabilityProposalSimulationEvidence.v1";
    /** Durable exact Proposal simulation result v1 wire version. */
    public static final String STORED_CAPABILITY_PROPOSAL_SIMULATION_V1 =
            "resourceGateway.storedCapabilityProposalSimulation.v1";
    /** Payload-free implementation-binding command v1 wire version. */
    public static final String CAPABILITY_IMPLEMENTATION_BINDING_REQUEST_V1 =
            "resourceGateway.capabilityImplementationBindingRequest.v1";
    /** Immutable server-attested Proposal implementation binding v1 wire version. */
    public static final String CAPABILITY_IMPLEMENTATION_BINDING_V1 =
            "resourceGateway.capabilityImplementationBinding.v1";
    /** Durable signed implementation-binding result v1 wire version. */
    public static final String STORED_CAPABILITY_IMPLEMENTATION_BINDING_V1 =
            "resourceGateway.storedCapabilityImplementationBinding.v1";

    /** Complete cancellation-fee Package example packaged with the client. */
    public static final String PACKAGE_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "cancellation-fee-package-stage1-v1.fixture.json";
    /** Complete simulation-only cancellation-attribution Proposal example. */
    public static final String PROPOSAL_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "cancellation-attribution-proposal-stage1-v1.fixture.json";
    /** Exact durable Capability Proposal create receipt example. */
    public static final String PROPOSAL_SAVE_RECEIPT_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "cancellation-attribution-proposal-save-receipt-v1.fixture.json";
    /** Exact durable Package create receipt example. */
    public static final String PACKAGE_SAVE_RECEIPT_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "cancellation-fee-package-save-receipt-v1.fixture.json";
    /** Server-produced fail-closed Legacy Graph projection example. */
    public static final String LEGACY_GRAPH_PROJECTION_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "loan-decision-legacy-graph-projection-v1.fixture.json";
    /** Server-produced, signed and payload-free Proposal simulation result example. */
    public static final String PROPOSAL_SIMULATION_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "refund-proposal-simulation-stage1-v1.fixture.json";
    /** Server-produced, signed and payload-free implementation-binding result example. */
    public static final String IMPLEMENTATION_BINDING_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "refund-implementation-binding-stage1-v1.fixture.json";

    static final String BUSINESS_ASSET_LINK_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "business-asset-link-v1.schema.json";
    static final String BUSINESS_ASSET_LINK_CLOSURE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "business-asset-link-closure-v1.schema.json";
    static final String PACKAGE_DRAFT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "domain-capability-package-draft-v1.schema.json";
    static final String STORED_PACKAGE_DRAFT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "stored-domain-capability-package-draft-v1.schema.json";
    static final String PACKAGE_SAVE_RECEIPT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "domain-capability-package-save-receipt-v1.schema.json";
    static final String PACKAGE_PAGE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "domain-capability-package-page-v1.schema.json";
    static final String PACKAGE_COMPILATION_RECEIPT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "package-compilation-receipt-v1.schema.json";
    static final String LEGACY_GRAPH_PROJECTION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "legacy-graph-package-projection-v1.schema.json";
    static final String LEGACY_GRAPH_PROJECTION_CATALOG_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "legacy-graph-package-projection-catalog-v1.schema.json";
    static final String PACKAGE_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "domain-capability-package-snapshot-v1.schema.json";
    static final String PACKAGE_READINESS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "package-readiness-report-v1.schema.json";
    static final String PROPOSAL_DRAFT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-proposal-draft-v1.schema.json";
    static final String STORED_PROPOSAL_DRAFT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "stored-capability-proposal-draft-v1.schema.json";
    static final String PROPOSAL_SAVE_RECEIPT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-proposal-save-receipt-v1.schema.json";
    static final String PROPOSAL_PAGE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-proposal-page-v1.schema.json";
    static final String PROPOSAL_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-proposal-snapshot-v1.schema.json";
    static final String PROPOSAL_SIMULATION_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-proposal-simulation-request-v1.schema.json";
    static final String PROPOSAL_SIMULATION_EVIDENCE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-proposal-simulation-evidence-v1.schema.json";
    static final String STORED_PROPOSAL_SIMULATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "stored-capability-proposal-simulation-v1.schema.json";
    static final String IMPLEMENTATION_BINDING_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-implementation-binding-request-v1.schema.json";
    static final String IMPLEMENTATION_BINDING_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-implementation-binding-v1.schema.json";
    static final String STORED_IMPLEMENTATION_BINDING_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "stored-capability-implementation-binding-v1.schema.json";

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
     * Requires a content-addressed, acyclic and single-Scope Business Asset Link Closure.
     *
     * @param value decoded Business Asset Link Closure
     * @throws IllegalArgumentException when Schema, fingerprint, Scope, link, or cycle checks fail
     */
    public static void requireBusinessAssetLinkClosure(JsonNode value) {
        BusinessMirrorCompilationVerifier.verifyBusinessAssetLinkClosure(value);
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
     * Requires a strict repository-owned stored Package revision v1 value.
     *
     * @param value decoded stored Package revision
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requireStoredPackageDraft(JsonNode value) {
        BusinessMirrorAuthoringVerifier.verifyStoredPackageDraft(value);
    }

    /**
     * Requires a strict exact Package save receipt v1 value.
     *
     * @param value decoded Package save receipt
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requirePackageSaveReceipt(JsonNode value) {
        BusinessMirrorAuthoringVerifier.verifyPackageSaveReceipt(value);
    }

    /**
     * Requires a strict bounded Package index page v1 value.
     *
     * @param value decoded Package index page
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requirePackagePage(JsonNode value) {
        BusinessMirrorAuthoringVerifier.verifyPackagePage(value);
    }

    /**
     * Requires an exact Package compilation receipt whose embedded facts share one identity.
     *
     * @param value decoded Package compilation receipt
     * @throws IllegalArgumentException when Schema, fingerprint, or fact alignment checks fail
     */
    public static void requirePackageCompilationReceipt(JsonNode value) {
        BusinessMirrorCompilationVerifier.verifyPackageCompilationReceipt(value);
    }

    /**
     * Requires a sealed Legacy Graph migration projection with complete fail-closed gaps.
     *
     * @param value decoded Legacy Graph Package projection
     * @throws IllegalArgumentException when Schema, source binding, gap, trust, or fingerprint checks fail
     */
    public static void requireLegacyGraphPackageProjection(JsonNode value) {
        BusinessMirrorLegacyMigrationVerifier.verifyProjection(value);
    }

    /**
     * Requires a graph-name ordered, single-Scope Legacy Graph projection catalog.
     *
     * @param value decoded Legacy Graph projection catalog
     * @throws IllegalArgumentException when catalog or nested projection verification fails
     */
    public static void requireLegacyGraphPackageProjectionCatalog(JsonNode value) {
        BusinessMirrorLegacyMigrationVerifier.verifyCatalog(value);
    }

    /**
     * Requires a strict immutable Domain Capability Package Snapshot v1 value.
     *
     * @param value decoded Package Snapshot
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requirePackageSnapshot(JsonNode value) {
        BusinessMirrorCompilationVerifier.verifyPackageSnapshot(value);
    }

    /**
     * Requires a strict compiler-owned Package Readiness Report v1 value.
     *
     * @param value decoded Package Readiness Report
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requirePackageReadinessReport(JsonNode value) {
        BusinessMirrorCompilationVerifier.verifyPackageReadinessReport(value);
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
     * Requires a content-addressed repository-owned Capability Proposal revision.
     *
     * @param value decoded stored Capability Proposal
     * @throws IllegalArgumentException when Schema, fingerprint, Scope, or time checks fail
     */
    public static void requireStoredProposalDraft(JsonNode value) {
        BusinessMirrorAuthoringVerifier.verifyStoredProposalDraft(value);
    }

    /**
     * Requires an exact durable Capability Proposal save receipt.
     *
     * @param value decoded Capability Proposal save receipt
     * @throws IllegalArgumentException when Schema or nested semantic verification fails
     */
    public static void requireProposalSaveReceipt(JsonNode value) {
        BusinessMirrorAuthoringVerifier.verifyProposalSaveReceipt(value);
    }

    /**
     * Requires a bounded, ordered, single-Scope Capability Proposal page.
     *
     * @param value decoded Capability Proposal page
     * @throws IllegalArgumentException when Schema, order, Scope, or cursor checks fail
     */
    public static void requireProposalPage(JsonNode value) {
        BusinessMirrorAuthoringVerifier.verifyProposalPage(value);
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

    /**
     * Requires a strict payload-free exact Proposal simulation command.
     *
     * @param value decoded simulation command
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requireProposalSimulationRequest(JsonNode value) {
        BusinessMirrorSimulationVerifier.verifyRequest(value);
    }

    /**
     * Requires content-addressed Proposal simulation aggregate evidence.
     *
     * @param value decoded aggregate evidence
     * @throws IllegalArgumentException when Schema, fingerprint, order, or coverage checks fail
     */
    public static void requireProposalSimulationEvidence(JsonNode value) {
        BusinessMirrorSimulationVerifier.verifyEvidence(value);
    }

    /**
     * Requires a durable simulation result with a complete Proposal identity closure.
     *
     * @param value decoded stored simulation result
     * @throws IllegalArgumentException when Schema, content, state, or identity checks fail
     */
    public static void requireStoredProposalSimulation(JsonNode value) {
        BusinessMirrorSimulationVerifier.verifyStoredSimulation(value);
    }

    /**
     * Requires a strict exact implementation-binding command.
     *
     * @param value decoded binding command
     * @throws IllegalArgumentException when the value violates the packaged protocol
     */
    public static void requireImplementationBindingRequest(JsonNode value) {
        BusinessMirrorImplementationVerifier.verifyRequest(value);
    }

    /**
     * Requires a content-addressed, read-only, stateless implementation binding.
     *
     * @param value decoded implementation binding
     * @throws IllegalArgumentException when Schema, content address, Scope, region, or time fails
     */
    public static void requireImplementationBinding(JsonNode value) {
        BusinessMirrorImplementationVerifier.verifyBinding(value);
    }

    /**
     * Requires a durable implementation binding with exact detached-attestation material binding.
     *
     * @param value decoded stored implementation binding
     * @throws IllegalArgumentException when Schema, fingerprint, or attestation closure fails
     */
    public static void requireStoredImplementationBinding(JsonNode value) {
        BusinessMirrorImplementationVerifier.verifyStoredBinding(value);
    }

    private static void require(JsonNode value, String schema, String failureCode) {
        BusinessMirrorSchemaValidator.require(value, schema,
                "RG.BUSINESS_MIRROR.CLIENT." + failureCode);
    }
}
