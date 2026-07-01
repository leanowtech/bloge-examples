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
                  'matchingPatternPropertySchemas',
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
                  'connectionLocalHeuristicStatus',
                  'connectionLocalMismatchIsAdvisory'
                ]) {
                  vm.runInContext(functionSource(name), context);
                }

                context.CONTEXT_SOURCE_ID = '__ctx';
                context.contextSourceForPath = (path) => ({ nodeId: '__ctx', path });
                context.sourceHandlesForNode = () => [];
                context.specForNode = () => ({ outputPort: 'payload' });
                context.outputPortsForSpec = () => [{ name: 'payload' }];
                context.schemaForPort = () => ({ schema: { type: 'array' } });
                context.schemaAtPath = (_schema, path) => ({ type: 'path', path });
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
                console.log('browser bracket path probe passed');
                """;
    }

    private record ProcessResult(boolean finished, int exitCode, String output) {
    }
}
