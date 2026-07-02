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
    void keepsServerPreflightAuthoritativeForLocallyRejectedConnections() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("connectionServerPreflightMessage(")
                .contains("Asking server for final decision...")
                .contains("Server validation is authoritative.");
        assertThat(source)
                .doesNotContain("if (!compatibility.ok) {\n        setConnectionMessage(compatibility.message, 'error');")
                .doesNotContain("if (!compatibility.ok) {\n          setConnectionMessage(compatibility.message, 'error');")
                .doesNotContain("const disabled = candidate.compatibility.ok ? '' : ' disabled';");
    }

    @Test
    void keepsVisualReadinessAcrossCompileRunAndConnectionPreflightStatusUpdates() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("function visualDraftExecutableActionState(")
                .contains("function composerDslUsesVisualDraft()")
                .contains("function renderExecutableAuthoringControls()")
                .contains("compileButton.disabled = compileState.disabled;")
                .contains("runButton.disabled = usesVisualDraft && runState.disabled;")
                .contains("renderExecutableAuthoringControls();")
                .contains("This graph can be saved, exported, or published as a Design artifact, but it cannot be compiled or run.")
                .contains("status: 'not_executable'")
                .contains("const readinessBeforeCompile = state.visualCheck?.readiness || null;")
                .contains("const executableState = visualDraftExecutableActionState(readinessBeforeCompile);")
                .contains("setVisualCheck('Compiling...', 'info', [], readinessBeforeCompile);")
                .contains("payload.validation?.readiness || readinessBeforeCompile")
                .contains("setVisualCheck(error.message, 'error', [], readinessBeforeCompile);")
                .contains("const readinessBeforeRun = publication.validation?.readiness || state.visualCheck?.readiness || null;")
                .contains("payload.validation?.readiness || readinessBeforeRun")
                .contains("setVisualCheck(error.message, 'error', [], readinessBeforeRun);")
                .contains("const validation = payload.validation || null;")
                .contains("const readiness = validation?.readiness || state.visualCheck?.readiness || null;")
                .contains("if (diagnostics.length || readiness)")
                .contains("Connection accepted; graph still has validation issues.");
    }

    @Test
    void surfacesStoredDraftDependencyReportInDraftPanel() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("draftDependencyReport: null")
                .contains("id=\"draft-dependencies\"")
                .contains("async function loadDraftDependencies(options = {})")
                .contains("/api/visual/drafts/${encodeURIComponent(state.currentDraftId)}/dependencies")
                .contains("function renderDraftDependencyReport()")
                .contains("Draft Dependencies")
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
                .contains("async function validateOperatorLibraryTextPayload(sourceText)")
                .contains("/admin/visual-operator-libraries/validate-text${libraryForceQuery()}")
                .contains("/admin/visual-operator-libraries/import-text${mutationQuery}")
                .contains("headers: { 'Content-Type': 'text/plain' }")
                .contains("libraryImportConfirmationKey(sourceText, validation.diagnostics)")
                .contains("sourceText: String(sourceText || '')")
                .contains("renderLibraryImpactPanel($('library-impact'), diagnostics, state.libraryMessage?.impact)")
                .contains("function renderLibraryImpactPanel(target, diagnostics, impact = null)")
                .contains("function libraryImpactSummaryFromPayload(impact)")
                .contains("function libraryImpactSummary(diagnostics)")
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
    void surfacesOperatorLibraryRevisionHistoryAndRestoreControls() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("id=\"library-history-id\"")
                .contains("id=\"library-revision-select\"")
                .contains("id=\"reload-library-revisions\"")
                .contains("id=\"export-library\"")
                .contains("id=\"import-library-bundle\"")
                .contains("id=\"preview-library-revision\"")
                .contains("id=\"restore-library-revision\"")
                .contains("id=\"library-revision-diff\"")
                .contains("id=\"library-allow-version-regression\"")
                .contains("async function exportSelectedOperatorLibrary()")
                .contains("/admin/visual-operator-libraries/${encodeURIComponent(libraryId)}/export")
                .contains("operatorLibraryExport: payload")
                .contains("Exported ${payload?.sourceLibraryId || libraryId}@${payload?.sourceRevision || 0}.")
                .contains("async function importOperatorLibraryBundle()")
                .contains("/admin/visual-operator-libraries/import-bundle${mutationQuery}")
                .contains("operatorLibraryImportResult: payload")
                .contains("Import Bundle")
                .contains("function loadOperatorLibraryRevisions(options = {})")
                .contains("function loadOperatorLibraryRevisionDiff(options = {})")
                .contains("/diff/${encodeURIComponent(target.revision || 0)}")
                .contains("/admin/visual-operator-libraries/${encodeURIComponent(libraryId)}/revisions")
                .contains("function previewSelectedOperatorLibraryRevision()")
                .contains("function restoreSelectedOperatorLibraryRevision()")
                .contains("/revisions/${encodeURIComponent(revision.revision || 0)}/restore")
                .contains("function renderLibraryRevisionDiff()")
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
                .contains("fetch('/api/visual/drafts/history')")
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

                const context = vm.createContext({ console });
                context.SUPPORTED_SCHEMA_UNION_KEYWORDS = ['oneOf', 'anyOf'];
                for (const name of [
                  'pretty',
                  'escapeHtml',
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
                  'schemaUnionLabel',
                  'schemaType',
                  'schemaEnumValues',
                  'schemaAllowsNull',
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
                  'rawSchemaType',
                  'residualPropertiesPolicy',
                  'additionalPropertySchema',
                  'matchingPatternPropertySchemas',
                  'patternPropertySchema',
                  'schemaPatternProperties',
                  'patternMatches',
                  'validateSchemaStructure',
                  'validateSchemaAdditionalProperties',
                  'validateSchemaUnevaluatedProperties',
                  'validateSchemaObjectPatternProperties',
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
                  'canvasTargetHandlesForNode',
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
                  'renderOpenApiOperationSummary',
                  'renderOpenApiOperationSummaryPanel',
                  'openApiOperationStatusLevel',
                  'openApiOperationStatusMessage',
                  'applyOpenApiOperationSelection',
                  'renderLibraryProfilePanel',
                  'renderLibraryImpactPanel',
                  'libraryImpactSummaryFromPayload',
                  'libraryImpactDraftTargetsFromPayload',
                  'libraryImpactSummary',
                  'libraryImpactRefsFromDiagnostic',
                  'libraryImpactHighestLevel',
                  'libraryImpactSummaryLabel',
                  'libraryImpactRefGroup',
                  'changeRiskLabel',
                  'libraryImpactRiskSummaryText',
                  'operatorLibraryWarningAcknowledgementMessage',
                  'changeRiskRank',
                  'openLibraryImpactDraft',
                  'openLibraryImpactDraftTarget',
                  'uniqueStrings',
                  'uniqueLibraryImpactDraftTargets',
                  'libraryProfileLevel',
                  'libraryProfileFromText',
                  'operatorLibraryProfile',
                  'operatorLibraryOperatorProfile',
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
                  'schemaDynamicSurfaceCount',
                  'normalizeReadinessState',
                  'normalizeVisualGraphNodeReadiness',
                  'normalizeVisualGraphReadiness',
                  'visualGraphReadinessStatusText',
                  'visualGraphReadinessNodeSummary',
                  'publicationReadiness',
                  'publicationReadinessStatusText',
                  'publicationReadinessReviewRows',
                  'publicationReadinessNodeLabel',
                  'publicationReadinessNodeSummary',
                  'publishArtifactKindsForReadiness',
                  'preferredPublishArtifactKind',
                  'publishArtifactKindControlState',
                  'renderPublishArtifactKindControls',
                  'visualCheckStatusLevel',
                  'operatorDiagnosticsForSpec',
                  'operatorPaletteCapabilityBadges',
                  'operatorPaletteCapabilityLabels',
                  'operatorPaletteCapabilityFacetValues',
                  'operatorPaletteReadinessState',
                  'operatorPaletteLoweringMode',
                  'operatorPaletteFacetLabel',
                  'normalizeOperatorCatalogFacets',
                  'operatorCatalogFacetSummary',
                  'operatorRuntimeReadiness',
                  'renderOperatorReadinessPanel',
                  'operatorPaletteDiagnosticBadges',
                  'operatorMatchesPaletteFilter',
                  'paletteSearchTokens',
                  'operatorPaletteSearchValues',
                  'operatorPaletteSchemaSearchValues',
                  'renderOperatorDiagnosticsPanel',
                  'bindingCandidateSummary',
                  'bindingCandidateSummaryLevel',
                  'bindingSourceValue',
                  'renderSourceCandidateOptions',
                  'renderSourceCandidateGroup',
                  'renderSourceCandidateOption',
                  'sourceCandidatesForTarget',
                  'sourceCandidateComparator',
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
                  'builderNodeToDraftNode',
                  'visualDraftEdgeFromBuilderEdge',
                  'builderScope',
                  'currentGraphInputSchema',
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
                  'nodeConnectabilityDisplayTargets',
                  'renderNodeConnectabilityTarget',
                  'connectNodeConnectabilityFromButton',
                  'nodeConnectabilitySourceFromButton',
                  'nodeConnectabilityTargetFromButton',
                  'nodeConnectabilitySummary',
                  'nodeConnectabilitySourceSummaryFor',
                  'nodeConnectabilityTargetsForSource',
                  'nodeConnectabilityTargetAppliesToSource',
                  'nodeConnectabilityTargetKind',
                  'nodeConnectabilityTotalsLabel',
                  'nodeConnectabilitySourceSummary',
                  'nodeConnectabilitySourceLevel',
                  'nodeConnectabilityTargetLevel',
                  'nodeConnectabilityTargetLabel',
                  'nodeConnectabilityTargetTitle',
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

                context.BUILDER_HISTORY_LIMIT = 50;
                context.CONTEXT_SOURCE_ID = '__ctx';
                context.NODE_SIZE = { width: 170, height: 74 };
                context.PUBLISH_ARTIFACT_KINDS = ['EXECUTABLE', 'DESIGN'];
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
                        schema: { fields: [{ path: 'score' }] }
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
                      inputPort: 'mode',
                      outputPort: 'output',
                      ports: [{
                        name: 'mode',
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
                  ports: [{
                    name: 'inputs',
                    schema: { fields: [{ path: 'scores.0.value' }, { path: 'amount' }] }
                  }]
                };
                const defaultInputs = context.defaultInputExpressionsForOperator(arrayInputSpec);
                const customInputs = context.defaultCustomInputStateForOperator(arrayInputSpec);
                const unsafeDefaultInputSpec = {
                  inputPort: 'mode',
                  ports: [{
                    name: 'mode',
                    schema: { fields: [{ path: 'score' }] }
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
                              id: { type: 'string' }
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
                          score: { type: 'integer' }
                        }
                      }
                    }
                  }],
                  configSchema: {
                    schema: {
                      type: 'object',
                      properties: {
                        threshold: { type: 'number' }
                      }
                    }
                  }
                };
                const paletteSchemaSearchValues = context.operatorPaletteSearchValues(paletteSearchSpec);
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
                  loweringModes: { transform: 2, design: 1 }
                });
                const serverCatalogFacetSummary = context.operatorCatalogFacetSummary(serverCatalogFacets);
                const fallbackCatalogFacets = context.normalizeOperatorCatalogFacets(null, [
                  {
                    source: { kind: 'user-library' },
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
                                    id: { type: 'string' },
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
                          properties: { threshold: { type: 'integer' } },
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
                          name: 'mode',
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
                const invalidLibraryProfile = context.libraryProfileFromText('{broken');
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
                context.state.paletteSearch = '';
                context.state.paletteSourceKind = 'user-library';
                const paletteSourceFilterMatch = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteSourceKind = 'resource-descriptor';
                const paletteSourceFilterMiss = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteSourceKind = '';
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
                    metadata: { changeRisk: 'BREAKING_SCHEMA' }
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
                context.canvasTargetHandlesForNode = (node) => {
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
                let quickConnectServerCall = '';
                let quickConnectApplied = '';
                let quickConnectMessage = '';
                let quickConnectMessageLevel = '';
                let quickConnectEditorRenders = 0;
                let quickConnectDiagramRenders = 0;
                context.checkVisualConnectionOnServer = async (source, target) => {
                  quickConnectServerCall = `${context.endpointLabel(source)} -> ${context.endpointLabel(target)}`;
                  return { accepted: true, bindingKey: 'inputs.score', diagnostics: [], message: '' };
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
                  ['graph readiness status text', graphReadinessStatusText, 'Design-only graph · 1 executable, 1 design-only · DESIGN artifact'],
                  ['publication readiness state', publicationReadiness.state, 'design-only'],
                  ['publication readiness status text', publicationReadinessStatusText, 'Design-only graph · 1 executable, 1 design-only · DESIGN artifact'],
                  ['publication readiness review row count', publicationReadinessRows.length, 1],
                  ['publication readiness review row label', publicationReadinessRows[0].label, 'Eligibility Draft · Design only'],
                  ['publication readiness review row value', publicationReadinessRows[0].value, 'risk:eligibility · Design-only operator'],
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
                  ['catalog facet fallback design count', fallbackCatalogFacets.capabilities['design-only'], 1],
                  ['catalog facet fallback durable count', fallbackCatalogFacets.capabilities.durable, 1],
                  ['catalog facet fallback design readiness count', fallbackCatalogFacets.runtimeReadinessStates['design-only'], 1],
                  ['catalog facet fallback blocked readiness count', fallbackCatalogFacets.runtimeReadinessStates['runtime-blocked'], 1],
                  ['palette capability search match', String(paletteCapabilitySearchMatch), 'true'],
                  ['palette source filter match', String(paletteSourceFilterMatch), 'true'],
                  ['palette source filter miss', String(paletteSourceFilterMiss), 'false'],
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
                  ['library profile runtime-blocked operators', libraryProfile.runtimeBlockedOperatorCount, 1],
                  ['library profile governance-review operators', governanceRiskProfile.governanceReviewOperatorCount, 1],
                  ['library profile operator input field count', libraryProfile.operators[0].inputFields.length, 3],
                  ['library profile operator output field count', libraryProfile.operators[0].outputFields.length, 2],
                  ['library profile operator config field count', libraryProfile.operators[0].configFields.length, 1],
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
                  ['library profile html policy summary', String(libraryProfileHtml.includes('policy tenants demo-tenant; namespaces local; env browser')), 'true'],
                  ['library profile policy-only html summary', String(policyOnlyProfileHtml.includes('policy tenants gold, silver, bronze +1; namespaces lending; env prod')), 'true'],
                  ['library profile html includes required input field', String(libraryProfileHtml.includes('inputs.customer.id*')), 'true'],
                  ['library profile html includes unsafe input field', String(libraryProfileHtml.includes('inputs.customer.bad-field !')), 'true'],
                  ['library profile html includes unsafe input port', String(libraryProfileHtml.includes('mode.(root) !')), 'true'],
                  ['library profile html includes unsafe field chip', String(libraryProfileHtml.includes('3 DSL-unsafe fields/ports')), 'true'],
                  ['library profile html includes output field', String(libraryProfileHtml.includes('graph.score* !')), 'true'],
                  ['library profile html includes config field', String(libraryProfileHtml.includes('config threshold')), 'true'],
                  ['library profile html includes dynamic flag', String(libraryProfileHtml.includes('2 dynamic schema surfaces')), 'true'],
                  ['library profile invalid json', String(Boolean(invalidLibraryProfile.parseError)), 'true'],
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
                  ['connectability blocked target title', context.nodeConnectabilityTargetTitle(rootConnectability.blockedTargets[0]), 'Audit (auditNode) · data -> inputs.score · blocked · Type mismatch: object cannot feed integer.'],
                  ['connectability panel includes score chip', String(connectabilityPanel.includes('riskNode.payload.score')), 'true'],
                  ['connectability panel includes blocked chip', String(connectabilityPanel.includes('blocked')), 'true'],
                  ['connectability panel includes blocked reason', String(connectabilityPanel.includes('Type mismatch: object cannot feed integer.')), 'true'],
                  ['connectability panel includes aria label', String(connectabilityPanel.includes('aria-label=')), 'true'],
                  ['connectability panel includes connect action', String(connectabilityPanel.includes('data-connectability-action="connect"')), 'true'],
                  ['connectability quick source', context.endpointLabel(quickConnectSource), 'riskNode.payload.score'],
                  ['connectability quick target', context.endpointLabel(quickConnectTarget), 'auditNode.inputs.score'],
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
                    ['rebase dependency renders', rebaseResult.rebaseDependencyRenders, 0],
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
                    ['rebase conflict dependency renders', rebaseConflict.rebaseDependencyRenders, 1],
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
                    if (String(url).startsWith('/api/visual/drafts/import')) {
                      return {
                        ok: true,
                        status: 201,
                        json: async () => ({
                          schemaVersion: 'bloge.visualGraphDraftImportResult.v1',
                          imported: true,
                          sourceBundleSchemaVersion: 'bloge.visualGraphDraftExport.v1',
                          sourceDraftId: 'draft-risk',
                          sourceRevision: 4,
                          draft: transferDraft,
                          diagnostics: [],
                          validation: {
                            valid: true,
                            diagnostics: [],
                            readiness: transferReadiness
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
                  context.syncGraphInputSchemaTextFromBuilder = () => {};
                  context.syncComposerFromBuilder = () => {};
                  context.renderScenario = () => {
                    transferScenarioRenders += 1;
                  };
                  return context.exportSelectedDraft()
                    .then(() => context.importDraftBundle())
                    .then(() => ({
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
                  const importBody = JSON.parse(transferResult.transferFetches[1].body || '{}');
                  const importUrl = transferResult.transferFetches[1].url;
                  const importQuery = new URLSearchParams(importUrl.split('?')[1] || '');
                  const exportVisualCheck = transferResult.transferVisualChecks[0] || {};
                  const importVisualCheck = transferResult.transferVisualChecks[1] || {};
                  const transferChecks = [
                    ['draft export endpoint', transferResult.transferFetches[0].url, '/api/visual/drafts/draft-risk/export'],
                    ['draft import endpoint', String(importUrl.startsWith('/api/visual/drafts/import?')), 'true'],
                    ['draft import actor', importQuery.get('actor'), 'visual-canvas'],
                    ['draft import source', importQuery.get('changeSource'), 'gateway-browser'],
                    ['draft import summary', importQuery.get('changeSummary'), 'Imported visual draft package from Drafts panel.'],
                    ['draft import reason', importQuery.get('reason'), 'User imported a portable visual graph draft bundle in the browser.'],
                    ['draft import body schema', importBody.schemaVersion, 'bloge.visualGraphDraftExport.v1'],
                    ['draft bundle carries validation', String(transferResult.draftBundleHasValidation), 'true'],
                    ['draft bundle carries dependency report', String(transferResult.draftBundleHasDependencyReport), 'true'],
                    ['draft export message', transferResult.transferDraftMessages[0].text, 'Exported draft-risk@4.'],
                    ['draft export visual readiness', exportVisualCheck.readiness?.state, 'design-only'],
                    ['draft import message', transferResult.transferDraftMessages[1].text, 'Imported draft-imported@1 from draft-risk@4.'],
                    ['draft import visual readiness', importVisualCheck.readiness?.state, 'design-only'],
                    ['draft import current id', transferResult.currentDraftId, 'draft-imported'],
                    ['draft import current revision', transferResult.currentDraftRevision, 1],
                    ['draft import dependency report', transferResult.dependencyReportDraftId, 'draft-imported'],
                    ['draft transfer catalog loads', transferResult.transferCatalogLoads, 1],
                    ['draft transfer draft list loads', transferResult.transferDraftListLoads, 1],
                    ['draft transfer revision loads', transferResult.transferRevisionLoads, 1],
                    ['draft transfer controls render', transferResult.transferDraftControlRenders, 1],
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
                context.UNSUPPORTED_SCHEMA_REFERENCE_KEYWORDS = ['$ref', '$dynamicRef'];
                context.UNSUPPORTED_SCHEMA_COMPOSITION_KEYWORDS = ['allOf', 'not', 'if', 'then', 'else'];
                context.UNSUPPORTED_SCHEMA_CONSTRAINT_KEYWORDS = ['unevaluatedItems'];

                for (const name of [
                  'escapeHtml',
                  'isPlainObject',
                  'validateSchemaEnvelope',
                  'validateSchemaStructure',
                  'validateSchemaTypeArray',
                  'validateSchemaDefinitions',
                  'validateUnsupportedSchemaKeywords',
                  'validateSupportedSchemaUnions',
                  'graphInputSchemaDiagnostic',
                  'schemaType',
                  'schemaUnionLabel',
                  'schemaUnionSummary',
                  'schemaUnionDescriptors',
                  'schemaUnionDescriptorLabel',
                  'schemaUnionBranches',
                  'schemaUnionBranchOptions',
                  'normalizedUnionBranchSelection',
                  'unionBranchSelectionValue',
                  'unionBranchSelectionFromValue',
                  'selectedUnionBranchSchema',
                  'schemaCompatibilityIssueForTargetUnionSelection',
                  'targetSchemaForUnionSelection',
                  'schemaWithoutUnions',
                  'schemaObjectProperties',
                  'schemaItemsSchema',
                  'schemaPrefixItems',
                  'schemaCompatibilityIssue',
                  'sourceUnionCompatibilityIssue',
                  'targetUnionCompatibilityIssue',
                  'unionBaseCompatibilityIssue',
                  'schemaValueMatchesSchema',
                  'schemaValueMatchesUnions',
                  'schemaValueMatchesType',
                  'rawSchemaType',
                  'nullableTypePrimary',
                  'schemaMayProduceNull',
                  'schemaAllowsNull',
                  'schemaTypeForValue',
                  'schemaEnumValues',
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
                  'renderContractPortGroup',
                  'renderLibraryProfilePanel',
                  'libraryProfileLevel',
                  'operatorLibraryProfile',
                  'operatorLibraryOperatorProfile',
                  'emptyOperatorLibraryPortProfile',
                  'addOperatorLibraryPortProfile',
                  'operatorLibraryPortProfile',
                  'operatorLibraryPortFields',
                  'operatorLibraryConfigFields',
                  'operatorLibraryPortUnionSummary',
                  'operatorLibraryInputPortDslPathSafe',
                  'operatorLibraryFieldProfile',
                  'operatorLibrarySchemaSummary',
                  'operatorLibraryFieldLabel'
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
                context.numericBoundsCompatibilityIssue = () => '';
                context.numericMultipleOfCompatibilityIssue = () => '';
                context.stringFormatCompatibilityIssue = () => '';
                context.stringPatternCompatibilityIssue = () => '';
                context.stringLengthCompatibilityIssue = () => '';
                context.arrayPrefixItemsCompatibilityIssue = () => '';
                context.arrayItemsCompatibilityIssue = () => '';
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
                const unsupportedCompositionDiagnostics = [];
                context.validateSchemaStructure({
                  allOf: [{ type: 'string' }]
                }, 'schema', unsupportedCompositionDiagnostics);

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
                      decision: { oneOf: [{ type: 'integer' }, { type: 'string' }] },
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
                const unionContractHtml = context.renderContractPortGroup('Inputs', [{
                  name: 'inputs',
                  schema: unionSchemaEnvelope,
                  required: true
                }]);
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
                  ['allOf remains unsupported', unsupportedCompositionDiagnostics.map((diagnostic) => diagnostic.code).join('|'), 'visual.schema.compositionUnsupported'],
                  ['oneOf type label', context.schemaType({ oneOf: [{ type: 'integer' }, { type: 'string' }] }), 'oneOf<integer|string>'],
                  ['nested anyOf type label', context.schemaType({ type: 'array', items: { anyOf: [{ type: 'boolean' }, { type: 'null' }] } }), 'array<anyOf<boolean|null>>'],
                  ['oneOf exact value', context.schemaValueMatchesSchema('APPROVE', { oneOf: [{ type: 'string', enum: ['APPROVE'] }, { type: 'string', enum: ['REJECT'] }] }), true],
                  ['oneOf missing value', context.schemaValueMatchesSchema('PENDING', { oneOf: [{ type: 'string', enum: ['APPROVE'] }, { type: 'string', enum: ['REJECT'] }] }), false],
                  ['oneOf ambiguous numeric value', context.schemaValueMatchesSchema(3, { oneOf: [{ type: 'integer' }, { type: 'number' }] }), false],
                  ['anyOf matching value', context.schemaValueMatchesSchema(3, { anyOf: [{ type: 'integer' }, { type: 'string' }] }), true],
                  ['anyOf missing value', context.schemaValueMatchesSchema(false, { anyOf: [{ type: 'integer' }, { type: 'string' }] }), false],
                  ['nested union summary', nestedUnionSummary, 'decision oneOf<integer|string>, events[] anyOf<boolean|null>'],
                  ['union contract row html', String(unionContractHtml.includes('decision oneOf&lt;integer|string&gt;, events[] anyOf&lt;boolean|null&gt;')), 'true'],
                  ['union library input summary', unionLibraryProfile.operators[0].inputUnionSummary, 'inputs.decision oneOf<integer|string>, inputs.events[] anyOf<boolean|null>'],
                  ['union library config summary', unionLibraryProfile.operators[0].configUnionSummary, '(root) oneOf<object|null>'],
                  ['union library html input branch', String(unionLibraryProfileHtml.includes('in union inputs.decision oneOf&lt;integer|string&gt;')), 'true'],
                  ['union library html config branch', String(unionLibraryProfileHtml.includes('config union (root) oneOf&lt;object|null&gt;')), 'true'],
                  ['source union issue', sourceUnionIssue, 'source oneOf branch 1 cannot feed target: source type string cannot feed target type integer'],
                  ['target anyOf compatible', targetAnyOfIssue, ''],
                  ['target oneOf ambiguous', targetOneOfIssue, 'target oneOf is ambiguous because source is compatible with 2 compatible branches'],
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

    private record ProcessResult(boolean finished, int exitCode, String output) {
    }
}
