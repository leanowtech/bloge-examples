package com.leanowtech.bloge.gateway.visual;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight behavioral coverage for browser-side visual authoring helpers.
 */
class VisualAuthoringAppJsTest {

    @Test
    void rendersSchemaArrayIndexPathsAsBracketDslReferences() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-app-js-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, bracketPathProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("browser bracket path probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void supportsBrowserUnionSchemaHeuristics() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-union-app-js-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, unionSchemaProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("browser union schema probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void browserSchemaCompatibilityHintsRejectDynamicOptionalPropertyCollisions() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-schema-compat-app-js-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, schemaCompatibilityProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("browser schema compatibility probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void browserRunActionBlocksDesignPublicationsBeforeFetch() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-publication-run-app-js-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, designPublicationRunGuardProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("browser design publication run guard probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void browserDraftCompileAndRunHonorServerActionReadinessBeforeFetch() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-draft-action-readiness-app-js-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, draftActionReadinessGuardProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("browser draft action readiness guard probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void keepsServerPreflightAuthoritativeForLocallyRejectedConnections() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("connectionServerPreflightMessage(")
                .contains("connectionCandidatePreview: null")
                .contains("startConnectionCandidatePreview(source);")
                .contains("fetch('/api/visual/connections/candidates'")
                .contains("targetNodeId: options.targetNodeId || target.nodeId || ''")
                .contains("targetSurface: options.targetSurface || (target.nodeId ? connectionCandidateTargetSurface(target) : 'canvas')")
                .contains("targetPort: options.targetPort || target.port || ''")
                .contains("targetPath: options.targetPath || target.path || ''")
                .contains("normalizeConnectionCandidateExplanation(candidate?.explanation")
                .contains("targetRuntimeBinding: normalizeConnectionCandidateRuntimeBindingImpact(explanation?.targetRuntimeBinding")
                .contains("normalizeConnectionCandidatesResult(payload, source, requestBody)")
                .contains("connectionRuntimeBindingSummary(summary)")
                .contains("connectionCandidateTargetRuntimeBindingSummary(explanation?.targetRuntimeBinding)")
                .contains("ensureConnectionCandidatePreviewForTarget(drag.source, target);")
                .contains("const requestKey = connectionCandidatePreviewRequestKey(source, kind, target);")
                .contains("connectionCandidatePreviewCoversTarget(preview.result, target)")
                .contains("connectionDragTargetDecision(state.connectionDrag.source, handle)")
                .contains("Asking server for final decision...")
                .contains("Server validation is authoritative.");
        assertThat(source)
                .doesNotContain("if (!compatibility.ok) {\n        setConnectionMessage(compatibility.message, 'error');")
                .doesNotContain("if (!compatibility.ok) {\n          setConnectionMessage(compatibility.message, 'error');")
                .doesNotContain("const disabled = candidate.compatibility.ok ? '' : ' disabled';");
    }

    @Test
    void selectedInspectorCanRefreshSingleOperatorDefinition() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("operatorDefinitionMessagesByRef: {}")
                .contains("operatorDefinitionLoadingRef: ''")
                .contains("function operatorDefinitionUrl(operatorRef, builder = state.builder, options = {})")
                .contains("params.set('includeProjections', 'true')")
                .contains("return `/api/visual/operators/${encodeURIComponent(operatorRef)}?${params.toString()}`")
                .contains("async function loadVisualOperatorDefinition(operatorRef, options = {})")
                .contains("includeProjections: options.includeProjections !== false")
                .contains("fetch(operatorDefinitionUrl(normalized, state.builder, query))")
                .contains("const detail = normalizeVisualOperatorDetailPayload(payload)")
                .contains("Operator hidden in active catalog. Retrying with deprecated visibility...")
                .contains("rememberOperatorProjections(")
                .contains("rememberCatalogOperator(operator, {")
                .contains("Operator definition refreshed.")
                .contains("function operatorDefinitionRefForNode(node)")
                .contains("data-load-operator-definition")
                .contains("loadVisualOperatorDefinition(button.dataset.loadOperatorDefinition)")
                .contains("data-open-operator-projection-action")
                .contains("openOperatorProjectionAction(");
    }

    @Test
    void operatorDetailProjectionEnvelopeFeedsInspectorReadiness() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-operator-detail-projection-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, operatorDetailProjectionProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("browser operator detail projection probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void operatorPaletteUsesServerCatalogWindow() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("visualOperatorCatalogWindow: null")
                .contains("visualOperatorFitCatalog: null")
                .contains("let visualOperatorCatalogRequestSeq = 0")
                .contains("let operatorPaletteReloadTimer = null")
                .contains("paletteFitSelectedOutput: false")
                .contains("paletteOffset: 0")
                .contains("paletteLimit: 24")
                .contains("function operatorPaletteCatalogQueryOptions()")
                .contains("function operatorPaletteFitSourceEndpoint()")
                .contains("fetch('/api/visual/operators/fit-candidates'")
                .contains("operatorFitCandidateMap(payload.fitCandidates)")
                .contains("operatorFitCandidate: operatorFitCandidatesByRef[operator?.operatorRef || ''] || null")
                .contains("function operatorPaletteFitBadge(spec)")
                .contains("function addBuilderNodeFromPalette(type, position = null)")
                .contains("const fitSource = operatorPaletteFitSourceEndpoint();")
                .contains("void applyPaletteFitConnection(node, fitSource, fitCandidate);")
                .contains("function applyPaletteFitConnection(node, sourceEndpoint, fitCandidate)")
                .contains("function operatorPaletteFitSourceHandle(sourceEndpoint)")
                .contains("function operatorPaletteFitTargetForNode(node, fitCandidate)")
                .contains("Checking recommended connection with server...")
                .contains("id=\"operator-palette-fit-selected\"")
                .contains("Fit selected output")
                .contains("itemLimit: state.paletteLimit")
                .contains("offset: state.paletteOffset")
                .contains("function normalizeOperatorCatalogWindow(payload, operators = [])")
                .contains("function normalizeOperatorFitCatalog(payload, source)")
                .contains("function markOperatorPaletteLoading()")
                .contains("function scheduleOperatorPaletteReloadFromFirstPage()")
                .contains("Loading catalog window...")
                .contains("state.visualOperatorCatalogWindow = normalizeOperatorCatalogWindow(payload, operators)")
                .contains("if (!isLatestRequest()) {\n      return;\n    }")
                .contains("operator-palette-limit")
                .contains("operator-palette-prev")
                .contains("operator-palette-next")
                .contains("params.set('itemLimit', String(itemLimit))")
                .contains("params.set('offset', String(offset))")
                .contains("loadVisualOperatorDefinition(operatorRef, {\n        paletteVisible: false,\n        render: false,\n        silent: true\n      })")
                .doesNotContain("fetch(operatorCatalogUrl(state.builder, { includeDeprecated: true }))");
    }

    @Test
    void paletteFitAddAutoConnectsThroughServerCheck() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-palette-fit-connect-app-js-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, paletteFitAutoConnectProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("browser palette fit auto-connect probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void surfacesPortablePublicationBundlesInBrowserControls() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("publicationBundleText: ''")
                .contains("id=\"export-publication\"")
                .contains("id=\"validate-publication-bundle\"")
                .contains("id=\"import-publication\"")
                .contains("id=\"publication-bundle-json\"")
                .contains("exportButton.onclick = exportSelectedPublication;")
                .contains("validateButton.onclick = validatePublicationBundle;")
                .contains("importButton.onclick = importPublicationBundle;")
                .contains("async function exportSelectedPublication()")
                .contains("async function validatePublicationBundle()")
                .contains("async function importPublicationBundle()")
                .contains("/api/visual/publications/${encodeURIComponent(publication.publicationId)}/export")
                .contains("fetch('/api/visual/publications/validate-bundle'")
                .contains("fetch('/api/visual/publications/import-bundle'")
                .contains("publicationExport: payload")
                .contains("publicationBundleValidation: payload")
                .contains("publicationImport: payload")
                .contains("const targetReview = publicationImportTargetReviewText(payload?.targetDependencyReport);")
                .contains("payload?.targetRuntimeBindingRequirements || importedPublication?.validation?.readiness?.runtimeBindingRequirements")
                .contains("const bindingReview = runtimeBindingRequirementImportReviewText(")
                .contains("function publicationImportTargetReviewText(report)")
                .contains("function runtimeBindingRequirementImportReviewText(requirements)")
                .contains("Target review: all frozen operator dependencies are available.");
    }

    @Test
    void keepsVisualReadinessAcrossCompileRunAndConnectionPreflightStatusUpdates() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("id=\"visual-readiness-panel\"")
                .contains("id=\"visual-asset-overview\"")
                .contains("id=\"draft-asset-summary\"")
                .contains("id=\"publication-asset-summary\"")
                .contains("visualAssetOverview: null")
                .contains("activeVisualAssetAction: null")
                .contains("visualAssetOverviewActionQuery: {")
                .contains("focusedOperatorRef: ''")
                .contains("actionReadiness: null")
                .contains("publicationSummaries: []")
                .contains("publicationDetailsById: {}")
                .contains("fetch(visualAssetOverviewUrl())")
                .contains("function visualAssetOverviewUrl(builder = state.builder)")
                .contains("function normalizeVisualAssetActionQuery(query = {})")
                .contains("async function updateVisualAssetActionQuery(patch = {})")
                .contains("params.set('actionLimit', String(actionQuery.limit))")
                .contains("params.set('actionOffset', String(actionQuery.offset))")
                .contains("params.set('actionSeverity', actionQuery.severity)")
                .contains("params.set('actionType', actionQuery.actionType)")
                .contains("params.set('actionTargetKind', actionQuery.targetKind)")
                .contains("params.set('actionOperatorRef', actionQuery.operatorRef)")
                .contains("params.set('actionOperatorLibraryId', actionQuery.operatorLibraryId)")
                .contains("params.set('actionHandoffLane', actionQuery.handoffLane)")
                .contains("params.set('actionHandoffKind', actionQuery.handoffKind)")
                .contains("params.set('actionHandoffTarget', actionQuery.handoffTarget)")
                .contains("params.set('actionReadinessState', actionQuery.readinessState)")
                .contains("params.set('actionArtifactKind', actionQuery.artifactKind)")
                .contains("params.set('actionEvidenceKind', actionQuery.evidenceKind)")
                .contains("params.set('actionEvidenceId', actionQuery.evidenceId)")
                .contains("params.set('actionBindingId', actionQuery.bindingId)")
                .contains("params.set('actionActivationId', actionQuery.activationId)")
                .contains("params.set('actionAdapterKind', actionQuery.adapterKind)")
                .contains("params.set('actionRuntimeEnvironment', actionQuery.runtimeEnvironment)")
                .contains("params.set('actionRolloutSignal', actionQuery.rolloutSignal)")
                .contains("operatorRef: String(query.operatorRef || '').trim()")
                .contains("operatorLibraryId: String(query.operatorLibraryId || '').trim()")
                .contains("handoffLane: String(query.handoffLane || '').trim().toLowerCase()")
                .contains("handoffKind: String(query.handoffKind || '').trim().toLowerCase()")
                .contains("handoffTarget: String(query.handoffTarget || '').trim()")
                .contains("readinessState: String(query.readinessState || '').trim().toLowerCase()")
                .contains("artifactKind: normalizePublicationArtifactKindFilter(query.artifactKind)")
                .contains("evidenceKind: String(query.evidenceKind || '').trim().toLowerCase()")
                .contains("evidenceId: String(query.evidenceId || '').trim()")
                .contains("bindingId: String(query.bindingId || '').trim()")
                .contains("activationId: String(query.activationId || '').trim()")
                .contains("adapterKind: String(query.adapterKind || '').trim().toLowerCase()")
                .contains("runtimeEnvironment: String(query.runtimeEnvironment || '').trim()")
                .contains("rolloutSignal: String(query.rolloutSignal || '').trim().toLowerCase()")
                .contains("function scopedVisualAuthoringUrl(path, builder = state.builder)")
                .contains("return `${path}?${params.toString()}`")
                .contains("function renderVisualAssetOverview()")
                .contains("function visualAssetOverviewScopeLabel(overview)")
                .contains("const runtimeEvidence = overview.runtimeEvidence || {};")
                .contains("function visualAssetOverviewRuntimeEvidenceRecordCount(runtimeEvidence, runtimeEvidenceRows)")
                .contains("function visualAssetOverviewRuntimeEvidenceHealth(runtimeEvidence, key, fallback = 0)")
                .contains("runtimeEvidence?.evidenceChainHealthCounts || {}")
                .contains("function visualAssetOverviewCountSum(counts)")
                .contains("drafts.schemaBreakingDriftCount")
                .contains("drafts.schemaCompatibleDriftCount")
                .contains("publications.schemaBreakingDriftCount")
                .contains("publications.schemaCompatibleDriftCount")
                .contains("Draft schema breaking drift")
                .contains("Publication schema review drift")
                .contains("runtimeEvidence.breachedRolloutSignalCounts")
                .contains("structured rollout guardrail signals breached")
                .contains("signals breached")
                .contains("function visualAssetOverviewActionControls(actionQueue)")
                .contains("function attachVisualAssetOverviewActionQueryHandlers(actionQueue)")
                .contains("function visualAssetOverviewActionRows(actionQueue)")
                .contains("function visualAssetActionContext(item)")
                .contains("function openVisualAssetAction(index, actionKey = '')")
                .contains("async function openVisualRuntimeEvidenceAction(item, context = null)")
                .contains("function visualRuntimeEvidenceTargetParts(targetId = '')")
                .contains("function findVisualRuntimeEvidenceRow(target)")
                .contains("function visualRuntimeEvidenceTargetKindMatches(rowKind, targetKind)")
                .contains("function focusOperatorPaletteRef(operatorRef, context = null)")
                .contains("function renderOperatorPaletteDetail()")
                .contains("function operatorPaletteDetailRows(spec)")
                .contains("function operatorPaletteDetailPortSummary(ports)")
                .contains("draggable=\"true\"")
                .contains("role=\"group\"")
                .contains("data-operator-detail")
                .contains("data-operator-add")
                .contains("event.target?.closest?.('button')")
                .contains("void focusOperatorPaletteRef(card.dataset.operatorType)")
                .contains("addBuilderNodeFromPalette(button.dataset.operatorAdd)")
                .contains("state.focusedOperatorRef = normalized")
                .contains("await loadVisualOperatorDefinition(normalized, { render: false })")
                .contains("const focusedRolloutSignal = row.kind === 'rollout-observation' && breachedSignals.length")
                .contains("breachedOnly: Boolean(focusedRolloutSignal)")
                .contains("['runtime-evidence', 'Runtime evidence']")
                .contains("['REVIEW_RUNTIME_ROLLOUT_RISK', 'Review rollout risk']")
                .contains("case 'runtime-evidence':")
                .contains("state.visualRuntimeEvidenceQuery = normalizeVisualRuntimeEvidenceQuery({")
                .contains("state.visualRuntimeBindingImplementationQuery = normalizeVisualRuntimeBindingImplementationQuery({")
                .contains("data-visual-asset-action")
                .contains("data-visual-asset-action-key")
                .contains("data-visual-asset-action-target-kind")
                .contains("data-visual-asset-action-target-id")
                .contains("id=\"operator-palette-detail\"")
                .contains("data-add-focused-operator")
                .contains("data-refresh-focused-operator")
                .contains("data-close-operator-detail")
                .contains("candidate?.actionKey === actionKey")
                .contains("Workspace Asset Overview")
                .contains("Authoring Scope")
                .contains("Action Queue")
                .contains("visual-asset-action-severity")
                .contains("visual-asset-action-type")
                .contains("visual-asset-action-target-kind")
                .contains("visual-asset-action-operator-library-id")
                .contains("actionQueue?.operatorLibraryIdCounts")
                .contains("visual-asset-action-operator-ref")
                .contains("visual-asset-action-handoff-lane")
                .contains("actionQueue?.handoffLaneCounts")
                .contains("visual-asset-action-handoff-kind")
                .contains("actionQueue?.handoffKindCounts")
                .contains("visual-asset-action-handoff-target")
                .contains("actionQueue?.handoffTargetCounts")
                .contains("visual-asset-action-readiness-state")
                .contains("actionQueue?.readinessStateCounts")
                .contains("visual-asset-action-artifact-kind")
                .contains("actionQueue?.artifactKindCounts")
                .contains("visual-asset-action-evidence-kind")
                .contains("actionQueue?.evidenceKindCounts")
                .contains("visual-asset-action-evidence-id")
                .contains("actionQueue?.evidenceIdCounts")
                .contains("visual-asset-action-binding-id")
                .contains("actionQueue?.bindingIdCounts")
                .contains("visual-asset-action-activation-id")
                .contains("actionQueue?.activationIdCounts")
                .contains("visual-asset-action-adapter-kind")
                .contains("actionQueue?.adapterKindCounts")
                .contains("visual-asset-action-runtime-environment")
                .contains("actionQueue?.runtimeEnvironmentCounts")
                .contains("visual-asset-action-rollout-signal")
                .contains("actionQueue?.rolloutSignalCounts")
                .contains("visual-asset-action-limit")
                .contains("visual-asset-action-prev")
                .contains("visual-asset-action-next")
                .contains("visual-asset-action-reset")
                .contains("Current action")
                .contains("Opened overview action:")
                .contains("suggested actions")
                .contains("Complete runtime evidence chains")
                .contains("Partial runtime evidence chains")
                .contains("Failed runtime evidence records")
                .contains("Server-derived readiness across drafts, publications, catalog, and runtime evidence.")
                .contains("function renderVisualReadinessPanel(target, readiness)")
                .contains("function visualActionReadinessRows(actionReadiness)")
                .contains("function renderDraftAssetSummary()")
                .contains("Draft Asset Index")
                .contains("Server-derived draft readiness is visible before loading a draft.")
                .contains("function renderPublicationAssetSummary()")
                .contains("Published Artifact Index")
                .contains("Frozen publication readiness is visible before selecting an artifact.")
                .contains("fetch(visualPublicationSummariesUrl())")
                .contains("function visualPublicationSummariesUrl(builder = state.builder)")
                .contains("publication-artifact-filter")
                .contains("publicationArtifactKindFilter")
                .contains("normalizePublicationArtifactKindFilter")
                .contains("ensurePublicationArtifactVisible")
                .contains("artifactKind=${encodeURIComponent(artifactKind)}")
                .contains("async function loadSelectedPublicationDetails(options = {})")
                .contains("async function loadPublicationDetails(publicationId)")
                .contains("state.publicationDetailsById[publication.publicationId] = publication")
                .contains("Design Artifact Path")
                .contains("Save, export, and publish as DESIGN.")
                .contains("Compile, Run, and EXECUTABLE publish require executable runtime binding.")
                .contains("function visualDraftExecutableActionState(")
                .contains("function normalizeVisualGraphActionReadiness(actionReadiness)")
                .contains("bloge.visualGraphActionReadiness.v1")
                .contains("payload.actionReadiness")
                .contains("payload.validation?.actionReadiness")
                .contains("Publish EXECUTABLE after ackWarnings plus actor/reason.")
                .contains("Publish DESIGN after ackWarnings plus actor/reason.")
                .contains("function composerDslUsesVisualDraft()")
                .contains("function renderExecutableAuthoringControls()")
                .contains("compileButton.disabled = compileState.disabled;")
                .contains("runButton.disabled = usesVisualDraft && runState.disabled;")
                .contains("renderExecutableAuthoringControls();")
                .contains("This graph can be saved, exported, or published as a Design artifact, but it cannot be compiled or run.")
                .contains("status: 'not_executable'")
                .contains("const readinessBeforeCompile = state.visualCheck?.readiness || null;")
                .contains("const actionReadinessBeforeCompile = state.visualCheck?.actionReadiness || null;")
                .contains("const executableState = visualDraftExecutableActionState(readinessBeforeCompile,")
                .contains("actionReadinessBeforeCompile, 'compile');")
                .contains("const actionReadinessBeforeRun = state.visualCheck?.actionReadiness || null;")
                .contains("const executableState = visualDraftExecutableActionState(readinessBeforeRun, actionReadinessBeforeRun, 'run');")
                .contains("setVisualCheck('Compiling...', 'info', [], readinessBeforeCompile, actionReadinessBeforeCompile);")
                .contains("payload.validation?.readiness || readinessBeforeCompile")
                .contains("payload.validation?.actionReadiness || actionReadinessBeforeCompile")
                .contains("setVisualCheck(error.message, 'error', [], readinessBeforeCompile, actionReadinessBeforeCompile);")
                .contains("const readinessBeforeRun = publicationReadiness(publication) || state.visualCheck?.readiness || null;")
                .contains("payload.validation?.readiness || readinessBeforeRun")
                .contains("payload.validation?.actionReadiness || actionReadinessBeforeRun")
                .contains("setVisualCheck(error.message, 'error', [], readinessBeforeRun, actionReadinessBeforeRun);")
                .contains("const validation = payload.validation || null;")
                .contains("const summary = normalizeConnectionCheckSummary(payload.summary, payload, diagnostics, validation);")
                .contains("const readiness = validation?.readiness || state.visualCheck?.readiness || null;")
                .contains("function normalizeConnectionCheckSummary(summary, payload, diagnostics, validation)")
                .contains("schemaVersion: source.schemaVersion || 'bloge.visualConnectionCheckSummary.v1'")
                .contains("graphStillInvalid: Boolean(graphStillInvalid)")
                .contains("runtimeBindingRequirementCount: numericCount(")
                .contains("runtimeBindingRequirementKeys: normalizeStringArray(source.runtimeBindingRequirementKeys)")
                .contains("bindingKindCounts: normalizeConnectionRuntimeBindingCountMap(")
                .contains("operatorLibraryIdCounts: normalizeConnectionRuntimeBindingCountMap(")
                .contains("replacedInputKeys: normalizeStringArray(source.replacedInputKeys)")
                .contains("replacedEdgeIds: normalizeStringArray(source.replacedEdgeIds)")
                .contains("function connectionRuntimeBindingSummary(summary)")
                .contains("summary?.operatorLibraryIdCounts")
                .contains("function connectionAppliedMessage(source, target, serverCheck = null)")
                .contains("function clearConnectionReplacementInputs(node, summary = null)")
                .contains("applyConnection(source, checkedTarget, serverCheck)")
                .contains("delete node.customInputs[key]")
                .contains("delete node.paramInputs[key]")
                .contains("summary.message ||")
                .contains("summary,")
                .contains("if (diagnostics.length || readiness)")
                .contains("Connection accepted; graph still has validation issues.");
    }

    @Test
    void surfacesWorkspaceRuntimeBindingRequirementsIndex() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("visualRuntimeBindingRequirements: null")
                .contains("visualRuntimeBindingRequirementsMessage: null")
                .contains("visualRuntimeBindingHandoffBundle: null")
                .contains("visualRuntimeBindingHandoffReview: null")
                .contains("visualRuntimeBindingHandoffBundleMessage: null")
                .contains("visualRuntimeBindingImplementations: []")
                .contains("visualRuntimeBindingImplementationsMessage: null")
                .contains("visualRuntimeBindingImplementationMessage: null")
                .contains("visualRuntimeBindingImplementationQuery: {")
                .contains("activeVisualRuntimeBindingImplementation: null")
                .contains("visualRuntimeAdapterActivations: []")
                .contains("visualRuntimeRolloutObservations: []")
                .contains("visualExecutableLoweringIntegrations: []")
                .contains("visualRuntimeEvidenceWindow: null")
                .contains("visualRuntimeEvidenceMessage: null")
                .contains("visualRuntimeEvidenceQuery: {")
                .contains("evidenceKind: ''")
                .contains("operatorLibraryId: ''")
                .contains("rolloutSignal: ''")
                .contains("breachedOnly: false")
                .contains("offset: 0")
                .contains("limit: 12")
                .contains("activeVisualRuntimeEvidence: null")
                .contains("visualRuntimeBindingRequirementQuery: {")
                .contains("activeVisualRuntimeBindingRequirement: null")
                .contains("await loadVisualRuntimeBindingRequirements({ render: false })")
                .contains("await loadVisualRuntimeBindingImplementations({ render: false })")
                .contains("await loadVisualRuntimeEvidenceChains({ render: false })")
                .contains("async function loadVisualRuntimeBindingRequirements(options = {})")
                .contains("async function loadVisualRuntimeBindingImplementations(options = {})")
                .contains("async function loadVisualRuntimeEvidenceChains(options = {})")
                .contains("fetch(visualRuntimeBindingRequirementsUrl())")
                .contains("fetch(visualRuntimeBindingImplementationsUrl())")
                .contains("fetch(visualRuntimeEvidenceWindowUrl())")
                .contains("function visualRuntimeBindingRequirementsUrl(builder = state.builder)")
                .contains("function visualRuntimeBindingHandoffBundleUrl(builder = state.builder)")
                .contains("function visualRuntimeBindingHandoffReviewUrl()")
                .contains("function visualRuntimeBindingImplementationsUrl()")
                .contains("function visualRuntimeBindingImplementationTransitionUrl(bindingId, action)")
                .contains("function visualRuntimeAdapterActivationsUrl()")
                .contains("function visualRuntimeRolloutObservationsUrl()")
                .contains("function visualExecutableLoweringIntegrationsUrl()")
                .contains("function visualRuntimeEvidenceWindowUrl()")
                .contains("function visualRuntimeEvidenceParams(kind = '')")
                .contains("function visualRuntimeBindingRequirementParams(builder = state.builder)")
                .contains("/api/visual/assets/runtime-binding-requirements")
                .contains("/api/visual/assets/runtime-binding-requirements/handoff-bundle")
                .contains("/api/visual/assets/runtime-binding-requirements/handoff-review")
                .contains("/api/visual/assets/runtime-binding-requirements/implementation-bindings")
                .contains("/api/visual/assets/runtime-binding-requirements/adapter-activations")
                .contains("/api/visual/assets/runtime-binding-requirements/rollout-observations")
                .contains("/api/visual/assets/runtime-binding-requirements/executable-lowering-integrations")
                .contains("/api/visual/assets/runtime-binding-requirements/runtime-evidence")
                .contains("params.set('limit', String(query.limit))")
                .contains("params.set('itemLimit', String(query.limit))")
                .contains("params.set('offset', String(query.offset))")
                .contains("params.set('evidenceKind', query.evidenceKind)")
                .contains("params.set('targetKind', query.targetKind)")
                .contains("params.set('artifactKind', query.artifactKind)")
                .contains("params.set('operatorRef', query.operatorRef)")
                .contains("params.set('operatorLibraryId', query.operatorLibraryId)")
                .contains("params.set('lifecycleState', query.lifecycleState)")
                .contains("params.set(kind === 'runtime-evidence' ? 'rolloutState' : 'state', query.rolloutState)")
                .contains("params.set('rolloutSignal', query.rolloutSignal)")
                .contains("params.set('breachedOnly', 'true')")
                .contains("params.set('bindingKind', query.bindingKind)")
                .contains("params.set('handoffLane', query.handoffLane)")
                .contains("params.set('handoffKind', query.handoffKind)")
                .contains("params.set('handoffTarget', query.handoffTarget)")
                .contains("params.set('sourceKind', query.sourceKind)")
                .contains("params.set('loweringMode', query.loweringMode)")
                .contains("params.set('readinessState', query.readinessState)")
                .contains("params.set('requirementKey', query.requirementKey)")
                .contains("artifactKind: normalizePublicationArtifactKindFilter(query.artifactKind)")
                .contains("operatorRef: String(query.operatorRef || '').trim()")
                .contains("operatorLibraryId: String(query.operatorLibraryId || '').trim()")
                .contains("requirementKey: String(query.requirementKey || '').trim()")
                .contains("function normalizeVisualRuntimeBindingRequirementQuery(query = {})")
                .contains("async function updateVisualRuntimeBindingRequirementQuery(patch = {})")
                .contains("function normalizeVisualRuntimeBindingImplementationQuery(query = {})")
                .contains("async function updateVisualRuntimeBindingImplementationQuery(patch = {})")
                .contains("function normalizeVisualRuntimeEvidenceQuery(query = {})")
                .contains("async function updateVisualRuntimeEvidenceQuery(patch = {})")
                .contains("async function transitionVisualRuntimeBindingImplementation(bindingId, action)")
                .contains("function visualRuntimeBindingImplementationTransitionRequest(binding, action, replacement = null)")
                .contains("expectedRevision: Number(binding?.revision || 0) || 0")
                .contains("expectedReplacementRevision: replacement ? (Number(replacement.revision || 0) || 0) : 0")
                .contains("async function exportVisualRuntimeBindingHandoffBundle()")
                .contains("fetch(visualRuntimeBindingHandoffBundleUrl())")
                .contains("payload?.operatorContracts?.length")
                .contains("operator contract snapshot")
                .contains("runtimeBindingHandoffBundle: payload")
                .contains("async function reviewVisualRuntimeBindingHandoffBundle()")
                .contains("fetch(visualRuntimeBindingHandoffReviewUrl(), {")
                .contains("runtimeBindingHandoffReview: payload")
                .contains("function visualRuntimeBindingHandoffReviewMessage(review)")
                .contains("function visualRuntimeBindingHandoffReviewRows(review)")
                .contains("function visualRuntimeBindingHandoffReviewRoutingSummary(review)")
                .contains("function visualRuntimeBindingHandoffReviewDistributionSummary(distribution)")
                .contains("function visualRuntimeBindingHandoffReviewCountFacetSummary(counts = {}, suffix = '', prettify = true)")
                .contains("function visualRuntimeBindingHandoffReviewCategorySummary(counts = {})")
                .contains("function visualRuntimeBindingHandoffReviewItemLabel(item)")
                .contains("function visualRuntimeBindingHandoffReviewContractItemLabel(item)")
                .contains("function visualRuntimeBindingHandoffReviewItemValue(item)")
                .contains("function visualRuntimeBindingFieldChangeText(change)")
                .contains("function visualRuntimeBindingFieldLabel(value)")
                .contains("Runtime Binding Requirements")
                .contains("Runtime Implementation Bindings")
                .contains("Runtime Evidence Chain")
                .contains("Current runtime binding")
                .contains("Current implementation binding")
                .contains("Current runtime evidence")
                .contains("Runtime binding handoff")
                .contains("Runtime implementation lifecycle")
                .contains("Runtime evidence chain incomplete")
                .contains("Handoff Review Drift Details")
                .contains("sourceBundleFingerprint")
                .contains("Snapshot fingerprint")
                .contains("fieldChangeCategoryCounts")
                .contains("operatorContractFieldChangeCategoryCounts")
                .contains("operatorContractItems")
                .contains("fieldChanges")
                .contains("Runtime binding index unavailable")
                .contains("function visualRuntimeBindingRequirementControls(bindingIndex)")
                .contains("runtime-binding-target-kind")
                .contains("runtime-binding-artifact-kind")
                .contains("bindingIndex?.artifactKindCounts")
                .contains("runtime-binding-operator-library-id")
                .contains("bindingIndex?.operatorLibraryIdCounts")
                .contains("runtime-binding-operator-ref")
                .contains("runtime-binding-kind")
                .contains("runtime-binding-handoff-lane")
                .contains("runtime-binding-handoff-kind")
                .contains("runtime-binding-handoff-target")
                .contains("runtime-binding-source-kind")
                .contains("runtime-binding-lowering-mode")
                .contains("runtime-binding-readiness-state")
                .contains("runtime-binding-limit")
                .contains("runtime-binding-prev")
                .contains("runtime-binding-next")
                .contains("runtime-binding-export")
                .contains("runtime-binding-review")
                .contains("runtime-binding-reset")
                .contains("runtime-binding-implementation-operator-ref")
                .contains("runtime-binding-implementation-state")
                .contains("runtime-binding-implementation-refresh")
                .contains("runtime-binding-implementation-reset")
                .contains("runtime-evidence-kind")
                .contains("runtime-evidence-operator-library-id")
                .contains("evidenceWindow?.operatorLibraryIdCounts")
                .contains("runtime-evidence-operator-ref")
                .contains("runtime-evidence-binding-id")
                .contains("runtime-evidence-activation-id")
                .contains("runtime-evidence-lifecycle-state")
                .contains("runtime-evidence-rollout-state")
                .contains("runtime-evidence-rollout-signal")
                .contains("runtime-evidence-breached-only")
                .contains("runtime-evidence-limit")
                .contains("runtime-evidence-prev")
                .contains("runtime-evidence-next")
                .contains("runtime-evidence-refresh")
                .contains("runtime-evidence-reset")
                .contains("function visualRuntimeBindingRequirementRows(bindingIndex)")
                .contains("function visualRuntimeBindingImplementationRows(bindings)")
                .contains("function visualRuntimeEvidenceRows(implementationBindings = [], adapterActivations = [], rolloutObservations = [], loweringIntegrations = [])")
                .contains("function runtimeEvidenceOperatorLibraryId(operatorRef)")
                .contains("function visualRuntimeEvidenceImplementationBindingRow(binding, index)")
                .contains("function visualRuntimeAdapterActivationRow(activation, index)")
                .contains("function visualRuntimeRolloutObservationRow(observation, index)")
                .contains("function visualExecutableLoweringIntegrationRow(integration, index)")
                .contains("function visualRuntimeEvidenceControls(rows)")
                .contains("function visualRuntimeEvidenceSignalCounts(rows, breachedOnly = false)")
                .contains("function visualRuntimeEvidenceContext(row)")
                .contains("function visualRuntimeBindingImplementationControls(bindings)")
                .contains("function visualRuntimeBindingImplementationActions(binding)")
                .contains("function visualRuntimeBindingImplementationContext(binding)")
                .contains("function visualRuntimeBindingRequirementCodeRows(requirements)")
                .contains("function visualRuntimeBindingRawOptionMarkup(emptyLabel, counts = {}, selectedValue = '')")
                .contains("function visualRuntimeBindingRequirementContext(item)")
                .contains("function attachVisualRuntimeBindingImplementationHandlers()")
                .contains("function attachVisualRuntimeBindingImplementationQueryHandlers(bindings)")
                .contains("function attachVisualRuntimeEvidenceHandlers(rows)")
                .contains("function attachVisualRuntimeEvidenceQueryHandlers()")
                .contains("function openVisualRuntimeBindingRequirement(index, requirementKey = '')")
                .contains("data-runtime-binding-requirement")
                .contains("data-runtime-binding-requirement-key")
                .contains("data-runtime-binding-implementation")
                .contains("data-runtime-binding-implementation-action")
                .contains("data-runtime-evidence")
                .contains("data-runtime-evidence-id")
                .contains("Opened runtime binding requirement:")
                .contains("Exported ${displayed} of ${total} runtime binding requirement(s).")
                .contains("Handoff review ${stateLabel}: ${matched} current, ${drifted} drifted, ${missing} missing, ${fresh} new in current window.")
                .contains("New current-window requirements")
                .contains("Drift categories")
                .contains("server-derived row")
                .contains("No matching runtime binding requirements")
                .contains("No matching runtime implementation bindings")
                .contains("No matching runtime evidence records")
                .contains("more runtime binding requirements");
        assertThat(countOccurrences(source, "function visualRuntimeBindingRequirementRows(")).isEqualTo(1);
        assertThat(countOccurrences(source, "function visualRuntimeBindingImplementationRows(")).isEqualTo(1);
        assertThat(countOccurrences(source, "function visualRuntimeEvidenceRows(")).isEqualTo(1);
    }

    @Test
    void rendersRuntimeBindingHandoffReviewDriftDetails() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-handoff-review-app-js-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, runtimeBindingHandoffReviewProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("runtime binding handoff review probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void buildsRuntimeBindingImplementationLifecycleRequestsWithRevisionGuards() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-implementation-binding-app-js-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, runtimeBindingImplementationLifecycleProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("runtime binding implementation lifecycle probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void rendersRuntimeEvidenceChainRows() throws Exception {
        assumeNodeAvailable();

        Path tempDir = Files.createTempDirectory("bloge-runtime-evidence-app-js-test");
        Path appJs = tempDir.resolve("app.js");
        Path probe = tempDir.resolve("probe.js");
        try {
            Files.writeString(appJs, appJsSource(), StandardCharsets.UTF_8);
            Files.writeString(probe, runtimeEvidenceChainProbe(), StandardCharsets.UTF_8);

            ProcessResult result = runProcess(List.of("node", probe.toString(), appJs.toString()), tempDir, 10);

            assertThat(result.finished()).as(result.output()).isTrue();
            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.output()).contains("runtime evidence chain probe passed");
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(appJs);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void surfacesStoredDraftDependencyReportInDraftPanel() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("draftDependencyReport: null")
                .contains("draftSummaries: []")
                .contains("id=\"draft-dependencies\"")
                .contains("fetch(visualDraftsUrl())")
                .contains("fetch(visualDraftHistoryUrl())")
                .contains("fetch(visualDraftSummariesUrl())")
                .contains("function visualDraftSummariesUrl(builder = state.builder)")
                .contains("async function loadDraftDependencies(options = {})")
                .contains("/api/visual/drafts/${encodeURIComponent(state.currentDraftId)}/dependencies")
                .contains("function renderDraftDependencyReport()")
                .contains("Draft Dependencies")
                .contains("operatorLibraryIdCounts")
                .contains("schemaCompatibilityStateCounts")
                .contains("schemaBreakingDriftCount")
                .contains("schemaCompatibleDriftCount")
                .contains("function draftDependencyCountLabel(label, key)")
                .contains("function draftDependencyOperatorLibraryLabel(operatorLibraryId)")
                .contains("function draftDependencySchemaCompatibilityLabel(value)")
                .contains("function draftDependencySchemaIssueLabel(row)")
                .contains("schema breaking")
                .contains("schema compatible")
                .contains("library ${value}")
                .contains("id=\"validate-draft-bundle\"")
                .contains("async function validateDraftBundle()")
                .contains("fetch('/api/visual/drafts/validate-bundle'")
                .contains("bindingTargetNodes")
                .contains("edgeTargetNodes")
                .contains("binding from:")
                .contains("binding to:")
                .contains("id=\"publication-dependencies\"")
                .contains("function renderPublicationDependencyReport()")
                .contains("Frozen Dependencies")
                .contains("Publish-time dependencies were snapshotted with this immutable artifact.")
                .contains("function publicationDependencyOperatorRows(report)")
                .contains("fingerprint drifted")
                .contains("catalog missing")
                .contains("scope mismatch")
                .contains("scopeMismatchOperatorCount")
                .contains("SCOPE_MISMATCH")
                .contains("function draftDependencyPolicyViolationLabel(row)")
                .contains("payload?.dependencyReport")
                .contains("payload?.targetDependencyReport || payload?.dependencyReport")
                .contains("payload?.targetRuntimeBindingRequirements || validation?.readiness?.runtimeBindingRequirements")
                .contains("function draftImportTargetReviewText(report)")
                .contains("Runtime binding handoff:")
                .contains("Target review: all imported draft operator dependencies are available.")
                .contains("data-draft-dependency-node")
                .contains("data-draft-dependency-rebase")
                .contains("focusCanvasNode(button.dataset.draftDependencyNode)")
                .contains("rebaseOperatorFingerprint(button.dataset.draftDependencyRebase)")
                .contains("function draftDependencyCanRebase(row)")
                .contains("!['CATALOG_MISSING', 'SCOPE_MISMATCH'].includes(readiness)")
                .contains("function currentDraftHasUnsavedGraphChanges()")
                .contains("!state.builderHistoryUndo.length")
                .contains("function operatorFingerprintRebaseBlockReason()")
                .contains("function draftLocalEditOperations(baseDraft, nextDraft)")
                .contains("function normalizeDraftForLocalEditGuard(draft)")
                .contains("function refreshSelectedOperatorFingerprintPanel()")
                .contains("data-operator-fingerprint-snapshot-panel")
                .contains("save or reload local changes before rebasing")
                .contains("async function refreshDraftConflictState(payload, options = {})")
                .contains("const reloadBuilder = options.reloadBuilder === true")
                .contains("await refreshDraftConflictState(payload, { reloadBuilder: true })")
                .contains("Review the latest draft dependencies before rebasing.")
                .contains("async function refreshCatalogDependentAuthoringViews()")
                .contains("status.status === 'OPERATOR_MISSING'")
                .contains("restore the operator library first")
                .contains("await loadDraftDependencies({ render: false });")
                .contains("renderDraftDependencyReport();");
    }

    @Test
    void requiresResourceContractWarningAcknowledgementBeforeSaving() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("async function validateResourceContractPayload(contract)")
                .contains("async function discoverOpenApiResourceOperations()")
                .contains("/admin/resource-design-contracts/from-openapi/operations")
                .contains("id=\"resource-contract-operation-select\"")
                .contains("id=\"resource-operation-summary\"")
                .contains("id=\"resource-contract-impact\"")
                .contains("function renderOpenApiOperationSummary(operations, current)")
                .contains("function openApiSelectedOperation(current = state.resourceContractImport)")
                .contains("function openApiOperationIsBlocked(operation)")
                .contains("function openApiBlockedProjectionMessage(current = state.resourceContractImport")
                .contains("OpenAPI projection is blocked:")
                .contains("Select a READY/WARNING operation")
                .contains("openApiProjectionBlocked")
                .contains("function openApiOperationStatusMessage(operation)")
                .contains("function resourceContractSaveConfirmationKey(contract, diagnostics = [])")
                .contains("function resourceContractWarningAcknowledgementMessage(impact, diagnostics, actionLabel = 'Save contract')")
                .contains("renderLibraryImpactPanel($('resource-contract-impact'), diagnostics, current.message?.impact)")
                .contains("current.saveConfirmationKey !== confirmationKey")
                .contains("Review warnings, then click ${actionLabel} again to continue.")
                .contains("payload?.validation?.impact")
                .contains("validation.impact")
                .contains("resourceContractMutationQuery(hasWarningDiagnostic(validation.diagnostics), contract.resourceId)")
                .contains("params.set('ackWarnings', 'true');")
                .contains("params.set('actor', 'visual-canvas');")
                .contains("Warnings reviewed in the visual resource contract panel");
    }

    @Test
    void surfacesOperatorLibraryImpactReviewBeforeImport() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("id=\"library-impact\"")
                .contains("aria-label=\"Operator library JSON or YAML\"")
                .contains("id=\"discover-asyncapi-library\"")
                .contains("id=\"asyncapi-operation-select\"")
                .contains("multiple size=\"4\"")
                .contains("id=\"asyncapi-operation-summary\"")
                .contains("id=\"project-asyncapi-library\"")
                .contains("id=\"asyncapi-projection-review\"")
                .contains("async function validateOperatorLibraryTextPayload(sourceText)")
                .contains("/admin/visual-operator-libraries/validate-text${libraryForceQuery()}")
                .contains("/admin/visual-operator-libraries/import-text${mutationQuery}")
                .contains("async function discoverAsyncApiOperatorOperations()")
                .contains("/admin/visual-operator-libraries/from-asyncapi/operations")
                .contains("function asyncApiProjectionRequest(sourceText)")
                .contains("request.selections = selections")
                .contains("function applyAsyncApiOperationSelections(operations)")
                .contains("function normalizeAsyncApiSelections(selections)")
                .contains("function asyncApiOperationIsBlocked(operation)")
                .contains("function asyncApiSelectedOperations(operations)")
                .contains("function asyncApiBlockedProjectionMessage(operations)")
                .contains("AsyncAPI projection is blocked:")
                .contains("Select only READY/WARNING operations")
                .contains("function asyncApiProjectionAuditMessage(payload)")
                .contains("payload?.projectionReview")
                .contains("payload?.importReadiness")
                .contains("validation?.importReadiness")
                .contains("payload?.availableOperations")
                .contains("payload?.selectedOperations")
                .contains("payload?.omittedOperationCount")
                .contains("hasHeaders: Boolean(operation?.hasHeaders)")
                .contains("headersType: String(operation?.headersType || 'opaque')")
                .contains("function asyncApiOperationSchemaLabel(operation)")
                .contains("headers ${operation.headersType || 'opaque'}")
                .contains("with headers")
                .contains("review.selectedOperationCount")
                .contains("review.unmatchedSelectionCount")
                .contains("selector${unmatched === 1 ? '' : 's'} unmatched")
                .contains("function renderAsyncApiProjectionReviewPanel(target, review)")
                .contains("AsyncAPI Projection Review")
                .contains("availableProjectionLevelCounts")
                .contains("selectedSourceKindCounts")
                .contains("unmatched selector")
                .contains("omitted operation")
                .contains("function asyncApiProjectionOperationLabel(operation)")
                .contains("projectionReview: projectionReview || null")
                .contains("importReadiness: importReadiness || null")
                .contains("renderAsyncApiProjectionReviewPanel($('asyncapi-projection-review'), state.libraryMessage?.projectionReview)")
                .contains("function renderLibraryImportReadiness(readiness)")
                .contains("function renderLibraryImportReadinessHandoffGroups(groups)")
                .contains("function renderLibraryImportReadinessCountRows(readiness)")
                .contains("function libraryImportReadinessCountRows(label, counts, valueLabel = operatorPaletteFacetLabel)")
                .contains("function normalizeOperatorLibraryImportReadiness(importReadiness)")
                .contains("function normalizeOperatorLibraryImportHandoffGroup(group)")
                .contains("function normalizeCountMap(value)")
                .contains("bloge.visualOperatorLibraryImportReadiness.v1")
                .contains("requiresAckWarnings: Boolean(importReadiness.requiresAckWarnings)")
                .contains("bindingKindCounts: normalizeCountMap(importReadiness.bindingKindCounts)")
                .contains("handoffLaneCounts: normalizeCountMap(importReadiness.handoffLaneCounts)")
                .contains("operatorLibraryIdCounts: normalizeCountMap(importReadiness.operatorLibraryIdCounts)")
                .contains("readinessStateCounts: normalizeCountMap(importReadiness.readinessStateCounts)")
                .contains("runtimeBindingRequirementKeys: normalizeStringArray(importReadiness.runtimeBindingRequirementKeys)")
                .contains("runtimeBindingRequirements: Array.isArray(importReadiness.runtimeBindingRequirements)")
                .contains("requirementKey: String(requirement?.requirementKey || '')")
                .contains("operatorLibraryId: String(requirement?.operatorLibraryId || '')")
                .contains("libraryImportReadinessCountRows('Library', readiness.operatorLibraryIdCounts")
                .contains("Runtime binding routing")
                .contains("Runtime binding handoff groups")
                .contains("Runtime binding requirements")
                .contains("request.operationId = current.operationId")
                .contains("request.channel = current.channel")
                .contains("request.action = current.action")
                .contains("request.messageName = current.messageName")
                .contains("async function projectAsyncApiOperatorLibrary()")
                .contains("/admin/visual-operator-libraries/from-asyncapi${libraryForceQuery()}")
                .contains("const blockedProjection = asyncApiBlockedProjectionMessage")
                .contains("asyncApiProjectionBlocked")
                .contains("body: JSON.stringify(asyncApiProjectionRequest(sourceText))")
                .contains("asyncApiOperatorLibraryImportResult: payload")
                .contains("state.libraryImportText = pretty(payload.library)")
                .contains("Projected AsyncAPI into ${payload.library.libraryId}.")
                .contains("payload?.projectionReview")
                .contains("Review generated library, then Import.")
                .contains("headers: { 'Content-Type': 'text/plain' }")
                .contains("libraryImportConfirmationKey(sourceText, validation.diagnostics)")
                .contains("sourceText: String(sourceText || '')")
                .contains("renderLibraryImpactPanel($('library-impact'), diagnostics, state.libraryMessage?.impact)")
                .contains("function renderLibraryImpactPanel(target, diagnostics, impact = null)")
                .contains("function libraryImpactSummaryFromPayload(impact)")
                .contains("function libraryImpactSummary(diagnostics)")
                .contains("function renderSchemaChangeRows(schemaChanges, limit = 5)")
                .contains("function librarySchemaChangesFromDiagnostics(diagnostics)")
                .contains("renderSchemaChangeRows(change.schemaChanges, 3)")
                .contains("path: String(change?.path || '').trim()")
                .contains("`${surface} ${port}.${path}`")
                .contains("function libraryImpactRefsFromDiagnostic(diagnostic)")
                .contains("function changeRiskLabel(risk)")
                .contains("function libraryImpactRiskSummaryText(summary)")
                .contains("function operatorLibraryWarningAcknowledgementMessage(impact, diagnostics, actionLabel = 'Import')")
                .contains("data-library-impact-draft")
                .contains("data-library-impact-publication")
                .contains("data-library-impact-node-index")
                .contains("function openLibraryImpactDraft(draftId)")
                .contains("function openLibraryImpactDraftTarget(draftId, nodeIndex = -1)")
                .contains("function openLibraryImpactPublicationTarget(publicationId, nodeIndex = -1)")
                .contains("Impact Review")
                .contains("payload?.impact")
                .contains("Review warnings, then click ${actionLabel} again to continue.")
                .contains("libraryMutationQuery(")
                .contains("appendLibraryRevisionMetadataParams(params, actionLabel, libraryId)")
                .contains("params.set('ackWarnings', 'true');")
                .contains("params.set('changeSource', 'gateway-browser');")
                .contains("params.set('changeSummary', summary || `${action} operator library ${id}.`);")
                .contains("params.set('reason', `${action} requested from the visual operator-library governance panel.`);");
    }

    @Test
    void surfacesAsyncApiProjectionReviewNegativeEvidence() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("function asyncApiProjectionSelectorReviewRows(matches)")
                .contains("status === 'AMBIGUOUS' ? 'ambiguous selector' : 'unmatched selector'")
                .contains("const matchedCount = numericReviewField(match?.matchedOperationCount)")
                .contains("const countLabel = `${matchedCount} match${matchedCount === 1 ? '' : 'es'}`")
                .contains("${escapeHtml(match?.target || '/')} · ${escapeHtml(countLabel)}")
                .contains("review.unmatchedSelectionCount")
                .contains("review.coverageStatus")
                .contains("review.coverage === 'NO_MATCH'")
                .contains("payload?.projectionReview")
                .contains("AsyncAPI projection failed with ${response.status}");
    }

    @Test
    void surfacesOperatorLibraryRevisionHistoryAndRestoreControls() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("id=\"library-history-id\"")
                .contains("id=\"library-revision-select\"")
                .contains("id=\"reload-library-revisions\"")
                .contains("id=\"export-library\"")
                .contains("id=\"validate-library-bundle\"")
                .contains("id=\"import-library-bundle\"")
                .contains("id=\"preview-library-revision\"")
                .contains("id=\"restore-library-revision\"")
                .contains("id=\"library-bundle-diff\"")
                .contains("id=\"library-revision-diff\"")
                .contains("id=\"library-allow-version-regression\"")
                .contains("async function exportSelectedOperatorLibrary()")
                .contains("/admin/visual-operator-libraries/${encodeURIComponent(libraryId)}/export")
                .contains("operatorLibraryExport: payload")
                .contains("state.libraryImportText = pretty(payload)")
                .contains("Exported ${payload?.sourceLibraryId || libraryId}@${payload?.sourceRevision || 0}${visualBundleFingerprintSuffix(payload?.bundleFingerprint)}.")
                .contains("async function validateOperatorLibraryBundle()")
                .contains("/admin/visual-operator-libraries/validate-bundle${libraryForceQuery()}")
                .contains("operatorLibraryBundleValidation: payload")
                .contains("payload?.targetDiff")
                .contains("Bundle Target Diff")
                .contains("${action} ${libraryId}${visualBundleFingerprintSuffix(payload?.sourceBundleFingerprint)}.")
                .contains("async function importOperatorLibraryBundle()")
                .contains("/admin/visual-operator-libraries/import-bundle${mutationQuery}")
                .contains("operatorLibraryImportResult: payload")
                .contains("${action} ${stored.libraryId}@${payload?.latestRevision?.revision || 0}${visualBundleFingerprintSuffix(payload?.sourceBundleFingerprint)}.")
                .contains("Validate Bundle")
                .contains("Import Bundle")
                .contains("function loadOperatorLibraryRevisions(options = {})")
                .contains("function loadOperatorLibraryRevisionDiff(options = {})")
                .contains("/diff/${encodeURIComponent(target.revision || 0)}")
                .contains("/admin/visual-operator-libraries/${encodeURIComponent(libraryId)}/revisions")
                .contains("function previewSelectedOperatorLibraryRevision()")
                .contains("function restoreSelectedOperatorLibraryRevision()")
                .contains("/revisions/${encodeURIComponent(revision.revision || 0)}/restore")
                .contains("function renderLibraryRevisionDiff()")
                .contains("function renderOperatorLibraryDiffPanel(target, diff, title = 'Library Diff')")
                .contains("diff.operatorChanges")
                .contains("diff.changeSummary")
                .contains("function operatorLibraryRevisionOptionLabel(revision)")
                .contains("const metadata = revision?.revisionMetadata || {};")
                .contains("metadata.changeSummary")
                .contains("metadata.actor")
                .contains("function libraryDeleteMutationQuery(libraryId = '')")
                .contains("function libraryRestoreMutationQuery(ackWarnings = false, libraryId = '', revision = 0)")
                .contains("params.set('allowVersionRegression', 'true');")
                .contains("function libraryRestoreConfirmationKey(libraryId, revision, diagnostics = [])")
                .contains("History remains available for restore.");
    }

    @Test
    void surfacesDraftRevisionDiffReviewControls() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("id=\"draft-revision-diff\"")
                .contains("function loadDraftRevisionDiff(options = {})")
                .contains("function renderDraftRevisionDiff()")
                .contains("function latestDraftRevision()")
                .contains("function selectedDraftRevision()")
                .contains("draftHistory: []")
                .contains("fetch(visualDraftHistoryUrl())")
                .contains("function draftHistoryEntries()")
                .contains("function currentDraftIsActive()")
                .contains("entry.active ? 'active' : 'deleted'")
                .contains("draft.revisionMetadata?.reason")
                .contains("entry.reason")
                .contains("/api/visual/drafts/${encodeURIComponent(state.currentDraftId)}/revisions")
                .contains("/diff/${encodeURIComponent(target.revision || 0)}")
                .contains("/restore")
                .contains("const expectedRevision = current?.revision || historyEntry?.latestRevision || 0")
                .contains("expectedRevision,")
                .contains("function draftCreateMutationQuery()")
                .contains("/api/visual/drafts${draftCreateMutationQuery()}")
                .contains("changeSummary', 'Created visual draft from browser canvas.'")
                .contains("reason', 'User saved a new schema-constrained visual graph draft from the browser canvas.'")
                .contains("reason: 'User saved schema-constrained visual draft changes from the browser canvas.'")
                .contains("changeSummary: `Restored draft revision @${revision}.`")
                .contains("reason: 'User reviewed draft revision history before restoring this version in the browser.'")
                .contains("const deleteParams = new URLSearchParams")
                .contains("changeSummary: `Deleted draft ${deletedId}@${expectedRevision}.`")
                .contains("reason: 'User deleted the visual draft from the browser Drafts panel.'")
                .contains("diff.nodeChanges")
                .contains("diff.edgeChanges")
                .contains("diff.graphChanges")
                .contains("diff.changeSummary")
                .contains("function draftRevisionDiffLevel(diff)")
                .contains("function draftRevisionDiffRiskLevel(risk)");
    }

    @Test
    void surfacesSelectedNodeDiagnosticsInInspector() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("function renderSelectedNodeDiagnosticsPanel(node)")
                .contains("function selectedNodeDiagnosticsSummary(diagnostics, traceNode)")
                .contains("function renderSelectedNodeTraceRow(traceNode)")
                .contains("${renderSelectedNodeDiagnosticsPanel(node)}")
                .contains("node-diagnostics-panel")
                .contains("node-diagnostic-row");
    }

    @Test
    void rendersVisualLayoutGroupsAsCanvasBands() throws Exception {
        String source = appJsSource();
        String styles = stylesCssSource();

        assertThat(source)
                .contains("function layoutGroupRegions(layout, nodes)")
                .contains("function layoutGroupNodeIds(group, nodes)")
                .contains("function layoutGroupKindClass(kind)")
                .contains("function renderLayoutGroup(svg, group)")
                .contains("const groupRegions = layoutGroupRegions(state.layout, nodes)")
                .contains("renderLayoutGroup(svg, group);");
        assertThat(styles)
                .contains(".layout-group-frame")
                .contains(".layout-group.branch .layout-group-frame")
                .contains(".layout-group.degradation .layout-group-frame")
                .contains(".layout-group-label")
                .contains(".layout-group-meta");
    }

    @Test
    void supportsSelectedNodeDuplicationInInspector() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("id=\"duplicate-operator\"")
                .contains("function duplicateSelectedBuilderNode()")
                .contains("function duplicateBuilderNode(node)")
                .contains("recordBuilderHistory(`Duplicate ${selected.id}`)")
                .contains("setConnectionMessage(`Duplicated ${selected.id} as ${duplicate.id}.`, 'success')")
                .contains("key === 'd' && !event.shiftKey")
                .contains("duplicateSelectedBuilderNode();")
                .contains("key === 'delete' || key === 'backspace'")
                .contains("deleteSelectedBuilderNode();");
    }

    @Test
    void summarizesGlobalVisualDiagnosticsByNode() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("visualDiagnosticNodeFilter")
                .contains("function renderVisualDiagnosticFilterNotice(count, activeNodeId = '')")
                .contains("function renderVisualDiagnosticSummary(diagnostics, activeNodeId = '')")
                .contains("function visualDiagnosticSummary(diagnostics)")
                .contains("function visualDiagnosticNodeQueue(diagnostics)")
                .contains("function visualDiagnosticNodeDisplayLabel(nodeId)")
                .contains("function visualDiagnosticPreviewNodes(queue, activeNodeId = '', previewLimit = VISUAL_DIAGNOSTIC_NODE_PREVIEW_LIMIT)")
                .contains("function visualDiagnosticOverflowText(totalCount, previewLimit = VISUAL_DIAGNOSTIC_NODE_PREVIEW_LIMIT)")
                .contains("function visualDiagnosticQueuePositionText(queue, activeNodeId = '')")
                .contains("function visualDiagnosticQueueTarget(diagnostics, activeNodeId = '', direction = 1)")
                .contains("function stepVisualDiagnosticNode(direction = 1)")
                .contains("function clearVisualDiagnosticNodeFilter()")
                .contains("function visualDiagnosticShortcutDirection(event)")
                .contains("function visualDiagnosticClearShortcut(event, activeNodeId = state.visualDiagnosticNodeFilter)")
                .contains("function visualDiagnosticNodeSummaryText(entry)")
                .contains("data-diagnostic-filter-node")
                .contains("data-diagnostic-step")
                .contains("data-diagnostic-clear-filter")
                .contains("aria-label=\"Previous visual diagnostic node\"")
                .contains("aria-label=\"Next visual diagnostic node\"")
                .contains("aria-label=\"Show all visual diagnostics\"")
                .contains("diagnostic-summary-overflow")
                .contains("visual-diagnostic-filter-note")
                .contains("visual-diagnostic-summary")
                .contains("clearVisualDiagnosticNodeFilter();")
                .contains("stepVisualDiagnosticNode(diagnosticDirection);");
    }

    private static String appJsSource() throws IOException {
        return new ClassPathResource("static/examples/gateway/app.js")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String stylesCssSource() throws IOException {
        return new ClassPathResource("static/examples/gateway/styles.css")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void assumeNodeAvailable() throws IOException, InterruptedException {
        ProcessResult result;
        try {
            result = runProcess(List.of("node", "--version"), null, 5);
        } catch (IOException ex) {
            Assumptions.assumeTrue(false, "node executable is not available");
            return;
        }
        Assumptions.assumeTrue(result.finished() && result.exitCode() == 0,
                "node executable is not available: " + result.output());
    }

    private static ProcessResult runProcess(List<String> command, Path workDir, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        Process process = builder.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = finished ? process.exitValue() : -1;
        return new ProcessResult(finished, exitCode, output);
    }

    private static String designPublicationRunGuardProbe() {
        return String.join("", """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');

                function functionSource(name) {
                  const asyncNeedle = `async function ${name}(`;
                  const functionNeedle = `function ${name}(`;
                  let start = source.indexOf(asyncNeedle);
                  if (start < 0) start = source.indexOf(functionNeedle);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                let fetchCalls = 0;
                const context = vm.createContext({
                  console,
                  fetch: async () => {
                    fetchCalls += 1;
                    throw new Error('fetch should not be called for design publication run guard');
                  }
                });
                for (const name of [
                  'normalizeReadinessState',
                  'normalizeRuntimeBindingRequirement',
                  'normalizeVisualGraphNodeReadiness',
                  'normalizeVisualGraphReadiness',
                  'normalizeStringArray',
                  'normalizeVisualGraphActionReadiness',
                  'normalizeDiagnostics',
                  'normalizePublishArtifactKind',
                  'publicationReadiness',
                  'selectedPublication',
                  'selectedPublicationExecutable',
                  'setPublicationMessage',
                  'setVisualCheck',
                  'runSelectedPublication'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }
                context.renderPublicationStatus = () => {
                  context.renderPublicationStatusCalls = (context.renderPublicationStatusCalls || 0) + 1;
                };
                context.renderPublicationReadinessReview = () => {
                  context.renderPublicationReadinessReviewCalls = (context.renderPublicationReadinessReviewCalls || 0) + 1;
                };
                context.renderVisualCheck = () => {
                  context.renderVisualCheckCalls = (context.renderVisualCheckCalls || 0) + 1;
                };
                context.renderCanvasNavigator = () => {
                  context.renderCanvasNavigatorCalls = (context.renderCanvasNavigatorCalls || 0) + 1;
                };
                context.state = {
                  selectedPublicationId: 'pub-design',
                  publications: [{
                    publicationId: 'pub-design',
                    artifactKind: 'DESIGN',
                    validation: {
                      readiness: {
                        schemaVersion: 'bloge.visualGraphReadiness.v1',
                        state: 'DESIGN_ONLY',
                        level: 'INFO',
                        executable: false,
                        artifactKinds: ['DESIGN'],
                        title: 'Design-only graph'
                      },
                      actionReadiness: {
                        schemaVersion: 'bloge.visualGraphActionReadiness.v1',
                        state: 'DESIGN_ARTIFACT_READY',
                        level: 'INFO',
                        publishDesignNow: true,
                        publishExecutableNow: false,
                        artifactKinds: ['DESIGN']
                      }
                    }
                  }],
                  publicationDetailsById: {},
                  customContextText: '{ invalid json',
                  visualCheck: { message: '', level: 'info', diagnostics: [], readiness: null, actionReadiness: null }
                };
                context.elements = { output: { textContent: 'unchanged-output' } };
                context.$ = (id) => context.elements[id] || null;
                context.runSelectedPublication().then(() => {
                  const checks = [
                    ['fetch calls', fetchCalls, 0],
                    ['publication message text', context.state.publicationMessage.text, 'Publication pub-design is a DESIGN artifact and cannot be run.'],
                    ['publication message level', context.state.publicationMessage.level, 'warning'],
                    ['visual message', context.state.visualCheck.message, 'Design publication cannot be run.'],
                    ['visual level', context.state.visualCheck.level, 'warning'],
                    ['visual readiness state', context.state.visualCheck.readiness.state, 'design-only'],
                    ['visual action readiness state', context.state.visualCheck.actionReadiness.state, 'design-artifact-ready'],
                    ['output unchanged', context.elements.output.textContent, 'unchanged-output'],
                    ['publication status rendered', context.renderPublicationStatusCalls, 1],
                    ['publication readiness review rendered', context.renderPublicationReadinessReviewCalls, 1],
                    ['visual check rendered', context.renderVisualCheckCalls, 1],
                    ['canvas navigator rendered', context.renderCanvasNavigatorCalls, 1]
                  ];
                  for (const [label, actual, expected] of checks) {
                    if (actual !== expected) {
                      throw new Error(`${label}: expected ${expected}, got ${actual}`);
                    }
                  }
                  console.log('browser design publication run guard probe passed');
                }).catch((error) => {
                  console.error(error);
                  process.exitCode = 1;
                });
                """);
    }

    private static String draftActionReadinessGuardProbe() {
        return String.join("", """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');

                function functionSource(name) {
                  const asyncNeedle = `async function ${name}(`;
                  const functionNeedle = `function ${name}(`;
                  let start = source.indexOf(asyncNeedle);
                  if (start < 0) start = source.indexOf(functionNeedle);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                let fetchCalls = 0;
                const context = vm.createContext({
                  console,
                  fetch: async () => {
                    fetchCalls += 1;
                    throw new Error('fetch should not be called when server action readiness blocks draft actions');
                  },
                  pretty: (value) => JSON.stringify(value)
                });
                for (const name of [
                  'normalizeReadinessState',
                  'normalizeRuntimeBindingRequirement',
                  'normalizeVisualGraphNodeReadiness',
                  'normalizeVisualGraphReadiness',
                  'normalizeStringArray',
                  'normalizeVisualGraphActionReadiness',
                  'normalizeDiagnostics',
                  'visualDraftExecutableActionState',
                  'setVisualCheck',
                  'compileVisualDraft',
                  'runCustomGraph'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }
                const readiness = {
                  schemaVersion: 'bloge.visualGraphReadiness.v1',
                  state: 'RUNTIME_EXECUTABLE',
                  level: 'SUCCESS',
                  executable: true,
                  artifactKinds: ['EXECUTABLE', 'DESIGN'],
                  title: 'Executable graph',
                  summary: 'Graph-level readiness still looks executable.'
                };
                const actionReadiness = {
                  schemaVersion: 'bloge.visualGraphActionReadiness.v1',
                  state: 'RUNTIME_BINDING_REQUIRED',
                  level: 'WARNING',
                  compileNow: false,
                  runNow: false,
                  publishDesignNow: true,
                  publishExecutableNow: false,
                  artifactKinds: ['DESIGN'],
                  message: 'Server action gate blocked runtime execution.',
                  recommendedAction: 'Bind runtime first.'
                };
                context.state = {
                  builder: { nodes: [] },
                  customDsl: 'draft-dsl',
                  lastGeneratedVisualDsl: 'draft-dsl',
                  customContextText: '{ invalid json',
                  visualCheck: {
                    message: '',
                    level: 'info',
                    diagnostics: [{ level: 'WARNING', code: 'visual.runtimeBinding.required' }],
                    readiness,
                    actionReadiness
                  }
                };
                context.elements = {
                  output: { textContent: 'unchanged-output' },
                  'composer-dsl': { value: 'draft-dsl' }
                };
                context.$ = (id) => context.elements[id] || null;
                context.builderToDsl = () => 'draft-dsl';
                context.renderVisualCheck = () => {
                  context.renderVisualCheckCalls = (context.renderVisualCheckCalls || 0) + 1;
                };
                context.renderCanvasNavigator = () => {
                  context.renderCanvasNavigatorCalls = (context.renderCanvasNavigatorCalls || 0) + 1;
                };

                const compileState = context.visualDraftExecutableActionState(readiness, actionReadiness, 'compile');
                const runState = context.visualDraftExecutableActionState(readiness, actionReadiness, 'run');
                context.compileVisualDraft()
                  .then(() => {
                    const compileOutput = context.elements.output.textContent;
                    const compileMessage = context.state.visualCheck.message;
                    return context.runCustomGraph().then(() => ({
                      compileOutput,
                      compileMessage,
                      finalOutput: context.elements.output.textContent,
                      finalVisualCheck: context.state.visualCheck
                    }));
                  })
                  .then((result) => {
                    const checks = [
                      ['compile state disabled', compileState.disabled, true],
                      ['compile state message', compileState.message, 'Server action gate blocked runtime execution.'],
                      ['run state disabled', runState.disabled, true],
                      ['run state message', runState.message, 'Server action gate blocked runtime execution.'],
                      ['fetch calls', fetchCalls, 0],
                      ['compile output not executable', String(result.compileOutput.includes('not_executable')), 'true'],
                      ['compile message', result.compileMessage, 'Server action gate blocked runtime execution.'],
                      ['run output not executable', String(result.finalOutput.includes('not_executable')), 'true'],
                      ['run output skipped context parse', String(result.finalOutput.includes('invalid_context')), 'false'],
                      ['visual level', result.finalVisualCheck.level, 'warning'],
                      ['visual readiness state', result.finalVisualCheck.readiness.state, 'runtime-executable'],
                      ['visual action state', result.finalVisualCheck.actionReadiness.state, 'runtime-binding-required'],
                      ['visual check renders', context.renderVisualCheckCalls, 2],
                      ['canvas navigator renders', context.renderCanvasNavigatorCalls, 2]
                    ];
                    for (const [label, actual, expected] of checks) {
                      if (actual !== expected) {
                        throw new Error(`${label}: expected ${expected}, got ${actual}`);
                      }
                    }
                    console.log('browser draft action readiness guard probe passed');
                  })
                  .catch((error) => {
                    console.error(error);
                    process.exitCode = 1;
                  });
                """);
    }

    private static String runtimeBindingHandoffReviewProbe() {
        return String.join("", """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');
                new vm.Script(source, { filename: 'app.js' });

                function functionSource(name) {
                  const start = source.indexOf(`function ${name}(`);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                const context = vm.createContext({ console });
                for (const name of [
                  'operatorPaletteFacetLabel',
                  'visualRuntimeBindingHandoffReviewMessage',
                  'visualRuntimeBindingHandoffReviewRows',
                  'visualRuntimeBindingHandoffReviewRoutingSummary',
                  'visualRuntimeBindingHandoffReviewDistributionSummary',
                  'visualRuntimeBindingHandoffReviewCountFacetSummary',
                  'visualRuntimeBindingHandoffReviewCategorySummary',
                  'visualRuntimeBindingHandoffReviewItemLabel',
                  'visualRuntimeBindingHandoffReviewContractItemLabel',
                  'visualRuntimeBindingHandoffReviewItemValue',
                  'visualRuntimeBindingFieldChangeText',
                  'visualRuntimeBindingFieldLabel'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }
                const review = {
                  state: 'STALE',
                  matchedCount: 0,
                  driftedCount: 1,
                  missingCount: 1,
                  newCurrentWindowCount: 1,
                  sourceBundleFingerprint: 'sha256:1234567890abcdef',
                  exportedOperatorContractCount: 1,
                  operatorContractMatchedCount: 0,
                  operatorContractDriftedCount: 1,
                  operatorContractMissingCount: 0,
                  operatorContractNewCurrentWindowCount: 0,
                  operatorContractStatusCounts: {
                    drifted: 1
                  },
                  fieldChangeCategoryCounts: {
                    'runtime-binding': 2,
                    'asset-metadata': 1
                  },
                  operatorContractFieldChangeCategoryCounts: {
                    'operator-contract': 1
                  },
                  newCurrentWindowRequirementKeys: [
                    'RUNTIME_BINDING|draft|fresh|eligibility|executable-lowering|risk:eligibility|'
                  ],
                  exportedWindowDistribution: {
                    requirementCount: 2,
                    operatorLibraryIdCounts: { 'risk-policy-design': 2 },
                    handoffLaneCounts: { 'operator-platform': 2 },
                    handoffKindCounts: { 'operator-implementation': 2 },
                    handoffTargetCounts: { 'risk:eligibility': 1, 'legacy-risk-owner': 1 }
                  },
                  currentWindowDistribution: {
                    requirementCount: 2,
                    operatorLibraryIdCounts: { 'risk-policy-design': 2 },
                    handoffLaneCounts: { 'operator-platform': 2 },
                    handoffKindCounts: { 'operator-implementation': 2 },
                    handoffTargetCounts: { 'risk:eligibility': 2 }
                  },
                  newCurrentWindowDistribution: {
                    requirementCount: 1,
                    operatorLibraryIdCounts: { 'risk-policy-design': 1 },
                    handoffLaneCounts: { 'operator-platform': 1 },
                    handoffKindCounts: { 'operator-implementation': 1 },
                    handoffTargetCounts: { 'risk:eligibility': 1 }
                  },
                  items: [
                    {
                      requirementKey: 'RUNTIME_BINDING|draft|risk|eligibility|executable-lowering|risk:eligibility|',
                      status: 'drifted',
                      level: 'warning',
                      exportedRequirement: {
                        targetLabel: 'Risk policy @1'
                      },
                      currentRequirement: {
                        targetLabel: 'Risk policy @2'
                      },
                      fieldChanges: [
                        {
                          field: 'targetLabel',
                          category: 'asset-metadata',
                          exportedValue: 'Risk policy @1',
                          currentValue: 'Risk policy @2'
                        },
                        {
                          field: 'handoffTarget',
                          category: 'runtime-binding',
                          exportedValue: 'legacy-risk-owner',
                          currentValue: 'risk:eligibility'
                        },
                        {
                          field: 'recommendedAction',
                          category: 'runtime-binding',
                          exportedValue: 'Legacy action',
                          currentValue: 'Bind executable lowering before EXECUTABLE promotion.'
                        }
                      ]
                    },
                    {
                      requirementKey: 'RUNTIME_BINDING|draft|missing|eligibility|executable-lowering|risk:eligibility|',
                      status: 'missing',
                      level: 'warning',
                      message: 'Exported requirement key is no longer present.',
                      exportedRequirement: {
                        targetLabel: 'Missing policy @1'
                      }
                    }
                  ],
                  operatorContractItems: [
                    {
                      operatorRef: 'risk:eligibility',
                      status: 'drifted',
                      level: 'warning',
                      exportedContract: {
                        display: { name: 'Eligibility' },
                        fingerprint: 'sha256:old'
                      },
                      currentContract: {
                        display: { name: 'Eligibility' },
                        fingerprint: 'sha256:new'
                      },
                      fieldChanges: [
                        {
                          field: 'fingerprint',
                          category: 'operator-contract',
                          exportedValue: 'sha256:old',
                          currentValue: 'sha256:new'
                        }
                      ]
                    }
                  ]
                };
                const message = context.visualRuntimeBindingHandoffReviewMessage(review);
                const rows = context.visualRuntimeBindingHandoffReviewRows(review);
                const routingSummary = context.visualRuntimeBindingHandoffReviewRoutingSummary(review);
                const checks = [
                  ['message', message, 'Handoff review STALE: 0 current, 1 drifted, 1 missing, 1 new in current window. Contracts: 0 current, 1 drifted, 0 missing, 0 new current-window.'],
                  ['row count', rows.length, 9],
                  ['fingerprint label', rows[0].label, 'Snapshot fingerprint'],
                  ['fingerprint value', rows[0].value, 'sha256:1234567890abcdef'],
                  ['contracts label', rows[1].label, 'Operator contract snapshots'],
                  ['contracts value', rows[1].value, '1 contract exported with this handoff; Drifted: 1'],
                  ['routing label', rows[2].label, 'Runtime binding routing'],
                  ['routing summary includes exported owner', String(routingSummary.includes('Exported 2 requirements (risk-policy-design library: 2')), 'true'],
                  ['routing summary includes current target', String(routingSummary.includes('Current 2 requirements')), 'true'],
                  ['routing summary includes new work', String(routingSummary.includes('New 1 requirement')), 'true'],
                  ['category label', rows[3].label, 'Drift categories'],
                  ['category value', rows[3].value, 'Asset Metadata 1 · Runtime Binding 2'],
                  ['contract category label', rows[4].label, 'Operator contract drift categories'],
                  ['contract category value', rows[4].value, 'Operator Contract 1'],
                  ['drift label', rows[5].label, 'Drifted · Risk policy @2'],
                  ['drift value includes route', String(rows[5].value.includes('Runtime Binding Handoff Target: legacy-risk-owner -> risk:eligibility')), 'true'],
                  ['drift value includes action', String(rows[5].value.includes('Runtime Binding Recommended Action: Legacy action -> Bind executable lowering before EXECUTABLE promotion.')), 'true'],
                  ['missing label', rows[6].label, 'Missing · Missing policy @1'],
                  ['contract drift label', rows[7].label, 'Drifted · Eligibility'],
                  ['contract drift value', rows[7].value, 'Operator Contract Fingerprint: sha256:old -> sha256:new'],
                  ['new key label', rows[8].label, 'New current-window requirements']
                ];
                for (const [label, actual, expected] of checks) {
                  if (actual !== expected) {
                    throw new Error(`${label}: expected ${expected}, got ${actual}`);
                  }
                }
                console.log('runtime binding handoff review probe passed');
                """);
    }

    private static String runtimeBindingImplementationLifecycleProbe() {
        return String.join("", """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');
                new vm.Script(source, { filename: 'app.js' });

                function functionSource(name) {
                  const start = source.indexOf(`function ${name}(`);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                const context = vm.createContext({
                  console,
                  URLSearchParams,
                  encodeURIComponent,
                  state: {
                    visualRuntimeBindingImplementationQuery: {
                      operatorRef: 'risk:eligibility',
                      state: 'bound'
                    },
                    visualRuntimeBindingImplementations: []
                  }
                });
                for (const name of [
                  'operatorPaletteFacetLabel',
                  'diagnosticMessage',
                  'normalizeVisualRuntimeBindingImplementationQuery',
                  'visualRuntimeBindingImplementationsUrl',
                  'visualRuntimeBindingImplementationTransitionUrl',
                  'visualRuntimeBindingImplementationTransitionRequest',
                  'visualRuntimeBindingImplementationTransitionReason',
                  'visualRuntimeBindingImplementationTransitionSummary',
                  'visualRuntimeBindingImplementationTransitionMessage',
                  'visualRuntimeBindingImplementationRows',
                  'visualRuntimeBindingImplementationLevel',
                  'visualRuntimeBindingImplementationLabel',
                  'visualRuntimeBindingImplementationValue',
                  'visualRuntimeBindingImplementationActions',
                  'visualRuntimeBindingImplementationContext'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }
                const current = {
                  bindingId: 'risk-eligibility-native-v1',
                  revision: 3,
                  state: 'bound',
                  operatorRef: 'risk:eligibility',
                  implementation: {
                    adapterKind: 'http',
                    runtimeOwner: 'risk-platform',
                    entrypoint: 'risk-worker',
                    implementationVersion: '1.2.0'
                  },
                  sourceRequirementKeys: ['RUNTIME_BINDING|draft|risk']
                };
                const replacement = {
                  bindingId: 'risk-eligibility-native-v2',
                  revision: 1,
                  state: 'requires-review',
                  operatorRef: 'risk:eligibility',
                  implementation: {
                    adapterKind: 'http',
                    runtimeOwner: 'risk-platform',
                    entrypoint: 'risk-worker-v2',
                    implementationVersion: '2.0.0'
                  },
                  sourceRequirementKeys: ['RUNTIME_BINDING|draft|risk']
                };
                context.state.visualRuntimeBindingImplementations = [current, replacement];
                const rows = context.visualRuntimeBindingImplementationRows(context.state.visualRuntimeBindingImplementations);
                const request = context.visualRuntimeBindingImplementationTransitionRequest(current, 'supersede', replacement);
                const url = context.visualRuntimeBindingImplementationsUrl();
                const transitionUrl = context.visualRuntimeBindingImplementationTransitionUrl(current.bindingId, 'supersede');
                const acceptedMessage = context.visualRuntimeBindingImplementationTransitionMessage(
                  'supersede',
                  current,
                  replacement,
                  { accepted: true },
                  [],
                  200
                );
                const rejectedMessage = context.visualRuntimeBindingImplementationTransitionMessage(
                  'bind',
                  replacement,
                  null,
                  { accepted: false },
                  [{
                    level: 'ERROR',
                    code: 'visual.runtimeBindingImplementation.revisionConflict',
                    message: 'Runtime binding implementation revision is stale.'
                  }],
                  409
                );
                const currentContext = context.visualRuntimeBindingImplementationContext(current);
                const checks = [
                  ['list url', url, '/api/visual/assets/runtime-binding-requirements/implementation-bindings?operatorRef=risk%3Aeligibility&state=bound'],
                  ['transition url', transitionUrl, '/api/visual/assets/runtime-binding-requirements/implementation-bindings/risk-eligibility-native-v1/supersede'],
                  ['row count', rows.length, 2],
                  ['bound row actions', rows[0].actions.map((action) => action.action).join(','), 'unbind,supersede'],
                  ['replacement row action', rows[1].actions.map((action) => action.action).join(','), 'bind'],
                  ['row label', rows[0].label, 'risk-eligibility-native-v1 · Bound · rev 3'],
                  ['row value includes owner', String(rows[0].value.includes('owner risk-platform')), 'true'],
                  ['request schema', request.schemaVersion, 'bloge.visualRuntimeBindingImplementationTransition.v1'],
                  ['request actor', request.actor, 'visual-canvas'],
                  ['request source', request.changeSource, 'gateway-browser'],
                  ['request ack', request.ackReview, true],
                  ['request replacement', request.replacementBindingId, 'risk-eligibility-native-v2'],
                  ['request expected revision', request.expectedRevision, 3],
                  ['request expected replacement revision', request.expectedReplacementRevision, 1],
                  ['request summary', request.changeSummary, 'Superseded runtime implementation risk-eligibility-native-v1@3 with risk-eligibility-native-v2@1.'],
                  ['accepted message', acceptedMessage, "Runtime binding implementation 'risk-eligibility-native-v2' superseded 'risk-eligibility-native-v1'."],
                  ['rejected message', rejectedMessage, 'Runtime binding implementation revision is stale.'],
                  ['context level', currentContext.level, 'success'],
                  ['context message', currentContext.message, 'risk-eligibility-native-v1 · Bound · risk:eligibility · rev 3']
                ];
                for (const [label, actual, expected] of checks) {
                  if (actual !== expected) {
                    throw new Error(`${label}: expected ${expected}, got ${actual}`);
                  }
                }
                console.log('runtime binding implementation lifecycle probe passed');
                """);
    }

    private static String runtimeEvidenceChainProbe() {
        return String.join("", """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');
                new vm.Script(source, { filename: 'app.js' });

                function functionSource(name) {
                  const start = source.indexOf(`function ${name}(`);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                const context = vm.createContext({
                  console,
                  URLSearchParams,
                  OPERATOR_TYPES: {
                    'risk:eligibility': {
                      operatorLibraryId: 'risk-policy-design'
                    }
                  },
                  state: {
                    visualRuntimeEvidenceQuery: {
                      evidenceKind: 'adapter_activation',
                      operatorRef: 'risk:eligibility',
                      operatorLibraryId: 'risk-policy-design',
                      bindingId: 'risk-eligibility-native-v1',
                      activationId: 'risk-eligibility-native-v1-prod',
                      lifecycleState: 'active',
                      rolloutState: 'healthy',
                      rolloutSignal: 'error_rate',
                      breachedOnly: true
                    }
                  }
                });
                for (const name of [
                  'operatorPaletteFacetLabel',
                  'normalizeReadinessState',
                  'normalizeVisualRuntimeEvidenceQuery',
                  'visualRuntimeEvidenceParams',
                  'visualRuntimeAdapterActivationsUrl',
                  'visualRuntimeRolloutObservationsUrl',
                  'visualExecutableLoweringIntegrationsUrl',
                  'visualRuntimeEvidenceWindowUrl',
                  'visualRuntimeEvidenceRows',
                  'runtimeEvidenceOperatorLibraryId',
                  'visualRuntimeEvidenceImplementationBindingRow',
                  'visualRuntimeAdapterActivationRow',
                  'visualRuntimeRolloutObservationRow',
                  'visualExecutableLoweringIntegrationRow',
                  'visualRuntimeEvidenceKindOrder',
                  'visualRuntimeEvidenceLevel',
                  'visualRuntimeEvidenceCounts',
                  'visualRuntimeEvidenceSignalCounts',
                  'visualRuntimeEvidenceWindowSummary',
                  'visualRuntimeEvidenceContext'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }
                const binding = {
                  bindingId: 'risk-eligibility-native-v1',
                  revision: 4,
                  state: 'bound',
                  level: 'success',
                  operatorRef: 'risk:eligibility',
                  sourceRequirementKeys: ['RUNTIME_BINDING|draft|risk'],
                  implementation: {
                    adapterKind: 'http',
                    runtimeOwner: 'runtime-team',
                    entrypoint: 'riskEligibility'
                  }
                };
                const activation = {
                  activationId: 'risk-eligibility-native-v1-prod',
                  revision: 2,
                  state: 'active',
                  level: 'success',
                  bindingId: 'risk-eligibility-native-v1',
                  bindingRevision: 4,
                  operatorRef: 'risk:eligibility',
                  adapterKind: 'http',
                  runtimeEnvironment: 'prod',
                  healthState: 'healthy',
                  activatedBy: 'runtime-team',
                  evidence: [{ kind: 'health-check' }]
                };
                const rollout = {
                  observationId: 'risk-eligibility-rollout-healthy',
                  revision: 1,
                  state: 'healthy',
                  level: 'success',
                  activationId: 'risk-eligibility-native-v1-prod',
                  activationRevision: 2,
                  bindingId: 'risk-eligibility-native-v1',
                  bindingRevision: 4,
                  operatorRef: 'risk:eligibility',
                  rolloutStrategy: 'canary',
                  trafficPercent: 25,
                  rolloutPhase: 'canary',
                  rollbackTriggered: false,
                  observedBy: 'rollout-controller',
                  rolloutSignals: [{
                    name: 'error-rate',
                    kind: 'metric',
                    breached: true
                  }],
                  evidence: [{ kind: 'metric' }]
                };
                const integration = {
                  integrationId: 'risk-eligibility-lowering-prod',
                  revision: 5,
                  state: 'active',
                  level: 'success',
                  activationId: 'risk-eligibility-native-v1-prod',
                  activationRevision: 2,
                  bindingId: 'risk-eligibility-native-v1',
                  bindingRevision: 4,
                  operatorRef: 'risk:eligibility',
                  loweringMode: 'native',
                  executorKind: 'bloge-worker',
                  executorEntrypoint: 'riskEligibility',
                  executorOwner: 'executor-platform',
                  evidence: [{ kind: 'executor-test' }]
                };
                const rows = context.visualRuntimeEvidenceRows([binding], [activation], [rollout], [integration]);
                const activationUrl = context.visualRuntimeAdapterActivationsUrl();
                const rolloutUrl = context.visualRuntimeRolloutObservationsUrl();
                const integrationUrl = context.visualExecutableLoweringIntegrationsUrl();
                const windowUrl = context.visualRuntimeEvidenceWindowUrl();
                const counts = context.visualRuntimeEvidenceCounts(rows, 'operatorRef');
                const libraryCounts = context.visualRuntimeEvidenceCounts(rows, 'operatorLibraryId');
                const signalCounts = context.visualRuntimeEvidenceSignalCounts(rows, true);
                const summary = context.visualRuntimeEvidenceWindowSummary(rows);
                const pagedSummary = context.visualRuntimeEvidenceWindowSummary({
                  total: 4,
                  unfilteredTotal: 6,
                  offset: 1,
                  displayedCount: 2,
                  kindCounts: {
                    'implementation-binding': 1,
                    'adapter-activation': 1,
                    'rollout-observation': 1,
                    'executable-lowering-integration': 1
                  }
                }, rows.slice(0, 2));
                const activeContext = context.visualRuntimeEvidenceContext(rows[0]);
                const checks = [
                  ['activation url', activationUrl, '/api/visual/assets/runtime-binding-requirements/adapter-activations?operatorRef=risk%3Aeligibility&bindingId=risk-eligibility-native-v1&state=active'],
                  ['rollout url', rolloutUrl, '/api/visual/assets/runtime-binding-requirements/rollout-observations?operatorRef=risk%3Aeligibility&bindingId=risk-eligibility-native-v1&activationId=risk-eligibility-native-v1-prod&state=healthy&rolloutSignal=error-rate&breachedOnly=true'],
                  ['integration url', integrationUrl, '/api/visual/assets/runtime-binding-requirements/executable-lowering-integrations?operatorRef=risk%3Aeligibility&activationId=risk-eligibility-native-v1-prod&state=active'],
                  ['window url', windowUrl, '/api/visual/assets/runtime-binding-requirements/runtime-evidence?evidenceKind=adapter-activation&operatorRef=risk%3Aeligibility&operatorLibraryId=risk-policy-design&bindingId=risk-eligibility-native-v1&activationId=risk-eligibility-native-v1-prod&lifecycleState=active&rolloutState=healthy&rolloutSignal=error-rate&breachedOnly=true&itemLimit=12&offset=0'],
                  ['row count', rows.length, 4],
                  ['first kind', rows[0].kind, 'implementation-binding'],
                  ['first library', rows[0].operatorLibraryId, 'risk-policy-design'],
                  ['second kind', rows[1].kind, 'adapter-activation'],
                  ['third kind', rows[2].kind, 'rollout-observation'],
                  ['fourth kind', rows[3].kind, 'executable-lowering-integration'],
                  ['binding label', rows[0].label, 'risk-eligibility-native-v1 · Implementation binding · Bound · rev 4'],
                  ['activation label', rows[1].label, 'risk-eligibility-native-v1-prod · Adapter activation · Active · rev 2'],
                  ['rollout value includes traffic', String(rows[2].value.includes('25% traffic')), 'true'],
                  ['integration value includes executor', String(rows[3].value.includes('executor bloge-worker')), 'true'],
                  ['operator count', counts['risk:eligibility'], 4],
                  ['library count', libraryCounts['risk-policy-design'], 4],
                  ['breached signal count', signalCounts['error-rate'], 1],
                  ['summary', summary, '4 runtime evidence records · 1 bindings / 1 activations / 1 rollout / 1 lowering'],
                  ['paged summary', pagedSummary, '2-3 of 4 / 6 total · 1 bindings / 1 activations / 1 rollout / 1 lowering'],
                  ['context level', activeContext.level, 'success'],
                  ['context message', activeContext.message, 'Implementation Binding · risk-eligibility-native-v1 · library risk-policy-design · risk:eligibility · binding risk-eligibility-native-v1']
                ];
                for (const [label, actual, expected] of checks) {
                  if (actual !== expected) {
                    throw new Error(`${label}: expected ${expected}, got ${actual}`);
                  }
                }
                console.log('runtime evidence chain probe passed');
                """);
    }

    private static String operatorDetailProjectionProbe() {
        return String.join("", """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');
                new vm.Script(source, { filename: 'app.js' });

                function functionSource(name) {
                  let start = source.indexOf(`function ${name}(`);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  if (source.slice(Math.max(0, start - 6), start) === 'async ') {
                    start -= 6;
                  }
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                const detailTarget = {
                  hidden: true,
                  className: '',
                  innerHTML: '',
                  querySelector: () => null,
                  querySelectorAll: () => []
                };
                const context = vm.createContext({ console });
                context.OPERATOR_TYPES = {};
                context.$ = (id) => id === 'operator-palette-detail' ? detailTarget : null;
                context.detailTarget = detailTarget;
                context.addBuilderNode = () => {};
                context.focusOperatorPaletteRef = async () => null;
                context.openOperatorProjectionAction = async () => null;
                context.schemaType = (schema) => schema?.type || '';
                context.schemaFieldDescriptors = (schemaEnvelope) =>
                  Object.keys(schemaEnvelope?.schema?.properties || {}).map((path) => ({ path }));
                context.inputPortsForSpec = (spec) => Array.isArray(spec?.inputPorts) ? spec.inputPorts : [];
                context.outputPortsForSpec = (spec) => Array.isArray(spec?.outputPorts) ? spec.outputPorts : [];
                context.operatorPaletteContractSummary = (spec) => {
                  const inputs = Array.isArray(spec?.inputPorts) ? spec.inputPorts.length : 0;
                  const outputs = Array.isArray(spec?.outputPorts) ? spec.outputPorts.length : 0;
                  return `In ${inputs}/0 fields · Out ${outputs}/0 fields`;
                };
                context.renderOperatorDiagnosticsPanel = () => '';
                context.state = {
                  operatorRuntimeBindingProjectionsByRef: {},
                  operatorExecutablePromotionProjectionsByRef: {},
                  operatorDefinitionMessagesByRef: {},
                  operatorDefinitionLoadingRef: '',
                  focusedOperatorRef: ''
                };
                for (const name of [
                  'escapeHtml',
                  'normalizeDiagnostics',
                  'normalizeProjectionLevel',
                  'normalizeOperatorRuntimeBindingProjection',
                  'normalizeOperatorExecutablePromotionProjection',
                  'normalizeVisualOperatorDetailPayload',
                  'operatorProjectionMap',
                  'rememberOperatorProjections',
                  'normalizeOperatorPorts',
                  'numericCount',
                  'normalizeOperatorFitTarget',
                  'normalizeOperatorFitCandidate',
                  'normalizeOperatorRuntimeReadiness',
                  'operatorPaletteFacetLabel',
                  'operatorPaletteCapabilityLabels',
                  'operatorPaletteCapabilityFacetValues',
                  'operatorPaletteLoweringMode',
                  'operatorProjectionRuntimeReadiness',
                  'operatorProjectionQueueActionType',
                  'operatorProjectionQueueAction',
                  'operatorProjectionActionLabel',
                  'operatorPaletteProjectionBadge',
                  'operatorPaletteDetailPortSummary',
                  'operatorPaletteDetailRows',
                  'attachOperatorPaletteDetailHandlers',
                  'renderOperatorPaletteDetail',
                  'operatorRuntimeReadiness',
                  'renderOperatorReadinessPanel',
                  'readableName',
                  'baseIdForResource',
                  'rememberCatalogOperator'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }
                const payload = {
                  schemaVersion: 'bloge.visualOperatorDetail.v1',
                  operator: {
                    schemaVersion: 'bloge.visualOperator.v1',
                    operatorRef: 'risk:eligibility',
                    fingerprint: 'sha256:eligibility',
                    display: { name: 'Eligibility', description: 'Risk eligibility', tags: ['risk'] },
                    source: { kind: 'user-library', libraryId: 'risk-policy' },
                    ports: {
                      inputs: [{ name: 'inputs', schema: { schema: { type: 'object' } }, required: true }],
                      outputs: [{ name: 'decision', schema: { schema: { type: 'string' } }, required: true }]
                    },
                    configSchema: { schema: { type: 'object' } },
                    capabilities: { effect: 'PURE' },
                    lowering: { mode: 'design', operatorRef: 'riskEligibility' },
                    runtimeReadiness: {
                      state: 'DESIGN_ONLY',
                      level: 'info',
                      executable: false,
                      artifactKinds: ['DESIGN'],
                      title: 'Design-only operator',
                      summary: 'Authorable schema contract.'
                    }
                  },
                  runtimeBindingProjection: {
                    schemaVersion: 'bloge.operatorRuntimeBindingProjection.v1',
                    operatorRef: 'risk:eligibility',
                    operatorFingerprint: 'sha256:eligibility',
                    runtimeReadinessState: 'design-only',
                    executable: false,
                    implementationBindingRequired: false,
                    runtimeActivationRequired: true,
                    projectionState: 'binding-bound',
                    level: 'warning',
                    title: 'Runtime binding found',
                    summary: 'Implementation is bound but not activated.',
                    activeBindingId: 'risk-eligibility-native-v1',
                    activeBindingRevision: 3
                  },
                  executablePromotionProjection: {
                    schemaVersion: 'bloge.operatorExecutablePromotionProjection.v1',
                    operatorRef: 'risk:eligibility',
                    operatorFingerprint: 'sha256:eligibility',
                    executableNow: false,
                    promotionReady: false,
                    promotionState: 'activation-required',
                    level: 'warning',
                    title: 'Adapter activation required',
                    summary: 'Activate the runtime adapter before EXECUTABLE promotion.',
                    requiredNextAction: 'ACTIVATE_RUNTIME_ADAPTER',
                    activeBindingId: 'risk-eligibility-native-v1'
                  }
                };
                const detail = context.normalizeVisualOperatorDetailPayload(payload);
                const runtimeMap = context.operatorProjectionMap(
                  [payload.runtimeBindingProjection],
                  context.normalizeOperatorRuntimeBindingProjection
                );
                const promotionMap = context.operatorProjectionMap(
                  [payload.executablePromotionProjection],
                  context.normalizeOperatorExecutablePromotionProjection
                );
                context.rememberCatalogOperator(detail.operator, {
                  runtimeBindingProjection: detail.runtimeBindingProjection,
                  executablePromotionProjection: detail.executablePromotionProjection
                });
                const spec = context.OPERATOR_TYPES['risk:eligibility'];
                const readiness = context.operatorRuntimeReadiness(spec);
                const queueAction = context.operatorProjectionQueueAction(spec);
                const badge = context.operatorPaletteProjectionBadge(spec);
                const panel = context.renderOperatorReadinessPanel(spec);
                context.state.focusedOperatorRef = 'risk:eligibility';
                context.state.operatorDefinitionMessagesByRef['risk:eligibility'] = {
                  text: 'Operator definition refreshed.',
                  level: 'success'
                };
                context.renderOperatorPaletteDetail();
                const detailHtml = context.detailTarget.innerHTML;
                const legacy = context.normalizeVisualOperatorDetailPayload({
                  schemaVersion: 'bloge.visualOperator.v1',
                  operatorRef: 'risk:legacy'
                });
                const checks = [
                  ['detail operator ref', detail.operator.operatorRef, 'risk:eligibility'],
                  ['detail binding state', detail.runtimeBindingProjection.projectionState, 'binding-bound'],
                  ['detail promotion action', detail.executablePromotionProjection.requiredNextAction, 'ACTIVATE_RUNTIME_ADAPTER'],
                  ['runtime map key', runtimeMap['risk:eligibility'].activeBindingId, 'risk-eligibility-native-v1'],
                  ['promotion map key', promotionMap['risk:eligibility'].promotionState, 'activation-required'],
                  ['spec binding state', spec.runtimeBindingProjection.projectionState, 'binding-bound'],
                  ['spec promotion state', spec.executablePromotionProjection.promotionState, 'activation-required'],
                  ['state binding remembered', context.state.operatorRuntimeBindingProjectionsByRef['risk:eligibility'].activeBindingId, 'risk-eligibility-native-v1'],
                  ['readiness title', readiness.title, 'Adapter activation required'],
                  ['readiness level', readiness.level, 'warning'],
                  ['readiness executable', readiness.executable, false],
                  ['readiness state', readiness.state, 'DESIGN_ONLY'],
                  ['queue action type', queueAction.actionType, 'ACTIVATE_RUNTIME_ADAPTER'],
                  ['queue required action', queueAction.requiredNextAction, 'ACTIVATE_RUNTIME_ADAPTER'],
                  ['queue action label', context.operatorProjectionActionLabel(queueAction.actionType), 'Activate Runtime Adapter'],
                  ['badge includes state label', String(badge.includes('Activation Required')), 'true'],
                  ['badge includes action label', String(badge.includes('Activate Runtime Adapter')), 'true'],
                  ['panel includes next action', String(panel.includes('ACTIVATE_RUNTIME_ADAPTER')), 'true'],
                  ['panel includes promotion label', String(panel.includes('Promotion')), 'true'],
                  ['panel includes action button', String(panel.includes('data-open-operator-projection-action="risk:eligibility"')), 'true'],
                  ['panel includes action type', String(panel.includes('data-operator-projection-action-type="ACTIVATE_RUNTIME_ADAPTER"')), 'true'],
                  ['detail panel visible', context.detailTarget.hidden, false],
                  ['detail panel focused class', String(context.detailTarget.className.includes('warning')), 'true'],
                  ['detail includes operator label', String(detailHtml.includes('Eligibility')), 'true'],
                  ['detail includes source row', String(detailHtml.includes('risk-policy')), 'true'],
                  ['detail includes add action', String(detailHtml.includes('data-add-focused-operator="risk:eligibility"')), 'true'],
                  ['detail includes refresh action', String(detailHtml.includes('data-refresh-focused-operator="risk:eligibility"')), 'true'],
                  ['detail includes close action', String(detailHtml.includes('data-close-operator-detail')), 'true'],
                  ['detail includes projection action', String(detailHtml.includes('data-open-operator-projection-action="risk:eligibility"')), 'true'],
                  ['legacy operator ref', legacy.operator.operatorRef, 'risk:legacy'],
                  ['legacy binding projection', legacy.runtimeBindingProjection, null]
                ];
                for (const [label, actual, expected] of checks) {
                  if (actual !== expected) {
                    throw new Error(`${label}: expected ${expected}, got ${actual}`);
                  }
                }
                console.log('browser operator detail projection probe passed');
                """);
    }

    private static String paletteFitAutoConnectProbe() {
        return """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');
                new vm.Script(source, { filename: 'app.js' });

                function functionSource(name) {
                  let start = source.indexOf(`function ${name}(`);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  if (source.slice(Math.max(0, start - 6), start) === 'async ') {
                    start -= 6;
                  }
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                const context = vm.createContext({ console });
                for (const name of [
                  'numericCount',
                  'normalizeOperatorFitTarget',
                  'normalizeOperatorFitCandidate',
                  'operatorPaletteFitSourceHandle',
                  'operatorPaletteFitTargetForNode',
                  'addBuilderNodeFromPalette',
                  'applyPaletteFitConnection'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }

                const sourceEndpoint = { nodeId: 'riskNode', port: 'payload', path: 'score' };
                const targetNode = { id: 'auditNode', type: 'customOperator' };
                const rawFitCandidate = {
                  accepted: true,
                  operator: { operatorRef: 'risk:audit' },
                  targets: [{
                    targetSurface: 'input',
                    targetPort: 'inputs',
                    targetPath: 'score',
                    accepted: true,
                    message: 'Source integer can feed input score.'
                  }],
                  message: '1 compatible target.'
                };
                context.state = {
                  builder: {
                    nodes: [{ id: 'riskNode', type: 'customOperator' }, targetNode]
                  }
                };
                context.sourceHandlesForNode = (node) => node.id === 'riskNode'
                  ? [{ nodeId: 'riskNode', port: 'payload', path: 'score' }]
                  : [];
                context.targetHandlesForNode = (node) => node.id === 'auditNode'
                  ? [
                    { nodeId: 'auditNode', port: 'inputs', path: 'risk', key: 'inputs.risk' },
                    { nodeId: 'auditNode', port: 'inputs', path: 'score', key: 'inputs.score' }
                  ]
                  : [];
                context.targetWithSelectedUnionBranch = (node, target) => ({ ...target, unionChecked: node.id });

                const normalizedCandidate = context.normalizeOperatorFitCandidate(rawFitCandidate);
                const resolvedSource = context.operatorPaletteFitSourceHandle(sourceEndpoint);
                const resolvedTarget = context.operatorPaletteFitTargetForNode(targetNode, normalizedCandidate);
                const originalApplyPaletteFitConnection = context.applyPaletteFitConnection;

                let addType = '';
                let addPosition = null;
                let autoNode = '';
                let autoSource = '';
                let autoAccepted = false;
                context.OPERATOR_TYPES = { 'risk:audit': { operatorFitCandidate: rawFitCandidate } };
                context.operatorPaletteFitSourceEndpoint = () => sourceEndpoint;
                context.addBuilderNode = (type, position) => {
                  addType = type;
                  addPosition = position;
                  return targetNode;
                };
                context.applyPaletteFitConnection = (node, source, candidate) => {
                  autoNode = node.id;
                  autoSource = `${source.nodeId}.${source.port}.${source.path}`;
                  autoAccepted = candidate.accepted;
                  return Promise.resolve(true);
                };
                const addedNode = context.addBuilderNodeFromPalette('risk:audit', { x: 120, y: 240 });

                let checkCall = '';
                let applied = '';
                const messages = [];
                let editorRenders = 0;
                let diagramRenders = 0;
                context.applyPaletteFitConnection = originalApplyPaletteFitConnection;
                context.connectionAlreadyApplied = () => false;
                context.connectionCompatibility = () => ({ ok: false, message: 'local advisory' });
                context.connectionServerPreflightMessage = (compatibility, fallback) => fallback;
                context.checkVisualConnectionOnServer = async (source, target) => {
                  checkCall = `${source.nodeId}.${source.port}.${source.path}->${target.nodeId}.${target.port}.${target.path}`;
                  return { accepted: true, bindingKey: 'inputs.score', diagnostics: [], message: '' };
                };
                context.targetWithServerBindingKey = (target, serverCheck) => ({ ...target, key: serverCheck.bindingKey });
                context.applyConnection = (source, target) => {
                  applied = `${source.nodeId}.${source.port}.${source.path}->${target.nodeId}.${target.port}.${target.path}:${target.key}`;
                };
                context.connectionAppliedMessage = (source, target) =>
                  `Connected ${source.nodeId}.${source.port}.${source.path} -> ${target.nodeId}.${target.port}.${target.path}.`;
                context.specForNode = () => ({ label: 'Risk Audit' });
                context.setConnectionMessage = (text, level) => messages.push({ text, level });
                context.renderSelectedOperatorEditor = () => {
                  editorRenders += 1;
                };
                context.renderDiagram = () => {
                  diagramRenders += 1;
                };

                context.applyPaletteFitConnection(targetNode, sourceEndpoint, normalizedCandidate)
                  .then((connected) => {
                    const checks = [
                      ['source node', resolvedSource.nodeId, 'riskNode'],
                      ['source port', resolvedSource.port, 'payload'],
                      ['target path', resolvedTarget.path, 'score'],
                      ['target key', resolvedTarget.key, 'inputs.score'],
                      ['target union checked', resolvedTarget.unionChecked, 'auditNode'],
                      ['add wrapper type', addType, 'risk:audit'],
                      ['add wrapper x', addPosition.x, 120],
                      ['add wrapper node', addedNode.id, 'auditNode'],
                      ['add wrapper auto node', autoNode, 'auditNode'],
                      ['add wrapper auto source', autoSource, 'riskNode.payload.score'],
                      ['add wrapper normalized candidate', autoAccepted, true],
                      ['connected', connected, true],
                      ['server check call', checkCall, 'riskNode.payload.score->auditNode.inputs.score'],
                      ['applied call', applied, 'riskNode.payload.score->auditNode.inputs.score:inputs.score'],
                      ['preflight message', messages[0].text, 'Checking recommended connection with server...'],
                      ['preflight level', messages[0].level, 'info'],
                      ['success level', messages[1].level, 'success'],
                      ['success message', messages[1].text, 'Added Risk Audit and Connected riskNode.payload.score -> auditNode.inputs.score.'],
                      ['editor renders', editorRenders, 1],
                      ['diagram renders', diagramRenders, 1]
                    ];
                    for (const [label, actual, expected] of checks) {
                      if (actual !== expected) {
                        throw new Error(`${label}: expected ${expected}, got ${actual}`);
                      }
                    }
                    console.log('browser palette fit auto-connect probe passed');
                  })
                  .catch((error) => {
                    console.error(error);
                    process.exitCode = 1;
                  });
                """;
    }

    private static String bracketPathProbe() {
        return String.join("", """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');
                new vm.Script(source, { filename: 'app.js' });

                function functionSource(name) {
                  let start = source.indexOf(`function ${name}(`);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  if (source.slice(Math.max(0, start - 6), start) === 'async ') {
                    start -= 6;
                  }
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                const context = vm.createContext({ console, URLSearchParams });
                context.SUPPORTED_SCHEMA_UNION_KEYWORDS = ['oneOf', 'anyOf'];
                context.SUPPORTED_SCHEMA_CONDITIONAL_KEYWORDS = ['if', 'then', 'else'];
                context.VISUAL_DIAGNOSTIC_NODE_PREVIEW_LIMIT = 6;
                context.DEFAULT_COMPOSER_DECISION_TABLE = {
                  rows: [{
                    id: 'R1',
                    conditions: { score: 'score >= 700', amount: 'amount <= 300000' },
                    output: { decision: 'approved', rate: 4.5, maxTerm: 300, reviewLane: 'standard' }
                  }, {
                    id: 'R2',
                    conditions: { score: 'otherwise', amount: 'otherwise' },
                    output: { decision: 'declined', rate: 0, maxTerm: 0, reviewLane: 'decline' }
                  }]
                };
                for (const name of [
                  'pretty',
                  'escapeHtml',
                  'visualBundleFingerprintSuffix',
                  'arrayIndexSegment',
                  'dslReferenceSuffixForSchemaPath',
                  'contextExpressionForPath',
                  'schemaPathSegmentsFromDslReferenceSuffix',
                  'schemaPathFromDslReferenceSuffix',
                  'expressionWithPath',
                  'renderTemplateExpression',
                  'replaceTemplateDescendants',
                  'replaceTemplateReference',
                  'replaceUnresolvedTemplateReferences',
                  'expressionForConnectionSource',
                  'sourceFromOutputExpressionParts',
                  'connectionSourceFromExpression',
                  'bindingFromExpression',
                  'normalizedUnionBranchSelection',
                  'normalizedUnionBranchSelections',
                  'schemaUnionBranches',
                  'schemaUnionBranchOptions',
                  'schemaAllOfBranches',
                  'schemaAllOfLabel',
                  'schemaUnionLabel',
                  'schemaType',
                  'schemaEnumValues',
                  'finiteSchemaValues',
                  'schemaValueMatchesNot',
                  'schemaValueMatchesEffectiveNotSchema',
                  'effectiveNotValueSchema',
                  'schemaAllowsNull',
                  'effectiveSchemaType',
                  'schemaHasAnyKeyword',
                  'unionBranchSelectionValue',
                  'unionBranchSelectionFromValue',
                  'selectedUnionBranchSchema',
                  'targetSchemaForUnionSelection',
                  'endpointLabel',
                  'configPathSegments',
                  'hasConfigPath',
                  'configValueAtPath',
                  'setConfigValueAtPath',
                  'deleteConfigValueAtPath',
                  'unknownConfigPaths',
                  'isPlainObject',
                  'isConfigContainerObject',
                  'isConfigBindingObject',
                  'isConfigContainerValue',
                  'configHasSegment',
                  'configSegmentValue',
                  'setConfigSegmentValue',
                  'deleteConfigSegmentValue',
                  'emptyConfigContainerForNext',
                  'configContainerForNext',
                  'configContainerEmpty',
                  'arrayItemSchemaForIndex',
                  'schemaPrefixItems',
                  'schemaItemsSchema',
                  'residualArrayItemSchema',
                  'unevaluatedArrayItemsPolicy',
                  'rawSchemaType',
                  'compatibilitySchemaType',
                  'effectiveSchemaType',
                  'schemaHasAnyKeyword',
                  'residualPropertiesPolicy',
                  'additionalPropertySchema',
                  'matchingPatternPropertySchemas',
                  'patternPropertySchema',
                  'schemaPatternProperties',
                  'patternMatches',
                  'graphInputSchemaDiagnostic',
                  'validatedSchemaRequiredNames',
                  'validatedSchemaObjectProperties',
                  'validateSchemaStructure',
                  'validateSupportedSchemaConditionals',
                  'validateSchemaNot',
                  'effectiveNotSchemaKind',
                  'effectiveConditionalSchema',
                  'effectiveConditionalValidationSchema',
                  'effectiveDependentObjectSchema',
                  'validateSchemaEnumValues',
                  'validateSchemaAdditionalProperties',
                  'validateSchemaUnevaluatedProperties',
                  'validateSchemaObjectPatternProperties',
                  'validateSchemaObjectDependentRequired',
                  'validateSchemaObjectDependentSchemas',
                  'schemaReferenceDiagnostic',
                  'schemaFieldDescriptors',
                  'dslSafeSchemaFieldDescriptors',
                  'schemaFieldsFromSchema',
                  'arraySchemaFieldDescriptors',
                  'isSchemaPathDslSafe',
                  'childSchemaForPathSegment',
                  'schemaAtPath',
                  'dynamicInputFieldDescriptors',
                  'dynamicOutputFieldDescriptors',
                  'customInputPathForKey',
                  'customInputPortForKey',
                  'customOutputPathForKey',
                  'customOutputPortForKey',
                  'inputPortsForSpec',
                  'dslSafeInputPortsForSpec',
                  'outputPortsForSpec',
                  'dslSafeOutputPortsForSpec',
                  'allOutputPortsDslPathSafe',
                  'inputPortForInputPath',
                  'schemaDeclaresPath',
                  'configUnionBranchForPath',
                  'setConfigUnionBranchForPath',
                  'configFieldDescriptors',
                  'uniqueFieldsByPath',
                  'requiredInputNamesForPort',
                  'defaultInputExpressionsForOperator',
                  'defaultCustomInputStateForOperator',
                  'defaultResourceParamInputs',
                  'resourceParamInputs',
                  'cloneJsonValue',
                  'inputUnionBranchForTarget',
                  'inputUnionBranchesForPort',
                  'inputUnionBranchesForTarget',
                  'targetWithSelectedUnionBranch',
                  'setInputUnionBranchForTarget',
                  'expressionForTargetInput',
                  'setExpressionForTargetInput',
                  'renderRequiredInputAutoBindButton',
                  'requiredInputAutoBindSummary',
                  'requiredInputAutoBindPlan',
                  'autoBindRequiredInputsFromButton',
                  'applyRequiredInputAutoBindPlan',
                  'sourceHandlesForNode',
                  'targetHandlesForNode',
                  'canvasDataTargetHandlesForNode',
                  'canvasTargetHandlesForNode',
                  'configTargetsForNode',
                  'dependencyTargetsForNode',
                  'routeTargetsForNode',
                  'nodeSupportsDependencyTarget',
                  'nativeOperatorLowersConfigInput',
                  'usesNativeNodeBlock',
                  'nativeInputPathForTarget',
                  'usesNativeConfigInputField',
                  'outputPathOptionsForNode',
                  'outputSelectionPathForHandle',
                  'isConfigExpressionValue',
                  'configExpressionForField',
                  'removeConfigReferencesToNode',
                  'normalizeDiagnostics',
                  'visualCheckLevel',
                  'publishWarningAcknowledgementKey',
                  'publishVisualDraft',
                  'renderVisualDiagnosticFilterNotice',
                  'renderVisualDiagnosticSummary',
                  'visualDiagnosticSummary',
                  'visualDiagnosticNodeQueue',
                  'visualDiagnosticNodeDisplayLabel',
                  'visualDiagnosticPreviewNodes',
                  'visualDiagnosticOverflowText',
                  'visualDiagnosticQueuePositionText',
                  'visualDiagnosticQueueTarget',
                  'clearVisualDiagnosticNodeFilter',
                  'visualDiagnosticShortcutDirection',
                  'visualDiagnosticClearShortcut',
                  'visualDiagnosticNodeSummaryText',
                  'normalizeOpenApiOperations',
                  'openApiOperationLabel',
                  'openApiOperationReadinessSummary',
                  'openApiOperationMatchesCurrent',
                  'openApiSelectedOperation',
                  'openApiOperationIsBlocked',
                  'openApiBlockedProjectionMessage',
                  'renderOpenApiOperationSummary',
                  'renderOpenApiOperationSummaryPanel',
                  'openApiOperationStatusLevel',
                  'openApiOperationStatusMessage',
                  'applyOpenApiOperationSelection',
                  'renderLibraryProfilePanel',
                  'renderLibraryImportReadiness',
                  'renderLibraryImportReadinessHandoffGroups',
                  'renderLibraryImportReadinessCountRows',
                  'libraryImportReadinessCountRows',
                  'renderOperatorLibraryDiffPanel',
                  'operatorLibraryDiffLevel',
                  'operatorLibraryDiffRiskLevel',
                  'renderLibraryImpactPanel',
                  'renderSchemaChangeRows',
                  'librarySchemaChangesFromDiagnostics',
                  'uniqueSchemaChanges',
                  'normalizeSchemaChange',
                  'schemaChangeLevel',
                  'schemaChangeSurfaceLabel',
                  'schemaChangeMessage',
                  'libraryImpactSummaryFromPayload',
                  'libraryImpactDraftTargetsFromPayload',
                  'libraryImpactPublicationTargetsFromPayload',
                  'libraryImpactSummary',
                  'libraryImpactRefsFromDiagnostic',
                  'libraryImpactHighestLevel',
                  'libraryImpactSummaryLabel',
                  'libraryImpactRefGroup',
                  'changeRiskLabel',
                  'libraryImpactRiskSummaryText',
                  'resourceContractWarningAcknowledgementMessage',
                  'operatorLibraryWarningAcknowledgementMessage',
                  'changeRiskRank',
                  'openLibraryImpactDraft',
                  'openLibraryImpactDraftTarget',
                  'uniqueStrings',
                  'uniqueLibraryImpactDraftTargets',
                  'uniqueLibraryImpactPublicationTargets',
                  'libraryProfileLevel',
                  'operatorLibraryYamlProfilePreview',
                  'operatorLibraryLooksLikeYaml',
                  'operatorLibraryYamlScalar',
                  'libraryProfileFromText',
                  'operatorLibraryProfile',
                  'operatorLibraryOperatorProfile',
                  'operatorLibraryRuntimeReadiness',
                  'operatorLibraryPolicyProfile',
                  'operatorLibraryPolicyScope',
                  'emptyOperatorLibraryPortProfile',
                  'addOperatorLibraryPortProfile',
                  'operatorLibraryPortProfile',
                  'operatorLibraryPortFields',
                  'operatorLibraryConfigFields',
                  'operatorLibraryInputPortDslPathSafe',
                  'operatorLibraryOutputPortDslPathSafe',
                  'outputPortDslPathSafe',
                  'operatorLibraryFieldProfile',
                  'operatorLibrarySchemaSummary',
                  'operatorLibraryFieldLabel',
                  'operatorLibraryFieldAnnotationSummary',
                  'schemaAnnotationDescriptor',
                  'schemaFieldDisplayHint',
                  'schemaAnnotationText',
                  'schemaExamplesSummary',
                  'schemaValueSummary',
                  'visibleSchemaAnnotationSummary',
                  'compactSchemaAnnotation',
                  'schemaDynamicSurfaceCount',
                  'normalizeReadinessState',
                  'normalizeCountMap',
                  'normalizeVisualGraphNodeReadiness',
                  'normalizeRuntimeBindingRequirement',
                  'normalizeVisualGraphReadiness',
                  'normalizeVisualGraphActionReadiness',
                  'visualGraphReadinessStatusText',
                  'visualGraphReadinessNodeSummary',
                  'draftSummaryFor',
                  'draftSummaryReadinessState',
                  'draftSummaryReadinessLabel',
                  'draftAssetSummary',
                  'draftAssetSummaryLevel',
                  'draftAssetInterestingSummaries',
                  'draftAssetSummaryRows',
                  'draftAssetRowLevel',
                  'draftHistoryOptionLabel',
                  'renderVisualReadinessPanel',
                  'visualReadinessPanelSummary',
                  'visualReadinessPanelStats',
                  'visualActionReadinessRows',
                  'visualReadinessActionRows',
                  'visualRuntimeBindingRequirementCodeRows',
                  'visualReadinessNodeRows',
                  'publicationReadiness',
                  'publicationReadinessStatusText',
                  'publicationReadinessReviewRows',
                  'publicationReadinessNodeLabel',
                  'publicationReadinessNodeSummary',
                  'normalizePublishArtifactKind',
                  'publicationOptionLabel',
                  'publicationListReadinessLabel',
                  'publicationAssetSummary',
                  'publicationAssetSummaryLevel',
                  'publicationAssetSummaryRows',
                  'publicationAssetInterestingPublications',
                  'publicationAssetRowLevel',
                  'publishArtifactKindsForReadiness',
                  'preferredPublishArtifactKind',
                  'publishArtifactKindControlState',
                  'renderPublishArtifactKindControls',
                  'visualCheckStatusLevel',
                  'operatorDiagnosticsForSpec',
                  'operatorPaletteCapabilityBadges',
                  'operatorPaletteCapabilityLabels',
                  'operatorPaletteCapabilityFacetValues',
                  'normalizeOperatorRuntimeReadiness',
                  'normalizeProjectionLevel',
                  'normalizeOperatorRuntimeBindingProjection',
                  'normalizeOperatorExecutablePromotionProjection',
                  'operatorPaletteReadinessState',
                  'operatorPaletteLoweringMode',
                  'operatorPaletteFacetLabel',
                  'normalizeFacetCountMap',
                  'incrementFacetCount',
                  'operatorCatalogFallbackFacets',
                  'normalizeOperatorCatalogFacets',
                  'facetSummaryPart',
                  'operatorCatalogFacetSummary',
                  'operatorProjectionRuntimeReadiness',
                  'operatorProjectionQueueActionType',
                  'operatorProjectionQueueAction',
                  'operatorProjectionActionLabel',
                  'operatorPaletteProjectionBadge',
                  'operatorRuntimeReadiness',
                  'renderOperatorReadinessPanel',
                  'operatorPaletteDiagnosticBadges',
                  'operatorMatchesPaletteFilter',
                  'paletteSearchTokens',
                  'operatorPaletteSearchValues',
                  'operatorPaletteSchemaSearchValues',
                  'operatorPaletteFieldSearchValues',
                  'renderOperatorDiagnosticsPanel',
                  'bindingCandidateSummary',
                  'bindingCandidateSummaryLevel',
                  'bindingSourceValue',
                  'renderSourceCandidateOptions',
                  'renderSourceCandidateGroup',
                  'renderSourceCandidateOption',
                  'sourceCandidatesForTarget',
                  'sourceCandidateComparator',
                  'normalizeConnectionCheckSummary',
                  'normalizeConnectionRuntimeBindingCountMap',
                  'countRuntimeBindingRequirementsBy',
                  'connectionRuntimeBindingSummary',
                  'connectionCandidateTargetRuntimeBindingSummary',
                  'normalizeCountMap',
                  'normalizeRuntimeBindingRequirement',
                  'normalizeStringArray',
                  'numericCount',
                  'normalizeConnectionCandidateExplanation',
                  'normalizeConnectionCandidateRuntimeBindingImpact',
                  'connectionReplacementSummary',
                  'normalizeConnectionCandidate',
                  'normalizeConnectionCandidatesResult',
                  'connectionCandidateTargetSurface',
                  'connectionCandidateKindForSource',
                  'connectionCandidateKindForTarget',
                  'connectionCandidatePreviewSourceKey',
                  'connectionCandidatePreviewRequestKey',
                  'connectionCandidateTargetKey',
                  'connectionTargetRequiresFocusedCandidatePreview',
                  'connectionCandidatePreviewForTarget',
                  'connectionCandidatePreviewCoversTarget',
                  'connectionDragTargetDecision',
                  'connectionDragTargetMessage',
                  'connectionServerPreflightMessage',
                  'targetWithServerBindingKey',
                  'connectionLocalHeuristicStatus',
                  'connectionLocalMismatchIsAdvisory',
                  'orderedBuilderNodes',
                  'selectedBuilderNode',
                  'nonOverlappingNodePosition',
                  'rectanglesOverlap',
                  'clampNodePosition',
                  'uniqueNodeId',
                  'defaultOutputNodeForBuilder',
                  'defaultOutputPathForNode',
                  'ensureBuilderOutput',
                  'builderToVisualDraft',
                  'operatorFingerprintsForBuilder',
                  'defaultDecisionRules',
                  'builderNodeToDraftNode',
                  'visualDraftEdgeFromBuilderEdge',
                  'builderScope',
                  'parseGraphInputSchemaText',
                  'normalizeGraphInputSchemaEnvelope',
                  'graphInputSchemaStructuralDiagnostics',
                  'currentGraphInputSchema',
                  'schemaEnvelopeFromContextText',
                  'schemaFromValue',
                  'currentSavedDraftSnapshot',
                  'draftPatchOperations',
                  'draftLocalEditOperations',
                  'normalizeDraftForPatch',
                  'normalizeDraftForLocalEditGuard',
                  'normalizeVisualLayoutForLocalEditGuard',
                  'jsonPatchDiff',
                  'jsonPointerEscape',
                  'builderEdges',
                  'canonicalEdgeKind',
                  'builderConfigBindings',
                  'builderInputBindings',
                  'customBusinessConfig',
                  'graphOutputContractSummary',
                  'graphOutputSelectedSchema',
                  'outputReferenceFromSelectionPath',
                  'renderNodeConnectabilityPanel',
                  'renderNodeConnectabilityRow',
                  'nodeConnectabilitySourceFilterControl',
                  'nodeConnectabilitySourceFilterOptions',
                  'nodeConnectabilitySourceFilterKey',
                  'nodeConnectabilityFilteredSources',
                  'nodeConnectabilityDisplaySources',
                  'nodeConnectabilityDisplaySourceWindow',
                  'nodeConnectabilitySourceWindow',
                  'nodeConnectabilitySourceWindowKey',
                  'nodeConnectabilitySourceWindowOffset',
                  'nodeConnectabilitySetSourceWindowOffset',
                  'nodeConnectabilitySourceWindowLimit',
                  'nodeConnectabilitySourceWindowSourceKeys',
                  'nodeConnectabilitySourceWindowRequestOptions',
                  'nodeConnectabilitySourceWindowRequestScopeKey',
                  'nodeConnectabilitySourceWindowSummary',
                  'renderNodeConnectabilitySourceWindowControls',
                  'nodeConnectabilityDisplayTargets',
                  'nodeConnectabilityDisplayTargetWindow',
                  'nodeConnectabilityTargetWindow',
                  'nodeConnectabilityDisplayWindowKey',
                  'nodeConnectabilityDisplayWindowOffset',
                  'nodeConnectabilitySetDisplayWindowOffset',
                  'nodeConnectabilityBlockedPreviewLimit',
                  'nodeConnectabilityDisplayOverflowSummary',
                  'renderNodeConnectabilityDisplayWindowControls',
                  'nodeConnectabilityRowDomId',
                  'nodeConnectabilityTargetDomId',
                  'nodeConnectabilityWindowSummaryDomId',
                  'nodeConnectabilityPrioritizedDisplayTargets',
                  'nodeConnectabilityDisplayPriority',
                  'renderNodeConnectabilityFilterControls',
                  'nodeConnectabilityActiveFilter',
                  'nodeConnectabilityFilterIsActive',
                  'nodeConnectabilityFilterDisplayLimit',
                  'nodeConnectabilityFacetFilterControls',
                  'nodeConnectabilityFacetDefinitions',
                  'nodeConnectabilityFacetFilterControl',
                  'nodeConnectabilityFacetFilterOptions',
                  'nodeConnectabilityFacetFilterOptionLimit',
                  'nodeConnectabilityFacetSummaryLimit',
                  'nodeConnectabilityAllTargets',
                  'nodeConnectabilityFilteredTargets',
                  'nodeConnectabilityTargetMatchesFilter',
                  'nodeConnectabilityTargetMatchesFacetFilters',
                  'connectionCandidateFacetValueForTarget',
                  'nodeConnectabilityTargetStatus',
                  'nodeConnectabilityTargetSearchText',
                  'nodeConnectabilityFilterSummary',
                  'clearNodeConnectabilityFilter',
                  'renderNodeConnectabilityTarget',
                  'connectionAppliedMessage',
                  'connectNodeConnectabilityFromButton',
                  'nodeConnectabilitySourceFromButton',
                  'nodeConnectabilityTargetFromButton',
                  'nodeConnectabilitySummary',
                  'nodeConnectabilitySourceSummaryFor',
                  'nodeConnectabilityTargetsForSource',
                  'ensureNodeConnectabilityServerCandidates',
                  'nodeConnectabilityServerCandidatesForSource',
                  'nodeConnectabilityServerStateFor',
                  'renderNodeConnectabilityServerStatus',
                  'renderNodeConnectabilityServerControls',
                  'nodeConnectabilityServerWindowSummary',
                  'nodeConnectabilityServerFacetSummary',
                  'nodeConnectabilityServerFacetCounts',
                  'connectionCandidateFacetLabel',
                  'topConnectionCandidateFacet',
                  'connectionCandidateFacetOptionLabel',
                  'nodeConnectabilityServerWindowStats',
                  'nodeConnectabilityServerWindowLabel',
                  'nodeConnectabilityServerWindowFor',
                  'changeNodeConnectabilityServerWindowFromButton',
                  'nodeConnectabilityServerStateMatchesNode',
                  'nodeConnectabilityServerCandidateLimit',
                  'nodeConnectabilityServerDraftKey',
                  'nodeConnectabilityServerRequestKey',
                  'nodeConnectabilityServerActiveStatus',
                  'nodeConnectabilityServerStatusKey',
                  'nodeConnectabilityServerActiveSourceKey',
                  'nodeConnectabilityServerSourceKey',
                  'normalizeNodeConnectabilityServerSourceKeys',
                  'nodeConnectabilityServerSourceScopeKey',
                  'nodeConnectabilityServerActiveFacetFilters',
                  'nodeConnectabilityServerFacetFiltersKey',
                  'normalizeConnectionCandidateStatus',
                  'normalizeConnectionCandidateStatusCounts',
                  'normalizeConnectionCandidateFacetCounts',
                  'normalizeConnectionCandidateFacetValues',
                  'normalizeConnectionCandidateFacetFilters',
                  'canonicalConnectionCandidateFacetKey',
                  'normalizeConnectionCandidateFacetValue',
                  'compactStringHash',
                  'nodeConnectabilityTargetAppliesToSource',
                  'nodeConnectabilityTargetKind',
                  'nodeConnectabilityTotalsLabel',
                  'nodeConnectabilitySourceSummary',
                  'nodeConnectabilitySourceLevel',
                  'nodeConnectabilityTargetLevel',
                  'nodeConnectabilityTargetLabel',
                  'nodeConnectabilityTargetTitle',
                  'nodeConnectabilityTargetDetail',
                  'nodeConnectabilityTargetA11yPositionAttrs',
                  'recordBuilderHistory',
                  'clearBuilderHistory',
                  'undoBuilderEdit',
                  'redoBuilderEdit',
                  'restoreBuilderHistorySnapshot',
                  'renderBuilderHistoryControls',
                  'serializeBuilderHistory',
                  'deserializeBuilderHistory',
                  'builderHistoryShortcutTargetIsEditable',
                  'canvasSearchResults',
                  'canvasSearchEntries',
                  'renderNodeImpactPanel',
                  'renderNodeImpactSection',
                  'renderNodeImpactRow',
                  'renderNodeImpactClearButton',
                  'nodeImpactClearActions',
                  'uniqueNodeImpactActions',
                  'nodeImpactActionKey',
                  'clearNodeImpactRelationsFromButton',
                  'clearNodeImpactRelationsForNode',
                  'duplicateSelectedBuilderNode',
                  'duplicateBuilderNode',
                  'deleteSelectedBuilderNode',
                  'removeBuilderReferencesToNode',
                  'expressionReferencesNode',
                  'fallbackContextExpression',
                  'operatorUsageRefForNode',
                  'currentDraftHasUnsavedGraphChanges',
                  'operatorFingerprintRebaseBlockReason',
                  'refreshDraftConflictState',
                  'rebaseOperatorFingerprint',
                  'rebaseOperatorFingerprints',
                  'renderOperatorUsagePanel',
                  'renderOperatorFingerprintSnapshotPanel',
                  'refreshSelectedOperatorFingerprintPanel',
                  'operatorFingerprintSnapshotStatus',
                  'renderOperatorUsageContent',
                  'renderOperatorUsageSection',
                  'renderOperatorDraftUsageRow',
                  'renderOperatorPublicationUsageRow',
                  'renderOperatorUsageDiagnostics',
                  'operatorUsageResponseLevel',
                  'operatorUsageStatus',
                  'operatorUsageStatusLevel',
                  'operatorUsageFingerprintPair',
                  'operatorUsageShortFingerprint',
                  'operatorUsageDraftEntryForNode',
                  'operatorUsageChangeRisk',
                  'operatorUsageChangeLine',
                  'operatorUsageRiskActionLine',
                  'operatorUsageRiskActionText',
                  'operatorUsageHighestChangeRisk',
                  'operatorUsageSummaryForNode',
                  'operatorUsagePrimaryStatus',
                  'operatorUsageBadgeText',
                  'nodeImpactSummary',
                  'nodeImpactEdgeEntry',
                  'nodeImpactClearActionForEdge',
                  'nodeImpactEdgeDetail',
                  'nodeImpactPortPath',
                  'contextImpactEntriesForBindings',
                  'readableEdgeKind',
                  'nodeImpactRelationExists',
                  'clearNodeImpactRelation',
                  'nodeImpactInputTarget',
                  'inputKeyForNodeImpactTarget',
                  'clearNodeImpactConfigBinding',
                  'canonicalImpactActionKind',
                  'nodeImpactActionLabel',
                  'diagnosticsForCanvasNode',
                  'diagnosticTargetNodeId',
                  'nodeIdFromDiagnosticPointer',
                  'normalizeDiagnosticNodeTarget',
                  'jsonPointerUnescape',
                  'canvasNodeIssueText',
                  'renderSelectedNodeDiagnosticsPanel',
                  'selectedNodeDiagnosticsLevel',
                  'selectedNodeDiagnosticsSummary',
                  'renderSelectedNodeDiagnosticRow',
                  'renderSelectedNodeTraceRow',
                  'runTraceForCanvasNode',
                  'runTraceLevel',
                  'runTraceStatusLabel',
                  'shortRunId',
                  'clearActiveRunTrace',
                  'runTraceCanvasCoverage',
                  'canvasNodeIds',
                  'runTraceCoverageText',
                  'runTraceSummary',
                  'layoutGroupRegions',
                  'layoutGroupNodeIds',
                  'layoutGroupKindClass',
                  'nodeTraceBadgeText',
                  'nodeTraceSummaryLabel',
                  'goldenAssertionsFromControls',
                  'currentGoldenAssertionsFromControls',
                  'cloneGoldenAssertion',
                  'goldenAssertionExpectedSummary',
                  'inferredApproximateAssertionValue',
                  'valueAtJsonPointer',
                  'schemaFromValue',
                  'configFieldDescriptors',
                  'hasSchemaProperties',
                  'labelForNode',
                  'readableName'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }
                context.actualSourceHandlesForNode = context.sourceHandlesForNode;
                context.actualOutputPathOptionsForNode = context.outputPathOptionsForNode;
                context.DSL_FIELD_IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/;
                context.RESERVED_DSL_FIELD_NAMES = new Set(['graph', 'node', 'input', 'output', 'true', 'false']);
                const typelessArrayEnvelope = {
                  schema: {
                    items: {
                      properties: {
                        score: { type: 'integer' }
                      },
                      required: ['score']
                    }
                  }
                };
                const typelessArrayPaths = context.schemaFieldDescriptors(typelessArrayEnvelope)
                  .map((field) => field.path)
                  .join('|');
                const typelessArrayScoreSchema = context.schemaAtPath(typelessArrayEnvelope, '0.score') || {};
                const typelessPrefixEnvelope = {
                  schema: {
                    prefixItems: [{
                      properties: {
                        code: { type: 'string' }
                      }
                    }]
                  }
                };
                const typelessPrefixPaths = context.schemaFieldDescriptors(typelessPrefixEnvelope)
                  .map((field) => field.path)
                  .join('|');
                if (typelessArrayPaths !== '0|0.score') {
                  throw new Error(`Expected typeless array items to expand, got ${typelessArrayPaths}`);
                }
                if (typelessArrayScoreSchema.type !== 'integer') {
                  throw new Error(`Expected typeless array schemaAtPath to resolve 0.score, got ${JSON.stringify(typelessArrayScoreSchema)}`);
                }
                if (typelessPrefixPaths !== '0|0.code') {
                  throw new Error(`Expected typeless prefixItems to expand, got ${typelessPrefixPaths}`);
                }
                const typelessRequiredSchema = { required: ['traceId'], additionalProperties: true };
                const typelessContainsSchema = { contains: { type: 'string' }, minContains: 1 };
                if (context.rawSchemaType(typelessRequiredSchema) !== 'object' || context.schemaType(typelessRequiredSchema) !== 'object') {
                  throw new Error(`Expected typeless required-only schema to display as object, got raw=${context.rawSchemaType(typelessRequiredSchema)} type=${context.schemaType(typelessRequiredSchema)}`);
                }
                if (context.rawSchemaType(typelessContainsSchema) !== 'array' || context.schemaType(typelessContainsSchema) !== 'array') {
                  throw new Error(`Expected typeless contains-only schema to display as array, got raw=${context.rawSchemaType(typelessContainsSchema)} type=${context.schemaType(typelessContainsSchema)}`);
                }
                context.SUPPORTED_SCHEMA_KINDS = new Set(['string', 'integer', 'number', 'decimal', 'boolean', 'object', 'array', 'enum', 'duration', 'datetime', 'null']);
                const schemaValidationStubNames = [
                  'validateUnsupportedSchemaKeywords',
                  'validateSupportedSchemaUnions',
                  'validateSupportedSchemaAllOf',
                  'validateSupportedSchemaConditionals',
                  'validateSchemaDefinitions',
                  'validateSchemaNot',
                  'validateSchemaEnum',
                  'validateSchemaConst',
                  'validateSchemaNumericBounds',
                  'validateSchemaNumericMultipleOf',
                  'validateSchemaStringLengthBounds',
                  'validateSchemaStringPattern',
                  'validateSchemaStringFormat',
                  'validateSchemaArrayItemBounds',
                  'validateSchemaArrayUniqueItems',
                  'validateSchemaArrayPrefixItems',
                  'validateSchemaArrayContains',
                  'validateSchemaArrayUnevaluatedItems',
                  'validateSchemaObjectPropertyBounds',
                  'validateSchemaObjectPatternProperties',
                  'validateSchemaObjectPropertyNames',
                  'validateSchemaUnevaluatedProperties',
                  'validateSchemaAdditionalProperties',
                  'validateCustomSchemaEnumValues'
                ];
                const schemaValidationOriginals = new Map();
                for (const name of schemaValidationStubNames) {
                  schemaValidationOriginals.set(name, context[name]);
                  context[name] = () => {};
                }
                schemaValidationOriginals.set('validateSchemaTypeArray', context.validateSchemaTypeArray);
                context.validateSchemaTypeArray = () => false;
                const requiredOnlyDiagnostics = [];
                context.validateSchemaStructure(typelessRequiredSchema, 'schema', requiredOnlyDiagnostics);
                if (requiredOnlyDiagnostics.some((diagnostic) => diagnostic.code === 'visual.schema.requiredUnknown')) {
                  throw new Error(`Browser schema mirror still rejects required-only object schema: ${JSON.stringify(requiredOnlyDiagnostics)}`);
                }
                const dependentRequiredDiagnostics = [];
                context.validateSchemaObjectDependentRequired({
                  dependentRequired: {
                    paymentMethod: ['cardNumber']
                  }
                }, 'object', 'schema', dependentRequiredDiagnostics);
                if (dependentRequiredDiagnostics.some((diagnostic) => diagnostic.code === 'visual.schema.dependentRequiredUnknown')) {
                  throw new Error(`Browser schema mirror still rejects dependentRequired without properties: ${JSON.stringify(dependentRequiredDiagnostics)}`);
                }
                const duplicateDependentDiagnostics = [];
                context.validateSchemaObjectDependentRequired({
                  dependentRequired: {
                    paymentMethod: ['cardNumber', 'cardNumber']
                  }
                }, 'object', 'schema', duplicateDependentDiagnostics);
                if (!duplicateDependentDiagnostics.some((diagnostic) => diagnostic.code === 'visual.schema.dependentRequiredDuplicate')) {
                  throw new Error(`Expected duplicate dependentRequired diagnostics to remain, got ${JSON.stringify(duplicateDependentDiagnostics)}`);
                }
                const originalValidateSchemaStructure = context.validateSchemaStructure;
                context.validateSchemaStructure = () => {};
                const dependentSchemasDiagnostics = [];
                context.validateSchemaObjectDependentSchemas({
                  dependentSchemas: {
                    riskFlag: { required: ['reviewReason'] }
                  }
                }, 'object', 'schema', dependentSchemasDiagnostics);
                context.validateSchemaStructure = originalValidateSchemaStructure;
                if (dependentSchemasDiagnostics.some((diagnostic) => diagnostic.code === 'visual.schema.dependentSchemasUnknown')) {
                  throw new Error(`Browser schema mirror still rejects dependentSchemas without properties: ${JSON.stringify(dependentSchemasDiagnostics)}`);
                }
                for (const [name, original] of schemaValidationOriginals.entries()) {
                  context[name] = original;
                }

                context.BUILDER_HISTORY_LIMIT = 50;
                context.CONTEXT_SOURCE_ID = '__ctx';
                context.NODE_SIZE = { width: 170, height: 74 };
                context.PUBLISH_ARTIFACT_KINDS = ['EXECUTABLE', 'DESIGN'];
                context.state = { draftSummaries: [] };
                context.elements = {};
                context.$ = (id) => context.elements[id] || null;
                context.syncGraphInputSchemaTextFromBuilder = () => {};
                context.syncComposerFromBuilder = () => {};
                context.renderScenario = () => {
                  context.renderCount = (context.renderCount || 0) + 1;
                };
                context.contextSourceForPath = (path) => ({ nodeId: '__ctx', path });
                context.sourceHandlesForNode = () => [];
                context.specForNode = (node = {}) => {
                  if (node.id === 'riskNode') {
                    return {
                      label: 'Eligibility',
                      inputPort: 'inputs',
                      outputPort: 'payload',
                      outputPorts: [{
                        name: 'payload',
                        required: true,
                        schema: {
                          schema: {
                            type: 'object',
                            properties: {
                              eligible: { type: 'boolean' },
                              score: { type: 'integer' },
                              facts: {
                                type: 'object',
                                properties: {
                                  reason: { type: 'string' }
                                },
                                required: ['reason']
                              }
                            },
                            required: ['eligible', 'score']
                          }
                        }
                      }],
                      ports: [{
                        name: 'inputs',
                        schema: {
                          schema: {
                            type: 'object',
                            properties: {
                              score: { type: 'integer' },
                              amount: { type: 'integer' }
                            },
                            required: ['score', 'amount']
                          }
                        }
                      }],
                      fingerprint: 'current-fingerprint-123456',
                      configSchema: {
                        schema: {
                          type: 'object',
                          properties: {
                            mode: { type: 'string' },
                            threshold: { type: 'integer' }
                          }
                        }
                      }
                    };
                  }
                  if (node.id === 'unsafeOutputNode') {
                    return {
                      label: 'Unsafe output',
                      outputPort: 'graph',
                      outputPorts: [{
                        name: 'graph',
                        required: true,
                        schema: {
                          schema: {
                            type: 'object',
                            properties: {
                              score: { type: 'integer' }
                            }
                          }
                        }
                      }]
                    };
                  }
                  if (node.id === 'mixedOutputNode') {
                    return {
                      label: 'Mixed output',
                      outputPort: 'facts',
                      outputPorts: [{
                        name: 'graph',
                        required: true,
                        schema: {
                          schema: {
                            type: 'object',
                            properties: {
                              hiddenScore: { type: 'integer' }
                            }
                          }
                        }
                      }, {
                        name: 'facts',
                        required: true,
                        schema: {
                          schema: {
                            type: 'object',
                            properties: {
                              score: { type: 'integer' }
                            }
                          }
                        }
                      }]
                    };
                  }
                  if (node.id === 'unsafeInputNode') {
                    return {
                      label: 'Unsafe input',
                      inputPort: 'input',
                      outputPort: 'output',
                      ports: [{
                        name: 'input',
                        required: true,
                        schema: {
                          schema: {
                            type: 'object',
                            properties: {
                              score: { type: 'integer' }
                            },
                            required: ['score']
                          }
                        }
                      }],
                      outputPorts: [{
                        name: 'output',
                        schema: { schema: { type: 'object', properties: { accepted: { type: 'boolean' } } } }
                      }]
                    };
                  }
                  if (node.id === 'nativeConfigPolicy') {
                    return {
                      label: 'Native config policy',
                      inputPort: 'inputs',
                      outputPort: 'output',
                      sourceKind: 'user-library',
                      lowering: { mode: 'native', operatorRef: 'riskNativeConfigPolicy' },
                      ports: [{
                        name: 'inputs',
                        required: true,
                        schema: {
                          schema: {
                            type: 'object',
                            properties: {
                              config: { type: 'integer' },
                              score: { type: 'integer' }
                            },
                            required: ['config', 'score']
                          }
                        }
                      }],
                      outputPorts: [{
                        name: 'output',
                        schema: { schema: { type: 'object', properties: { accepted: { type: 'boolean' } } } }
                      }],
                      configSchema: {
                        schema: {
                          type: 'object',
                          properties: {
                            limit: { type: 'integer' }
                          }
                        }
                      }
                    };
                  }
                  if (node.id === 'auditNode') {
                    return {
                      label: 'Audit',
                      inputPort: 'inputs',
                      outputPort: 'payload',
                      outputPorts: [{ name: 'payload', schema: { schema: { type: 'object' } } }],
                      ports: [{
                        name: 'inputs',
                        schema: { fields: [{ path: 'risk' }] }
                      }]
                    };
                  }
                  if (node.id === 'unionInputNode') {
                    return {
                      label: 'Union Input',
                      inputPort: 'inputs',
                      outputPort: 'payload',
                      ports: [{
                        name: 'inputs',
                        required: true,
                        schema: {
                          schema: {
                            type: 'object',
                            properties: {
                              value: {
                                oneOf: [
                                  { type: 'integer' },
                                  { type: 'string' }
                                ]
                              }
                            },
                            required: ['value']
                          }
                        }
                      }],
                      outputPorts: [{ name: 'payload', schema: { schema: { type: 'object' } } }]
                    };
                  }
                  if (node.id === 'policy') {
                    return { label: 'Decision Table', inputPort: 'inputs', outputPort: 'output' };
                  }
                  return { outputPort: 'payload' };
                };
                context.outputPortsForSpec = (spec) => spec?.outputPorts || [{ name: 'payload' }];
                context.schemaForPort = (spec, role, portName) => {
                  const ports = role === 'source' ? (spec?.outputPorts || []) : (spec?.ports || []);
                  return ports.find((port) => port.name === portName)?.schema || { schema: { type: 'array' } };
                };
                context.schemaAtPath = (schemaEnvelope, path) => {
                  let current = schemaEnvelope?.schema || schemaEnvelope || {};
                  for (const segment of String(path || '').split('.').filter(Boolean)) {
                    if (current.type === 'array') {
                      current = current.items || {};
                      continue;
                    }
                    if (current.properties && Object.prototype.hasOwnProperty.call(current.properties, segment)) {
                      current = current.properties[segment] || {};
                      continue;
                    }
                    return { type: 'path', path };
                  }
                  return current;
                };
                context.schemaType = (schema) => schema?.type || '';
                context.isDslPathSafe = () => true;
                context.actualIsSchemaPathDslSafe = context.isSchemaPathDslSafe;
                context.isSchemaPathDslSafe = () => true;
                context.inputPortsForSpec = (spec) => spec.inputPorts || spec.ports || [];
                context.schemaDefaultInputFields = (schema) => schema?.fields || [];
                context.inputKeyForPortPath = (_spec, port, path) => `${port}.${path}`;
                context.expressionReferencesNode = (expression, nodeId) =>
                  String(expression || '').includes(`${nodeId}.output`);
                context.SUPPORTED_SCHEMA_KINDS = new Set(['object', 'array', 'string', 'integer', 'number', 'boolean', 'null', 'any']);
                context.graphInputSchemaDiagnostic = (code, message, target) => ({
                  code,
                  message,
                  target: `/inputSchema/${target}`
                });
                context.isDslFieldName = (value) => /^[A-Za-z_][A-Za-z0-9_]*$/.test(String(value || ''))
                  && !new Set(['graph', 'node', 'input', 'output', 'true', 'false']).has(String(value || ''));
                context.validatedSchemaObjectProperties = (schema) => {
                  const properties = schema?.properties;
                  return properties && typeof properties === 'object' && !Array.isArray(properties) ? properties : {};
                };
                context.validatedSchemaRequiredNames = () => [];
                context.operatorLibraryPortUnionSummary = context.schemaUnionSummary = () => '';
                for (const name of [
                  'validateUnsupportedSchemaKeywords',
                  'validateSupportedSchemaUnions',
                  'validateSupportedSchemaAllOf',
                  'validateSchemaDefinitions',
                  'validateSchemaEnum',
                  'validateSchemaConst',
                  'validateSchemaNumericBounds',
                  'validateSchemaNumericMultipleOf',
                  'validateSchemaStringLengthBounds',
                  'validateSchemaStringPattern',
                  'validateSchemaStringFormat',
                  'validateSchemaArrayItemBounds',
                  'validateSchemaArrayUniqueItems',
                  'validateSchemaArrayPrefixItems',
                  'validateSchemaArrayContains',
                  'validateSchemaArrayUnevaluatedItems',
                  'validateSchemaObjectPropertyBounds',
                  'validateSchemaObjectPropertyNames',
                  'validateSchemaObjectDependentRequired',
                  'validateSchemaObjectDependentSchemas',
                  'validateCustomSchemaEnumValues'
                ]) {
                  context[name] = () => {};
                }
                context.validateSchemaTypeArray = () => false;

                const resolvedPortPath = context.sourceFromOutputExpressionParts(
                  { id: 'facts' },
                  'payload[0].score'
                );
                const parsedContext = context.connectionSourceFromExpression(
                  'ctx.scores[0].value',
                  { nodes: [] }
                );
                const parsedUnknownOutput = context.connectionSourceFromExpression(
                  'unknown.output.items[0].score',
                  { nodes: [] }
                );
                const contextBinding = context.bindingFromExpression(
                  'ctx.scores[0].value',
                  { builder: { nodes: [] } }
                );
                const outputBinding = context.bindingFromExpression(
                  'unknown.output.items[0].score',
                  { builder: { nodes: [] } }
                );
                const unionTargetBinding = context.bindingFromExpression(
                  'unknown.output.items[0].score',
                  {
                    builder: { nodes: [] },
                    targetPort: 'inputs',
                    targetPath: 'decision',
                    targetUnionBranch: { keyword: 'oneOf', index: 1 }
                  }
                );
                const nestedUnionTargetBinding = context.bindingFromExpression(
                  'unknown.output.items[0].score',
                  {
                    builder: { nodes: [] },
                    targetPort: 'inputs',
                    targetPath: 'payload.score',
                    targetUnionBranches: { payload: { keyword: 'oneOf', index: 0 } }
                  }
                );
                const nestedUnionTargetSchema = {
                  schema: {
                    type: 'object',
                    properties: {
                      payload: {
                        oneOf: [
                          {
                            type: 'object',
                            properties: { score: { type: 'integer' } },
                            required: ['score'],
                            additionalProperties: false
                          },
                          {
                            type: 'object',
                            properties: { decision: { type: 'string' } },
                            required: ['decision'],
                            additionalProperties: false
                          }
                        ]
                      }
                    }
                  }
                };
                const nestedUnionBranchSelections = { payload: { keyword: 'oneOf', index: 0 } };
                const nestedUnionFieldPaths = context.schemaFieldDescriptors(
                  nestedUnionTargetSchema,
                  nestedUnionBranchSelections
                ).map((field) => field.path).join('|');
                const configUnionNode = {};
                context.setConfigUnionBranchForPath(configUnionNode, 'payload', { keyword: 'oneOf', index: 0 });
                const configUnionSelection = context.configUnionBranchForPath(configUnionNode, 'payload');
                const configUnionFieldPaths = context.configFieldDescriptors(
                  nestedUnionTargetSchema,
                  configUnionNode.configUnionBranches
                ).map((field) => field.path).join('|');
                const configUnionAllowedUnknownPaths = context.unknownConfigPaths(
                  { payload: { score: 720 } },
                  nestedUnionTargetSchema.schema,
                  '',
                  configUnionNode.configUnionBranches
                ).join('|');
                const configUnionRejectedUnknownPaths = context.unknownConfigPaths(
                  { payload: { decision: 'APPROVE' } },
                  nestedUnionTargetSchema.schema,
                  '',
                  configUnionNode.configUnionBranches
                ).join('|');
                const unsafeContextExpression = context.bindingFromExpression(
                  'ctx.customer-id',
                  { builder: { nodes: [] } }
                );
                const config = {};
                context.setConfigValueAtPath(config, 'thresholds.0', { kind: 'expression', expr: 'ctx.score' });
                context.setConfigValueAtPath(config, 'matrix.0.score', 720);
                const configArrayBeforeDelete = Array.isArray(config.thresholds);
                const configValueBeforeDelete = context.configValueAtPath(config, 'thresholds.0').expr;
                const configNestedArrayBeforeDelete = Array.isArray(config.matrix);
                const configNestedObjectValue = context.configValueAtPath(config, 'matrix.0.score');
                context.deleteConfigValueAtPath(config, 'thresholds.0');
                const arrayInputSpec = {
                  inputPorts: [{
                    name: 'inputs',
                    schema: {
                      fields: [{ path: 'scores.0.value' }, { path: 'amount' }],
                      schema: {
                        type: 'object',
                        properties: {
                          scores: {
                            type: 'array',
                            items: {
                              type: 'object',
                              properties: {
                                value: { type: 'integer' }
                              },
                              required: ['value']
                            }
                          },
                          amount: { type: 'integer' }
                        },
                        required: ['scores', 'amount']
                      }
                    }
                  }]
                };
                const defaultInputs = context.defaultInputExpressionsForOperator(arrayInputSpec);
                const customInputs = context.defaultCustomInputStateForOperator(arrayInputSpec);
                const unsafeDefaultInputSpec = {
                  inputPort: 'input',
                  inputPorts: [{
                    name: 'input',
                    schema: {
                      schema: {
                        type: 'object',
                        properties: {
                          score: { type: 'integer' }
                        },
                        required: ['score']
                      }
                    }
                  }]
                };
                const unsafeDefaultInputs = context.defaultInputExpressionsForOperator(unsafeDefaultInputSpec);
                const unsafeDefaultCustomInputs = context.defaultCustomInputStateForOperator(unsafeDefaultInputSpec);
                const resourceInputs = context.defaultResourceParamInputs(arrayInputSpec);
                const resourceFallbackInputs = context.resourceParamInputs(
                  { paramName: 'scores.0.value' },
                  { ports: [] }
                );
                const configWithArrayReferences = {
                  thresholds: [
                    { kind: 'expression', expr: 'deletedNode.output[0].score' },
                    { kind: 'expression', expr: 'keptNode.output.score' },
                    42
                  ],
                  nested: {
                    values: [
                      { kind: 'expression', expr: 'deletedNode.output.value' },
                      'static'
                    ]
                  }
                };
                context.removeConfigReferencesToNode(configWithArrayReferences, 'deletedNode');
                const unknownArrayConfigPaths = context.unknownConfigPaths(
                  {
                    rules: [
                      { limit: 10, extra: true },
                      { limit: 20 }
                    ]
                  },
                  {
                    type: 'object',
                    properties: {
                      rules: {
                        type: 'array',
                        items: {
                          type: 'object',
                          properties: { limit: { type: 'integer' } },
                          additionalProperties: false
                        }
                      }
                    },
                    additionalProperties: false
                  },
                  ''
                ).sort().join('|');
                const dynamicSchemaDiagnostics = [];
                context.validateSchemaStructure({
                  type: 'object',
                  properties: {
                    dynamicAdditional: {
                      type: 'object',
                      additionalProperties: {
                        type: 'object',
                        properties: { 'bad-field': { type: 'string' } },
                        additionalProperties: false
                      }
                    },
                    patterned: {
                      type: 'object',
                      patternProperties: {
                        '^item\\\\.[a-z]+$': {
                          type: 'object',
                          properties: { 'bad-pattern-field': { type: 'string' } },
                          additionalProperties: false
                        }
                      },
                      additionalProperties: false
                    },
                    dynamicResidual: {
                      type: 'object',
                      unevaluatedProperties: {
                        type: 'object',
                        properties: { 'bad-residual-field': { type: 'string' } },
                        unevaluatedProperties: false
                      }
                    }
                  }
                }, 'schema', dynamicSchemaDiagnostics);
                const dynamicSchemaDslTargets = dynamicSchemaDiagnostics
                  .filter((diagnostic) => diagnostic.code === 'visual.inputSchema.dslField.invalid')
                  .map((diagnostic) => diagnostic.target)
                  .sort()
                  .join('|');
                context.isSchemaPathDslSafe = context.actualIsSchemaPathDslSafe;
                const unsafePathSchema = {
                  schema: {
                    type: 'object',
                    properties: {
                      safeScore: { type: 'integer' },
                      'bad-field': { type: 'integer' },
                      items: {
                        type: 'array',
                        items: {
                          type: 'object',
                          properties: {
                            safeNested: { type: 'string' },
                            'bad-nested': { type: 'string' }
                          }
                        }
                      }
                    }
                  }
                };
                const dslSafeStaticPaths = context.dslSafeSchemaFieldDescriptors(unsafePathSchema)
                  .map((field) => field.path)
                  .sort()
                  .join('|');
                const dynamicInputPaths = context.dynamicInputFieldDescriptors({
                  type: 'customOperator',
                  customInputPorts: {
                    safeScore: 'inputs',
                    'bad-field': 'inputs'
                  },
                  customInputPaths: {
                    safeScore: 'safeScore',
                    'bad-field': 'bad-field'
                  }
                }, {}, 'inputs', unsafePathSchema)
                  .map((field) => field.path)
                  .sort()
                  .join('|');
                const dynamicOutputPaths = context.dynamicOutputFieldDescriptors({
                  type: 'customOperator',
                  customOutputPorts: {
                    safeScore: 'facts',
                    'bad-field': 'facts'
                  },
                  customOutputPaths: {
                    safeScore: 'safeScore',
                    'bad-field': 'bad-field'
                  }
                }, {}, 'facts', unsafePathSchema)
                  .map((field) => field.path)
                  .sort()
                  .join('|');
                const operatorDiagnosticSpec = {
                  diagnostics: [{
                    level: 'WARNING',
                    code: 'visual.catalog.operatorRefShadowed',
                    message: 'OperatorRef shadowed by runtime Java operator.',
                    target: '/operators/risk:eligibility'
                  }]
                };
                const operatorDiagnosticSearchValues = context.operatorPaletteSearchValues(operatorDiagnosticSpec)
                  .filter(Boolean)
                  .join('|');
                const operatorDiagnosticBadge = context.operatorPaletteDiagnosticBadges(operatorDiagnosticSpec);
                const operatorDiagnosticPanel = context.renderOperatorDiagnosticsPanel(operatorDiagnosticSpec);
                const paletteSearchSpec = {
                  kind: 'custom',
                  label: 'Eligibility',
                  operatorRef: 'risk:eligibility',
                  sourceKind: 'user-library',
                  operatorLibraryId: 'risk-policy',
                  tags: ['risk', 'policy'],
                  capabilities: { effect: 'PURE', idempotency: 'DETERMINISTIC' },
                  lowering: { mode: 'transform' },
                  inputPorts: [{
                    name: 'inputs',
                    schema: {
                      schema: {
                        type: 'object',
                        properties: {
                          customer: {
                            type: 'object',
                            properties: {
                              id: {
                                type: 'string',
                                title: 'Customer identifier',
                                description: 'External customer id used by the risk policy.',
                                examples: ['C-1001']
                              }
                            }
                          }
                        }
                      }
                    }
                  }],
                  outputPorts: [{
                    name: 'payload',
                    schema: {
                      schema: {
                        type: 'object',
                        properties: {
                          score: {
                            type: 'integer',
                            title: 'Eligibility score',
                            description: 'Normalized risk score.',
                            examples: [720]
                          }
                        }
                      }
                    }
                  }],
                  configSchema: {
                    schema: {
                      type: 'object',
                      properties: {
                        threshold: {
                          type: 'number',
                          title: 'Risk threshold',
                          description: 'Minimum accepted score.',
                          examples: [0.72],
                          default: 0.5,
                          $comment: 'Authoring-time policy control.'
                        }
                      }
                    }
                  }
                };
                const paletteSchemaSearchValues = context.operatorPaletteSearchValues(paletteSearchSpec);
                const paletteInputField = context.schemaFieldDescriptors(paletteSearchSpec.inputPorts[0].schema)
                  .find((field) => field.path === 'customer.id') || {};
                const paletteConfigField = context.configFieldDescriptors(paletteSearchSpec.configSchema)
                  .find((field) => field.path === 'threshold') || {};
                const paletteInputFieldHint = context.schemaFieldDisplayHint(paletteInputField);
                const paletteConfigFieldHint = context.schemaFieldDisplayHint(paletteConfigField);
                const paletteCommentOnlyHint = context.schemaFieldDisplayHint({
                  commentSummary: 'Internal authoring note.'
                });
                const suspendablePaletteSpec = {
                  kind: 'custom',
                  label: 'Await Approval',
                  operatorRef: 'awaitApproval',
                  sourceKind: 'java-suspendable-operator',
                  tags: ['java'],
                  capabilities: {
                    effect: 'WRITE_EXTERNAL',
                    streaming: false,
                    durable: true,
                    requiresSecrets: true
                  },
                  inputPorts: [],
                  outputPorts: [],
                  configSchema: { schema: { type: 'object', properties: {} } }
                };
                const suspendableCapabilityLabels = context.operatorPaletteCapabilityLabels(suspendablePaletteSpec).join('|');
                const suspendableCapabilityBadges = context.operatorPaletteCapabilityBadges(suspendablePaletteSpec);
                const designOnlyPaletteSpec = {
                  kind: 'custom',
                  label: 'Partner Decision',
                  operatorRef: 'partner:decision',
                  sourceKind: 'user-library',
                  lowering: { mode: 'design' },
                  capabilities: { effect: 'PURE' },
                  inputPorts: [],
                  outputPorts: [],
                  configSchema: { schema: { type: 'object', properties: {} } }
                };
                const designOnlyCapabilityLabels = context.operatorPaletteCapabilityLabels(designOnlyPaletteSpec).join('|');
                const designOnlyCapabilityFacets = context.operatorPaletteCapabilityFacetValues(designOnlyPaletteSpec).join('|');
                const designOnlyReadinessFacet = context.operatorPaletteReadinessState(designOnlyPaletteSpec);
                const executableCapabilityFacets = context.operatorPaletteCapabilityFacetValues(paletteSearchSpec).join('|');
                const executableReadinessFacet = context.operatorPaletteReadinessState(paletteSearchSpec);
                const governedPaletteSpec = {
                  kind: 'custom',
                  label: 'Write Audit',
                  operatorRef: 'risk:writeAudit',
                  sourceKind: 'user-library',
                  lowering: { mode: 'native', operatorRef: 'riskWriteAudit' },
                  capabilities: {
                    effect: 'WRITE_EXTERNAL',
                    idempotency: 'NON_IDEMPOTENT',
                    streaming: false,
                    durable: false,
                    requiresSecrets: true
                  },
                  inputPorts: [],
                  outputPorts: [],
                  configSchema: { schema: { type: 'object', properties: {} } }
                };
                const serverReadinessSpec = {
                  kind: 'custom',
                  label: 'Server Readiness',
                  operatorRef: 'risk:serverReady',
                  sourceKind: 'user-library',
                  lowering: { mode: 'native', operatorRef: 'riskServerReady' },
                  capabilities: { effect: 'PURE' },
                  runtimeReadiness: {
                    state: 'GOVERNANCE_REVIEW',
                    level: 'warning',
                    executable: true,
                    artifactKinds: ['EXECUTABLE'],
                    title: 'Server authoritative readiness',
                    summary: 'Use the catalog-provided readiness instead of browser heuristics.',
                    details: [{ label: 'Governance', value: 'server-reviewed' }]
                  },
                  inputPorts: [],
                  outputPorts: [],
                  configSchema: { schema: { type: 'object', properties: {} } }
                };
                const executableReadiness = context.operatorRuntimeReadiness(paletteSearchSpec);
                const designOnlyReadiness = context.operatorRuntimeReadiness(designOnlyPaletteSpec);
                const designOnlyReadinessPanel = context.renderOperatorReadinessPanel(designOnlyPaletteSpec);
                const suspendableReadiness = context.operatorRuntimeReadiness(suspendablePaletteSpec);
                const governedReadiness = context.operatorRuntimeReadiness(governedPaletteSpec);
                const governedReadinessPanel = context.renderOperatorReadinessPanel(governedPaletteSpec);
                const serverReadiness = context.operatorRuntimeReadiness(serverReadinessSpec);
                const serverReadinessPanel = context.renderOperatorReadinessPanel(serverReadinessSpec);
                const serverReadinessFacet = context.operatorPaletteReadinessState(serverReadinessSpec);
                const graphReadiness = context.normalizeVisualGraphReadiness({
                  schemaVersion: 'bloge.visualGraphReadiness.v1',
                  state: 'DESIGN_ONLY',
                  level: 'INFO',
                  executable: false,
                  artifactKinds: ['design'],
                  title: 'Design-only graph',
                  summary: 'Freeze as design artifact.',
                  nodeCount: 2,
                  runtimeExecutableNodeCount: 1,
                  designOnlyNodeCount: 1,
                  runtimeBlockedNodeCount: 0,
                  governanceReviewNodeCount: 0,
                  draftRepairNodeCount: 0,
                  runtimeBindingRequirementCount: 1,
                  runtimeBindingRequirements: [
                    {
                      nodeId: 'eligibility',
                      operatorRef: 'risk:eligibility',
                      state: 'DESIGN_ONLY',
                      level: 'INFO',
                      sourceKind: 'user-library',
                      loweringMode: 'design',
                      bindingKind: 'executable-lowering',
                      bindingTarget: 'risk:eligibility',
                      handoffLane: 'operator-platform',
                      handoffKind: 'operator-implementation',
                      handoffTarget: 'risk:eligibility',
                      title: 'Executable lowering required',
                      recommendedAction: 'Bind executable lowering before EXECUTABLE promotion.'
                    }
                  ],
                  nodes: [
                    {
                      nodeId: 'eligibility',
                      operatorRef: 'risk:eligibility',
                      state: 'DESIGN_ONLY',
                      level: 'INFO',
                      executable: false,
                      title: 'Design-only operator',
                      diagnosticCount: 0
                    }
                  ]
                });
                const graphReadinessStatusText = context.visualGraphReadinessStatusText(graphReadiness);
                const graphReadinessPanel = { hidden: true, innerHTML: '', className: '' };
                context.renderVisualReadinessPanel(graphReadinessPanel, graphReadiness);
                const designPublication = {
                  publicationId: 'pub-design',
                  artifactKind: 'DESIGN',
                  draft: {
                    nodes: [
                      { id: 'eligibility', label: 'Eligibility Draft' },
                      { id: 'policy', label: 'Policy Table' }
                    ]
                  },
                  validation: { readiness: graphReadiness }
                };
                const publicationReadiness = context.publicationReadiness(designPublication);
                const publicationReadinessStatusText = context.publicationReadinessStatusText(designPublication);
                const publicationReadinessRows = context.publicationReadinessReviewRows(designPublication);
                const executablePublication = {
                  publicationId: 'pub-executable',
                  artifactKind: 'EXECUTABLE',
                  graphName: 'Executable Risk',
                  draftRevision: 3,
                  validation: {
                    readiness: context.normalizeVisualGraphReadiness({
                      schemaVersion: 'bloge.visualGraphReadiness.v1',
                      state: 'RUNTIME_EXECUTABLE',
                      level: 'SUCCESS',
                      executable: true,
                      artifactKinds: ['EXECUTABLE', 'DESIGN'],
                      title: 'Runtime executable',
                      runtimeExecutableNodeCount: 2
                    })
                  }
                };
                const blockedPublication = {
                  publicationId: 'pub-blocked',
                  artifactKind: 'DESIGN',
                  graphName: 'Blocked Risk',
                  draftRevision: 4,
                  validation: {
                    readiness: context.normalizeVisualGraphReadiness({
                      schemaVersion: 'bloge.visualGraphReadiness.v1',
                      state: 'RUNTIME_BLOCKED',
                      level: 'WARNING',
                      executable: false,
                      artifactKinds: ['DESIGN'],
                      title: 'Runtime blocked',
                      runtimeBlockedNodeCount: 1
                    })
                  }
                };
                const publicationOptionLabel = context.publicationOptionLabel(designPublication);
                const publicationListReadinessLabel = context.publicationListReadinessLabel(designPublication);
                const publicationSummaryReadinessLabel = context.publicationListReadinessLabel({
                  publicationId: 'pub-summary',
                  artifactKind: 'DESIGN',
                  graphName: 'Summary Risk',
                  draftRevision: 5,
                  readiness: graphReadiness
                });
                const publicationAssetSummary = context.publicationAssetSummary([
                  designPublication,
                  executablePublication,
                  blockedPublication
                ]);
                const publicationAssetSummaryLevel = context.publicationAssetSummaryLevel(publicationAssetSummary);
                const publicationAssetSummaryRows = context.publicationAssetSummaryRows([
                  designPublication,
                  executablePublication,
                  blockedPublication
                ]);
                const publicationAssetInterestingPublications = context.publicationAssetInterestingPublications([
                  designPublication,
                  executablePublication,
                  blockedPublication
                ]);
                const publicationAssetRowLevel = context.publicationAssetRowLevel(publicationAssetSummaryRows[1].value);
                const draftSummaries = [
                  {
                    draftId: 'draft-design',
                    graphName: 'Design Draft',
                    active: true,
                    currentRevision: 2,
                    latestRevision: 2,
                    valid: true,
                    readiness: graphReadiness
                  },
                  {
                    draftId: 'draft-blocked',
                    graphName: 'Blocked Draft',
                    active: true,
                    currentRevision: 4,
                    latestRevision: 4,
                    valid: true,
                    readiness: blockedPublication.validation.readiness
                  },
                  {
                    draftId: 'draft-deleted',
                    graphName: 'Deleted Draft',
                    active: false,
                    currentRevision: 0,
                    latestRevision: 5,
                    valid: true,
                    readiness: graphReadiness
                  }
                ];
                context.state.draftSummaries = draftSummaries;
                const draftSummaryReadinessLabel = context.draftSummaryReadinessLabel(draftSummaries[0]);
                const draftAssetSummary = context.draftAssetSummary(draftSummaries);
                const draftAssetSummaryLevel = context.draftAssetSummaryLevel(draftAssetSummary);
                const draftAssetInterestingSummaries = context.draftAssetInterestingSummaries(draftSummaries);
                const draftAssetSummaryRows = context.draftAssetSummaryRows(draftSummaries);
                const draftAssetBlockedRowLevel = context.draftAssetRowLevel(draftAssetSummaryRows[1]);
                const draftHistoryOptionLabel = context.draftHistoryOptionLabel({
                  draftId: 'draft-design',
                  graphName: 'Design Draft',
                  active: true,
                  currentRevision: 2,
                  latestRevision: 2,
                  changeSummary: 'Saved design draft.',
                  reason: ''
                });
                const designOnlyPublishControl = context.publishArtifactKindControlState(graphReadiness, 'EXECUTABLE');
                const designOnlyAllowedKinds = context.publishArtifactKindsForReadiness(graphReadiness).join('|');
                const repairPublishControl = context.publishArtifactKindControlState({
                  schemaVersion: 'bloge.visualGraphReadiness.v1',
                  state: 'DRAFT_REPAIR_REQUIRED',
                  level: 'ERROR',
                  executable: false,
                  artifactKinds: [],
                  title: 'Draft repair required',
                  summary: 'Fix schema errors before publishing.',
                  nodeCount: 1,
                  draftRepairNodeCount: 1
                }, 'EXECUTABLE');
                const unconstrainedPublishControl = context.publishArtifactKindControlState(null, 'EXECUTABLE');
                const publishErrorStatusLevel = context.visualCheckStatusLevel({
                  level: 'error',
                  readiness: graphReadiness
                });
                const serverCatalogFacets = context.normalizeOperatorCatalogFacets({
                  total: 6,
                  capabilities: {
                    'runtime-executable': 4,
                    'design-only': 2,
                    streaming: 1,
                    'requires-secret': 1,
                    'external-effect': 1
                  },
                  runtimeReadinessStates: {
                    'runtime-executable': 3,
                    'design-only': 2,
                    'runtime-blocked': 1,
                    'governance-review': 1
                  },
                  sourceKinds: { 'user-library': 3 },
                  operatorLibraryIds: { 'risk-policy': 3 },
                  loweringModes: { transform: 2, design: 1 }
                });
                const serverCatalogFacetSummary = context.operatorCatalogFacetSummary(serverCatalogFacets);
                const fallbackCatalogFacets = context.normalizeOperatorCatalogFacets(null, [
                  {
                    source: { kind: 'user-library', libraryId: 'risk-policy' },
                    lowering: { mode: 'design' },
                    capabilities: { effect: 'PURE' }
                  },
                  {
                    source: { kind: 'java-suspendable-operator' },
                    lowering: { mode: 'native' },
                    capabilities: { effect: 'WRITE_EXTERNAL', durable: true, requiresSecrets: true }
                  }
                ]);
                const libraryProfile = context.operatorLibraryProfile({
                  libraryId: 'risk-profile',
                  version: '2.1.0',
                  status: 'deprecated',
                  operators: [
                    {
                      operatorRef: 'risk:score',
                      display: { name: 'Risk <Score>' },
                      ports: {
                        inputs: [{
                          name: 'inputs',
                          required: true,
                          schema: {
                            schema: {
                              type: 'object',
                              properties: {
                                customer: {
                                  type: 'object',
                                  properties: {
                                    id: {
                                      type: 'string',
                                      title: 'Customer identifier',
                                      description: 'External customer id used by the risk policy.',
                                      examples: ['C-1001'],
                                      default: 'UNKNOWN'
                                    },
                                    'bad-field': { type: 'string' }
                                  },
                                  required: ['id']
                                },
                                facts: {
                                  type: 'object',
                                  additionalProperties: { type: 'integer' }
                                }
                              },
                              required: ['customer']
                            }
                          }
                        }],
                        outputs: [{
                          name: 'graph',
                          schema: {
                            schema: {
                              type: 'object',
                              properties: {
                                score: { type: 'integer' },
                                reason: { type: 'string' }
                              },
                              required: ['score']
                            }
                          }
                        }]
                      },
                      configSchema: {
                        schema: {
                          type: 'object',
                          properties: {
                            threshold: {
                              type: 'integer',
                              title: 'Risk threshold',
                              description: 'Minimum accepted score.',
                              examples: [720, 760, 790],
                              default: 700,
                              $comment: 'Tune only during risk policy review.'
                            }
                          },
                          patternProperties: { '^flag_[A-Za-z]+$': { type: 'boolean' } }
                        }
                      },
                      capabilities: {
                        effect: 'READ_EXTERNAL',
                        idempotency: 'NON_IDEMPOTENT',
                        streaming: true,
                        requiresSecrets: true
                      },
                      lowering: { mode: 'transform' }
                    },
                    {
                      operatorRef: 'risk:route',
                      source: { kind: 'java-suspendable-operator' },
                      policy: {
                        tenants: ['demo-tenant'],
                        namespaces: ['local'],
                        environments: ['browser']
                      },
                      ports: {
                        inputs: [{
                          name: 'input',
                          required: false,
                          schema: { schema: { type: 'string' } }
                        }],
                        outputs: []
                      },
                      capabilities: { effect: 'PURE', requiresSecrets: false },
                      lowering: { mode: 'branch' }
                    }
                  ]
                });
                const libraryProfileHtml = context.renderLibraryProfilePanel(libraryProfile);
                const libraryCustomerField = libraryProfile.operators[0].inputFields
                  .find((field) => field.path === 'customer.id') || {};
                const libraryThresholdField = libraryProfile.operators[0].configFields
                  .find((field) => field.path === 'threshold') || {};
                const invalidLibraryProfile = context.libraryProfileFromText('{broken');
                """, """
                const yamlLibraryProfile = context.libraryProfileFromText(`
schemaVersion: bloge.visualOperatorLibrary.v1
libraryId: risk-yaml
version: 1.2.3
operators:
  - operatorRef: risk:score
`);
                const yamlLibraryProfileHtml = context.renderLibraryProfilePanel(yamlLibraryProfile);
                const runtimeRiskProfile = context.operatorLibraryProfile({
                  libraryId: 'runtime-risk',
                  operators: [{
                    operatorRef: 'risk:stream',
                    display: { name: 'Runtime Risk' },
                    ports: {
                      outputs: [{
                        name: 'output',
                        schema: { schema: { type: 'object', properties: { ok: { type: 'boolean' } } } }
                      }]
                    },
                    capabilities: { effect: 'READ_EXTERNAL', idempotency: 'IDEMPOTENT', streaming: true },
                    lowering: { mode: 'native' }
                  }]
                });
                const governanceRiskProfile = context.operatorLibraryProfile({
                  libraryId: 'governance-risk',
                  operators: [{
                    operatorRef: 'risk:write',
                    display: { name: 'Governance Risk' },
                    ports: {
                      outputs: [{
                        name: 'output',
                        schema: { schema: { type: 'object', properties: { ok: { type: 'boolean' } } } }
                      }]
                    },
                    capabilities: {
                      effect: 'WRITE_EXTERNAL',
                      idempotency: 'NON_IDEMPOTENT',
                      requiresSecrets: true
                    },
                    lowering: { mode: 'native' }
                  }]
                });
                const externalOnlyProfile = context.operatorLibraryProfile({
                  libraryId: 'external-only',
                  operators: [{
                    operatorRef: 'risk:read',
                    display: { name: 'External Read' },
                    ports: {
                      outputs: [{
                        name: 'output',
                        schema: { schema: { type: 'object', properties: { ok: { type: 'boolean' } } } }
                      }]
                    },
                    capabilities: { effect: 'READ_EXTERNAL', idempotency: 'IDEMPOTENT' },
                    lowering: { mode: 'native' }
                  }]
                });
                const policyOnlyProfile = context.operatorLibraryProfile({
                  libraryId: 'policy-only',
                  operators: [{
                    operatorRef: 'risk:tenantScoped',
                    display: { name: 'Tenant Scoped' },
                    policies: {
                      allowedTenants: ['gold', 'silver', 'bronze', 'trial'],
                      allowedNamespaces: ['lending'],
                      allowedEnvironments: ['prod']
                    },
                    ports: {
                      outputs: [{
                        name: 'output',
                        schema: { schema: { type: 'object', properties: { ok: { type: 'boolean' } } } }
                      }]
                    },
                    capabilities: { effect: 'PURE', idempotency: 'DETERMINISTIC' },
                    lowering: { mode: 'native' }
                  }]
                });
                const policyOnlyProfileHtml = context.renderLibraryProfilePanel(policyOnlyProfile);
                const designOnlyProfile = context.operatorLibraryProfile({
                  libraryId: 'schema-only',
                  operators: [{
                    operatorRef: 'partner:decision',
                    display: { name: 'Partner Decision' },
                    ports: {
                      inputs: [{
                        name: 'inputs',
                        required: true,
                        schema: { schema: { type: 'object', properties: { score: { type: 'integer' } } } }
                      }],
                      outputs: [{
                        name: 'output',
                        schema: { schema: { type: 'object', properties: { decision: { type: 'string' } } } }
                      }]
                    },
                    capabilities: { effect: 'PURE', idempotency: 'UNKNOWN' },
                    lowering: { mode: 'design' }
                  }]
                });
                const designOnlyProfileHtml = context.renderLibraryProfilePanel(designOnlyProfile);
                const serverRuntimeBlockedProfileHtml = context.renderLibraryProfilePanel({
                  libraryId: 'server-reviewed',
                  version: '1.0.0',
                  status: 'ACTIVE',
                  operatorCount: 1,
                  inputPortCount: 1,
                  outputPortCount: 1,
                  requiredInputCount: 1,
                  configFieldCount: 0,
                  outputFieldCount: 1,
                  runtimeBlockedOperatorCount: 1,
                  operators: [{
                    label: 'Native Binding',
                    loweringMode: 'native',
                    inputPortCount: 1,
                    outputPortCount: 1,
                    requiredInputCount: 1,
                    inputFields: [{ port: 'inputs', path: 'score', required: true, dslPathSafe: true }],
                    outputFields: [{ port: 'output', path: 'decision', required: false, dslPathSafe: true }],
                    configFields: [],
                    runtimeReadinessTitle: 'Runtime binding unresolved'
                  }]
                });
                const importReadinessProfileHtml = context.renderLibraryProfilePanel({
                  libraryId: 'server-reviewed',
                  version: '1.0.0',
                  status: 'ACTIVE',
                  operatorCount: 1,
                  inputPortCount: 1,
                  outputPortCount: 1,
                  requiredInputCount: 1,
                  configFieldCount: 0,
                  outputFieldCount: 1,
                  runtimeBlockedOperatorCount: 1,
                  importReadiness: {
                    state: 'runtime-binding-required',
                    level: 'warning',
                    message: 'The library can support authoring, but runtime binding is incomplete for executable graphs.',
                    recommendedAction: 'Import for design work or bind the missing runtime before executable publication.',
                    requiresAckWarnings: true,
                    requiresGovernanceEvidence: true,
                    affectedDraftCount: 2,
                    affectedOperatorCount: 1,
                    runtimeBindingRequirementCount: 1,
                    bindingKindCounts: { 'runtime-adapter': 1 },
                    handoffLaneCounts: { 'runtime-platform': 1 },
                    handoffKindCounts: { 'runtime-adapter': 1 },
                    handoffTargetCounts: { missingRuntimeBinding: 1 },
                    sourceKindCounts: { 'user-library': 1 },
                    operatorLibraryIdCounts: { 'server-reviewed': 1 },
                    loweringModeCounts: { native: 1 },
                    readinessStateCounts: { 'runtime-blocked': 1 },
                    runtimeBindingHandoffGroups: [{
                      groupKey: 'RUNTIME_BINDING_GROUP|operator-library|server-reviewed|runtime-platform|runtime-adapter|missingRuntimeBinding|runtime-adapter',
                      operatorLibraryId: 'server-reviewed',
                      handoffLane: 'runtime-platform',
                      handoffKind: 'runtime-adapter',
                      handoffTarget: 'missingRuntimeBinding',
                      bindingKind: 'runtime-adapter',
                      requirementCount: 1,
                      operatorRefs: ['risk:nativeBinding'],
                      requirementKeys: ['RUNTIME_BINDING|operator-library|server-reviewed|risk:nativeBinding|native|runtime-adapter|missingRuntimeBinding'],
                      recommendedAction: 'Bind the missing runtime adapter.'
                    }],
                    runtimeBindingRequirements: [{
                      operatorRef: 'risk:nativeBinding',
                      operatorLibraryId: 'server-reviewed',
                      label: 'Native Binding',
                      state: 'runtime-blocked',
                      level: 'warning',
                      sourceKind: 'user-library',
                      loweringMode: 'native',
                      bindingKind: 'runtime-adapter',
                      bindingTarget: 'missingRuntimeBinding',
                      handoffLane: 'runtime-platform',
                      handoffKind: 'runtime-adapter',
                      handoffTarget: 'missingRuntimeBinding',
                      recommendedAction: 'Bind the missing runtime adapter.'
                    }]
                  },
                  operators: [{
                    label: 'Native Binding',
                    loweringMode: 'native',
                    inputPortCount: 1,
                    outputPortCount: 1,
                    requiredInputCount: 1,
                    inputFields: [{ port: 'inputs', path: 'score', required: true, dslPathSafe: true }],
                    outputFields: [{ port: 'output', path: 'decision', required: false, dslPathSafe: true }],
                    configFields: [],
                    runtimeReadinessTitle: 'Runtime binding unresolved'
                  }]
                });
                const mixedCandidateSummary = context.bindingCandidateSummary([
                  { compatibility: { ok: true, message: '' } },
                  { compatibility: { ok: false, message: 'source type string cannot feed target type integer' } }
                ]);
                const mixedCandidateLevel = context.bindingCandidateSummaryLevel([
                  { compatibility: { ok: true, message: '' } },
                  { compatibility: { ok: false, message: 'source type string cannot feed target type integer' } }
                ]);
                const blockedCandidateSummary = context.bindingCandidateSummary([
                  { compatibility: { ok: false, message: 'Target path is not accepted.' } }
                ]);
                const blockedCandidateLevel = context.bindingCandidateSummaryLevel([
                  { compatibility: { ok: false, message: 'Target path is not accepted.' } }
                ]);
                const emptyCandidateSummary = context.bindingCandidateSummary([]);
                const emptyCandidateLevel = context.bindingCandidateSummaryLevel([]);
                const localOkPreflightMessage = context.connectionServerPreflightMessage(
                  { ok: true, message: '' },
                  'Checking connection with server...'
                );
                const localMismatchPreflightMessage = context.connectionServerPreflightMessage(
                  { ok: false, message: 'Type mismatch: string cannot feed integer.' },
                  'Checking connection with server...'
                );
                const localMismatchStatus = context.connectionLocalHeuristicStatus({
                  ok: false,
                  message: 'Type mismatch: string cannot feed integer.'
                });
                const localCycleStatus = context.connectionLocalHeuristicStatus({
                  ok: false,
                  message: 'This connection would create a cycle.'
                });
                const layoutGroupNodes = [
                  {
                    id: 'primaryCreditProvider',
                    position: { x: 80, y: 210 },
                    size: { width: 170, height: 74 }
                  },
                  {
                    id: 'secondaryCreditProvider',
                    group: 'secondaryPath',
                    position: { x: 360, y: 300 },
                    size: { width: 170, height: 74 }
                  },
                  {
                    id: 'assembleSecondary',
                    group: 'secondaryPath',
                    position: { x: 640, y: 300 },
                    size: { width: 170, height: 74 }
                  }
                ];
                const layoutGroup = {
                  id: 'secondaryPath',
                  label: 'Secondary <Path>',
                  kind: 'Degradation Path!',
                  nodeIds: ['secondaryCreditProvider']
                };
                const layoutGroupIds = context.layoutGroupNodeIds(layoutGroup, layoutGroupNodes).join('|');
                const layoutGroupRegion = context.layoutGroupRegions({
                  groups: [layoutGroup],
                  nodes: layoutGroupNodes
                }, layoutGroupNodes)[0];
                const layoutGroupBounds = [
                  layoutGroupRegion.x,
                  layoutGroupRegion.y,
                  layoutGroupRegion.width,
                  layoutGroupRegion.height
                ].join('|');
                const layoutGroupKindClass = context.layoutGroupKindClass(layoutGroup.kind);
                """, """
                context.state = {
                  builder: {
                    graphName: 'historyGraph',
                    selectedId: 'policy',
                    operatorFingerprints: {
                      policy: 'policy-current-fingerprint',
                      riskNode: 'saved-fingerprint-123456',
                      auditNode: 'audit-current-fingerprint'
                    },
                    nodes: [
                      { id: 'policy', type: 'decisionTable', x: 80, y: 210 },
                      {
                        id: 'riskNode',
                        type: 'customOperator',
                        paletteType: 'risk:eligibility',
                        customInputs: { score: 'ctx.score' },
                        config: {
                          mode: 'strict',
                          threshold: { kind: 'expression', expr: 'policy.output.score' }
                        }
                      },
                      {
                        id: 'auditNode',
                        type: 'customOperator',
                        paletteType: 'risk:audit',
                        customInputs: { risk: 'riskNode.output.payload' }
                      }
                    ],
                    dependencyEdges: [
                      { source: 'policy', target: 'riskNode', label: 'depends' },
                      { source: 'riskNode', target: 'auditNode', label: 'depends' }
                    ],
                    routeEdges: [
                      { source: 'riskNode', target: 'auditNode', condition: 'eligible' }
                    ],
                    output: { nodeId: 'riskNode', path: '' }
                  },
                  builderHistoryUndo: [],
                  builderHistoryRedo: [],
                  builderHistoryMessage: null,
                  currentDraftId: 'draft-risk',
                  currentDraftRevision: 3,
                  pendingPublishWarningKey: '',
                  savedDraftSnapshot: null,
                  drafts: [],
                  draftRevisions: [],
                  selectedDraftRevision: 3,
                  previewingDraftRevision: 7,
                  visualCheck: { message: 'Previously checked', level: 'success', diagnostics: [] },
                  connectionMessage: { text: 'connected', level: 'success' },
                  operatorUsageByRef: {
                    'risk:eligibility': {
                      schemaVersion: 'bloge.visualOperatorUsage.v1',
                      operatorRef: 'risk:eligibility',
                      currentFingerprint: 'current-fingerprint-123456',
                      drafts: [{
                        draftId: 'draft-risk',
                        revision: 3,
                        graphName: 'Eligibility Graph',
                        tenantId: 'demo-tenant',
                        namespace: 'local',
                        environment: 'browser',
                        nodeId: 'riskNode',
                        nodeLabel: 'Eligibility',
                        savedFingerprint: 'saved-fingerprint-123456',
                        currentFingerprint: 'current-fingerprint-123456',
                        fingerprintStatus: 'DRIFTED',
                        changedSurface: "input port 'inputs' schema changed",
                        changeRisk: 'BREAKING_SCHEMA',
                        changeCategories: ['BREAKING_SCHEMA'],
                        changeSummary: "input port 'inputs' schema changed"
                      }],
                      publications: [{
                        publicationId: 'publication-risk-0001',
                        draftId: 'draft-risk',
                        draftRevision: 3,
                        graphName: 'Eligibility Graph',
                        tenantId: 'demo-tenant',
                        namespace: 'local',
                        environment: 'browser',
                        nodeId: 'riskNode',
                        nodeLabel: 'Eligibility',
                        frozenFingerprint: 'frozen-fingerprint-123456',
                        currentFingerprint: 'current-fingerprint-123456',
                        fingerprintStatus: 'DRIFTED',
                        changedSurface: 'lowering changed',
                        changeRisk: 'RUNTIME_BINDING',
                        changeCategories: ['RUNTIME_BINDING'],
                        changeSummary: 'lowering changed'
                      }],
                      diagnostics: []
                    }
                  },
                  operatorUsageMessagesByRef: {
                    'risk:eligibility': { text: '1 draft usage · 1 publication usage', level: 'warning' }
                  },
                  operatorUsageLoadingRef: '',
                  operatorFingerprintRebaseNodeId: '',
                  lastPayload: { output: true },
                  publications: [],
                  publicationSummaries: [],
                  publicationDetailsById: {},
                  selectedPublicationId: 'publication-1',
                  goldenAssertionMode: 'OUTPUT_MATCHES_SCHEMA',
                  goldenAssertionPath: '',
                  goldenAssertionValueText: '',
                  layout: {
                    nodes: [
                      { id: 'policy', label: 'Loan Policy', operatorRef: 'bloge:decisionTable', kind: 'decision-table' },
                      { id: 'riskNode', label: 'Eligibility', operatorRef: 'risk:eligibility', kind: 'custom' },
                      { id: 'auditNode', label: 'Audit', operatorRef: 'risk:audit', kind: 'custom' }
                    ]
                  }
                };
                context.state.savedDraftSnapshot = {
                  ...context.builderToVisualDraft(context.state.builder),
                  draftId: 'draft-risk',
                  revision: 3
                };
                const normalizedPaletteTokens = context.paletteSearchTokens('  Risk   SCORE  ').join('|');
                context.state.paletteSearch = 'risk inputs.customer.id config.threshold number';
                const paletteMultiTokenMatch = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteSearch = 'risk inputs.customer.id missingField';
                const paletteMultiTokenMiss = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteSearch = 'durable suspendable write-external secret';
                const paletteCapabilitySearchMatch = context.operatorMatchesPaletteFilter('awaitApproval', suspendablePaletteSpec);
                context.state.paletteSearch = 'risk-policy';
                const paletteLibrarySearchMatch = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteSearch = '';
                context.state.paletteSourceKind = 'user-library';
                const paletteSourceFilterMatch = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteSourceKind = 'resource-descriptor';
                const paletteSourceFilterMiss = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteSourceKind = '';
                context.state.paletteOperatorLibraryId = 'risk-policy';
                const paletteLibraryFilterMatch = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteOperatorLibraryId = 'fraud-policy';
                const paletteLibraryFilterMiss = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteOperatorLibraryId = '';
                context.state.paletteCapability = 'requires-secret';
                const paletteCapabilityFilterMatch = context.operatorMatchesPaletteFilter('awaitApproval', suspendablePaletteSpec);
                context.state.paletteCapability = 'runtime-executable';
                const paletteCapabilityFilterMiss = context.operatorMatchesPaletteFilter('awaitApproval', suspendablePaletteSpec);
                context.state.paletteCapability = '';
                context.state.paletteReadiness = 'governance-review';
                const paletteReadinessFilterMatch = context.operatorMatchesPaletteFilter('risk:writeAudit', governedPaletteSpec);
                context.state.paletteReadiness = 'runtime-executable';
                const paletteReadinessFilterMiss = context.operatorMatchesPaletteFilter('risk:writeAudit', governedPaletteSpec);
                context.state.paletteReadiness = '';
                context.state.paletteLoweringMode = 'transform';
                const paletteLoweringFilterMatch = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteLoweringMode = 'design';
                const paletteLoweringFilterMiss = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteLoweringMode = '';
                const unsafeOutputNode = { id: 'unsafeOutputNode', type: 'customOperator' };
                const unsafeOutputHandleCount = context.actualSourceHandlesForNode(unsafeOutputNode).length;
                const unsafeOutputOptionCount = context.actualOutputPathOptionsForNode(unsafeOutputNode).length;
                const mixedOutputNode = { id: 'mixedOutputNode', type: 'customOperator' };
                const mixedOutputOptions = context.actualOutputPathOptionsForNode(mixedOutputNode)
                  .map((option) => option.value)
                  .join('|');
                const mixedOutputDefaultPath = context.defaultOutputPathForNode(mixedOutputNode);
                const mixedOutputBuilder = {
                  nodes: [mixedOutputNode],
                  output: { nodeId: 'mixedOutputNode', path: '' }
                };
                const mixedOutputNormalizedPath = context.ensureBuilderOutput(mixedOutputBuilder).path;
                const unsafeInputNode = { id: 'unsafeInputNode', type: 'customOperator' };
                const unsafeInputTargetCount = context.targetHandlesForNode(unsafeInputNode).length;
                const canvasEligibilityTargets = context.canvasTargetHandlesForNode({
                  id: 'riskNode',
                  type: 'customOperator',
                  paletteType: 'risk:eligibility'
                })
                  .filter((target) => target.port === 'inputs')
                  .map((target) => target.path || '<root>')
                  .join('|');
                const nativeConfigPolicyNode = {
                  id: 'nativeConfigPolicy',
                  type: 'customOperator',
                  config: { limit: 700 }
                };
                const nativeConfigPolicyTargets = context.targetHandlesForNode(nativeConfigPolicyNode);
                const nativeConfigInputHidden = nativeConfigPolicyTargets
                  .some((target) => target.port === 'inputs' && target.path === 'config');
                const nativeScoreInputVisible = nativeConfigPolicyTargets
                  .some((target) => target.port === 'inputs' && target.path === 'score');
                const nativeExecutionOnlyTargets = context.targetHandlesForNode({
                  id: 'nativeConfigPolicy',
                  type: 'customOperator',
                  config: { timeout: '2s' }
                });
                const nativeExecutionOnlyConfigVisible = nativeExecutionOnlyTargets
                  .some((target) => target.port === 'inputs' && target.path === 'config');
                const nativeRootConfigInputPath = context.nativeInputPathForTarget('config', '');
                context.contextSourceHandles = () => [
                  { nodeId: '__ctx', path: 'name', type: 'string' },
                  { nodeId: '__ctx', path: 'score', type: 'integer' }
                ];
                context.sourceHandlesForNode = (node) => node.id === 'policy'
                  ? [{ nodeId: 'policy', port: 'output', path: 'decision', type: 'boolean' }]
                  : [];
                context.connectionCompatibility = (source, target) => source.type === target.type
                  ? { ok: true, message: '' }
                  : { ok: false, message: `${source.type} cannot feed ${target.type}` };
                const sourceCandidatesForScore = context.sourceCandidatesForTarget({
                  nodeId: 'riskNode',
                  port: 'inputs',
                  path: 'score',
                  type: 'integer',
                  schema: { type: 'integer' }
                });
                const sourceCandidateOrder = sourceCandidatesForScore.map((candidate) =>
                  `${context.endpointLabel(candidate.source)}:${candidate.compatibility.ok ? 'ok' : 'blocked'}`)
                  .join('|');
                const sourceCandidateOptionsHtml = context.renderSourceCandidateOptions(
                  sourceCandidatesForScore,
                  context.bindingSourceValue({ nodeId: '__ctx', path: 'score' })
                );
                context.recordBuilderHistory('Move policy');
                context.state.builder.nodes[0].x = 260;
                const historyUndoAfterRecord = context.state.builderHistoryUndo.length;
                const historyRedoAfterRecord = context.state.builderHistoryRedo.length;
                context.undoBuilderEdit();
                const historyUndoRestoredX = context.state.builder.nodes[0].x;
                const historyRedoAfterUndo = context.state.builderHistoryRedo.length;
                const historyPreviewAfterUndo = context.state.previewingDraftRevision;
                const historyVisualCheckAfterUndo = context.state.visualCheck.message;
                const historyRenderCountAfterUndo = context.renderCount || 0;
                context.redoBuilderEdit();
                const historyRedoRestoredX = context.state.builder.nodes[0].x;
                """, """
                const historyUndoAfterRedo = context.state.builderHistoryUndo.length;
                context.clearBuilderHistory('Loaded draft; local edit history cleared.');
                const historyClearUndo = context.state.builderHistoryUndo.length;
                const historyClearRedo = context.state.builderHistoryRedo.length;
                const historyClearMessage = context.state.builderHistoryMessage.text;
                const editableShortcutTarget = context.builderHistoryShortcutTargetIsEditable({
                  closest: (selector) => selector.includes('input') ? {} : null
                });
                const canvasShortcutTarget = context.builderHistoryShortcutTargetIsEditable({
                  closest: () => null
                });
                context.state.visualCheck = {
                  diagnostics: [
                    { level: 'ERROR', code: 'visual.input.required', target: '/nodes/1/inputs/score', message: 'Risk node score failed.' },
                    { level: 'WARNING', code: 'visual.policy.scope', nodeId: 'policy', target: '/graphName', message: 'Policy warning.' },
                    { level: 'INFO', code: 'visual.graph.notice', target: '/graphName', message: 'Graph notice.' }
                  ]
                };
                const visualDiagnosticSummary = context.visualDiagnosticSummary(context.state.visualCheck.diagnostics);
                const visualDiagnosticNodeIds = visualDiagnosticSummary.nodes.map((entry) => entry.nodeId).join('|');
                const visualDiagnosticQueueIds = context.visualDiagnosticNodeQueue(context.state.visualCheck.diagnostics)
                  .map((entry) => entry.nodeId)
                  .join('|');
                const visualDiagnosticOverflowNone = context.visualDiagnosticOverflowText(6, 6);
                const visualDiagnosticOverflowOne = context.visualDiagnosticOverflowText(7, 6);
                const visualDiagnosticOverflowMany = context.visualDiagnosticOverflowText(9, 6);
                const originalBuilderBeforeOverflowProbe = context.state.builder;
                context.state.builder = {
                  nodes: Array.from({ length: 8 }, (_, index) => ({ id: `overflowNode${index}` }))
                };
                const overflowDiagnostics = Array.from({ length: 8 }, (_, index) => ({
                  level: index === 0 ? 'ERROR' : 'WARNING',
                  nodeId: `overflowNode${index}`,
                  code: `visual.overflow.${index}`,
                  message: `Overflow ${index}`
                }));
                const visualDiagnosticOverflowSummary = context.visualDiagnosticSummary(overflowDiagnostics);
                const visualDiagnosticOverflowPreview = context.visualDiagnosticPreviewNodes(
                  visualDiagnosticOverflowSummary.nodes,
                  'overflowNode7',
                  6
                ).map((entry) => entry.nodeId).join('|');
                const visualDiagnosticOverflowHtml = context.renderVisualDiagnosticSummary(overflowDiagnostics);
                const visualDiagnosticOverflowActiveHtml = context.renderVisualDiagnosticSummary(
                  overflowDiagnostics,
                  'overflowNode7'
                );
                context.state.builder = originalBuilderBeforeOverflowProbe;
                const visualDiagnosticRiskPosition = context.visualDiagnosticQueuePositionText(
                  visualDiagnosticSummary.nodes,
                  'riskNode'
                );
                const visualDiagnosticPolicyPosition = context.visualDiagnosticQueuePositionText(
                  visualDiagnosticSummary.nodes,
                  'policy'
                );
                const visualDiagnosticMissingPosition = context.visualDiagnosticQueuePositionText(
                  visualDiagnosticSummary.nodes,
                  'missingNode'
                );
                const visualDiagnosticRiskDisplayLabel = context.visualDiagnosticNodeDisplayLabel('riskNode');
                const visualDiagnosticMissingDisplayLabel = context.visualDiagnosticNodeDisplayLabel('missingNode');
                const visualDiagnosticFilterNotice = context.renderVisualDiagnosticFilterNotice(1, 'riskNode');
                const visualDiagnosticEmptyFilterNotice = context.renderVisualDiagnosticFilterNotice(1, '');
                const visualDiagnosticFirstTarget = context.visualDiagnosticQueueTarget(context.state.visualCheck.diagnostics, '', 1);
                const visualDiagnosticNextTarget = context.visualDiagnosticQueueTarget(context.state.visualCheck.diagnostics, 'riskNode', 1);
                const visualDiagnosticPrevTarget = context.visualDiagnosticQueueTarget(context.state.visualCheck.diagnostics, 'riskNode', -1);
                const visualDiagnosticShortcutNext = context.visualDiagnosticShortcutDirection({ key: 'F8' });
                const visualDiagnosticShortcutPrev = context.visualDiagnosticShortcutDirection({ key: 'F8', shiftKey: true });
                const visualDiagnosticShortcutCommand = context.visualDiagnosticShortcutDirection({ key: 'F8', ctrlKey: true });
                const visualDiagnosticClearShortcutActive = context.visualDiagnosticClearShortcut(
                  { key: 'Escape' },
                  'riskNode'
                );
                const visualDiagnosticClearShortcutInactive = context.visualDiagnosticClearShortcut(
                  { key: 'Escape' },
                  ''
                );
                const visualDiagnosticClearShortcutCommand = context.visualDiagnosticClearShortcut(
                  { key: 'Escape', metaKey: true },
                  'riskNode'
                );
                context.state.visualDiagnosticNodeFilter = 'riskNode';
                let visualDiagnosticClearRenderCount = 0;
                context.renderVisualCheck = () => { visualDiagnosticClearRenderCount += 1; };
                const visualDiagnosticClearResult = context.clearVisualDiagnosticNodeFilter();
                const visualDiagnosticFilterAfterClear = context.state.visualDiagnosticNodeFilter;
                const visualDiagnosticClearAgain = context.clearVisualDiagnosticNodeFilter();
                const visualDiagnosticRiskText = context.visualDiagnosticNodeSummaryText(
                  visualDiagnosticSummary.nodes.find((entry) => entry.nodeId === 'riskNode')
                );
                const visualDiagnosticSummaryHtml = context.renderVisualDiagnosticSummary(context.state.visualCheck.diagnostics);
                const visualDiagnosticFilteredHtml = context.renderVisualDiagnosticSummary(
                  context.state.visualCheck.diagnostics,
                  'riskNode'
                );
                """, """
                const normalizedOpenApiOperations = context.normalizeOpenApiOperations([
                  {
                    operationId: 'submitOrder',
                    path: '/orders/{orderId}',
                    method: 'post',
                    summary: 'Submit order',
                    tags: ['order', ''],
                    hasRequestBody: true,
                    requestMediaTypes: ['application/json'],
                    responseMediaTypes: ['application/json'],
                    projectionLevel: 'ready',
                    projectionMessage: 'Ready to project.'
                  },
                  { operationId: '', path: '', method: 'get' }
                ]);
                const openApiOperationLabel = context.openApiOperationLabel(normalizedOpenApiOperations[0]);
                const openApiMultipartOperationLabel = context.openApiOperationLabel(context.normalizeOpenApiOperations([
                  {
                    operationId: 'uploadOrderNote',
                    path: '/orders/{orderId}/notes',
                    method: 'post',
                    requestMediaTypes: ['multipart/form-data'],
                    projectionLevel: 'READY',
                    projectionMessage: 'Ready to project.'
                  }
                ])[0]);
                const openApiOperationMatchById = context.openApiOperationMatchesCurrent(
                  normalizedOpenApiOperations[0],
                  { operationId: 'submitOrder', path: '', method: '' }
                );
                const openApiOperationMatchByPath = context.openApiOperationMatchesCurrent(
                  normalizedOpenApiOperations[0],
                  { operationId: '', path: '/orders/{orderId}', method: 'POST' }
                );
                const openApiOperationMiss = context.openApiOperationMatchesCurrent(
                  normalizedOpenApiOperations[0],
                  { operationId: 'other', path: '/orders/{orderId}', method: 'POST' }
                );
                const openApiReadinessOperations = context.normalizeOpenApiOperations([
                  {
                    operationId: 'readyOrder',
                    path: '/orders',
                    method: 'get',
                    projectionLevel: 'READY',
                    projectionMessage: 'Ready to project.'
                  },
                  {
                    operationId: 'uploadOrder',
                    path: '/orders/upload',
                    method: 'post',
                    projectionLevel: 'WARNING',
                    projectionMessage: 'Request body will be omitted.'
                  },
                  {
                    operationId: 'healthText',
                    path: '/health',
                    method: 'get',
                    projectionLevel: 'BLOCKED',
                    projectionMessage: 'Selected 2xx response is not JSON.'
                  }
                ]);
                const openApiReadinessSummary = context.openApiOperationReadinessSummary(openApiReadinessOperations);
                const openApiReadinessSummaryText = [
                  openApiReadinessSummary.total,
                  openApiReadinessSummary.ready,
                  openApiReadinessSummary.warning,
                  openApiReadinessSummary.blocked
                ].join('|');
                const openApiSelectedBlockedOperation = context.openApiSelectedOperation({
                  operations: openApiReadinessOperations,
                  operationId: 'healthText'
                });
                const openApiBlockedProjectionMessage = context.openApiBlockedProjectionMessage({
                  operations: openApiReadinessOperations,
                  operationId: 'healthText'
                });
                const openApiReadyProjectionMessage = context.openApiBlockedProjectionMessage({
                  operations: openApiReadinessOperations,
                  operationId: 'readyOrder'
                });
                const openApiSummaryHtml = context.renderOpenApiOperationSummary(
                  openApiReadinessOperations,
                  { operationId: 'healthText' }
                );
                const openApiEmptySummaryHtml = context.renderOpenApiOperationSummary([], {});
                const openApiBlockedStatusLevel = context.openApiOperationStatusLevel(openApiReadinessOperations[2]);
                const openApiBlockedStatusMessage = context.openApiOperationStatusMessage(openApiReadinessOperations[2]);
                context.$ = () => null;
                context.state.resourceContractImport = {};
                context.applyOpenApiOperationSelection(normalizedOpenApiOperations[0]);
                const openApiOperationApplied = [
                  context.state.resourceContractImport.operationId,
                  context.state.resourceContractImport.path,
                  context.state.resourceContractImport.method
                ].join('|');
                const openApiOperationMessage = context.state.resourceContractImport.message.text;
                const openApiOperationMessageLevel = context.state.resourceContractImport.message.level;
                const riskSearch = context.canvasSearchResults('eligibility strict', context.state.builder, context.state.layout)
                  .map((entry) => entry.nodeId)
                  .join('|');
                const policyByPointer = context.diagnosticTargetNodeId({ target: '/nodes/0/config/rules/0' }, context.state.builder);
                const riskByPointer = context.diagnosticTargetNodeId({ target: '/nodes/1/inputs/score' }, context.state.builder);
                const originalLayoutBeforeDiagnosticProbe = context.state.layout;
                context.state.layout = { nodes: [
                  { id: 'riskNode', operatorRef: 'risk:eligibility' },
                  { id: 'policy', operatorRef: 'bloge:decisionTable' }
                ] };
                const riskByLayoutPointer = context.diagnosticTargetNodeId(
                  { target: '/visualLayout/nodes/0/operatorRef' },
                  context.state.builder
                );
                context.state.layout = originalLayoutBeforeDiagnosticProbe;
                const policyByDirectNode = context.diagnosticTargetNodeId({ nodeId: 'policy', target: '/graphName' }, context.state.builder);
                const riskDiagnosticCount = context.diagnosticsForCanvasNode('riskNode').length;
                const unescapedPointerSegment = context.jsonPointerUnescape('node~1with~0marker');
                """, """
                context.state.activeRunTrace = {
                  nodes: [
                    { nodeId: 'policy', status: 'COMPLETED', diagnosticCount: 0, errorCount: 0, operatorRef: 'bloge:decisionTable' },
                    { nodeId: 'riskNode', status: 'COMPLETED', diagnosticCount: 2, errorCount: 1, operatorRef: 'risk:eligibility' },
                    { nodeId: 'auditNode', status: 'COMPLETED', elapsedMs: 9, timingKnown: true, diagnosticCount: 1, errorCount: 0, operatorRef: 'risk:audit', outputSelected: true },
                    { nodeId: 'removedNode', status: 'COMPLETED', diagnosticCount: 0, errorCount: 0, operatorRef: 'risk:removed' }
                  ]
                };
                const riskTraceNode = context.runTraceForCanvasNode('riskNode');
                const riskTraceLevel = context.runTraceLevel(riskTraceNode);
                const riskTraceStatus = context.runTraceStatusLabel(riskTraceNode);
                const riskTraceBadge = context.nodeTraceBadgeText(riskTraceNode);
                const riskTraceIssueText = context.canvasNodeIssueText(context.diagnosticsForCanvasNode('riskNode'), riskTraceNode);
                const riskSelectedDiagnosticsLevel = context.selectedNodeDiagnosticsLevel(
                  context.diagnosticsForCanvasNode('riskNode'),
                  riskTraceNode
                );
                const riskSelectedDiagnosticsSummary = context.selectedNodeDiagnosticsSummary(
                  context.diagnosticsForCanvasNode('riskNode'),
                  riskTraceNode
                );
                const riskSelectedDiagnosticsPanel = context.renderSelectedNodeDiagnosticsPanel(
                  context.state.builder.nodes.find((node) => node.id === 'riskNode')
                );
                const riskSelectedDiagnosticsLeak = riskSelectedDiagnosticsPanel.includes('Policy warning.');
                const originalBuilderBeforeDuplicateProbe = context.state.builder;
                const originalSelectedBeforeDuplicateProbe = context.state.selectedNodeId;
                const originalTraceBeforeDuplicateProbe = context.state.activeRunTrace;
                const duplicateProbeBuilder = JSON.parse(JSON.stringify(context.state.builder));
                duplicateProbeBuilder.selectedId = 'riskNode';
                const duplicateProbeRiskNode = duplicateProbeBuilder.nodes.find((node) => node.id === 'riskNode');
                duplicateProbeRiskNode.x = 140;
                duplicateProbeRiskNode.y = 210;
                duplicateProbeRiskNode.customInputPorts = { score: 'inputs' };
                duplicateProbeRiskNode.customInputPaths = { score: 'score' };
                duplicateProbeRiskNode.config.nested = { flags: { review: true } };
                context.state.builder = duplicateProbeBuilder;
                context.state.selectedNodeId = 'riskNode';
                context.state.builderHistoryUndo = [];
                context.state.builderHistoryRedo = [];
                context.state.activeRunTrace = originalTraceBeforeDuplicateProbe;
                let duplicateSyncCount = 0;
                let duplicateInputRenders = 0;
                let duplicateEditorRenders = 0;
                let duplicateDiagramRenders = 0;
                let duplicateMessage = '';
                context.syncComposerFromBuilder = () => { duplicateSyncCount += 1; };
                context.renderInputForm = () => { duplicateInputRenders += 1; };
                context.renderSelectedOperatorEditor = () => { duplicateEditorRenders += 1; };
                context.renderDiagram = () => { duplicateDiagramRenders += 1; };
                context.setConnectionMessage = (text) => { duplicateMessage = text; };
                const duplicatedNode = context.duplicateSelectedBuilderNode();
                duplicatedNode.config.nested.flags.review = false;
                const duplicateSourceNode = context.state.builder.nodes.find((node) => node.id === 'riskNode');
                const duplicateNodeId = duplicatedNode.id;
                const duplicateNodeCount = context.state.builder.nodes.length;
                const duplicateSelectedId = context.state.builder.selectedId;
                const duplicateCopiedInput = duplicatedNode.customInputs.score;
                const duplicateCopiedConfigExpr = duplicatedNode.config.threshold.expr;
                const duplicateSourceNestedFlag = duplicateSourceNode.config.nested.flags.review;
                const duplicateOutputNode = context.state.builder.output.nodeId;
                const duplicateFingerprint = context.state.builder.operatorFingerprints[duplicatedNode.id] || '';
                const duplicateHistoryAction = context.state.builderHistoryUndo[0].action;
                const duplicateUndoNodeCount = JSON.parse(context.state.builderHistoryUndo[0].snapshot).nodes.length;
                const duplicateTraceCleared = context.state.activeRunTrace === null;
                const duplicateRenderCounts = `${duplicateSyncCount}|${duplicateInputRenders}|${duplicateEditorRenders}|${duplicateDiagramRenders}`;
                const duplicateEdgesToCopy = [
                  ...(context.state.builder.dependencyEdges || []),
                  ...(context.state.builder.routeEdges || [])
                ].filter((edge) => edge.source === duplicateNodeId || edge.target === duplicateNodeId).length;
                context.state.builder = originalBuilderBeforeDuplicateProbe;
                context.state.selectedNodeId = originalSelectedBeforeDuplicateProbe;
                context.state.activeRunTrace = originalTraceBeforeDuplicateProbe;
                context.state.builderHistoryUndo = [];
                context.state.builderHistoryRedo = [];
                """, """
                const auditTraceSummaryLabel = context.nodeTraceSummaryLabel(context.runTraceForCanvasNode('auditNode'));
                const traceCoverage = context.runTraceCanvasCoverage(context.state.activeRunTrace, context.state.builder, context.state.layout);
                const traceCoverageText = context.runTraceCoverageText(traceCoverage);
                context.clearActiveRunTrace();
                const activeTraceCleared = context.runTraceForCanvasNode('riskNode') === null;
                const traceSummary = context.runTraceSummary({
                  outputNode: 'auditNode',
                  nodes: [
                    { nodeId: 'policy', diagnosticCount: 0, errorCount: 0 },
                    { nodeId: 'riskNode', diagnosticCount: 2, errorCount: 1 },
                    { nodeId: 'auditNode', diagnosticCount: 1, errorCount: 0, outputSelected: true }
                  ]
                });
                const riskImpact = context.nodeImpactSummary('riskNode', context.state.builder);
                const riskIncomingKinds = riskImpact.incoming
                  .map((entry) => `${entry.kind}:${entry.peerId}`)
                  .sort()
                  .join('|');
                const riskOutgoingKinds = riskImpact.outgoing
                  .map((entry) => `${entry.kind}:${entry.peerId}:${entry.detail}`)
                  .sort()
                  .join('|');
                const riskContextInputs = riskImpact.contextInputs
                  .map((entry) => entry.detail)
                  .join('|');
                const riskImpactClearActions = context.nodeImpactClearActions('riskNode', context.state.builder);
                const riskImpactPanel = context.renderNodeImpactPanel(context.state.builder.nodes[1]);
                const riskUsageRef = context.operatorUsageRefForNode(context.state.builder.nodes[1]);
                const policyUsageRef = context.operatorUsageRefForNode(context.state.builder.nodes[0]);
                const riskUsageLevel = context.operatorUsageResponseLevel(context.state.operatorUsageByRef['risk:eligibility']);
                const riskUsagePrimaryStatus = context.operatorUsagePrimaryStatus(context.state.operatorUsageByRef['risk:eligibility']);
                const riskUsageSummary = context.operatorUsageSummaryForNode(context.state.builder.nodes[1]);
                const riskUsagePanel = context.renderOperatorUsagePanel(context.state.builder.nodes[1]);
                const riskFingerprintStatus = context.operatorFingerprintSnapshotStatus(context.state.builder.nodes[1]);
                const riskFingerprintPanel = context.renderOperatorFingerprintSnapshotPanel(context.state.builder.nodes[1]);
                const cleanRebaseBlockReason = context.operatorFingerprintRebaseBlockReason();
                const cleanDraftDirty = context.currentDraftHasUnsavedGraphChanges();
                const originalRiskScoreInput = context.state.builder.nodes[1].customInputs.score;
                context.state.builderHistoryUndo = [{
                  action: 'Edit binding riskNode.score',
                  snapshot: context.serializeBuilderHistory(context.state.builder)
                }];
                context.state.builder.nodes[1].customInputs.score = 'ctx.changedScore';
                const dirtyDraftDirty = context.currentDraftHasUnsavedGraphChanges();
                const dirtyRebaseBlockReason = context.operatorFingerprintRebaseBlockReason();
                const dirtyFingerprintStatus = context.operatorFingerprintSnapshotStatus(context.state.builder.nodes[1]);
                const dirtyFingerprintPanel = context.renderOperatorFingerprintSnapshotPanel(context.state.builder.nodes[1]);
                context.state.builder.nodes[1].customInputs.score = originalRiskScoreInput;
                context.state.builderHistoryUndo = [];
                const riskUsageDraftEntry = context.operatorUsageDraftEntryForNode(context.state.builder.nodes[1]);
                const riskUsageChange = context.operatorUsageChangeLine(riskUsageDraftEntry);
                const riskUsageAction = context.operatorUsageRiskActionLine(riskUsageDraftEntry, 'draft');
                const libraryImpactDiagnostics = [
                  {
                    level: 'ERROR',
                    code: 'visual.library.inUse',
                    target: '/drafts/draft-risk/nodes/0/operatorRef',
                    message: "Operator library 'risk-policy' cannot be replaced because draft 'draft-risk@3' node 'riskNode' still uses operatorRef 'risk:eligibility'."
                  },
                  {
                    level: 'WARNING',
                    code: 'visual.library.operatorFingerprintDrift',
                    target: '/drafts/draft-risk/nodes/0/operatorRef',
                    message: "Operator library 'risk-policy' changes operatorRef 'risk:eligibility' used by draft 'draft-risk@3' node 'riskNode' from saved fingerprint 'old' to 'new'; changed surface: change risk: BREAKING_SCHEMA; output schema changed.",
                    metadata: {
                      changeRisk: 'BREAKING_SCHEMA',
                      schemaChanges: [
                        {
                          surface: 'output',
                          portName: 'result',
                          compatibility: 'breaking',
                          path: 'eligible',
                          message: 'output result type changed'
                        }
                      ]
                    }
                  },
                  {
                    level: 'WARNING',
                    code: 'visual.library.publicationOperatorFingerprintDrift',
                    target: '/publications/pub-risk/nodes/0/operatorRef',
                    message: "Operator library 'risk-policy' changes operatorRef 'risk:eligibility' used by publication 'pub-risk' node 'riskNode' from frozen fingerprint 'old' to 'new'; changed surface: change risk: GOVERNANCE; effect capability changed.",
                    metadata: { changeRisk: 'GOVERNANCE' }
                  },
                  {
                    level: 'ERROR',
                    code: 'visual.operator.version.invalid',
                    target: '/operators/1/operatorVersion',
                    message: "Operator 'risk:audit' version '1' must use semantic version form MAJOR.MINOR.PATCH."
                  }
                ];
                const libraryImpact = context.libraryImpactSummary(libraryImpactDiagnostics);
                const libraryImpactFromPayload = context.libraryImpactSummaryFromPayload({
                  diagnosticCount: 3,
                  errorCount: 1,
                  warningCount: 2,
                  draftIds: ['draft-risk', 'draft-risk'],
                  publicationIds: ['pub-risk'],
                  operatorRefs: ['risk:eligibility'],
                  draftTargets: [
                    { draftId: 'draft-risk', nodeIndex: 1 },
                    { draftId: 'draft-risk', nodeIndex: 1 }
                  ],
                  publicationTargets: [
                    { publicationId: 'pub-risk', nodeIndex: 4 },
                    { publicationId: 'pub-risk', nodeIndex: 4 }
                  ],
                  codeCounts: [
                    { code: 'visual.library.operatorFingerprintDrift', level: 'WARNING', count: 2 },
                    { code: 'visual.library.inUse', level: 'ERROR', count: 1 }
                  ],
                  changeRiskCounts: [
                    { risk: 'BREAKING_SCHEMA', count: 2 }
                  ]
                });
                const libraryImpactRiskText = context.libraryImpactRiskSummaryText(libraryImpact);
                const libraryPayloadRiskText = context.libraryImpactRiskSummaryText(libraryImpactFromPayload);
                const librarySchemaChanges = context.librarySchemaChangesFromDiagnostics(libraryImpactDiagnostics);
                const librarySchemaRows = context.renderSchemaChangeRows(librarySchemaChanges);
                const libraryWarningAcknowledgement = context.operatorLibraryWarningAcknowledgementMessage({
                  diagnosticCount: 1,
                  errorCount: 0,
                  warningCount: 1,
                  changeRiskCounts: [
                    { risk: 'BREAKING_SCHEMA', count: 1 }
                  ]
                }, libraryImpactDiagnostics, 'Import');
                const resourceImpactFromPayload = context.libraryImpactSummaryFromPayload({
                  schemaVersion: 'bloge.resourceDesignContractImpact.v1',
                  diagnosticCount: 2,
                  errorCount: 0,
                  warningCount: 2,
                  resourceIds: ['order-service.listOrders', 'order-service.listOrders'],
                  operatorRefs: ['resource:order-service.listOrders'],
                  draftIds: ['draft-orders'],
                  publicationIds: ['pub-orders'],
                  draftTargets: [{ draftId: 'draft-orders', nodeIndex: 3 }],
                  publicationTargets: [{ publicationId: 'pub-orders', nodeIndex: 4 }],
                  codeCounts: [
                    { code: 'visual.resourceContract.operatorFingerprintDrift', level: 'WARNING', count: 1 },
                    { code: 'visual.resourceContract.lifecycle.deprecated', level: 'WARNING', count: 1 }
                  ],
                  changeRiskCounts: [
                    { risk: 'BREAKING_SCHEMA', count: 1 }
                  ]
                });
                const resourceImpactDiagnostics = [
                  {
                    level: 'WARNING',
                    code: 'visual.resourceContract.operatorFingerprintDrift',
                    target: '/drafts/draft-orders/nodes/3/operatorRef',
                    message: "Resource design contract 'order-service.listOrders' changes operatorRef 'resource:order-service.listOrders' used by draft 'draft-orders@2' node 'orders' from saved fingerprint 'old' to 'new'; changed surface: output schema changed.",
                    metadata: {
                      resourceId: 'order-service.listOrders',
                      operatorRef: 'resource:order-service.listOrders',
                      changeRisk: 'BREAKING_SCHEMA'
                    }
                  },
                  {
                    level: 'WARNING',
                    code: 'visual.resourceContract.publicationOperatorFingerprintDrift',
                    target: '/publications/pub-orders/nodes/4/operatorRef',
                    message: "Resource design contract 'order-service.listOrders' changes operatorRef 'resource:order-service.listOrders' used by publication 'pub-orders' node 'orders' from frozen fingerprint 'old' to 'new'; changed surface: output schema changed.",
                    metadata: {
                      resourceId: 'order-service.listOrders',
                      operatorRef: 'resource:order-service.listOrders',
                      publicationId: 'pub-orders',
                      changeRisk: 'BREAKING_SCHEMA'
                    }
                  }
                ];
                const resourceImpactFromDiagnostics = context.libraryImpactSummary(resourceImpactDiagnostics);
                const resourceWarningAcknowledgement = context.resourceContractWarningAcknowledgementMessage(
                  resourceImpactFromPayload,
                  resourceImpactDiagnostics,
                  'Save contract'
                );
                const resourceImpactPanel = (() => {
                  const target = { hidden: false, innerHTML: '', className: '' };
                  context.renderLibraryImpactPanel(target, resourceImpactDiagnostics, resourceImpactFromPayload);
                  return target;
                })();
                const libraryImpactPanel = (() => {
                  const target = { hidden: false, innerHTML: '', className: '' };
                  context.renderLibraryImpactPanel(target, libraryImpactDiagnostics, {
                    diagnosticCount: 3,
                    errorCount: 1,
                    warningCount: 2,
                    draftIds: ['draft-from-payload'],
                    publicationIds: ['pub-from-payload'],
                    operatorRefs: ['risk:payload'],
                    draftTargets: [{ draftId: 'draft-from-payload', nodeIndex: 2 }],
                    publicationTargets: [{ publicationId: 'pub-from-payload', nodeIndex: 5 }],
                    codeCounts: [
                      { code: 'visual.library.payloadImpact', level: 'WARNING', count: 2 }
                    ],
                    changeRiskCounts: [
                      { risk: 'RUNTIME_BINDING', count: 1 }
                    ]
                  });
                  return target;
                })();
                const libraryDiffPanel = (() => {
                  const target = { hidden: false, innerHTML: '', className: '' };
                  context.renderOperatorLibraryDiffPanel(target, {
                    changed: true,
                    changeRisk: 'BREAKING_SCHEMA',
                    changeSummary: 'input port inputs schema changed',
                    addedOperatorCount: 0,
                    removedOperatorCount: 0,
                    changedOperatorCount: 1,
                    libraryChanges: [],
                    operatorChanges: [
                      {
                        operatorRef: 'risk:eligibility',
                        changeKind: 'CHANGED',
                        risk: 'BREAKING_SCHEMA',
                        summary: 'input port inputs schema changed',
                        schemaChanges: [
                          {
                            surface: 'input',
                            portName: 'inputs',
                            compatibility: 'breaking',
                            path: 'score',
                            message: 'input score type changed'
                          }
                        ]
                      }
                    ]
                  }, 'Library Diff');
                  return target;
                })();
                const libraryImpactDraftGroup = context.libraryImpactRefGroup('Drafts', ['draft-risk'], 'draft');
                const fullOutputContract = context.graphOutputContractSummary(
                  context.state.builder.nodes[1],
                  { nodeId: 'riskNode', path: '' }
                );
                const nestedOutputContract = context.graphOutputContractSummary(
                  context.state.builder.nodes[1],
                  { nodeId: 'riskNode', path: 'facts.reason' }
                );
                const inferredSchemaAssertion = context.goldenAssertionsFromControls({
                  approved: true,
                  score: 720
                })[0];
                context.state.goldenAssertionValueText = '{"type":"object","properties":{"approved":{"type":"boolean"}},"required":["approved"],"additionalProperties":true}';
                const explicitSchemaAssertion = context.goldenAssertionsFromControls({
                  approved: true,
                  score: 720
                })[0];
                context.state.goldenAssertionMode = 'PATH_APPROX_EQUALS';
                context.state.goldenAssertionPath = '/score';
                context.state.goldenAssertionValueText = '';
                const inferredApproxAssertion = context.goldenAssertionsFromControls({
                  approved: true,
                  score: 720.000002
                })[0];
                context.state.goldenAssertionValueText = '{"value":720,"relativeTolerance":0.01}';
                const explicitApproxAssertion = context.goldenAssertionsFromControls({
                  approved: true,
                  score: 720.000002
                })[0];
                const approxPointerValue = context.valueAtJsonPointer({
                  nested: {
                    'score/value': 720
                  }
                }, '/nested/score~1value');
                context.state.goldenAssertions = [inferredApproxAssertion, explicitSchemaAssertion];
                const queuedAssertions = context.goldenAssertionsFromControls({
                  approved: false,
                  score: 1
                });
                queuedAssertions[0].expectedValue.value = 0;
                const queuedFirstValueAfterClone = context.state.goldenAssertions[0].expectedValue.value;
                const queuedAssertionModes = queuedAssertions.map((assertion) => assertion.mode).join('|');
                const queuedAssertionSummary = context.goldenAssertionExpectedSummary(context.state.goldenAssertions[0]);
                context.state.goldenAssertions = [];
                """, """
                context.sourceHandlesForNode = (node) => {
                  if (node.id !== 'riskNode') {
                    return [];
                  }
                  return [
                    {
                      nodeId: 'riskNode',
                      port: 'payload',
                      path: '',
                      type: 'object',
                      schema: {
                        type: 'object',
                        properties: {
                          risk: { type: 'object' },
                          score: { type: 'integer' },
                          eligible: { type: 'boolean' }
                        }
                      },
                      dslPathSafe: true
                    },
                    { nodeId: 'riskNode', port: 'payload', path: 'score', type: 'integer', schema: { type: 'integer' }, dslPathSafe: true },
                    { nodeId: 'riskNode', port: 'payload', path: 'eligible', type: 'boolean', schema: { type: 'boolean' }, dslPathSafe: true }
                  ];
                };
                const actualCanvasTargetHandlesForNode = context.canvasTargetHandlesForNode;
                context.canvasTargetHandlesForNode = (node) => {
                  if (node.id === 'unionInputNode') {
                    return actualCanvasTargetHandlesForNode(node);
                  }
                  if (node.id !== 'auditNode') {
                    return [];
                  }
                  return [
                    { nodeId: 'auditNode', port: 'inputs', path: 'risk', type: 'object', schema: { type: 'object' } },
                    { nodeId: 'auditNode', port: 'inputs', path: 'score', type: 'integer', schema: { type: 'integer' } },
                    { nodeId: 'auditNode', port: 'inputs', path: 'approved', type: 'boolean', schema: { type: 'boolean' } },
                    { nodeId: 'auditNode', port: 'config', path: 'threshold', type: 'integer', schema: { type: 'integer' } },
                    { nodeId: 'auditNode', port: 'dependency', path: '', kind: 'dependency', type: 'dependency' }
                  ];
                };
                context.connectionCompatibility = (source, target) => {
                  if (target.kind === 'dependency') {
                    return { ok: true, message: '' };
                  }
                  return source.type === target.type
                    ? { ok: true, message: '' }
                    : { ok: false, message: `Type mismatch: ${source.type} cannot feed ${target.type}.` };
                };
                context.connectionAlreadyApplied = (source, target) =>
                  source.nodeId === 'riskNode'
                    && source.port === 'payload'
                    && source.path === ''
                    && target.nodeId === 'auditNode'
                    && target.port === 'inputs'
                    && target.path === 'risk';
                const connectability = context.nodeConnectabilitySummary('riskNode', context.state.builder);
                const connectabilityPanel = context.renderNodeConnectabilityPanel(context.state.builder.nodes[1]);
                const rootConnectability = connectability.sources.find((entry) => entry.source.path === '');
                const scoreConnectability = connectability.sources.find((entry) => entry.source.path === 'score');
                const eligibleConnectability = connectability.sources.find((entry) => entry.source.path === 'eligible');
                const overflowReadyTargets = Array.from({ length: 30 }, (_, index) => {
                  const base = scoreConnectability.availableTargets[0];
                  return {
                    ...base,
                    targetNodeId: `overflowReview${index + 1}`,
                    targetLabel: `Overflow Review ${index + 1}`,
                    target: {
                      ...base.target,
                      nodeId: `overflowReview${index + 1}`
                    }
                  };
                });
                const overflowConnectability = {
                  ...scoreConnectability,
                  availableCount: overflowReadyTargets.length,
                  compatibleTargets: overflowReadyTargets,
                  availableTargets: overflowReadyTargets
                };
                const overflowDisplayTargets = context.nodeConnectabilityDisplayTargets(overflowConnectability);
                const overflowDisplayWindow = context.nodeConnectabilityDisplayTargetWindow(overflowConnectability);
                const overflowSummary = context.nodeConnectabilityDisplayOverflowSummary(overflowConnectability);
                const overflowRow = context.renderNodeConnectabilityRow(overflowConnectability);
                const overflowWindowControls = context.renderNodeConnectabilityDisplayWindowControls(
                  overflowDisplayWindow,
                  overflowSummary
                );
                const overflowDisplayWindowKey = context.nodeConnectabilityDisplayWindowKey(overflowConnectability);
                const overflowSourceKey = context.nodeConnectabilitySourceFilterKey(overflowConnectability.source);
                const overflowRowDomId = context.nodeConnectabilityRowDomId(overflowConnectability.source);
                const overflowTargetDomId = context.nodeConnectabilityTargetDomId(
                  overflowConnectability.source,
                  overflowDisplayTargets[0]
                );
                const overflowWindowSummaryDomId = context.nodeConnectabilityWindowSummaryDomId(overflowDisplayWindow.key);
                context.nodeConnectabilitySetDisplayWindowOffset(overflowDisplayWindowKey, 24);
                const overflowSecondWindowTargets = context.nodeConnectabilityDisplayTargets(overflowConnectability);
                const overflowSecondDisplayWindow = context.nodeConnectabilityDisplayTargetWindow(overflowConnectability);
                const overflowSecondSummary = context.nodeConnectabilityDisplayOverflowSummary(overflowConnectability);
                const overflowSecondRow = context.renderNodeConnectabilityRow(overflowConnectability);
                const overflowSecondTargetDomId = context.nodeConnectabilityTargetDomId(
                  overflowConnectability.source,
                  overflowSecondWindowTargets[0]
                );
                context.nodeConnectabilitySetDisplayWindowOffset(overflowDisplayWindowKey, 0);
                const overflowReadyFilter = {
                  query: '',
                  normalizedQuery: '',
                  status: 'ready',
                  sourceKey: '',
                  facetFilters: {}
                };
                const overflowFilteredTargets = context.nodeConnectabilityDisplayTargets(
                  overflowConnectability,
                  overflowReadyFilter
                );
                const overflowFilteredSummary = context.nodeConnectabilityDisplayOverflowSummary(
                  overflowConnectability,
                  overflowReadyFilter
                );
                const overflowFilteredWindowKey = context.nodeConnectabilityDisplayWindowKey(overflowConnectability, overflowReadyFilter);
                context.nodeConnectabilitySetDisplayWindowOffset(overflowFilteredWindowKey, 24);
                const overflowFilteredSecondWindow = context.nodeConnectabilityDisplayTargetWindow(
                  overflowConnectability,
                  overflowReadyFilter
                );
                const overflowFilteredSecondSummary = context.nodeConnectabilityDisplayOverflowSummary(
                  overflowConnectability,
                  overflowReadyFilter
                );
                context.nodeConnectabilitySetDisplayWindowOffset(overflowFilteredWindowKey, 0);
                const overflowServerWindowTargets = context.nodeConnectabilityDisplayTargets({
                  ...overflowConnectability,
                  compatibleTargets: overflowReadyTargets.map((entry, index) =>
                    index === overflowReadyTargets.length - 1
                      ? {
                          ...entry,
                          serverCandidate: {
                            accepted: true,
                            targetStatus: 'ready'
                          }
                        }
                      : entry
                  )
                });
                context.state.nodeConnectabilityFilter = { query: 'score', status: 'ready' };
                const activeConnectabilityFilter = context.nodeConnectabilityActiveFilter();
                const filteredScoreTargets = context.nodeConnectabilityDisplayTargets(
                  scoreConnectability,
                  activeConnectabilityFilter
                );
                const filteredRootBlockedTargets = context.nodeConnectabilityDisplayTargets(
                  rootConnectability,
                  { query: '', normalizedQuery: '', status: 'blocked' }
                );
                const filteredControls = context.renderNodeConnectabilityFilterControls(connectability, activeConnectabilityFilter);
                const filteredPanel = context.renderNodeConnectabilityPanel(context.state.builder.nodes[1]);
                context.clearNodeConnectabilityFilter();
                const clearedConnectabilityFilter = context.nodeConnectabilityActiveFilter();
                const scoreReadyTarget = scoreConnectability.availableTargets
                  .find((entry) => entry.target.nodeId === 'auditNode' && entry.target.path === 'score');
                const quickConnectButton = {
                  disabled: false,
                  dataset: {
                    connectSourceNode: scoreConnectability.source.nodeId,
                    connectSourcePort: scoreConnectability.source.port,
                    connectSourcePath: scoreConnectability.source.path,
                    connectTargetNode: scoreReadyTarget.target.nodeId,
                    connectTargetPort: scoreReadyTarget.target.port,
                    connectTargetPath: scoreReadyTarget.target.path,
                    connectTargetKind: scoreReadyTarget.kind,
                    connectTargetCondition: ''
                  }
                };
                const quickConnectSource = context.nodeConnectabilitySourceFromButton(quickConnectButton);
                const quickConnectTarget = context.nodeConnectabilityTargetFromButton(quickConnectButton);
                const serverCandidateResult = context.normalizeConnectionCandidatesResult({
                  schemaVersion: 'bloge.visualConnectionCandidates.v1',
                  kind: 'data',
                  source: {
                    nodeId: scoreConnectability.source.nodeId,
                    port: scoreConnectability.source.port,
                    path: scoreConnectability.source.path
	                  },
	                  totalCandidateCount: 3,
	                  statusCounts: { ready: 1, blocked: 2, wired: 0 },
	                  facetCounts: {
	                    surface: { input: 2 },
	                    schemaType: { integer: 1, object: 1 },
	                    operatorRef: { 'risk:audit': 2 },
	                    operatorLibraryId: { 'risk-policy': 2 },
	                    runtimeReadiness: { 'design-only': 2 },
	                    loweringMode: { design: 2 },
	                    sourceKind: { 'user-library': 2 }
	                  },
	                  offset: 1,
                  acceptedCount: 1,
                  rejectedCount: 2,
                  displayedCount: 2,
                  candidates: [{
                    targetNodeId: 'auditNode',
                    targetNodeLabel: 'Audit',
                    targetOperatorRef: 'risk:audit',
	                    targetSurface: 'input',
	                    target: { nodeId: 'auditNode', port: 'inputs', path: 'score' },
	                    accepted: true,
	                    targetStatus: 'ready',
	                    facetValues: {
	                      surface: 'input',
	                      schemaType: 'integer',
	                      operatorRef: 'risk:audit',
	                      operatorLibraryId: 'risk-policy',
	                      runtimeReadiness: 'design-only',
	                      loweringMode: 'design',
	                      sourceKind: 'user-library'
	                    },
	                    bindingKey: 'inputs.score',
                    summary: {
                      message: 'Server schema accepts score.',
                      runtimeBindingRequirementCount: 1,
                      runtimeBindingRequirementKeys: [
                        'RUNTIME_BINDING|connection-preview||auditNode|executable-lowering|risk:audit|'
                      ],
                      bindingKindCounts: { 'executable-lowering': 1 },
                      handoffLaneCounts: { 'operator-platform': 1 },
                      handoffKindCounts: { 'operator-implementation': 1 },
                      handoffTargetCounts: { 'risk:audit': 1 },
                      sourceKindCounts: { 'user-library': 1 },
                      operatorLibraryIdCounts: { 'risk-policy': 1 },
                      loweringModeCounts: { design: 1 },
                      readinessStateCounts: { 'design-only': 1 }
                    },
                    explanation: {
                      sourceLabel: 'riskNode.payload.score',
                      targetLabel: 'auditNode.inputs.score',
                      sourceSchemaType: 'integer',
                      targetSchemaType: 'integer',
                      sourceSchemaKnown: true,
                      targetSchemaKnown: true,
                      decisionSource: 'server-validator',
                      decisionMessage: '',
                      replacementSummary: '',
                      targetRuntimeBinding: {
                        requirementCount: 1,
                        requirementKeys: [
                          'RUNTIME_BINDING|connection-preview||auditNode|executable-lowering|risk:audit|'
                        ],
                        bindingKindCounts: { 'executable-lowering': 1 },
                        handoffLaneCounts: { 'operator-platform': 1 },
                        handoffKindCounts: { 'operator-implementation': 1 },
                        handoffTargetCounts: { 'risk:audit': 1 },
                        sourceKindCounts: { 'user-library': 1 },
                        operatorLibraryIdCounts: { 'risk-policy': 1 },
                        loweringModeCounts: { design: 1 },
                        readinessStateCounts: { 'design-only': 1 }
                      }
                    },
                    diagnostics: []
                  }, {
                    targetNodeId: 'auditNode',
                    targetNodeLabel: 'Audit',
                    targetOperatorRef: 'risk:audit',
	                    targetSurface: 'input',
	                    target: { nodeId: 'auditNode', port: 'inputs', path: 'risk' },
	                    accepted: false,
	                    targetStatus: 'blocked',
	                    facetValues: {
	                      surface: 'input',
	                      schemaType: 'object',
	                      operatorRef: 'risk:audit',
	                      operatorLibraryId: 'risk-policy',
	                      runtimeReadiness: 'design-only',
	                      loweringMode: 'design',
	                      sourceKind: 'user-library'
	                    },
	                    bindingKey: '',
                    summary: { message: '' },
                    explanation: {
                      sourceLabel: 'riskNode.payload.score',
                      targetLabel: 'auditNode.inputs.risk',
                      sourceSchemaType: 'integer',
                      targetSchemaType: 'object',
                      sourceSchemaKnown: true,
                      targetSchemaKnown: true,
                      decisionSource: 'server-validator',
                      decisionMessage: 'Server schema rejects root risk.',
                      firstDiagnosticCode: 'visual.binding.typeMismatch',
                      replacementSummary: 'Replaces 1 binding.',
                      replacedBindingCount: 1
                    },
                    diagnostics: [{ level: 'ERROR', message: 'Server schema rejects root risk.' }]
                  }]
                }, scoreConnectability.source);
                const unionCandidateResult = context.normalizeConnectionCandidatesResult({
                  schemaVersion: 'bloge.visualConnectionCandidates.v1',
                  kind: 'data',
                  source: {
                    nodeId: scoreConnectability.source.nodeId,
                    port: scoreConnectability.source.port,
                    path: scoreConnectability.source.path
                  },
                  totalCandidateCount: 1,
                  acceptedCount: 1,
                  rejectedCount: 0,
                  displayedCount: 1,
                  candidates: [{
                    targetNodeId: 'unionInputNode',
                    targetNodeLabel: 'Union Input',
                    targetOperatorRef: 'risk:unionInput',
                    targetSurface: 'input',
                    target: { nodeId: 'unionInputNode', port: 'inputs', path: 'value' },
                    accepted: true,
                    bindingKey: 'inputs.value',
                    summary: { message: 'Server schema accepts selected branch.' },
                    explanation: {
                      sourceLabel: 'riskNode.payload.score',
                      targetLabel: 'unionInputNode.inputs.value',
                      sourceSchemaType: 'integer',
                      targetSchemaType: 'integer',
                      sourceSchemaKnown: true,
                      targetSchemaKnown: true,
                      decisionSource: 'server-validator'
                    },
                    diagnostics: []
                  }]
                }, scoreConnectability.source, {
                  targetNodeId: 'unionInputNode',
                  targetSurface: 'input',
                  targetPort: 'inputs',
                  targetPath: 'value',
                  targetUnionBranch: { keyword: 'oneOf', index: 0 }
                });
                const unionTarget = {
                  nodeId: 'unionInputNode',
                  port: 'inputs',
                  path: 'value',
                  targetUnionBranch: { keyword: 'oneOf', index: 0 }
                };
                const unionCanvasNode = {
                  id: 'unionInputNode',
                  type: 'customOperator',
                  customInputPorts: { 'inputs.value': 'inputs' },
                  customInputPaths: { 'inputs.value': 'value' },
                  customInputUnionBranches: { 'inputs.value': { keyword: 'oneOf', index: 0 } }
                };
                const unionCanvasTarget = context.canvasTargetHandlesForNode(unionCanvasNode)
                  .find((target) => target.port === 'inputs' && target.path === 'value');
                const selectedUnionPreviewCovers = context.connectionCandidatePreviewCoversTarget(
                  unionCandidateResult,
                  unionTarget
                );
                const wrongUnionPreviewCovers = context.connectionCandidatePreviewCoversTarget(
                  unionCandidateResult,
                  { ...unionTarget, targetUnionBranch: { keyword: 'oneOf', index: 1 } }
                );
                const broadPreviewCoversSelectedUnion = context.connectionCandidatePreviewCoversTarget(
                  serverCandidateResult,
                  unionTarget
                );
                const broadPreviewRequestKey = context.connectionCandidatePreviewRequestKey(
                  scoreConnectability.source,
                  'data'
                );
                const unionPreviewRequestKey = context.connectionCandidatePreviewRequestKey(
                  scoreConnectability.source,
                  'data',
                  unionTarget
                );
                const unionTargetRequiresFocusedPreview =
                  context.connectionTargetRequiresFocusedCandidatePreview(unionTarget);
                const plainTargetRequiresFocusedPreview =
                  context.connectionTargetRequiresFocusedCandidatePreview(scoreReadyTarget.target);
                context.state.nodeConnectabilityServer = {
                  nodeId: 'riskNode',
                  draftKey: context.nodeConnectabilityServerDraftKey('riskNode', context.state.builder),
                  requestKey: context.nodeConnectabilityServerRequestKey('riskNode', context.state.builder),
                  status: 'ready',
                  offset: 0,
                  limit: 250,
                  resultsBySourceKey: {
                    [context.connectionCandidatePreviewSourceKey(scoreConnectability.source, 'data')]: serverCandidateResult
                  },
                  error: ''
                };
                const serverConnectability = context.nodeConnectabilitySummary('riskNode', context.state.builder);
                const serverScoreConnectability = serverConnectability.sources.find((entry) => entry.source.path === 'score');
                const serverRiskTarget = serverScoreConnectability.blockedTargets
                  .find((entry) => entry.target.nodeId === 'auditNode' && entry.target.path === 'risk');
                const serverReadyTarget = serverScoreConnectability.availableTargets
                  .find((entry) => entry.target.nodeId === 'auditNode' && entry.target.path === 'score');
                const serverConnectabilityPanel = context.renderNodeConnectabilityPanel(context.state.builder.nodes[1]);
                const serverFacetSummary =
                  context.nodeConnectabilityServerFacetSummary(context.state.nodeConnectabilityServer);
                context.state.nodeConnectabilityFilter = {
                  query: '',
                  status: '',
                  sourceKey: '',
                  facetFilters: {
                    schemaType: ['integer'],
                    runtimeReadiness: ['design-only'],
                    operatorLibraryId: ['risk-policy'],
                    operatorRef: ['risk:audit'],
                    sourceKind: ['user-library'],
                    loweringMode: ['design'],
                    surface: ['input']
                  }
                };
                const activeFacetFilter = context.nodeConnectabilityActiveFilter();
                const facetFilteredScoreTargets = context.nodeConnectabilityFilteredTargets(
                  serverScoreConnectability,
                  activeFacetFilter
                );
                const facetFilterControls = context.renderNodeConnectabilityFilterControls(
                  serverConnectability,
                  activeFacetFilter,
                  context.state.nodeConnectabilityServer
                );
                const facetFilterKey = context.nodeConnectabilityServerFacetFiltersKey(activeFacetFilter.facetFilters);
                const facetRequestKey = context.nodeConnectabilityServerRequestKey(
                  'riskNode',
                  context.state.builder,
                  0,
                  250,
                  '',
                  '',
                  '',
                  activeFacetFilter.facetFilters
                );
                const scoreSourceKey = context.nodeConnectabilitySourceFilterKey(scoreConnectability.source);
                context.state.nodeConnectabilityFilter = {
                  query: '',
                  status: '',
                  sourceKey: scoreSourceKey,
                  facetFilters: {}
                };
                const activeSourceFilter = context.nodeConnectabilityActiveFilter();
                const sourceFilteredSources = context.nodeConnectabilityDisplaySources(
                  serverConnectability,
                  activeSourceFilter
                );
                const sourceFilterControls = context.renderNodeConnectabilityFilterControls(
                  serverConnectability,
                  activeSourceFilter,
                  context.state.nodeConnectabilityServer
                );
                const sourceFilterSummary = context.nodeConnectabilityFilterSummary(
                  serverConnectability,
                  activeSourceFilter
                );
                const sourceFilteredPanel = sourceFilteredSources
                  .map((entry) => context.renderNodeConnectabilityRow(entry, activeSourceFilter))
                  .join('');
                const sourceRequestKey = context.nodeConnectabilityServerRequestKey(
                  'riskNode',
                  context.state.builder,
                  0,
                  250,
                  '',
                  '',
                  scoreSourceKey,
                  {}
                );
                const manySourceSummaries = Array.from({ length: 12 }, (_, index) => {
                  const path = `metric${index + 1}`;
                  return {
                    ...scoreConnectability,
                    source: {
                      ...scoreConnectability.source,
                      path
                    }
                  };
                });
                const manySourceConnectability = {
                  ...serverConnectability,
                  sourceCount: manySourceSummaries.length,
                  availableCount: manySourceSummaries.reduce((total, entry) => total + entry.availableCount, 0),
                  alreadyCount: manySourceSummaries.reduce((total, entry) => total + entry.alreadyCount, 0),
                  blockedCount: manySourceSummaries.reduce((total, entry) => total + entry.blockedCount, 0),
                  targetCount: manySourceSummaries.reduce((total, entry) => total + entry.targetCount, 0),
                  sources: manySourceSummaries
                };
                context.clearNodeConnectabilityFilter();
                const manySourceFilter = context.nodeConnectabilityActiveFilter();
                const manySourceWindow = context.nodeConnectabilityDisplaySourceWindow(manySourceConnectability, manySourceFilter);
                const manySourceDisplaySources = context.nodeConnectabilityDisplaySources(manySourceConnectability, manySourceFilter);
                const manySourceWindowSummary = context.nodeConnectabilitySourceWindowSummary(manySourceWindow);
                const manySourceWindowControls = context.renderNodeConnectabilitySourceWindowControls(manySourceWindow);
                const manySourceRequestScope = context.nodeConnectabilitySourceWindowRequestScopeKey(manySourceWindow);
                const manySourceScopedRequestKey = context.nodeConnectabilityServerRequestKey(
                  'riskNode',
                  context.state.builder,
                  0,
                  250,
                  '',
                  '',
                  '',
                  {},
                  manySourceRequestScope
                );
                const manySourceUnscopedRequestKey = context.nodeConnectabilityServerRequestKey(
                  'riskNode',
                  context.state.builder,
                  0,
                  250,
                  '',
                  '',
                  '',
                  {}
                );
                context.nodeConnectabilitySetSourceWindowOffset(manySourceWindow.key, context.nodeConnectabilitySourceWindowLimit());
                const manySourceSecondWindow = context.nodeConnectabilityDisplaySourceWindow(manySourceConnectability, manySourceFilter);
                const manySourceSecondSummary = context.nodeConnectabilitySourceWindowSummary(manySourceSecondWindow);
                const metric10SourceKey = context.nodeConnectabilitySourceFilterKey(manySourceSummaries[9].source);
                const manySourceSpecificFilter = {
                  query: '',
                  normalizedQuery: '',
                  status: '',
                  sourceKey: metric10SourceKey,
                  facetFilters: {}
                };
                const manySourceSpecificWindow = context.nodeConnectabilityDisplaySourceWindow(
                  manySourceConnectability,
                  manySourceSpecificFilter
                );
                context.nodeConnectabilitySetSourceWindowOffset(manySourceWindow.key, 0);
                context.clearNodeConnectabilityFilter();
                const truncatedServerStatus = context.renderNodeConnectabilityServerStatus({
                  nodeId: 'riskNode',
                  draftKey: context.nodeConnectabilityServerDraftKey('riskNode', context.state.builder),
                  requestKey: context.nodeConnectabilityServerRequestKey('riskNode', context.state.builder),
                  status: 'ready',
                  offset: 0,
                  limit: 250,
                  resultsBySourceKey: {
                    [context.connectionCandidatePreviewSourceKey(scoreConnectability.source, 'data')]:
                      context.normalizeConnectionCandidatesResult({
                        schemaVersion: 'bloge.visualConnectionCandidates.v1',
                        kind: 'data',
                        source: {
                          nodeId: scoreConnectability.source.nodeId,
                          port: scoreConnectability.source.port,
                          path: scoreConnectability.source.path
                        },
                        totalCandidateCount: 300,
                        facetCounts: {
                          schemaType: { integer: 300 },
                          operatorLibraryId: { 'risk-policy': 300 },
                          runtimeReadiness: { 'design-only': 300 }
                        },
                        offset: 0,
                        acceptedCount: 250,
                        rejectedCount: 50,
                        displayedCount: 250,
                        truncated: true,
                        candidates: []
                      }, scoreConnectability.source)
                  },
                  error: ''
                });
                const truncatedServerState = {
                  nodeId: 'riskNode',
                  draftKey: context.nodeConnectabilityServerDraftKey('riskNode', context.state.builder),
                  requestKey: context.nodeConnectabilityServerRequestKey('riskNode', context.state.builder, 0, 250),
                  status: 'ready',
                  offset: 0,
                  limit: 250,
                  resultsBySourceKey: {
                    [context.connectionCandidatePreviewSourceKey(scoreConnectability.source, 'data')]:
                      context.normalizeConnectionCandidatesResult({
                        schemaVersion: 'bloge.visualConnectionCandidates.v1',
                        kind: 'data',
                        source: {
                          nodeId: scoreConnectability.source.nodeId,
                          port: scoreConnectability.source.port,
                          path: scoreConnectability.source.path
                        },
                        totalCandidateCount: 300,
                        offset: 0,
                        acceptedCount: 250,
                        rejectedCount: 50,
                        displayedCount: 250,
                        truncated: true,
                        candidates: []
                      }, scoreConnectability.source)
                  },
                  error: ''
                };
                const pageTwoServerState = {
                  ...truncatedServerState,
                  requestKey: context.nodeConnectabilityServerRequestKey('riskNode', context.state.builder, 250, 250),
                  offset: 250,
                  resultsBySourceKey: {
                    [context.connectionCandidatePreviewSourceKey(scoreConnectability.source, 'data')]:
                      context.normalizeConnectionCandidatesResult({
                        schemaVersion: 'bloge.visualConnectionCandidates.v1',
                        kind: 'data',
                        source: {
                          nodeId: scoreConnectability.source.nodeId,
                          port: scoreConnectability.source.port,
                          path: scoreConnectability.source.path
                        },
                        totalCandidateCount: 300,
                        offset: 250,
                        acceptedCount: 50,
                        rejectedCount: 0,
                        displayedCount: 50,
                        truncated: false,
                        candidates: []
                      }, scoreConnectability.source)
                  }
                };
                const pageOneRequestKey = context.nodeConnectabilityServerRequestKey('riskNode', context.state.builder, 0, 250);
                const pageTwoRequestKey = context.nodeConnectabilityServerRequestKey('riskNode', context.state.builder, 250, 250);
	                const pageOneStats = context.nodeConnectabilityServerWindowStats(truncatedServerState);
	                const pageTwoStats = context.nodeConnectabilityServerWindowStats(pageTwoServerState);
	                const pageOneControls = context.renderNodeConnectabilityServerControls(truncatedServerState);
	                const pageTwoControls = context.renderNodeConnectabilityServerControls(pageTwoServerState);
	                const pageOneReadyStatusRequestKey = context.nodeConnectabilityServerRequestKey(
	                  'riskNode',
	                  context.state.builder,
	                  0,
	                  250,
	                  '',
	                  'ready'
	                );
	                const pageOneReadySchemaFacetRequestKey = context.nodeConnectabilityServerRequestKey(
	                  'riskNode',
	                  context.state.builder,
	                  0,
	                  250,
	                  '',
	                  'ready',
	                  '',
	                  { schemaType: ['integer'] }
	                );
	                context.state.nodeConnectabilityFilter = {
	                  query: '',
	                  status: 'ready',
	                  sourceKey: '',
	                  facetFilters: { schemaType: ['integer'] }
	                };
	                const activeServerStatus = context.nodeConnectabilityServerActiveStatus();
	                const activeServerSourceKey = context.nodeConnectabilityServerActiveSourceKey();
	                const activeServerFacetFilters = context.nodeConnectabilityServerActiveFacetFilters();
	                const connectabilityServerBeforeFetch = context.state.nodeConnectabilityServer;
	                const connectabilityFetchOptions = [];
                const previousFetch = context.fetch;
                const previousCandidateDiscoverer = context.discoverVisualConnectionCandidatesOnServer;
                context.fetch = () => {};
                context.discoverVisualConnectionCandidatesOnServer = (source, options) => {
                  connectabilityFetchOptions.push({
	                    sourcePath: source.path || '',
	                    offset: options.offset,
	                    limit: options.limit,
	                    includeRejected: options.includeRejected,
	                    targetStatus: options.targetStatus || '',
	                    facetFiltersKey: context.nodeConnectabilityServerFacetFiltersKey(options.facetFilters || {})
	                  });
                  return Promise.resolve(context.normalizeConnectionCandidatesResult({
                    schemaVersion: 'bloge.visualConnectionCandidates.v1',
                    kind: options.kind || 'data',
                    source: {
                      nodeId: source.nodeId,
                      port: source.port,
                      path: source.path || ''
                    },
                    totalCandidateCount: 300,
                    offset: options.offset,
                    acceptedCount: 0,
                    rejectedCount: 0,
                    displayedCount: 0,
                    truncated: false,
                    candidates: []
	                  }, source, { targetStatus: options.targetStatus || '', facetFilters: options.facetFilters || {} }));
	                };
                context.ensureNodeConnectabilityServerCandidates(
                  context.state.builder.nodes[1],
                  connectability,
                  { offset: 250, limit: 250, force: true }
                );
	                const connectabilityFetchOffsets = connectabilityFetchOptions
	                  .map((entry) => `${entry.sourcePath}:${entry.offset}:${entry.limit}:${entry.includeRejected}:${entry.targetStatus}:${entry.facetFiltersKey}`)
	                  .sort()
	                  .join('|');
	                context.state.nodeConnectabilityServer = connectabilityServerBeforeFetch;
	                context.state.nodeConnectabilityFilter = {
	                  query: '',
	                  status: 'ready',
	                  sourceKey: scoreSourceKey,
	                  facetFilters: { schemaType: ['integer'] }
	                };
	                connectabilityFetchOptions.length = 0;
	                context.ensureNodeConnectabilityServerCandidates(
	                  context.state.builder.nodes[1],
	                  connectability,
	                  { offset: 250, limit: 250, force: true }
	                );
	                const sourceFilteredFetchOffsets = connectabilityFetchOptions
	                  .map((entry) => `${entry.sourcePath}:${entry.offset}:${entry.limit}:${entry.includeRejected}:${entry.targetStatus}:${entry.facetFiltersKey}`)
	                  .sort()
	                  .join('|');
	                context.state.nodeConnectabilityServer = connectabilityServerBeforeFetch;
	                context.clearNodeConnectabilityFilter();
	                connectabilityFetchOptions.length = 0;
	                const manySourceFetchWindow = context.nodeConnectabilityDisplaySourceWindow(
	                  manySourceConnectability,
	                  context.nodeConnectabilityActiveFilter()
	                );
	                context.ensureNodeConnectabilityServerCandidates(
	                  context.state.builder.nodes[1],
	                  manySourceConnectability,
	                  {
	                    ...context.nodeConnectabilitySourceWindowRequestOptions(manySourceFetchWindow),
	                    offset: 0,
	                    limit: 250,
	                    force: true
	                  }
	                );
	                const manySourceWindowFetchPaths = connectabilityFetchOptions
	                  .map((entry) => entry.sourcePath)
	                  .sort()
	                  .join('|');
	                context.state.nodeConnectabilityServer = connectabilityServerBeforeFetch;
	                context.nodeConnectabilitySetSourceWindowOffset(manySourceFetchWindow.key, context.nodeConnectabilitySourceWindowLimit());
	                connectabilityFetchOptions.length = 0;
	                const manySourceSecondFetchWindow = context.nodeConnectabilityDisplaySourceWindow(
	                  manySourceConnectability,
	                  context.nodeConnectabilityActiveFilter()
	                );
	                context.ensureNodeConnectabilityServerCandidates(
	                  context.state.builder.nodes[1],
	                  manySourceConnectability,
	                  {
	                    ...context.nodeConnectabilitySourceWindowRequestOptions(manySourceSecondFetchWindow),
	                    offset: 0,
	                    limit: 250,
	                    force: true
	                  }
	                );
	                const manySourceSecondWindowFetchPaths = connectabilityFetchOptions
	                  .map((entry) => entry.sourcePath)
	                  .sort()
	                  .join('|');
	                context.nodeConnectabilitySetSourceWindowOffset(manySourceFetchWindow.key, 0);
	                context.state.nodeConnectabilityServer = connectabilityServerBeforeFetch;
	                context.clearNodeConnectabilityFilter();
	                context.fetch = previousFetch;
                context.discoverVisualConnectionCandidatesOnServer = previousCandidateDiscoverer;
                context.state.connectionCandidatePreview = {
                  sourceKey: context.connectionCandidatePreviewSourceKey(scoreConnectability.source, 'data'),
                  kind: serverCandidateResult.kind,
                  status: 'ready',
                  result: serverCandidateResult,
                  candidatesByTargetKey: serverCandidateResult.candidatesByTargetKey,
                  error: ''
                };
                const serverAcceptedDecision = context.connectionDragTargetDecision(scoreConnectability.source, scoreReadyTarget.target);
                const serverAcceptedMessage = context.connectionDragTargetMessage(
                  scoreConnectability.source,
                  scoreReadyTarget.target,
                  serverAcceptedDecision
                );
                const serverRejectedTarget = {
                  nodeId: 'auditNode',
                  port: 'inputs',
                  path: 'risk',
                  type: 'object',
                  schema: { type: 'object' }
                };
                const serverRejectedDecision = context.connectionDragTargetDecision(scoreConnectability.source, serverRejectedTarget);
	                const serverRejectedMessage = context.connectionDragTargetMessage(
	                  scoreConnectability.source,
	                  serverRejectedTarget,
	                  serverRejectedDecision
	                );
	                const serverWiredCandidateResult = context.normalizeConnectionCandidatesResult({
	                  schemaVersion: 'bloge.visualConnectionCandidates.v1',
	                  kind: 'data',
	                  source: {
	                    nodeId: scoreConnectability.source.nodeId,
	                    port: scoreConnectability.source.port,
	                    path: scoreConnectability.source.path
	                  },
	                  totalCandidateCount: 1,
	                  statusCounts: { ready: 0, blocked: 0, wired: 1 },
	                  acceptedCount: 0,
	                  rejectedCount: 1,
	                  displayedCount: 1,
	                  candidates: [{
	                    targetNodeId: 'auditNode',
	                    targetNodeLabel: 'Audit',
	                    targetOperatorRef: 'risk:audit',
	                    targetSurface: 'input',
	                    target: { nodeId: 'auditNode', port: 'inputs', path: 'score' },
	                    accepted: false,
	                    targetStatus: 'wired',
	                    summary: { message: 'Connection already exists.' },
	                    diagnostics: [{ level: 'ERROR', message: 'Connection already exists.' }]
	                  }]
	                }, scoreConnectability.source);
	                const serverWiredStatus = context.nodeConnectabilityTargetStatus({
	                  compatibility: { ok: false, message: 'Connection already exists.' },
	                  alreadyConnected: false,
	                  serverCandidate: serverWiredCandidateResult.candidates[0]
	                });
	                const localFallbackTarget = {
                  nodeId: 'auditNode',
                  port: 'inputs',
                  path: 'approved',
                  type: 'boolean',
                  schema: { type: 'boolean' }
                };
                const localFallbackDecision = context.connectionDragTargetDecision(scoreConnectability.source, localFallbackTarget);
                let quickConnectServerCall = '';
                let quickConnectApplied = '';
                let quickConnectMessage = '';
                let quickConnectMessageLevel = '';
                let quickConnectEditorRenders = 0;
                let quickConnectDiagramRenders = 0;
                context.checkVisualConnectionOnServer = (source, target) => {
                  quickConnectServerCall = `${context.endpointLabel(source)} -> ${context.endpointLabel(target)}`;
                  const serverCheck = { accepted: true, bindingKey: 'inputs.score', diagnostics: [], message: '' };
                  return {
                    then: (onFulfilled) => {
                      const result = onFulfilled(serverCheck);
                      return {
                        catch: () => ({
                          finally: (onFinally) => {
                            onFinally();
                            return Promise.resolve(result);
                          }
                        })
                      };
                    }
                  };
                };
                context.applyConnection = (source, target) => {
                  quickConnectApplied = `${context.endpointLabel(source)} -> ${context.endpointLabel(target)}:${target.key || ''}`;
                };
                context.setConnectionMessage = (text, level) => {
                  quickConnectMessage = text;
                  quickConnectMessageLevel = level;
                };
                context.renderSelectedOperatorEditor = () => {
                  quickConnectEditorRenders += 1;
                };
                context.renderDiagram = () => {
                  quickConnectDiagramRenders += 1;
                };
                const quickConnectPromise = context.connectNodeConnectabilityFromButton(quickConnectButton);
                context.targetHandlesForNode = (node) => {
                  if (node.id !== 'auditNode') {
                    return [];
                  }
                  return [
                    { nodeId: 'auditNode', port: 'inputs', key: 'risk', path: 'risk', type: 'object', schema: { type: 'object' }, required: true },
                    { nodeId: 'auditNode', port: 'inputs', key: 'score', path: 'score', type: 'integer', schema: { type: 'integer' }, required: true },
                    { nodeId: 'auditNode', port: 'inputs', key: 'approved', path: 'approved', type: 'boolean', schema: { type: 'boolean' }, required: true }
                  ];
                };
                const autoScoreSource = {
                  nodeId: 'riskNode',
                  port: 'payload',
                  path: 'score',
                  type: 'integer',
                  schema: { type: 'integer' },
                  dslPathSafe: true
                };
                context.sourceCandidatesForTarget = (target) => {
                  if (target.path === 'score') {
                    return [{ source: autoScoreSource, compatibility: { ok: true, message: '' } }];
                  }
                  if (target.path === 'approved') {
                    return [
                      { source: { nodeId: 'riskNode', port: 'payload', path: 'eligible', type: 'boolean', schema: { type: 'boolean' } }, compatibility: { ok: true, message: '' } },
                      { source: { nodeId: '__ctx', port: 'ctx', path: 'approved', type: 'boolean', schema: { type: 'boolean' } }, compatibility: { ok: true, message: '' } }
                    ];
                  }
                  return [];
                };
                context.connectionAlreadyApplied = () => false;
                const autoBindPlan = context.requiredInputAutoBindPlan('auditNode');
                const autoBindButtonHtml = context.renderRequiredInputAutoBindButton(
                  context.state.builder.nodes.find((node) => node.id === 'auditNode'),
                  autoBindPlan
                );
                const autoBindButton = {
                  disabled: false,
                  dataset: { autoBindNode: 'auditNode' }
                };
                let autoBindServerCall = '';
                let autoBindApplied = '';
                let autoBindMessage = '';
                let autoBindMessageLevel = '';
                let autoBindEditorRenders = 0;
                let autoBindDiagramRenders = 0;
                const clearInputBuilder = JSON.parse(JSON.stringify(context.state.builder));
                const clearInputAction = context.nodeImpactSummary('riskNode', clearInputBuilder)
                  .contextInputs.find((entry) => entry.kind === 'input').clearAction;
                const clearInputExistsBefore = context.nodeImpactRelationExists(clearInputAction, clearInputBuilder);
                context.clearNodeImpactRelation(clearInputAction, clearInputBuilder);
                const clearInputValueAfter = clearInputBuilder.nodes.find((node) => node.id === 'riskNode').customInputs.score;
                const clearInputExistsAfter = context.nodeImpactRelationExists(clearInputAction, clearInputBuilder);
                const clearConfigBuilder = JSON.parse(JSON.stringify(context.state.builder));
                const clearConfigAction = context.nodeImpactSummary('riskNode', clearConfigBuilder)
                  .incoming.find((entry) => entry.kind === 'config').clearAction;
                const clearConfigExistsBefore = context.nodeImpactRelationExists(clearConfigAction, clearConfigBuilder);
                context.clearNodeImpactRelation(clearConfigAction, clearConfigBuilder);
                const clearConfigExistsAfter = context.nodeImpactRelationExists(clearConfigAction, clearConfigBuilder);
                const clearConfigPathAfter = context.hasConfigPath(
                  clearConfigBuilder.nodes.find((node) => node.id === 'riskNode').config,
                  'threshold'
                );
                const clearDependencyBuilder = JSON.parse(JSON.stringify(context.state.builder));
                const clearDependencyAction = context.nodeImpactSummary('riskNode', clearDependencyBuilder)
                  .incoming.find((entry) => entry.kind === 'dependency').clearAction;
                context.clearNodeImpactRelation(clearDependencyAction, clearDependencyBuilder);
                const clearDependencyAfter = clearDependencyBuilder.dependencyEdges
                  .some((edge) => edge.source === 'policy' && edge.target === 'riskNode');
                const clearRouteBuilder = JSON.parse(JSON.stringify(context.state.builder));
                const clearRouteAction = context.nodeImpactSummary('auditNode', clearRouteBuilder)
                  .incoming.find((entry) => entry.kind === 'route').clearAction;
                context.clearNodeImpactRelation(clearRouteAction, clearRouteBuilder);
                const clearRouteAfter = clearRouteBuilder.routeEdges
                  .some((edge) => edge.source === 'riskNode' && edge.target === 'auditNode' && edge.condition === 'eligible');
                const clearAllImpactBuilder = JSON.parse(JSON.stringify(context.state.builder));
                const clearAllImpactActionCount = context.nodeImpactClearActions('riskNode', clearAllImpactBuilder).length;
                const clearAllImpactCleared = context.clearNodeImpactRelationsForNode('riskNode', clearAllImpactBuilder);
                const clearAllImpactRemaining = context.nodeImpactClearActions('riskNode', clearAllImpactBuilder)
                  .filter((action) => context.nodeImpactRelationExists(action, clearAllImpactBuilder)).length;
                const clearAllRiskNode = clearAllImpactBuilder.nodes.find((node) => node.id === 'riskNode');
                const clearAllAuditNode = clearAllImpactBuilder.nodes.find((node) => node.id === 'auditNode');
                const clearAllRiskScore = clearAllRiskNode.customInputs.score;
                const clearAllRiskThresholdExists = context.hasConfigPath(clearAllRiskNode.config, 'threshold');
                const clearAllAuditRisk = clearAllAuditNode.customInputs.risk;
                const clearAllDependencyAfter = clearAllImpactBuilder.dependencyEdges
                  .some((edge) => edge.source === 'riskNode' || edge.target === 'riskNode');
                const clearAllRouteAfter = clearAllImpactBuilder.routeEdges
                  .some((edge) => edge.source === 'riskNode' || edge.target === 'riskNode');
                const clearAllOutputAfter = clearAllImpactBuilder.output?.nodeId || '';
                const originalBuilderAfterImpactProbe = context.state.builder;
                const originalSelectedNodeAfterImpactProbe = context.state.selectedNodeId;
                const detachButtonBuilder = JSON.parse(JSON.stringify(context.state.builder));
                detachButtonBuilder.output = { nodeId: 'riskNode', path: '' };
                context.state.builder = detachButtonBuilder;
                context.state.selectedNodeId = 'riskNode';
                context.state.builderHistoryUndo = [];
                context.state.builderHistoryRedo = [];
                let detachButtonEditorRenders = 0;
                let detachButtonDiagramRenders = 0;
                let detachButtonMessage = '';
                context.renderSelectedOperatorEditor = () => { detachButtonEditorRenders += 1; };
                context.renderDiagram = () => { detachButtonDiagramRenders += 1; };
                context.setConnectionMessage = (text) => { detachButtonMessage = text; };
                context.clearNodeImpactRelationsFromButton({ dataset: { clearNodeImpact: 'riskNode' } });
                const detachButtonOutputNode = context.state.builder.output?.nodeId || '';
                const detachButtonRemaining = context.nodeImpactClearActions('riskNode', context.state.builder)
                  .filter((action) => context.nodeImpactRelationExists(action, context.state.builder)).length;
                const detachButtonRenderCounts = `${detachButtonEditorRenders}|${detachButtonDiagramRenders}`;
                const deletePolicyBuilder = JSON.parse(JSON.stringify(originalBuilderAfterImpactProbe));
                deletePolicyBuilder.selectedId = 'policy';
                deletePolicyBuilder.nodes.find((node) => node.id === 'riskNode').customInputs.score = 'policy.output.maxTerm';
                deletePolicyBuilder.nodes.find((node) => node.id === 'riskNode').config.threshold = {
                  kind: 'expression',
                  expr: 'policy.output.score'
                };
                context.state.builder = deletePolicyBuilder;
                context.state.selectedNodeId = 'policy';
                context.state.builderHistoryUndo = [];
                context.state.builderHistoryRedo = [];
                let deleteRenderInputCount = 0;
                let deleteRenderDiagramCount = 0;
                let deleteConnectionMessage = '';
                context.renderInputForm = () => { deleteRenderInputCount += 1; };
                context.renderDiagram = () => { deleteRenderDiagramCount += 1; };
                context.setConnectionMessage = (text) => { deleteConnectionMessage = text; };
                context.deleteSelectedBuilderNode();
                const deletePolicyNodeStillPresent = context.state.builder.nodes
                  .some((node) => node.id === 'policy');
                const deletePolicyRiskNode = context.state.builder.nodes
                  .find((node) => node.id === 'riskNode');
                const deletePolicyRiskScore = deletePolicyRiskNode.customInputs.score;
                const deletePolicyThresholdExists = context.hasConfigPath(deletePolicyRiskNode.config, 'threshold');
                const deletePolicyEdgesRemain = [
                  ...(context.state.builder.dependencyEdges || []),
                  ...(context.state.builder.routeEdges || [])
                ].some((edge) => edge.source === 'policy' || edge.target === 'policy');
                const deletePolicyUndoSnapshotHasPolicy = JSON.parse(context.state.builderHistoryUndo[0].snapshot)
                  .nodes.some((node) => node.id === 'policy');
                const deletePolicyRenderCounts = `${deleteRenderInputCount}|${deleteRenderDiagramCount}`;
                context.state.builder = originalBuilderAfterImpactProbe;
                context.state.selectedNodeId = originalSelectedNodeAfterImpactProbe;

                """, """
                const checks = [
                  ['schema path suffix', context.dslReferenceSuffixForSchemaPath('items.0.score'), '.items[0].score'],
                  ['schema path parse', context.schemaPathFromDslReferenceSuffix('items[0].score'), 'items.0.score'],
                  ['expression descendant', context.expressionWithPath('risk.output.items', '0.score'), 'risk.output.items[0].score'],
                  ['template descendant', context.replaceTemplateDescendants('{{input.items.0.score}}', 'input.items', 'risk.output.items'), 'risk.output.items[0].score'],
                  ['spaced template descendant', context.replaceTemplateDescendants('{{ input.items.0.score }}', 'input.items', 'risk.output.items'), 'risk.output.items[0].score'],
                  ['spaced template root', context.renderTemplateExpression('{{ input.items.0 }} + {{ score }}', { items: 'risk.output.items', score: 'ctx.score' }), 'risk.output.items[0] + ctx.score'],
                  ['unresolved template', context.replaceUnresolvedTemplateReferences('{{input.items.0}}'), 'null'],
                  ['context source expression', context.expressionForConnectionSource({ nodeId: '__ctx', path: 'scores.0' }), 'ctx.scores[0]'],
                  ['node source expression', context.expressionForConnectionSource({ nodeId: 'riskArrayFacts', port: 'output', path: 'items.0' }), 'riskArrayFacts.output.items[0]'],
                  ['multi-port root array expression', context.expressionForConnectionSource({ nodeId: 'facts', port: 'payload', path: '0.score' }), 'facts.output.payload[0].score'],
                  ['multi-port root array parse port', resolvedPortPath.port, 'payload'],
                  ['multi-port root array parse path', resolvedPortPath.path, '0.score'],
                  ['context parse path', parsedContext.path, 'scores.0.value'],
                  ['unknown output parse path', parsedUnknownOutput.path, 'items.0.score'],
                  ['unknown output parse safety', parsedUnknownOutput.dslPathSafe, true],
                  ['unsafe output port source handles filtered', unsafeOutputHandleCount, 0],
                  ['unsafe output port output options filtered', unsafeOutputOptionCount, 0],
                  ['mixed unsafe output hides full output option', mixedOutputOptions, 'facts'],
                  ['mixed unsafe output default path', mixedOutputDefaultPath, 'facts'],
                  ['mixed unsafe output normalized path', mixedOutputNormalizedPath, 'facts'],
                  ['unsafe input port target handles filtered', unsafeInputTargetCount, 0],
                  ['canvas custom input omits root target when fields exist', canvasEligibilityTargets, 'score|amount'],
                  ['native config input hidden with business config', nativeConfigInputHidden, false],
                  ['native score input visible with business config', nativeScoreInputVisible, true],
                  ['native config input visible with execution config only', nativeExecutionOnlyConfigVisible, true],
                  ['native root config port path', nativeRootConfigInputPath, 'config'],
                  ['context binding kind', contextBinding.kind, 'contextPath'],
                  ['context binding path', contextBinding.path, 'scores.0.value'],
                  ['output binding kind', outputBinding.kind, 'nodePath'],
                  ['output binding path', outputBinding.path, 'items.0.score'],
                  ['union binding branch keyword', unionTargetBinding.targetUnionBranch.keyword, 'oneOf'],
                  ['union binding branch index', unionTargetBinding.targetUnionBranch.index, 1],
                  ['nested union binding branch keyword', nestedUnionTargetBinding.targetUnionBranches.payload.keyword, 'oneOf'],
                  ['nested union binding branch index', nestedUnionTargetBinding.targetUnionBranches.payload.index, 0],
                  ['nested union field paths', nestedUnionFieldPaths, 'payload|payload.score'],
                  ['config union branch keyword', configUnionSelection.keyword, 'oneOf'],
                  ['config union branch index', configUnionSelection.index, 0],
                  ['config union field paths', configUnionFieldPaths, 'payload|payload.score'],
                  ['config union allowed unknown paths', configUnionAllowedUnknownPaths, ''],
                  ['config union rejected unknown path', configUnionRejectedUnknownPaths, 'payload.decision'],
                  ['unsafe context expression kind', unsafeContextExpression.kind, 'expression'],
                  ['config array container', configArrayBeforeDelete, true],
                  ['config array value', configValueBeforeDelete, 'ctx.score'],
                  ['config nested array container', configNestedArrayBeforeDelete, true],
                  ['config nested object value', configNestedObjectValue, 720],
                  ['config array path deleted', context.hasConfigPath(config, 'thresholds.0'), false],
                  ['default input array expression', defaultInputs['scores.0.value'], 'ctx.scores[0].value'],
                  ['default custom input array expression', customInputs.customInputs['inputs.scores.0.value'], 'ctx.scores[0].value'],
                  ['unsafe input port default input count', Object.keys(unsafeDefaultInputs).length, 0],
                  ['unsafe input port default custom input count', Object.keys(unsafeDefaultCustomInputs.customInputs).length, 0],
                  ['default resource param array expression', resourceInputs['scores.0.value'], 'ctx.scores[0].value'],
                  ['resource param fallback array expression', resourceFallbackInputs['scores.0.value'], 'ctx.scores[0].value'],
                  ['array config deleted node reference removed', context.hasConfigPath(configWithArrayReferences, 'thresholds.0'), false],
                  ['array config sibling expression retained', context.configValueAtPath(configWithArrayReferences, 'thresholds.1').expr, 'keptNode.output.score'],
                  ['array config scalar retained', context.configValueAtPath(configWithArrayReferences, 'thresholds.2'), 42],
                  ['nested array config deleted node reference removed', context.hasConfigPath(configWithArrayReferences, 'nested.values.0'), false],
                  ['nested array config scalar retained', context.configValueAtPath(configWithArrayReferences, 'nested.values.1'), 'static'],
                  ['unknown array item config path', unknownArrayConfigPaths, 'rules.0.extra'],
                  ['DSL-safe static schema paths', dslSafeStaticPaths, 'items|items.0|items.0.safeNested|safeScore'],
                  ['DSL-safe dynamic input paths', dynamicInputPaths, 'safeScore'],
                  ['DSL-safe dynamic output paths', dynamicOutputPaths, 'safeScore'],
                  ['operator diagnostic search', operatorDiagnosticSearchValues, 'payload|visual.catalog.operatorRefShadowed|OperatorRef shadowed by runtime Java operator.|/operators/risk:eligibility'],
                  ['operator diagnostic badge warning', String(operatorDiagnosticBadge.includes('1 warning diagnostic')), 'true'],
                  ['operator diagnostic panel code', String(operatorDiagnosticPanel.includes('visual.catalog.operatorRefShadowed')), 'true'],
                  ['palette schema search input field', String(paletteSchemaSearchValues.includes('inputs.customer.id')), 'true'],
                  ['palette schema search output type', String(paletteSchemaSearchValues.includes('integer')), 'true'],
                  ['palette schema search config field', String(paletteSchemaSearchValues.includes('config.threshold')), 'true'],
                  ['palette schema search config type', String(paletteSchemaSearchValues.includes('number')), 'true'],
                  ['palette schema search title annotation', String(paletteSchemaSearchValues.includes('Risk threshold')), 'true'],
                  ['palette schema search description annotation', String(paletteSchemaSearchValues.includes('Minimum accepted score.')), 'true'],
                  ['palette schema search example annotation', String(paletteSchemaSearchValues.includes('0.72')), 'true'],
                  ['palette schema search default annotation', String(paletteSchemaSearchValues.includes('0.5')), 'true'],
                  ['palette schema search comment annotation', String(paletteSchemaSearchValues.includes('Authoring-time policy control.')), 'true'],
                  ['palette field title annotation', paletteInputField.title, 'Customer identifier'],
                  ['palette field example annotation', paletteInputField.examplesSummary, 'C-1001'],
                  ['palette input field hint', paletteInputFieldHint, 'Customer identifier · ex C-1001'],
                  ['palette config field hint', paletteConfigFieldHint, 'Risk threshold · ex 0.72 · default 0.5'],
                  ['palette comment-only field hint', paletteCommentOnlyHint, 'note Internal authoring note.'],
                  ['palette search tokens', normalizedPaletteTokens, 'risk|score'],
                  ['palette multi-token match', String(paletteMultiTokenMatch), 'true'],
                  ['palette multi-token miss', String(paletteMultiTokenMiss), 'false'],
                  ['palette capability labels', suspendableCapabilityLabels, 'durable|suspendable|requires secret|write-external'],
                  ['palette capability badges', String(suspendableCapabilityBadges.includes('durable') && suspendableCapabilityBadges.includes('suspendable') && suspendableCapabilityBadges.includes('requires secret')), 'true'],
                  ['palette design-only capability labels', designOnlyCapabilityLabels, 'design-only'],
                  ['palette design-only capability facets', designOnlyCapabilityFacets, 'design-only'],
                  ['palette design-only readiness facet', designOnlyReadinessFacet, 'design-only'],
                  ['palette executable capability facets', executableCapabilityFacets, 'runtime-executable|idempotent'],
                  ['palette executable readiness facet', executableReadinessFacet, 'runtime-executable'],
                  ['operator runtime readiness executable', `${executableReadiness.level}|${executableReadiness.title}`, 'success|Runtime executable'],
                  ['operator runtime readiness design-only', `${designOnlyReadiness.level}|${designOnlyReadiness.title}`, 'info|Design-only operator'],
                  ['operator runtime readiness design-only panel', String(designOnlyReadinessPanel.includes('DESIGN artifact only') && designOnlyReadinessPanel.includes('executable lowering is not bound yet')), 'true'],
                  ['operator runtime readiness blocked', `${suspendableReadiness.level}|${suspendableReadiness.title}`, 'warning|Runtime blocked'],
                  ['operator runtime readiness governed', `${governedReadiness.level}|${governedReadiness.title}`, 'warning|Executable with governance review'],
                  ['operator runtime readiness governed panel', String(governedReadinessPanel.includes('secret binding') && governedReadinessPanel.includes('non-idempotent side effect')), 'true'],
                  ['operator runtime readiness server state', `${serverReadiness.state}|${serverReadiness.title}`, 'GOVERNANCE_REVIEW|Server authoritative readiness'],
                  ['operator runtime readiness server panel', String(serverReadinessPanel.includes('server-reviewed')), 'true'],
                  ['operator runtime readiness server facet', serverReadinessFacet, 'governance-review'],
                  ['graph readiness normalized state', graphReadiness.state, 'design-only'],
                  ['graph readiness normalized node state', graphReadiness.nodes[0].state, 'design-only'],
                  ['graph readiness binding requirement kind', graphReadiness.runtimeBindingRequirements[0].bindingKind, 'executable-lowering'],
                  ['graph readiness binding requirement lane', graphReadiness.runtimeBindingRequirements[0].handoffLane, 'operator-platform'],
                  ['graph readiness binding requirement count', String(graphReadiness.runtimeBindingRequirementCount), '1'],
                  ['graph readiness status text', graphReadinessStatusText, 'Design-only graph · 1 executable, 1 design-only · DESIGN artifact'],
                  ['graph readiness panel visible', String(graphReadinessPanel.hidden), 'false'],
                  ['graph readiness panel class', graphReadinessPanel.className, 'library-impact-panel info'],
                  ['graph readiness panel heading', String(graphReadinessPanel.innerHTML.includes('Design Artifact Path')), 'true'],
                  ['graph readiness panel allowed action', String(graphReadinessPanel.innerHTML.includes('Save, export, and publish as DESIGN.')), 'true'],
                  ['graph readiness panel blocked action', String(graphReadinessPanel.innerHTML.includes('Compile, Run, and EXECUTABLE publish require executable runtime binding.')), 'true'],
                  ['graph readiness panel binding row', String(graphReadinessPanel.innerHTML.includes('Executable Lowering') && graphReadinessPanel.innerHTML.includes('risk:eligibility')), 'true'],
                  ['graph readiness panel binding handoff', String(graphReadinessPanel.innerHTML.includes('Operator Platform') && graphReadinessPanel.innerHTML.includes('Operator Implementation')), 'true'],
                  ['graph readiness panel node row', String(graphReadinessPanel.innerHTML.includes('eligibility') && graphReadinessPanel.innerHTML.includes('Design only')), 'true'],
                  ['publication readiness state', publicationReadiness.state, 'design-only'],
                  ['publication readiness status text', publicationReadinessStatusText, 'Design-only graph · 1 executable, 1 design-only · DESIGN artifact'],
                  ['publication readiness review row count', publicationReadinessRows.length, 1],
                  ['publication readiness review row label', publicationReadinessRows[0].label, 'Eligibility Draft · Design only'],
                  ['publication readiness review row value', publicationReadinessRows[0].value, 'risk:eligibility · Design-only operator'],
                  ['publication option readiness label', publicationOptionLabel, 'pub-design @0 · DESIGN · Design only · pub-design'],
                  ['publication list readiness label', publicationListReadinessLabel, 'Design only'],
                  ['publication summary readiness label', publicationSummaryReadinessLabel, 'Design only'],
                  ['publication asset summary design artifacts', publicationAssetSummary.designArtifactCount, 2],
                  ['publication asset summary executable artifacts', publicationAssetSummary.executableArtifactCount, 1],
                  ['publication asset summary design-only readiness', publicationAssetSummary.designOnlyCount, 1],
                  ['publication asset summary runtime-blocked readiness', publicationAssetSummary.runtimeBlockedCount, 1],
                  ['publication asset summary runtime-executable readiness', publicationAssetSummary.runtimeExecutableCount, 1],
                  ['publication asset summary level', publicationAssetSummaryLevel, 'warning'],
                  ['publication asset interesting count', publicationAssetInterestingPublications.length, 2],
                  ['publication asset first row label', publicationAssetSummaryRows[0].label, 'pub-design @0 · DESIGN · Design only · pub-design'],
                  ['publication asset blocked row level', publicationAssetRowLevel, 'warning'],
                  ['draft summary readiness label', draftSummaryReadinessLabel, 'Design only'],
                  ['draft history readiness label', draftHistoryOptionLabel, 'Design Draft @2 · active · Design only · Saved design draft.'],
                  ['draft asset summary total', draftAssetSummary.total, 3],
                  ['draft asset summary active count', draftAssetSummary.activeCount, 2],
                  ['draft asset summary recoverable count', draftAssetSummary.recoverableDeletedCount, 1],
                  ['draft asset summary design-only readiness', draftAssetSummary.designOnlyCount, 2],
                  ['draft asset summary runtime-blocked readiness', draftAssetSummary.runtimeBlockedCount, 1],
                  ['draft asset summary level', draftAssetSummaryLevel, 'warning'],
                  ['draft asset interesting count', draftAssetInterestingSummaries.length, 3],
                  ['draft asset first row label', draftAssetSummaryRows[0].label, 'Design Draft @2 · active · Design only'],
                  ['draft asset blocked row level', draftAssetBlockedRowLevel, 'warning'],
                  ['graph readiness publish allowed kinds', designOnlyAllowedKinds, 'DESIGN'],
                  ['graph readiness publish selected kind', designOnlyPublishControl.selected, 'DESIGN'],
                  ['graph readiness publish select disabled', String(designOnlyPublishControl.selectDisabled), 'true'],
                  ['graph readiness publish button enabled', String(designOnlyPublishControl.publishDisabled), 'false'],
                  ['draft repair publish disabled', String(repairPublishControl.publishDisabled), 'true'],
                  ['draft repair publish level', repairPublishControl.level, 'error'],
                  ['unconstrained publish kinds', unconstrainedPublishControl.allowedKinds.join('|'), 'EXECUTABLE|DESIGN'],
                  ['publish error status beats readiness info', publishErrorStatusLevel, 'error'],
                  ['catalog facet summary', serverCatalogFacetSummary, 'Catalog mix: 3 Runtime executable · 2 Design only · 1 Runtime blocked · 1 Governance review · 1 Streaming · 1 Requires secret · 1 External effect.'],
                  ['catalog facet fallback total', fallbackCatalogFacets.total, 2],
                  ['catalog facet server library count', serverCatalogFacets.operatorLibraryIds['risk-policy'], 3],
                  ['catalog facet fallback library count', fallbackCatalogFacets.operatorLibraryIds['risk-policy'], 1],
                  ['catalog facet fallback design count', fallbackCatalogFacets.capabilities['design-only'], 1],
                  ['catalog facet fallback durable count', fallbackCatalogFacets.capabilities.durable, 1],
                  ['catalog facet fallback design readiness count', fallbackCatalogFacets.runtimeReadinessStates['design-only'], 1],
                  ['catalog facet fallback blocked readiness count', fallbackCatalogFacets.runtimeReadinessStates['runtime-blocked'], 1],
                  ['palette capability search match', String(paletteCapabilitySearchMatch), 'true'],
                  ['palette library search match', String(paletteLibrarySearchMatch), 'true'],
                  ['palette source filter match', String(paletteSourceFilterMatch), 'true'],
                  ['palette source filter miss', String(paletteSourceFilterMiss), 'false'],
                  ['palette library filter match', String(paletteLibraryFilterMatch), 'true'],
                  ['palette library filter miss', String(paletteLibraryFilterMiss), 'false'],
                  ['palette capability filter match', String(paletteCapabilityFilterMatch), 'true'],
                  ['palette capability filter miss', String(paletteCapabilityFilterMiss), 'false'],
                  ['palette readiness filter match', String(paletteReadinessFilterMatch), 'true'],
                  ['palette readiness filter miss', String(paletteReadinessFilterMiss), 'false'],
                  ['palette lowering filter match', String(paletteLoweringFilterMatch), 'true'],
                  ['palette lowering filter miss', String(paletteLoweringFilterMiss), 'false'],
                  ['library profile operator count', libraryProfile.operatorCount, 2],
                  ['library profile input count', libraryProfile.inputPortCount, 2],
                  ['library profile output count', libraryProfile.outputPortCount, 1],
                  ['library profile required count', libraryProfile.requiredInputCount, 1],
                  ['library profile config fields', libraryProfile.configFieldCount, 1],
                  ['library profile output fields', libraryProfile.outputFieldCount, 2],
                  ['library profile unsafe fields', libraryProfile.dslUnsafeFieldCount, 3],
                  ['library profile dynamic schemas', libraryProfile.dynamicSchemaCount, 2],
                  ['library profile streaming operators', libraryProfile.streamingOperatorCount, 1],
                  ['library profile durable operators', libraryProfile.durableOperatorCount, 1],
                  ['library profile external operators', libraryProfile.externalOperatorCount, 1],
                """, """
                  ['library profile non-idempotent operators', libraryProfile.nonIdempotentOperatorCount, 1],
                  ['library profile secret operators', libraryProfile.secretOperatorCount, 1],
                  ['library profile policy-restricted operators', libraryProfile.policyRestrictedOperatorCount, 1],
                  ['library profile runtime-blocked operators', libraryProfile.runtimeBlockedOperatorCount, 2],
                  ['library profile governance-review operators', governanceRiskProfile.governanceReviewOperatorCount, 1],
                  ['library profile operator input field count', libraryProfile.operators[0].inputFields.length, 3],
                  ['library profile operator output field count', libraryProfile.operators[0].outputFields.length, 2],
                  ['library profile operator config field count', libraryProfile.operators[0].configFields.length, 1],
                  ['library profile input field title', libraryCustomerField.title, 'Customer identifier'],
                  ['library profile input field examples', libraryCustomerField.examplesSummary, 'C-1001'],
                  ['library profile input field default', libraryCustomerField.defaultSummary, 'UNKNOWN'],
                  ['library profile config field title', libraryThresholdField.title, 'Risk threshold'],
                  ['library profile config field examples', libraryThresholdField.examplesSummary, '720, 760 +1 more'],
                  ['library profile config field default', libraryThresholdField.defaultSummary, '700'],
                  ['library profile config field comment', libraryThresholdField.commentSummary, 'Tune only during risk policy review.'],
                  ['library profile level', context.libraryProfileLevel(libraryProfile), 'warning'],
                  ['library profile runtime risk level', context.libraryProfileLevel(runtimeRiskProfile), 'warning'],
                  ['library profile governance risk level', context.libraryProfileLevel(governanceRiskProfile), 'warning'],
                  ['library profile external-only level', context.libraryProfileLevel(externalOnlyProfile), 'warning'],
                  ['library profile policy-only level', context.libraryProfileLevel(policyOnlyProfile), 'info'],
                  ['library profile policy-only operators', policyOnlyProfile.policyRestrictedOperatorCount, 1],
                  ['library profile policy-only summary', policyOnlyProfile.operators[0].policySummary, 'tenants gold, silver, bronze +1; namespaces lending; env prod'],
                  ['library profile design-only level', context.libraryProfileLevel(designOnlyProfile), 'info'],
                  ['library profile design-only operators', designOnlyProfile.designOnlyOperatorCount, 1],
                  ['library profile html escapes score', String(libraryProfileHtml.includes('Risk &lt;Score&gt;')), 'true'],
                  ['library profile html streaming chip', String(libraryProfileHtml.includes('1 streaming operators')), 'true'],
                  ['library profile html durable chip', String(libraryProfileHtml.includes('1 durable operators')), 'true'],
                  ['library profile html non-idempotent chip', String(libraryProfileHtml.includes('1 non-idempotent operators')), 'true'],
                  ['library profile html policy chip', String(libraryProfileHtml.includes('1 scope-restricted operators')), 'true'],
                  ['library profile html design-only chip', String(designOnlyProfileHtml.includes('1 design-only operators')), 'true'],
                  ['library profile html server runtime-blocked chip', String(serverRuntimeBlockedProfileHtml.includes('1 runtime-blocked operators')), 'true'],
                  ['library profile html server readiness title', String(serverRuntimeBlockedProfileHtml.includes('readiness Runtime binding unresolved')), 'true'],
                  ['library profile html import readiness state', String(importReadinessProfileHtml.includes('Runtime binding required')), 'true'],
                  ['library profile html import readiness gates', String(importReadinessProfileHtml.includes('ackWarnings + actor/reason')), 'true'],
                  ['library profile html import readiness affected', String(importReadinessProfileHtml.includes('2 drafts · 1 operators')), 'true'],
                  ['library profile html import readiness action', String(importReadinessProfileHtml.includes('bind the missing runtime')), 'true'],
                  ['library profile html import routing heading', String(importReadinessProfileHtml.includes('Runtime binding routing')), 'true'],
                  ['library profile html import routing binding', String(importReadinessProfileHtml.includes('Runtime Adapter: 1')), 'true'],
                  ['library profile html import routing lane', String(importReadinessProfileHtml.includes('Runtime Platform: 1')), 'true'],
                  ['library profile html import routing route', String(importReadinessProfileHtml.includes('missingRuntimeBinding: 1')), 'true'],
                  ['library profile html import routing library', String(importReadinessProfileHtml.includes('server-reviewed: 1')), 'true'],
                  ['library profile html import handoff groups heading', String(importReadinessProfileHtml.includes('Runtime binding handoff groups')), 'true'],
                  ['library profile html import handoff groups count', String(importReadinessProfileHtml.includes('1 requirement')), 'true'],
                  ['library profile html import handoff groups action', String(importReadinessProfileHtml.includes('Bind the missing runtime adapter.')), 'true'],
                  ['library profile html import binding heading', String(importReadinessProfileHtml.includes('Runtime binding requirements')), 'true'],
                  ['library profile html import binding label', String(importReadinessProfileHtml.includes('Native Binding')), 'true'],
                  ['library profile html import binding target', String(importReadinessProfileHtml.includes('missingRuntimeBinding')), 'true'],
                  ['library profile html import binding library owner', String(importReadinessProfileHtml.includes('library server-reviewed')), 'true'],
                  ['library profile html import binding handoff', String(importReadinessProfileHtml.includes('Runtime Platform') && importReadinessProfileHtml.includes('Runtime Adapter')), 'true'],
                  ['library profile html import binding action', String(importReadinessProfileHtml.includes('Bind the missing runtime adapter.')), 'true'],
                  ['library profile html policy summary', String(libraryProfileHtml.includes('policy tenants demo-tenant; namespaces local; env browser')), 'true'],
                  ['library profile policy-only html summary', String(policyOnlyProfileHtml.includes('policy tenants gold, silver, bronze +1; namespaces lending; env prod')), 'true'],
                  ['library profile html includes required input field', String(libraryProfileHtml.includes('inputs.customer.id*')), 'true'],
                  ['library profile html includes input annotation', String(libraryProfileHtml.includes('inputs.customer.id* (Customer identifier)')), 'true'],
                  ['library profile html includes unsafe input field', String(libraryProfileHtml.includes('inputs.customer.bad-field !')), 'true'],
                  ['library profile html includes unsafe input port', String(libraryProfileHtml.includes('input.(root) !')), 'true'],
                  ['library profile html includes unsafe field chip', String(libraryProfileHtml.includes('3 DSL-unsafe fields/ports')), 'true'],
                  ['library profile html includes output field', String(libraryProfileHtml.includes('graph.score* !')), 'true'],
                  ['library profile html includes config field', String(libraryProfileHtml.includes('config threshold')), 'true'],
                  ['library profile html includes config annotation', String(libraryProfileHtml.includes('config threshold (Risk threshold)')), 'true'],
                  ['library profile html includes dynamic flag', String(libraryProfileHtml.includes('2 dynamic schema surfaces')), 'true'],
                  ['library profile invalid json', String(Boolean(invalidLibraryProfile.parseError)), 'true'],
                  ['library profile yaml pending', String(Boolean(yamlLibraryProfile.awaitingServerValidation)), 'true'],
                  ['library profile yaml id', yamlLibraryProfile.libraryId, 'risk-yaml'],
                  ['library profile yaml html validation required', String(yamlLibraryProfileHtml.includes('Click Validate to load the server-reviewed profile.')), 'true'],
                  ['mixed binding candidate summary', mixedCandidateSummary, '1 compatible · 1 blocked · source type string cannot feed target type integer'],
                  ['mixed binding candidate level', mixedCandidateLevel, 'success'],
                  ['blocked binding candidate summary', blockedCandidateSummary, '0 compatible · 1 blocked · Target path is not accepted.'],
                  ['blocked binding candidate level', blockedCandidateLevel, 'error'],
                  ['empty binding candidate summary', emptyCandidateSummary, '0 compatible sources.'],
                  ['empty binding candidate level', emptyCandidateLevel, 'info'],
                  ['source candidate compatible first', sourceCandidateOrder, 'ctx.score:ok|ctx.name:blocked|policy.output.decision:blocked'],
                  ['source candidate compatible group', String(sourceCandidateOptionsHtml.includes('Compatible sources (1)')), 'true'],
                  ['source candidate blocked group', String(sourceCandidateOptionsHtml.includes('Blocked sources (2)')), 'true'],
                  ['source candidate selected option', String(sourceCandidateOptionsHtml.includes('ctx.score · integer') && sourceCandidateOptionsHtml.includes(' selected')), 'true'],
                  ['source candidate blocked reason', String(sourceCandidateOptionsHtml.includes('ctx.name · string · string cannot feed integer')), 'true'],
                  ['local ok preflight message', localOkPreflightMessage, 'Checking connection with server...'],
                  ['local mismatch preflight message', localMismatchPreflightMessage, 'Type mismatch: string cannot feed integer. Asking server for final decision...'],
                  ['local mismatch advisory helper', String(context.connectionLocalMismatchIsAdvisory('Type mismatch: string cannot feed integer.')), 'true'],
                  ['local cycle advisory helper', String(context.connectionLocalMismatchIsAdvisory('This connection would create a cycle.')), 'false'],
                  ['local mismatch status level', localMismatchStatus.level, 'info'],
                  ['local mismatch status message', localMismatchStatus.message, 'Local schema hint: Type mismatch: string cannot feed integer. Server validation is authoritative.'],
                  ['local cycle status level', localCycleStatus.level, 'error'],
                  ['local cycle status message', localCycleStatus.message, 'This connection would create a cycle.'],
                  ['layout group node ids', layoutGroupIds, 'secondaryCreditProvider|assembleSecondary'],
                  ['layout group bounds', layoutGroupBounds, '336|258|498|140'],
                  ['layout group label', layoutGroupRegion.label, 'Secondary <Path>'],
                  ['layout group kind class', layoutGroupKindClass, 'degradation-path'],
                  ['history undo after record', historyUndoAfterRecord, 1],
                  ['history redo after record', historyRedoAfterRecord, 0],
                  ['history undo restored x', historyUndoRestoredX, 80],
                  ['history redo after undo', historyRedoAfterUndo, 1],
                  ['history preview reset after undo', historyPreviewAfterUndo, 0],
                  ['history visual check reset after undo', historyVisualCheckAfterUndo, 'Not checked'],
                  ['history render after undo', historyRenderCountAfterUndo, 1],
                  ['history redo restored x', historyRedoRestoredX, 260],
                  ['history undo after redo', historyUndoAfterRedo, 1],
                  ['history clear undo', historyClearUndo, 0],
                  ['history clear redo', historyClearRedo, 0],
                  ['history clear message', historyClearMessage, 'Loaded draft; local edit history cleared.'],
                  ['history shortcut editable target', editableShortcutTarget, true],
                  ['history shortcut canvas target', canvasShortcutTarget, false],
                  ['visual diagnostic summary total', visualDiagnosticSummary.total, 3],
                  ['visual diagnostic summary errors', visualDiagnosticSummary.errorCount, 1],
                  ['visual diagnostic summary warnings', visualDiagnosticSummary.warningCount, 1],
                  ['visual diagnostic summary untargeted', visualDiagnosticSummary.untargetedCount, 1],
                  ['visual diagnostic summary nodes', visualDiagnosticNodeIds, 'riskNode|policy'],
                  ['visual diagnostic queue ids', visualDiagnosticQueueIds, 'riskNode|policy'],
                  ['visual diagnostic overflow none', visualDiagnosticOverflowNone, ''],
                  ['visual diagnostic overflow one', visualDiagnosticOverflowOne, '1 more node'],
                  ['visual diagnostic overflow many', visualDiagnosticOverflowMany, '3 more nodes'],
                  ['visual diagnostic active overflow preview', visualDiagnosticOverflowPreview, 'overflowNode0|overflowNode1|overflowNode2|overflowNode3|overflowNode4|overflowNode7'],
                  ['visual diagnostic overflow chip', String(visualDiagnosticOverflowHtml.includes('2 more nodes')), 'true'],
                  ['visual diagnostic overflow aria label', String(visualDiagnosticOverflowHtml.includes('aria-label="2 more nodes not shown in compact diagnostic preview"')), 'true'],
                  ['visual diagnostic active overflow chip', String(visualDiagnosticOverflowActiveHtml.includes('overflowNode7') && visualDiagnosticOverflowActiveHtml.includes('8/8')), 'true'],
                  ['visual diagnostic risk position', visualDiagnosticRiskPosition, '1/2'],
                  ['visual diagnostic policy position', visualDiagnosticPolicyPosition, '2/2'],
                  ['visual diagnostic missing position', visualDiagnosticMissingPosition, ''],
                  ['visual diagnostic risk display label', visualDiagnosticRiskDisplayLabel, 'Eligibility (riskNode)'],
                  ['visual diagnostic missing display label', visualDiagnosticMissingDisplayLabel, 'missingNode'],
                  ['visual diagnostic filter notice', String(visualDiagnosticFilterNotice.includes('Showing 1 issue for Eligibility (riskNode)')), 'true'],
                  ['visual diagnostic empty filter notice', visualDiagnosticEmptyFilterNotice, ''],
                  ['visual diagnostic queue first', visualDiagnosticFirstTarget, 'riskNode'],
                  ['visual diagnostic queue next', visualDiagnosticNextTarget, 'policy'],
                  ['visual diagnostic queue prev', visualDiagnosticPrevTarget, 'policy'],
                  ['visual diagnostic shortcut next', visualDiagnosticShortcutNext, 1],
                  ['visual diagnostic shortcut prev', visualDiagnosticShortcutPrev, -1],
                  ['visual diagnostic shortcut command ignored', visualDiagnosticShortcutCommand, 0],
                  ['visual diagnostic clear shortcut active', visualDiagnosticClearShortcutActive, true],
                  ['visual diagnostic clear shortcut inactive', visualDiagnosticClearShortcutInactive, false],
                  ['visual diagnostic clear shortcut command ignored', visualDiagnosticClearShortcutCommand, false],
                  ['visual diagnostic clear result', visualDiagnosticClearResult, true],
                  ['visual diagnostic filter after clear', visualDiagnosticFilterAfterClear, ''],
                  ['visual diagnostic clear render count', visualDiagnosticClearRenderCount, 1],
                  ['visual diagnostic clear again', visualDiagnosticClearAgain, false],
                  ['visual diagnostic risk text', visualDiagnosticRiskText, '1 issue · 1 error · visual.input.required'],
                  ['visual diagnostic summary filter button', String(visualDiagnosticSummaryHtml.includes('data-diagnostic-filter-node="riskNode"')), 'true'],
                  ['visual diagnostic summary node aria label', String(visualDiagnosticSummaryHtml.includes('aria-label="Filter visual diagnostics to Eligibility (riskNode): 1 issue')), 'true'],
                  ['visual diagnostic summary step button', String(visualDiagnosticSummaryHtml.includes('data-diagnostic-step="1"')), 'true'],
                  ['visual diagnostic summary step aria label', String(visualDiagnosticSummaryHtml.includes('aria-label="Next visual diagnostic node"')), 'true'],
                  ['visual diagnostic summary clear aria label', String(visualDiagnosticFilteredHtml.includes('aria-label="Show all visual diagnostics"')), 'true'],
                  ['visual diagnostic summary position chip', String(visualDiagnosticFilteredHtml.includes('1/2')), 'true'],
                  ['visual diagnostic summary global count', String(visualDiagnosticSummaryHtml.includes('1 global')), 'true'],
                  ['visual diagnostic summary active filter', String(visualDiagnosticFilteredHtml.includes('filtered to Eligibility (riskNode)')), 'true'],
                  ['openapi operation normalize count', normalizedOpenApiOperations.length, 1],
                  ['openapi operation label', openApiOperationLabel, 'READY · POST /orders/{orderId} · submitOrder · application/json'],
                  ['openapi multipart operation label', openApiMultipartOperationLabel, 'READY · POST /orders/{orderId}/notes · uploadOrderNote · multipart/form-data'],
                  ['openapi operation match by id', openApiOperationMatchById, true],
                  ['openapi operation match by path', openApiOperationMatchByPath, true],
                  ['openapi operation miss', openApiOperationMiss, false],
                  ['openapi readiness summary counts', openApiReadinessSummaryText, '3|1|1|1'],
                  ['openapi selected blocked operation', openApiSelectedBlockedOperation.operationId, 'healthText'],
                  ['openapi selected blocked operation flag', context.openApiOperationIsBlocked(openApiSelectedBlockedOperation), true],
                  ['openapi blocked projection message', String(openApiBlockedProjectionMessage.includes('OpenAPI projection is blocked:') && openApiBlockedProjectionMessage.includes('Select a READY/WARNING operation')), 'true'],
                  ['openapi ready projection message', openApiReadyProjectionMessage, ''],
                  ['openapi readiness summary selected detail', String(openApiSummaryHtml.includes('Selected · BLOCKED') && openApiSummaryHtml.includes('Selected 2xx response is not JSON.')), 'true'],
                  ['openapi readiness summary stats', String(openApiSummaryHtml.includes('<strong>1</strong> ready') && openApiSummaryHtml.includes('<strong>1</strong> blocked')), 'true'],
                  ['openapi readiness empty summary', openApiEmptySummaryHtml, ''],
                  ['openapi blocked status level', openApiBlockedStatusLevel, 'error'],
                  ['openapi blocked status message', openApiBlockedStatusMessage, 'BLOCKED · GET /health · healthText: Selected 2xx response is not JSON.'],
                  ['openapi operation applied', openApiOperationApplied, 'submitOrder|/orders/{orderId}|POST'],
                  ['openapi operation applied message', openApiOperationMessage, 'READY · POST /orders/{orderId} · submitOrder: Ready to project.'],
                  ['openapi operation applied message level', openApiOperationMessageLevel, 'success'],
                  ['canvas search custom config hit', riskSearch, 'riskNode'],
                  ['diagnostic node pointer index 0', policyByPointer, 'policy'],
                  ['diagnostic node pointer index 1', riskByPointer, 'riskNode'],
                  ['diagnostic visual layout pointer index 0', riskByLayoutPointer, 'riskNode'],
                  ['diagnostic direct node target', policyByDirectNode, 'policy'],
                  ['diagnostic node count', riskDiagnosticCount, 1],
                  ['json pointer unescape', unescapedPointerSegment, 'node/with~marker'],
                """, """
                  ['run trace node lookup', riskTraceNode.nodeId, 'riskNode'],
                  ['run trace level', riskTraceLevel, 'error'],
                  ['run trace status label', riskTraceStatus, 'COMPLETED'],
                  ['run trace badge', riskTraceBadge, 'ERR 1'],
                  ['run trace issue text', riskTraceIssueText, '1 issue · COMPLETED · 2 trace'],
                  ['selected diagnostics level', riskSelectedDiagnosticsLevel, 'error'],
                  ['selected diagnostics summary', riskSelectedDiagnosticsSummary, '1 validation issue · COMPLETED · 2 trace diagnostics'],
                  ['selected diagnostics panel has code', String(riskSelectedDiagnosticsPanel.includes('visual.input.required')), 'true'],
                  ['selected diagnostics panel has trace', String(riskSelectedDiagnosticsPanel.includes('risk:eligibility')), 'true'],
                  ['selected diagnostics panel isolates node', riskSelectedDiagnosticsLeak, false],
                  ['duplicate node id', duplicateNodeId, 'riskNode2'],
                  ['duplicate node count', duplicateNodeCount, 4],
                  ['duplicate selected id', duplicateSelectedId, 'riskNode2'],
                  ['duplicate copied input', duplicateCopiedInput, 'ctx.score'],
                  ['duplicate copied config expression', duplicateCopiedConfigExpr, 'policy.output.score'],
                  ['duplicate config deep copied', duplicateSourceNestedFlag, true],
                  ['duplicate output unchanged', duplicateOutputNode, 'riskNode'],
                  ['duplicate fingerprint snapshot omitted', duplicateFingerprint, ''],
                  ['duplicate history action', duplicateHistoryAction, 'Duplicate riskNode'],
                  ['duplicate undo node count', duplicateUndoNodeCount, 3],
                  ['duplicate trace cleared', duplicateTraceCleared, true],
                  ['duplicate render counts', duplicateRenderCounts, '1|1|1|1'],
                  ['duplicate message', duplicateMessage, 'Duplicated riskNode as riskNode2.'],
                  ['duplicate edges omitted', duplicateEdgesToCopy, 0],
                  ['run trace summary label', auditTraceSummaryLabel, 'COMPLETED · risk:audit · 9ms · selected output · 1 diagnostic'],
                  ['run trace coverage matched', traceCoverage.matchedNodeCount, 3],
                  ['run trace coverage total', traceCoverage.traceNodeCount, 4],
                  ['run trace coverage missing', traceCoverage.unmatchedNodeIds.join('|'), 'removedNode'],
                  ['run trace coverage selected matched', traceCoverage.selectedOutputMatched, true],
                  ['run trace coverage text', traceCoverageText, 'trace 3/4 mapped, 1 missing'],
                  ['active run trace cleared', activeTraceCleared, true],
                  ['run trace node count', traceSummary.nodeCount, 3],
                  ['run trace diagnostic count', traceSummary.diagnosticCount, 3],
                  ['run trace error count', traceSummary.errorCount, 1],
                  ['run trace selected output', traceSummary.selectedOutputNode, 'auditNode'],
                  ['risk impact incoming kinds', riskIncomingKinds, 'config:policy|dependency:policy'],
                  ['risk impact outgoing kinds', riskOutgoingKinds, 'data:auditNode:payload -> inputs.risk|dependency:auditNode:orders downstream execution|route:auditNode:routes on eligible'],
                  ['risk impact context input', riskContextInputs, 'ctx.score -> inputs.score'],
                  ['risk impact graph output affected', riskImpact.graphOutputAffected, true],
                  ['risk impact panel includes delete summary', String(riskImpactPanel.includes('Delete Impact')), 'true'],
                  ['risk impact panel includes focus button', String(riskImpactPanel.includes('data-impact-node="auditNode"')), 'true'],
                  ['risk impact panel includes clear button', String(riskImpactPanel.includes('data-clear-impact="input"')), 'true'],
                  ['risk impact clear action count', riskImpactClearActions.length, 7],
                  ['risk impact panel includes bulk detach', String(riskImpactPanel.includes('data-clear-node-impact="riskNode"')), 'true'],
                  ['risk impact panel includes downstream clear', String(riskImpactPanel.includes('data-impact-target="auditNode"')), 'true'],
                  ['risk usage ref', riskUsageRef, 'risk:eligibility'],
                  ['policy usage ref', policyUsageRef, 'bloge:decisionTable'],
                  ['risk usage level', riskUsageLevel, 'warning'],
                  ['risk usage primary status', riskUsagePrimaryStatus, 'DRIFTED'],
                  ['risk usage summary label', riskUsageSummary.label, 'DRIFT'],
                  ['risk usage summary title', riskUsageSummary.title, 'risk:eligibility: 1 draft · 1 publication · DRIFTED · Breaking Schema'],
                  ['risk usage change line', riskUsageChange, "Breaking Schema: input port 'inputs' schema changed"],
                  ['risk usage action line', riskUsageAction, 'Repair affected bindings or explicitly review before rebasing.'],
                  ['risk usage panel includes refresh action', String(riskUsagePanel.includes('data-operator-usage="risk:eligibility"')), 'true'],
                  ['risk usage panel includes drift status', String(riskUsagePanel.includes('DRIFTED')), 'true'],
                  ['risk usage panel includes changed surface', String(riskUsagePanel.includes('input port &#39;inputs&#39; schema changed')), 'true'],
                  ['risk usage panel includes risk label', String(riskUsagePanel.includes('Breaking Schema')), 'true'],
                  ['risk usage panel includes action guidance', String(riskUsagePanel.includes('Repair affected bindings')), 'true'],
                  ['risk fingerprint status', riskFingerprintStatus.status, 'DRIFTED'],
                  ['risk fingerprint level', riskFingerprintStatus.level, 'warning'],
                  ['risk fingerprint risk', riskFingerprintStatus.changeRisk, 'BREAKING_SCHEMA'],
                  ['clean draft dirty', cleanDraftDirty, false],
                  ['clean rebase block reason', cleanRebaseBlockReason, ''],
                  ['risk fingerprint can rebase', riskFingerprintStatus.canRebase, true],
                  ['risk fingerprint panel includes rebase action', String(riskFingerprintPanel.includes('data-rebase-operator-fingerprint="riskNode"')), 'true'],
                  ['risk fingerprint panel includes drift label', String(riskFingerprintPanel.includes('Snapshot drifted')), 'true'],
                  ['risk fingerprint panel includes risk guidance', String(riskFingerprintPanel.includes('Repair affected bindings')), 'true'],
                  ['dirty draft dirty', dirtyDraftDirty, true],
                  ['dirty rebase block reason', dirtyRebaseBlockReason, 'save or reload local changes before rebasing'],
                  ['dirty fingerprint can rebase', dirtyFingerprintStatus.canRebase, false],
                  ['dirty fingerprint reason', dirtyFingerprintStatus.rebaseReason, 'save or reload local changes before rebasing'],
                  ['dirty fingerprint panel disables rebase', String(dirtyFingerprintPanel.includes('disabled')), 'true'],
                  ['dirty fingerprint panel includes reason', String(dirtyFingerprintPanel.includes('save or reload local changes before rebasing')), 'true'],
                  ['risk usage panel includes rebase action', String(riskUsagePanel.includes('data-rebase-operator-fingerprint="riskNode"')), 'true'],
                  ['library impact diagnostics', libraryImpact.diagnosticCount, 4],
                  ['library impact errors', libraryImpact.errorCount, 2],
                  ['library impact warnings', libraryImpact.warningCount, 2],
                  ['library impact drafts', libraryImpact.draftIds.join('|'), 'draft-risk'],
                  ['library impact publications', libraryImpact.publicationIds.join('|'), 'pub-risk'],
                  ['library impact operators', libraryImpact.operatorRefs.join('|'), 'risk:audit|risk:eligibility'],
                  ['library impact risk counts', libraryImpact.changeRiskCounts.map((entry) => `${entry.risk}:${entry.count}`).join('|'), 'BREAKING_SCHEMA:1|GOVERNANCE:1'],
                  ['library impact risk text', libraryImpactRiskText, 'Breaking schema change: affected drafts need repair or explicit rebase review. Breaking Schema 1 · Governance 1.'],
                  ['library schema changes count', librarySchemaChanges.length, 1],
                  ['library schema change label', context.schemaChangeSurfaceLabel(librarySchemaChanges[0]), 'Output result.eligible'],
                  ['library schema change message', context.schemaChangeMessage(librarySchemaChanges[0]), 'Breaking: output result type changed'],
                  ['library schema rows include change', String(librarySchemaRows.includes('output result type changed')), 'true'],
                  ['library impact label', context.libraryImpactSummaryLabel(libraryImpact), '2 errors · 2 warnings · 1 draft · 1 publication · 2 operators'],
                  ['library payload impact diagnostics', libraryImpactFromPayload.diagnosticCount, 3],
                  ['library payload impact drafts deduped', libraryImpactFromPayload.draftIds.join('|'), 'draft-risk'],
                  ['library payload impact node index', libraryImpactFromPayload.draftTargets[0].nodeIndex, 1],
                  ['library payload impact publication target', libraryImpactFromPayload.publicationTargets[0].nodeIndex, 4],
                  ['library payload impact code', libraryImpactFromPayload.codeCounts[0].code, 'visual.library.operatorFingerprintDrift'],
                  ['library payload impact risk', libraryImpactFromPayload.changeRiskCounts[0].risk, 'BREAKING_SCHEMA'],
                  ['library payload risk text', libraryPayloadRiskText, 'Breaking schema change: affected drafts need repair or explicit rebase review. Breaking Schema 2.'],
                  ['library warning acknowledgement risk', String(libraryWarningAcknowledgement.includes('Breaking schema change')), 'true'],
                  ['library warning acknowledgement click', String(libraryWarningAcknowledgement.includes('click Import again')), 'true'],
                  ['library impact panel visible', libraryImpactPanel.hidden, false],
                  ['library impact panel level', libraryImpactPanel.className, 'library-impact-panel error'],
                  ['library impact panel prefers payload draft', String(libraryImpactPanel.innerHTML.includes('draft-from-payload')), 'true'],
                  ['library impact panel ignores fallback draft', String(libraryImpactPanel.innerHTML.includes('draft-risk')), 'false'],
                  ['library impact panel includes node index', String(libraryImpactPanel.innerHTML.includes('data-library-impact-node-index="2"')), 'true'],
                  ['library impact panel includes publication action', String(libraryImpactPanel.innerHTML.includes('data-library-impact-publication="pub-from-payload"')), 'true'],
                  ['library impact panel includes publication node index', String(libraryImpactPanel.innerHTML.includes('data-library-impact-node-index="5"')), 'true'],
                  ['library impact panel includes payload code', String(libraryImpactPanel.innerHTML.includes('visual.library.payloadImpact')), 'true'],
                  ['library impact panel includes risk label', String(libraryImpactPanel.innerHTML.includes('Runtime Binding')), 'true'],
                  ['library impact panel includes risk summary', String(libraryImpactPanel.innerHTML.includes('Runtime binding change')), 'true'],
                  ['library impact panel includes schema change', String(libraryImpactPanel.innerHTML.includes('Output result.eligible')), 'true'],
                  ['library diff panel includes schema change', String(libraryDiffPanel.innerHTML.includes('Input inputs.score')), 'true'],
                  ['library diff panel includes schema message', String(libraryDiffPanel.innerHTML.includes('input score type changed')), 'true'],
                  ['library impact draft group action', String(libraryImpactDraftGroup.includes('data-library-impact-draft="draft-risk"')), 'true'],
                  ['resource payload impact resources deduped', resourceImpactFromPayload.resourceIds.join('|'), 'order-service.listOrders'],
                  ['resource payload impact operators', resourceImpactFromPayload.operatorRefs.join('|'), 'resource:order-service.listOrders'],
                  ['resource payload impact publications', resourceImpactFromPayload.publicationIds.join('|'), 'pub-orders'],
                  ['resource payload impact draft target', resourceImpactFromPayload.draftTargets[0].nodeIndex, 3],
                  ['resource payload impact publication target', resourceImpactFromPayload.publicationTargets[0].nodeIndex, 4],
                  ['resource diagnostic impact resources', resourceImpactFromDiagnostics.resourceIds.join('|'), 'order-service.listOrders'],
                  ['resource diagnostic impact publications', resourceImpactFromDiagnostics.publicationIds.join('|'), 'pub-orders'],
                  ['resource diagnostic impact publication target', resourceImpactFromDiagnostics.publicationTargets[0].nodeIndex, 4],
                  ['resource diagnostic impact risk', resourceImpactFromDiagnostics.changeRiskCounts[0].risk, 'BREAKING_SCHEMA'],
                  ['resource warning acknowledgement risk', String(resourceWarningAcknowledgement.includes('Breaking schema change')), 'true'],
                  ['resource warning acknowledgement click', String(resourceWarningAcknowledgement.includes('click Save contract again')), 'true'],
                  ['resource impact panel visible', resourceImpactPanel.hidden, false],
                  ['resource impact panel level', resourceImpactPanel.className, 'library-impact-panel warning'],
                  ['resource impact panel includes resource', String(resourceImpactPanel.innerHTML.includes('order-service.listOrders')), 'true'],
                  ['resource impact panel includes publication', String(resourceImpactPanel.innerHTML.includes('pub-orders')), 'true'],
                  ['resource impact panel includes publication action', String(resourceImpactPanel.innerHTML.includes('data-library-impact-publication="pub-orders"')), 'true'],
                  ['resource impact panel includes publication node index', String(resourceImpactPanel.innerHTML.includes('data-library-impact-node-index="4"')), 'true'],
                  ['resource impact panel includes resource code', String(resourceImpactPanel.innerHTML.includes('visual.resourceContract.operatorFingerprintDrift')), 'true'],
                  ['resource impact panel includes node index', String(resourceImpactPanel.innerHTML.includes('data-library-impact-node-index="3"')), 'true'],
                  ['full output contract type', fullOutputContract.type, 'object'],
                  ['full output contract fields', fullOutputContract.fieldCount, 4],
                  ['full output contract required', fullOutputContract.requiredCount, 2],
                  ['full output contract source label', fullOutputContract.sourceLabel, 'riskNode.payload'],
                  ['nested output contract type', nestedOutputContract.type, 'string'],
                  ['nested output contract fields', nestedOutputContract.fieldCount, 0],
                  ['nested output contract source label', nestedOutputContract.sourceLabel, 'riskNode.payload.facts.reason'],
                  ['golden schema assertion mode', inferredSchemaAssertion.mode, 'OUTPUT_MATCHES_SCHEMA'],
                  ['golden schema assertion envelope', inferredSchemaAssertion.expectedValue.format, 'json-schema'],
                  ['golden schema assertion inferred field', inferredSchemaAssertion.expectedValue.schema.properties.approved.type, 'boolean'],
                  ['golden schema assertion inferred required', inferredSchemaAssertion.expectedValue.schema.required.join('|'), 'approved|score'],
                  ['golden schema assertion explicit type', explicitSchemaAssertion.expectedValue.properties.approved.type, 'boolean'],
                  ['golden approx assertion mode', inferredApproxAssertion.mode, 'PATH_APPROX_EQUALS'],
                  ['golden approx assertion path', inferredApproxAssertion.path, '/score'],
                  ['golden approx assertion inferred value', inferredApproxAssertion.expectedValue.value, 720.000002],
                  ['golden approx assertion inferred tolerance', inferredApproxAssertion.expectedValue.tolerance, 0.000001],
                  ['golden approx assertion explicit relative tolerance', explicitApproxAssertion.expectedValue.relativeTolerance, 0.01],
                  ['golden approx json pointer unescape', approxPointerValue, 720],
                  ['golden queued assertion count', queuedAssertions.length, 2],
                  ['golden queued assertion modes', queuedAssertionModes, 'PATH_APPROX_EQUALS|OUTPUT_MATCHES_SCHEMA'],
                  ['golden queued assertion cloned', queuedFirstValueAfterClone, 720.000002],
                  ['golden queued assertion summary', queuedAssertionSummary, '/score · {"value":720.000002,"tolerance":0.000001}'],
                  ['connectability source count', connectability.sourceCount, 3],
                  ['connectability available count', connectability.availableCount, 6],
                  ['connectability wired count', connectability.alreadyCount, 1],
                  ['connectability blocked count', connectability.blockedCount, 8],
                  ['connectability totals label', context.nodeConnectabilityTotalsLabel(connectability), '3 sources · 6 connectable · 1 wired · 8 blocked'],
                  ['connectability root summary', context.nodeConnectabilitySourceSummary(rootConnectability), '1 connectable · 1 already wired · 3 blocked'],
                  ['connectability score summary', context.nodeConnectabilitySourceSummary(scoreConnectability), '3 connectable · 2 blocked'],
                  ['connectability eligible summary', context.nodeConnectabilitySourceSummary(eligibleConnectability), '2 connectable · 3 blocked'],
                  ['connectability score level', context.nodeConnectabilitySourceLevel(scoreConnectability), 'success'],
                  ['connectability root first target label', context.nodeConnectabilityTargetLabel(rootConnectability.compatibleTargets[0]), 'Audit (auditNode) · data -> inputs.risk · wired'],
                  ['connectability root display targets', context.nodeConnectabilityDisplayTargets(rootConnectability).length, 4],
                  ['connectability root blocked preview count', context.nodeConnectabilityDisplayTargets(rootConnectability).filter((entry) => !entry.compatibility.ok).length, 2],
                  ['connectability overflow display targets', overflowDisplayTargets.length, 26],
                  ['connectability overflow display ready count', overflowDisplayWindow.displayed, 24],
                  ['connectability overflow total ready count', overflowDisplayWindow.total, 30],
                  ['connectability overflow summary', overflowSummary, 'Showing first 24 of 30 ready targets'],
                  ['connectability overflow window key', String(overflowDisplayWindowKey.includes(overflowSourceKey)), 'true'],
                  ['connectability overflow next offset', overflowDisplayWindow.nextOffset, 24],
                  ['connectability overflow has previous', overflowDisplayWindow.hasPrevious, false],
                  ['connectability overflow has next', overflowDisplayWindow.hasNext, true],
                  ['connectability overflow controls next', String(overflowWindowControls.includes('data-connectability-row-window="next"')), 'true'],
                  ['connectability overflow row marker', String(overflowRow.includes('data-connectability-overflow')), 'true'],
                  ['connectability overflow row controls', String(overflowRow.includes('data-connectability-row-window-key')), 'true'],
                  ['connectability overflow row role', String(overflowRow.includes('role="group"')), 'true'],
                  ['connectability overflow row labelledby', String(overflowRow.includes(`aria-labelledby="${overflowRowDomId}-label"`)), 'true'],
                  ['connectability overflow targets aria controls', String(overflowRow.includes(`aria-controls="${overflowRowDomId}-targets"`)), 'true'],
                  ['connectability overflow target id', String(overflowRow.includes(`id="${overflowTargetDomId}"`)), 'true'],
                  ['connectability overflow target position', String(overflowRow.includes('aria-posinset="1"') && overflowRow.includes('aria-setsize="30"')), 'true'],
                  ['connectability overflow target current marker', String(overflowRow.includes('aria-current="false"')), 'true'],
                  ['connectability overflow summary live', String(overflowRow.includes(`id="${overflowWindowSummaryDomId}"`) && overflowRow.includes('aria-live="polite"')), 'true'],
                  ['connectability overflow second first target', overflowSecondWindowTargets[0].target.nodeId, 'overflowReview25'],
                  ['connectability overflow second display count', overflowSecondDisplayWindow.displayed, 6],
                  ['connectability overflow second has previous', overflowSecondDisplayWindow.hasPrevious, true],
                  ['connectability overflow second has next', overflowSecondDisplayWindow.hasNext, false],
                  ['connectability overflow second summary', overflowSecondSummary, 'Showing 25-30 of 30 ready targets'],
                  ['connectability overflow second row previous', String(overflowSecondRow.includes('data-connectability-row-window="prev"')), 'true'],
                  ['connectability overflow second target id', String(overflowSecondRow.includes(`id="${overflowSecondTargetDomId}"`)), 'true'],
                  ['connectability overflow second target position', String(overflowSecondRow.includes('aria-posinset="25"') && overflowSecondRow.includes('aria-setsize="30"')), 'true'],
                  ['connectability overflow filtered targets', overflowFilteredTargets.length, 24],
                  ['connectability overflow filtered summary', overflowFilteredSummary, 'Showing first 24 of 30 matches'],
                  ['connectability overflow filtered second count', overflowFilteredSecondWindow.displayed, 6],
                  ['connectability overflow filtered second summary', overflowFilteredSecondSummary, 'Showing 25-30 of 30 matches'],
                  ['connectability overflow server target first', overflowServerWindowTargets[0].target.nodeId, 'overflowReview30'],
                  ['connectability blocked target title', context.nodeConnectabilityTargetTitle(rootConnectability.blockedTargets[0]), 'Audit (auditNode) · data -> inputs.score · blocked · Type mismatch: object cannot feed integer.'],
                  ['connectability panel includes score chip', String(connectabilityPanel.includes('riskNode.payload.score')), 'true'],
                  ['connectability panel includes blocked chip', String(connectabilityPanel.includes('blocked')), 'true'],
                  ['connectability panel includes blocked reason', String(connectabilityPanel.includes('Type mismatch: object cannot feed integer.')), 'true'],
                  ['connectability panel includes aria label', String(connectabilityPanel.includes('aria-label=')), 'true'],
                  ['connectability panel includes connect action', String(connectabilityPanel.includes('data-connectability-action="connect"')), 'true'],
                  ['connectability panel includes filter query', String(connectabilityPanel.includes('data-connectability-filter-query')), 'true'],
                  ['connectability active filter status', activeConnectabilityFilter.status, 'ready'],
                  ['connectability active filter query', activeConnectabilityFilter.normalizedQuery, 'score'],
                  ['connectability filtered target count', filteredScoreTargets.length, 1],
                  ['connectability filtered target status', context.nodeConnectabilityTargetStatus(filteredScoreTargets[0]), 'ready'],
                  ['connectability filtered target label', context.nodeConnectabilityTargetLabel(filteredScoreTargets[0]), 'Audit (auditNode) · data -> inputs.score · ready'],
                  ['connectability blocked filter count', filteredRootBlockedTargets.length, 3],
                  ['connectability wired target status', context.nodeConnectabilityTargetStatus(rootConnectability.compatibleTargets[0]), 'wired'],
                  ['connectability search text includes reason', String(context.nodeConnectabilityTargetSearchText(rootConnectability.blockedTargets[0]).includes('type mismatch')), 'true'],
                  ['connectability filter summary', context.nodeConnectabilityFilterSummary(connectability, activeConnectabilityFilter), '1/15 matches'],
                  ['connectability filter controls include clear', String(filteredControls.includes('data-connectability-filter-clear')), 'true'],
                  ['connectability filtered panel no matching targets', String(filteredPanel.includes('No matching targets')), 'true'],
                  ['connectability cleared filter inactive', context.nodeConnectabilityFilterIsActive(clearedConnectabilityFilter), false],
                  ['connectability quick source', context.endpointLabel(quickConnectSource), 'riskNode.payload.score'],
                  ['connectability quick target', context.endpointLabel(quickConnectTarget), 'auditNode.inputs.score'],
                  ['connection candidates schema', serverCandidateResult.schemaVersion, 'bloge.visualConnectionCandidates.v1'],
                  ['connection candidates offset', serverCandidateResult.offset, 1],
                  ['connection candidates target keys', Object.keys(serverCandidateResult.candidatesByTargetKey).sort().join('|'), 'data:auditNode:inputs:risk|data:auditNode:inputs:score'],
                  ['connection candidates accepted count', serverCandidateResult.acceptedCount, 1],
                  ['connection candidates rejected count', serverCandidateResult.rejectedCount, 2],
                  ['connection candidates status ready count', serverCandidateResult.statusCounts.ready, 1],
                  ['connection candidates status blocked count', serverCandidateResult.statusCounts.blocked, 2],
                  ['connection candidates status wired count', serverCandidateResult.statusCounts.wired, 0],
                  ['connection candidates facet schema integer', serverCandidateResult.facetCounts.schemaType.integer, 1],
                  ['connection candidates facet schema object', serverCandidateResult.facetCounts.schemaType.object, 1],
                  ['connection candidates facet library', serverCandidateResult.facetCounts.operatorLibraryId['risk-policy'], 2],
                  ['connection candidates facet runtime', serverCandidateResult.facetCounts.runtimeReadiness['design-only'], 2],
                  ['connection candidates facet value schema', serverCandidateResult.candidates[0].facetValues.schemaType, 'integer'],
                  ['connection candidates facet value library', serverCandidateResult.candidates[0].facetValues.operatorLibraryId, 'risk-policy'],
                  ['connection candidates facet filter normalized', serverCandidateResult.facetFilters.schemaType?.[0] || '', ''],
                  ['connection candidates target status', serverCandidateResult.candidates[0].targetStatus, 'ready'],
                  ['connection candidates explanation source type', serverCandidateResult.candidates[0].explanation.sourceSchemaType, 'integer'],
                  ['connection candidates explanation target type', serverCandidateResult.candidates[0].explanation.targetSchemaType, 'integer'],
                  ['connection candidates runtime binding count', serverCandidateResult.candidates[0].summary.runtimeBindingRequirementCount, 1],
                  ['connection candidates runtime binding key', serverCandidateResult.candidates[0].summary.runtimeBindingRequirementKeys[0], 'RUNTIME_BINDING|connection-preview||auditNode|executable-lowering|risk:audit|'],
                  ['connection candidates runtime binding kind count', serverCandidateResult.candidates[0].summary.bindingKindCounts['executable-lowering'], 1],
                  ['connection candidates runtime binding library count', serverCandidateResult.candidates[0].summary.operatorLibraryIdCounts['risk-policy'], 1],
                  ['connection candidates runtime binding summary', context.connectionRuntimeBindingSummary(serverCandidateResult.candidates[0].summary), '1 runtime binding requirement (Executable Lowering: 1) before executable promotion. risk-policy library: 1.'],
                  ['connection candidates target runtime binding count', serverCandidateResult.candidates[0].explanation.targetRuntimeBinding.requirementCount, 1],
                  ['connection candidates target runtime binding library count', serverCandidateResult.candidates[0].explanation.targetRuntimeBinding.operatorLibraryIdCounts['risk-policy'], 1],
                  ['connection candidates target runtime binding summary', context.connectionCandidateTargetRuntimeBindingSummary(serverCandidateResult.candidates[0].explanation.targetRuntimeBinding), '1 target runtime binding requirement (Executable Lowering: 1) before executable promotion. risk-policy library: 1.'],
                  ['connection candidates explanation diagnostic code', serverCandidateResult.candidates[1].explanation.firstDiagnosticCode, 'visual.binding.typeMismatch'],
                  ['connection candidates explanation replacement', serverCandidateResult.candidates[1].explanation.replacementSummary, 'Replaces 1 binding.'],
                  ['connection candidates target port filter', unionCandidateResult.targetPort, 'inputs'],
                  ['connection candidates target path filter', unionCandidateResult.targetPath, 'value'],
                  ['connection candidates union branch metadata', context.unionBranchSelectionValue(unionCandidateResult.targetUnionBranch), 'oneOf:0'],
                  ['connection candidates selected union preview covers', selectedUnionPreviewCovers, true],
                  ['connection candidates wrong union preview rejected', wrongUnionPreviewCovers, false],
                  ['connection candidates broad preview rejects selected union', broadPreviewCoversSelectedUnion, false],
                  ['connection candidates broad request key', broadPreviewRequestKey, 'data:riskNode:payload:score|*'],
                  ['connection candidates union request key', unionPreviewRequestKey, 'data:riskNode:payload:score|data:unionInputNode:inputs:value|oneOf:0|{}'],
                  ['connection candidates union target needs focused preview', unionTargetRequiresFocusedPreview, true],
                  ['connection candidates plain target skips focused preview', plainTargetRequiresFocusedPreview, false],
                  ['canvas target carries selected union branch', context.unionBranchSelectionValue(unionCanvasTarget.targetUnionBranch), 'oneOf:0'],
                  ['server candidate decision source', serverAcceptedDecision.source, 'server'],
                  ['server candidate accepted', serverAcceptedDecision.ok, true],
                  ['server candidate accepted message', serverAcceptedMessage, 'Server schema accepts score.'],
                  ['server connectability ready title includes target runtime binding', context.nodeConnectabilityTargetTitle(serverReadyTarget).includes('1 target runtime binding requirement'), true],
                  ['server connectability ready detail includes schema type', context.nodeConnectabilityTargetDetail(serverReadyTarget).includes('integer -> integer'), true],
                  ['server connectability ready detail includes runtime binding', context.nodeConnectabilityTargetDetail(serverReadyTarget).includes('1 target runtime binding requirement'), true],
                  ['server candidate rejected source', serverRejectedDecision.source, 'server'],
                  ['server candidate rejected', serverRejectedDecision.ok, false],
                  ['server candidate rejected message', serverRejectedMessage, 'Server schema rejects root risk.'],
                  ['server candidate wired status preserved', serverWiredStatus, 'wired'],
                  ['server candidate local fallback source', localFallbackDecision.source, 'local'],
                  ['server candidate local fallback message', localFallbackDecision.message, 'Type mismatch: integer cannot feed boolean.'],
                  ['server connectability source count', serverConnectability.sourceCount, 3],
                  ['server connectability score available', serverScoreConnectability.availableCount, 3],
                  ['server connectability score blocked', serverScoreConnectability.blockedCount, 2],
                  ['server connectability ready source', serverReadyTarget.decisionSource, 'server'],
                  ['server connectability blocked source', serverRiskTarget.decisionSource, 'server'],
                  ['server connectability blocked title', context.nodeConnectabilityTargetTitle(serverRiskTarget), 'Audit (auditNode) · data -> inputs.risk · blocked · Server schema rejects root risk. · integer -> object · Replaces 1 binding.'],
                  ['server connectability blocked detail', context.nodeConnectabilityTargetDetail(serverRiskTarget), 'integer -> object · Replaces 1 binding. · Server schema rejects root risk.'],
                  ['server connectability panel synced', String(serverConnectabilityPanel.includes('Server candidates synced')), 'true'],
                  ['server connectability panel includes visible detail class', String(serverConnectabilityPanel.includes('node-connectability-chip-detail')), 'true'],
                  ['server connectability panel includes visible schema detail', String(serverConnectabilityPanel.includes('integer -&gt; integer')), 'true'],
                  ['server connectability panel includes visible runtime debt', String(serverConnectabilityPanel.includes('1 target runtime binding requirement')), 'true'],
                  ['server connectability facet definition count', context.nodeConnectabilityFacetDefinitions().length, 7],
                  ['server connectability facet summary limit', context.nodeConnectabilityFacetSummaryLimit(), 5],
                  ['server connectability facet summary', serverFacetSummary, 'Facets · schema integer 1 +1 · runtime design-only 2 · library risk-policy 2 · operator risk:audit 2 · source user-library 2'],
                  ['server connectability panel includes facet summary', String(serverConnectabilityPanel.includes('Facets')), 'true'],
                  ['server connectability active facet key', facetFilterKey, 'loweringMode=design|operatorLibraryId=risk-policy|operatorRef=risk:audit|runtimeReadiness=design-only|schemaType=integer|sourceKind=user-library|surface=input'],
                  ['server connectability facet request key differs', String(pageOneRequestKey !== facetRequestKey), 'true'],
                  ['server connectability source filter key', scoreSourceKey, 'data:riskNode:payload:score'],
                  ['server connectability source options', context.nodeConnectabilitySourceFilterOptions(serverConnectability).map((entry) => entry.label).join('|'), 'riskNode.payload|riskNode.payload.score|riskNode.payload.eligible'],
                  ['server connectability source display count', sourceFilteredSources.length, 1],
                  ['server connectability source display label', context.endpointLabel(sourceFilteredSources[0].source), 'riskNode.payload.score'],
                  ['server connectability source filter summary', sourceFilterSummary, '5/5 matches'],
                  ['server connectability source controls include endpoint', String(sourceFilterControls.includes('data-connectability-filter-source')), 'true'],
                  ['server connectability source controls selected score', String(sourceFilterControls.includes('value="' + scoreSourceKey + '" selected')), 'true'],
                  ['server connectability source row hides eligible', String(!sourceFilteredPanel.includes('riskNode.payload.eligible')), 'true'],
                  ['server connectability source row keeps score', String(sourceFilteredPanel.includes('riskNode.payload.score')), 'true'],
                  ['server connectability source request key differs', String(pageOneRequestKey !== sourceRequestKey), 'true'],
                  ['server connectability source window limit', context.nodeConnectabilitySourceWindowLimit(), 8],
                  ['server connectability source window display count', manySourceDisplaySources.length, 8],
                  ['server connectability source window total', manySourceWindow.total, 12],
                  ['server connectability source window first label', context.endpointLabel(manySourceDisplaySources[0].source), 'riskNode.payload.metric1'],
                  ['server connectability source window last first-page label', context.endpointLabel(manySourceDisplaySources[7].source), 'riskNode.payload.metric8'],
                  ['server connectability source window summary', manySourceWindowSummary, 'Showing first 8 of 12 source endpoints'],
                  ['server connectability source window controls next', String(manySourceWindowControls.includes('data-connectability-source-window="next"')), 'true'],
                  ['server connectability source window aria group', String(manySourceWindowControls.includes('role="group"') && manySourceWindowControls.includes('aria-describedby')), 'true'],
                  ['server connectability source window live summary', String(manySourceWindowControls.includes('aria-live="polite"')), 'true'],
                  ['server connectability source request scope first-page', manySourceRequestScope.split('|').length, 8],
                  ['server connectability source request scope includes metric8', String(manySourceRequestScope.includes('metric8')), 'true'],
                  ['server connectability source request scope excludes metric9', String(!manySourceRequestScope.includes('metric9')), 'true'],
                  ['server connectability source scoped request key differs', String(manySourceScopedRequestKey !== manySourceUnscopedRequestKey), 'true'],
                  ['server connectability source second window count', manySourceSecondWindow.displayed, 4],
                  ['server connectability source second window first label', context.endpointLabel(manySourceSecondWindow.sources[0].source), 'riskNode.payload.metric9'],
                  ['server connectability source second window summary', manySourceSecondSummary, 'Showing 9-12 of 12 source endpoints'],
                  ['server connectability source filter bypasses source window', manySourceSpecificWindow.displayed, 1],
                  ['server connectability source filter selected metric', context.endpointLabel(manySourceSpecificWindow.sources[0].source), 'riskNode.payload.metric10'],
                  ['server connectability facet controls include schema', String(facetFilterControls.includes('data-connectability-filter-facet="schemaType"')), 'true'],
                  ['server connectability facet controls include operator', String(facetFilterControls.includes('data-connectability-filter-facet="operatorRef"')), 'true'],
                  ['server connectability facet controls include source', String(facetFilterControls.includes('data-connectability-filter-facet="sourceKind"')), 'true'],
                  ['server connectability facet controls include lowering', String(facetFilterControls.includes('data-connectability-filter-facet="loweringMode"')), 'true'],
                  ['server connectability facet controls include surface', String(facetFilterControls.includes('data-connectability-filter-facet="surface"')), 'true'],
                  ['server connectability facet controls selected schema', String(facetFilterControls.includes('value="integer" selected')), 'true'],
                  ['server connectability facet controls selected operator', String(facetFilterControls.includes('value="risk:audit" selected')), 'true'],
                  ['server connectability facet filtered count', facetFilteredScoreTargets.length, 1],
                  ['server connectability facet filtered target', context.nodeConnectabilityTargetLabel(facetFilteredScoreTargets[0]), 'Audit (auditNode) · data -> inputs.score · ready'],
                  ['server connectability truncated status includes partial window', String(truncatedServerStatus.includes('partial server window 1-250 of 300')), 'true'],
                  ['server connectability truncated status warns local fallback', String(truncatedServerStatus.includes('local fallback beyond server window')), 'true'],
                  ['server connectability truncated status includes facet summary', String(truncatedServerStatus.includes('schema integer 300')), 'true'],
                  ['server connectability page request keys differ', String(pageOneRequestKey !== pageTwoRequestKey), 'true'],
                  ['server connectability page one has next', pageOneStats.hasNext, true],
                  ['server connectability page one has no previous', pageOneStats.hasPrevious, false],
                  ['server connectability page two has previous', pageTwoStats.hasPrevious, true],
                  ['server connectability page two has no next', pageTwoStats.hasNext, false],
                  ['server connectability page one controls next', String(pageOneControls.includes('data-connectability-window="next"')), 'true'],
                  ['server connectability page two controls window label', String(pageTwoControls.includes('Window 251-300 of 300')), 'true'],
                  ['server connectability status request key differs', String(pageOneRequestKey !== pageOneReadyStatusRequestKey), 'true'],
                  ['server connectability status facet request key differs', String(pageOneReadyStatusRequestKey !== pageOneReadySchemaFacetRequestKey), 'true'],
                  ['server connectability active status', activeServerStatus, 'ready'],
                  ['server connectability active source', activeServerSourceKey, ''],
                  ['server connectability active facet filters', context.nodeConnectabilityServerFacetFiltersKey(activeServerFacetFilters), 'schemaType=integer'],
                  ['server connectability next page request offsets', connectabilityFetchOffsets, ':250:250:true:ready:schemaType=integer|eligible:250:250:true:ready:schemaType=integer|score:250:250:true:ready:schemaType=integer'],
                  ['server connectability source filtered fetch offsets', sourceFilteredFetchOffsets, 'score:250:250:true:ready:schemaType=integer'],
                  ['server connectability source window fetch paths', manySourceWindowFetchPaths, 'metric1|metric2|metric3|metric4|metric5|metric6|metric7|metric8'],
                  ['server connectability source second window fetch paths', manySourceSecondWindowFetchPaths, 'metric10|metric11|metric12|metric9'],
                  ['auto bind required unbound count', autoBindPlan.requiredUnboundCount, 2],
                  ['auto bind item count', autoBindPlan.items.length, 1],
                  ['auto bind skipped count', autoBindPlan.skippedCount, 1],
                  ['auto bind summary', context.requiredInputAutoBindSummary(autoBindPlan), '1 required ready · 1 ambiguous'],
                  ['auto bind source label', context.endpointLabel(autoBindPlan.items[0].source), 'riskNode.payload.score'],
                  ['auto bind target label', context.endpointLabel(autoBindPlan.items[0].target), 'auditNode.inputs.score'],
                  ['auto bind button label', String(autoBindButtonHtml.includes('Auto Bind 1')), 'true'],
                  ['impact clear input existed before', clearInputExistsBefore, true],
                  ['impact clear input value after', clearInputValueAfter, ''],
                  ['impact clear input missing after', clearInputExistsAfter, false],
                  ['impact clear config existed before', clearConfigExistsBefore, true],
                  ['impact clear config relation missing after', clearConfigExistsAfter, false],
                  ['impact clear config path removed', clearConfigPathAfter, false],
                  ['impact clear dependency removed', clearDependencyAfter, false],
                  ['impact clear route removed', clearRouteAfter, false],
                  ['impact clear all action count', clearAllImpactActionCount, 7],
                  ['impact clear all cleared', clearAllImpactCleared, 7],
                  ['impact clear all remaining', clearAllImpactRemaining, 0],
                  ['impact clear all input value', clearAllRiskScore, ''],
                  ['impact clear all config removed', clearAllRiskThresholdExists, false],
                  ['impact clear all downstream input value', clearAllAuditRisk, ''],
                  ['impact clear all dependencies removed', clearAllDependencyAfter, false],
                  ['impact clear all routes removed', clearAllRouteAfter, false],
                  ['impact clear all output removed', clearAllOutputAfter, ''],
                  ['impact detach button output reassigned', detachButtonOutputNode, 'auditNode'],
                  ['impact detach button remaining', detachButtonRemaining, 0],
                  ['impact detach button render counts', detachButtonRenderCounts, '1|1'],
                  ['impact detach button message', detachButtonMessage, 'Detached 7 impact relations for riskNode.'],
                  ['delete selected removed policy', deletePolicyNodeStillPresent, false],
                  ['delete selected fallback input', deletePolicyRiskScore, 'ctx.score'],
                  ['delete selected config reference removed', deletePolicyThresholdExists, false],
                  ['delete selected impact edges removed', deletePolicyEdgesRemain, false],
                  ['delete selected undo snapshot retained node', deletePolicyUndoSnapshotHasPolicy, true],
                  ['delete selected render counts', deletePolicyRenderCounts, '1|1'],
                  ['delete selected impact message', deleteConnectionMessage, 'Deleted policy; cleaned 3 impact relations.'],
                  ['dynamic schema DSL targets', dynamicSchemaDslTargets, [
                    '/inputSchema/schema/properties/dynamicAdditional/additionalProperties/properties/bad-field',
                    '/inputSchema/schema/properties/dynamicResidual/unevaluatedProperties/properties/bad-residual-field',
                    '/inputSchema/schema/properties/patterned/patternProperties/^item\\\\.[a-z]+$/properties/bad-pattern-field'
                  ].sort().join('|')]
                ];

                for (const [label, actual, expected] of checks) {
                  if (actual !== expected) {
                    throw new Error(`${label}: expected ${expected}, got ${actual}`);
                  }
                }
                let rebaseFetchUrl = '';
                let rebaseFetchBody = '';
                let rebaseDraftMessage = '';
                let rebaseDraftMessageLevel = '';
                let rebaseDraftListCalls = 0;
                let rebaseRevisionCalls = 0;
                let rebaseDependencyCalls = 0;
                let rebaseDependencyRenders = 0;
                let rebaseUsageRef = '';
                let rebaseDraftControlRenders = 0;
                let rebaseEditorRenders = 0;
                let rebaseDiagramRenders = 0;
                """, """
                quickConnectPromise.then(() => {
                  const asyncChecks = [
                    ['connectability quick server call', quickConnectServerCall, 'riskNode.payload.score -> auditNode.inputs.score'],
                    ['connectability quick applied', quickConnectApplied, 'riskNode.payload.score -> auditNode.inputs.score:inputs.score'],
                    ['connectability quick message level', quickConnectMessageLevel, 'success'],
                    ['connectability quick message', quickConnectMessage, 'Connected riskNode.payload.score -> auditNode.inputs.score.'],
                    ['connectability quick editor render', quickConnectEditorRenders, 1],
                    ['connectability quick diagram render', quickConnectDiagramRenders, 1],
                    ['connectability quick button enabled', quickConnectButton.disabled, false]
                  ];
                  for (const [label, actual, expected] of asyncChecks) {
                    if (actual !== expected) {
                      throw new Error(`${label}: expected ${expected}, got ${actual}`);
                    }
                  }
                  context.checkVisualConnectionOnServer = async (source, target) => {
                    autoBindServerCall = `${context.endpointLabel(source)} -> ${context.endpointLabel(target)}`;
                    return { accepted: true, bindingKey: 'inputs.score', diagnostics: [], message: '' };
                  };
                  context.applyConnection = (source, target) => {
                    autoBindApplied = `${context.endpointLabel(source)} -> ${context.endpointLabel(target)}:${target.key || ''}`;
                    context.state.builder.nodes.find((node) => node.id === target.nodeId).customInputs[target.key || target.path] =
                      context.expressionForConnectionSource(source);
                  };
                  context.setConnectionMessage = (text, level) => {
                    autoBindMessage = text;
                    autoBindMessageLevel = level;
                  };
                  context.renderSelectedOperatorEditor = () => {
                    autoBindEditorRenders += 1;
                  };
                  context.renderDiagram = () => {
                    autoBindDiagramRenders += 1;
                  };
                  return context.autoBindRequiredInputsFromButton(autoBindButton);
                }).then(() => {
                  const autoChecks = [
                    ['auto bind server call', autoBindServerCall, 'riskNode.payload.score -> auditNode.inputs.score'],
                    ['auto bind applied', autoBindApplied, 'riskNode.payload.score -> auditNode.inputs.score:inputs.score'],
                    ['auto bind custom input value', context.state.builder.nodes.find((node) => node.id === 'auditNode').customInputs['inputs.score'], 'riskNode.output.payload.score'],
                    ['auto bind message level', autoBindMessageLevel, 'success'],
                    ['auto bind message', autoBindMessage, 'Auto-bound 1 required input.'],
                    ['auto bind editor render', autoBindEditorRenders, 1],
                    ['auto bind diagram render', autoBindDiagramRenders, 1],
                    ['auto bind button enabled', autoBindButton.disabled, false]
                  ];
                  for (const [label, actual, expected] of autoChecks) {
                    if (actual !== expected) {
                      throw new Error(`${label}: expected ${expected}, got ${actual}`);
                    }
                  }
                  context.state.savedDraftSnapshot = {
                    ...context.builderToVisualDraft(context.state.builder),
                    draftId: 'draft-risk',
                    revision: context.state.currentDraftRevision
                  };
                  context.state.builderHistoryUndo = [];
                  context.state.builderHistoryRedo = [];
                  rebaseFetchUrl = '';
                  rebaseFetchBody = '';
                  rebaseDraftMessage = '';
                  rebaseDraftMessageLevel = '';
                  rebaseDraftListCalls = 0;
                  rebaseRevisionCalls = 0;
                  rebaseDependencyCalls = 0;
                  rebaseDependencyRenders = 0;
                  rebaseUsageRef = '';
                  rebaseDraftControlRenders = 0;
                  rebaseEditorRenders = 0;
                  rebaseDiagramRenders = 0;
                  context.fetch = async (url, options = {}) => {
                    rebaseFetchUrl = url;
                    rebaseFetchBody = options.body || '';
                    return {
                      ok: true,
                      status: 200,
                      json: async () => ({
                        patched: true,
                        draft: {
                          draftId: 'draft-risk',
                          revision: 4,
                          operatorFingerprints: {
                            policy: 'policy-current-fingerprint',
                            riskNode: 'current-fingerprint-123456',
                            auditNode: 'audit-current-fingerprint'
                          }
                        }
                      })
                    };
                  };
                  context.setDraftMessage = (text, level) => {
                    rebaseDraftMessage = text;
                    rebaseDraftMessageLevel = level;
                  };
                  context.loadDraftList = async () => {
                    rebaseDraftListCalls += 1;
                    return [];
                  };
                  context.loadDraftRevisions = async () => {
                    rebaseRevisionCalls += 1;
                    return [];
                  };
                  context.loadDraftDependencies = async () => {
                    rebaseDependencyCalls += 1;
                    return { draftId: 'draft-risk', revision: context.state.currentDraftRevision };
                  };
                  context.renderDraftDependencyReport = () => {
                    rebaseDependencyRenders += 1;
                  };
                  context.renderDraftControls = () => {
                    rebaseDraftControlRenders += 1;
                  };
                  context.loadOperatorUsage = async (operatorRef) => {
                    rebaseUsageRef = operatorRef;
                  };
                  context.renderSelectedOperatorEditor = () => {
                    rebaseEditorRenders += 1;
                  };
                  context.renderDiagram = () => {
                    rebaseDiagramRenders += 1;
                  };
                  return context.rebaseOperatorFingerprint('riskNode').then((rebasedDraft) => ({
                    rebasedDraft,
                    rebaseFetchUrl,
                    rebaseFetchBody,
                    rebaseDraftMessage,
                    rebaseDraftMessageLevel,
                    rebaseDraftListCalls,
                    rebaseRevisionCalls,
                    rebaseDependencyCalls,
                    rebaseDependencyRenders,
                    rebaseUsageRef,
                    rebaseDraftControlRenders,
                    rebaseEditorRenders,
                    rebaseDiagramRenders
                  }));
                }).then((rebaseResult) => {
                  const rebaseBody = JSON.parse(rebaseResult.rebaseFetchBody);
                  const rebaseChecks = [
                    ['rebase endpoint', rebaseResult.rebaseFetchUrl, '/api/visual/drafts/draft-risk/operator-fingerprints/rebase'],
                    ['rebase expected revision', rebaseBody.expectedRevision, 3],
                    ['rebase node id', rebaseBody.nodeIds.join('|'), 'riskNode'],
                    ['rebase actor', rebaseBody.actor, 'visual-canvas'],
                    ['rebase change source', rebaseBody.changeSource, 'gateway-browser'],
                    ['rebase change summary', rebaseBody.changeSummary, 'Rebased operator fingerprint snapshot for riskNode.'],
                    ['rebase reason', rebaseBody.reason, 'User reviewed operator drift before rebasing the saved fingerprint snapshot in the browser.'],
                    ['rebase returned revision', rebaseResult.rebasedDraft.revision, 4],
                    ['rebase state revision', context.state.currentDraftRevision, 4],
                    ['rebase state fingerprint', context.state.builder.operatorFingerprints.riskNode, 'current-fingerprint-123456'],
                    ['rebase message level', rebaseResult.rebaseDraftMessageLevel, 'success'],
                    ['rebase message text', rebaseResult.rebaseDraftMessage, 'Rebased riskNode operator fingerprint at draft-risk@4.'],
                    ['rebase draft list calls', rebaseResult.rebaseDraftListCalls, 1],
                    ['rebase revision calls', rebaseResult.rebaseRevisionCalls, 1],
                    ['rebase dependency calls', rebaseResult.rebaseDependencyCalls, 1],
                    ['rebase dependency renders', rebaseResult.rebaseDependencyRenders, 2],
                    ['rebase usage ref', rebaseResult.rebaseUsageRef, 'risk:eligibility'],
                    ['rebase draft controls rendered', rebaseResult.rebaseDraftControlRenders, 2],
                    ['rebase editor renders', rebaseResult.rebaseEditorRenders, 2],
                    ['rebase diagram renders', rebaseResult.rebaseDiagramRenders, 1],
                    ['rebase loading cleared', context.state.operatorFingerprintRebaseNodeId, '']
                  ];
                  for (const [label, actual, expected] of rebaseChecks) {
                    if (actual !== expected) {
                      throw new Error(`${label}: expected ${expected}, got ${actual}`);
                    }
                  }
                  rebaseFetchUrl = '';
                  rebaseFetchBody = '';
                  rebaseDraftMessage = '';
                  rebaseDraftMessageLevel = '';
                  rebaseDraftListCalls = 0;
                  rebaseRevisionCalls = 0;
                  rebaseDependencyCalls = 0;
                  rebaseDependencyRenders = 0;
                  rebaseUsageRef = '';
                  rebaseDraftControlRenders = 0;
                  rebaseEditorRenders = 0;
                  rebaseDiagramRenders = 0;
                  context.state.builder.operatorFingerprints.riskNode = 'stale-fingerprint-before-conflict';
                  context.fetch = async (url, options = {}) => {
                    rebaseFetchUrl = url;
                    rebaseFetchBody = options.body || '';
                    return {
                      ok: false,
                      status: 409,
                      json: async () => ({
                        patched: false,
                        draft: {
                          draftId: 'draft-risk',
                          revision: 5,
                          graphName: 'serverAdvancedPolicy',
                          operatorFingerprints: {
                            riskNode: 'server-still-drifted-fingerprint'
                          }
                        },
                        diagnostics: [{
                          level: 'ERROR',
                          code: 'visual.draft.revisionConflict',
                          message: 'Draft revision conflict: expected 4 but current revision is 5.',
                          target: '/expectedRevision'
                        }]
                      })
                    };
                  };
                  return context.rebaseOperatorFingerprint('riskNode').then((conflictDraft) => ({
                    conflictDraft,
                    rebaseFetchUrl,
                    rebaseFetchBody,
                    rebaseDraftMessage,
                    rebaseDraftMessageLevel,
                    rebaseDraftListCalls,
                    rebaseRevisionCalls,
                    rebaseDependencyCalls,
                    rebaseDependencyRenders,
                    rebaseUsageRef,
                    rebaseDraftControlRenders,
                    rebaseEditorRenders,
                    rebaseDiagramRenders,
                    currentDraftRevision: context.state.currentDraftRevision,
                    savedDraftRevision: context.state.savedDraftSnapshot.revision,
                    builderGraphName: context.state.builder.graphName,
                    loadingNodeId: context.state.operatorFingerprintRebaseNodeId
                  }));
                }).then((rebaseConflict) => {
                  const conflictBody = JSON.parse(rebaseConflict.rebaseFetchBody);
                  const conflictChecks = [
                    ['rebase conflict endpoint', rebaseConflict.rebaseFetchUrl, '/api/visual/drafts/draft-risk/operator-fingerprints/rebase'],
                    ['rebase conflict expected revision', conflictBody.expectedRevision, 4],
                    ['rebase conflict result', rebaseConflict.conflictDraft, null],
                    ['rebase conflict state revision', rebaseConflict.currentDraftRevision, 5],
                    ['rebase conflict saved revision', rebaseConflict.savedDraftRevision, 5],
                    ['rebase conflict builder graph', rebaseConflict.builderGraphName, 'serverAdvancedPolicy'],
                    ['rebase conflict message level', rebaseConflict.rebaseDraftMessageLevel, 'error'],
                    ['rebase conflict message text', rebaseConflict.rebaseDraftMessage, 'Draft revision conflict: expected 4 but current revision is 5. Review the latest draft dependencies before rebasing.'],
                    ['rebase conflict draft list calls', rebaseConflict.rebaseDraftListCalls, 1],
                    ['rebase conflict revision calls', rebaseConflict.rebaseRevisionCalls, 1],
                    ['rebase conflict dependency calls', rebaseConflict.rebaseDependencyCalls, 1],
                    ['rebase conflict dependency renders', rebaseConflict.rebaseDependencyRenders, 3],
                    ['rebase conflict usage ref', rebaseConflict.rebaseUsageRef, ''],
                    ['rebase conflict draft controls rendered', rebaseConflict.rebaseDraftControlRenders, 3],
                    ['rebase conflict editor renders', rebaseConflict.rebaseEditorRenders, 2],
                    ['rebase conflict diagram renders', rebaseConflict.rebaseDiagramRenders, 1],
                    ['rebase conflict loading cleared', rebaseConflict.loadingNodeId, '']
                  ];
                  for (const [label, actual, expected] of conflictChecks) {
                    if (actual !== expected) {
                      throw new Error(`${label}: expected ${expected}, got ${actual}`);
                    }
                  }
                  """, """
                  const transferReadiness = {
                    schemaVersion: 'bloge.visualGraphReadiness.v1',
                    state: 'DESIGN_ONLY',
                    level: 'INFO',
                    executable: false,
                    artifactKinds: ['DESIGN'],
                    title: 'Design-only graph',
                    summary: 'Imported as a design artifact.',
                    nodeCount: 1,
                    runtimeExecutableNodeCount: 0,
                    designOnlyNodeCount: 1,
                    runtimeBlockedNodeCount: 0,
                    governanceReviewNodeCount: 0,
                    draftRepairNodeCount: 0,
                    runtimeBindingRequirementCount: 1,
                    runtimeBindingRequirements: [{
                      nodeId: 'riskNode',
                      operatorRef: 'risk:eligibility',
                      state: 'DESIGN_ONLY',
                      level: 'INFO',
                      sourceKind: 'USER_LIBRARY',
                      loweringMode: 'DESIGN',
                      bindingKind: 'EXECUTABLE_LOWERING',
                      bindingTarget: 'risk:eligibility',
                      handoffLane: 'OPERATOR_PLATFORM',
                      handoffKind: 'OPERATOR_IMPLEMENTATION',
                      handoffTarget: 'risk:eligibility',
                      title: 'Executable lowering required',
                      summary: 'No executable lowering is bound.',
                      recommendedAction: 'Bind a runtime implementation.'
                    }],
                    nodes: [{
                      nodeId: 'riskNode',
                      operatorRef: 'risk:eligibility',
                      state: 'DESIGN_ONLY',
                      level: 'INFO',
                      executable: false,
                      title: 'Design-only operator',
                      summary: 'No runtime lowering yet.',
                      diagnosticCount: 0,
                      errorCount: 0,
                      warningCount: 0
                    }]
                  };
                  const transferDraft = {
                    schemaVersion: 'bloge.visualGraphDraft.v1',
                    draftId: 'draft-imported',
                    revision: 1,
                    graphName: 'importedGraph',
                    tenantId: 'demo-tenant',
                    namespace: 'local',
                    environment: 'browser',
                    status: 'DRAFT',
                    inputSchema: { schemaVersion: 'json-schema-draft-2020-12', schema: { type: 'object' } },
                    nodes: [],
                    edges: [],
                    output: { nodeId: '', path: '' },
                    visualLayout: { schemaVersion: 'bloge.visualLayout.v1', rootId: 'importedGraph', nodes: [], edges: [] },
                    operatorFingerprints: {},
                    operatorSnapshots: {}
                  };
                  const transferPreviewDraft = { ...transferDraft, draftId: 'draft-risk', revision: 4 };
                  const transferFetches = [];
                  const transferDraftMessages = [];
                  const transferVisualChecks = [];
                  let transferDraftControlRenders = 0;
                  let transferDraftListLoads = 0;
                  let transferRevisionLoads = 0;
                  let transferCatalogLoads = 0;
                  let transferScenarioRenders = 0;
                  context.state.currentDraftId = 'draft-risk';
                  context.state.currentDraftRevision = 4;
                  context.state.draftBundleText = '';
                  context.fetch = async (url, options = {}) => {
                    transferFetches.push({ url, method: options.method || 'GET', body: options.body || '' });
                    if (url === '/api/visual/drafts/draft-risk/export') {
                      return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                          schemaVersion: 'bloge.visualGraphDraftExport.v1',
                          bundleFingerprint: 'sha256:draft-transfer',
                          sourceDraftId: 'draft-risk',
                          sourceRevision: 4,
                          draft: transferDraft,
                          operatorSnapshots: [],
                          diagnostics: [],
                          validation: {
                            valid: true,
                            diagnostics: [],
                            readiness: transferReadiness
                          },
                          dependencyReport: {
                            schemaVersion: 'bloge.visualGraphDraftDependencies.v1',
                            draftId: 'draft-imported',
                            revision: 1,
                            graphName: 'importedGraph',
                            tenantId: 'demo-tenant',
                            namespace: 'local',
                            environment: 'browser',
                            nodeCount: 0,
                            edgeCount: 0,
                            operatorDependencyCount: 0,
                            missingOperatorCount: 0,
                            scopeMismatchOperatorCount: 0,
                            driftedFingerprintCount: 0,
                            missingFingerprintCount: 0,
                            sourceKindCounts: {},
                            loweringModeCounts: {},
                            runtimeReadinessStateCounts: {},
                            operators: [],
                            nodes: []
                          }
                        })
                      };
                    }
                    if (url === '/api/visual/drafts/validate-bundle') {
                      return {
                        ok: true,
                        status: 200,
                        json: async () => ({
                          schemaVersion: 'bloge.visualGraphDraftImportResult.v1',
                          imported: false,
                          sourceBundleSchemaVersion: 'bloge.visualGraphDraftExport.v1',
                          sourceBundleFingerprint: 'sha256:draft-transfer',
                          sourceDraftId: 'draft-risk',
                          sourceRevision: 4,
                          draft: transferPreviewDraft,
                          diagnostics: [],
                          validation: {
                            valid: true,
                            diagnostics: [],
                            readiness: transferReadiness
                          },
                          targetRuntimeBindingRequirements: transferReadiness.runtimeBindingRequirements,
                          targetDependencyReport: {
                            schemaVersion: 'bloge.visualGraphDraftDependencies.v1',
                            draftId: 'draft-risk',
                            revision: 4,
                            graphName: 'importedGraph',
                            tenantId: 'demo-tenant',
                            namespace: 'local',
                            environment: 'browser',
                            nodeCount: 0,
                            edgeCount: 0,
                            operatorDependencyCount: 0,
                            missingOperatorCount: 0,
                            scopeMismatchOperatorCount: 0,
                            driftedFingerprintCount: 0,
                            missingFingerprintCount: 0,
                            sourceKindCounts: {},
                            loweringModeCounts: {},
                            runtimeReadinessStateCounts: {},
                            operators: [],
                            nodes: []
                          },
                          dependencyReport: {
                            schemaVersion: 'bloge.visualGraphDraftDependencies.v1',
                            draftId: 'draft-risk',
                            revision: 4,
                            graphName: 'importedGraph',
                            tenantId: 'demo-tenant',
                            namespace: 'local',
                            environment: 'browser',
                            nodeCount: 0,
                            edgeCount: 0,
                            operatorDependencyCount: 0,
                            missingOperatorCount: 0,
                            scopeMismatchOperatorCount: 0,
                            driftedFingerprintCount: 0,
                            missingFingerprintCount: 0,
                            sourceKindCounts: {},
                            loweringModeCounts: {},
                            runtimeReadinessStateCounts: {},
                            operators: [],
                            nodes: []
                          }
                        })
                      };
                    }
                    if (String(url).startsWith('/api/visual/drafts/import')) {
                      return {
                        ok: true,
                        status: 201,
                        json: async () => ({
                          schemaVersion: 'bloge.visualGraphDraftImportResult.v1',
                          imported: true,
                          sourceBundleSchemaVersion: 'bloge.visualGraphDraftExport.v1',
                          sourceBundleFingerprint: 'sha256:draft-transfer',
                          sourceDraftId: 'draft-risk',
                          sourceRevision: 4,
                          draft: transferDraft,
                          diagnostics: [],
                          validation: {
                            valid: true,
                            diagnostics: [],
                            readiness: transferReadiness
                          },
                          targetRuntimeBindingRequirements: transferReadiness.runtimeBindingRequirements,
                          sourceDependencyReport: {
                            schemaVersion: 'bloge.visualGraphDraftDependencies.v1',
                            draftId: 'draft-risk',
                            revision: 4,
                            graphName: 'importedGraph',
                            tenantId: 'demo-tenant',
                            namespace: 'local',
                            environment: 'browser',
                            nodeCount: 0,
                            edgeCount: 0,
                            operatorDependencyCount: 0,
                            missingOperatorCount: 0,
                            scopeMismatchOperatorCount: 0,
                            driftedFingerprintCount: 0,
                            missingFingerprintCount: 0,
                            sourceKindCounts: {},
                            loweringModeCounts: {},
                            runtimeReadinessStateCounts: {},
                            operators: [],
                            nodes: []
                          },
                          targetDependencyReport: {
                            schemaVersion: 'bloge.visualGraphDraftDependencies.v1',
                            draftId: 'draft-imported',
                            revision: 1,
                            graphName: 'importedGraph',
                            tenantId: 'demo-tenant',
                            namespace: 'local',
                            environment: 'browser',
                            nodeCount: 0,
                            edgeCount: 0,
                            operatorDependencyCount: 0,
                            missingOperatorCount: 0,
                            scopeMismatchOperatorCount: 0,
                            driftedFingerprintCount: 0,
                            missingFingerprintCount: 0,
                            sourceKindCounts: {},
                            loweringModeCounts: {},
                            runtimeReadinessStateCounts: {},
                            operators: [],
                            nodes: []
                          },
                          dependencyReport: {
                            schemaVersion: 'bloge.visualGraphDraftDependencies.v1',
                            draftId: 'draft-imported',
                            revision: 1,
                            graphName: 'importedGraph',
                            tenantId: 'demo-tenant',
                            namespace: 'local',
                            environment: 'browser',
                            nodeCount: 0,
                            edgeCount: 0,
                            operatorDependencyCount: 0,
                            missingOperatorCount: 0,
                            scopeMismatchOperatorCount: 0,
                            driftedFingerprintCount: 0,
                            missingFingerprintCount: 0,
                            sourceKindCounts: {},
                            loweringModeCounts: {},
                            runtimeReadinessStateCounts: {},
                            operators: [],
                            nodes: []
                          }
                        })
                      };
                    }
                    throw new Error(`unexpected transfer fetch ${url}`);
                  };
                  context.$ = (id) => {
                    context.elements[id] = context.elements[id] || { textContent: '', value: '' };
                    return context.elements[id];
                  };
                  context.setDraftMessage = (text, level) => {
                    transferDraftMessages.push({ text, level });
                  };
                  context.setVisualCheck = (message, level, diagnostics = [], readiness = null) => {
                    const normalized = context.normalizeVisualGraphReadiness(readiness);
                    context.state.visualCheck = { message, level, diagnostics, readiness: normalized };
                    transferVisualChecks.push({ message, level, readiness: normalized });
                  };
                  context.renderDraftControls = () => {
                    transferDraftControlRenders += 1;
                  };
                  context.clearBuilderHistory = () => {};
                  context.loadVisualOperatorCatalog = async () => {
                    transferCatalogLoads += 1;
                    return [];
                  };
                  context.loadDraftList = async () => {
                    transferDraftListLoads += 1;
                    return [];
                  };
                  context.loadDraftRevisions = async () => {
                    transferRevisionLoads += 1;
                    return [];
                  };
                  context.loadVisualAssetOverview = async () => {};
                  context.syncGraphInputSchemaTextFromBuilder = () => {};
                  context.syncComposerFromBuilder = () => {};
                  context.renderScenario = () => {
                    transferScenarioRenders += 1;
                  };
                  return context.exportSelectedDraft()
                    .then(() => context.validateDraftBundle())
                    .then(() => {
                      const afterValidate = {
                        currentDraftId: context.state.currentDraftId,
                        currentDraftRevision: context.state.currentDraftRevision,
                        dependencyReportDraftId: context.state.draftDependencyReport?.draftId || ''
                      };
                      return context.importDraftBundle().then(() => afterValidate);
                    })
                    .then((afterValidate) => ({
                      afterValidate,
                      transferFetches,
                      transferDraftMessages,
                      transferVisualChecks,
                      transferDraftControlRenders,
                      transferDraftListLoads,
                      transferRevisionLoads,
                      transferCatalogLoads,
                      transferScenarioRenders,
                      currentDraftId: context.state.currentDraftId,
                      currentDraftRevision: context.state.currentDraftRevision,
                      dependencyReportDraftId: context.state.draftDependencyReport?.draftId || '',
                      draftBundleHasValidation: context.state.draftBundleText.includes('"validation"'),
                      draftBundleHasDependencyReport: context.state.draftBundleText.includes('"dependencyReport"')
                    }));
                }).then((transferResult) => {
                  const validateBody = JSON.parse(transferResult.transferFetches[1].body || '{}');
                  const importBody = JSON.parse(transferResult.transferFetches[2].body || '{}');
                  const importUrl = transferResult.transferFetches[2].url;
                  const importQuery = new URLSearchParams(importUrl.split('?')[1] || '');
                  const exportVisualCheck = transferResult.transferVisualChecks[0] || {};
                  const validateVisualCheck = transferResult.transferVisualChecks[1] || {};
                  const importVisualCheck = transferResult.transferVisualChecks[2] || {};
                  const transferChecks = [
                    ['draft export endpoint', transferResult.transferFetches[0].url, '/api/visual/drafts/draft-risk/export'],
                    ['draft validate endpoint', transferResult.transferFetches[1].url, '/api/visual/drafts/validate-bundle'],
                    ['draft import endpoint', String(importUrl.startsWith('/api/visual/drafts/import?')), 'true'],
                    ['draft import actor', importQuery.get('actor'), 'visual-canvas'],
                    ['draft import source', importQuery.get('changeSource'), 'gateway-browser'],
                    ['draft import summary', importQuery.get('changeSummary'), 'Imported visual draft package from Drafts panel.'],
                    ['draft import reason', importQuery.get('reason'), 'User imported a portable visual graph draft bundle in the browser.'],
                    ['draft validate body schema', validateBody.schemaVersion, 'bloge.visualGraphDraftExport.v1'],
                    ['draft import body schema', importBody.schemaVersion, 'bloge.visualGraphDraftExport.v1'],
                    ['draft bundle carries validation', String(transferResult.draftBundleHasValidation), 'true'],
                    ['draft bundle carries dependency report', String(transferResult.draftBundleHasDependencyReport), 'true'],
                    ['draft export message', transferResult.transferDraftMessages[0].text, 'Exported draft-risk@4 (sha256:draft-transfer).'],
                    ['draft export visual readiness', exportVisualCheck.readiness?.state, 'design-only'],
                    ['draft validate message', transferResult.transferDraftMessages[1].text, 'Validated draft bundle from draft-risk@4 (sha256:draft-transfer). Target review: all imported draft operator dependencies are available. Runtime binding handoff: 1 requirement across operator-platform (executable-lowering).'],
                    ['draft validate visual readiness', validateVisualCheck.readiness?.state, 'design-only'],
                    ['draft validate current id unchanged', transferResult.afterValidate.currentDraftId, 'draft-risk'],
                    ['draft validate current revision unchanged', transferResult.afterValidate.currentDraftRevision, 4],
                    ['draft validate dependency report', transferResult.afterValidate.dependencyReportDraftId, 'draft-risk'],
                    ['draft import message', transferResult.transferDraftMessages[2].text, 'Imported draft-imported@1 from draft-risk@4 (sha256:draft-transfer). Target review: all imported draft operator dependencies are available. Runtime binding handoff: 1 requirement across operator-platform (executable-lowering).'],
                    ['draft import visual readiness', importVisualCheck.readiness?.state, 'design-only'],
                    ['draft import current id', transferResult.currentDraftId, 'draft-imported'],
                    ['draft import current revision', transferResult.currentDraftRevision, 1],
                    ['draft import dependency report', transferResult.dependencyReportDraftId, 'draft-imported'],
                    ['draft transfer catalog loads', transferResult.transferCatalogLoads, 1],
                    ['draft transfer draft list loads', transferResult.transferDraftListLoads, 1],
                    ['draft transfer revision loads', transferResult.transferRevisionLoads, 1],
                    ['draft transfer controls render', transferResult.transferDraftControlRenders, 2],
                    ['draft transfer scenario render', transferResult.transferScenarioRenders, 1]
                  ];
                  for (const [label, actual, expected] of transferChecks) {
                    if (actual !== expected) {
                      throw new Error(`${label}: expected ${expected}, got ${actual}`);
                    }
                  }
                  """, """
                  let publishCallCount = 0;
                  const publishBodies = [];
                  const publishMessages = [];
                  const publishChecks = [];
                  let publishDraftSnapshotLoads = 0;
                  let publishDraftListLoads = 0;
                  let publishPublicationListLoads = 0;
                  let publishGoldenCaseLoads = 0;
                  let publishCertificationLoads = 0;
                  let publishCatalogLoads = 0;
                  let publishPaletteRenders = 0;
                  let publishControlRenders = 0;
                  context.state.currentDraftRevision = 4;
                  context.state.pendingPublishWarningKey = '';
                  context.state.publishArtifactKind = 'DESIGN';
                  context.saveCurrentDraft = async () => ({ draftId: 'draft-risk', revision: 4 });
                  context.fetch = async (url, options = {}) => {
                    publishCallCount += 1;
                    publishChecks.push(url);
                    publishBodies.push(JSON.parse(options.body || '{}'));
                    if (publishCallCount === 1) {
                      return {
                        status: 409,
                        json: async () => ({
                          published: false,
                          diagnostics: [{
                            level: 'WARNING',
                            code: 'visual.operator.governance.nonIdempotent',
                            message: 'Operator requires production promotion review.'
                          }],
                          validation: {
                            readiness: {
                              schemaVersion: 'bloge.visualGraphReadiness.v1',
                              state: 'GOVERNANCE_REVIEW',
                              level: 'WARNING',
                              executable: true,
                              artifactKinds: ['EXECUTABLE', 'DESIGN'],
                              title: 'Governance review graph',
                              summary: 'Executable after promotion review.'
                            }
                          }
                        })
                      };
                    }
                    return {
                      status: 201,
                      json: async () => ({
                        published: true,
                        publication: { publicationId: 'pub-risk' },
                        diagnostics: []
                      })
                    };
                  };
                  context.loadCurrentDraftSnapshot = async () => {
                    publishDraftSnapshotLoads += 1;
                  };
                  context.loadDraftList = async () => {
                    publishDraftListLoads += 1;
                    return [];
                  };
                  context.loadPublicationList = async () => {
                    publishPublicationListLoads += 1;
                    return [];
                  };
                  context.loadGoldenCases = async () => {
                    publishGoldenCaseLoads += 1;
                    return [];
                  };
                  context.loadGoldenCertificationStatus = async () => {
                    publishCertificationLoads += 1;
                    return null;
                  };
                  context.loadVisualOperatorCatalog = async () => {
                    publishCatalogLoads += 1;
                    return [];
                  };
                  context.renderOperatorPalette = () => {
                    publishPaletteRenders += 1;
                  };
                  context.renderPublicationControls = () => {
                    publishControlRenders += 1;
                  };
                  context.setPublicationMessage = (text, level) => {
                    publishMessages.push({ text, level });
                  };
                  context.setVisualCheck = (message, level, diagnostics = [], readiness = null) => {
                    context.state.visualCheck = {
                      message,
                      level,
                      diagnostics,
                      readiness: context.normalizeVisualGraphReadiness(readiness)
                    };
                  };
                  context.$ = (id) => {
                    context.elements[id] = context.elements[id] || { textContent: '', value: '' };
                    return context.elements[id];
                  };
                  return context.publishVisualDraft()
                    .then(() => {
                      const firstWarningKey = context.state.pendingPublishWarningKey;
                      const firstVisualLevel = context.state.visualCheck.level;
                      const firstVisualMessage = context.state.visualCheck.message;
                      const firstVisualReadinessState = context.state.visualCheck.readiness?.state || '';
                      const warningMessage = publishMessages[0] || {};
                      return context.publishVisualDraft().then(() => ({
                        firstBody: publishBodies[0],
                        secondBody: publishBodies[1],
                        endpoint: publishChecks[0],
                        firstWarningKey,
                        firstVisualLevel,
                        firstVisualMessage,
                        firstVisualReadinessState,
                        warningMessage,
                        finalWarningKey: context.state.pendingPublishWarningKey,
                        finalPublicationId: context.state.selectedPublicationId,
                        successMessage: publishMessages[publishMessages.length - 1] || {},
                        finalVisualLevel: context.state.visualCheck.level,
                        outputText: context.elements.output.textContent,
                        publishDraftSnapshotLoads,
                        publishDraftListLoads,
                        publishPublicationListLoads,
                        publishGoldenCaseLoads,
                        publishCertificationLoads,
                        publishCatalogLoads,
                        publishPaletteRenders,
                        publishControlRenders,
                        finalPublishArtifactKind: context.state.publishArtifactKind
                      }));
                    });
                }).then((publishResult) => {
                  const publishChecks = [
                    ['publish endpoint', publishResult.endpoint, '/api/visual/drafts/draft-risk/publish'],
                    ['publish first expected revision', publishResult.firstBody.expectedRevision, 4],
                    ['publish first warning ack', publishResult.firstBody.ackWarnings, false],
                    ['publish first artifact kind', publishResult.firstBody.artifactKind, 'DESIGN'],
                    ['publish first actor', publishResult.firstBody.actor, 'visual-canvas'],
                    ['publish first change source', publishResult.firstBody.changeSource, 'gateway-browser'],
                    ['publish first reason', publishResult.firstBody.reason, 'Published from the visual publication panel.'],
                    ['publish pending warning key', publishResult.firstWarningKey, 'draft-risk@4'],
                    ['publish warning message level', publishResult.warningMessage.level, 'warning'],
                    ['publish warning message text', publishResult.warningMessage.text, 'Review publish warnings, then click Publish again to continue.'],
                    ['publish first visual level', publishResult.firstVisualLevel, 'warning'],
                    ['publish first visual message', publishResult.firstVisualMessage, 'Visual graph was not published.'],
                    ['publish first visual readiness state', publishResult.firstVisualReadinessState, 'governance-review'],
                    ['publish warning draft snapshot load', publishResult.publishDraftSnapshotLoads, 1],
                    ['publish warning draft list load', publishResult.publishDraftListLoads, 1],
                    ['publish second expected revision', publishResult.secondBody.expectedRevision, 4],
                    ['publish second warning ack', publishResult.secondBody.ackWarnings, true],
                    ['publish second artifact kind', publishResult.secondBody.artifactKind, 'DESIGN'],
                    ['publish second actor', publishResult.secondBody.actor, 'visual-canvas'],
                    ['publish second change source', publishResult.secondBody.changeSource, 'gateway-browser'],
                    ['publish second change summary', publishResult.secondBody.changeSummary, 'Published DESIGN visual draft draft-risk@4.'],
                    ['publish second reason', publishResult.secondBody.reason, 'Warnings reviewed in the visual publication panel.'],
                    ['publish final warning key cleared', publishResult.finalWarningKey, ''],
                    ['publish state artifact kind', publishResult.finalPublishArtifactKind, 'DESIGN'],
                    ['publish selected publication', publishResult.finalPublicationId, 'pub-risk'],
                    ['publish success message level', publishResult.successMessage.level, 'success'],
                    ['publish success message text', publishResult.successMessage.text, 'Published pub-risk.'],
                    ['publish final visual level', publishResult.finalVisualLevel, 'success'],
                    ['publish publication list load', publishResult.publishPublicationListLoads, 1],
                    ['publish golden case load', publishResult.publishGoldenCaseLoads, 1],
                    ['publish certification load', publishResult.publishCertificationLoads, 1],
                    ['publish catalog load', publishResult.publishCatalogLoads, 1],
                    ['publish palette render', publishResult.publishPaletteRenders, 1],
                    ['publish controls render', publishResult.publishControlRenders, 1],
                    ['publish output payload', String(publishResult.outputText.includes('"published": true')), 'true']
                  ];
                  for (const [label, actual, expected] of publishChecks) {
                    if (actual !== expected) {
                      throw new Error(`${label}: expected ${expected}, got ${actual}`);
                    }
                  }
                  console.log('browser bracket path probe passed');
                }).catch((error) => {
                  console.error(error);
                  process.exitCode = 1;
                });
                """);
    }

    private static String unionSchemaProbe() {
        return """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');
                new vm.Script(source, { filename: 'app.js' });

                function functionSource(name) {
                  const start = source.indexOf(`function ${name}(`);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                const context = vm.createContext({ console });
                context.SUPPORTED_SCHEMA_FORMAT = 'json-schema';
                context.SUPPORTED_SCHEMA_VERSION = '2020-12';
                context.SUPPORTED_SCHEMA_KINDS = new Set([
                  'object', 'array', 'string', 'integer', 'number', 'decimal',
                  'boolean', 'duration', 'datetime', 'enum', 'any', 'opaque', 'null'
                ]);
                context.SUPPORTED_SCHEMA_UNION_KEYWORDS = ['oneOf', 'anyOf'];
                context.SUPPORTED_SCHEMA_CONDITIONAL_KEYWORDS = ['if', 'then', 'else'];
                context.SCHEMA_OUTLINE_VISIBLE_ROW_LIMIT = 24;
                context.SCHEMA_OUTLINE_MAX_DEPTH = 4;
                context.UNSUPPORTED_SCHEMA_REFERENCE_KEYWORDS = ['$ref', '$dynamicRef'];
                context.UNSUPPORTED_SCHEMA_CONSTRAINT_KEYWORDS = [];
                context.LOCAL_SCHEMA_DEFS_REF_PREFIX = '#/$defs/';
                context.SCHEMA_REF_ANNOTATION_KEYS = new Set([
                  '$ref', '$comment', 'title', 'description', 'examples', 'deprecated', 'readOnly', 'writeOnly'
                ]);
                context.SCHEMA_ANNOTATION_KEYS = new Set([
                  '$comment', 'title', 'description', 'examples', 'deprecated', 'readOnly', 'writeOnly'
                ]);
                context.SCHEMA_DECLARATION_KEYS = new Set(['$defs']);
                context.state = {
                  selectedNodeId: 'schemaProbe',
                  currentDraftId: 'draft-schema-probe',
                  currentDraftRevision: 3,
                  savedDraftSnapshot: { draftId: 'draft-schema-probe', revision: 3 },
                  builderHistoryUndo: [],
                  operatorFingerprintRebaseNodeId: '',
                  builder: { selectedId: 'schemaProbe' }
                };
                context.isDslFieldName = (value) => /^[A-Za-z_][A-Za-z0-9_]*$/.test(String(value || ''))
                  && !new Set(['graph', 'node', 'input', 'output', 'true', 'false']).has(String(value || ''));

                for (const name of [
                  'escapeHtml',
                  'compactStringHash',
                  'isPlainObject',
                  'uniqueStrings',
                  'resolveLocalSchemaRefs',
                  'resolveLocalSchemaRefValue',
                  'flattenSafeAllOf',
                  'flattenObjectAllOf',
                  'flattenScalarAllOf',
                  'explicitScalarAllOfType',
                  'scalarAllOfFragmentSupported',
                  'mergeScalarAllOfFragment',
                  'scalarAllOfAllowedKeys',
                  'validScalarAllOfConstraint',
                  'objectCompositionSchema',
                  'mergeObjectMap',
                  'mergeObjectKeyword',
                  'mergeDependentRequiredKeyword',
                  'mergeRequiredKeyword',
                  'mergeUniqueStrings',
                  'residualPolicy',
                  'mergeResidualPolicy',
                  'propertyBound',
                  'maxOptionalNumber',
                  'minOptionalNumber',
                  'expandableLocalSchemaRef',
                  'resolveSchemaJsonPointer',
                  'decodeJsonPointerToken',
                  'arrayIndexSegment',
                  'deepCloneSchemaValue',
                  'validateSchemaEnvelope',
                  'validateSchemaStructure',
                  'validateSchemaTypeArray',
                  'validateSchemaDefinitions',
                  'validateUnsupportedSchemaKeywords',
                  'schemaReferenceDiagnostic',
                  'validateSupportedSchemaUnions',
                  'validateSupportedSchemaAllOf',
                  'validateSupportedSchemaConditionals',
                  'validateSchemaNot',
                  'validateSchemaEnumValues',
                  'graphInputSchemaDiagnostic',
                  'schemaType',
                  'schemaAllOfLabel',
                  'schemaUnionLabel',
                  'schemaUnionSummary',
                  'schemaUnionDescriptors',
                  'schemaUnionDescriptorLabel',
                  'schemaUnionBranches',
                  'schemaAllOfBranches',
                  'schemaUnionBranchOptions',
                  'normalizedUnionBranchSelection',
                  'unionBranchSelectionValue',
                  'unionBranchSelectionFromValue',
                  'selectedUnionBranchSchema',
                  'schemaCompatibilityIssueForTargetUnionSelection',
                  'targetSchemaForUnionSelection',
                  'schemaWithoutUnions',
                  'schemaWithoutConditionals',
                  'schemaWithoutCombinator',
                  'schemaWithoutCombinators',
                  'effectiveConditionalSchema',
                  'effectiveConditionalValidationSchema',
                  'effectiveDependentObjectSchema',
                  'schemaObjectProperties',
                  'schemaPatternProperties',
                  'schemaPropertyNameSchema',
                  'schemaRequiredNames',
                  'schemaItemsSchema',
                  'schemaPrefixItems',
                  'schemaContainsSchema',
                  'arrayItemBoundaryValue',
                  'schemaMinContains',
                  'schemaMaxContains',
                  'hasSupportedArrayItemContract',
                  'residualArrayItemSchema',
                  'unevaluatedArrayItemsPolicy',
                  'residualPropertiesPolicy',
                  'residualPropertiesKeyword',
                  'schemaDependentRequired',
                  'schemaDependentSchemas',
                  'schemaCompatibilityIssue',
                  'sourceAllOfCompatibilityIssue',
                  'branchCanProveAllOfSourceCompatibility',
                  'targetAllOfCompatibilityIssue',
                  'sourceUnionCompatibilityIssue',
                  'targetUnionCompatibilityIssue',
                  'targetConditionalCompatibilityIssue',
                  'conditionalBranchIssue',
                  'unionBaseCompatibilityIssue',
                  'targetFiniteDomainCompatibilityIssue',
                  'targetFiniteDomainLabel',
                  'sourceFiniteDomainLabel',
                  'sourceDomainKind',
                  'targetNotCompatibilityIssue',
                  'schemaValueMatchesSchema',
                  'schemaValueMatchesNot',
                  'schemaValueMatchesEffectiveNotSchema',
                  'effectiveNotValueSchema',
                  'effectiveSchemaType',
                  'compatibilitySchemaType',
                  'schemaHasAnyKeyword',
                  'schemaValueMatchesUnions',
                  'schemaValueMatchesAllOf',
                  'schemaValueMatchesConditional',
                  'schemaValueMatchesType',
                  'arrayValueMatchesItemsPolicy',
                  'arrayValueMatchesUnevaluatedItems',
                  'rawSchemaType',
                  'nullableTypePrimary',
                  'schemaMayProduceNull',
                  'schemaAllowsNull',
                  'schemaTypeForValue',
                  'schemaEnumValues',
                  'finiteSchemaValues',
                  'uniqueSchemaValues',
                  'schemaValuesEqual',
                  'schemaValueKey',
                  'canonicalSchemaValueKey',
                  'numericType',
                  'stringType',
                  'arrayType',
                  'reasonAt',
                  'appendCompatibilityPath',
                  'valueDomainLabel',
                  'changeRiskLabel',
                  'currentDraftHasUnsavedGraphChanges',
                  'operatorFingerprintRebaseBlockReason',
                  'draftDependencyCanRebase',
                  'draftDependencyHasRebaseState',
                  'normalizeSchemaRebaseDecisions',
                  'deriveSchemaRebaseDecisionsFromNodes',
                  'renderSchemaRebaseDecisionQueue',
                  'renderSchemaRebaseDecisionRow',
                  'schemaRebaseEligibleNodeIds',
                  'selectedNodeSchemaRebaseDecision',
                  'renderSelectedNodeSchemaRebaseDecision',
                  'schemaRebaseDecisionQueueSummary',
                  'schemaRebaseDecisionRank',
                  'schemaRebaseDecisionLevel',
                  'schemaRebaseDecisionStateLabel',
                  'schemaRebaseDecisionActionLabel',
                  'draftDependencyRebaseBlockReason',
                  'normalizeSchemaDriftIssues',
                  'normalizeSchemaDriftSchemaPreview',
                  'plainSchemaPreviewObject',
                  'schemaDriftSchemaPreviewText',
                  'schemaDriftSchemaPreviewSearchText',
                  'normalizeSchemaDriftSchemaChanges',
                  'schemaDriftTypeTransitionLabel',
                  'schemaDriftReviewHint',
                  'schemaDriftSchemaChangeLabel',
                  'schemaDriftIssueChangeSummary',
                  'schemaDriftIssueDetailLabel',
                  'normalizeSchemaDriftPath',
                  'schemaDriftIssuesForPort',
                  'schemaDriftIssueMatchesOutlineRow',
                  'schemaDriftIssuesForOutlineRow',
                  'schemaDriftSummaryLabel',
                  'renderSchemaDriftSummary',
                  'renderSchemaDriftReviewPanel',
                  'schemaDriftLevel',
                  'schemaOutlineSearchInputDomId',
                  'schemaOutlineSearchMetaDomId',
                  'schemaOutlinePortDomId',
                  'schemaOutlineRowDomId',
                  'schemaOutlineSelectedNodeKey',
                  'contractSchemaOutlineDomIds',
                  'schemaOutlineRowA11yPositionAttrs',
                  'renderSchemaOutlineSearchControl',
                  'contractSchemaOutlineSearchSummary',
                  'renderSchemaOutline',
                  'schemaOutlineRowSummary',
                  'schemaOutlineForEnvelope',
                  'normalizeSchemaOutlineQuery',
                  'schemaOutlineRowMatchesQuery',
                  'schemaOutlineRowSearchText',
                  'schemaOutlinePrioritizedRows',
                  'schemaOutlineDisplayRank',
                  'schemaOutlineDisplayDepth',
                  'schemaOutlineKindPriority',
                  'schemaOutlineRows',
                  'schemaOutlineCombinatorRows',
                  'schemaOutlineConditionalRows',
                  'schemaOutlineDependentRows',
                  'schemaOutlineObjectRows',
                  'schemaOutlineResidualPropertyRows',
                  'schemaOutlineArrayRows',
                  'schemaOutlineDescriptor',
                  'schemaOutlinePath',
                  'schemaOutlineHasNestedSurface',
                  'renderContractPortGroup',
                  'renderLibraryProfilePanel',
                  'renderLibraryImportReadiness',
                  'renderLibraryImportReadinessHandoffGroups',
                  'renderLibraryImportReadinessCountRows',
                  'libraryImportReadinessCountRows',
                  'libraryProfileLevel',
                  'operatorLibraryProfile',
                  'operatorLibraryOperatorProfile',
                  'operatorLibraryRuntimeReadiness',
                  'operatorLibraryPolicyProfile',
                  'operatorLibraryPolicyScope',
                  'emptyOperatorLibraryPortProfile',
                  'addOperatorLibraryPortProfile',
                  'operatorLibraryPortProfile',
                  'operatorLibraryPortFields',
                  'operatorLibraryConfigFields',
                  'operatorLibraryPortUnionSummary',
                  'operatorLibraryInputPortDslPathSafe',
                  'operatorLibraryFieldProfile',
                  'operatorLibrarySchemaSummary',
                  'operatorLibraryFieldLabel',
                  'operatorLibraryFieldAnnotationSummary',
                  'schemaAnnotationDescriptor',
                  'schemaFieldDisplayHint',
                  'schemaAnnotationText',
                  'schemaExamplesSummary',
                  'schemaValueSummary',
                  'visibleSchemaAnnotationSummary',
                  'compactSchemaAnnotation',
                  'normalizeCountMap',
                  'normalizeOperatorRuntimeReadiness'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }

                for (const name of [
                  'validateSchemaEnum',
                  'validateSchemaConst',
                  'validateSchemaNumericBounds',
                  'validateSchemaNumericMultipleOf',
                  'validateSchemaStringLengthBounds',
                  'validateSchemaStringPattern',
                  'validateSchemaStringFormat',
                  'validateSchemaArrayItemBounds',
                  'validateSchemaArrayUniqueItems',
                  'validateSchemaArrayPrefixItems',
                  'validateSchemaArrayContains',
                  'validateSchemaArrayUnevaluatedItems',
                  'validateSchemaObjectPropertyBounds',
                  'validateSchemaObjectPatternProperties',
                  'validateSchemaObjectPropertyNames',
                  'validateSchemaObjectDependentRequired',
                  'validateSchemaObjectDependentSchemas',
                  'validateSchemaUnevaluatedProperties',
                  'validateSchemaAdditionalProperties',
                  'validateCustomSchemaEnumValues'
                ]) {
                  context[name] = () => {};
                }
                context.validatedSchemaObjectProperties = (schema) =>
                  schema?.properties && typeof schema.properties === 'object' && !Array.isArray(schema.properties)
                    ? schema.properties
                    : {};
                context.validatedSchemaRequiredNames = () => [];
                context.numericIntegerCompatibilityIssue = () => '';
                context.numericBoundsCompatibilityIssue = () => '';
                context.numericMultipleOfCompatibilityIssue = () => '';
                context.stringFormatCompatibilityIssue = () => '';
                context.stringPatternCompatibilityIssue = () => '';
                context.stringLengthCompatibilityIssue = () => '';
                context.arrayPrefixItemsCompatibilityIssue = () => '';
                context.arrayItemsCompatibilityIssue = () => '';
                context.arrayUnevaluatedItemsCompatibilityIssue = () => '';
                context.arrayItemBoundsCompatibilityIssue = () => '';
                context.arrayUniqueItemsCompatibilityIssue = () => '';
                context.arrayContainsCompatibilityIssue = () => '';
                context.objectSchemaCompatibilityIssue = () => '';
                context.numericValueMatchesBounds = () => true;
                context.numericValueMatchesMultipleOf = () => true;
                context.stringValueMatchesLengthBounds = () => true;
                context.stringValueMatchesPattern = () => true;
                context.stringValueMatchesFormat = () => true;
                context.arrayValueMatchesSchema = () => true;
                context.objectValueMatchesSchema = () => true;
                context.schemaFieldDescriptors = () => [];
                context.configFieldDescriptors = () => [];
                context.inputPortsForSpec = (spec) => Array.isArray(spec?.inputPorts) ? spec.inputPorts : [];
                context.outputPortsForSpec = (spec) => Array.isArray(spec?.outputPorts) ? spec.outputPorts : [];
                context.hasSchemaProperties = () => false;
                context.schemaDynamicSurfaceCount = () => 0;
                context.operatorLibraryOutputPortDslPathSafe = () => true;

                const validUnionDiagnostics = [];
                context.validateSchemaStructure({
                  oneOf: [{ type: 'integer' }, { type: 'string' }]
                }, 'schema', validUnionDiagnostics);
                const invalidUnionDiagnostics = [];
                context.validateSchemaStructure({ anyOf: [] }, 'schema', invalidUnionDiagnostics);
                const ambiguousUnionDiagnostics = [];
                context.validateSchemaStructure({
                  oneOf: [{ type: 'integer' }],
                  anyOf: [{ type: 'string' }]
                }, 'schema', ambiguousUnionDiagnostics);
                const validConditionalDiagnostics = [];
                context.validateSchemaStructure({
                  if: { type: 'string' },
                  then: { type: 'string', enum: ['VIP'] },
                  else: { type: 'integer' }
                }, 'schema', validConditionalDiagnostics);
                const invalidConditionalDiagnostics = [];
                context.validateSchemaStructure({
                  if: 'bad'
                }, 'schema', invalidConditionalDiagnostics);
                const validAllOfDiagnostics = [];
                context.validateSchemaStructure({
                  allOf: [{ type: 'string' }, { minLength: 3 }]
                }, 'schema', validAllOfDiagnostics);
                const invalidAllOfDiagnostics = [];
                context.validateSchemaStructure({
                  allOf: [{ type: 'string' }, 'bad']
                }, 'schema', invalidAllOfDiagnostics);
                const finiteNotDiagnostics = [];
                context.validateSchemaStructure({
                  type: 'string',
                  not: { const: 'ARCHIVED' }
                }, 'schema', finiteNotDiagnostics);
                const nonFiniteNotDiagnostics = [];
                context.validateSchemaStructure({
                  type: 'string',
                  not: { type: 'string' }
                }, 'schema', nonFiniteNotDiagnostics);
                const safeAllOfSchema = context.resolveLocalSchemaRefs({
                  type: 'object',
                  properties: {
                    code: {
                      allOf: [
                        { type: 'string' },
                        { minLength: 3 },
                        { pattern: '^[A-Z]+$' }
                      ]
                    },
                    score: {
                      allOf: [
                        { type: 'integer' },
                        { minimum: 300 },
                        { maximum: 850 }
                      ]
                    }
                  }
                });
                const safeAllOfDiagnostics = [];
                context.validateSchemaStructure(safeAllOfSchema, 'schema', safeAllOfDiagnostics);
                const unresolvedRefDiagnostics = [];
                context.validateSchemaStructure({
                  type: 'object',
                  properties: {
                    customer: { $ref: '#/$defs/Customer' },
                    remote: { $ref: 'https://schemas.example.test/Customer.json' }
                  }
                }, 'schema', unresolvedRefDiagnostics);

                const sourceUnionIssue = context.schemaCompatibilityIssue(
                  { oneOf: [{ type: 'integer' }, { type: 'string' }] },
                  { type: 'integer' }
                );
                const targetAnyOfIssue = context.schemaCompatibilityIssue(
                  { type: 'string' },
                  { anyOf: [{ type: 'integer' }, { type: 'string' }] }
                );
                const targetOneOfIssue = context.schemaCompatibilityIssue(
                  { type: 'integer' },
                  { oneOf: [{ type: 'integer' }, { type: 'number' }] }
                );
                const targetConditionalCompatible = context.schemaCompatibilityIssue(
                  { type: 'string', enum: ['VIP'] },
                  { if: { type: 'string' }, then: { type: 'string', enum: ['VIP'] }, else: { type: 'integer' } }
                );
                const targetConditionalIssue = context.schemaCompatibilityIssue(
                  { type: 'string', enum: ['NOPE'] },
                  { if: { type: 'string' }, then: { type: 'string', enum: ['VIP'] }, else: { type: 'integer' } }
                );
                const selectedBranchIssue = context.schemaCompatibilityIssueForTargetUnionSelection(
                  { type: 'integer' },
                  { oneOf: [{ type: 'integer' }, { type: 'number' }] },
                  { keyword: 'oneOf', index: 0 }
                );
                const wrongBranchIssue = context.schemaCompatibilityIssueForTargetUnionSelection(
                  { type: 'integer' },
                  { oneOf: [{ type: 'string' }, { type: 'integer' }] },
                  { keyword: 'oneOf', index: 0 }
                );
                const branchOptions = context.schemaUnionBranchOptions({
                  oneOf: [{ type: 'integer' }, { type: 'string' }]
                }).map((option) => option.label).join('|');
                const branchValue = context.unionBranchSelectionValue({ keyword: 'oneOf', index: 1 });
                const parsedBranch = context.unionBranchSelectionFromValue(branchValue);
                const nestedUnionSummary = context.schemaUnionSummary({
                  schema: {
                    type: 'object',
                    properties: {
                      decision: {
                        oneOf: [{ type: 'integer' }, { type: 'string' }],
                        title: 'Decision value',
                        examples: ['APPROVE']
                      },
                      events: {
                        type: 'array',
                        items: { anyOf: [{ type: 'boolean' }, { type: 'null' }] }
                      }
                    }
                  }
                });
                const unionSchemaEnvelope = {
                  schema: {
                    type: 'object',
                    properties: {
                      decision: { oneOf: [{ type: 'integer' }, { type: 'string' }] },
                      events: {
                        type: 'array',
                        items: { anyOf: [{ type: 'boolean' }, { type: 'null' }] }
                      }
                    }
                  }
                };
                context.schemaFieldDescriptors = () => [{
                  path: 'decision',
                  schema: unionSchemaEnvelope.schema.properties.decision,
                  required: false,
                  dslPathSafe: true,
                  title: 'Decision value',
                  examplesSummary: 'APPROVE'
                }];
                const unionContractHtml = context.renderContractPortGroup('Inputs', [{
                  name: 'inputs',
                  schema: unionSchemaEnvelope,
                  required: true
                }]);
                const complexOutlineEnvelope = {
                  schema: {
                    type: 'object',
                    properties: {
                      customer: {
                        type: 'object',
                        properties: {
                          profile: {
                            type: 'object',
                            properties: {
                              id: { type: 'string', title: 'Customer identifier' }
                            },
                            required: ['id']
                          },
                          payment: {
                            type: 'object',
                            properties: {
                              paymentMethod: { type: 'string' },
                              cardNumber: { type: 'string' },
                              cardExpiry: { type: 'string' }
                            },
                            dependentRequired: {
                              paymentMethod: ['cardNumber', 'cardExpiry']
                            }
                          },
                          attributes: {
                            type: 'object',
                            patternProperties: {
                              '^risk[A-Z].*': { type: 'integer' }
                            },
                            additionalProperties: { type: 'string' }
                          }
                        }
                      },
                      events: {
                        type: 'array',
                        contains: {
                          type: 'object',
                          properties: {
                            type: { type: 'string' }
                          }
                        },
                        minContains: 1
                      },
                      payload: {
                        oneOf: [
                          { type: 'object', properties: { manualLane: { type: 'string' } } },
                          { type: 'object', properties: { autoScore: { type: 'integer' } } }
                        ]
                      }
                    }
                  }
                };
                const complexOutline = context.schemaOutlineForEnvelope(complexOutlineEnvelope, 40);
                const complexOutlinePaths = complexOutline.rows.map((row) => row.path).join('|');
                const complexOutlineHtml = context.renderSchemaOutline(
                  context.schemaOutlineForEnvelope(complexOutlineEnvelope, 4),
                  { outlineId: 'outline-test', label: 'Test outline' }
                );
                const cardExpiryOutline = context.schemaOutlineForEnvelope(complexOutlineEnvelope, 8, 'customer.payment.cardExpiry');
                const cardExpiryOutlinePaths = cardExpiryOutline.rows.map((row) => row.path).join('|');
                const cardExpiryOutlineHtml = context.renderSchemaOutline(context.schemaOutlineForEnvelope(complexOutlineEnvelope, 1, 'payment'));
                const missingOutlineHtml = context.renderSchemaOutline(context.schemaOutlineForEnvelope(complexOutlineEnvelope, 8, 'missingPath'));
                const driftIssues = [{
                  surface: 'input',
                  portName: 'request',
                  compatibility: 'breaking',
                  path: 'customer.profile.id',
                  savedType: 'string',
                  currentType: 'integer',
                  reviewHint: 'Review bindings before rebase.',
                  schemaChanges: [{
                    path: 'customer.profile.id',
                    keyword: 'type',
                    savedValue: 'string',
                    currentValue: 'integer',
                    compatibility: 'breaking',
                    summary: 'type: string -> integer'
                  }],
                  schemaPreview: {
                    path: 'customer.profile.id',
                    savedSchema: { type: 'string', title: 'Customer identifier' },
                    currentSchema: { type: 'integer', minimum: 1 },
                    truncated: false
                  },
                  message: 'id type narrowed'
                }];
                const driftOutline = context.schemaOutlineForEnvelope(complexOutlineEnvelope, 40, 'breaking', driftIssues);
                const driftOutlineByType = context.schemaOutlineForEnvelope(complexOutlineEnvelope, 40, 'string integer', driftIssues);
                const driftOutlineByKeyword = context.schemaOutlineForEnvelope(complexOutlineEnvelope, 40, 'type integer', driftIssues);
                const driftOutlineByPreview = context.schemaOutlineForEnvelope(complexOutlineEnvelope, 40, 'frozen minimum', driftIssues);
                const driftOutlineByReview = context.schemaOutlineForEnvelope(complexOutlineEnvelope, 40, 'rebase', driftIssues);
                const driftOutlinePaths = driftOutline.rows.map((row) => row.path).join('|');
                const driftLeafRow = driftOutline.rows.find((row) => row.path === 'customer.profile.id');
                const driftOutlineHtml = context.renderSchemaOutline(
                  driftOutline,
                  { outlineId: 'drift-outline', label: 'Drift outline' }
                );
                const driftSummaryHtml = context.renderSchemaDriftSummary(driftIssues);
                const driftReviewHtml = context.renderSchemaDriftReviewPanel(driftIssues);
                const schemaRebaseReport = {
                  schemaRebaseDecisionStateCounts: {
                    'repair-review': 1,
                    'ready-rebase': 1,
                    blocked: 1
                  },
                  schemaRebaseDecisions: [{
                    decisionId: 'schema-rebase:riskEligibility:breaking:drifted',
                    nodeId: 'riskEligibility',
                    nodeLabel: 'Eligibility',
                    operatorRef: 'risk:eligibility',
                    operatorLibraryId: 'risk-policy',
                    queueState: 'repair-review',
                    recommendedAction: 'repair bindings or explicitly approve rebase',
                    rebaseEligible: true,
                    issueCount: 1,
                    breakingIssueCount: 1,
                    compatibleIssueCount: 0,
                    affectedSurfaces: ['input.inputs'],
                    affectedPaths: ['input.inputs.score'],
                    downstreamNodes: ['riskAudit'],
                    reviewSummary: '1 schema issue; input.inputs.score: score type narrowed'
                  }, {
                    decisionId: 'schema-rebase:riskAudit:compatible:drifted',
                    nodeId: 'riskAudit',
                    nodeLabel: 'Audit',
                    operatorRef: 'risk:audit',
                    operatorLibraryId: 'risk-policy',
                    queueState: 'ready-rebase',
                    recommendedAction: 'review drift evidence and rebase',
                    rebaseEligible: true,
                    issueCount: 1,
                    breakingIssueCount: 0,
                    compatibleIssueCount: 1,
                    affectedSurfaces: ['output.output'],
                    affectedPaths: ['output.output.auditId'],
                    downstreamNodes: [],
                    reviewSummary: '1 schema issue; output.output.auditId widened'
                  }, {
                    decisionId: 'schema-rebase:missingRisk:catalog-missing:catalog-missing',
                    nodeId: 'missingRisk',
                    nodeLabel: 'Missing Risk',
                    operatorRef: 'risk:missing',
                    operatorLibraryId: 'risk-policy',
                    queueState: 'blocked',
                    recommendedAction: 'repair catalog or authoring scope first',
                    rebaseEligible: false,
                    blockingReason: 'current operator is unavailable in the catalog',
                    issueCount: 0,
                    breakingIssueCount: 0,
                    compatibleIssueCount: 0,
                    affectedSurfaces: [],
                    affectedPaths: [],
                    downstreamNodes: [],
                    reviewSummary: 'current operator is unavailable in the catalog'
                  }]
                };
                context.state.draftDependencyReport = schemaRebaseReport;
                const schemaRebaseDecisions = context.normalizeSchemaRebaseDecisions(schemaRebaseReport);
                const schemaRebaseQueueHtml = context.renderSchemaRebaseDecisionQueue(schemaRebaseReport);
                const selectedSchemaRebaseDecision = context.selectedNodeSchemaRebaseDecision({ id: 'riskEligibility' });
                const selectedSchemaRebaseHtml = context.renderSelectedNodeSchemaRebaseDecision({ id: 'riskEligibility' });
                const schemaRebaseEligibleNodes = context.schemaRebaseEligibleNodeIds(schemaRebaseReport).join('|');
                const schemaRebaseSummary = context.schemaRebaseDecisionQueueSummary(schemaRebaseDecisions);
                const driftIssueForPort = context.schemaDriftIssuesForPort(driftIssues, 'input', 'request')[0];
                const driftTransitionLabel = context.schemaDriftTypeTransitionLabel(driftIssueForPort);
                const driftReviewHint = context.schemaDriftReviewHint(driftIssueForPort);
                const driftChangeSummary = context.schemaDriftIssueChangeSummary(driftIssueForPort);
                const driftPreviewSearchText = context.schemaDriftSchemaPreviewSearchText(driftIssueForPort.schemaPreview);
                const driftDetailLabel = context.schemaDriftIssueDetailLabel(driftIssueForPort);
                const schemaSearchHtml = context.renderSchemaOutlineSearchControl(
                  { id: 'riskComplexIntake' },
                  { inputPorts: [{ name: 'request', schema: complexOutlineEnvelope }], outputPorts: [] },
                  'payment',
                  driftIssues
                );
                const unionLibraryProfile = context.operatorLibraryProfile({
                  libraryId: 'union-profile',
                  version: '1.0.0',
                  operators: [{
                    operatorRef: 'risk:union',
                    display: { name: 'Union Operator' },
                    ports: {
                      inputs: [{ name: 'inputs', schema: unionSchemaEnvelope, required: true }],
                      outputs: []
                    },
                    configSchema: {
                      schema: {
                        oneOf: [{ type: 'object' }, { type: 'null' }]
                      }
                    }
                  }]
                });
                const unionLibraryProfileHtml = context.renderLibraryProfilePanel(unionLibraryProfile);

                const checks = [
                  ['valid union diagnostics', validUnionDiagnostics.length, 0],
                  ['invalid union code', invalidUnionDiagnostics.map((diagnostic) => diagnostic.code).join('|'), 'visual.schema.unionInvalid'],
                  ['ambiguous union code', ambiguousUnionDiagnostics.some((diagnostic) => diagnostic.code === 'visual.schema.unionAmbiguous'), true],
                  ['valid conditional diagnostics', validConditionalDiagnostics.length, 0],
                  ['invalid conditional code', invalidConditionalDiagnostics.map((diagnostic) => diagnostic.code).join('|'), 'visual.schema.conditionalInvalid'],
                  ['valid allOf diagnostics', validAllOfDiagnostics.length, 0],
                  ['invalid allOf code', invalidAllOfDiagnostics.map((diagnostic) => diagnostic.code).join('|'), 'visual.schema.allOfInvalid'],
                  ['finite not diagnostics', finiteNotDiagnostics.length, 0],
                  ['schema-form not diagnostics', nonFiniteNotDiagnostics.length, 0],
                  ['safe scalar allOf diagnostics', safeAllOfDiagnostics.length, 0],
                  ['safe scalar allOf string type', safeAllOfSchema.properties.code.type, 'string'],
                  ['safe scalar allOf string pattern', safeAllOfSchema.properties.code.pattern, '^[A-Z]+$'],
                  ['safe scalar allOf number minimum', safeAllOfSchema.properties.score.minimum, 300],
                  ['unresolved local ref diagnostic', unresolvedRefDiagnostics.find((diagnostic) => diagnostic.target === '/inputSchema/schema/properties/customer/$ref')?.code, 'visual.schema.refUnresolved'],
                  ['remote ref diagnostic', unresolvedRefDiagnostics.find((diagnostic) => diagnostic.target === '/inputSchema/schema/properties/remote/$ref')?.code, 'visual.schema.refRemoteUnsupported'],
                  ['oneOf type label', context.schemaType({ oneOf: [{ type: 'integer' }, { type: 'string' }] }), 'oneOf<integer|string>'],
                  ['allOf type label', context.schemaType({ allOf: [{ type: 'string' }, { enum: ['APPROVE'] }] }), 'allOf<string&enum<APPROVE>>'],
                  ['nested anyOf type label', context.schemaType({ type: 'array', items: { anyOf: [{ type: 'boolean' }, { type: 'null' }] } }), 'array<anyOf<boolean|null>>'],
                  ['oneOf exact value', context.schemaValueMatchesSchema('APPROVE', { oneOf: [{ type: 'string', enum: ['APPROVE'] }, { type: 'string', enum: ['REJECT'] }] }), true],
                  ['oneOf missing value', context.schemaValueMatchesSchema('PENDING', { oneOf: [{ type: 'string', enum: ['APPROVE'] }, { type: 'string', enum: ['REJECT'] }] }), false],
                  ['oneOf ambiguous numeric value', context.schemaValueMatchesSchema(3, { oneOf: [{ type: 'integer' }, { type: 'number' }] }), false],
                  ['anyOf matching value', context.schemaValueMatchesSchema(3, { anyOf: [{ type: 'integer' }, { type: 'string' }] }), true],
                  ['anyOf missing value', context.schemaValueMatchesSchema(false, { anyOf: [{ type: 'integer' }, { type: 'string' }] }), false],
                  ['conditional then matching value', context.schemaValueMatchesSchema('VIP', { if: { type: 'string' }, then: { type: 'string', enum: ['VIP'] }, else: { type: 'integer' } }), true],
                  ['conditional then rejected value', context.schemaValueMatchesSchema('NOPE', { if: { type: 'string' }, then: { type: 'string', enum: ['VIP'] }, else: { type: 'integer' } }), false],
                  ['conditional else matching value', context.schemaValueMatchesSchema(7, { if: { type: 'string' }, then: { type: 'string', enum: ['VIP'] }, else: { type: 'integer' } }), true],
                  ['allOf matching value', context.schemaValueMatchesSchema('APPROVE', { allOf: [{ type: 'string' }, { enum: ['APPROVE', 'REJECT'] }, { not: { const: 'ARCHIVED' } }] }), true],
                  ['allOf rejected value', context.schemaValueMatchesSchema('ARCHIVED', { allOf: [{ type: 'string' }, { not: { const: 'ARCHIVED' } }] }), false],
                  ['not excluded value', context.schemaValueMatchesSchema('ARCHIVED', { type: 'string', not: { const: 'ARCHIVED' } }), false],
                  ['not accepted value', context.schemaValueMatchesSchema('ACTIVE', { type: 'string', not: { const: 'ARCHIVED' } }), true],
                  ['type-less numeric not keeps string value', context.schemaValueMatchesSchema('ACTIVE', { type: 'string', not: { minimum: 0 } }), true],
                  ['nested union summary', nestedUnionSummary, 'decision oneOf<integer|string>, events[] anyOf<boolean|null>'],
                  ['union contract row html', String(unionContractHtml.includes('decision oneOf&lt;integer|string&gt;, events[] anyOf&lt;boolean|null&gt;')), 'true'],
                  ['union contract row annotation', String(unionContractHtml.includes('Decision value')), 'true'],
                  ['complex outline nested path', String(complexOutlinePaths.includes('customer.profile.id')), 'true'],
                  ['complex outline pattern path', String(complexOutlinePaths.includes('customer.attributes.pattern ^risk[A-Z].*')), 'true'],
                  ['complex outline residual path', String(complexOutlinePaths.includes('customer.attributes.additionalProperties *')), 'true'],
                  ['complex outline contains path', String(complexOutlinePaths.includes('events.contains')), 'true'],
                  ['complex outline union branch path', String(complexOutlinePaths.includes('payload.oneOf[0]')), 'true'],
                  ['complex outline dependent path', String(complexOutlinePaths.includes('customer.payment.dependentRequired paymentMethod')), 'true'],
                  ['complex outline overflow html', String(complexOutlineHtml.includes('Showing first 4 of')), 'true'],
                  ['complex outline list role', String(complexOutlineHtml.includes('role="list"')), 'true'],
                  ['complex outline stable id', String(complexOutlineHtml.includes('id="outline-test"')), 'true'],
                  ['complex outline row role', String(complexOutlineHtml.includes('role="listitem"')), 'true'],
                  ['complex outline row position', String(complexOutlineHtml.includes('aria-posinset="1"')), 'true'],
                  ['complex outline row set size', String(complexOutlineHtml.includes('aria-setsize="')), 'true'],
                  ['complex outline search deep path', String(cardExpiryOutlinePaths.includes('customer.payment.cardExpiry')), 'true'],
                  ['complex outline search filters unrelated rows', String(cardExpiryOutlinePaths.includes('events.contains')), 'false'],
                  ['complex outline search total', cardExpiryOutline.total, 1],
                  ['complex outline search overflow html', String(cardExpiryOutlineHtml.includes('matching schema entries')), 'true'],
                  ['complex outline search empty html', String(missingOutlineHtml.includes('No schema entries match "missingpath"')), 'true'],
                  ['complex outline search empty status', String(missingOutlineHtml.includes('role="status"')), 'true'],
                  ['complex outline search empty live', String(missingOutlineHtml.includes('aria-live="polite"')), 'true'],
                  ['complex outline search controls', String(schemaSearchHtml.includes('aria-controls="schema-outline-')), 'true'],
                  ['complex outline search described by', String(schemaSearchHtml.includes('aria-describedby="schema-outline-search-')), 'true'],
                  ['complex outline search live meta', String(schemaSearchHtml.includes('data-schema-outline-search-meta') && schemaSearchHtml.includes('aria-live="polite"')), 'true'],
                  ['complex drift path normalized', context.normalizeSchemaDriftPath('#/properties/customer/properties/profile/properties/id'), 'customer.profile.id'],
                  ['complex drift port match', driftIssueForPort.path, 'customer.profile.id'],
                  ['complex drift saved type', driftIssueForPort.savedType, 'string'],
                  ['complex drift current type', driftIssueForPort.currentType, 'integer'],
                  ['complex drift transition label', driftTransitionLabel, 'string -> integer'],
                  ['complex drift review hint', driftReviewHint, 'Review bindings before rebase.'],
                  ['complex drift change keyword', driftIssueForPort.schemaChanges[0].keyword, 'type'],
                  ['complex drift change summary', driftChangeSummary, 'type: string -> integer'],
                  ['complex drift preview saved type', driftIssueForPort.schemaPreview.savedSchema.type, 'string'],
                  ['complex drift preview current minimum', driftIssueForPort.schemaPreview.currentSchema.minimum, 1],
                  ['complex drift preview search text', String(driftPreviewSearchText.includes('frozen schema') && driftPreviewSearchText.includes('"minimum": 1')), 'true'],
                  ['complex drift detail label', driftDetailLabel, 'customer.profile.id: id type narrowed · type: string -> integer · Review bindings before rebase.'],
                  ['complex drift outline search total', driftOutline.total, 3],
                  ['complex drift outline type search path', String(driftOutlineByType.rows.some((row) => row.path === 'customer.profile.id')), 'true'],
                  ['complex drift outline keyword search path', String(driftOutlineByKeyword.rows.some((row) => row.path === 'customer.profile.id')), 'true'],
                  ['complex drift outline preview search path', String(driftOutlineByPreview.rows.some((row) => row.path === 'customer.profile.id')), 'true'],
                  ['complex drift outline review search path', String(driftOutlineByReview.rows.some((row) => row.path === 'customer.profile.id')), 'true'],
                  ['complex drift outline search path', String(driftOutlinePaths.includes('customer.profile.id')), 'true'],
                  ['complex drift outline issue attached', driftLeafRow.driftIssues[0].message, 'id type narrowed'],
                  ['complex drift outline transition attached', context.schemaDriftTypeTransitionLabel(driftLeafRow.driftIssues[0]), 'string -> integer'],
                  ['complex drift outline change attached', context.schemaDriftIssueChangeSummary(driftLeafRow.driftIssues[0]), 'type: string -> integer'],
                  ['complex drift outline html class', String(driftOutlineHtml.includes('data-schema-drift="error"')), 'true'],
                  ['complex drift outline html label', String(driftOutlineHtml.includes('Schema drift · Breaking: id type narrowed')), 'true'],
                  ['complex drift outline html transition', String(driftOutlineHtml.includes('type: string -&gt; integer')), 'true'],
                  ['complex drift outline html review', String(driftOutlineHtml.includes('Review bindings before rebase.')), 'true'],
                  ['complex drift outline described by', String(driftOutlineHtml.includes('aria-describedby="drift-outline-row-')), 'true'],
                  ['complex drift outline label id', String(driftOutlineHtml.includes('id="drift-outline-row-')), 'true'],
                  ['complex drift summary html', String(driftSummaryHtml.includes('1 breaking drift')), 'true'],
                  ['complex drift summary html transition', String(driftSummaryHtml.includes('type: string -&gt; integer')), 'true'],
                  ['complex drift review html title', String(driftReviewHtml.includes('Schema Review')), 'true'],
                  ['complex drift review html path', String(driftReviewHtml.includes('data-schema-drift-review-path="customer.profile.id"')), 'true'],
                  ['complex drift review html frozen', String(driftReviewHtml.includes('Frozen schema') && driftReviewHtml.includes('&quot;type&quot;: &quot;string&quot;')), 'true'],
                  ['complex drift review html current', String(driftReviewHtml.includes('Current schema') && driftReviewHtml.includes('&quot;minimum&quot;: 1')), 'true'],
                  ['schema rebase queue sorted blocked first', schemaRebaseDecisions[0].queueState, 'blocked'],
                  ['schema rebase eligible nodes', schemaRebaseEligibleNodes, 'riskEligibility|riskAudit'],
                  ['schema rebase queue summary', schemaRebaseSummary, '3 decisions · 2 rebaseable · 1 repair review · 1 blocked'],
                  ['schema rebase queue html title', String(schemaRebaseQueueHtml.includes('Schema Rebase Queue')), 'true'],
                  ['schema rebase queue bulk action', String(schemaRebaseQueueHtml.includes('data-schema-rebase-bulk') && schemaRebaseQueueHtml.includes('Rebase 2')), 'true'],
                  ['schema rebase queue repair state', String(schemaRebaseQueueHtml.includes('data-schema-rebase-decision-state="repair-review"')), 'true'],
                  ['schema rebase queue path', String(schemaRebaseQueueHtml.includes('input.inputs.score')), 'true'],
                  ['schema rebase queue blocked reason', String(schemaRebaseQueueHtml.includes('current operator is unavailable in the catalog')), 'true'],
                  ['selected schema rebase decision node', selectedSchemaRebaseDecision.nodeId, 'riskEligibility'],
                  ['selected schema rebase queue no bulk', String(selectedSchemaRebaseHtml.includes('Schema Rebase Queue') && !selectedSchemaRebaseHtml.includes('data-schema-rebase-bulk')), 'true'],
                  ['union library input summary', unionLibraryProfile.operators[0].inputUnionSummary, 'inputs.decision oneOf<integer|string>, inputs.events[] anyOf<boolean|null>'],
                  ['union library config summary', unionLibraryProfile.operators[0].configUnionSummary, '(root) oneOf<object|null>'],
                  ['union library html input branch', String(unionLibraryProfileHtml.includes('in union inputs.decision oneOf&lt;integer|string&gt;')), 'true'],
                  ['union library html config branch', String(unionLibraryProfileHtml.includes('config union (root) oneOf&lt;object|null&gt;')), 'true'],
                  ['source union issue', sourceUnionIssue, 'source oneOf branch 1 cannot feed target: source type string cannot feed target type integer'],
                  ['target anyOf compatible', targetAnyOfIssue, ''],
                  ['target oneOf ambiguous', targetOneOfIssue, 'target oneOf is ambiguous because source is compatible with 2 compatible branches'],
                  ['target conditional compatible', targetConditionalCompatible, ''],
                  ['target conditional issue', targetConditionalIssue, 'source enum value(s) [NOPE] do not match target conditional schema'],
                  ['selected branch compatible', selectedBranchIssue, ''],
                  ['wrong selected branch issue', wrongBranchIssue, 'source type integer cannot feed target type string'],
                  ['branch option labels', branchOptions, 'oneOf[0] integer|oneOf[1] string'],
                  ['branch selection value', branchValue, 'oneOf:1'],
                  ['branch selection parse keyword', parsedBranch.keyword, 'oneOf'],
                  ['branch selection parse index', parsedBranch.index, 1]
                ];

                for (const [label, actual, expected] of checks) {
                  if (actual !== expected) {
                    throw new Error(`${label}: expected ${expected}, got ${actual}`);
                  }
                }
                console.log('browser union schema probe passed');
                """;
    }

    private static String schemaCompatibilityProbe() {
        return """
                const fs = require('fs');
                const vm = require('vm');
                const source = fs.readFileSync(process.argv[2], 'utf8');
                new vm.Script(source, { filename: 'app.js' });

                function functionSource(name) {
                  const start = source.indexOf(`function ${name}(`);
                  if (start < 0) throw new Error(`missing function ${name}`);
                  const openParen = source.indexOf('(', start);
                  let parenDepth = 0;
                  let brace = -1;
                  for (let i = openParen; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '(') parenDepth += 1;
                    if (ch === ')') {
                      parenDepth -= 1;
                      if (parenDepth === 0) {
                        brace = source.indexOf('{', i + 1);
                        break;
                      }
                    }
                  }
                  if (brace < 0) throw new Error(`missing body for function ${name}`);
                  let depth = 0;
                  for (let i = brace; i < source.length; i += 1) {
                    const ch = source[i];
                    if (ch === '{') depth += 1;
                    if (ch === '}') {
                      depth -= 1;
                      if (depth === 0) return source.slice(start, i + 1);
                    }
                  }
                  throw new Error(`unterminated function ${name}`);
                }

                const context = vm.createContext({ console });
                context.SUPPORTED_SCHEMA_UNION_KEYWORDS = ['oneOf', 'anyOf'];
                context.SUPPORTED_SCHEMA_CONDITIONAL_KEYWORDS = ['if', 'then', 'else'];
                context.SUPPORTED_SCHEMA_STRING_FORMATS = new Set(['date', 'date-time', 'duration', 'email', 'uri', 'uuid']);

                for (const name of [
                  'schemaCompatibilityIssue',
                  'sourceAllOfCompatibilityIssue',
                  'branchCanProveAllOfSourceCompatibility',
                  'targetAllOfCompatibilityIssue',
                  'sourceUnionCompatibilityIssue',
                  'targetUnionCompatibilityIssue',
                  'targetConditionalCompatibilityIssue',
                  'conditionalBranchIssue',
                  'unionBaseCompatibilityIssue',
                  'targetFiniteDomainCompatibilityIssue',
                  'targetFiniteDomainLabel',
                  'sourceFiniteDomainLabel',
                  'sourceDomainKind',
                  'targetNotCompatibilityIssue',
                  'schemasDefinitelyDisjoint',
                  'schemaTypesOverlap',
                  'effectiveSchemaType',
                  'compatibilitySchemaType',
                  'schemaHasAnyKeyword',
                  'excludedSchemaLabel',
                  'numericRangesDisjoint',
                  'upperBoundIsBelowLower',
                  'lowerBoundIsAboveUpper',
                  'longRangesDisjoint',
                  'arraySchemasDefinitelyDisjoint',
                  'sourceItemsCannotMatchContains',
                  'objectSchemasDefinitelyDisjoint',
                  'sourcePropertyConstraintsDisjointFrom',
                  'propertyConstraintCompatibilityIssue',
                  'sourcePropertyConstraintsFor',
                  'propertyConstraintsFor',
                  'numericIntegerCompatibilityIssue',
                  'objectSchemaCompatibilityIssue',
                  'objectOptionalTargetPropertiesCompatibilityIssue',
                  'objectPatternPropertiesCompatibilityIssue',
                  'sourcePatternCompatibleWithAllTargetPatterns',
                  'objectPropertyNamesCompatibilityIssue',
                  'objectDependentRequiredCompatibilityIssue',
                  'objectDependentSchemasCompatibilityIssue',
                  'schemaDependentRequired',
                  'schemaDependentSchemas',
                  'sourceDependentSchemaRequiresProperty',
                  'sourceSchemaAssumingDependentTriggerPresent',
                  'effectiveDependentObjectSchema',
                  'objectPropertyBoundsCompatibilityIssue',
                  'arrayUnevaluatedItemsCompatibilityIssue',
                  'arrayContainsCompatibilityIssue',
                  'containsBoundsSatisfyTarget',
                  'inferContainsMatchBounds',
                  'schemaType',
                  'schemaAllOfLabel',
                  'schemaUnionLabel',
                  'schemaUnionBranches',
                  'schemaAllOfBranches',
                  'schemaWithoutUnions',
                  'schemaWithoutConditionals',
                  'schemaWithoutCombinator',
                  'schemaWithoutCombinators',
                  'effectiveConditionalSchema',
                  'effectiveConditionalValidationSchema',
                  'schemaObjectProperties',
                  'schemaPatternProperties',
                  'matchingPatternPropertySchemas',
                  'patternMatches',
                  'schemaPropertyNameSchema',
                  'effectivePropertyNameSchema',
                  'schemaRequiredNames',
                  'sourceCannotContainProperty',
                  'sourcePropertyNamesRejectProperty',
                  'residualPropertiesPolicy',
                  'residualPropertiesKeyword',
                  'appendCompatibilityPath',
                  'reasonAt',
                  'valueDomainLabel',
                  'schemaEnumValues',
                  'finiteSchemaValues',
                  'uniqueSchemaValues',
                  'schemaValuesEqual',
                  'schemaValueKey',
                  'canonicalSchemaValueKey',
                  'schemaValueMatchesSchema',
                  'schemaValueMatchesNot',
                  'schemaValueMatchesEffectiveNotSchema',
                  'effectiveNotValueSchema',
                  'schemaValueMatchesUnions',
                  'schemaValueMatchesAllOf',
                  'schemaValueMatchesConditional',
                  'schemaValueMatchesType',
                  'rawSchemaType',
                  'nullableTypePrimary',
                  'schemaMayProduceNull',
                  'schemaAllowsNull',
                  'schemaTypeForValue',
                  'schemaLowerBound',
                  'schemaUpperBound',
                  'numericBoundary',
                  'numericBoundaryValue',
                  'numericLowerLabel',
                  'numericUpperLabel',
                  'trimNumericLabel',
                  'schemaMinLength',
                  'schemaMaxLength',
                  'stringLengthBoundaryValue',
                  'stringCodePointLength',
                  'schemaMinItems',
                  'schemaMaxItems',
                  'explicitSchemaMinItems',
                  'explicitSchemaMaxItems',
                  'arrayItemBoundsCompatibilityIssue',
                  'schemaPrefixItems',
                  'schemaItemsSchema',
                  'residualArrayItemSchema',
                  'unevaluatedArrayItemsPolicy',
                  'arrayValueMatchesItemsPolicy',
                  'arrayValueMatchesUnevaluatedItems',
                  'schemaContainsSchema',
                  'schemaMinContains',
                  'schemaMaxContains',
                  'arrayItemBoundaryValue',
                  'schemaMinProperties',
                  'schemaMaxProperties',
                  'explicitSchemaMinProperties',
                  'explicitSchemaMaxProperties',
                  'objectPropertyBoundaryValue',
                  'numericType',
                  'numericMultipleOfValue',
                  'numericValueIsMultipleOf',
                  'numberLabel',
                  'stringType',
                  'arrayType',
                  'schemaFormatValue',
                  'stringValueMatchesPattern',
                  'schemaPatternValue'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }

                context.numericBoundsCompatibilityIssue = () => '';
                context.numericMultipleOfCompatibilityIssue = () => '';
                context.stringFormatCompatibilityIssue = () => '';
                context.stringPatternCompatibilityIssue = () => '';
                context.stringLengthCompatibilityIssue = () => '';
                context.arrayPrefixItemsCompatibilityIssue = () => '';
                context.arrayItemsCompatibilityIssue = () => '';
                context.arrayUniqueItemsCompatibilityIssue = () => '';
                context.numericValueMatchesBounds = () => true;
                context.numericValueMatchesMultipleOf = () => true;
                context.stringValueMatchesLengthBounds = () => true;
                context.stringValueMatchesFormat = () => true;
                context.arrayValueMatchesSchema = () => true;
                context.objectValueMatchesSchema = () => true;
                context.objectPropertyBoundsCompatibilityIssue = () => '';

                const requiredOnlySafeIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  required: ['traceId'],
                  additionalProperties: true
                }, {
                  type: 'object',
                  required: ['traceId'],
                  additionalProperties: true
                });
                const requiredOnlyTargetConstrainedIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  required: ['traceId'],
                  additionalProperties: true
                }, {
                  type: 'object',
                  properties: {
                    traceId: { type: 'string' }
                  },
                  required: ['traceId'],
                  additionalProperties: true
                });
                const requiredPatternConstrainedSafeIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  required: ['risk_score'],
                  patternProperties: {
                    '^risk_': { type: 'integer' }
                  },
                  additionalProperties: false
                }, {
                  type: 'object',
                  properties: {
                    risk_score: { type: 'number' }
                  },
                  required: ['risk_score'],
                  additionalProperties: true
                });
                const typelessTargetRequiredMissingIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  additionalProperties: true
                }, {
                  required: ['traceId'],
                  additionalProperties: true
                });
                const typelessTargetResidualMismatchIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  additionalProperties: { type: 'integer' }
                }, {
                  additionalProperties: { type: 'string' }
                });
                const typelessSourceResidualMismatchIssue = context.schemaCompatibilityIssue({
                  additionalProperties: { type: 'integer' }
                }, {
                  type: 'object',
                  additionalProperties: { type: 'string' }
                });

                const dependentRequiredProvesDependentSchemaIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    creditCard: { type: 'string' },
                    billingAddress: { type: 'string' }
                  },
                  dependentRequired: {
                    creditCard: ['billingAddress']
                  },
                  additionalProperties: false
                }, {
                  type: 'object',
                  properties: {
                    creditCard: { type: 'string' },
                    billingAddress: { type: 'string' }
                  },
                  dependentSchemas: {
                    creditCard: {
                      properties: {
                        billingAddress: { type: 'string' }
                      },
                      required: ['billingAddress']
                    }
                  },
                  additionalProperties: false
                });
                const dependentRequiredRejectsDependentSchemaIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    creditCard: { type: 'string' },
                    billingAddress: { type: 'integer' }
                  },
                  dependentRequired: {
                    creditCard: ['billingAddress']
                  },
                  additionalProperties: false
                }, {
                  type: 'object',
                  properties: {
                    creditCard: { type: 'string' }
                  },
                  dependentSchemas: {
                    creditCard: {
                      properties: {
                        billingAddress: { type: 'string' }
                      },
                      required: ['billingAddress']
                    }
                  },
                  additionalProperties: true
                });
                const triggeredRequired = context.sourceSchemaAssumingDependentTriggerPresent({
                  type: 'object',
                  required: ['customerId'],
                  dependentRequired: {
                    creditCard: ['billingAddress']
                  }
                }, 'creditCard').required.sort().join('|');
                const dependentSchemaProvesDependentRequiredIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    paymentMethod: { type: 'string' },
                    cardNumber: { type: 'string' }
                  },
                  dependentSchemas: {
                    paymentMethod: { required: ['cardNumber'] }
                  },
                  additionalProperties: false
                }, {
                  type: 'object',
                  properties: {
                    paymentMethod: { type: 'string' },
                    cardNumber: { type: 'string' }
                  },
                  dependentRequired: {
                    paymentMethod: ['cardNumber']
                  },
                  additionalProperties: false
                });
                const dependentSchemaMissingRequiredIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    paymentMethod: { type: 'string' },
                    cardNumber: { type: 'string' }
                  },
                  dependentSchemas: {
                    paymentMethod: {
                      properties: {
                        cardNumber: { type: 'string' }
                      }
                    }
                  },
                  additionalProperties: false
                }, {
                  type: 'object',
                  properties: {
                    paymentMethod: { type: 'string' },
                    cardNumber: { type: 'string' }
                  },
                  dependentRequired: {
                    paymentMethod: ['cardNumber']
                  },
                  additionalProperties: false
                });
                const sourceDependentSchemaRequiresCard = context.sourceDependentSchemaRequiresProperty({
                  type: 'object',
                  dependentSchemas: {
                    paymentMethod: { required: ['cardNumber'] }
                  }
                }, 'paymentMethod', 'cardNumber');
                const dependentSchemaResidualSafeIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    riskFlag: { type: 'string' }
                  },
                  additionalProperties: { type: 'string' }
                }, {
                  type: 'object',
                  dependentSchemas: {
                    riskFlag: {
                      additionalProperties: { type: 'string' }
                    }
                  }
                });
                const dependentSchemaResidualMismatchIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    riskFlag: { type: 'string' }
                  },
                  additionalProperties: { type: 'integer' }
                }, {
                  type: 'object',
                  dependentSchemas: {
                    riskFlag: {
                      additionalProperties: { type: 'string' }
                    }
                  }
                });

                context.objectDependentRequiredCompatibilityIssue = () => '';
                context.objectDependentSchemasCompatibilityIssue = () => '';

                const target = {
                  type: 'object',
                  properties: {
                    score: { type: 'integer' }
                  },
                  additionalProperties: true
                };
                const residualIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  additionalProperties: { type: 'string' }
                }, target);
                const patternIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  patternProperties: {
                    '^score$': { type: 'string' }
                  },
                  additionalProperties: false
                }, target);
                const samePatternValueSafeIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  patternProperties: {
                    '^meta\\\\.': { type: 'string' }
                  },
                  additionalProperties: false
                }, {
                  type: 'object',
                  patternProperties: {
                    '^meta\\\\.': { type: 'string' }
                  },
                  additionalProperties: false
                });
                const samePatternValueMismatchIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  patternProperties: {
                    '^meta\\\\.': { type: 'string' }
                  },
                  additionalProperties: false
                }, {
                  type: 'object',
                  patternProperties: {
                    '^meta\\\\.': { type: 'integer' }
                  },
                  additionalProperties: false
                });
                const sourceOnlyPatternForbiddenIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  patternProperties: {
                    '^debug_': { type: 'string' }
                  },
                  additionalProperties: false
                }, {
                  type: 'object',
                  additionalProperties: false
                });
                const excludedByPropertyNamesIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  propertyNames: { pattern: '^meta\\\\.' },
                  additionalProperties: { type: 'string' }
                }, target);
                const targetNotIssue = context.schemaCompatibilityIssue({
                  type: 'string'
                }, {
                  type: 'string',
                  not: { const: 'ARCHIVED' }
                });
                const finiteNotSafeIssue = context.schemaCompatibilityIssue({
                  type: 'string',
                  enum: ['ACTIVE']
                }, {
                  type: 'string',
                  not: { const: 'ARCHIVED' }
                });
                const finiteNotExcludedIssue = context.schemaCompatibilityIssue({
                  type: 'string',
                  enum: ['ARCHIVED']
                }, {
                  type: 'string',
                  not: { const: 'ARCHIVED' }
                });
                const targetConstIssue = context.schemaCompatibilityIssue({
                  type: 'string'
                }, {
                  type: 'string',
                  const: 'APPROVE'
                });
                const targetConstSafeIssue = context.schemaCompatibilityIssue({
                  type: 'string',
                  const: 'APPROVE'
                }, {
                  type: 'string',
                  const: 'APPROVE'
                });
                const targetConstMismatchIssue = context.schemaCompatibilityIssue({
                  type: 'string',
                  const: 'REJECT'
                }, {
                  type: 'string',
                  const: 'APPROVE'
                });
                const reorderedObjectConstMatches = context.schemaValueMatchesSchema({
                  b: [1],
                  a: 'x'
                }, {
                  type: 'object',
                  const: {
                    a: 'x',
                    b: [1]
                  }
                });
                const reorderedObjectUniqueCount = context.uniqueSchemaValues([
                  { b: 1, a: 'x' },
                  { a: 'x', b: 1 }
                ]).length;
                const numberToIntegerIssue = context.schemaCompatibilityIssue({
                  type: 'number'
                }, {
                  type: 'integer'
                });
                const integralNumberToIntegerIssue = context.schemaCompatibilityIssue({
                  type: 'number',
                  multipleOf: 1
                }, {
                  type: 'integer'
                });
                const fractionalNumberToIntegerIssue = context.schemaCompatibilityIssue({
                  type: 'number',
                  multipleOf: 0.5
                }, {
                  type: 'integer'
                });
                const targetNotPatternIssue = context.schemaCompatibilityIssue({
                  type: 'string',
                  enum: ['ACTIVE', 'ARCHIVED']
                }, {
                  type: 'string',
                  not: { pattern: '^ARCHIVED$' }
                });
                const targetNotPatternSafeIssue = context.schemaCompatibilityIssue({
                  type: 'string',
                  enum: ['ACTIVE']
                }, {
                  type: 'string',
                  not: { pattern: '^ARCHIVED$' }
                });
                const targetOnlyNotStringIssue = context.schemaCompatibilityIssue({
                  type: 'string'
                }, {
                  not: { type: 'string' }
                });
                const targetNotNumericDisjointIssue = context.schemaCompatibilityIssue({
                  type: 'number',
                  maximum: -1
                }, {
                  type: 'number',
                  not: { minimum: 0 }
                });
                const targetNotNumericOverlapIssue = context.schemaCompatibilityIssue({
                  type: 'number',
                  maximum: 10
                }, {
                  type: 'number',
                  not: { minimum: 0 }
                });
                const targetNotStringLengthDisjointIssue = context.schemaCompatibilityIssue({
                  type: 'string',
                  minLength: 4
                }, {
                  type: 'string',
                  not: { maxLength: 3 }
                });
                const targetNotArrayCountDisjointIssue = context.schemaCompatibilityIssue({
                  type: 'array',
                  maxItems: 1
                }, {
                  type: 'array',
                  not: { minItems: 2 }
                });
                const targetNotArrayContainsDisjointIssue = context.schemaCompatibilityIssue({
                  type: 'array',
                  items: { type: 'string', enum: ['GOOD', 'OK'] }
                }, {
                  type: 'array',
                  not: { contains: { const: 'BAD' } }
                });
                const targetNotArrayPrefixContainsDisjointIssue = context.schemaCompatibilityIssue({
                  type: 'array',
                  prefixItems: [{ type: 'integer', maximum: 0 }],
                  maxItems: 1
                }, {
                  type: 'array',
                  not: { contains: { minimum: 1 } }
                });
                const targetNotArrayContainsAdditionalItemIssue = context.schemaCompatibilityIssue({
                  type: 'array',
                  prefixItems: [{ type: 'string', enum: ['GOOD'] }]
                }, {
                  type: 'array',
                  not: { contains: { const: 'BAD' } }
                });
                const targetNotObjectCountDisjointIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  maxProperties: 1
                }, {
                  type: 'object',
                  not: { minProperties: 2 }
                });
                const targetObjectRequiredMinPropertiesSafe = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    customerId: { type: 'string' },
                    score: { type: 'integer' }
                  },
                  required: ['customerId', 'score']
                }, {
                  type: 'object',
                  minProperties: 2
                });
                const targetNotObjectRequiredDisjointIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    publicId: { type: 'string' }
                  },
                  required: ['publicId'],
                  additionalProperties: false
                }, {
                  type: 'object',
                  not: { required: ['debug'] }
                });
                const targetNotObjectRequiredConstDisjointIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    mode: { type: 'string', enum: ['user', 'guest'] }
                  },
                  additionalProperties: false
                }, {
                  type: 'object',
                  not: {
                    required: ['mode'],
                    properties: {
                      mode: { const: 'admin' }
                    }
                  }
                });
                const targetNotObjectPropertyNamesDisjointIssue = context.schemaCompatibilityIssue({
                  type: 'object',
                  properties: {
                    'public.id': { type: 'string' }
                  },
                  required: ['public.id'],
                  additionalProperties: false
                }, {
                  type: 'object',
                  not: {
                    propertyNames: { pattern: '^internal\\\\.' }
                  }
                });
                const targetAllOfSafeIssue = context.schemaCompatibilityIssue({
                  type: 'string',
                  enum: ['APPROVE']
                }, {
                  type: 'string',
                  allOf: [
                    { type: 'string', enum: ['APPROVE', 'REJECT'] },
                    { not: { const: 'ARCHIVED' } }
                  ]
                });
                const targetAllOfRejectedIssue = context.schemaCompatibilityIssue({
                  type: 'string'
                }, {
                  type: 'string',
                  allOf: [
                    { type: 'string', enum: ['APPROVE'] }
                  ]
                });
                const sourceAllOfSafeIssue = context.schemaCompatibilityIssue({
                  allOf: [
                    { type: 'string', enum: ['APPROVE'] },
                    { minLength: 3 }
                  ]
                }, {
                  type: 'string'
                });
                const sourceAllOfRejectedIssue = context.schemaCompatibilityIssue({
                  allOf: [
                    { minLength: 3 },
                    { pattern: '^[A-Z]+$' }
                  ]
                }, {
                  type: 'string'
                });
                const allOfValueAccepted = context.schemaValueMatchesSchema('APPROVE', {
                  allOf: [
                    { type: 'string' },
                    { enum: ['APPROVE', 'REJECT'] },
                    { not: { const: 'ARCHIVED' } }
                  ]
                });
                const allOfValueRejected = context.schemaValueMatchesSchema('ARCHIVED', {
                  allOf: [
                    { type: 'string' },
                    { not: { const: 'ARCHIVED' } }
                  ]
                });
                const targetUnevaluatedItemsIssue = context.schemaCompatibilityIssue({
                  type: 'array',
                  prefixItems: [{ type: 'string' }],
                  items: { type: 'string' }
                }, {
                  type: 'array',
                  prefixItems: [{ type: 'string' }],
                  unevaluatedItems: false
                });
                const targetUnevaluatedItemsSafe = context.schemaCompatibilityIssue({
                  type: 'array',
                  prefixItems: [{ type: 'string' }],
                  unevaluatedItems: false
                }, {
                  type: 'array',
                  prefixItems: [{ type: 'string' }],
                  unevaluatedItems: false
                });
                const targetItemsFalseIssue = context.schemaCompatibilityIssue({
                  type: 'array',
                  prefixItems: [{ type: 'string' }],
                  items: { type: 'string' }
                }, {
                  type: 'array',
                  prefixItems: [{ type: 'string' }],
                  items: false
                });
                const targetItemsFalseSafe = context.schemaCompatibilityIssue({
                  type: 'array',
                  prefixItems: [{ type: 'string' }],
                  items: false
                }, {
                  type: 'array',
                  prefixItems: [{ type: 'string' }],
                  items: false
                });
                const targetContainsFromItemsSafe = context.schemaCompatibilityIssue({
                  type: 'array',
                  items: { type: 'string' },
                  minItems: 2
                }, {
                  type: 'array',
                  items: true,
                  contains: { type: 'string' },
                  minContains: 2
                });
                const targetContainsFromPrefixSafe = context.schemaCompatibilityIssue({
                  type: 'array',
                  prefixItems: [
                    { type: 'string', const: 'primary' },
                    { type: 'integer' }
                  ],
                  items: false,
                  minItems: 1
                }, {
                  type: 'array',
                  items: true,
                  contains: { type: 'string', const: 'primary' },
                  minContains: 1
                });
                const targetMaxContainsDisjointSafe = context.schemaCompatibilityIssue({
                  type: 'array',
                  items: { type: 'integer' },
                  maxItems: 5
                }, {
                  type: 'array',
                  items: true,
                  contains: { type: 'string' },
                  minContains: 0,
                  maxContains: 0
                });

                const checks = [
                  ['required-only object field safe', requiredOnlySafeIssue, ''],
                  ['required-only target constrained rejected', String(requiredOnlyTargetConstrainedIssue.includes("source object guarantees required field 'traceId' but does not constrain it")), 'true'],
                  ['required field constrained by source pattern safe', requiredPatternConstrainedSafeIssue, ''],
                  ['typeless target required rejected', String(typelessTargetRequiredMissingIssue.includes("source object does not guarantee required field 'traceId'")), 'true'],
                  ['typeless target residual rejected', String(typelessTargetResidualMismatchIssue.includes('source type integer cannot feed target type string')), 'true'],
                  ['typeless source residual rejected', String(typelessSourceResidualMismatchIssue.includes('source type integer cannot feed target type string')), 'true'],
                  ['dependentRequired proves dependentSchemas safe', dependentRequiredProvesDependentSchemaIssue, ''],
                  ['dependentRequired dependentSchemas mismatch surfaced', String(dependentRequiredRejectsDependentSchemaIssue.includes("target requires dependentSchemas 'creditCard'")), 'true'],
                  ['dependent trigger required projection', triggeredRequired, 'billingAddress|creditCard|customerId'],
                  ['dependentSchemas proves dependentRequired safe', dependentSchemaProvesDependentRequiredIssue, ''],
                  ['dependentSchemas missing required rejected', String(dependentSchemaMissingRequiredIssue.includes("target requires dependentRequired 'paymentMethod' -> 'cardNumber'")), 'true'],
                  ['dependentSchemas required helper', String(sourceDependentSchemaRequiresCard), 'true'],
                  ['dependentSchemas residual safe', dependentSchemaResidualSafeIssue, ''],
                  ['dependentSchemas residual mismatch rejected', String(dependentSchemaResidualMismatchIssue.includes("target requires dependentSchemas 'riskFlag'")), 'true'],
                  ['residual optional collision path', String(residualIssue.includes("at 'score'")), 'true'],
                  ['residual optional collision type', String(residualIssue.includes('source type string cannot feed target type integer')), 'true'],
                  ['pattern optional collision path', String(patternIssue.includes("at 'score'")), 'true'],
                  ['pattern optional collision type', String(patternIssue.includes('source type string cannot feed target type integer')), 'true'],
                  ['same pattern dynamic value safe', samePatternValueSafeIssue, ''],
                  ['same pattern dynamic value mismatch', String(samePatternValueMismatchIssue.includes('source type string cannot feed target type integer')), 'true'],
                  ['source-only pattern forbidden by target', String(sourceOnlyPatternForbiddenIssue.includes("source patternProperties '^debug_'")), 'true'],
                  ['propertyNames excluded optional collision', excludedByPropertyNamesIssue, ''],
                  ['target finite not possible issue', targetNotIssue, 'target excludes value(s) [ARCHIVED] but source schema could produce them'],
                  ['target finite not safe enum', finiteNotSafeIssue, ''],
                  ['target finite not excluded enum', finiteNotExcludedIssue, 'source enum value(s) [ARCHIVED] do not match target schema string'],
                  ['target const requires finite source', targetConstIssue, 'target const [APPROVE] requires a finite source value domain, but source is string'],
                  ['target const safe source', targetConstSafeIssue, ''],
                  ['target const mismatch', targetConstMismatchIssue, 'source const value(s) [REJECT] are outside target const [APPROVE]'],
                  ['reordered object schema equality', String(context.schemaValuesEqual({ b: [1], a: 'x' }, { a: 'x', b: [1] })), 'true'],
                  ['reordered object const match', String(reorderedObjectConstMatches), 'true'],
                  ['reordered object unique collapse', String(reorderedObjectUniqueCount), '1'],
                  ['number to integer requires proof', numberToIntegerIssue, 'target type integer requires integer-valued source, but source type number has no integral multipleOf'],
                  ['integral number to integer safe', integralNumberToIntegerIssue, ''],
                  ['fractional number to integer blocked', fractionalNumberToIntegerIssue, 'source multipleOf 0.5 does not guarantee integer values required by target type integer'],
                  ['target not pattern excludes enum', targetNotPatternIssue, 'source enum value(s) [ARCHIVED] do not match target schema string'],
                  ['target not pattern safe enum', targetNotPatternSafeIssue, ''],
                  ['target only not string blocks source', targetOnlyNotStringIssue, 'target excludes schema string but source string cannot prove it avoids the excluded domain'],
                  ['target not numeric disjoint safe', targetNotNumericDisjointIssue, ''],
                  ['target not numeric overlap blocked', targetNotNumericOverlapIssue, 'target excludes schema number value >= 0 but source number cannot prove it avoids the excluded domain'],
                  ['target not string length disjoint safe', targetNotStringLengthDisjointIssue, ''],
                  ['target not array count disjoint safe', targetNotArrayCountDisjointIssue, ''],
                  ['target not array contains disjoint safe', targetNotArrayContainsDisjointIssue, ''],
                  ['target not array prefix contains disjoint safe', targetNotArrayPrefixContainsDisjointIssue, ''],
                  ['target not array contains additional item blocked', targetNotArrayContainsAdditionalItemIssue, 'target excludes schema array contains [BAD] minContains 1 but source array cannot prove it avoids the excluded domain'],
                  ['target not object count disjoint safe', targetNotObjectCountDisjointIssue, ''],
                  ['target object minProperties inferred from required safe', targetObjectRequiredMinPropertiesSafe, ''],
                  ['target not object required property disjoint safe', targetNotObjectRequiredDisjointIssue, ''],
                  ['target not object required const disjoint safe', targetNotObjectRequiredConstDisjointIssue, ''],
                  ['target not object propertyNames disjoint safe', targetNotObjectPropertyNamesDisjointIssue, ''],
                  ['target allOf safe', targetAllOfSafeIssue, ''],
                  ['target allOf rejected', targetAllOfRejectedIssue, 'target allOf branch 0 is not compatible: target enum [APPROVE] requires a finite source enum domain, but source is string'],
                  ['source allOf safe', sourceAllOfSafeIssue, ''],
                  ['source allOf rejected', String(sourceAllOfRejectedIssue.includes('source allOf has no constituent that can prove compatibility with target')), 'true'],
                  ['allOf accepted value', String(allOfValueAccepted), 'true'],
                  ['allOf rejected value', String(allOfValueRejected), 'false'],
                  ['target unevaluatedItems blocks residual source', targetUnevaluatedItemsIssue, 'target unevaluatedItems=false allows no residual array items but source may produce items beyond prefixItems[1]'],
                  ['target unevaluatedItems bounded source safe', targetUnevaluatedItemsSafe, ''],
                  ['target items=false blocks residual source', targetItemsFalseIssue, 'target requires item count <= 1 but source has no maxItems'],
                  ['target items=false bounded source safe', targetItemsFalseSafe, ''],
                  ['target contains inferred from items safe', targetContainsFromItemsSafe, ''],
                  ['target contains inferred from prefixItems safe', targetContainsFromPrefixSafe, ''],
                  ['target maxContains inferred disjoint safe', targetMaxContainsDisjointSafe, '']
                ];

                for (const [label, actual, expected] of checks) {
                  if (actual !== expected) {
                    throw new Error(`${label}: expected ${expected}, got ${actual}`);
                  }
                }
                console.log('browser schema compatibility probe passed');
                """;
    }

    private record ProcessResult(boolean finished, int exitCode, String output) {
    }
}
