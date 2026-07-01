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
                  'dynamicInputFieldDescriptors',
                  'dynamicOutputFieldDescriptors',
                  'customInputPathForKey',
                  'customInputPortForKey',
                  'customOutputPathForKey',
                  'customOutputPortForKey',
                  'inputPortForInputPath',
                  'schemaDeclaresPath',
                  'requiredInputNamesForPort',
                  'defaultInputExpressionsForOperator',
                  'defaultCustomInputStateForOperator',
                  'defaultResourceParamInputs',
                  'resourceParamInputs',
                  'expressionForTargetInput',
                  'setExpressionForTargetInput',
                  'renderRequiredInputAutoBindButton',
                  'requiredInputAutoBindSummary',
                  'requiredInputAutoBindPlan',
                  'autoBindRequiredInputsFromButton',
                  'applyRequiredInputAutoBindPlan',
                  'isConfigExpressionValue',
                  'configExpressionForField',
                  'removeConfigReferencesToNode',
                  'normalizeDiagnostics',
                  'operatorDiagnosticsForSpec',
                  'operatorPaletteDiagnosticBadges',
                  'operatorPaletteSearchValues',
                  'renderOperatorDiagnosticsPanel',
                  'bindingCandidateSummary',
                  'bindingCandidateSummaryLevel',
                  'connectionServerPreflightMessage',
                  'targetWithServerBindingKey',
                  'connectionLocalHeuristicStatus',
                  'connectionLocalMismatchIsAdvisory',
                  'orderedBuilderNodes',
                  'builderEdges',
                  'canonicalEdgeKind',
                  'builderConfigBindings',
                  'builderInputBindings',
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
                  'configFieldDescriptors',
                  'hasSchemaProperties',
                  'labelForNode',
                  'readableName'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }

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
                context.inputPortsForSpec = (spec) => spec.ports || [];
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
                for (const name of [
                  'validateUnsupportedSchemaKeywords',
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
                context.state = {
                  builder: {
                    graphName: 'historyGraph',
                    selectedId: 'policy',
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
                  previewingDraftRevision: 7,
                  visualCheck: { message: 'Previously checked', level: 'success', diagnostics: [] },
                  connectionMessage: { text: 'connected', level: 'success' },
                  lastPayload: { output: true },
                  layout: {
                    nodes: [
                      { id: 'policy', label: 'Loan Policy', operatorRef: 'bloge:decisionTable', kind: 'decision-table' },
                      { id: 'riskNode', label: 'Eligibility', operatorRef: 'risk:eligibility', kind: 'custom' },
                      { id: 'auditNode', label: 'Audit', operatorRef: 'risk:audit', kind: 'custom' }
                    ]
                  }
                };
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
                const fullOutputContract = context.graphOutputContractSummary(
                  context.state.builder.nodes[1],
                  { nodeId: 'riskNode', path: '' }
                );
                const nestedOutputContract = context.graphOutputContractSummary(
                  context.state.builder.nodes[1],
                  { nodeId: 'riskNode', path: 'facts.reason' }
                );
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
                  ['context binding kind', contextBinding.kind, 'contextPath'],
                  ['context binding path', contextBinding.path, 'scores.0.value'],
                  ['output binding kind', outputBinding.kind, 'nodePath'],
                  ['output binding path', outputBinding.path, 'items.0.score'],
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
                  ['mixed binding candidate summary', mixedCandidateSummary, '1 compatible · 1 blocked · source type string cannot feed target type integer'],
                  ['mixed binding candidate level', mixedCandidateLevel, 'success'],
                  ['blocked binding candidate summary', blockedCandidateSummary, '0 compatible · 1 blocked · Target path is not accepted.'],
                  ['blocked binding candidate level', blockedCandidateLevel, 'error'],
                  ['empty binding candidate summary', emptyCandidateSummary, '0 compatible sources.'],
                  ['empty binding candidate level', emptyCandidateLevel, 'info'],
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
                  ['risk impact incoming kinds', riskIncomingKinds, 'config:policy|dependency:policy'],
                  ['risk impact outgoing kinds', riskOutgoingKinds, 'data:auditNode:payload -> inputs.risk|dependency:auditNode:orders downstream execution|route:auditNode:routes on eligible'],
                  ['risk impact context input', riskContextInputs, 'ctx.score -> inputs.score'],
                  ['risk impact graph output affected', riskImpact.graphOutputAffected, true],
                  ['risk impact panel includes delete summary', String(riskImpactPanel.includes('Delete Impact')), 'true'],
                  ['risk impact panel includes focus button', String(riskImpactPanel.includes('data-impact-node="auditNode"')), 'true'],
                  ['risk impact panel includes clear button', String(riskImpactPanel.includes('data-clear-impact="input"')), 'true'],
                  ['full output contract type', fullOutputContract.type, 'object'],
                  ['full output contract fields', fullOutputContract.fieldCount, 4],
                  ['full output contract required', fullOutputContract.requiredCount, 2],
                  ['full output contract source label', fullOutputContract.sourceLabel, 'riskNode.payload'],
                  ['nested output contract type', nestedOutputContract.type, 'string'],
                  ['nested output contract fields', nestedOutputContract.fieldCount, 0],
                  ['nested output contract source label', nestedOutputContract.sourceLabel, 'riskNode.payload.facts.reason'],
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
                  console.log('browser bracket path probe passed');
                }).catch((error) => {
                  console.error(error);
                  process.exitCode = 1;
                });
                """;
    }

    private record ProcessResult(boolean finished, int exitCode, String output) {
    }
}
