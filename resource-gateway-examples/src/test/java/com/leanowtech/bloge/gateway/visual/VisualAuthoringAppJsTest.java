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
    void requiresResourceContractWarningAcknowledgementBeforeSaving() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("async function validateResourceContractPayload(contract)")
                .contains("function resourceContractSaveConfirmationKey(contract, diagnostics = [])")
                .contains("current.saveConfirmationKey !== confirmationKey")
                .contains("Review warnings, then click Save contract again to continue.")
                .contains("resourceContractMutationQuery(hasWarningDiagnostic(validation.diagnostics))")
                .contains("return ackWarnings ? '?ackWarnings=true' : '';");
    }

    @Test
    void surfacesOperatorLibraryImpactReviewBeforeImport() throws Exception {
        String source = appJsSource();

        assertThat(source)
                .contains("id=\"library-impact\"")
                .contains("renderLibraryImpactPanel($('library-impact'), diagnostics, state.libraryMessage?.impact)")
                .contains("function renderLibraryImpactPanel(target, diagnostics, impact = null)")
                .contains("function libraryImpactSummaryFromPayload(impact)")
                .contains("function libraryImpactSummary(diagnostics)")
                .contains("function libraryImpactRefsFromDiagnostic(diagnostic)")
                .contains("data-library-impact-draft")
                .contains("data-library-impact-node-index")
                .contains("function openLibraryImpactDraft(draftId)")
                .contains("function openLibraryImpactDraftTarget(draftId, nodeIndex = -1)")
                .contains("Impact Review")
                .contains("payload?.impact");
    }

    private static String appJsSource() throws IOException {
        return new ClassPathResource("static/examples/gateway/app.js")
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
                  'renderLibraryProfilePanel',
                  'renderLibraryImpactPanel',
                  'libraryImpactSummaryFromPayload',
                  'libraryImpactDraftTargetsFromPayload',
                  'libraryImpactSummary',
                  'libraryImpactRefsFromDiagnostic',
                  'libraryImpactHighestLevel',
                  'libraryImpactSummaryLabel',
                  'libraryImpactRefGroup',
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
                  'operatorLibraryOutputPortDslPathSafe',
                  'outputPortDslPathSafe',
                  'operatorLibraryFieldProfile',
                  'operatorLibrarySchemaSummary',
                  'operatorLibraryFieldLabel',
                  'schemaDynamicSurfaceCount',
                  'operatorDiagnosticsForSpec',
                  'operatorPaletteCapabilityBadges',
                  'operatorPaletteCapabilityLabels',
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
                  'defaultOutputNodeForBuilder',
                  'defaultOutputPathForNode',
                  'ensureBuilderOutput',
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
                  'operatorUsageRefForNode',
                  'rebaseOperatorFingerprint',
                  'renderOperatorUsagePanel',
                  'renderOperatorFingerprintSnapshotPanel',
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
                  'runTraceForCanvasNode',
                  'runTraceLevel',
                  'runTraceStatusLabel',
                  'shortRunId',
                  'clearActiveRunTrace',
                  'runTraceCanvasCoverage',
                  'canvasNodeIds',
                  'runTraceCoverageText',
                  'runTraceSummary',
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
                  tags: ['risk', 'policy'],
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
                    requiresSecrets: true
                  },
                  inputPorts: [],
                  outputPorts: [],
                  configSchema: { schema: { type: 'object', properties: {} } }
                };
                const suspendableCapabilityLabels = context.operatorPaletteCapabilityLabels(suspendablePaletteSpec).join('|');
                const suspendableCapabilityBadges = context.operatorPaletteCapabilityBadges(suspendablePaletteSpec);
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
                      capabilities: { effect: 'READ_EXTERNAL', requiresSecrets: true },
                      lowering: { mode: 'transform' }
                    },
                    {
                      operatorRef: 'risk:route',
                      ports: { inputs: [], outputs: [] },
                      capabilities: { effect: 'PURE', requiresSecrets: false },
                      lowering: { mode: 'branch' }
                    }
                  ]
                });
                const libraryProfileHtml = context.renderLibraryProfilePanel(libraryProfile);
                const invalidLibraryProfile = context.libraryProfileFromText('{broken');
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
                        fingerprintStatus: 'DRIFTED'
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
                        changedSurface: 'output schema changed'
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
                const normalizedPaletteTokens = context.paletteSearchTokens('  Risk   SCORE  ').join('|');
                context.state.paletteSearch = 'risk inputs.customer.id config.threshold number';
                const paletteMultiTokenMatch = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteSearch = 'risk inputs.customer.id missingField';
                const paletteMultiTokenMiss = context.operatorMatchesPaletteFilter('risk:eligibility', paletteSearchSpec);
                context.state.paletteSearch = 'suspendable write-external secret';
                const paletteCapabilitySearchMatch = context.operatorMatchesPaletteFilter('awaitApproval', suspendablePaletteSpec);
                context.state.paletteSearch = '';
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
                    { level: 'ERROR', target: '/nodes/1/inputs/score', message: 'Risk node score failed.' },
                    { level: 'WARNING', nodeId: 'policy', target: '/graphName', message: 'Policy warning.' }
                  ]
                };
                const riskSearch = context.canvasSearchResults('eligibility strict', context.state.builder, context.state.layout)
                  .map((entry) => entry.nodeId)
                  .join('|');
                const policyByPointer = context.diagnosticTargetNodeId({ target: '/nodes/0/config/rules/0' }, context.state.builder);
                const riskByPointer = context.diagnosticTargetNodeId({ target: '/nodes/1/inputs/score' }, context.state.builder);
                const policyByDirectNode = context.diagnosticTargetNodeId({ nodeId: 'policy', target: '/graphName' }, context.state.builder);
                const riskDiagnosticCount = context.diagnosticsForCanvasNode('riskNode').length;
                const unescapedPointerSegment = context.jsonPointerUnescape('node~1with~0marker');
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
                const riskImpactPanel = context.renderNodeImpactPanel(context.state.builder.nodes[1]);
                const riskUsageRef = context.operatorUsageRefForNode(context.state.builder.nodes[1]);
                const policyUsageRef = context.operatorUsageRefForNode(context.state.builder.nodes[0]);
                const riskUsageLevel = context.operatorUsageResponseLevel(context.state.operatorUsageByRef['risk:eligibility']);
                const riskUsagePrimaryStatus = context.operatorUsagePrimaryStatus(context.state.operatorUsageByRef['risk:eligibility']);
                const riskUsageSummary = context.operatorUsageSummaryForNode(context.state.builder.nodes[1]);
                const riskUsagePanel = context.renderOperatorUsagePanel(context.state.builder.nodes[1]);
                const riskFingerprintStatus = context.operatorFingerprintSnapshotStatus(context.state.builder.nodes[1]);
                const riskFingerprintPanel = context.renderOperatorFingerprintSnapshotPanel(context.state.builder.nodes[1]);
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
                    message: "Operator library 'risk-policy' changes operatorRef 'risk:eligibility' used by draft 'draft-risk@3' node 'riskNode' from saved fingerprint 'old' to 'new'; changed surface: output schema changed."
                  },
                  {
                    level: 'WARNING',
                    code: 'visual.library.publicationOperatorFingerprintDrift',
                    target: '/publications/pub-risk/nodes/0/operatorRef',
                    message: "Operator library 'risk-policy' changes operatorRef 'risk:eligibility' used by publication 'pub-risk' node 'riskNode' from frozen fingerprint 'old' to 'new'; changed surface: output schema changed."
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
                  codeCounts: [
                    { code: 'visual.library.operatorFingerprintDrift', level: 'WARNING', count: 2 },
                    { code: 'visual.library.inUse', level: 'ERROR', count: 1 }
                  ]
                });
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
                    codeCounts: [
                      { code: 'visual.library.payloadImpact', level: 'WARNING', count: 2 }
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
                  ['palette capability labels', suspendableCapabilityLabels, 'suspendable|requires secret|write-external'],
                  ['palette capability badges', String(suspendableCapabilityBadges.includes('suspendable') && suspendableCapabilityBadges.includes('requires secret')), 'true'],
                  ['palette capability search match', String(paletteCapabilitySearchMatch), 'true'],
                  ['library profile operator count', libraryProfile.operatorCount, 2],
                  ['library profile input count', libraryProfile.inputPortCount, 1],
                  ['library profile output count', libraryProfile.outputPortCount, 1],
                  ['library profile required count', libraryProfile.requiredInputCount, 1],
                  ['library profile config fields', libraryProfile.configFieldCount, 1],
                  ['library profile output fields', libraryProfile.outputFieldCount, 2],
                  ['library profile unsafe fields', libraryProfile.dslUnsafeFieldCount, 2],
                  ['library profile dynamic schemas', libraryProfile.dynamicSchemaCount, 2],
                  ['library profile external operators', libraryProfile.externalOperatorCount, 1],
                  ['library profile secret operators', libraryProfile.secretOperatorCount, 1],
                  ['library profile operator input field count', libraryProfile.operators[0].inputFields.length, 3],
                  ['library profile operator output field count', libraryProfile.operators[0].outputFields.length, 2],
                  ['library profile operator config field count', libraryProfile.operators[0].configFields.length, 1],
                  ['library profile level', context.libraryProfileLevel(libraryProfile), 'warning'],
                  ['library profile html escapes score', String(libraryProfileHtml.includes('Risk &lt;Score&gt;')), 'true'],
                  ['library profile html includes required input field', String(libraryProfileHtml.includes('inputs.customer.id*')), 'true'],
                  ['library profile html includes unsafe input field', String(libraryProfileHtml.includes('inputs.customer.bad-field !')), 'true'],
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
                  ['canvas search custom config hit', riskSearch, 'riskNode'],
                  ['diagnostic node pointer index 0', policyByPointer, 'policy'],
                  ['diagnostic node pointer index 1', riskByPointer, 'riskNode'],
                  ['diagnostic direct node target', policyByDirectNode, 'policy'],
                  ['diagnostic node count', riskDiagnosticCount, 1],
                  ['json pointer unescape', unescapedPointerSegment, 'node/with~marker'],
                  ['run trace node lookup', riskTraceNode.nodeId, 'riskNode'],
                  ['run trace level', riskTraceLevel, 'error'],
                  ['run trace status label', riskTraceStatus, 'COMPLETED'],
                  ['run trace badge', riskTraceBadge, 'ERR 1'],
                  ['run trace issue text', riskTraceIssueText, '1 issue · COMPLETED · 2 trace'],
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
                  ['risk usage ref', riskUsageRef, 'risk:eligibility'],
                  ['policy usage ref', policyUsageRef, 'bloge:decisionTable'],
                  ['risk usage level', riskUsageLevel, 'warning'],
                  ['risk usage primary status', riskUsagePrimaryStatus, 'DRIFTED'],
                  ['risk usage summary label', riskUsageSummary.label, 'DRIFT'],
                  ['risk usage summary title', riskUsageSummary.title, 'risk:eligibility: 1 draft · 1 publication · DRIFTED'],
                  ['risk usage panel includes refresh action', String(riskUsagePanel.includes('data-operator-usage="risk:eligibility"')), 'true'],
                  ['risk usage panel includes drift status', String(riskUsagePanel.includes('DRIFTED')), 'true'],
                  ['risk usage panel includes changed surface', String(riskUsagePanel.includes('output schema changed')), 'true'],
                  ['risk fingerprint status', riskFingerprintStatus.status, 'DRIFTED'],
                  ['risk fingerprint level', riskFingerprintStatus.level, 'warning'],
                  ['risk fingerprint can rebase', riskFingerprintStatus.canRebase, true],
                  ['risk fingerprint panel includes rebase action', String(riskFingerprintPanel.includes('data-rebase-operator-fingerprint="riskNode"')), 'true'],
                  ['risk fingerprint panel includes drift label', String(riskFingerprintPanel.includes('Snapshot drifted')), 'true'],
                  ['risk usage panel includes rebase action', String(riskUsagePanel.includes('data-rebase-operator-fingerprint="riskNode"')), 'true'],
                  ['library impact diagnostics', libraryImpact.diagnosticCount, 4],
                  ['library impact errors', libraryImpact.errorCount, 2],
                  ['library impact warnings', libraryImpact.warningCount, 2],
                  ['library impact drafts', libraryImpact.draftIds.join('|'), 'draft-risk'],
                  ['library impact publications', libraryImpact.publicationIds.join('|'), 'pub-risk'],
                  ['library impact operators', libraryImpact.operatorRefs.join('|'), 'risk:audit|risk:eligibility'],
                  ['library impact label', context.libraryImpactSummaryLabel(libraryImpact), '2 errors · 2 warnings · 1 draft · 1 publication · 2 operators'],
                  ['library payload impact diagnostics', libraryImpactFromPayload.diagnosticCount, 3],
                  ['library payload impact drafts deduped', libraryImpactFromPayload.draftIds.join('|'), 'draft-risk'],
                  ['library payload impact node index', libraryImpactFromPayload.draftTargets[0].nodeIndex, 1],
                  ['library payload impact code', libraryImpactFromPayload.codeCounts[0].code, 'visual.library.operatorFingerprintDrift'],
                  ['library impact panel visible', libraryImpactPanel.hidden, false],
                  ['library impact panel level', libraryImpactPanel.className, 'library-impact-panel error'],
                  ['library impact panel prefers payload draft', String(libraryImpactPanel.innerHTML.includes('draft-from-payload')), 'true'],
                  ['library impact panel ignores fallback draft', String(libraryImpactPanel.innerHTML.includes('draft-risk')), 'false'],
                  ['library impact panel includes node index', String(libraryImpactPanel.innerHTML.includes('data-library-impact-node-index="2"')), 'true'],
                  ['library impact panel includes payload code', String(libraryImpactPanel.innerHTML.includes('visual.library.payloadImpact')), 'true'],
                  ['library impact draft group action', String(libraryImpactDraftGroup.includes('data-library-impact-draft="draft-risk"')), 'true'],
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
                  ['connectability panel includes score chip', String(connectabilityPanel.includes('riskNode.payload.score')), 'true'],
                  ['connectability panel includes blocked chip', String(connectabilityPanel.includes('blocked')), 'true'],
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
                    ['rebase returned revision', rebaseResult.rebasedDraft.revision, 4],
                    ['rebase state revision', context.state.currentDraftRevision, 4],
                    ['rebase state fingerprint', context.state.builder.operatorFingerprints.riskNode, 'current-fingerprint-123456'],
                    ['rebase message level', rebaseResult.rebaseDraftMessageLevel, 'success'],
                    ['rebase message text', rebaseResult.rebaseDraftMessage, 'Rebased riskNode operator fingerprint at draft-risk@4.'],
                    ['rebase draft list calls', rebaseResult.rebaseDraftListCalls, 1],
                    ['rebase revision calls', rebaseResult.rebaseRevisionCalls, 1],
                    ['rebase usage ref', rebaseResult.rebaseUsageRef, 'risk:eligibility'],
                    ['rebase draft controls rendered', rebaseResult.rebaseDraftControlRenders, 1],
                    ['rebase editor renders', rebaseResult.rebaseEditorRenders, 2],
                    ['rebase diagram renders', rebaseResult.rebaseDiagramRenders, 1],
                    ['rebase loading cleared', context.state.operatorFingerprintRebaseNodeId, '']
                  ];
                  for (const [label, actual, expected] of rebaseChecks) {
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
