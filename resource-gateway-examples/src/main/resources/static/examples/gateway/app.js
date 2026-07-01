const COMPOSER_GRAPH = '__composer';
const CONTEXT_SOURCE_ID = '__ctx';
const CONFIG_MANUAL_EXPRESSION = '__manual_config_expression';

const DEFAULT_COMPOSER_DSL = `graph customLoanPolicy {
  decision_table loanPolicy(
    score  = ctx.score,
    amount = ctx.amount
  ) hit=unique -> { decision: String, rate: Decimal, maxTerm: Int, reviewLane: String, ruleId: String } {
    rule (score: score >= 760, amount: amount <= 500000)       -> { decision: "approved", rate: 3.5,  maxTerm: 360, reviewLane: "auto-approve",       ruleId: "R1" }
    rule (score: 700 <= score < 760, amount: amount <= 300000) -> { decision: "approved", rate: 4.5,  maxTerm: 300, reviewLane: "standard",           ruleId: "R2" }
    rule (score: 650 <= score < 700, amount: amount <= 200000) -> { decision: "manual_review", rate: 5.75, maxTerm: 240, reviewLane: "senior-underwriter", ruleId: "R3" }
    otherwise                                                  -> { decision: "declined", rate: 0.0,  maxTerm: 0,   reviewLane: "decline",            ruleId: "R4" }
  }

  transform response {
    applicant       = { score: ctx.score, segment: ctx.segment }
    requestedAmount = ctx.amount
    policy          = loanPolicy.output
  }
}`;

const DEFAULT_COMPOSER_CONTEXT = {
  score: 670,
  amount: 180000,
  segment: 'existing'
};

const DSL_FIELD_IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/;
const RESERVED_DSL_FIELD_NAMES = new Set([
  'graph', 'node', 'branch', 'decision_table', 'on', 'input', 'depends_on',
  'timeout', 'retry', 'fallback', 'execution_mode', 'worker_topic', 'compensate',
  'saga', 'true', 'false', 'schema', 'output', 'otherwise', 'when', 'transform',
  'foreach', 'sequential', 'in', 'loop', 'parallel', 'until', 'carry', 'wait',
  'after', 'await', 'event', 'where', 'mode', 'stream', 'streaming', 'buffer',
  'let', 'import', 'as', 'script', 'exit', 'exhausted'
]);

const DEFAULT_COMPOSER_DECISION_TABLE = {
  title: 'LoanPolicy',
  hitPolicy: 'unique',
  inputs: [
    { key: 'score', label: 'Score' },
    { key: 'amount', label: 'Amount' }
  ],
  outputs: [
    { key: 'decision', label: 'Decision' },
    { key: 'rate', label: 'Rate' },
    { key: 'maxTerm', label: 'MaxTerm' },
    { key: 'reviewLane', label: 'ReviewLane' },
    { key: 'ruleId', label: 'RuleId' }
  ],
  rows: [
    {
      id: 'R1',
      conditions: { score: 'score >= 760', amount: 'amount <= 500000' },
      output: { decision: 'approved', rate: 3.5, maxTerm: 360, reviewLane: 'auto-approve', ruleId: 'R1' },
      explanation: 'Prime auto approval'
    },
    {
      id: 'R2',
      conditions: { score: '700 <= score < 760', amount: 'amount <= 300000' },
      output: { decision: 'approved', rate: 4.5, maxTerm: 300, reviewLane: 'standard', ruleId: 'R2' },
      explanation: 'Standard approval'
    },
    {
      id: 'R3',
      conditions: { score: '650 <= score < 700', amount: 'amount <= 200000' },
      output: { decision: 'manual_review', rate: 5.75, maxTerm: 240, reviewLane: 'senior-underwriter', ruleId: 'R3' },
      explanation: 'Manual review'
    },
    {
      id: 'R4',
      conditions: { score: 'otherwise', amount: 'otherwise' },
      output: { decision: 'declined', rate: 0, maxTerm: 0, reviewLane: 'decline', ruleId: 'R4' },
      explanation: 'Fallback rule'
    }
  ]
};

const COMPOSER_SCENARIO = {
  graphName: COMPOSER_GRAPH,
  title: 'Custom Composer',
  pattern: 'Drag operators + decision_table',
  description: 'Build a loan policy graph from reusable resource, decision-table, and transform operators, then execute the generated DSL immediately.',
  concepts: ['Drag compose', 'Decision table', 'Live diagnostics'],
  sampleInput: {},
  samplePresets: [],
  diagramPath: '',
  decisionTable: null
};

const SCENARIO_EXPLANATIONS = {
  [COMPOSER_GRAPH]: {
    capability: 'BLOGE turns a visual composition into the same executable graph DSL, keeping orchestration, resource calls, policy rules, and response shaping aligned.',
    signal: 'The diagram, DSL preview, decision matrix, selected node details, and graph output update from one graph definition.'
  },
  userDashboard: {
    capability: 'BLOGE models parallel fan-out, timeout, retry, fallback, and aggregation as graph semantics instead of scattered controller code.',
    signal: 'Independent resource nodes converge into one dashboard response while failures can be isolated behind fallback behavior.'
  },
  loanDecisionPolicy: {
    capability: 'BLOGE keeps the external fact fetch and the decision_table policy in one auditable graph with explicit rule output.',
    signal: 'The matched rule row, decision summary, and response payload share the same ruleId.'
  },
  productDetail: {
    capability: 'BLOGE branches on resource data, enriches only the relevant path, and normalizes branch-specific outputs into one response contract.',
    signal: 'Physical, digital, and generic paths remain visible as branches while the final node produces a unified detail payload.'
  },
  enrichOrderList: {
    capability: 'BLOGE expresses per-item foreach enrichment with parallel inner work and local fallback around each order.',
    signal: 'One list fetch expands into item-level enrichment nodes before collecting the final order collection.'
  },
  creditScore: {
    capability: 'BLOGE makes provider degradation a first-class graph path, preserving primary/secondary provenance in the output.',
    signal: 'The fallback path is explicit on the diagram and the response can identify which provider supplied the score.'
  },
  resourceDispatch: {
    capability: 'BLOGE uses a generic httpResource operator backed by descriptors, so new APIs can be added through metadata instead of custom operator classes.',
    signal: 'Changing resourceId and params dispatches a different registered resource through the same execution path.'
  },
  aiEnrichedSearch: {
    capability: 'BLOGE can orchestrate streaming operators alongside normal graph nodes and bridge each stream to a named SSE lane.',
    signal: 'Metadata, token, and citation streams run in parallel but remain separated in the browser output.'
  }
};

const OPERATOR_TYPES = {
  httpResource: {
    label: 'HTTP Resource',
    kind: 'resource',
    operatorRef: 'httpResource',
    inputPort: 'input',
    outputPort: 'output',
    baseId: 'fetchApplicant'
  },
  decisionTable: {
    label: 'Decision Table',
    kind: 'decision-table',
    operatorRef: '',
    inputPort: 'inputs',
    outputPort: 'output',
    baseId: 'loanPolicy'
  },
  transform: {
    label: 'Transform',
    kind: 'transform',
    operatorRef: '',
    inputPort: 'inputs',
    outputPort: 'output',
    baseId: 'response'
  }
};

const CORE_OPERATOR_TYPES = new Set(Object.keys(OPERATOR_TYPES));

const SAMPLE_OPERATOR_LIBRARY = {
  schemaVersion: 'bloge.visualOperatorLibrary.v1',
  libraryId: 'risk-policy',
  displayName: 'Risk policy operators',
  version: '1.0.0',
  owner: 'risk-team',
  operators: [
    {
      schemaVersion: 'bloge.visualOperator.v1',
      operatorRef: 'risk:eligibility',
      display: {
        name: 'Eligibility',
        description: 'Evaluates a reusable eligibility predicate.',
        tags: ['risk', 'policy']
      },
      policy: {
        tenants: ['demo-tenant'],
        namespaces: ['local'],
        environments: ['browser']
      },
      source: { kind: 'user-library', virtual: true },
      ports: {
        inputs: [
          {
            name: 'inputs',
            required: true,
            schema: {
              schema: {
                type: 'object',
                properties: {
                  score: { type: 'integer' },
                  amount: { type: 'number' }
                },
                required: ['score', 'amount'],
                additionalProperties: false
              }
            }
          }
        ],
        outputs: [
          {
            name: 'output',
            required: true,
            schema: {
              schema: {
                type: 'object',
                properties: {
                  eligible: { type: 'boolean' },
                  ruleId: { type: 'string' }
                },
                additionalProperties: false
              }
            }
          }
        ]
      },
      lowering: {
        mode: 'transform',
        operatorRef: 'transform',
        parameters: {
          assignments: {
            eligible: '{{input.score}} >= 700 && {{input.amount}} <= 300000',
            ruleId: '"ELIGIBILITY_V1"'
          }
        }
      }
    }
  ]
};

const SAMPLE_OPENAPI_RESOURCE_CONTRACT = {
  openapi: '3.0.3',
  info: {
    title: 'Loan Applicant API',
    version: '1.0.0'
  },
  servers: [
    { url: 'https://api.example.test' }
  ],
  paths: {
    '/api/loan-applicants/{applicantId}': {
      get: {
        operationId: 'getLoanApplicant',
        parameters: [
          {
            name: 'applicantId',
            in: 'path',
            required: true,
            schema: { type: 'string' }
          }
        ],
        responses: {
          200: {
            description: 'Applicant facts',
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    score: { type: 'integer' },
                    segment: { type: 'string' },
                    income: { type: 'number' }
                  },
                  required: ['score']
                }
              }
            }
          }
        }
      }
    }
  }
};

const NODE_SIZE = { width: 184, height: 76 };
const DRAG_START_THRESHOLD = 4;
const SUPPORTED_SCHEMA_FORMAT = 'json-schema';
const SUPPORTED_SCHEMA_VERSION = '2020-12';
const SUPPORTED_SCHEMA_KINDS = new Set([
  'object',
  'array',
  'string',
  'integer',
  'number',
  'decimal',
  'boolean',
  'duration',
  'datetime',
  'enum',
  'any',
  'opaque',
  'null'
]);
const UNSUPPORTED_SCHEMA_REFERENCE_KEYWORDS = ['$ref', '$dynamicRef'];
const UNSUPPORTED_SCHEMA_COMPOSITION_KEYWORDS = ['oneOf', 'anyOf', 'allOf', 'not', 'if', 'then', 'else'];
const UNSUPPORTED_SCHEMA_CONSTRAINT_KEYWORDS = [
  'unevaluatedItems'
];
const SUPPORTED_SCHEMA_STRING_FORMATS = new Set(['date', 'date-time', 'duration', 'email', 'uri', 'uuid']);
const LOCAL_SCHEMA_DEFS_REF_PREFIX = '#/$defs/';
const SCHEMA_REF_ANNOTATION_KEYS = new Set([
  '$ref',
  '$comment',
  'title',
  'description',
  'examples',
  'deprecated',
  'readOnly',
  'writeOnly'
]);
const SCHEMA_ANNOTATION_KEYS = new Set([
  '$comment',
  'title',
  'description',
  'examples',
  'deprecated',
  'readOnly',
  'writeOnly'
]);
const SCHEMA_DECLARATION_KEYS = new Set([
  '$defs'
]);

const state = {
  scenarios: [],
  visualOperators: [],
  selected: null,
  layout: null,
  selectedNodeId: null,
  eventSource: null,
  lastPayload: null,
  builder: createDefaultBuilder(),
  drafts: [],
  currentDraftId: '',
  currentDraftRevision: 0,
  savedDraftSnapshot: null,
  draftRevisions: [],
  selectedDraftRevision: 0,
  previewingDraftRevision: 0,
  draftBundleText: '',
  draftMessage: null,
  publications: [],
  selectedPublicationId: '',
  publicationMessage: null,
  goldenCases: [],
  selectedGoldenCaseId: '',
  goldenCertification: null,
  goldenCertificationStatus: null,
  goldenAssertionMode: 'EXACT_OUTPUT',
  goldenAssertionPath: '',
  goldenAssertionValueText: '',
  runHistory: [],
  runHistoryFilters: {
    sourceKind: '',
    outcome: '',
    limit: '8'
  },
  runHistoryMessage: null,
  runHistoryStats: null,
  visualCheck: {
    message: 'Not checked',
    level: 'info',
    diagnostics: []
  },
  operatorLibraries: [],
  selectedLibraryId: '',
  libraryImportText: pretty(SAMPLE_OPERATOR_LIBRARY),
  libraryForce: false,
  libraryMessage: null,
  libraryImportConfirmationKey: '',
  resourceContractImport: createDefaultResourceContractImport(),
  paletteSearch: '',
  paletteKind: '',
  paletteTag: '',
  draggingOperatorType: null,
  paletteDrag: null,
  nodeDrag: null,
  connectionDrag: null,
  connectionMessage: null,
  suppressPaletteClick: false,
  suppressNodeClick: false,
  customDsl: DEFAULT_COMPOSER_DSL,
  lastGeneratedVisualDsl: '',
  graphInputSchemaText: pretty(defaultGraphInputSchema()),
  graphInputSchemaMessage: null,
  graphInputSchemaDiagnostics: [],
  customContextText: '',
  customDecisionTable: DEFAULT_COMPOSER_DECISION_TABLE
};

let dragPreview = null;

const $ = (id) => document.getElementById(id);

function pretty(value) {
  return JSON.stringify(value, null, 2);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function createDefaultResourceContractImport() {
  return {
    resourceId: 'loan-applicant-service.getProfile',
    operationId: 'getLoanApplicant',
    path: '',
    method: 'GET',
    status: 'ACTIVE',
    openApiText: pretty(SAMPLE_OPENAPI_RESOURCE_CONTRACT),
    contractText: '',
    descriptorText: '',
    message: null
  };
}

async function loadScenarios() {
  await loadVisualOperatorCatalog();
  await loadOperatorLibraries({ render: false });
  await loadDraftList({ render: false });
  await loadPublicationList({ render: false });
  await loadGoldenCases({ render: false });
  await loadGoldenCertificationStatus({ render: false });
  await loadRunHistory({ render: false });
  const response = await fetch('/api/gateway/examples/scenarios');
  state.customContextText = pretty(DEFAULT_COMPOSER_CONTEXT);
  syncGraphInputSchemaTextFromBuilder({ render: false });
  syncComposerFromBuilder({ render: false });
  state.scenarios = [COMPOSER_SCENARIO, ...await response.json()];
  renderScenarioButtons();
  await selectScenario(COMPOSER_GRAPH);
}

async function loadVisualOperatorCatalog() {
  try {
    resetDynamicOperatorTypes();
    const response = await fetch(operatorCatalogUrl());
    if (!response.ok) {
      return;
    }
    const payload = await response.json();
    const operators = Array.isArray(payload.operators) ? payload.operators : [];
    state.visualOperators = operators;
    const activeOperatorRefs = new Set();
    for (const operator of operators) {
      const operatorRef = rememberCatalogOperator(operator);
      if (operatorRef) {
        activeOperatorRefs.add(operatorRef);
      }
    }
    const nodeOnlyRefs = operatorRefsRequiredByBuilder()
      .filter((operatorRef) => !activeOperatorRefs.has(operatorRef));
    if (nodeOnlyRefs.length) {
      const deprecatedResponse = await fetch(operatorCatalogUrl(state.builder, { includeDeprecated: true }));
      if (deprecatedResponse.ok) {
        const deprecatedPayload = await deprecatedResponse.json();
        const required = new Set(nodeOnlyRefs);
        const deprecatedOperators = Array.isArray(deprecatedPayload.operators)
          ? deprecatedPayload.operators
          : [];
        for (const operator of deprecatedOperators) {
          if (required.has(operator.operatorRef || '')) {
            rememberCatalogOperator(operator, { paletteVisible: false, deprecated: true });
          }
        }
      }
    }
  } catch (error) {
    console.debug('Visual operator catalog unavailable', error);
  }
}

function rememberCatalogOperator(operator, options = {}) {
  const operatorRef = operator.operatorRef || '';
  if (!operatorRef || operatorRef === 'httpResource' || operatorRef === 'bloge:decisionTable' || operatorRef === 'bloge:transform') {
    rememberCoreOperatorFingerprint(operatorRef, operator.fingerprint || '');
    return operatorRef;
  }
  if (!operatorRef.startsWith('resource:')) {
    const inputPorts = normalizeOperatorPorts(operator.ports?.inputs, 'inputs');
    const outputPorts = normalizeOperatorPorts(operator.ports?.outputs, 'output');
    const primaryInput = inputPorts[0] || { name: 'inputs', schema: null };
    const primaryOutput = outputPorts[0] || { name: 'output', schema: null };
    OPERATOR_TYPES[operatorRef] = {
      label: operator.display?.name || readableName(operatorRef),
      kind: 'custom',
      operatorRef,
      visualOperatorRef: operatorRef,
      fingerprint: operator.fingerprint || '',
      inputPort: primaryInput.name,
      outputPort: primaryOutput.name,
      baseId: baseIdForResource(operatorRef),
      inputPorts,
      outputPorts,
      inputSchema: primaryInput.schema,
      outputSchema: primaryOutput.schema,
      configSchema: operator.configSchema,
      description: operator.display?.description || '',
      tags: Array.isArray(operator.display?.tags) ? operator.display.tags.map(String) : [],
      sourceKind: operator.source?.kind || '',
      policy: operator.policy,
      lowering: operator.lowering,
      paletteVisible: options.paletteVisible !== false,
      deprecated: Boolean(options.deprecated)
    };
    return operatorRef;
  }
  const resourceId = operator.source?.resourceId
    || operator.lowering?.parameters?.resourceId
    || operatorRef.slice('resource:'.length);
  const inputPorts = normalizeOperatorPorts(operator.ports?.inputs, 'params');
  const outputPorts = normalizeOperatorPorts(operator.ports?.outputs, 'payload');
  const primaryInput = inputPorts[0] || { name: 'params', schema: null };
  const primaryOutput = outputPorts[0] || { name: 'payload', schema: null };
  OPERATOR_TYPES[operatorRef] = {
    label: operator.display?.name || readableName(resourceId),
    kind: 'resource',
    operatorRef: 'httpResource',
    visualOperatorRef: operatorRef,
    fingerprint: operator.fingerprint || '',
    inputPort: primaryInput.name,
    outputPort: primaryOutput.name,
    baseId: baseIdForResource(resourceId),
    resourceId,
    inputPorts,
    outputPorts,
    inputSchema: primaryInput.schema,
    outputSchema: primaryOutput.schema,
    configSchema: operator.configSchema,
    description: operator.display?.description || '',
    tags: Array.isArray(operator.display?.tags) ? operator.display.tags.map(String) : [],
    sourceKind: operator.source?.kind || '',
    policy: operator.policy,
    lowering: operator.lowering,
    paletteVisible: options.paletteVisible !== false,
    deprecated: Boolean(options.deprecated)
  };
  return operatorRef;
}

function operatorRefsRequiredByBuilder(builder = state.builder) {
  const refs = new Set();
  for (const node of builder?.nodes || []) {
    if (node.type === 'customOperator' && node.paletteType) {
      refs.add(node.paletteType);
    }
    if (node.type === 'httpResource' && node.resourceId) {
      refs.add(node.paletteType?.startsWith('resource:')
        ? node.paletteType
        : `resource:${node.resourceId}`);
    }
  }
  return [...refs];
}

function operatorCatalogUrl(builder = state.builder, options = {}) {
  const scope = builderScope(builder);
  const params = new URLSearchParams();
  params.set('tenantId', scope.tenantId);
  params.set('namespace', scope.namespace);
  params.set('environment', scope.environment);
  if (options.includeDeprecated) {
    params.set('includeDeprecated', 'true');
  }
  return `/api/visual/operators?${params.toString()}`;
}

function builderScope(builder = state.builder) {
  return {
    tenantId: builder?.tenantId || 'demo-tenant',
    namespace: builder?.namespace || 'local',
    environment: builder?.environment || 'browser'
  };
}

function scopeFromControls() {
  return {
    tenantId: normalizedScopeValue($('scope-tenant')?.value, 'demo-tenant'),
    namespace: normalizedScopeValue($('scope-namespace')?.value, 'local'),
    environment: normalizedScopeValue($('scope-environment')?.value, 'browser')
  };
}

function normalizedScopeValue(value, fallback) {
  const normalized = String(value || '').trim();
  return normalized || fallback;
}

async function applyBuilderScopeFromControls() {
  const next = scopeFromControls();
  const current = builderScope();
  state.builder.tenantId = next.tenantId;
  state.builder.namespace = next.namespace;
  state.builder.environment = next.environment;
  if (current.tenantId !== next.tenantId
      || current.namespace !== next.namespace
      || current.environment !== next.environment) {
    state.visualCheck = { message: 'Not checked', level: 'info', diagnostics: [] };
  }
  renderScopeStatus('Loading catalog...', 'info');
  await loadVisualOperatorCatalog();
  renderOperatorPalette();
  renderSelectedOperatorEditor();
  renderGraphOutputEditor();
  renderVisualCheck();
  renderScopeStatus();
}

function renderScopeStatus(message = '', level = 'info') {
  const target = $('scope-status');
  if (!target) return;
  const scope = builderScope();
  target.textContent = message || `${scope.tenantId} / ${scope.namespace} / ${scope.environment}`;
  target.className = `scope-status ${level}`;
}

function rememberCoreOperatorFingerprint(operatorRef, fingerprint) {
  if (!fingerprint) {
    return;
  }
  const type = {
    httpResource: 'httpResource',
    'bloge:decisionTable': 'decisionTable',
    'bloge:transform': 'transform'
  }[operatorRef];
  if (type && OPERATOR_TYPES[type]) {
    OPERATOR_TYPES[type].fingerprint = fingerprint;
  }
}

function normalizeOperatorPorts(ports, fallbackName) {
  if (!Array.isArray(ports) || ports.length === 0) {
    return [];
  }
  return ports.map((port) => ({
    name: port?.name || fallbackName,
    schema: port?.schema || null,
    required: Boolean(port?.required),
    description: port?.description || ''
  }));
}

function inputPortsForSpec(spec) {
  if (Array.isArray(spec?.inputPorts)) {
    return spec.inputPorts;
  }
  return [{ name: spec?.inputPort || 'inputs', schema: spec?.inputSchema || null, required: true }];
}

function outputPortsForSpec(spec) {
  if (spec?.lowering?.mode === 'branch') {
    return [];
  }
  if (Array.isArray(spec?.outputPorts)) {
    return spec.outputPorts;
  }
  return [{ name: spec?.outputPort || 'output', schema: spec?.outputSchema || null, required: true }];
}

function inputPortForInputPath(spec, path) {
  const ports = inputPortsForSpec(spec);
  const matches = ports.filter((port) => schemaDeclaresPath(port.schema, path));
  if (matches.length === 1) {
    return matches[0].name;
  }
  return ports[0]?.name || spec?.inputPort || 'inputs';
}

function inputKeyForPortPath(spec, portName, path) {
  if (!path) {
    return portName || spec?.inputPort || 'inputs';
  }
  const matchingPorts = inputPortsForSpec(spec).filter((port) => schemaDeclaresPath(port.schema, path));
  return matchingPorts.length > 1 ? `${portName || spec?.inputPort || 'inputs'}.${path}` : path;
}

function customInputPathForKey(node, key) {
  const paths = node.customInputPaths || {};
  return Object.prototype.hasOwnProperty.call(paths, key) ? paths[key] : key;
}

function customInputPortForKey(node, spec, key) {
  return node.customInputPorts?.[key] || inputPortForInputPath(spec, customInputPathForKey(node, key));
}

function outputKeyForPortPath(spec, portName, path) {
  if (!path) {
    return portName || spec?.outputPort || 'output';
  }
  const matchingPorts = outputPortsForSpec(spec).filter((port) => schemaDeclaresPath(port.schema, path));
  return matchingPorts.length > 1 ? `${portName || spec?.outputPort || 'output'}.${path}` : path;
}

function customOutputPathForKey(node, key) {
  const paths = node.customOutputPaths || {};
  return Object.prototype.hasOwnProperty.call(paths, key) ? paths[key] : key;
}

function customOutputPortForKey(node, spec, key) {
  const portName = node.customOutputPorts?.[key];
  if (portName) {
    return portName;
  }
  return outputPortsForSpec(spec)[0]?.name || spec?.outputPort || 'output';
}

function bindingTargetPathForKey(key, binding) {
  if (Object.prototype.hasOwnProperty.call(binding || {}, 'targetPath')) {
    return binding.targetPath || '';
  }
  return binding?.targetPort === key ? '' : key;
}

function schemaForPort(spec, role, portName) {
  const ports = role === 'source' ? outputPortsForSpec(spec) : inputPortsForSpec(spec);
  return ports.find((port) => port.name === portName)?.schema
    || (role === 'source' ? spec?.outputSchema : spec?.inputSchema)
    || null;
}

function resetDynamicOperatorTypes() {
  for (const key of Object.keys(OPERATOR_TYPES)) {
    if (!CORE_OPERATOR_TYPES.has(key)) {
      delete OPERATOR_TYPES[key];
    }
  }
}

function renderScenarioButtons() {
  const target = $('scenarios');
  target.innerHTML = '';
  for (const scenario of state.scenarios) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'scenario-button';
    button.setAttribute('aria-current', state.selected?.graphName === scenario.graphName ? 'true' : 'false');
    button.innerHTML = `<strong>${scenario.title}</strong><span>${scenario.pattern}</span>`;
    button.addEventListener('click', () => selectScenario(scenario.graphName));
    target.appendChild(button);
  }
}

async function selectScenario(graphName) {
  closeStream();
  const scenario = state.scenarios.find((item) => item.graphName === graphName);
  if (!scenario) return;
  state.selected = scenario;
  state.selectedNodeId = null;
  state.lastPayload = null;
  if (isComposerSelected()) {
    state.builder.selectedId = state.builder.selectedId || state.builder.nodes[0]?.id || null;
    state.layout = layoutFromBuilder(state.builder);
    state.selectedNodeId = state.builder.selectedId;
  } else {
    const response = await fetch(scenario.diagramPath);
    state.layout = await response.json();
  }
  renderScenarioButtons();
  renderScenario();
}

function renderScenario() {
  $('scenario-title').textContent = state.selected.title;
  $('scenario-pattern').textContent = state.selected.pattern;
  $('concepts').innerHTML = state.selected.concepts.map((concept) => `<span class="chip">${escapeHtml(concept)}</span>`).join('');
  renderExampleBrief();
  $('inspector').classList.toggle('composer-mode', isComposerSelected());
  renderInputForm();
  renderDecisionTable();
  renderDiagram();
  renderNodeDetails(state.layout?.nodes?.[0]);
  renderDecisionSummary(null);
  $('output').textContent = pretty({});
}

function renderExampleBrief() {
  const insight = SCENARIO_EXPLANATIONS[state.selected.graphName] || {
    capability: 'BLOGE makes the graph structure, resource calls, policies, and runtime result visible from one executable model.',
    signal: 'Compare the selected graph path, node metadata, and output payload after running the example.'
  };
  $('example-description').textContent = state.selected.description || `${state.selected.title} demonstrates ${state.selected.pattern}.`;
  $('example-capability').textContent = insight.capability;
  $('example-signal').textContent = insight.signal;
}

function renderInputForm() {
  const form = $('input-form');
  form.innerHTML = '';
  $('input-title').textContent = isComposerSelected() ? 'Composer' : 'Sample Input';
  $('run-scenario').textContent = isComposerSelected() ? 'Run Custom Graph' : 'Run';
  if (isComposerSelected()) {
    form.innerHTML = `
      <div class="builder-panel">
        <div class="panel-title">Authoring Scope</div>
        <div class="scope-controls">
          <label>
            <span>Tenant</span>
            <input id="scope-tenant" value="${escapeHtml(builderScope().tenantId)}">
          </label>
          <label>
            <span>Namespace</span>
            <input id="scope-namespace" value="${escapeHtml(builderScope().namespace)}">
          </label>
          <label>
            <span>Environment</span>
            <input id="scope-environment" value="${escapeHtml(builderScope().environment)}">
          </label>
          <button id="apply-scope" class="secondary compact" type="button">Apply</button>
        </div>
        <div id="scope-status" class="scope-status"></div>
      </div>
      <div class="builder-panel">
        <div class="panel-title">Operator Palette</div>
        <div class="palette-controls">
          <input id="operator-palette-search" type="search" value="${escapeHtml(state.paletteSearch)}" aria-label="Search operators">
          <select id="operator-palette-kind" aria-label="Filter operators by type"></select>
          <select id="operator-palette-tag" aria-label="Filter operators by tag"></select>
        </div>
        <div id="operator-palette-summary" class="palette-summary"></div>
        <div id="operator-palette" class="operator-palette"></div>
        <div id="connection-status" class="connection-status" hidden></div>
      </div>
      <div class="builder-panel">
        <div class="panel-title">Operator Libraries</div>
        <div class="library-controls">
          <select id="library-select" aria-label="Imported operator libraries"></select>
          <button id="validate-library" class="secondary compact" type="button">Validate</button>
          <button id="import-library" class="secondary compact" type="button">Import</button>
          <button id="reload-libraries" class="secondary compact" type="button">Reload</button>
          <button id="delete-library" class="secondary compact danger" type="button">Delete</button>
          <label class="config-checkbox compact">
            <input id="library-force" type="checkbox">
            <span>Force</span>
          </label>
        </div>
        <textarea id="operator-library-json" class="library-editor" spellcheck="false"></textarea>
        <div id="library-status" class="library-status" hidden></div>
        <div id="library-diagnostics" class="visual-diagnostics"></div>
      </div>
      <div class="builder-panel">
        <div class="panel-title">OpenAPI Resource Contract</div>
        <div class="resource-contract-controls">
          <label>
            <span>Resource ID</span>
            <input id="resource-contract-resource-id" value="${escapeHtml(state.resourceContractImport.resourceId)}">
          </label>
          <label>
            <span>Operation ID</span>
            <input id="resource-contract-operation-id" value="${escapeHtml(state.resourceContractImport.operationId)}">
          </label>
          <label>
            <span>Path</span>
            <input id="resource-contract-path" value="${escapeHtml(state.resourceContractImport.path)}">
          </label>
          <label>
            <span>Method</span>
            <select id="resource-contract-method" aria-label="OpenAPI method"></select>
          </label>
          <label>
            <span>Status</span>
            <select id="resource-contract-lifecycle" aria-label="Resource contract lifecycle"></select>
          </label>
        </div>
        <textarea id="openapi-resource-json" class="library-editor resource-contract-editor" spellcheck="false"></textarea>
        <div class="resource-contract-actions">
          <button id="preview-resource-contract" class="secondary compact" type="button">Preview</button>
          <button id="save-resource-contract" class="secondary compact" type="button">Save Contract</button>
          <button id="save-resource-descriptor" class="secondary compact" type="button">Save Descriptor</button>
          <button id="reset-resource-contract" class="secondary compact" type="button">Sample</button>
        </div>
        <textarea id="resource-contract-json" class="library-editor resource-contract-editor" spellcheck="false"></textarea>
        <textarea id="resource-descriptor-json" class="library-editor resource-contract-editor" spellcheck="false"></textarea>
        <div id="resource-contract-status-message" class="library-status" hidden></div>
        <div id="resource-contract-diagnostics" class="visual-diagnostics"></div>
      </div>
      <div class="builder-panel">
        <div class="panel-title">Drafts</div>
        <div class="draft-controls">
          <select id="draft-select" aria-label="Stored graph drafts"></select>
          <button id="save-draft" class="secondary compact" type="button">Save</button>
          <button id="load-draft" class="secondary compact" type="button">Load</button>
          <button id="delete-draft" class="secondary compact danger" type="button">Delete</button>
        </div>
        <div class="draft-revision-controls">
          <select id="draft-revision-select" aria-label="Draft revision history"></select>
          <button id="reload-revisions" class="secondary compact" type="button">History</button>
          <button id="preview-revision" class="secondary compact" type="button">Preview</button>
          <button id="restore-revision" class="secondary compact" type="button">Restore</button>
        </div>
        <div class="draft-transfer-controls">
          <button id="export-draft" class="secondary compact" type="button">Export</button>
          <button id="import-draft" class="secondary compact" type="button">Import Bundle</button>
        </div>
        <textarea id="draft-bundle-json" class="library-editor draft-bundle-editor" spellcheck="false"></textarea>
        <div id="draft-status" class="draft-status" hidden></div>
      </div>
      <div class="builder-panel">
        <div class="panel-title">Publications</div>
        <div class="draft-controls">
          <select id="publication-select" aria-label="Published visual graphs"></select>
          <button id="run-publication" class="secondary compact" type="button">Run</button>
          <button id="reload-publications" class="secondary compact" type="button">Reload</button>
        </div>
        <div class="draft-controls">
          <select id="golden-case-select" aria-label="Golden regression cases"></select>
          <button id="save-golden-case" class="secondary compact" type="button">Save Golden</button>
          <button id="run-golden-case" class="secondary compact" type="button">Run Golden</button>
          <button id="delete-golden-case" class="secondary compact danger" type="button">Delete</button>
          <button id="run-golden-suite" class="secondary compact" type="button">Run Suite</button>
          <button id="certify-golden-suite" class="secondary compact" type="button">Certify</button>
        </div>
        <div class="golden-assertion-controls">
          <select id="golden-assertion-mode" aria-label="Golden assertion mode"></select>
          <input id="golden-assertion-path" type="text" placeholder="/approved" aria-label="Golden assertion JSON pointer">
          <textarea id="golden-assertion-value" spellcheck="false" aria-label="Golden assertion expected JSON"></textarea>
        </div>
        <div id="golden-certification-status" class="certification-status" hidden></div>
        <div id="publication-status" class="draft-status" hidden></div>
      </div>
      <div class="builder-panel">
        <div class="panel-title">Run History</div>
        <div class="run-history-controls">
          <select id="run-history-source" aria-label="Filter run history by source"></select>
          <select id="run-history-outcome" aria-label="Filter run history by outcome"></select>
          <input id="run-history-limit" type="number" min="1" max="50" value="${escapeHtml(state.runHistoryFilters.limit)}" aria-label="Run history limit">
          <button id="reload-run-history" class="secondary compact" type="button">Reload</button>
        </div>
        <div id="run-history-status" class="draft-status" hidden></div>
        <div id="run-history-stats" class="run-history-stats"></div>
        <div id="run-history-list" class="run-history-list"></div>
      </div>
      <div id="selected-operator-editor" class="builder-panel"></div>
      <div id="graph-output-editor" class="builder-panel"></div>
      <div class="builder-panel">
        <div class="panel-title">Server Check</div>
        <div class="visual-check-actions">
          <button id="validate-visual-draft" class="secondary compact" type="button">Validate</button>
          <button id="compile-visual-draft" class="secondary compact" type="button">Compile</button>
          <button id="publish-visual-draft" class="secondary compact" type="button">Publish</button>
        </div>
        <div id="visual-check-status" class="visual-check-status"></div>
        <div id="visual-diagnostics" class="visual-diagnostics"></div>
      </div>
      <div class="field">
        <label for="composer-dsl">DSL Preview</label>
        <textarea id="composer-dsl" class="code-editor" spellcheck="false"></textarea>
      </div>
      <div class="field">
        <label for="graph-input-schema">Graph Input Schema</label>
        <textarea id="graph-input-schema" class="schema-editor" spellcheck="false"></textarea>
        <div id="graph-input-schema-status" class="schema-status" hidden></div>
        <div id="graph-input-schema-diagnostics" class="visual-diagnostics"></div>
      </div>
      <div class="field">
        <label for="composer-context">Context JSON</label>
        <textarea id="composer-context" class="context-editor" spellcheck="false"></textarea>
      </div>
      <div class="composer-actions">
        <button id="reset-composer" class="secondary compact" type="button">Reset</button>
      </div>
    `;
    $('composer-dsl').value = state.customDsl;
    $('graph-input-schema').value = state.graphInputSchemaText;
    $('composer-context').value = state.customContextText;
    renderOperatorPalette();
    renderScopeStatus();
    renderConnectionStatus();
    renderOperatorLibraryControls();
    renderResourceContractImportControls();
    renderDraftControls();
    renderPublicationControls();
    renderRunHistoryControls();
    renderSelectedOperatorEditor();
    renderGraphOutputEditor();
    renderVisualCheck();
    $('composer-dsl').addEventListener('input', (event) => {
      state.customDsl = event.target.value;
    });
    $('graph-input-schema').addEventListener('input', (event) => {
      updateGraphInputSchemaFromText(event.target.value);
    });
    renderGraphInputSchemaStatus();
    $('composer-context').addEventListener('input', (event) => {
      state.customContextText = event.target.value;
      renderSelectedOperatorEditor();
    });
    $('reset-composer').addEventListener('click', resetComposer);
    $('apply-scope').addEventListener('click', applyBuilderScopeFromControls);
    $('validate-visual-draft').addEventListener('click', validateVisualDraft);
    $('compile-visual-draft').addEventListener('click', compileVisualDraft);
    $('publish-visual-draft').addEventListener('click', publishVisualDraft);
    return;
  }
  if (state.selected.samplePresets?.length) {
    const presets = document.createElement('div');
    presets.className = 'preset-grid';
    for (const preset of state.selected.samplePresets) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'preset-button';
      const expected = [preset.expected?.ruleId, preset.expected?.decision].filter(Boolean).join(' / ');
      button.innerHTML = `<strong>${escapeHtml(preset.label)}</strong><span>${escapeHtml(expected)}</span>`;
      button.addEventListener('click', () => applyPreset(preset));
      presets.appendChild(button);
    }
    form.appendChild(presets);
  }
  for (const [key, value] of Object.entries(state.selected.sampleInput)) {
    const wrapper = document.createElement('div');
    wrapper.className = 'field';
    wrapper.innerHTML = `<label for="input-${key}">${key}</label><input id="input-${key}" name="${key}" value="${value}">`;
    form.appendChild(wrapper);
  }
}

function applyPreset(preset) {
  for (const [key, value] of Object.entries(preset.values || {})) {
    const field = $(`input-${key}`);
    if (field) {
      field.value = value;
    }
  }
  runScenario();
}

function inputValues() {
  const values = {};
  for (const field of $('input-form').querySelectorAll('input')) {
    values[field.name] = field.value;
  }
  return values;
}

function createDefaultBuilder() {
  return {
    graphName: 'customLoanPolicy',
    tenantId: 'demo-tenant',
    namespace: 'local',
    environment: 'browser',
    inputSchema: defaultGraphInputSchema(),
    selectedId: 'loanPolicy',
    output: { nodeId: 'response', path: '' },
    dependencyEdges: [],
    routeEdges: [],
    nodes: [
      {
        id: 'loanPolicy',
        type: 'decisionTable',
        x: 80,
        y: 210,
        hitPolicy: 'unique',
        scoreSource: 'ctx.score',
        amountSource: 'ctx.amount',
        rules: defaultDecisionRules()
      },
      {
        id: 'response',
        type: 'transform',
        x: 360,
        y: 210
      }
    ]
  };
}

function defaultDecisionRules() {
  return DEFAULT_COMPOSER_DECISION_TABLE.rows.map((row) => ({
    id: row.id,
    score: row.conditions.score,
    amount: row.conditions.amount,
    decision: row.output.decision,
    rate: row.output.rate,
    maxTerm: row.output.maxTerm,
    reviewLane: row.output.reviewLane,
    otherwise: row.conditions.score === 'otherwise'
  }));
}

function isComposerSelected() {
  return state.selected?.graphName === COMPOSER_GRAPH;
}

function currentDecisionTable() {
  return isComposerSelected() ? state.customDecisionTable : state.selected?.decisionTable;
}

function orderedBuilderNodes(builder = state.builder) {
  return [...builder.nodes].sort((left, right) => {
    if (left.x === right.x) {
      return left.y - right.y;
    }
    return left.x - right.x;
  });
}

function orderedDslNodes(builder = state.builder) {
  const rank = { httpResource: 0, customOperator: 1, decisionTable: 2, transform: 3 };
  return [...builder.nodes].sort((left, right) => {
    const leftRank = rank[left.type] ?? 9;
    const rightRank = rank[right.type] ?? 9;
    if (leftRank === rightRank) {
      return left.x - right.x;
    }
    return leftRank - rightRank;
  });
}

function builderEdges(builder = state.builder, options = {}) {
  const edges = [];
  const includeFallback = options.includeFallback !== false;
  const includeConfig = options.includeConfig === true;
  const add = (edge) => {
    if (!edge.source || !edge.target || edge.source === edge.target) return;
    const kind = canonicalEdgeKind(edge.kind);
    const key = kind === 'dependency'
      ? [kind, edge.source, edge.target].join(':')
      : (kind === 'route'
        ? [kind, edge.source, edge.target, edge.condition || 'otherwise'].join(':')
      : [
          kind,
          edge.source,
          edge.sourcePort || '',
          edge.sourcePath || '',
          edge.target,
          edge.targetPort || '',
          edge.targetPath || ''
        ].join(':'));
    if (edges.some((item) => item.key === key)) return;
    edges.push({ key, label: '', ...edge, kind });
  };

  for (const edge of builder.dependencyEdges || []) {
    add({
      kind: 'dependency',
      source: edge.source,
      target: edge.target,
      sourcePort: edge.sourcePort || 'output',
      sourcePath: edge.sourcePath || '',
      targetPort: 'dependency',
      targetPath: '',
      label: edge.label || 'depends'
    });
  }

  for (const edge of builder.routeEdges || []) {
    add({
      kind: 'route',
      source: edge.source,
      target: edge.target,
      sourcePort: 'route',
      sourcePath: '',
      targetPort: 'route',
      targetPath: '',
      condition: edge.condition || 'otherwise',
      label: edge.condition || 'otherwise'
    });
  }

  const decisions = builder.nodes.filter((node) => node.type === 'decisionTable');
  const transforms = builder.nodes.filter((node) => node.type === 'transform');

  for (const transform of transforms) {
    if (transform.policyNodeCleared) {
      continue;
    }
    const decision = builder.nodes.find((node) => node.id === transform.policyNode)
      || decisions[0];
    if (decision) {
      add({
        source: decision.id,
        target: transform.id,
        sourcePort: specForNode(decision).outputPort || 'output',
        sourcePath: '',
        targetPort: specForNode(transform).inputPort || 'inputs',
        targetPath: 'policy',
        label: 'policy'
      });
    } else {
      const previous = orderedBuilderNodes(builder).filter((node) => node.id !== transform.id).at(-1);
      if (previous) {
        add({
          source: previous.id,
          target: transform.id,
          sourcePort: specForNode(previous).outputPort || 'output',
          sourcePath: '',
          targetPort: specForNode(transform).inputPort || 'inputs',
          targetPath: 'result'
        });
      }
    }
  }

  for (const node of builder.nodes) {
    for (const binding of builderInputBindings(node)) {
      const source = connectionSourceFromExpression(binding.expression, builder);
      if (!source || source.nodeId === CONTEXT_SOURCE_ID) continue;
      add({
        source: source.nodeId,
        target: node.id,
        sourcePort: source.port,
        sourcePath: source.path,
        targetPort: binding.targetPort,
        targetPath: binding.targetPath,
        label: binding.label || 'data'
      });
    }
    if (includeConfig) {
      for (const binding of builderConfigBindings(node, builder)) {
        const source = connectionSourceFromExpression(binding.expression, builder);
        if (!source || source.nodeId === CONTEXT_SOURCE_ID) continue;
        add({
          source: source.nodeId,
          target: node.id,
          sourcePort: source.port,
          sourcePath: source.path,
          targetPort: 'config',
          targetPath: binding.targetPath,
          label: binding.label || 'config'
        });
      }
    }
  }

  if (includeFallback && edges.length === 0) {
    const ordered = orderedBuilderNodes(builder);
    for (let i = 0; i < ordered.length - 1; i++) {
      add({
        source: ordered[i].id,
        target: ordered[i + 1].id,
        sourcePort: specForNode(ordered[i]).outputPort || 'output',
        sourcePath: '',
        targetPort: specForNode(ordered[i + 1]).inputPort || 'inputs',
        targetPath: ''
      });
    }
  }
  return edges.map(({ key, ...edge }) => edge);
}

function canonicalEdgeKind(kind) {
  const value = String(kind || '').trim().toLowerCase();
  if (!value || value === 'data') {
    return 'data';
  }
  if (value === 'dependency' || value === 'dependson' || value === 'depends_on') {
    return 'dependency';
  }
  if (value === 'route' || value === 'branch') {
    return 'route';
  }
  return value;
}

function builderConfigBindings(node, builder = state.builder) {
  const spec = specForNode(node);
  return configFieldDescriptors(spec.configSchema)
    .map((field) => {
      const value = configValueAtPath(node.config || {}, field.path);
      const expression = isConfigExpressionValue(value) ? configExpressionForField(value) : '';
      return {
        expression,
        targetPath: field.path,
        label: `config:${field.path}`
      };
    })
    .filter((binding) => binding.expression && connectionSourceFromExpression(binding.expression, builder));
}

function builderInputBindings(node) {
  const spec = specForNode(node);
  if (node.type === 'httpResource') {
    return Object.entries(resourceParamInputs(node, spec)).map(([inputName, expression]) => ({
      inputName,
      expression,
      targetPort: spec.inputPort || 'params',
      targetPath: inputName,
      label: inputName
    }));
  }
  if (node.type === 'decisionTable') {
    return [
      {
        inputName: 'score',
        expression: node.scoreSource,
        targetPort: spec.inputPort || 'inputs',
        targetPath: 'score',
        label: 'score'
      },
      {
        inputName: 'amount',
        expression: node.amountSource,
        targetPort: spec.inputPort || 'inputs',
        targetPath: 'amount',
        label: 'amount'
      }
    ];
  }
  if (node.type === 'customOperator') {
    return Object.entries(node.customInputs || {}).map(([inputName, expression]) => ({
      inputName,
      expression,
      targetPort: customInputPortForKey(node, spec, inputName),
      targetPath: customInputPathForKey(node, inputName),
      label: inputName
    }));
  }
  return [];
}

function connectionSourceFromExpression(expression, builder = state.builder) {
  const value = String(expression || '').trim();
  if (value === 'ctx') {
    return contextSourceForPath('', builder);
  }
  if (value.startsWith('ctx.') || value.startsWith('ctx[')) {
    const rawPath = value.startsWith('ctx.') ? value.slice(4) : value.slice(3);
    const schemaPath = schemaPathFromDslReferenceSuffix(rawPath);
    if (schemaPath !== null) {
      return contextSourceForPath(schemaPath, builder);
    }
  }
  const outputMatch = value.match(/^([A-Za-z_][A-Za-z0-9_]*)\.output(?=$|[.\[])/);
  if (outputMatch) {
    const outputSuffix = value.slice(outputMatch[0].length);
    const rawOutputPath = outputSuffix.startsWith('.') ? outputSuffix.slice(1) : outputSuffix;
    const sourceNode = builder.nodes.find((node) => node.id === outputMatch[1]);
    if (sourceNode) {
      const handle = sourceHandlesForNode(sourceNode).find((candidate) =>
        expressionForConnectionSource(candidate) === value
      );
      if (handle) {
        return { ...handle, path: handle.path || '' };
      }
      return sourceFromOutputExpressionParts(sourceNode, rawOutputPath);
    }
    const path = schemaPathFromDslReferenceSuffix(rawOutputPath);
    return {
      nodeId: outputMatch[1],
      port: 'output',
      path: path || '',
      dslPathSafe: path !== null
    };
  }
  return null;
}

function sourceFromOutputExpressionParts(sourceNode, outputSuffix) {
  const spec = specForNode(sourceNode);
  const outputPorts = outputPortsForSpec(spec);
  const primaryPort = outputPorts[0]?.name || spec.outputPort || 'output';
  let port = primaryPort;
  let path = String(outputSuffix || '');
  if (path) {
    for (const candidate of outputPorts) {
      const candidateName = candidate.name || spec.outputPort || 'output';
      if (path === candidateName && (outputPorts.length > 1 || candidateName !== 'output')) {
        port = candidateName;
        path = '';
        break;
      }
      if (path.startsWith(`${candidateName}.`)
          && (outputPorts.length > 1 || candidateName !== 'output' || path.length > candidateName.length + 1)) {
        port = candidateName;
        path = path.slice(candidateName.length + 1);
        break;
      }
      if (path.startsWith(`${candidateName}[`) && (outputPorts.length > 1 || candidateName !== 'output')) {
        port = candidateName;
        path = path.slice(candidateName.length);
        break;
      }
    }
  }
  path = schemaPathFromDslReferenceSuffix(path) ?? path;
  const portSchema = schemaForPort(spec, 'source', port);
  const schema = schemaAtPath(portSchema, path);
  return {
    nodeId: sourceNode.id,
    port,
    path,
    type: schemaType(schema),
    schema,
    dslPathSafe: isSchemaPathDslSafe(portSchema, path)
  };
}

function selectedBuilderNode() {
  return state.builder.nodes.find((node) => node.id === state.builder.selectedId) || null;
}

function specForNode(node) {
  if (node?.paletteType && OPERATOR_TYPES[node.paletteType]) {
    return OPERATOR_TYPES[node.paletteType];
  }
  if (node?.type === 'customOperator' && node?.paletteType) {
    return unavailableOperatorSpec(node.paletteType);
  }
  return OPERATOR_TYPES[node?.type] || OPERATOR_TYPES.transform;
}

function unavailableOperatorSpec(operatorRef) {
  return {
    label: `Unavailable: ${readableName(operatorRef)}`,
    kind: 'unavailable',
    operatorRef,
    visualOperatorRef: operatorRef,
    inputPort: 'inputs',
    outputPort: 'output',
    baseId: baseIdForResource(operatorRef),
    inputPorts: [],
    outputPorts: [],
    inputSchema: { schema: { type: 'opaque' } },
    outputSchema: { schema: { type: 'opaque' } },
    configSchema: { schema: { type: 'opaque' } },
    unavailable: true
  };
}

function payloadData(payload) {
  if (!payload) {
    return null;
  }
  return payload.data ?? payload.output ?? payload;
}

function resetComposer() {
  state.builder = createDefaultBuilder();
  state.currentDraftId = '';
  state.currentDraftRevision = 0;
  state.savedDraftSnapshot = null;
  state.draftRevisions = [];
  state.selectedDraftRevision = 0;
  state.previewingDraftRevision = 0;
  state.draftMessage = null;
  state.visualCheck = { message: 'Not checked', level: 'info', diagnostics: [] };
  state.customDsl = builderToDsl(state.builder);
  state.lastGeneratedVisualDsl = '';
  syncGraphInputSchemaTextFromBuilder({ render: false });
  state.customContextText = pretty(DEFAULT_COMPOSER_CONTEXT);
  state.customDecisionTable = decisionTableFromBuilder(state.builder);
  state.layout = layoutFromBuilder(state.builder);
  state.selectedNodeId = state.builder.selectedId;
  state.lastPayload = null;
  renderScenario();
}

function renderOperatorPalette() {
  const target = $('operator-palette');
  if (!target) return;
  const entries = Object.entries(OPERATOR_TYPES)
    .filter(([, spec]) => spec.paletteVisible !== false);
  renderOperatorPaletteFilters(entries);
  const filteredEntries = entries.filter(([type, spec]) => operatorMatchesPaletteFilter(type, spec));
  renderOperatorPaletteSummary(filteredEntries.length, entries.length);
  target.innerHTML = filteredEntries
    .map(([type, spec]) => `
    <button
      class="operator-card ${escapeHtml(spec.kind)}"
      type="button"
      data-operator-type="${escapeHtml(type)}"
      data-testid="operator-${escapeHtml(type)}">
      <strong>${escapeHtml(spec.label)}</strong>
      <span>${escapeHtml(spec.kind)}</span>
      <small>${escapeHtml(operatorPaletteContractSummary(spec))}</small>
      ${operatorPaletteTagBadges(spec)}
    </button>
  `).join('') || '<div class="palette-empty">No matching operators.</div>';
  for (const button of target.querySelectorAll('[data-operator-type]')) {
    button.addEventListener('pointerdown', (event) => startPaletteDrag(event, button));
    button.addEventListener('dragstart', (event) => {
      state.draggingOperatorType = button.dataset.operatorType;
      event.dataTransfer.setData('application/x-bloge-operator', button.dataset.operatorType);
      event.dataTransfer.setData('text/plain', button.dataset.operatorType);
    });
    button.addEventListener('dragend', cancelPaletteDrag);
    button.addEventListener('click', () => {
      if (state.suppressPaletteClick) {
        state.suppressPaletteClick = false;
        return;
      }
      addBuilderNode(button.dataset.operatorType);
      state.draggingOperatorType = null;
    });
  }
}

function renderOperatorPaletteFilters(entries) {
  const search = $('operator-palette-search');
  if (search) {
    search.value = state.paletteSearch || '';
    search.oninput = (event) => {
      state.paletteSearch = event.target.value;
      renderOperatorPalette();
    };
  }

  const kinds = [...new Set(entries.map(([, spec]) => spec.kind).filter(Boolean))].sort();
  const kindSelect = $('operator-palette-kind');
  if (kindSelect) {
    const selected = kinds.includes(state.paletteKind) ? state.paletteKind : '';
    state.paletteKind = selected;
    kindSelect.innerHTML = ['<option value="">All types</option>']
      .concat(kinds.map((kind) => `<option value="${escapeHtml(kind)}">${escapeHtml(kind)}</option>`))
      .join('');
    kindSelect.value = selected;
    kindSelect.onchange = (event) => {
      state.paletteKind = event.target.value;
      renderOperatorPalette();
    };
  }

  const tags = [...new Set(entries.flatMap(([, spec]) => spec.tags || []).filter(Boolean))].sort();
  const tagSelect = $('operator-palette-tag');
  if (tagSelect) {
    const selected = tags.includes(state.paletteTag) ? state.paletteTag : '';
    state.paletteTag = selected;
    tagSelect.innerHTML = ['<option value="">All tags</option>']
      .concat(tags.map((tag) => `<option value="${escapeHtml(tag)}">${escapeHtml(tag)}</option>`))
      .join('');
    tagSelect.value = selected;
    tagSelect.onchange = (event) => {
      state.paletteTag = event.target.value;
      renderOperatorPalette();
    };
  }
}

function renderOperatorPaletteSummary(visible, total) {
  const target = $('operator-palette-summary');
  if (!target) return;
  const filters = [
    state.paletteSearch ? `search "${state.paletteSearch.trim()}"` : '',
    state.paletteKind ? `type ${state.paletteKind}` : '',
    state.paletteTag ? `tag ${state.paletteTag}` : ''
  ].filter(Boolean);
  target.textContent = filters.length
    ? `${visible} of ${total} operators match ${filters.join(', ')}.`
    : `${total} operators available.`;
}

function operatorMatchesPaletteFilter(type, spec) {
  if (state.paletteKind && spec.kind !== state.paletteKind) {
    return false;
  }
  const tags = spec.tags || [];
  if (state.paletteTag && !tags.includes(state.paletteTag)) {
    return false;
  }
  const query = String(state.paletteSearch || '').trim().toLowerCase();
  if (!query) {
    return true;
  }
  return [
    type,
    spec.label,
    spec.kind,
    spec.operatorRef,
    spec.visualOperatorRef,
    spec.resourceId,
    spec.description,
    spec.sourceKind,
    ...operatorPaletteSearchValues(spec),
    ...tags
  ]
    .filter(Boolean)
    .some((value) => String(value).toLowerCase().includes(query));
}

function operatorPaletteContractSummary(spec) {
  const inputs = inputPortsForSpec(spec);
  const outputs = outputPortsForSpec(spec);
  const inputFields = inputs.flatMap((port) => schemaFieldDescriptors(port.schema)).length;
  const outputFields = outputs.flatMap((port) => schemaFieldDescriptors(port.schema)).length;
  return `In ${inputs.length}/${inputFields} fields · Out ${outputs.length}/${outputFields} fields`;
}

function operatorPaletteTagBadges(spec) {
  const tags = (spec.tags || []).slice(0, 3);
  if (!tags.length) {
    return '';
  }
  return `<div class="operator-card-tags">${tags.map((tag) =>
    `<em>${escapeHtml(tag)}</em>`).join('')}</div>`;
}

function operatorPaletteSearchValues(spec) {
  return [
    ...inputPortsForSpec(spec).flatMap((port) => [
      port.name,
      ...schemaFieldDescriptors(port.schema).map((field) => field.path)
    ]),
    ...outputPortsForSpec(spec).flatMap((port) => [
      port.name,
      ...schemaFieldDescriptors(port.schema).map((field) => field.path)
    ])
  ];
}

function renderConnectionStatus() {
  const target = $('connection-status');
  if (!target) return;
  if (!state.connectionMessage?.text) {
    target.hidden = true;
    target.textContent = '';
    target.className = 'connection-status';
    return;
  }
  target.hidden = false;
  target.textContent = state.connectionMessage.text;
  target.className = `connection-status ${state.connectionMessage.level || 'info'}`;
}

function setConnectionMessage(text, level = 'info') {
  state.connectionMessage = text ? { text, level } : null;
  renderConnectionStatus();
}

function renderOperatorLibraryControls() {
  const select = $('library-select');
  const editor = $('operator-library-json');
  if (!select || !editor) return;
  const options = ['<option value="">New library</option>']
    .concat(state.operatorLibraries.map((library) => {
      const count = Array.isArray(library.operators) ? library.operators.length : 0;
      const label = `${library.displayName || library.libraryId} (${count})`;
      return `<option value="${escapeHtml(library.libraryId)}">${escapeHtml(label)}</option>`;
    }));
  select.innerHTML = options.join('');
  select.value = state.selectedLibraryId || '';
  editor.value = state.libraryImportText || pretty(SAMPLE_OPERATOR_LIBRARY);

  select.onchange = () => {
    state.selectedLibraryId = select.value;
    const library = state.operatorLibraries.find((item) => item.libraryId === select.value);
    if (library) {
      state.libraryImportText = pretty(library);
    }
    state.libraryMessage = null;
    state.libraryImportConfirmationKey = '';
    renderOperatorLibraryControls();
  };
  editor.oninput = () => {
    state.libraryImportText = editor.value;
    state.libraryImportConfirmationKey = '';
  };

  const importButton = $('import-library');
  const validateButton = $('validate-library');
  const reloadButton = $('reload-libraries');
  const deleteButton = $('delete-library');
  const forceToggle = $('library-force');
  if (forceToggle) {
    forceToggle.checked = Boolean(state.libraryForce);
    forceToggle.onchange = () => {
      state.libraryForce = forceToggle.checked;
      state.libraryMessage = null;
      state.libraryImportConfirmationKey = '';
      renderLibraryStatus();
    };
  }
  if (validateButton) {
    validateButton.onclick = validateOperatorLibrary;
  }
  if (importButton) {
    importButton.onclick = importOperatorLibrary;
  }
  if (reloadButton) {
    reloadButton.onclick = reloadOperatorLibrariesAndCatalog;
  }
  if (deleteButton) {
    deleteButton.disabled = !state.selectedLibraryId;
    deleteButton.onclick = deleteSelectedOperatorLibrary;
  }
  renderLibraryStatus();
}

function renderLibraryStatus() {
  const target = $('library-status');
  if (!target) return;
  const current = state.selectedLibraryId
    ? `Selected ${state.selectedLibraryId}`
    : `${state.operatorLibraries.length} imported libraries`;
  const message = state.libraryMessage?.text || current;
  target.hidden = false;
  target.textContent = message;
  target.className = `library-status ${state.libraryMessage?.level || 'info'}`;
  renderDiagnosticList($('library-diagnostics'), normalizeDiagnostics(state.libraryMessage?.diagnostics));
}

function setLibraryMessage(text, level = 'info', diagnostics = []) {
  state.libraryMessage = text ? { text, level, diagnostics: normalizeDiagnostics(diagnostics) } : null;
  renderLibraryStatus();
}

function renderVisualCheck() {
  const status = $('visual-check-status');
  const list = $('visual-diagnostics');
  if (!status || !list) return;
  const check = state.visualCheck || {};
  status.textContent = check.message || 'Not checked';
  status.className = `visual-check-status ${check.level || 'info'}`;
  renderDiagnosticList(list, check.diagnostics || []);
}

function renderDiagnosticList(list, diagnostics) {
  if (!list) return;
  const normalized = normalizeDiagnostics(diagnostics);
  if (!normalized.length) {
    list.innerHTML = '';
    list.hidden = true;
    return;
  }
  list.hidden = false;
  list.innerHTML = normalized.map((diagnostic) => {
    const level = String(diagnostic.level || 'INFO').toLowerCase();
    const target = diagnostic.target ? ` · ${diagnostic.target}` : '';
    const location = diagnostic.line >= 0 ? ` · ${diagnostic.line}:${diagnostic.column}` : '';
    return `
      <div class="visual-diagnostic ${escapeHtml(level)}">
        <strong>${escapeHtml(diagnostic.code || diagnostic.level || 'visual.info')}</strong>
        <span>${escapeHtml((diagnostic.message || '') + target + location)}</span>
      </div>
    `;
  }).join('');
}

function setVisualCheck(message, level = 'info', diagnostics = []) {
  state.visualCheck = {
    message,
    level,
    diagnostics: normalizeDiagnostics(diagnostics)
  };
  renderVisualCheck();
}

function normalizeDiagnostics(diagnostics) {
  return Array.isArray(diagnostics) ? diagnostics : [];
}

function diagnosticMessage(diagnostics, fallback) {
  const error = diagnostics.find((diagnostic) => String(diagnostic.level || '').toUpperCase() === 'ERROR');
  const warning = diagnostics.find((diagnostic) => String(diagnostic.level || '').toUpperCase() === 'WARNING');
  return error?.message || warning?.message || fallback;
}

function validationResultMessage(valid, diagnostics, successMessage) {
  if (!diagnostics.length) {
    return valid === false ? 'Validation failed.' : successMessage;
  }
  const prefix = valid === false ? 'Invalid' : 'Valid with warnings';
  const summary = diagnostics.slice(0, 3).map((diagnostic) => {
    const code = diagnostic.code || diagnostic.level || 'visual.info';
    const target = diagnostic.target ? ` @ ${diagnostic.target}` : '';
    return `${code}: ${diagnostic.message || ''}${target}`;
  }).join(' | ');
  const remaining = diagnostics.length > 3 ? ` | +${diagnostics.length - 3} more` : '';
  return `${prefix}: ${summary}${remaining}`;
}

function visualCheckLevel(diagnostics, success = true) {
  if (diagnostics.some((diagnostic) => String(diagnostic.level || '').toUpperCase() === 'ERROR')) {
    return 'error';
  }
  if (diagnostics.some((diagnostic) => String(diagnostic.level || '').toUpperCase() === 'WARNING')) {
    return 'warning';
  }
  return success ? 'success' : 'error';
}

function hasWarningDiagnostic(diagnostics) {
  return diagnostics.some((diagnostic) => String(diagnostic.level || '').toUpperCase() === 'WARNING');
}

async function validateVisualDraft() {
  setVisualCheck('Validating...', 'info');
  try {
    const response = await fetch('/api/visual/drafts/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(builderToVisualDraft(state.builder))
    });
    const payload = await response.json();
    const diagnostics = normalizeDiagnostics(payload.diagnostics);
    setVisualCheck(
      payload.valid ? 'Valid visual graph.' : 'Visual graph has errors.',
      visualCheckLevel(diagnostics, payload.valid),
      diagnostics
    );
    $('output').textContent = pretty({ status: response.status, validation: payload });
  } catch (error) {
    setVisualCheck(error.message, 'error');
  }
}

async function compileVisualDraft() {
  setVisualCheck('Compiling...', 'info');
  try {
    const response = await fetch('/api/visual/drafts/compile', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(builderToVisualDraft(state.builder))
    });
    const payload = await response.json();
    const diagnostics = normalizeDiagnostics(payload.diagnostics);
    if (payload.dsl) {
      state.customDsl = payload.dsl;
      state.lastGeneratedVisualDsl = payload.dsl;
      const dslBox = $('composer-dsl');
      if (dslBox) {
        dslBox.value = payload.dsl;
      }
    }
    setVisualCheck(
      payload.generated ? 'Compiled visual graph.' : 'Visual graph did not compile.',
      visualCheckLevel(diagnostics, payload.generated),
      diagnostics
    );
    $('output').textContent = pretty({ status: response.status, compile: payload });
  } catch (error) {
    setVisualCheck(error.message, 'error');
  }
}

async function publishVisualDraft() {
  setVisualCheck('Publishing...', 'info');
  try {
    const stored = await saveCurrentDraft();
    if (!stored?.draftId) {
      setVisualCheck('Draft was not saved.', 'error');
      return;
    }
    const draftId = stored.draftId;
    const expectedRevision = stored.revision || state.currentDraftRevision || 0;
    const response = await fetch(`/api/visual/drafts/${encodeURIComponent(draftId)}/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedRevision })
    });
    const payload = await response.json();
    const diagnostics = normalizeDiagnostics(payload.diagnostics);
    if (response.status === 409) {
      await loadCurrentDraftSnapshot();
      await loadDraftList();
    }
    const publication = payload.publication || {};
    if (payload.published && publication.publicationId) {
      state.selectedPublicationId = publication.publicationId;
      await loadPublicationList({ render: false });
      await loadGoldenCases({ render: false });
      await loadGoldenCertificationStatus({ render: false });
      await loadVisualOperatorCatalog();
      renderOperatorPalette();
      renderPublicationControls();
      setPublicationMessage(`Published ${publication.publicationId}.`, 'success');
    }
    setVisualCheck(
      payload.published ? `Published ${publication.publicationId || ''}.` : 'Visual graph was not published.',
      visualCheckLevel(diagnostics, payload.published),
      diagnostics
    );
    $('output').textContent = pretty({ status: response.status, publication: payload });
  } catch (error) {
    setVisualCheck(error.message, 'error');
  }
}

async function loadOperatorLibraries(options = {}) {
  try {
    const response = await fetch('/admin/visual-operator-libraries');
    if (!response.ok) {
      throw new Error(`Library list failed with ${response.status}`);
    }
    state.operatorLibraries = await response.json();
    if (state.selectedLibraryId
        && !state.operatorLibraries.some((library) => library.libraryId === state.selectedLibraryId)) {
      state.selectedLibraryId = '';
      state.libraryImportText = pretty(SAMPLE_OPERATOR_LIBRARY);
      state.libraryImportConfirmationKey = '';
    }
    if (options.render !== false) {
      renderOperatorLibraryControls();
    }
  } catch (error) {
    setLibraryMessage(error.message, 'error');
  }
}

async function reloadOperatorLibrariesAndCatalog() {
  await loadOperatorLibraries({ render: false });
  await loadVisualOperatorCatalog();
  renderOperatorPalette();
  renderOperatorLibraryControls();
  setLibraryMessage(`Loaded ${state.operatorLibraries.length} libraries.`, 'success');
}

async function validateOperatorLibrary() {
  let library;
  try {
    library = JSON.parse(state.libraryImportText || '{}');
  } catch (error) {
    setLibraryMessage(`Invalid JSON: ${error.message}`, 'error');
    return;
  }
  const { response, payload, diagnostics } = await validateOperatorLibraryPayload(library);
  if (!response.ok) {
    setLibraryMessage(`Validation failed with ${response.status}`, 'error');
    return;
  }
  state.libraryImportConfirmationKey = payload?.valid !== false && hasWarningDiagnostic(diagnostics)
    ? libraryImportConfirmationKey(library, diagnostics)
    : '';
  setLibraryMessage(
    validationResultMessage(payload?.valid, diagnostics, 'Operator library is valid.'),
    visualCheckLevel(diagnostics, payload?.valid !== false),
    diagnostics
  );
}

async function validateOperatorLibraryPayload(library) {
  const response = await fetch(`/admin/visual-operator-libraries/validate${libraryForceQuery()}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(library)
  });
  const payload = await response.json().catch(() => null);
  return {
    response,
    payload,
    diagnostics: normalizeDiagnostics(payload?.diagnostics)
  };
}

function libraryImportConfirmationKey(library, diagnostics = []) {
  return JSON.stringify({
    force: Boolean(state.libraryForce),
    library,
    warnings: normalizeDiagnostics(diagnostics)
      .filter((diagnostic) => String(diagnostic.level || '').toUpperCase() === 'WARNING')
      .map((diagnostic) => ({
        level: diagnostic.level || 'WARNING',
        code: diagnostic.code || '',
        message: diagnostic.message || '',
        target: diagnostic.target || ''
      }))
  });
}

async function importOperatorLibrary() {
  let library;
  try {
    library = JSON.parse(state.libraryImportText || '{}');
  } catch (error) {
    setLibraryMessage(`Invalid JSON: ${error.message}`, 'error');
    return;
  }
  if (!library.libraryId) {
    setLibraryMessage('libraryId is required.', 'error');
    return;
  }
  const validation = await validateOperatorLibraryPayload(library);
  const confirmationKey = libraryImportConfirmationKey(library, validation.diagnostics);
  if (!validation.response.ok || validation.payload?.valid === false) {
    state.libraryImportConfirmationKey = '';
    setLibraryMessage(
      validationResultMessage(
        validation.payload?.valid,
        validation.diagnostics,
        `Validation failed with ${validation.response.status}`
      ),
      'error',
      validation.diagnostics
    );
    return;
  }
  if (hasWarningDiagnostic(validation.diagnostics)
      && state.libraryImportConfirmationKey !== confirmationKey) {
    state.libraryImportConfirmationKey = confirmationKey;
    setLibraryMessage(
      `${validationResultMessage(true, validation.diagnostics, 'Operator library is valid.')} Review warnings, then click Import again to continue.`,
      'warning',
      validation.diagnostics
    );
    return;
  }
  if (!hasWarningDiagnostic(validation.diagnostics)) {
    state.libraryImportConfirmationKey = '';
  }
  const replacing = libraryExists(library.libraryId);
  const mutationQuery = libraryMutationQuery(hasWarningDiagnostic(validation.diagnostics));
  const endpoint = replacing
    ? `/admin/visual-operator-libraries/${encodeURIComponent(library.libraryId)}${mutationQuery}`
    : `/admin/visual-operator-libraries${mutationQuery}`;
  const response = await fetch(endpoint, {
    method: replacing ? 'PUT' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(library)
  });
  const text = await response.text();
  if (!response.ok) {
    let payload = null;
    try {
      payload = text ? JSON.parse(text) : null;
    } catch {
    }
    const diagnostics = normalizeDiagnostics(payload?.diagnostics);
    setLibraryMessage(
      validationResultMessage(payload?.valid, diagnostics, text || `Import failed with ${response.status}`),
      'error',
      diagnostics
    );
    return;
  }
  const stored = JSON.parse(text);
  state.libraryImportConfirmationKey = '';
  state.selectedLibraryId = stored.libraryId;
  state.libraryImportText = pretty(stored);
  await loadOperatorLibraries({ render: false });
  await loadVisualOperatorCatalog();
  renderOperatorPalette();
  renderOperatorLibraryControls();
  setLibraryMessage(`${replacing ? 'Replaced' : 'Imported'} ${stored.libraryId}.`, 'success');
}

async function deleteSelectedOperatorLibrary() {
  if (!state.selectedLibraryId) return;
  const forced = Boolean(state.libraryForce);
  const prompt = forced
    ? `Force delete operator library ${state.selectedLibraryId}? Stored drafts that reference it may become invalid.`
    : `Delete operator library ${state.selectedLibraryId}?`;
  if (!confirm(prompt)) return;
  const deletedId = state.selectedLibraryId;
  const response = await fetch(
    `/admin/visual-operator-libraries/${encodeURIComponent(deletedId)}${libraryForceQuery()}`,
    { method: 'DELETE' }
  );
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    const diagnostics = normalizeDiagnostics(payload?.diagnostics);
    setLibraryMessage(
      validationResultMessage(payload?.valid, diagnostics, `Delete failed with ${response.status}`),
      'error',
      diagnostics
    );
    return;
  }
  state.selectedLibraryId = '';
  state.libraryImportText = pretty(SAMPLE_OPERATOR_LIBRARY);
  state.libraryImportConfirmationKey = '';
  await loadOperatorLibraries({ render: false });
  await loadVisualOperatorCatalog();
  renderOperatorPalette();
  renderOperatorLibraryControls();
  setLibraryMessage(`Deleted ${deletedId}.`, 'success');
}

function libraryForceQuery() {
  return state.libraryForce ? '?force=true' : '';
}

function libraryMutationQuery(ackWarnings = false) {
  const params = new URLSearchParams();
  if (state.libraryForce) {
    params.set('force', 'true');
  }
  if (ackWarnings) {
    params.set('ackWarnings', 'true');
  }
  const query = params.toString();
  return query ? `?${query}` : '';
}

function libraryExists(libraryId) {
  return state.operatorLibraries.some((library) => library.libraryId === libraryId);
}

function renderResourceContractImportControls() {
  const current = state.resourceContractImport || createDefaultResourceContractImport();
  state.resourceContractImport = current;

  const resourceId = $('resource-contract-resource-id');
  const operationId = $('resource-contract-operation-id');
  const path = $('resource-contract-path');
  const method = $('resource-contract-method');
  const lifecycle = $('resource-contract-lifecycle');
  const openApiEditor = $('openapi-resource-json');
  const contractEditor = $('resource-contract-json');
  const descriptorEditor = $('resource-descriptor-json');
  if (!resourceId || !operationId || !path || !method || !lifecycle || !openApiEditor || !contractEditor
      || !descriptorEditor) {
    return;
  }

  resourceId.value = current.resourceId || '';
  operationId.value = current.operationId || '';
  path.value = current.path || '';
  method.innerHTML = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
    .map((value) => `<option value="${value}">${value}</option>`)
    .join('');
  method.value = current.method || 'GET';
  lifecycle.innerHTML = ['ACTIVE', 'DEPRECATED', 'DISABLED']
    .map((value) => `<option value="${value}">${value}</option>`)
    .join('');
  lifecycle.value = current.status || 'ACTIVE';
  openApiEditor.value = current.openApiText || pretty(SAMPLE_OPENAPI_RESOURCE_CONTRACT);
  contractEditor.value = current.contractText || '';
  descriptorEditor.value = current.descriptorText || '';

  resourceId.oninput = () => {
    current.resourceId = resourceId.value;
  };
  operationId.oninput = () => {
    current.operationId = operationId.value;
  };
  path.oninput = () => {
    current.path = path.value;
  };
  method.onchange = () => {
    current.method = method.value;
  };
  lifecycle.onchange = () => {
    current.status = lifecycle.value;
  };
  openApiEditor.oninput = () => {
    current.openApiText = openApiEditor.value;
  };
  contractEditor.oninput = () => {
    current.contractText = contractEditor.value;
    const saveButton = $('save-resource-contract');
    if (saveButton) {
      saveButton.disabled = !current.contractText;
    }
  };
  descriptorEditor.oninput = () => {
    current.descriptorText = descriptorEditor.value;
    const saveButton = $('save-resource-descriptor');
    if (saveButton) {
      saveButton.disabled = !current.descriptorText;
    }
  };

  const previewButton = $('preview-resource-contract');
  const saveContractButton = $('save-resource-contract');
  const saveDescriptorButton = $('save-resource-descriptor');
  const resetButton = $('reset-resource-contract');
  if (previewButton) {
    previewButton.onclick = previewOpenApiResourceContract;
  }
  if (saveContractButton) {
    saveContractButton.disabled = !current.contractText;
    saveContractButton.onclick = saveOpenApiResourceContract;
  }
  if (saveDescriptorButton) {
    saveDescriptorButton.disabled = !current.descriptorText;
    saveDescriptorButton.onclick = saveOpenApiResourceDescriptor;
  }
  if (resetButton) {
    resetButton.onclick = resetResourceContractImport;
  }
  renderResourceContractImportStatus();
}

function updateProjectedResourceContractText(text) {
  const current = state.resourceContractImport || createDefaultResourceContractImport();
  state.resourceContractImport = current;
  current.contractText = text || '';
  const contractEditor = $('resource-contract-json');
  if (contractEditor) {
    contractEditor.value = current.contractText;
  }
  const saveButton = $('save-resource-contract');
  if (saveButton) {
    saveButton.disabled = !current.contractText;
  }
}

function updateProjectedResourceDescriptorText(text) {
  const current = state.resourceContractImport || createDefaultResourceContractImport();
  state.resourceContractImport = current;
  current.descriptorText = text || '';
  const descriptorEditor = $('resource-descriptor-json');
  if (descriptorEditor) {
    descriptorEditor.value = current.descriptorText;
  }
  const saveButton = $('save-resource-descriptor');
  if (saveButton) {
    saveButton.disabled = !current.descriptorText;
  }
}

function renderResourceContractImportStatus() {
  const target = $('resource-contract-status-message');
  if (!target) return;
  const current = state.resourceContractImport || createDefaultResourceContractImport();
  const message = current.message?.text || 'Project one OpenAPI operation into a schema-aware resource operator contract.';
  target.hidden = false;
  target.textContent = message;
  target.className = `library-status ${current.message?.level || 'info'}`;
  renderDiagnosticList(
    $('resource-contract-diagnostics'),
    normalizeDiagnostics(current.message?.diagnostics)
  );
}

function setResourceContractImportMessage(text, level = 'info', diagnostics = []) {
  state.resourceContractImport.message = text
    ? { text, level, diagnostics: normalizeDiagnostics(diagnostics) }
    : null;
  renderResourceContractImportStatus();
}

function resetResourceContractImport() {
  state.resourceContractImport = createDefaultResourceContractImport();
  renderResourceContractImportControls();
}

async function previewOpenApiResourceContract() {
  const current = state.resourceContractImport;
  if (!current.resourceId?.trim()) {
    setResourceContractImportMessage('resourceId is required.', 'error');
    return;
  }
  if (!current.openApiText?.trim()) {
    setResourceContractImportMessage('OpenAPI JSON or YAML is required.', 'error');
    return;
  }
  updateProjectedResourceContractText('');
  updateProjectedResourceDescriptorText('');
  setResourceContractImportMessage('Projecting OpenAPI operation...', 'info');
  const request = {
    resourceId: current.resourceId.trim(),
    operationId: current.operationId?.trim() || null,
    path: current.path?.trim() || null,
    method: current.path?.trim() ? current.method || null : null,
    status: current.status || null,
    openApiText: current.openApiText
  };
  try {
    const response = await fetch('/admin/resource-design-contracts/from-openapi', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request)
    });
    const payload = await response.json().catch(() => null);
    const diagnostics = normalizeDiagnostics(payload?.validation?.diagnostics);
    if (!response.ok) {
      setResourceContractImportMessage(
        validationResultMessage(payload?.validation?.valid, diagnostics, `OpenAPI preview failed with ${response.status}`),
        'error',
        diagnostics
      );
      return;
    }
    if (payload?.contract) {
      updateProjectedResourceContractText(pretty(payload.contract));
    }
    if (payload?.descriptorSuggestion) {
      updateProjectedResourceDescriptorText(pretty(payload.descriptorSuggestion));
    }
    const valid = payload?.validation?.valid !== false;
    setResourceContractImportMessage(
      valid && payload?.contract
        ? `Projected contract ${payload.contract.resourceId}. Review contract and descriptor drafts before saving.`
        : validationResultMessage(valid, diagnostics, 'OpenAPI projection completed.'),
      visualCheckLevel(diagnostics, valid),
      diagnostics
    );
    $('output').textContent = pretty({ status: response.status, openApiContract: payload });
    renderResourceContractImportControls();
  } catch (error) {
    setResourceContractImportMessage(error.message, 'error');
  }
}

async function saveOpenApiResourceContract() {
  const current = state.resourceContractImport;
  let contract;
  try {
    contract = JSON.parse(current.contractText || '{}');
  } catch (error) {
    setResourceContractImportMessage(`Invalid contract JSON: ${error.message}`, 'error');
    return;
  }
  if (!contract.resourceId) {
    setResourceContractImportMessage('Projected contract resourceId is required.', 'error');
    return;
  }
  setResourceContractImportMessage('Saving resource contract...', 'info');
  try {
    const response = await fetch(`/admin/resource-design-contracts/${encodeURIComponent(contract.resourceId)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(contract)
    });
    const text = await response.text();
    let payload = null;
    try {
      payload = text ? JSON.parse(text) : null;
    } catch {
    }
    if (!response.ok) {
      const diagnostics = normalizeDiagnostics(payload?.diagnostics);
      setResourceContractImportMessage(
        validationResultMessage(payload?.valid, diagnostics, text || `Save failed with ${response.status}`),
        'error',
        diagnostics
      );
      return;
    }
    if (!payload) {
      setResourceContractImportMessage('Save succeeded but no contract body was returned.', 'error');
      return;
    }
    updateProjectedResourceContractText(pretty(payload));
    await loadVisualOperatorCatalog();
    renderOperatorPalette();
    renderResourceContractImportControls();
    setResourceContractImportMessage(`Saved ${payload.resourceId}; palette refreshed.`, 'success');
    $('output').textContent = pretty({ status: response.status, resourceContract: payload });
  } catch (error) {
    setResourceContractImportMessage(error.message, 'error');
  }
}

async function saveOpenApiResourceDescriptor() {
  const current = state.resourceContractImport;
  let descriptor;
  try {
    descriptor = JSON.parse(current.descriptorText || '{}');
  } catch (error) {
    setResourceContractImportMessage(`Invalid descriptor JSON: ${error.message}`, 'error');
    return;
  }
  if (!descriptor.resourceId) {
    setResourceContractImportMessage('Projected descriptor resourceId is required.', 'error');
    return;
  }
  setResourceContractImportMessage('Saving resource descriptor...', 'info');
  try {
    let response = await fetch('/admin/resources', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(descriptor)
    });
    if (response.status === 409) {
      response = await fetch(`/admin/resources/${encodeURIComponent(descriptor.resourceId)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(descriptor)
      });
    }
    const text = await response.text();
    let payload = null;
    try {
      payload = text ? JSON.parse(text) : null;
    } catch {
    }
    if (!response.ok) {
      setResourceContractImportMessage(text || `Save descriptor failed with ${response.status}`, 'error');
      return;
    }
    if (!payload) {
      setResourceContractImportMessage('Save descriptor succeeded but no descriptor body was returned.', 'error');
      return;
    }
    updateProjectedResourceDescriptorText(pretty(payload));
    await loadVisualOperatorCatalog();
    renderOperatorPalette();
    renderResourceContractImportControls();
    setResourceContractImportMessage(`Saved descriptor ${payload.resourceId}; palette refreshed.`, 'success');
    $('output').textContent = pretty({ status: response.status, resourceDescriptor: payload });
  } catch (error) {
    setResourceContractImportMessage(error.message, 'error');
  }
}

function renderDraftControls() {
  const select = $('draft-select');
  if (!select) return;
  const options = ['<option value="">New draft</option>']
    .concat(state.drafts.map((draft) => {
      const label = `${draft.graphName || draft.draftId} @${draft.revision || 0}`;
      return `<option value="${escapeHtml(draft.draftId)}">${escapeHtml(label)}</option>`;
    }));
  select.innerHTML = options.join('');
  select.value = state.currentDraftId || '';
  select.onchange = () => {
    state.currentDraftId = select.value;
    const draft = state.drafts.find((item) => item.draftId === select.value);
    state.currentDraftRevision = draft?.revision || 0;
    state.savedDraftSnapshot = draft || null;
    state.draftRevisions = [];
    state.selectedDraftRevision = 0;
    state.previewingDraftRevision = 0;
    state.draftMessage = null;
    if (state.currentDraftId) {
      loadDraftRevisions();
    }
    renderDraftControls();
  };

  const saveButton = $('save-draft');
  const loadButton = $('load-draft');
  const deleteButton = $('delete-draft');
  const exportButton = $('export-draft');
  const importButton = $('import-draft');
  const bundleEditor = $('draft-bundle-json');
  if (saveButton) {
    saveButton.onclick = saveCurrentDraft;
  }
  if (loadButton) {
    loadButton.disabled = !state.currentDraftId;
    loadButton.onclick = loadSelectedDraft;
  }
  if (deleteButton) {
    deleteButton.disabled = !state.currentDraftId;
    deleteButton.onclick = deleteSelectedDraft;
  }
  if (exportButton) {
    exportButton.disabled = !state.currentDraftId;
    exportButton.onclick = exportSelectedDraft;
  }
  if (importButton) {
    importButton.disabled = !state.draftBundleText.trim();
    importButton.onclick = importDraftBundle;
  }
  if (bundleEditor) {
    bundleEditor.value = state.draftBundleText;
    bundleEditor.oninput = () => {
      state.draftBundleText = bundleEditor.value;
      const button = $('import-draft');
      if (button) {
        button.disabled = !state.draftBundleText.trim();
      }
    };
  }
  renderDraftRevisionControls();
  renderDraftStatus();
}

function renderDraftRevisionControls() {
  const select = $('draft-revision-select');
  if (!select) return;
  const revisions = state.draftRevisions || [];
  const options = revisions.length
    ? revisions.map((draft) => {
      const selected = Number(draft.revision || 0) === Number(state.selectedDraftRevision || 0) ? ' selected' : '';
      return `<option value="${escapeHtml(draft.revision || 0)}"${selected}>${escapeHtml(revisionOptionLabel(draft))}</option>`;
    })
    : [`<option value="">${state.currentDraftId ? 'No history loaded' : 'Select a draft'}</option>`];
  select.innerHTML = options.join('');
  select.disabled = !state.currentDraftId || !revisions.length;
  select.onchange = () => {
    state.selectedDraftRevision = Number(select.value || 0);
  };

  const reloadButton = $('reload-revisions');
  const previewButton = $('preview-revision');
  const restoreButton = $('restore-revision');
  if (reloadButton) {
    reloadButton.disabled = !state.currentDraftId;
    reloadButton.onclick = loadDraftRevisions;
  }
  if (previewButton) {
    previewButton.disabled = !state.currentDraftId || !state.selectedDraftRevision;
    previewButton.onclick = previewSelectedDraftRevision;
  }
  if (restoreButton) {
    restoreButton.disabled = !state.currentDraftId || !state.selectedDraftRevision;
    restoreButton.onclick = restoreSelectedDraftRevision;
  }
}

function revisionOptionLabel(draft) {
  const nodeCount = Array.isArray(draft.nodes) ? draft.nodes.length : 0;
  const metadata = draft.revisionMetadata || {};
  const actor = metadata.updatedBy || metadata.createdBy || 'visual-canvas';
  const summary = metadata.changeSummary || `${nodeCount} nodes`;
  return `@${draft.revision || 0} · ${summary} · ${actor}`;
}

function renderDraftStatus() {
  const target = $('draft-status');
  if (!target) return;
  const current = state.previewingDraftRevision
    ? `Previewing ${state.currentDraftId}@${state.previewingDraftRevision}; current server revision is @${state.currentDraftRevision || 0}`
    : state.currentDraftId
    ? `Current ${state.currentDraftId}@${state.currentDraftRevision || 0}`
    : 'Unsaved draft';
  const message = state.draftMessage?.text || current;
  target.hidden = false;
  target.textContent = message;
  target.className = `draft-status ${state.draftMessage?.level || 'info'}`;
}

function setDraftMessage(text, level = 'info') {
  state.draftMessage = text ? { text, level } : null;
  renderDraftStatus();
}

function renderPublicationControls() {
  const select = $('publication-select');
  if (!select) return;
  const options = state.publications.length
    ? state.publications.map((publication) => {
      const selected = publication.publicationId === state.selectedPublicationId ? ' selected' : '';
      return `<option value="${escapeHtml(publication.publicationId)}"${selected}>${escapeHtml(publicationOptionLabel(publication))}</option>`;
    })
    : ['<option value="">No publications</option>'];
  select.innerHTML = options.join('');
  select.disabled = !state.publications.length;
  select.onchange = async () => {
    state.selectedPublicationId = select.value;
    state.publicationMessage = null;
    state.selectedGoldenCaseId = '';
    await loadGoldenCases({ render: false });
    await loadGoldenCertificationStatus({ render: false });
    renderPublicationControls();
  };

  const runButton = $('run-publication');
  const reloadButton = $('reload-publications');
  if (runButton) {
    runButton.disabled = !state.selectedPublicationId;
    runButton.onclick = runSelectedPublication;
  }
  if (reloadButton) {
    reloadButton.onclick = async () => {
      await loadPublicationList({ render: false });
      await loadGoldenCases({ render: false });
      await loadGoldenCertificationStatus({ render: false });
      renderPublicationControls();
    };
  }
  renderGoldenCaseControls();
  renderGoldenCertificationStatus();
  renderPublicationStatus();
}

function renderGoldenCaseControls() {
  const select = $('golden-case-select');
  const saveButton = $('save-golden-case');
  const runButton = $('run-golden-case');
  const deleteButton = $('delete-golden-case');
  const suiteButton = $('run-golden-suite');
  const certifyButton = $('certify-golden-suite');
  if (!select || !saveButton || !runButton || !deleteButton || !suiteButton || !certifyButton) return;
  const options = state.goldenCases.length
    ? state.goldenCases.map((testCase) => {
      const selected = testCase.caseId === state.selectedGoldenCaseId ? ' selected' : '';
      return `<option value="${escapeHtml(testCase.caseId)}"${selected}>${escapeHtml(goldenCaseOptionLabel(testCase))}</option>`;
    })
    : ['<option value="">No golden cases</option>'];
  select.innerHTML = options.join('');
  select.disabled = !state.goldenCases.length;
  select.onchange = () => {
    state.selectedGoldenCaseId = select.value;
  };
  saveButton.disabled = !state.selectedPublicationId;
  saveButton.onclick = saveGoldenCaseFromCurrentOutput;
  runButton.disabled = !state.selectedGoldenCaseId;
  runButton.onclick = runSelectedGoldenCase;
  deleteButton.disabled = !state.selectedGoldenCaseId;
  deleteButton.onclick = deleteSelectedGoldenCase;
  suiteButton.disabled = !state.selectedPublicationId;
  suiteButton.onclick = runPublicationGoldenSuite;
  certifyButton.disabled = !state.selectedPublicationId;
  certifyButton.onclick = certifyPublicationGoldenSuite;
  renderGoldenAssertionControls();
}

function renderGoldenAssertionControls() {
  const modeSelect = $('golden-assertion-mode');
  const pathInput = $('golden-assertion-path');
  const valueInput = $('golden-assertion-value');
  if (!modeSelect || !pathInput || !valueInput) return;
  const modes = [
    ['EXACT_OUTPUT', 'Exact output'],
    ['OUTPUT_EQUALS', 'Output equals'],
    ['PATH_EQUALS', 'Path equals'],
    ['PATH_EXISTS', 'Path exists'],
    ['PATH_ABSENT', 'Path absent']
  ];
  modeSelect.innerHTML = modes.map(([value, label]) => {
    const selected = state.goldenAssertionMode === value ? ' selected' : '';
    return `<option value="${escapeHtml(value)}"${selected}>${escapeHtml(label)}</option>`;
  }).join('');
  modeSelect.disabled = !state.selectedPublicationId;
  modeSelect.onchange = () => {
    state.goldenAssertionMode = modeSelect.value || 'EXACT_OUTPUT';
    renderGoldenAssertionControls();
  };

  const pathMode = state.goldenAssertionMode === 'PATH_EQUALS'
    || state.goldenAssertionMode === 'PATH_EXISTS'
    || state.goldenAssertionMode === 'PATH_ABSENT';
  const valueMode = state.goldenAssertionMode === 'OUTPUT_EQUALS'
    || state.goldenAssertionMode === 'PATH_EQUALS';
  pathInput.value = state.goldenAssertionPath;
  pathInput.disabled = !state.selectedPublicationId || !pathMode;
  pathInput.oninput = () => {
    state.goldenAssertionPath = pathInput.value;
  };
  valueInput.value = state.goldenAssertionValueText;
  valueInput.disabled = !state.selectedPublicationId || !valueMode;
  valueInput.placeholder = valueMode ? 'Expected JSON value' : '';
  valueInput.oninput = () => {
    state.goldenAssertionValueText = valueInput.value;
  };
}

function goldenCaseOptionLabel(testCase) {
  const name = testCase.name || 'Golden case';
  const id = shortRunId(testCase.caseId || '');
  return id ? `${name} · ${id}` : name;
}

function renderGoldenCertificationStatus() {
  const target = $('golden-certification-status');
  if (!target) return;
  const status = state.goldenCertificationStatus;
  const certification = state.goldenCertification;
  target.hidden = false;
  if (!state.selectedPublicationId) {
    target.textContent = 'No publication selected';
    target.className = 'certification-status neutral';
    return;
  }
  if (status) {
    const passedCases = status.certification ? Number(status.certification.passedCases) || 0 : 0;
    const totalCases = Number(status.caseCount) || Number(status.certification?.totalCases) || 0;
    const time = status.certification?.certifiedAt
      ? ` · ${new Date(status.certification.certifiedAt).toLocaleString()}`
      : '';
    target.textContent = `${goldenCertificationStatusLabel(status)} (${passedCases}/${totalCases})${time}`;
    target.className = `certification-status ${goldenCertificationStatusClass(status)}`;
    return;
  }
  if (!certification) {
    target.textContent = 'Not certified';
    target.className = 'certification-status neutral';
    return;
  }
  const passedCases = Number(certification.passedCases) || 0;
  const totalCases = Number(certification.totalCases) || 0;
  const label = certification.certified ? 'Certified' : 'Certification failed';
  const time = certification.certifiedAt ? ` · ${new Date(certification.certifiedAt).toLocaleString()}` : '';
  target.textContent = `${label} (${passedCases}/${totalCases})${time}`;
  target.className = `certification-status ${certification.certified ? 'success' : 'error'}`;
}

function goldenCertificationStatusLabel(status) {
  switch (status.status) {
    case 'CERTIFIED':
      return status.promotionReady ? 'Promotion ready' : 'Certified';
    case 'STALE':
      return 'Certification stale';
    case 'FAILED':
      return 'Certification failed';
    case 'MISSING_CASES':
      return 'Missing golden cases';
    case 'UNCERTIFIED':
      return 'Not certified';
    default:
      return status.status || 'Certification status';
  }
}

function goldenCertificationStatusClass(status) {
  if (status.promotionReady) return 'success';
  if (status.status === 'STALE' || status.status === 'UNCERTIFIED' || status.status === 'MISSING_CASES') {
    return 'warning';
  }
  return 'error';
}

function publicationOptionLabel(publication) {
  const revision = publication.draftRevision || publication.draft?.revision || 0;
  const graph = publication.graphName || publication.draft?.graphName || publication.publicationId;
  return `${graph} @${revision} · ${publication.publicationId}`;
}

function renderPublicationStatus() {
  const target = $('publication-status');
  if (!target) return;
  const selected = state.publications.find((publication) =>
    publication.publicationId === state.selectedPublicationId);
  const current = selected
    ? `Selected ${selected.publicationId}`
    : `${state.publications.length} published artifacts`;
  const message = state.publicationMessage?.text || current;
  target.hidden = false;
  target.textContent = message;
  target.className = `draft-status ${state.publicationMessage?.level || 'info'}`;
}

function setPublicationMessage(text, level = 'info') {
  state.publicationMessage = text ? { text, level } : null;
  renderPublicationStatus();
}

function renderRunHistoryControls() {
  const source = $('run-history-source');
  const outcome = $('run-history-outcome');
  const limit = $('run-history-limit');
  const reload = $('reload-run-history');
  if (!source || !outcome || !limit || !reload) {
    return;
  }

  source.innerHTML = [
    ['','All sources'],
    ['TRANSIENT_DRAFT', 'Draft preview'],
    ['STORED_DRAFT', 'Saved draft'],
    ['PUBLICATION', 'Publication']
  ].map(([value, label]) => `<option value="${escapeHtml(value)}">${escapeHtml(label)}</option>`).join('');
  source.value = state.runHistoryFilters.sourceKind || '';
  source.onchange = () => {
    state.runHistoryFilters.sourceKind = source.value;
    loadRunHistory();
  };

  outcome.innerHTML = [
    ['', 'All outcomes'],
    ['true', 'Success'],
    ['false', 'Errors']
  ].map(([value, label]) => `<option value="${escapeHtml(value)}">${escapeHtml(label)}</option>`).join('');
  outcome.value = state.runHistoryFilters.outcome || '';
  outcome.onchange = () => {
    state.runHistoryFilters.outcome = outcome.value;
    loadRunHistory();
  };

  limit.value = state.runHistoryFilters.limit || '8';
  limit.onchange = () => {
    state.runHistoryFilters.limit = limit.value || '8';
    loadRunHistory();
  };
  reload.onclick = loadRunHistory;
  renderRunHistoryStatus();
  renderRunHistoryStats();
  renderRunHistoryList();
}

function renderRunHistoryStatus() {
  const target = $('run-history-status');
  if (!target) return;
  const message = state.runHistoryMessage?.text || `${state.runHistory.length} recent runs`;
  target.hidden = false;
  target.textContent = message;
  target.className = `draft-status ${state.runHistoryMessage?.level || 'info'}`;
}

function renderRunHistoryStats() {
  const target = $('run-history-stats');
  if (!target) return;
  const stats = state.runHistoryStats;
  if (!stats || !stats.totalRuns) {
    target.innerHTML = '<div class="run-history-empty">No SLO samples</div>';
    return;
  }
  const successRate = Math.round((Number(stats.successRate) || 0) * 100);
  target.innerHTML = [
    runHistoryStatHtml('Runs', stats.totalRuns),
    runHistoryStatHtml('Success', `${successRate}%`),
    runHistoryStatHtml('p95', `${stats.p95ElapsedMs || 0}ms`),
    runHistoryStatHtml('Blocked', stats.blockedRuns || 0)
  ].join('');
}

function runHistoryStatHtml(label, value) {
  return `
    <div class="run-history-stat">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
    </div>
  `;
}

function renderRunHistoryList() {
  const target = $('run-history-list');
  if (!target) return;
  if (!state.runHistory.length) {
    target.innerHTML = '<div class="run-history-empty">No matching runs</div>';
    return;
  }
  target.innerHTML = state.runHistory
    .map((record) => runHistoryRowHtml(record))
    .join('');
  target.querySelectorAll('[data-run-history-id]').forEach((button) => {
    button.addEventListener('click', () => openRunHistoryRecord(button.dataset.runHistoryId || ''));
  });
}

function runHistoryRowHtml(record) {
  const source = record.sourceKind || 'RUN';
  const outcome = record.success ? 'success' : record.compiled ? 'error' : 'blocked';
  const reference = record.publicationId
    ? record.publicationId
    : record.draftId
    ? `${record.draftId}@${record.draftRevision || 0}`
    : 'transient';
  const diagnostics = Array.isArray(record.diagnostics) ? record.diagnostics.length : 0;
  const errors = Array.isArray(record.errors) ? record.errors.length : 0;
  return `
    <button class="run-history-row ${escapeHtml(outcome)}" type="button" data-run-history-id="${escapeHtml(record.runId || '')}">
      <span class="run-history-main">
        <strong>${escapeHtml(record.graphName || 'unnamedGraph')}</strong>
        <small>${escapeHtml(source)} · ${escapeHtml(reference)}</small>
      </span>
      <span class="run-history-meta">
        <span>${escapeHtml(outcome)}</span>
        <small>${escapeHtml(shortRunId(record.runId || ''))} · ${escapeHtml(record.elapsedMs || 0)}ms · ${diagnostics + errors} notes</small>
      </span>
    </button>
  `;
}

function shortRunId(runId) {
  return runId && runId.length > 8 ? runId.slice(0, 8) : runId;
}

async function openRunHistoryRecord(runId) {
  if (!runId) return;
  try {
    const response = await fetch(`/api/visual/runs/${encodeURIComponent(runId)}`);
    if (!response.ok) {
      throw new Error(`Run ${runId} failed with ${response.status}`);
    }
    const payload = await response.json();
    $('output').textContent = pretty({ runHistory: payload });
    state.runHistoryMessage = { text: `Loaded ${shortRunId(runId)}.`, level: 'success' };
  } catch (error) {
    state.runHistoryMessage = { text: error.message, level: 'error' };
  }
  renderRunHistoryStatus();
}

async function loadRunHistory(options = {}) {
  try {
    const [historyResponse, statsResponse] = await Promise.all([
      fetch(runHistoryUrl()),
      fetch(runHistoryStatsUrl())
    ]);
    if (!historyResponse.ok) {
      throw new Error(`Run history failed with ${historyResponse.status}`);
    }
    if (!statsResponse.ok) {
      throw new Error(`Run history stats failed with ${statsResponse.status}`);
    }
    state.runHistory = await historyResponse.json();
    state.runHistoryStats = await statsResponse.json();
    state.runHistoryMessage = null;
  } catch (error) {
    state.runHistory = [];
    state.runHistoryStats = null;
    state.runHistoryMessage = { text: error.message, level: 'error' };
  }
  if (options.render !== false) {
    renderRunHistoryControls();
  }
  return state.runHistory;
}

function runHistoryUrl() {
  return runHistoryUrlFor('/api/visual/runs');
}

function runHistoryStatsUrl() {
  return runHistoryUrlFor('/api/visual/runs/stats');
}

function runHistoryUrlFor(path) {
  const params = new URLSearchParams();
  const filters = state.runHistoryFilters || {};
  if (filters.sourceKind) {
    params.set('sourceKind', filters.sourceKind);
  }
  if (filters.outcome) {
    params.set('success', filters.outcome);
  }
  const limit = Number.parseInt(filters.limit || '8', 10);
  params.set('limit', Number.isFinite(limit) && limit > 0 ? String(Math.min(limit, 50)) : '8');
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

async function loadPublicationList(options = {}) {
  try {
    const response = await fetch('/api/visual/publications');
    if (!response.ok) {
      throw new Error(`Publication list failed with ${response.status}`);
    }
    state.publications = await response.json();
    if (state.selectedPublicationId
        && !state.publications.some((publication) => publication.publicationId === state.selectedPublicationId)) {
      state.selectedPublicationId = '';
    }
    if (!state.selectedPublicationId && state.publications.length) {
      state.selectedPublicationId = state.publications[0].publicationId;
    }
    if (options.render !== false) {
      renderPublicationControls();
    }
    return state.publications;
  } catch (error) {
    setPublicationMessage(error.message, 'error');
    return [];
  }
}

async function loadGoldenCases(options = {}) {
  if (!state.selectedPublicationId) {
    state.goldenCases = [];
    state.selectedGoldenCaseId = '';
    state.goldenCertification = null;
    state.goldenCertificationStatus = null;
    if (options.render !== false) {
      renderGoldenCaseControls();
      renderGoldenCertificationStatus();
    }
    return state.goldenCases;
  }
  try {
    const response = await fetch(`/api/visual/golden-cases?publicationId=${encodeURIComponent(state.selectedPublicationId)}`);
    if (!response.ok) {
      throw new Error(`Golden cases failed with ${response.status}`);
    }
    state.goldenCases = await response.json();
    if (state.selectedGoldenCaseId
        && !state.goldenCases.some((testCase) => testCase.caseId === state.selectedGoldenCaseId)) {
      state.selectedGoldenCaseId = '';
    }
    if (!state.selectedGoldenCaseId && state.goldenCases.length) {
      state.selectedGoldenCaseId = state.goldenCases[0].caseId;
    }
    if (options.render !== false) {
      renderGoldenCaseControls();
    }
    return state.goldenCases;
  } catch (error) {
    state.goldenCases = [];
    state.selectedGoldenCaseId = '';
    state.goldenCertificationStatus = null;
    setPublicationMessage(error.message, 'error');
    if (options.render !== false) {
      renderGoldenCaseControls();
    }
    return [];
  }
}

async function loadGoldenCertificationStatus(options = {}) {
  if (!state.selectedPublicationId) {
    state.goldenCertification = null;
    state.goldenCertificationStatus = null;
    if (options.render !== false) {
      renderGoldenCertificationStatus();
    }
    return null;
  }
  try {
    const response = await fetch(
      `/api/visual/golden-cases/publications/${encodeURIComponent(state.selectedPublicationId)}/certification/status`
    );
    if (response.status === 404) {
      state.goldenCertification = null;
      state.goldenCertificationStatus = null;
    } else {
      if (!response.ok) {
        throw new Error(`Golden certification status failed with ${response.status}`);
      }
      state.goldenCertificationStatus = await response.json();
      state.goldenCertification = state.goldenCertificationStatus.certification || null;
    }
  } catch (error) {
    state.goldenCertificationStatus = null;
    state.goldenCertification = null;
    setPublicationMessage(error.message, 'error');
  }
  if (options.render !== false) {
    renderGoldenCertificationStatus();
  }
  return state.goldenCertificationStatus;
}

function selectedPublication() {
  return state.publications.find((publication) =>
    publication.publicationId === state.selectedPublicationId) || null;
}

function selectedGoldenCase() {
  return state.goldenCases.find((testCase) => testCase.caseId === state.selectedGoldenCaseId) || null;
}

async function deleteSelectedGoldenCase() {
  const testCase = selectedGoldenCase();
  if (!testCase) {
    setPublicationMessage('Select a golden case first.', 'error');
    return;
  }
  if (!confirm(`Delete golden case ${testCase.caseId}?`)) return;
  const deletedId = testCase.caseId;
  setPublicationMessage(`Deleting golden ${shortRunId(deletedId)}...`, 'info');
  try {
    const response = await fetch(`/api/visual/golden-cases/${encodeURIComponent(deletedId)}`, {
      method: 'DELETE'
    });
    if (!response.ok) {
      throw new Error(`Delete golden case failed with ${response.status}`);
    }
    if (state.selectedGoldenCaseId === deletedId) {
      state.selectedGoldenCaseId = '';
    }
    await loadGoldenCases({ render: false });
    await loadGoldenCertificationStatus({ render: false });
    $('output').textContent = pretty({ status: response.status, deletedGoldenCase: { caseId: deletedId } });
    setPublicationMessage(`Deleted golden ${shortRunId(deletedId)}.`, 'success');
    renderPublicationControls();
  } catch (error) {
    setPublicationMessage(error.message, 'error');
  }
}

async function saveGoldenCaseFromCurrentOutput() {
  const publication = selectedPublication();
  if (!publication) {
    setPublicationMessage('Select a publication first.', 'error');
    return;
  }
  let context;
  try {
    context = JSON.parse(state.customContextText || '{}');
  } catch (error) {
    setPublicationMessage('Context JSON is invalid.', 'error');
    return;
  }
  if (!context || Array.isArray(context) || typeof context !== 'object') {
    setPublicationMessage('Context JSON must be an object.', 'error');
    return;
  }
  if (!state.lastPayload || !Object.prototype.hasOwnProperty.call(state.lastPayload, 'data')) {
    setPublicationMessage('Run the publication before saving a golden case.', 'error');
    return;
  }
  const graphName = publication.graphName || publication.draft?.graphName || 'visual graph';
  const outputNode = state.lastPayload.composer?.outputNode || '';
  try {
    const assertions = goldenAssertionsFromControls(state.lastPayload.data);
    const response = await fetch('/api/visual/golden-cases', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        publicationId: publication.publicationId,
        name: `${graphName} golden`,
        description: 'Saved from the browser publication output.',
        outputNode,
        context,
        expectedOutput: state.lastPayload.data,
        assertions
      })
    });
    if (!response.ok) {
      throw new Error(`Save golden case failed with ${response.status}`);
    }
    const payload = await response.json();
    state.selectedGoldenCaseId = payload.caseId || '';
    setPublicationMessage(`Saved golden ${shortRunId(state.selectedGoldenCaseId)}.`, 'success');
    await loadGoldenCases({ render: false });
    renderGoldenCaseControls();
    await loadGoldenCertificationStatus();
  } catch (error) {
    setPublicationMessage(error.message, 'error');
  }
}

function goldenAssertionsFromControls(actualOutput) {
  const mode = state.goldenAssertionMode || 'EXACT_OUTPUT';
  if (mode === 'EXACT_OUTPUT') {
    return [];
  }
  if ((mode === 'PATH_EQUALS' || mode === 'PATH_EXISTS' || mode === 'PATH_ABSENT')
      && (!state.goldenAssertionPath || !state.goldenAssertionPath.startsWith('/'))) {
    throw new Error('Golden assertion path must be a JSON Pointer starting with /.');
  }
  if (mode === 'PATH_EXISTS' || mode === 'PATH_ABSENT') {
    return [{ mode, path: state.goldenAssertionPath || '', expectedValue: null }];
  }
  const expectedValue = state.goldenAssertionValueText && state.goldenAssertionValueText.trim()
    ? JSON.parse(state.goldenAssertionValueText)
    : actualOutput;
  return [{
    mode,
    path: mode === 'PATH_EQUALS' ? state.goldenAssertionPath : '',
    expectedValue
  }];
}

async function runSelectedGoldenCase() {
  const testCase = selectedGoldenCase();
  if (!testCase) {
    setPublicationMessage('Select a golden case first.', 'error');
    return;
  }
  setPublicationMessage(`Running golden ${shortRunId(testCase.caseId)}...`, 'info');
  try {
    const response = await fetch(`/api/visual/golden-cases/${encodeURIComponent(testCase.caseId)}/run`, {
      method: 'POST'
    });
    if (!response.ok) {
      throw new Error(`Golden case run failed with ${response.status}`);
    }
    const payload = await response.json();
    const passed = Boolean(payload.passed);
    setPublicationMessage(
      passed ? `Golden ${shortRunId(testCase.caseId)} passed.` : `Golden ${shortRunId(testCase.caseId)} failed.`,
      passed ? 'success' : 'error'
    );
    $('output').textContent = pretty({ status: response.status, goldenCaseRun: payload });
    await loadRunHistory();
  } catch (error) {
    setPublicationMessage(error.message, 'error');
  }
}

async function runPublicationGoldenSuite() {
  const publication = selectedPublication();
  if (!publication) {
    setPublicationMessage('Select a publication first.', 'error');
    return;
  }
  setPublicationMessage(`Running golden suite for ${shortRunId(publication.publicationId)}...`, 'info');
  try {
    const response = await fetch(
      `/api/visual/golden-cases/publications/${encodeURIComponent(publication.publicationId)}/run`,
      { method: 'POST' }
    );
    if (!response.ok) {
      throw new Error(`Golden suite run failed with ${response.status}`);
    }
    const payload = await response.json();
    const passed = Boolean(payload.passed);
    const passedCases = Number(payload.passedCases) || 0;
    const totalCases = Number(payload.totalCases) || 0;
    setPublicationMessage(
      passed
        ? `Golden suite passed (${passedCases}/${totalCases}).`
        : `Golden suite failed (${passedCases}/${totalCases}).`,
      passed ? 'success' : 'error'
    );
    $('output').textContent = pretty({ status: response.status, goldenSuiteRun: payload });
    await loadRunHistory();
  } catch (error) {
    setPublicationMessage(error.message, 'error');
  }
}

async function certifyPublicationGoldenSuite() {
  const publication = selectedPublication();
  if (!publication) {
    setPublicationMessage('Select a publication first.', 'error');
    return;
  }
  setPublicationMessage(`Certifying ${shortRunId(publication.publicationId)}...`, 'info');
  try {
    const response = await fetch(
      `/api/visual/golden-cases/publications/${encodeURIComponent(publication.publicationId)}/certify`,
      { method: 'POST' }
    );
    if (!response.ok) {
      throw new Error(`Golden certification failed with ${response.status}`);
    }
    const payload = await response.json();
    state.goldenCertification = payload;
    state.goldenCertificationStatus = null;
    const passedCases = Number(payload.passedCases) || 0;
    const totalCases = Number(payload.totalCases) || 0;
    setPublicationMessage(
      payload.certified
        ? `Certified (${passedCases}/${totalCases}).`
        : `Certification failed (${passedCases}/${totalCases}).`,
      payload.certified ? 'success' : 'error'
    );
    $('output').textContent = pretty({ status: response.status, goldenCertification: payload });
    await loadGoldenCertificationStatus();
    await loadRunHistory();
  } catch (error) {
    setPublicationMessage(error.message, 'error');
  }
}

async function runSelectedPublication() {
  const publication = selectedPublication();
  if (!publication) {
    setPublicationMessage('Select a publication first.', 'error');
    return;
  }
  let context;
  try {
    context = JSON.parse(state.customContextText || '{}');
  } catch (error) {
    setPublicationMessage('Context JSON is invalid.', 'error');
    $('output').textContent = pretty({ status: 'invalid_context', error: error.message });
    return;
  }
  if (!context || Array.isArray(context) || typeof context !== 'object') {
    setPublicationMessage('Context JSON must be an object.', 'error');
    $('output').textContent = pretty({ status: 'invalid_context', error: 'Context JSON must be an object.' });
    return;
  }

  setPublicationMessage(`Running ${publication.publicationId}...`, 'info');
  setVisualCheck(`Running published ${publication.publicationId}...`, 'info');
  try {
    const response = await fetch(`/api/visual/publications/${encodeURIComponent(publication.publicationId)}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ context, outputNode: '' })
    });
    const payload = await response.json();
    const diagnostics = normalizeDiagnostics(payload.diagnostics);
    const ok = Boolean(payload.validated && payload.compiled && payload.success);
    const level = visualCheckLevel(diagnostics, ok);
    setVisualCheck(
      ok ? 'Published run completed.' : 'Published run returned errors.',
      level,
      diagnostics
    );
    setPublicationMessage(
      ok ? `Ran ${publication.publicationId}.` : `Run returned errors for ${publication.publicationId}.`,
      level
    );
    if (Object.prototype.hasOwnProperty.call(payload, 'decisionTable')) {
      state.customDecisionTable = payload.decisionTable;
    }
    state.lastPayload = payload.output == null ? null : { data: payload.output, composer: payload };
    $('output').textContent = pretty({ status: response.status, publicationRun: payload });
    renderDecisionTable();
    highlightDecisionRow(state.lastPayload);
    renderDecisionSummary(state.lastPayload);
    renderDiagram();
    await loadRunHistory();
  } catch (error) {
    setPublicationMessage(error.message, 'error');
    setVisualCheck(error.message, 'error');
  }
}

async function loadDraftList(options = {}) {
  try {
    const response = await fetch('/api/visual/drafts');
    if (!response.ok) {
      throw new Error(`Draft list failed with ${response.status}`);
    }
    state.drafts = await response.json();
    if (state.currentDraftId && !state.drafts.some((draft) => draft.draftId === state.currentDraftId)) {
      state.currentDraftId = '';
      state.currentDraftRevision = 0;
      state.savedDraftSnapshot = null;
      state.draftRevisions = [];
      state.selectedDraftRevision = 0;
      state.previewingDraftRevision = 0;
    }
    if (options.render !== false) {
      renderDraftControls();
    }
  } catch (error) {
    setDraftMessage(error.message, 'error');
  }
}

async function loadDraftRevisions(options = {}) {
  if (!state.currentDraftId) {
    state.draftRevisions = [];
    state.selectedDraftRevision = 0;
    state.previewingDraftRevision = 0;
    if (options.render !== false) {
      renderDraftControls();
    }
    return [];
  }
  try {
    const response = await fetch(`/api/visual/drafts/${encodeURIComponent(state.currentDraftId)}/revisions`);
    if (!response.ok) {
      throw new Error(`Revision history failed with ${response.status}`);
    }
    state.draftRevisions = await response.json();
    if (!state.draftRevisions.some((draft) => Number(draft.revision || 0) === Number(state.selectedDraftRevision || 0))) {
      state.selectedDraftRevision = state.currentDraftRevision || state.draftRevisions[0]?.revision || 0;
    }
    if (options.render !== false) {
      renderDraftControls();
    }
    return state.draftRevisions;
  } catch (error) {
    setDraftMessage(error.message, 'error');
    return [];
  }
}

async function saveCurrentDraft() {
  const draft = builderToVisualDraft(state.builder);
  const draftId = state.currentDraftId;
  const expectedRevision = state.currentDraftRevision || 0;
  let baseDraft = draftId ? currentSavedDraftSnapshot(draftId) : null;
  let response;
  if (draftId) {
    if (!baseDraft) {
      const current = await fetchCurrentDraftSnapshot();
      if (!current) {
        return null;
      }
      if (expectedRevision && Number(current.revision || 0) !== Number(expectedRevision)) {
        state.currentDraftRevision = current.revision || 0;
        state.savedDraftSnapshot = current;
        await loadDraftList({ render: false });
        renderDraftControls();
        setDraftMessage(`Draft changed on server at @${state.currentDraftRevision}. Reload before saving.`, 'error');
        return null;
      }
      state.currentDraftRevision = current.revision || state.currentDraftRevision;
      state.savedDraftSnapshot = current;
      baseDraft = current;
    }
    const patch = draftPatchOperations(baseDraft, draft);
    if (!patch.length) {
      const current = baseDraft || draft;
      state.savedDraftSnapshot = current;
      setDraftMessage(`No changes in ${draftId}@${state.currentDraftRevision || 0}.`, 'success');
      return current;
    }
    response = await fetch(`/api/visual/drafts/${encodeURIComponent(draftId)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        expectedRevision: state.currentDraftRevision || 0,
        actor: 'visual-canvas',
        changeSource: 'gateway-browser',
        changeSummary: draftPatchSummary(patch),
        patch
      })
    });
  } else {
    response = await fetch('/api/visual/drafts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(draft)
    });
  }
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const diagnostics = normalizeDiagnostics(payload?.diagnostics);
    const current = payload?.draft;
    if (response.status === 409 && current?.revision !== undefined) {
      state.currentDraftRevision = current.revision || 0;
      state.savedDraftSnapshot = current;
      await loadDraftList();
    }
    setDraftMessage(diagnosticMessage(diagnostics, `Save failed with ${response.status}`), 'error');
    return null;
  }
  const stored = payload?.draft || payload;
  state.currentDraftId = stored.draftId || '';
  state.currentDraftRevision = stored.revision || 0;
  state.savedDraftSnapshot = stored;
  state.previewingDraftRevision = 0;
  setDraftMessage(`Saved ${state.currentDraftId}@${state.currentDraftRevision}.`, 'success');
  await loadDraftList({ render: false });
  await loadDraftRevisions({ render: false });
  renderDraftControls();
  return stored;
}

function currentSavedDraftSnapshot(draftId = state.currentDraftId) {
  if (state.savedDraftSnapshot?.draftId === draftId
      && Number(state.savedDraftSnapshot.revision || 0) === Number(state.currentDraftRevision || 0)) {
    return state.savedDraftSnapshot;
  }
  const listed = state.drafts.find((draft) =>
    draft.draftId === draftId && Number(draft.revision || 0) === Number(state.currentDraftRevision || 0)
  );
  return listed || null;
}

function draftPatchOperations(baseDraft, nextDraft) {
  if (!baseDraft) {
    throw new Error('Current draft snapshot is required before patching.');
  }
  return jsonPatchDiff(normalizeDraftForPatch(baseDraft), normalizeDraftForPatch(nextDraft), '');
}

function draftPatchSummary(patch) {
  const paths = patch.map((operation) => operation.path || '/').filter(Boolean);
  const preview = paths.slice(0, 3).join(', ');
  const suffix = paths.length > 3 ? `, +${paths.length - 3} more` : '';
  return `${patch.length} draft field change${patch.length === 1 ? '' : 's'}${preview ? `: ${preview}${suffix}` : ''}`;
}

function normalizeDraftForPatch(draft) {
  if (!draft || typeof draft !== 'object') {
    return draft;
  }
  const {
    draftId,
    revision,
    revisionMetadata,
    operatorFingerprints,
    ...patchableDraft
  } = draft;
  return patchableDraft;
}

function jsonPatchDiff(before, after, path) {
  if (deepEqual(before, after)) {
    return [];
  }
  if (!isPlainObject(before) || !isPlainObject(after)) {
    return [{ op: 'replace', path, value: after }];
  }
  const operations = [];
  const beforeKeys = Object.keys(before);
  const afterKeys = Object.keys(after);
  for (const key of beforeKeys.filter((item) => !Object.prototype.hasOwnProperty.call(after, item)).sort()) {
    operations.push({ op: 'remove', path: `${path}/${jsonPointerEscape(key)}` });
  }
  for (const key of afterKeys.sort()) {
    const childPath = `${path}/${jsonPointerEscape(key)}`;
    if (!Object.prototype.hasOwnProperty.call(before, key)) {
      operations.push({ op: 'add', path: childPath, value: after[key] });
    } else {
      operations.push(...jsonPatchDiff(before[key], after[key], childPath));
    }
  }
  return operations;
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function deepEqual(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function jsonPointerEscape(segment) {
  return String(segment).replaceAll('~', '~0').replaceAll('/', '~1');
}

async function loadSelectedDraft() {
  if (!state.currentDraftId) return;
  const response = await fetch(`/api/visual/drafts/${encodeURIComponent(state.currentDraftId)}`);
  if (!response.ok) {
    setDraftMessage(`Load failed with ${response.status}`, 'error');
    return;
  }
  const draft = await response.json();
  state.builder = builderFromVisualDraft(draft);
  await loadVisualOperatorCatalog();
  state.currentDraftId = draft.draftId || '';
  state.currentDraftRevision = draft.revision || 0;
  state.savedDraftSnapshot = draft;
  state.previewingDraftRevision = 0;
  state.lastPayload = null;
  state.lastGeneratedVisualDsl = '';
  await loadDraftRevisions({ render: false });
  syncGraphInputSchemaTextFromBuilder({ render: false });
  syncComposerFromBuilder({ render: false });
  setDraftMessage(`Loaded ${state.currentDraftId}@${state.currentDraftRevision}.`, 'success');
  renderScenario();
}

async function exportSelectedDraft() {
  if (!state.currentDraftId) return;
  const response = await fetch(`/api/visual/drafts/${encodeURIComponent(state.currentDraftId)}/export`);
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    setDraftMessage(`Export failed with ${response.status}`, 'error');
    return;
  }
  state.draftBundleText = pretty(payload);
  setDraftMessage(`Exported ${payload.sourceDraftId}@${payload.sourceRevision || 0}.`, 'success');
  $('output').textContent = pretty({ status: response.status, draftExport: payload });
  renderDraftControls();
}

async function importDraftBundle() {
  let bundle;
  try {
    bundle = JSON.parse(state.draftBundleText || '{}');
  } catch (error) {
    setDraftMessage(`Invalid draft bundle JSON: ${error.message}`, 'error');
    return;
  }
  const response = await fetch('/api/visual/drafts/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(bundle)
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const diagnostics = normalizeDiagnostics(payload?.diagnostics);
    setDraftMessage(diagnosticMessage(diagnostics, `Import failed with ${response.status}`), 'error');
    return;
  }
  const importedDraft = payload?.draft || payload;
  const diagnostics = normalizeDiagnostics(payload?.diagnostics);
  state.builder = builderFromVisualDraft(importedDraft);
  await loadVisualOperatorCatalog();
  state.currentDraftId = importedDraft.draftId || '';
  state.currentDraftRevision = importedDraft.revision || 0;
  state.savedDraftSnapshot = importedDraft;
  state.previewingDraftRevision = 0;
  state.lastPayload = null;
  state.lastGeneratedVisualDsl = '';
  await loadDraftList({ render: false });
  await loadDraftRevisions({ render: false });
  syncGraphInputSchemaTextFromBuilder({ render: false });
  syncComposerFromBuilder({ render: false });
  const hasImportErrors = diagnostics.some((diagnostic) => String(diagnostic.level || '').toUpperCase() === 'ERROR');
  const importMessage = diagnostics.length
    ? `Imported ${state.currentDraftId}@${state.currentDraftRevision} with ${hasImportErrors ? 'errors' : 'warnings'}: ${diagnosticMessage(diagnostics, 'review diagnostics')}`
    : `Imported ${state.currentDraftId}@${state.currentDraftRevision}.`;
  setDraftMessage(
    importMessage,
    hasImportErrors ? 'error' : 'success'
  );
  renderScenario();
  $('output').textContent = pretty({ status: response.status, draftImport: payload, importedDraft });
}

async function previewSelectedDraftRevision() {
  const draft = await selectedDraftRevisionSnapshot();
  if (!draft) return;
  const current = await loadCurrentDraftSnapshot();
  if (!current) return;
  state.builder = builderFromVisualDraft(draft);
  await loadVisualOperatorCatalog();
  state.currentDraftId = current.draftId || state.currentDraftId;
  state.currentDraftRevision = current.revision || state.currentDraftRevision;
  state.savedDraftSnapshot = current;
  state.previewingDraftRevision = draft.revision || 0;
  state.lastPayload = null;
  state.lastGeneratedVisualDsl = '';
  syncGraphInputSchemaTextFromBuilder({ render: false });
  syncComposerFromBuilder({ render: false });
  setDraftMessage(`Previewing revision @${state.previewingDraftRevision}. Save or Restore to create a new revision.`, 'info');
  renderScenario();
}

async function restoreSelectedDraftRevision() {
  const draft = await selectedDraftRevisionSnapshot();
  if (!draft) return;
  const current = await loadCurrentDraftSnapshot();
  if (!current) return;
  state.builder = builderFromVisualDraft(draft);
  await loadVisualOperatorCatalog();
  state.currentDraftId = current.draftId || state.currentDraftId;
  state.currentDraftRevision = current.revision || state.currentDraftRevision;
  state.savedDraftSnapshot = current;
  state.previewingDraftRevision = draft.revision || 0;
  syncGraphInputSchemaTextFromBuilder({ render: false });
  syncComposerFromBuilder({ render: false });
  const restored = await saveCurrentDraft();
  if (restored) {
    setDraftMessage(`Restored @${draft.revision || 0} as @${restored.revision || 0}.`, 'success');
    renderScenario();
  }
}

async function selectedDraftRevisionSnapshot() {
  if (!state.currentDraftId || !state.selectedDraftRevision) {
    return null;
  }
  const cached = state.draftRevisions.find((draft) =>
    Number(draft.revision || 0) === Number(state.selectedDraftRevision)
  );
  if (cached) {
    return cached;
  }
  const response = await fetch(`/api/visual/drafts/${encodeURIComponent(state.currentDraftId)}/revisions/${encodeURIComponent(state.selectedDraftRevision)}`);
  if (!response.ok) {
    setDraftMessage(`Revision load failed with ${response.status}`, 'error');
    return null;
  }
  return response.json();
}

async function loadCurrentDraftSnapshot() {
  const current = await fetchCurrentDraftSnapshot();
  if (!current) {
    return null;
  }
  state.currentDraftRevision = current.revision || state.currentDraftRevision;
  state.savedDraftSnapshot = current;
  return current;
}

async function fetchCurrentDraftSnapshot() {
  if (!state.currentDraftId) {
    return null;
  }
  const response = await fetch(`/api/visual/drafts/${encodeURIComponent(state.currentDraftId)}`);
  if (!response.ok) {
    setDraftMessage(`Current draft load failed with ${response.status}`, 'error');
    return null;
  }
  return response.json();
}

async function deleteSelectedDraft() {
  if (!state.currentDraftId || !confirm(`Delete draft ${state.currentDraftId}?`)) return;
  const deletedId = state.currentDraftId;
  const expectedRevision = state.currentDraftRevision || 0;
  const response = await fetch(
    `/api/visual/drafts/${encodeURIComponent(deletedId)}?expectedRevision=${encodeURIComponent(expectedRevision)}`,
    { method: 'DELETE' }
  );
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    const diagnostics = normalizeDiagnostics(payload?.diagnostics);
    if (response.status === 409 && payload?.draft) {
      state.currentDraftRevision = payload.draft.revision || state.currentDraftRevision;
      state.savedDraftSnapshot = payload.draft;
      await loadDraftList();
      renderDraftControls();
    }
    setDraftMessage(diagnosticMessage(diagnostics, `Delete failed with ${response.status}`), 'error');
    return;
  }
  state.currentDraftId = '';
  state.currentDraftRevision = 0;
  state.savedDraftSnapshot = null;
  state.draftRevisions = [];
  state.selectedDraftRevision = 0;
  state.previewingDraftRevision = 0;
  renderDraftControls();
  setDraftMessage(`Deleted ${deletedId}.`, 'success');
  await loadDraftList();
}

function renderSelectedOperatorEditor() {
  const target = $('selected-operator-editor');
  if (!target) return;
  const node = selectedBuilderNode();
  if (!node) {
    target.innerHTML = '<div class="panel-title">Selected Operator</div>';
    return;
  }

  target.innerHTML = `
    <div class="operator-editor-heading">
      <div>
        <div class="panel-title">Selected Operator</div>
        <strong>${escapeHtml(specForNode(node).label || node.type)}</strong>
      </div>
      <button id="delete-operator" class="secondary compact danger" type="button">Delete</button>
    </div>
    ${operatorEditorBody(node)}
  `;

  const deleteButton = $('delete-operator');
  if (deleteButton) {
    deleteButton.disabled = state.builder.nodes.length <= 1;
    deleteButton.addEventListener('click', deleteSelectedBuilderNode);
  }

  for (const input of target.querySelectorAll('[data-node-field]')) {
    input.addEventListener('input', () => {
      const field = input.dataset.nodeField;
      node[field] = input.value;
      if (field === 'policyNode') {
        if (input.value.trim()) {
          delete node.policyNodeCleared;
        } else {
          node.policyNodeCleared = true;
        }
      }
      syncComposerFromBuilder();
    });
  }

  for (const input of target.querySelectorAll('[data-custom-input]')) {
    input.addEventListener('input', () => {
      node.customInputs = node.customInputs || {};
      node.customInputs[input.dataset.customInput] = input.value;
      syncComposerFromBuilder();
    });
  }

  for (const button of target.querySelectorAll('[data-add-dynamic-input]')) {
    button.addEventListener('click', () => {
      addDynamicInputBinding(node, button);
    });
  }

  for (const button of target.querySelectorAll('[data-add-dynamic-output]')) {
    button.addEventListener('click', () => {
      addDynamicOutputPath(node, button);
    });
  }

  for (const input of target.querySelectorAll('[data-config-field]:not([data-config-source]):not([data-config-expression])')) {
    const eventName = input.type === 'checkbox' || input.tagName === 'SELECT' ? 'change' : 'input';
    input.addEventListener(eventName, () => {
      setConfigValueFromInput(node, input);
      updateConfigFieldStatus(node, input);
      syncComposerFromBuilder({ render: false });
      renderDiagram();
    });
  }

  for (const select of target.querySelectorAll('[data-config-source]')) {
    select.addEventListener('change', async () => {
      const spec = specForNode(node);
      const field = configFieldDescriptors(spec.configSchema)
        .find((item) => item.path === select.dataset.configField);
      if (!field) {
        return;
      }
      if (select.value && select.value !== CONFIG_MANUAL_EXPRESSION) {
        const source = sourceFromBindingValue(select.value);
        const configTarget = configTargetForField(node, field);
        if (!source) {
          renderSelectedOperatorEditor();
          return;
        }
        const compatibility = connectionCompatibility(source, configTarget);
        if (!compatibility.ok) {
          setConnectionMessage(compatibility.message, 'error');
          renderSelectedOperatorEditor();
          return;
        }
        select.disabled = true;
        setConnectionMessage('Checking config source with server...', 'info');
        try {
          const serverCheck = await checkVisualConnectionOnServer(source, configTarget);
          if (!serverCheck.accepted) {
            setConnectionMessage(serverCheck.message, 'error');
            renderSelectedOperatorEditor();
            renderDiagram();
            return;
          }
          setConnectionMessage(`Config ${node.id}.${field.path} bound to ${endpointLabel(source)}.`, 'success');
        } catch (error) {
          setConnectionMessage(error.message, 'error');
          renderSelectedOperatorEditor();
          renderDiagram();
          return;
        } finally {
          select.disabled = false;
        }
      }
      setConfigSourceFromSelect(node, select);
      syncComposerFromBuilder({ render: false });
      renderSelectedOperatorEditor();
      renderDiagram();
    });
  }

  for (const input of target.querySelectorAll('[data-config-expression]')) {
    input.addEventListener('input', () => {
      setConfigExpressionFromInput(node, input);
      updateConfigFieldStatus(node, input);
      syncComposerFromBuilder({ render: false });
      renderDiagram();
    });
  }

  for (const select of target.querySelectorAll('[data-binding-source]')) {
    select.addEventListener('change', async () => {
      const source = sourceFromBindingValue(select.value);
      const bindingTarget = bindingTargetFromElement(select);
      if (!source || !bindingTarget) return;
      const compatibility = connectionCompatibility(source, bindingTarget);
      if (!compatibility.ok) {
        setConnectionMessage(compatibility.message, 'error');
        renderSelectedOperatorEditor();
        return;
      }
      if (connectionAlreadyApplied(source, bindingTarget)) {
        setConnectionMessage('Connection already exists.', 'info');
        renderSelectedOperatorEditor();
        renderDiagram();
        return;
      }
      select.disabled = true;
      setConnectionMessage('Checking connection with server...', 'info');
      try {
        const serverCheck = await checkVisualConnectionOnServer(source, bindingTarget);
        if (serverCheck.accepted) {
          const checkedTarget = targetWithServerBindingKey(bindingTarget, serverCheck);
          applyConnection(source, checkedTarget);
          setConnectionMessage(
            `Connected ${endpointLabel(source)} -> ${endpointLabel(checkedTarget)}.`,
            'success'
          );
          renderDiagram();
        } else {
          setConnectionMessage(serverCheck.message, 'error');
          renderSelectedOperatorEditor();
          renderDiagram();
        }
      } catch (error) {
        setConnectionMessage(error.message, 'error');
        renderSelectedOperatorEditor();
        renderDiagram();
      } finally {
        select.disabled = false;
      }
    });
  }

  for (const input of target.querySelectorAll('[data-binding-expression]')) {
    input.addEventListener('input', () => {
      const bindingTarget = bindingTargetFromElement(input);
      if (!bindingTarget) return;
      setExpressionForTargetInput(node, bindingTarget, input.value);
      syncComposerFromBuilder({ render: false });
      renderDiagram();
    });
  }

  for (const button of target.querySelectorAll('[data-clear-binding]')) {
    button.addEventListener('click', () => {
      const bindingTarget = bindingTargetFromElement(button);
      if (!bindingTarget) return;
      setExpressionForTargetInput(node, bindingTarget, '');
      syncComposerFromBuilder({ render: false });
      renderSelectedOperatorEditor();
      renderDiagram();
    });
  }

  for (const input of target.querySelectorAll('[data-rule-field]')) {
    input.addEventListener('input', () => {
      const rule = node.rules[Number(input.dataset.ruleIndex)];
      if (!rule) return;
      const field = input.dataset.ruleField;
      rule[field] = input.type === 'number' ? Number(input.value) : input.value;
      syncComposerFromBuilder();
    });
  }

  const addRuleButton = $('add-decision-rule');
  if (addRuleButton) {
    addRuleButton.addEventListener('click', () => {
      addDecisionRule(node);
    });
  }
}

function renderGraphOutputEditor() {
  const target = $('graph-output-editor');
  if (!target) return;
  const output = ensureBuilderOutput(state.builder);
  const selectedNode = state.builder.nodes.find((node) => node.id === output.nodeId);
  const pathOptions = selectedNode ? outputPathOptionsForNode(selectedNode) : [];
  const outputSummary = output.path ? `${output.nodeId}.${output.path}` : output.nodeId || 'No output';
  const orderedOutputNodes = orderedBuilderNodes();
  const selectableOutputNodes = orderedOutputNodes.filter((node) => outputPortsForSpec(specForNode(node)).length > 0);
  const nodeOptions = (selectableOutputNodes.length ? selectableOutputNodes : orderedOutputNodes).map((node) => {
    const selected = node.id === output.nodeId ? ' selected' : '';
    return `<option value="${escapeHtml(node.id)}"${selected}>${escapeHtml(labelForNode(node))} (${escapeHtml(node.id)})</option>`;
  }).join('');
  const pathOptionMarkup = pathOptions.map((option) => {
    const selected = option.value === output.path ? ' selected' : '';
    const type = option.type ? ` · ${option.type}` : '';
    return `<option value="${escapeHtml(option.value)}"${selected}>${escapeHtml(option.label + type)}</option>`;
  }).join('');

  target.innerHTML = `
    <div class="operator-editor-heading">
      <div>
        <div class="panel-title">Graph Output</div>
        <strong>${escapeHtml(output.nodeId || 'No output')}</strong>
      </div>
    </div>
    <div class="operator-fields">
      <label>
        <span>Output Node</span>
        <select id="graph-output-node" aria-label="Graph output node">${nodeOptions}</select>
      </label>
      <label>
        <span>Output Path</span>
        <select id="graph-output-path" aria-label="Graph output path">${pathOptionMarkup}</select>
      </label>
    </div>
    <div class="binding-status">Current: ${escapeHtml(outputSummary)}</div>
  `;

  const nodeSelect = $('graph-output-node');
  if (nodeSelect) {
    nodeSelect.addEventListener('change', () => {
      state.builder.output = { nodeId: nodeSelect.value, path: '' };
      syncComposerFromBuilder({ render: false });
      renderGraphOutputEditor();
    });
  }
  const pathSelect = $('graph-output-path');
  if (pathSelect) {
    pathSelect.addEventListener('change', () => {
      state.builder.output = {
        nodeId: output.nodeId,
        path: pathSelect.value
      };
      syncComposerFromBuilder({ render: false });
      renderGraphOutputEditor();
    });
  }
}

function operatorEditorBody(node) {
  if (node.type === 'httpResource') {
    return `
      <div class="operator-fields">
        ${textField('Node', node.id, '', true)}
        ${textField('Resource ID', node.resourceId, 'resourceId')}
      </div>
      ${renderOperatorContractPanel(node)}
      ${renderConfigPanel(node)}
      ${renderInputBindingsPanel(node)}
    `;
  }
  if (node.type === 'customOperator') {
    const spec = specForNode(node);
    if (spec.unavailable) {
      return renderUnavailableOperatorPanel(node, spec);
    }
    return `
      <div class="operator-fields">
        ${textField('Node', node.id, '', true)}
        ${textField('Operator', spec.visualOperatorRef || node.paletteType, '', true)}
      </div>
      ${renderOperatorContractPanel(node)}
      ${renderDynamicOutputPathControls(node)}
      ${renderConfigPanel(node)}
      ${renderInputBindingsPanel(node)}
    `;
  }
  if (node.type === 'decisionTable') {
    const rows = node.rules.map((rule, index) => `
      <tr>
        <td>${escapeHtml(rule.id)}</td>
        <td><input data-rule-index="${index}" data-rule-field="score" value="${escapeHtml(rule.score)}" ${rule.otherwise ? 'disabled' : ''}></td>
        <td><input data-rule-index="${index}" data-rule-field="amount" value="${escapeHtml(rule.amount)}" ${rule.otherwise ? 'disabled' : ''}></td>
        <td><input data-rule-index="${index}" data-rule-field="decision" value="${escapeHtml(rule.decision)}"></td>
        <td><input type="number" step="0.01" data-rule-index="${index}" data-rule-field="rate" value="${escapeHtml(rule.rate)}"></td>
        <td><input type="number" step="1" data-rule-index="${index}" data-rule-field="maxTerm" value="${escapeHtml(rule.maxTerm)}"></td>
        <td><input data-rule-index="${index}" data-rule-field="reviewLane" value="${escapeHtml(rule.reviewLane)}"></td>
      </tr>
    `).join('');
    return `
      <div class="operator-fields">
        ${textField('Node', node.id, '', true)}
      </div>
      ${renderOperatorContractPanel(node)}
      ${renderInputBindingsPanel(node)}
      <div class="rule-editor">
        <div class="rule-editor-title">
          <span>Rules</span>
          <button id="add-decision-rule" class="secondary compact" type="button">Add Rule</button>
        </div>
        <div class="rule-editor-scroll">
          <table class="rule-editor-table">
            <thead>
              <tr>
                <th>Rule</th>
                <th>Score</th>
                <th>Amount</th>
                <th>Decision</th>
                <th>Rate</th>
                <th>Term</th>
                <th>Lane</th>
              </tr>
            </thead>
            <tbody>${rows}</tbody>
          </table>
        </div>
      </div>
    `;
  }
  return `
    <div class="operator-fields">
      ${textField('Node', node.id, '', true)}
      ${textField('Policy Node', node.policyNodeCleared ? '' : (node.policyNode || firstDecisionTableId()), 'policyNode')}
    </div>
    ${renderOperatorContractPanel(node)}
  `;
}

function renderOperatorContractPanel(node) {
  const spec = specForNode(node);
  if (spec.unavailable) {
    return '';
  }
  const targets = targetHandlesForNode(node);
  const configFields = configFieldDescriptors(spec.configSchema);
  const requiredTargets = targets.filter((target) => target.required);
  const statuses = targets.map((target) => {
    const expression = expressionForTargetInput(node, target);
    return {
      target,
      expression,
      status: bindingStatusForTarget(node, target, expression)
    };
  });
  const bound = statuses.filter((item) => String(item.expression || '').trim()).length;
  const requiredValid = statuses.filter((item) => item.target.required && item.status.level === 'success').length;
  const errors = statuses.filter((item) => item.status.level === 'error').length;
  return `
    <div class="contract-panel">
      <div class="binding-panel-title">
        <span>Contract</span>
        <small>${bound}/${targets.length} inputs bound · ${requiredValid}/${requiredTargets.length} required valid · ${errors} issues</small>
      </div>
      <div class="contract-port-groups">
        ${renderContractPortGroup('Inputs', inputPortsForSpec(spec))}
        ${renderContractPortGroup('Outputs', outputPortsForSpec(spec))}
      </div>
      ${configFields.length ? `
        <div class="contract-config-summary">
          <strong>Config</strong>
          <span>${configFields.length} fields · ${configFields.filter((field) => field.required).length} required</span>
        </div>
      ` : ''}
    </div>
  `;
}

function renderContractPortGroup(label, ports) {
  const rows = ports.map((port) => {
    const fields = schemaFieldDescriptors(port.schema);
    const required = fields.filter((field) => field.required).length;
    const rootType = schemaType(port.schema?.schema) || 'any';
    return `
      <div class="contract-port-row">
        <strong>${escapeHtml(port.name)}</strong>
        <span>${escapeHtml(rootType)} · ${fields.length} fields · ${required} required</span>
      </div>
    `;
  }).join('');
  return `
    <div class="contract-port-group">
      <div class="contract-port-title">${escapeHtml(label)}</div>
      ${rows || '<div class="contract-port-row empty"><span>None</span></div>'}
    </div>
  `;
}

function renderUnavailableOperatorPanel(node, spec) {
  const scope = builderScope();
  const inputs = Object.entries(node.customInputs || {});
  const inputRows = inputs.length
    ? inputs.map(([key, expression]) => `
        <div class="unavailable-input-row">
          <strong>${escapeHtml(key)}</strong>
          <span>${escapeHtml(expression)}</span>
        </div>
      `).join('')
    : '<div class="unavailable-input-row"><span>No saved input bindings.</span></div>';
  return `
    <div class="operator-fields">
      ${textField('Node', node.id, '', true)}
      ${textField('Operator', spec.visualOperatorRef || node.paletteType, '', true)}
    </div>
    <div class="operator-unavailable">
      <strong>Operator unavailable in current authoring scope.</strong>
      <span>${escapeHtml(scope.tenantId)} / ${escapeHtml(scope.namespace)} / ${escapeHtml(scope.environment)}</span>
      <span>Reload the library or switch scope, then validate to get the server diagnostic.</span>
    </div>
    <div class="unavailable-inputs">
      ${inputRows}
    </div>
  `;
}

function textField(label, value, field, disabled = false) {
  const attr = field ? `data-node-field="${escapeHtml(field)}"` : '';
  return `
    <label>
      <span>${escapeHtml(label)}</span>
      <input ${attr} value="${escapeHtml(value ?? '')}" ${disabled ? 'disabled' : ''}>
    </label>
  `;
}

function renderConfigPanel(node) {
  const spec = specForNode(node);
  const fields = configFieldDescriptors(spec.configSchema);
  const unknownRows = unknownConfigRows(node, spec);
  if (!fields.length && !unknownRows.length) {
    return '';
  }
  return `
    <div class="binding-panel">
      <div class="binding-panel-title">
        <span>Config</span>
        <small>configSchema checked</small>
      </div>
      ${fields.map((field) => renderConfigRow(node, field)).join('')}
      ${unknownRows.join('')}
    </div>
  `;
}

function renderConfigRow(node, field) {
  const status = configStatusForField(node, field);
  const required = field.required ? 'Required' : 'Optional';
  return `
    <div class="binding-row ${escapeHtml(status.level)}" data-config-row="${escapeHtml(field.path)}">
      <div class="binding-row-head">
        <div>
          <strong>${escapeHtml(readableName(field.path))}</strong>
          <span>${escapeHtml(schemaType(field.schema) || 'any')} · ${escapeHtml(required)}</span>
        </div>
      </div>
      ${renderConfigControl(node, field)}
      <div class="binding-status" data-config-status>${escapeHtml(status.message)}</div>
    </div>
  `;
}

function renderConfigControl(node, field) {
  const value = configValueAtPath(node.config, field.path);
  const expressionMode = isConfigExpressionValue(value);
  const expression = expressionMode ? configExpressionForField(value) : '';
  const sourceSelect = renderConfigSourceSelect(node, field, expression, expressionMode);
  if (expressionMode) {
    return `
      ${sourceSelect}
      <input
        data-config-expression
        data-config-field="${escapeHtml(field.path)}"
        value="${escapeHtml(expression)}"
        placeholder="ctx.${escapeHtml(field.path || 'value')}">
    `;
  }
  return `
    ${sourceSelect}
    ${renderLiteralConfigControl(node, field, value)}
  `;
}

function renderConfigSourceSelect(node, field, expression, expressionMode) {
  const target = configTargetForField(node, field);
  const selectedSource = expressionMode ? connectionSourceFromExpression(expression) : null;
  const selectedValue = selectedSource ? bindingSourceValue(selectedSource) : '';
  const candidates = sourceCandidatesForTarget(target);
  const hasSelectedCandidate = selectedValue
    && candidates.some((candidate) => bindingSourceValue(candidate.source) === selectedValue);
  const staleOption = selectedValue && !hasSelectedCandidate
    ? `<option value="${escapeHtml(selectedValue)}" selected disabled>${escapeHtml(endpointLabel(selectedSource))} (unavailable)</option>`
    : '';
  const options = [
    `<option value="" ${expressionMode ? '' : 'selected'}>Literal value</option>`,
    `<option value="${CONFIG_MANUAL_EXPRESSION}" ${expressionMode && !selectedValue ? 'selected' : ''}>Manual expression</option>`,
    staleOption,
    ...candidates.map((candidate) => {
      const value = bindingSourceValue(candidate.source);
      const selected = value === selectedValue ? ' selected' : '';
      const disabled = candidate.compatibility.ok ? '' : ' disabled';
      const type = candidate.source.type ? ` · ${candidate.source.type}` : '';
      const suffix = candidate.compatibility.ok ? '' : ` · ${candidate.compatibility.message}`;
      return `<option value="${escapeHtml(value)}"${selected}${disabled}>${escapeHtml(endpointLabel(candidate.source) + type + suffix)}</option>`;
    })
  ].join('');
  return `
    <select
      data-config-source
      data-config-field="${escapeHtml(field.path)}"
      aria-label="${escapeHtml(readableName(field.path))} config source">
      ${options}
    </select>
  `;
}

function renderLiteralConfigControl(node, field, value) {
  const type = rawSchemaType(field.schema);
  const attr = `data-config-field="${escapeHtml(field.path)}"`;
  const values = schemaEnumValues(field.schema);
  if (values.length) {
    const hasValue = value !== undefined && value !== null && value !== '';
    const blank = field.required
      ? `<option value="" ${hasValue ? '' : 'selected'} disabled>Select...</option>`
      : '<option value="">Unset</option>';
    return `
      <select ${attr} aria-label="${escapeHtml(readableName(field.path))} config">
        ${blank}
        ${values.map((item) => {
          const stringValue = String(item);
          const selected = value === item || String(value ?? '') === stringValue ? ' selected' : '';
          return `<option value="${escapeHtml(stringValue)}"${selected}>${escapeHtml(stringValue)}</option>`;
        }).join('')}
      </select>
    `;
  }
  if (type === 'boolean') {
    return `
      <label class="config-checkbox">
        <input ${attr} type="checkbox" ${value === true ? 'checked' : ''}>
        <span>${escapeHtml(value === true ? 'Enabled' : 'Disabled')}</span>
      </label>
    `;
  }
  if (type === 'integer' || type === 'number' || type === 'decimal') {
    const step = type === 'integer' ? '1' : 'any';
    return `<input ${attr} type="number" step="${step}" value="${escapeHtml(value ?? '')}">`;
  }
  const rendered = typeof value === 'object' && value !== null ? JSON.stringify(value) : (value ?? '');
  return `<input ${attr} value="${escapeHtml(rendered)}">`;
}

function isConfigExpressionValue(value) {
  return value && typeof value === 'object' && !Array.isArray(value) && value.kind === 'expression';
}

function isConfigBindingObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value) && typeof value.kind === 'string';
}

function configExpressionForField(value) {
  return isConfigExpressionValue(value) ? String(value.expr || '') : '';
}

function configTargetForField(node, field) {
  return {
    nodeId: node.id,
    port: 'config',
    path: field.path,
    key: field.path,
    required: field.required,
    type: schemaType(field.schema),
    schema: field.schema
  };
}

function configTargetsForNode(node) {
  const spec = specForNode(node);
  return configFieldDescriptors(spec.configSchema).map((field) => configTargetForField(node, field));
}

function unknownConfigRows(node, spec) {
  const schema = spec.configSchema?.schema || {};
  return unknownConfigPaths(node.config || {}, schema, '')
    .map((path) => `
      <div class="binding-row error">
        <div class="binding-row-head">
          <div>
            <strong>${escapeHtml(readableName(path))}</strong>
            <span>unknown · config</span>
          </div>
        </div>
        <div class="binding-status">Not declared by configSchema.</div>
      </div>
    `);
}

function unknownConfigPaths(value, schema, prefix) {
  if (!isConfigContainerObject(value)) {
    return [];
  }
  const type = rawSchemaType(schema);
  if (type && type !== 'object' && !schema?.properties) {
    return [];
  }
  const properties = schema?.properties || {};
  const residual = residualPropertiesPolicy(schema);
  const residualSchema = residual && typeof residual === 'object' && !Array.isArray(residual)
    ? residual
    : null;
  const paths = [];
  for (const [key, item] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (Object.prototype.hasOwnProperty.call(properties, key)) {
      paths.push(...unknownConfigPaths(item, properties[key] || {}, path));
      continue;
    }
    const patternSchemas = matchingPatternPropertySchemas(schema, key);
    if (patternSchemas.length) {
      for (const patternSchema of patternSchemas) {
        paths.push(...unknownConfigPaths(item, patternSchema, path));
      }
    } else if (residual === false) {
      paths.push(path);
    } else if (residualSchema) {
      paths.push(...unknownConfigPaths(item, residualSchema, path));
    }
  }
  return paths;
}

function setConfigValueFromInput(node, input) {
  const spec = specForNode(node);
  const field = configFieldDescriptors(spec.configSchema)
    .find((item) => item.path === input.dataset.configField);
  if (!field) {
    return;
  }
  node.config = node.config || {};
  if (input.type !== 'checkbox' && input.value === '') {
    if (field.required) {
      setConfigValueAtPath(node.config, field.path, '');
    } else {
      deleteConfigValueAtPath(node.config, field.path);
    }
    return;
  }
  setConfigValueAtPath(node.config, field.path, parseConfigInputValue(input, field.schema));
}

function setConfigSourceFromSelect(node, select) {
  const spec = specForNode(node);
  const field = configFieldDescriptors(spec.configSchema)
    .find((item) => item.path === select.dataset.configField);
  if (!field) {
    return;
  }
  node.config = node.config || {};
  if (select.value === '') {
    if (field.required) {
      setConfigValueAtPath(node.config, field.path, '');
    } else {
      deleteConfigValueAtPath(node.config, field.path);
    }
    return;
  }
  if (select.value === CONFIG_MANUAL_EXPRESSION) {
    setConfigValueAtPath(node.config, field.path, {
      kind: 'expression',
      expr: configExpressionForField(configValueAtPath(node.config, field.path))
    });
    return;
  }
  const source = sourceFromBindingValue(select.value);
  if (!source) {
    return;
  }
  setConfigValueAtPath(node.config, field.path, {
    kind: 'expression',
    expr: expressionForConnectionSource(source)
  });
}

function setConfigExpressionFromInput(node, input) {
  const spec = specForNode(node);
  const field = configFieldDescriptors(spec.configSchema)
    .find((item) => item.path === input.dataset.configField);
  if (!field) {
    return;
  }
  node.config = node.config || {};
  setConfigValueAtPath(node.config, field.path, {
    kind: 'expression',
    expr: input.value
  });
}

function updateConfigFieldStatus(node, input) {
  const spec = specForNode(node);
  const field = configFieldDescriptors(spec.configSchema)
    .find((item) => item.path === input.dataset.configField);
  if (!field) {
    return;
  }
  const status = configStatusForField(node, field);
  const row = input.closest('[data-config-row]');
  if (!row) {
    return;
  }
  row.classList.remove('info', 'success', 'error');
  row.classList.add(status.level);
  const statusNode = row.querySelector('[data-config-status]');
  if (statusNode) {
    statusNode.textContent = status.message;
  }
}

function parseConfigInputValue(input, schema) {
  const type = rawSchemaType(schema);
  const values = schemaEnumValues(schema);
  if (input.type === 'checkbox' || type === 'boolean') {
    return Boolean(input.checked);
  }
  if (values.length) {
    return values.find((item) => String(item) === input.value) ?? input.value;
  }
  if (type === 'integer') {
    return Number.parseInt(input.value || '0', 10);
  }
  if (type === 'number' || type === 'decimal') {
    return Number(input.value || 0);
  }
  if (type === 'object' || type === 'array') {
    try {
      return JSON.parse(input.value || (type === 'array' ? '[]' : '{}'));
    } catch {
      return input.value;
    }
  }
  return input.value;
}

function configStatusForField(node, field) {
  const hasValue = hasConfigPath(node.config || {}, field.path);
  const value = configValueAtPath(node.config, field.path);
  if (!hasValue || value === null || value === '') {
    return field.required
      ? { level: 'error', message: 'Required config is missing.' }
      : { level: 'info', message: 'Optional config is empty.' };
  }
  if (isConfigExpressionValue(value)) {
    return configExpressionStatusForField(node, field, configExpressionForField(value));
  }
  if (!configValueMatchesSchema(value, field.schema)) {
    return { level: 'error', message: `Expected ${schemaType(field.schema) || 'schema-compatible'} value.` };
  }
  return { level: 'success', message: 'Config matches configSchema.' };
}

function renderDynamicOutputPathControls(node) {
  if (node.type !== 'customOperator') {
    return '';
  }
  const spec = specForNode(node);
  const ports = outputPortsForSpec(spec).filter((port) => schemaAllowsDynamicInputPath(port.schema));
  if (!ports.length) {
    return '';
  }
  const options = ports.map((port) =>
    `<option value="${escapeHtml(port.name)}">${escapeHtml(port.name)}</option>`
  ).join('');
  return `
    <div class="binding-panel">
      <div class="binding-panel-title">
        <span>Output Sources</span>
        <small>schema checked</small>
      </div>
      <div class="operator-fields" data-dynamic-output-authoring>
        <label>
          <span>Port</span>
          <select data-dynamic-output-port aria-label="Dynamic output port">${options}</select>
        </label>
        <label>
          <span>Output path</span>
          <input data-dynamic-output-path aria-label="Dynamic output path">
        </label>
        <button class="secondary compact" type="button" data-add-dynamic-output>Add</button>
      </div>
    </div>
  `;
}

function addDynamicOutputPath(node, button) {
  const container = button.closest('[data-dynamic-output-authoring]');
  if (!container) {
    return;
  }
  const portName = container.querySelector('[data-dynamic-output-port]')?.value || '';
  const path = String(container.querySelector('[data-dynamic-output-path]')?.value || '').trim();
  const spec = specForNode(node);
  const portSchema = schemaForPort(spec, 'source', portName);
  if (!path || !isSchemaPathDslSafe(portSchema, path)) {
    setConnectionMessage('Output path must be a DSL-safe field path.', 'error');
    return;
  }
  if (!schemaAtPath(portSchema, path)) {
    setConnectionMessage('Output path is not accepted by the source schema.', 'error');
    return;
  }
  const key = outputKeyForPortPath(spec, portName, path);
  node.customOutputPorts = node.customOutputPorts || {};
  node.customOutputPaths = node.customOutputPaths || {};
  node.customOutputPorts[key] = portName;
  node.customOutputPaths[key] = path;
  setConnectionMessage(`Added ${node.id}.${portName}.${path}.`, 'success');
  syncComposerFromBuilder({ render: false });
  renderSelectedOperatorEditor();
  renderGraphOutputEditor();
  renderDiagram();
}

function configExpressionStatusForField(node, field, expression) {
  const value = String(expression || '').trim();
  if (!value) {
    return field.required
      ? { level: 'error', message: 'Required config expression is empty.' }
      : { level: 'info', message: 'Optional config expression is empty.' };
  }
  const source = connectionSourceFromExpression(value);
  if (!source) {
    const literalSchema = staticExpressionLiteralSchema(value);
    if (literalSchema) {
      const compatibilityIssue = schemaCompatibilityIssue(literalSchema, field.schema);
      return compatibilityIssue
        ? {
          level: 'error',
          message: `Type mismatch: ${schemaType(literalSchema)} cannot feed ${schemaType(field.schema)}. Reason: ${compatibilityIssue}.`
        }
        : { level: 'success', message: 'Literal expression matches configSchema.' };
    }
    return { level: 'info', message: 'Manual expression; server validation checks referenced paths.' };
  }
  const target = configTargetForField(node, field);
  const compatibility = connectionCompatibility(source, target);
  if (!compatibility.ok) {
    return { level: 'error', message: compatibility.message };
  }
  return { level: 'success', message: `Config expression bound to ${endpointLabel(source)}.` };
}

function configValueMatchesSchema(value, schema) {
  return schemaValueMatchesSchema(value, schema);
}

function renderInputBindingsPanel(node) {
  const targets = targetHandlesForNode(node);
  const dynamicInputControls = renderDynamicInputBindingControls(node);
  if (!targets.length) {
    return dynamicInputControls
      ? `
        <div class="binding-panel">
          <div class="binding-panel-title">
            <span>Input Bindings</span>
            <small>schema checked</small>
          </div>
          ${dynamicInputControls}
        </div>
      `
      : '';
  }
  return `
    <div class="binding-panel">
      <div class="binding-panel-title">
        <span>Input Bindings</span>
        <small>schema checked</small>
      </div>
      ${targets.map((target) => renderInputBindingRow(node, target)).join('')}
      ${dynamicInputControls}
    </div>
  `;
}

function renderDynamicInputBindingControls(node) {
  if (node.type !== 'customOperator') {
    return '';
  }
  const spec = specForNode(node);
  const ports = inputPortsForSpec(spec).filter((port) => schemaAllowsDynamicInputPath(port.schema));
  if (!ports.length) {
    return '';
  }
  const options = ports.map((port) =>
    `<option value="${escapeHtml(port.name)}">${escapeHtml(port.name)}</option>`
  ).join('');
  return `
    <div class="operator-fields" data-dynamic-input-authoring>
      <label>
        <span>Port</span>
        <select data-dynamic-input-port aria-label="Dynamic input port">${options}</select>
      </label>
      <label>
        <span>Input path</span>
        <input data-dynamic-input-path aria-label="Dynamic input path">
      </label>
      <button class="secondary compact" type="button" data-add-dynamic-input>Add</button>
    </div>
  `;
}

function schemaAllowsDynamicInputPath(schemaEnvelope) {
  const schema = schemaEnvelope?.schema || {};
  const type = rawSchemaType(schema);
  const objectLike = type === 'object'
    || schema.properties
    || Object.prototype.hasOwnProperty.call(schema, 'additionalProperties')
    || Object.prototype.hasOwnProperty.call(schema, 'unevaluatedProperties')
    || schema.patternProperties;
  if (!objectLike) {
    return false;
  }
  const residual = residualPropertiesPolicy(schema);
  if (residual === true || (residual && typeof residual === 'object' && !Array.isArray(residual))) {
    return true;
  }
  const patterns = schemaPatternProperties(schema);
  return Boolean(patterns && Object.keys(patterns).length);
}

function addDynamicInputBinding(node, button) {
  const container = button.closest('[data-dynamic-input-authoring]');
  if (!container) {
    return;
  }
  const portName = container.querySelector('[data-dynamic-input-port]')?.value || '';
  const path = String(container.querySelector('[data-dynamic-input-path]')?.value || '').trim();
  const spec = specForNode(node);
  const portSchema = schemaForPort(spec, 'target', portName);
  if (!path || !isSchemaPathDslSafe(portSchema, path)) {
    setConnectionMessage('Input path must be a DSL-safe field path.', 'error');
    return;
  }
  if (!schemaAtPath(portSchema, path)) {
    setConnectionMessage('Input path is not accepted by the target schema.', 'error');
    return;
  }
  const key = inputKeyForPortPath(spec, portName, path);
  node.customInputs = node.customInputs || {};
  node.customInputPorts = node.customInputPorts || {};
  node.customInputPaths = node.customInputPaths || {};
  if (!Object.prototype.hasOwnProperty.call(node.customInputs, key)) {
    node.customInputs[key] = '';
  }
  node.customInputPorts[key] = portName;
  node.customInputPaths[key] = path;
  setConnectionMessage(`Added ${node.id}.${portName}.${path}.`, 'success');
  syncComposerFromBuilder({ render: false });
  renderSelectedOperatorEditor();
  renderDiagram();
}

function renderInputBindingRow(node, target) {
  const expression = expressionForTargetInput(node, target);
  const status = bindingStatusForTarget(node, target, expression);
  const selectedSource = connectionSourceFromExpression(expression);
  const selectedValue = selectedSource ? bindingSourceValue(selectedSource) : '';
  const candidates = sourceCandidatesForTarget(target);
  const hasSelectedCandidate = selectedValue
    && candidates.some((candidate) => bindingSourceValue(candidate.source) === selectedValue);
  const staleOption = selectedValue && !hasSelectedCandidate
    ? `<option value="${escapeHtml(selectedValue)}" selected disabled>${escapeHtml(endpointLabel(selectedSource))} (unavailable)</option>`
    : '';
  const options = [
    `<option value="" ${selectedValue ? '' : 'selected'}>Manual expression</option>`,
    staleOption,
    ...candidates.map((candidate) => {
      const value = bindingSourceValue(candidate.source);
      const selected = value === selectedValue ? ' selected' : '';
      const disabled = candidate.compatibility.ok ? '' : ' disabled';
      const type = candidate.source.type ? ` · ${candidate.source.type}` : '';
      const suffix = candidate.compatibility.ok ? '' : ` · ${candidate.compatibility.message}`;
      return `<option value="${escapeHtml(value)}"${selected}${disabled}>${escapeHtml(endpointLabel(candidate.source) + type + suffix)}</option>`;
    })
  ].join('');
  const type = target.type
    || schemaType(schemaAtPath(schemaForPort(specForNode(node), 'target', target.port), target.path))
    || 'any';
  const required = target.required ? 'Required' : 'Optional';
  const label = target.key && target.key !== target.path ? target.key : (target.path || target.port);
  return `
    <div class="binding-row ${escapeHtml(status.level)}">
      <div class="binding-row-head">
        <div>
          <strong>${escapeHtml(readableName(label))}</strong>
          <span>${escapeHtml(type)} · ${escapeHtml(required)}</span>
        </div>
        <button
          class="secondary compact"
          type="button"
          data-clear-binding
          data-binding-key="${escapeHtml(target.key || target.path || '')}"
          data-binding-port="${escapeHtml(target.port)}"
          data-binding-path="${escapeHtml(target.path || '')}">
          Clear
        </button>
      </div>
      <select
        data-binding-source
        data-binding-key="${escapeHtml(target.key || target.path || '')}"
        data-binding-port="${escapeHtml(target.port)}"
        data-binding-path="${escapeHtml(target.path || '')}"
        aria-label="${escapeHtml(readableName(label))} source">
        ${options}
      </select>
      <input
        data-binding-expression
        data-binding-key="${escapeHtml(target.key || target.path || '')}"
        data-binding-port="${escapeHtml(target.port)}"
        data-binding-path="${escapeHtml(target.path || '')}"
        value="${escapeHtml(expression)}"
        placeholder="ctx.${escapeHtml(target.path || 'value')}">
      <div class="binding-status">${escapeHtml(status.message)}</div>
    </div>
  `;
}

function bindingTargetFromElement(element) {
  const node = selectedBuilderNode();
  if (!node) {
    return null;
  }
  const port = element.dataset.bindingPort || '';
  const path = element.dataset.bindingPath || '';
  const key = element.dataset.bindingKey || '';
  return targetHandlesForNode(node).find((target) =>
    target.port === port && (target.path || '') === path && (!key || (target.key || target.path || '') === key)
  ) || null;
}

function addDecisionRule(node) {
  const nextId = uniqueRuleId(node.rules);
  const insertAt = Math.max(0, node.rules.length - 1);
  node.rules.splice(insertAt, 0, {
    id: nextId,
    score: 'score >= 720',
    amount: 'amount <= 250000',
    decision: 'approved',
    rate: 4.25,
    maxTerm: 300,
    reviewLane: 'standard',
    otherwise: false
  });
  syncComposerFromBuilder();
  renderSelectedOperatorEditor();
}

function uniqueRuleId(rules) {
  let index = rules.length + 1;
  const used = new Set(rules.map((rule) => rule.id));
  while (used.has(`R${index}`)) {
    index++;
  }
  return `R${index}`;
}

function addBuilderNode(type, position = null) {
  const spec = OPERATOR_TYPES[type];
  if (!spec) return;
  const isResource = spec.kind === 'resource';
  const ordered = orderedBuilderNodes();
  const last = ordered[ordered.length - 1];
  const firstDecision = state.builder.nodes.find((node) => node.type === 'decisionTable');
  const fallbackX = (type === 'httpResource' || isResource) && firstDecision ? firstDecision.x - 280 : (last ? last.x + 280 : 80);
  const fallbackY = (type === 'httpResource' || isResource) && firstDecision ? firstDecision.y : (last ? last.y : 210);
  const point = nonOverlappingNodePosition(position?.x ?? fallbackX, position?.y ?? fallbackY);
  const node = createBuilderNode(type, point.x, point.y);
  state.builder.nodes.push(node);
  state.builder.selectedId = node.id;
  state.selectedNodeId = node.id;
  if (node.type === 'httpResource') {
    applyResourceDefaults(node);
  }
  syncComposerFromBuilder();
  renderInputForm();
  renderDiagram();
  return node;
}

function createBuilderNode(type, x, y) {
  const spec = OPERATOR_TYPES[type];
  const resourceOperator = spec.kind === 'resource' && spec.resourceId;
  const customOperator = spec.kind === 'custom';
  const id = uniqueNodeId(spec.baseId);
  const base = {
    id,
    type: resourceOperator ? 'httpResource' : (customOperator ? 'customOperator' : type),
    paletteType: resourceOperator || customOperator ? type : '',
    x: Math.max(40, Math.round(x)),
    y: Math.max(80, Math.round(y))
  };
  if (type === 'httpResource' || resourceOperator) {
    const paramInputs = defaultResourceParamInputs(spec);
    const paramName = Object.keys(paramInputs)[0] || defaultParamNameForOperator(spec);
    return {
      ...base,
      resourceId: spec.resourceId || 'loan-applicant-service.getProfile',
      paramName,
      applicantExpr: paramInputs[paramName] || `ctx.${paramName}`,
      paramInputs,
      config: { timeout: '3s', retryAttempts: 1, ...defaultConfigForOperator(spec) }
    };
  }
  if (type === 'decisionTable') {
    return {
      ...base,
      hitPolicy: 'unique',
      scoreSource: 'ctx.score',
      amountSource: 'ctx.amount',
      rules: defaultDecisionRules()
    };
  }
  if (customOperator) {
    return {
      ...base,
      ...defaultCustomInputStateForOperator(spec),
      config: defaultConfigForOperator(spec)
    };
  }
  return {
    ...base,
    policyNode: firstDecisionTableId()
  };
}

function defaultParamNameForOperator(spec) {
  const primaryInput = inputPortsForSpec(spec)[0];
  const fields = schemaDefaultInputFields(primaryInput?.schema || spec?.inputSchema);
  if (fields.length) {
    return fields[0].path;
  }
  return 'applicantId';
}

function defaultInputExpressionsForOperator(spec) {
  const entries = [];
  for (const port of inputPortsForSpec(spec)) {
    const fields = schemaDefaultInputFields(port?.schema);
    for (const field of fields) {
      if (!entries.some(([existing]) => existing === field.path)) {
        entries.push([field.path, `ctx.${field.path}`]);
      }
    }
  }
  return Object.fromEntries(entries);
}

function defaultCustomInputStateForOperator(spec) {
  const customInputs = {};
  const customInputPorts = {};
  const customInputPaths = {};
  for (const port of inputPortsForSpec(spec)) {
    const fields = schemaDefaultInputFields(port?.schema);
    for (const field of fields) {
      const key = inputKeyForPortPath(spec, port.name, field.path);
      customInputs[key] = `ctx.${field.path}`;
      customInputPorts[key] = port.name;
      customInputPaths[key] = field.path;
    }
  }
  return { customInputs, customInputPorts, customInputPaths };
}

function defaultConfigForOperator(spec) {
  const config = {};
  const rootSchema = spec.configSchema?.schema || {};
  if (rootSchema.default && typeof rootSchema.default === 'object' && !Array.isArray(rootSchema.default)) {
    Object.assign(config, cloneJsonValue(rootSchema.default));
  }
  for (const field of configDefaultFieldDescriptors(spec.configSchema)) {
    if (!hasConfigPath(config, field.path)
        && Object.prototype.hasOwnProperty.call(field.schema || {}, 'default')) {
      setConfigValueAtPath(config, field.path, cloneJsonValue(field.schema.default));
    }
  }
  return config;
}

function cloneJsonValue(value) {
  if (Array.isArray(value)) {
    return value.map((item) => cloneJsonValue(item));
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, cloneJsonValue(item)]));
  }
  return value;
}

function configPathSegments(path) {
  return String(path || '')
    .split('.')
    .map((segment) => segment.trim())
    .filter(Boolean);
}

function hasConfigPath(config, path) {
  const segments = configPathSegments(path);
  if (!segments.length) {
    return config !== undefined;
  }
  let current = config;
  for (const segment of segments) {
    if (!isConfigContainerObject(current) || !Object.prototype.hasOwnProperty.call(current, segment)) {
      return false;
    }
    current = current[segment];
  }
  return true;
}

function configValueAtPath(config, path) {
  const segments = configPathSegments(path);
  let current = config;
  for (const segment of segments) {
    if (!isConfigContainerObject(current) || !Object.prototype.hasOwnProperty.call(current, segment)) {
      return undefined;
    }
    current = current[segment];
  }
  return current;
}

function setConfigValueAtPath(config, path, value) {
  const segments = configPathSegments(path);
  if (!segments.length) {
    return;
  }
  let current = config;
  for (const segment of segments.slice(0, -1)) {
    if (!isConfigContainerObject(current[segment])) {
      current[segment] = {};
    }
    current = current[segment];
  }
  current[segments[segments.length - 1]] = value;
}

function deleteConfigValueAtPath(config, path) {
  const segments = configPathSegments(path);
  if (!segments.length) {
    return;
  }
  let current = config;
  const parents = [];
  for (const segment of segments.slice(0, -1)) {
    if (!isConfigContainerObject(current) || !isConfigContainerObject(current[segment])) {
      return;
    }
    parents.push([current, segment]);
    current = current[segment];
  }
  if (!isConfigContainerObject(current)) {
    return;
  }
  delete current[segments[segments.length - 1]];
  for (let i = parents.length - 1; i >= 0; i--) {
    const [parent, segment] = parents[i];
    if (isPlainObject(parent[segment]) && Object.keys(parent[segment]).length === 0) {
      delete parent[segment];
    } else {
      break;
    }
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isConfigContainerObject(value) {
  return isPlainObject(value) && !isConfigBindingObject(value);
}

function defaultResourceParamInputs(spec) {
  const inputs = defaultInputExpressionsForOperator(spec);
  if (Object.keys(inputs).length) {
    return inputs;
  }
  const paramName = defaultParamNameForOperator(spec);
  return { [paramName]: `ctx.${paramName}` };
}

function resourceParamInputs(node, spec = specForNode(node)) {
  if (node.paramInputs && typeof node.paramInputs === 'object' && Object.keys(node.paramInputs).length) {
    return { ...node.paramInputs };
  }
  const paramName = node.paramName || defaultParamNameForOperator(spec);
  return { [paramName]: node.applicantExpr || `ctx.${paramName}` };
}

function setResourceParamExpression(node, name, expression) {
  const spec = specForNode(node);
  node.paramInputs = {
    ...resourceParamInputs(node, spec),
    [name]: expression
  };
  node.paramName = name;
  node.applicantExpr = expression;
}

function expressionForTargetInput(node, target) {
  if (node.type === 'httpResource') {
    return resourceParamInputs(node, specForNode(node))[target.path] || '';
  }
  if (node.type === 'decisionTable') {
    return target.path === 'amount' ? node.amountSource : node.scoreSource;
  }
  if (node.type === 'customOperator') {
    return node.customInputs?.[target.key || target.path] || '';
  }
  if (node.type === 'transform') {
    const policyNode = node.policyNode || (node.policyNodeCleared ? '' : firstDecisionTableId());
    return policyNode ? `${policyNode}.output` : '';
  }
  return '';
}

function setExpressionForTargetInput(node, target, expression) {
  if (node.type === 'httpResource') {
    setResourceParamExpression(node, target.path || defaultParamNameForOperator(specForNode(node)), expression);
  } else if (node.type === 'decisionTable') {
    if (target.path === 'amount') {
      node.amountSource = expression;
    } else {
      node.scoreSource = expression;
    }
  } else if (node.type === 'customOperator') {
    const key = target.key || target.path;
    node.customInputs = node.customInputs || {};
    node.customInputs[key] = expression;
    node.customInputPorts = node.customInputPorts || {};
    node.customInputPorts[key] = target.port || inputPortForInputPath(specForNode(node), target.path);
    node.customInputPaths = node.customInputPaths || {};
    node.customInputPaths[key] = target.path;
  } else if (node.type === 'transform') {
    const source = connectionSourceFromExpression(expression);
    if (!String(expression || '').trim()) {
      node.policyNode = '';
      node.policyNodeCleared = true;
    } else if (source?.nodeId) {
      node.policyNode = source.nodeId;
      delete node.policyNodeCleared;
    }
  }
}

function requiredInputNamesForPort(port) {
  const required = port?.schema?.schema?.required;
  return Array.isArray(required) ? required : [];
}

function configFieldDescriptors(configSchema) {
  const fields = schemaFieldsFromSchema(configSchema?.schema || {}, '', true);
  const leaves = fields.filter((field) => !hasSchemaProperties(field.schema));
  return leaves.length ? leaves : fields;
}

function configDefaultFieldDescriptors(configSchema) {
  return schemaFieldsFromSchema(configSchema?.schema || {}, '', true);
}

function schemaProperties(schemaEnvelope) {
  return schemaEnvelope?.schema?.properties || {};
}

function schemaFieldDescriptors(schemaEnvelope) {
  return schemaFieldsFromSchema(schemaEnvelope?.schema || {}, '', true);
}

function schemaDefaultInputFields(schemaEnvelope) {
  const fields = dslSafeSchemaFieldDescriptors(schemaEnvelope);
  const leafFields = fields.filter((field) => !hasSchemaProperties(field.schema));
  const preferred = leafFields.length ? leafFields : fields;
  const required = preferred.filter((field) => field.required);
  return required.length ? required : preferred;
}

function dslSafeSchemaFieldDescriptors(schemaEnvelope) {
  return schemaFieldDescriptors(schemaEnvelope).filter((field) => field.dslPathSafe);
}

function schemaFieldsFromSchema(schema, prefix, parentRequired, prefixDslPathSafe = true) {
  const normalizedSchema = schema && typeof schema === 'object' && !Array.isArray(schema) ? schema : {};
  const properties = normalizedSchema.properties || {};
  const required = new Set(Array.isArray(normalizedSchema.required) ? normalizedSchema.required.map(String) : []);
  const propertyFields = Object.entries(properties).flatMap(([name, childSchema]) => {
    const path = prefix ? `${prefix}.${name}` : name;
    const normalizedChildSchema = childSchema && typeof childSchema === 'object' && !Array.isArray(childSchema)
      ? childSchema
      : {};
    const fieldRequired = parentRequired && required.has(name);
    const fieldDslPathSafe = prefixDslPathSafe && isDslFieldName(name);
    const hasNestedRequired = Array.isArray(normalizedChildSchema.required)
      && normalizedChildSchema.required.length > 0;
    return [
      {
        path,
        schema: normalizedChildSchema,
        required: fieldRequired && !hasNestedRequired,
        dslPathSafe: fieldDslPathSafe
      },
      ...schemaFieldsFromSchema(normalizedChildSchema, path, fieldRequired, fieldDslPathSafe)
    ];
  });
  if (rawSchemaType(normalizedSchema) !== 'array') {
    return propertyFields;
  }
  return [
    ...propertyFields,
    ...arraySchemaFieldDescriptors(normalizedSchema, prefix, prefixDslPathSafe)
  ];
}

function arraySchemaFieldDescriptors(schema, prefix, prefixDslPathSafe) {
  const itemDescriptors = [];
  const prefixItems = Array.isArray(schema?.prefixItems) ? schema.prefixItems : [];
  prefixItems.forEach((itemSchema, index) => {
    if (itemSchema && typeof itemSchema === 'object' && !Array.isArray(itemSchema)) {
      itemDescriptors.push({ index, schema: itemSchema });
    }
  });
  const itemsSchema = schema?.items && typeof schema.items === 'object' && !Array.isArray(schema.items)
    ? schema.items
    : null;
  if (itemsSchema) {
    const nextUniformIndex = prefixItems.length;
    if (!itemDescriptors.some((item) => item.index === nextUniformIndex)) {
      itemDescriptors.push({ index: nextUniformIndex, schema: itemsSchema });
    }
  }
  return itemDescriptors.flatMap((item) => {
    const path = prefix ? `${prefix}.${item.index}` : String(item.index);
    return [
      {
        path,
        schema: item.schema,
        required: false,
        dslPathSafe: prefixDslPathSafe
      },
      ...schemaFieldsFromSchema(item.schema, path, false, prefixDslPathSafe)
    ];
  });
}

function isDslPathSafe(path) {
  if (!path) {
    return true;
  }
  const normalized = String(path).startsWith('.') ? String(path).slice(1) : String(path);
  return normalized.split('.')
    .filter(Boolean)
    .every((segment) => isDslFieldName(segment));
}

function dslReferenceSuffixForSchemaPath(path) {
  const segments = String(path || '').split('.').filter(Boolean);
  return segments.map((segment) =>
    arrayIndexSegment(segment) !== null ? `[${segment}]` : `.${segment}`
  ).join('');
}

function schemaPathFromDslReferenceSuffix(path) {
  const segments = schemaPathSegmentsFromDslReferenceSuffix(path);
  return segments === null ? null : segments.join('.');
}

function schemaPathSegmentsFromDslReferenceSuffix(path) {
  const value = String(path || '').trim();
  if (!value) {
    return [];
  }
  const segments = [];
  let index = 0;
  while (index < value.length) {
    if (value[index] === '.') {
      index += 1;
      if (index >= value.length || value[index] === '.') {
        return null;
      }
      continue;
    }
    if (value[index] === '[') {
      const end = value.indexOf(']', index + 1);
      if (end < 0) {
        return null;
      }
      const segment = value.slice(index + 1, end);
      if (arrayIndexSegment(segment) === null) {
        return null;
      }
      segments.push(segment);
      index = end + 1;
      if (index < value.length && value[index] !== '.' && value[index] !== '[') {
        return null;
      }
      continue;
    }
    const match = value.slice(index).match(/^[A-Za-z_][A-Za-z0-9_]*/);
    if (!match) {
      return null;
    }
    segments.push(match[0]);
    index += match[0].length;
    if (index < value.length && value[index] !== '.' && value[index] !== '[') {
      return null;
    }
  }
  return segments;
}

function isDslFieldName(value) {
  const text = String(value || '');
  return DSL_FIELD_IDENTIFIER.test(text) && !RESERVED_DSL_FIELD_NAMES.has(text);
}

function isSchemaPathDslSafe(schemaEnvelope, path) {
  if (!path) {
    return true;
  }
  let current = schemaEnvelope?.schema || {};
  const normalized = String(path).startsWith('.') ? String(path).slice(1) : String(path);
  for (const segment of normalized.split('.').filter(Boolean)) {
    if (rawSchemaType(current) === 'array') {
      const index = arrayIndexSegment(segment);
      if (index !== null) {
        current = arrayItemSchemaForIndex(current, index) || {};
        continue;
      }
    }
    if (!isDslFieldName(segment)) {
      return false;
    }
    current = childSchemaForPathSegment(current, segment) || {};
  }
  return true;
}

function childSchemaForPathSegment(schema, segment) {
  const properties = schema?.properties || {};
  if (Object.prototype.hasOwnProperty.call(properties, segment)) {
    return properties[segment] || {};
  }
  return patternPropertySchema(schema, segment) || additionalPropertySchema(schema);
}

function hasSchemaProperties(schema) {
  return Boolean(schema?.properties && Object.keys(schema.properties).length);
}

function schemaDeclaresPath(schemaEnvelope, path) {
  return schemaAtPath(schemaEnvelope, path) !== null;
}

function nonOverlappingNodePosition(x, y) {
  let position = clampNodePosition(x, y);
  for (let attempt = 0; attempt < 10; attempt++) {
    const overlaps = state.builder.nodes.some((node) =>
      rectanglesOverlap(position.x, position.y, NODE_SIZE.width, NODE_SIZE.height, node.x, node.y, NODE_SIZE.width, NODE_SIZE.height, 14)
    );
    if (!overlaps) {
      return position;
    }
    position = clampNodePosition(position.x + 28, position.y + NODE_SIZE.height + 24);
  }
  return position;
}

function rectanglesOverlap(ax, ay, aw, ah, bx, by, bw, bh, gap = 0) {
  return ax < bx + bw + gap
    && ax + aw + gap > bx
    && ay < by + bh + gap
    && ay + ah + gap > by;
}

function clampNodePosition(x, y) {
  return {
    x: Math.max(40, Math.round(x)),
    y: Math.max(80, Math.round(y))
  };
}

function uniqueNodeId(baseId) {
  const used = new Set(state.builder.nodes.map((node) => node.id));
  if (!used.has(baseId)) {
    return baseId;
  }
  let index = 2;
  while (used.has(`${baseId}${index}`)) {
    index++;
  }
  return `${baseId}${index}`;
}

function deleteSelectedBuilderNode() {
  const selected = selectedBuilderNode();
  if (!selected || state.builder.nodes.length <= 1) return;
  state.builder.nodes = state.builder.nodes.filter((node) => node.id !== selected.id);
  state.builder.dependencyEdges = (state.builder.dependencyEdges || [])
    .filter((edge) => edge.source !== selected.id && edge.target !== selected.id);
  state.builder.routeEdges = (state.builder.routeEdges || [])
    .filter((edge) => edge.source !== selected.id && edge.target !== selected.id);
  removeBuilderReferencesToNode(selected.id);
  state.builder.selectedId = orderedBuilderNodes()[0]?.id || null;
  state.selectedNodeId = state.builder.selectedId;
  if (selected.type === 'httpResource' && !state.builder.nodes.some((node) => node.type === 'httpResource')) {
    for (const node of state.builder.nodes.filter((item) => item.type === 'decisionTable')) {
      node.scoreSource = 'ctx.score';
      node.amountSource = 'ctx.amount';
    }
  }
  syncComposerFromBuilder();
  renderInputForm();
  renderDiagram();
}

function removeBuilderReferencesToNode(nodeId) {
  for (const node of state.builder.nodes) {
    if (node.type === 'httpResource') {
      const params = resourceParamInputs(node, specForNode(node));
      for (const [name, expression] of Object.entries(params)) {
        if (expressionReferencesNode(expression, nodeId)) {
          params[name] = fallbackContextExpression(name);
        }
      }
      node.paramInputs = params;
      if (expressionReferencesNode(node.applicantExpr, nodeId)) {
        node.applicantExpr = fallbackContextExpression(node.paramName || defaultParamNameForOperator(specForNode(node)));
      }
    } else if (node.type === 'decisionTable') {
      if (expressionReferencesNode(node.scoreSource, nodeId)) {
        node.scoreSource = 'ctx.score';
      }
      if (expressionReferencesNode(node.amountSource, nodeId)) {
        node.amountSource = 'ctx.amount';
      }
    } else if (node.type === 'customOperator') {
      for (const [key, expression] of Object.entries(node.customInputs || {})) {
        if (expressionReferencesNode(expression, nodeId)) {
          node.customInputs[key] = fallbackContextExpression(customInputPathForKey(node, key));
        }
      }
    } else if (node.type === 'transform' && node.policyNode === nodeId) {
      node.policyNode = '';
      node.policyNodeCleared = true;
    }
    removeConfigReferencesToNode(node.config, nodeId);
  }
}

function removeConfigReferencesToNode(value, nodeId) {
  if (!isConfigContainerObject(value)) {
    return;
  }
  for (const [key, item] of Object.entries(value)) {
    if (isConfigExpressionValue(item) && expressionReferencesNode(configExpressionForField(item), nodeId)) {
      delete value[key];
    } else if (isConfigContainerObject(item)) {
      removeConfigReferencesToNode(item, nodeId);
      if (Object.keys(item).length === 0) {
        delete value[key];
      }
    }
  }
}

function expressionReferencesNode(expression, nodeId) {
  const source = connectionSourceFromExpression(expression, state.builder);
  return Boolean(source && source.nodeId === nodeId);
}

function fallbackContextExpression(path) {
  return path ? `ctx.${path}` : '';
}

function applyResourceDefaults(resourceNode) {
  const decisionNode = state.builder.nodes.find((node) => node.type === 'decisionTable');
  if (decisionNode) {
    const scoreTarget = targetHandlesForNode(decisionNode).find((target) => target.path === 'score');
    const scoreSource = preferredSourceExpressionForTarget(resourceNode, scoreTarget, ['score']);
    if (scoreSource) {
      decisionNode.scoreSource = scoreSource;
    }
    decisionNode.amountSource = 'ctx.requestedAmount';
  }
  let context;
  try {
    context = JSON.parse(state.customContextText || '{}');
  } catch {
    context = {};
  }
  const paramNames = Object.keys(resourceParamInputs(resourceNode, specForNode(resourceNode)));
  for (const paramName of paramNames.length ? paramNames : [resourceNode.paramName || 'applicantId']) {
    context[paramName] = context[paramName] || sampleValueForParam(paramName);
  }
  context.requestedAmount = context.requestedAmount || 450000;
  state.customContextText = pretty(context);
}

function preferredSourceExpressionForTarget(sourceNode, target, preferredPaths = []) {
  if (!sourceNode || !target) {
    return '';
  }
  const compatible = sourceHandlesForNode(sourceNode)
    .filter((source) => connectionCompatibility(source, target).ok);
  for (const path of preferredPaths) {
    const preferred = compatible.find((source) => source.path === path);
    if (preferred) {
      return expressionForConnectionSource(preferred);
    }
  }
  return '';
}

function sampleValueForParam(paramName) {
  const normalized = String(paramName || '').toLowerCase();
  if (normalized.includes('amount')) return 450000;
  if (normalized.includes('product')) return 'p1';
  if (normalized.includes('order')) return 'o1';
  if (normalized.includes('user')) return 'u1';
  if (normalized.includes('applicant')) return 'prime';
  return 'demo';
}

function firstDecisionTableId() {
  return state.builder.nodes.find((node) => node.type === 'decisionTable')?.id || 'loanPolicy';
}

function defaultOutputNodeForBuilder(builder = state.builder) {
  const ordered = orderedBuilderNodes(builder);
  const lastTransform = [...ordered].reverse().find((node) => node.type === 'transform');
  const lastSelectable = [...ordered].reverse().find((node) => outputPortsForSpec(specForNode(node)).length > 0);
  return (lastTransform || lastSelectable || ordered[ordered.length - 1])?.id || 'response';
}

function ensureBuilderOutput(builder = state.builder) {
  const fallbackNodeId = defaultOutputNodeForBuilder(builder);
  const requested = builder.output || {};
  const nodeExists = builder.nodes.some((node) => node.id === requested.nodeId);
  const requestedNode = builder.nodes.find((node) => node.id === requested.nodeId);
  const nodeId = nodeExists && outputPortsForSpec(specForNode(requestedNode)).length > 0
    ? requested.nodeId
    : fallbackNodeId;
  const node = builder.nodes.find((item) => item.id === nodeId);
  const pathOptions = outputPathOptionsForNode(node);
  const requestedPath = String(requested.path || '');
  const path = pathOptions.some((option) => option.value === requestedPath) ? requestedPath : '';
  builder.output = { nodeId, path };
  return builder.output;
}

function syncComposerFromBuilder(options = {}) {
  const render = options.render !== false;
  ensureBuilderOutput(state.builder);
  state.customDsl = builderToDsl(state.builder);
  state.layout = layoutFromBuilder(state.builder);
  state.customDecisionTable = decisionTableFromBuilder(state.builder);
  state.selectedNodeId = state.builder.selectedId;
  const dslBox = $('composer-dsl');
  if (dslBox && dslBox.value !== state.customDsl) {
    dslBox.value = state.customDsl;
  }
  const contextBox = $('composer-context');
  if (contextBox && contextBox.value !== state.customContextText) {
    contextBox.value = state.customContextText;
  }
  const graphInputSchemaBox = $('graph-input-schema');
  if (graphInputSchemaBox && graphInputSchemaBox.value !== state.graphInputSchemaText) {
    graphInputSchemaBox.value = state.graphInputSchemaText;
  }
  renderGraphInputSchemaStatus();
  if (render && isComposerSelected()) {
    renderDecisionTable();
    renderNodeDetails(selectedBuilderNode() || state.layout.nodes[0]);
    renderGraphOutputEditor();
  }
}

function layoutFromBuilder(builder) {
  const nodes = builder.nodes.map((node) => {
    const spec = specForNode(node);
    return {
      id: node.id,
      kind: spec.kind,
      operatorRef: spec.visualOperatorRef || spec.operatorRef,
      label: labelForNode(node),
      position: { x: node.x, y: node.y },
      size: { ...NODE_SIZE },
      group: null,
      annotations: {
        type: node.type,
        generated: true
      }
    };
  });
  const edges = builderEdges(builder, { includeConfig: true }).map((edge) => ({
    id: `${edge.kind || 'data'}:${edge.source}:${edge.sourcePort || ''}.${edge.sourcePath || ''}->${edge.target}:${edge.targetPort || ''}.${edge.targetPath || ''}${edge.kind === 'route' ? `:${edge.condition || 'otherwise'}` : ''}`,
    kind: edge.kind || 'data',
    source: edge.source,
    target: edge.target,
    sourcePort: edge.sourcePort || '',
    sourcePath: edge.sourcePath || '',
    targetPort: edge.targetPort || '',
    targetPath: edge.targetPath || '',
    condition: edge.condition || '',
    label: edge.label
  }));
  return {
    schemaVersion: 'bloge.visualLayout.v1',
    rootId: builder.graphName,
    executionMode: 'GRAPH',
    nodes,
    edges,
    groups: [],
    viewport: { x: 0, y: 0, zoom: 1 }
  };
}

function decisionTableFromBuilder(builder) {
  const node = builder.nodes.find((item) => item.type === 'decisionTable');
  if (!node) {
    return null;
  }
  return {
    title: readableName(node.id),
    hitPolicy: node.hitPolicy,
    inputs: [
      { key: 'score', label: 'Score' },
      { key: 'amount', label: 'Amount' }
    ],
    outputs: [
      { key: 'decision', label: 'Decision' },
      { key: 'rate', label: 'Rate' },
      { key: 'maxTerm', label: 'MaxTerm' },
      { key: 'reviewLane', label: 'ReviewLane' },
      { key: 'ruleId', label: 'RuleId' }
    ],
    rows: node.rules.map((rule) => ({
      id: rule.id,
      conditions: {
        score: rule.otherwise ? 'otherwise' : rule.score,
        amount: rule.otherwise ? 'otherwise' : rule.amount
      },
      output: {
        decision: rule.decision,
        rate: rule.rate,
        maxTerm: rule.maxTerm,
        reviewLane: rule.reviewLane,
        ruleId: rule.id
      },
      explanation: rule.otherwise ? 'otherwise' : `${rule.score}, ${rule.amount}`
    }))
  };
}

function builderToDsl(builder) {
  const body = orderedDslNodes(builder)
    .map((node) => nodeToDsl(node, builder))
    .filter(Boolean)
    .join('\n\n');
  return `graph ${builder.graphName} {\n\n${body}\n}`;
}

function nodeToDsl(node, builder) {
  const dependsOn = dependsOnDslLine(builder, node);
  if (node.type === 'httpResource') {
    const params = resourceParamInputs(node, specForNode(node));
    const paramBody = Object.entries(params)
      .map(([name, expression]) => `${name}: ${expression || 'null'}`)
      .join(', ');
    const executionConfig = commonExecutionConfigToDsl(node.config || {});
    return `  node ${node.id} : httpResource {\n${dependsOn}    input {\n      resourceId = ${quote(node.resourceId)}\n      params = { ${paramBody} }\n    }${executionConfig ? `\n${executionConfig}` : ''}\n  }`;
  }
  if (node.type === 'decisionTable') {
    const rules = node.rules.map((rule) => {
      const output = `{ decision: ${quote(rule.decision)}, rate: ${numberValue(rule.rate)}, maxTerm: ${numberValue(rule.maxTerm)}, reviewLane: ${quote(rule.reviewLane)}, ruleId: ${quote(rule.id)} }`;
      if (rule.otherwise) {
        return `    otherwise                                                  -> ${output}`;
      }
      return `    rule (score: ${rule.score}, amount: ${rule.amount}) -> ${output}`;
    }).join('\n');
    return `  decision_table ${node.id}(\n    score  = ${node.scoreSource},\n    amount = ${node.amountSource}\n  ) hit=${node.hitPolicy || 'unique'} -> { decision: String, rate: Decimal, maxTerm: Int, reviewLane: String, ruleId: String } {\n${rules}\n  }`;
  }
  if (node.type === 'transform') {
    const decisionNode = node.policyNodeCleared
      ? null
      : builder.nodes.find((item) => item.id === node.policyNode)
        || builder.nodes.find((item) => item.type === 'decisionTable');
    const resourceNode = builder.nodes.find((item) => item.type === 'httpResource');
    if (node.policyNodeCleared) {
      return `  transform ${node.id} {\n    result = {}\n  }`;
    }
    if (!decisionNode) {
      const previous = orderedBuilderNodes(builder).filter((item) => item.id !== node.id).at(-1);
      return `  transform ${node.id} {\n    result = ${previous ? `${previous.id}.output` : '{}'}\n  }`;
    }
    const applicant = resourceNode
      ? `${resourceNode.id}.output.payload`
      : `{ score: ${decisionNode.scoreSource}, segment: ctx.segment }`;
    return `  transform ${node.id} {\n    applicant       = ${applicant}\n    requestedAmount = ${decisionNode.amountSource}\n    policy          = ${decisionNode.id}.output\n  }`;
  }
  if (node.type === 'customOperator') {
    return customNodeToDsl(node, builder);
  }
  return '';
}

function customNodeToDsl(node, builder) {
  const spec = specForNode(node);
  const inputs = customInputTemplateValues(node);
  if (spec.lowering?.mode === 'branch') {
    const selector = renderTemplateExpression(String(spec.lowering?.parameters?.expression || ''), inputs) || 'null';
    const routes = routeEdgesForNode(builder, node.id)
      .map((edge) => `    ${renderRouteConditionForDsl(edge.condition)} -> ${edge.target}`)
      .join('\n');
    return `  transform ${node.id} {\n    value = ${selector}\n  }\n\n  branch on ${node.id}.output.value {\n${routes}\n  }`;
  }
  if (spec.lowering?.mode === 'transform' && spec.lowering?.parameters?.assignments) {
    const assignments = Object.entries(spec.lowering.parameters.assignments).map(([key, template]) =>
      `    ${key} = ${renderTemplateExpression(String(template), inputs)}`
    ).join('\n');
    return `  transform ${node.id} {\n${assignments || '    result = {}'}\n  }`;
  }
  const executable = spec.lowering?.operatorRef || spec.operatorRef || spec.visualOperatorRef || node.paletteType;
  const inputEntries = customDslInputEntries(node);
  const config = customBusinessConfig(node.config || {});
  if (spec.sourceKind === 'visual-publication' && spec.lowering?.parameters?.publicationId && !config.publicationId) {
    config.publicationId = spec.lowering.parameters.publicationId;
  }
  if (Object.keys(config).length && !inputEntries.some(([key]) => key === 'config')) {
    inputEntries.push(['config', renderConfigDslValue(config)]);
  }
  const inputLines = inputEntries.map(([key, expression]) =>
    `      ${key} = ${expression || 'null'}`
  ).join('\n');
  const executionConfig = commonExecutionConfigToDsl(node.config || {});
  return `  node ${node.id} : ${renderOperatorRefForDsl(executable)} {\n${dependsOnDslLine(builder, node)}    input {\n${inputLines}\n    }${executionConfig ? `\n${executionConfig}` : ''}\n  }`;
}

function dependsOnDslLine(builder, node) {
  if (!nodeSupportsDependencyTarget(node)) {
    return '';
  }
  const dependencies = dependencySourcesForNode(builder, node.id);
  return dependencies.length ? `    depends_on = [${dependencies.join(', ')}]\n` : '';
}

function dependencySourcesForNode(builder, nodeId) {
  const seen = new Set();
  const dependencies = [];
  for (const edge of builder.dependencyEdges || []) {
    if (edge.target !== nodeId || !edge.source || seen.has(edge.source)) {
      continue;
    }
    seen.add(edge.source);
    dependencies.push(edge.source);
  }
  return dependencies;
}

function routeEdgesForNode(builder, nodeId) {
  return (builder.routeEdges || [])
    .filter((edge) => edge.source === nodeId && edge.target)
    .map((edge) => ({
      ...edge,
      condition: edge.condition || 'otherwise'
    }));
}

function renderRouteConditionForDsl(condition) {
  const value = String(condition || '').trim();
  if (!value || value.toLowerCase() === 'otherwise') {
    return 'otherwise';
  }
  if (value === 'true' || value === 'false' || value === 'null' || /^[-+]?(?:\d+|\d+\.\d*|\d*\.\d+)(?:[eE][-+]?\d+)?$/.test(value)) {
    return value;
  }
  if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
    return value;
  }
  return quote(value);
}

function customInputTemplateValues(node) {
  const values = {};
  for (const [key, expression] of Object.entries(node.customInputs || {})) {
    const targetPath = customInputPathForKey(node, key);
    const targetPort = node.customInputPorts?.[key] || '';
    if (targetPath) {
      values[targetPath] = expression;
    }
    if (targetPort) {
      values[targetPath ? `${targetPort}.${targetPath}` : targetPort] = expression;
    }
    if (key && key !== targetPath) {
      values[key] = expression;
    }
  }
  return values;
}

function customDslInputEntries(node) {
  const inputTree = {};
  for (const [key, expression] of Object.entries(node.customInputs || {})) {
    putExpressionAtPath(inputTree, customDslInputPath(node, key), expression || 'null');
  }
  return Object.entries(inputTree).map(([key, value]) => [
    key,
    renderExpressionTreeValue(value)
  ]);
}

function customDslInputPath(node, key) {
  const spec = specForNode(node);
  const targetPath = customInputPathForKey(node, key);
  const targetPort = customInputPortForKey(node, spec, key);
  if (!targetPort || targetPort === 'inputs' || targetPath === targetPort || targetPath.startsWith(`${targetPort}.`)) {
    return targetPath;
  }
  return targetPath ? `${targetPort}.${targetPath}` : targetPort;
}

function putExpressionAtPath(tree, path, expression) {
  const segments = String(path || '').split('.').filter(Boolean);
  if (!segments.length) {
    return;
  }
  let current = tree;
  for (let index = 0; index < segments.length; index += 1) {
    const segment = segments[index];
    if (index === segments.length - 1) {
      current[segment] = expression;
      return;
    }
    if (!current[segment] || typeof current[segment] !== 'object' || Array.isArray(current[segment])) {
      current[segment] = {};
    }
    current = current[segment];
  }
}

function renderExpressionTreeValue(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return String(value ?? 'null');
  }
  const fields = Object.entries(value).map(([key, item]) =>
    `${key}: ${renderExpressionTreeValue(item)}`
  );
  return `{ ${fields.join(', ')} }`;
}

function customBusinessConfig(config = {}) {
  return Object.fromEntries(Object.entries(config)
    .filter(([key]) => key !== 'timeout' && key !== 'retryAttempts'));
}

function renderConfigDslValue(value) {
  if (isConfigBindingObject(value)) {
    return expressionFromConfig(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => renderConfigDslValue(item)).join(', ')}]`;
  }
  if (isPlainObject(value)) {
    const fields = Object.entries(value).map(([key, item]) =>
      `${key}: ${renderConfigDslValue(item)}`
    );
    return `{ ${fields.join(', ')} }`;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (value === null || value === undefined) {
    return 'null';
  }
  return quote(value);
}

function renderOperatorRefForDsl(operatorRef) {
  const value = String(operatorRef || '');
  return /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$/.test(value) ? value : quote(value);
}

function renderTemplateExpression(template, inputs) {
  let expression = template;
  for (const [name, value] of Object.entries(inputs || {})) {
    if (name) {
      expression = replaceTemplateDescendants(expression, `input.${name}`, value || 'null');
      expression = replaceTemplateDescendants(expression, name, value || 'null');
    }
    expression = replaceTemplateReference(expression, `input.${name}`, value || 'null');
    expression = replaceTemplateReference(expression, name, value || 'null');
  }
  return replaceUnresolvedTemplateReferences(expression);
}

function replaceUnresolvedTemplateReferences(expression) {
  return expression.replace(
    /\{\{\s*(?:input\.)?[A-Za-z_][A-Za-z0-9_]*(?:\.(?:[A-Za-z_][A-Za-z0-9_]*|\d+))*\s*\}\}/g,
    'null'
  );
}

function replaceTemplateDescendants(expression, prefix, value) {
  const escaped = prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pathSegment = '(?:[A-Za-z_][A-Za-z0-9_]*|\\d+)';
  const pattern = new RegExp(`\\{\\{\\s*${escaped}\\.(${pathSegment}(?:\\.${pathSegment})*)\\s*\\}\\}`, 'g');
  return expression.replace(pattern, (_, path) => expressionWithPath(value, path));
}

function replaceTemplateReference(expression, reference, value) {
  const escaped = reference.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return expression.replace(new RegExp(`\\{\\{\\s*${escaped}\\s*\\}\\}`, 'g'), value);
}

function expressionWithPath(expression, path) {
  if (!path) {
    return expression;
  }
  return `${expression}${dslReferenceSuffixForSchemaPath(path)}`;
}

function builderToVisualDraft(builder = state.builder) {
  const layout = layoutFromBuilder(builder);
  const output = ensureBuilderOutput(builder);
  const scope = builderScope(builder);
  return {
    schemaVersion: 'bloge.visualGraphDraft.v1',
    draftId: state.currentDraftId || '',
    revision: state.currentDraftRevision || 0,
    graphName: builder.graphName,
    tenantId: scope.tenantId,
    namespace: scope.namespace,
    environment: scope.environment,
    status: 'DRAFT',
    inputSchema: currentGraphInputSchema(builder),
    nodes: builder.nodes.map((node) => builderNodeToDraftNode(node, builder)),
    edges: builderEdges(builder, { includeFallback: false }).map((edge) => visualDraftEdgeFromBuilderEdge(edge)),
    visualLayout: layout,
    output,
    operatorFingerprints: operatorFingerprintsForBuilder(builder)
  };
}

function operatorFingerprintsForBuilder(builder = state.builder) {
  const saved = builder.operatorFingerprints || {};
  return Object.fromEntries(builder.nodes
    .map((node) => [node.id, saved[node.id] || specForNode(node).fingerprint || ''])
    .filter((entry) => entry[1]));
}

function builderFromVisualDraft(draft) {
  const layoutNodes = Object.fromEntries((draft.visualLayout?.nodes || [])
    .map((node) => [node.id, node]));
  const nodes = (draft.nodes || []).map((node) => builderNodeFromDraftNode(node, draft, layoutNodes));
  const selectedId = nodes[0]?.id || null;
  let inputSchema = null;
  try {
    inputSchema = draft.inputSchema ? normalizeGraphInputSchemaEnvelope(draft.inputSchema) : null;
  } catch {
    inputSchema = null;
  }
  const builder = {
    graphName: draft.graphName || 'visualGraph',
    tenantId: draft.tenantId || 'demo-tenant',
    namespace: draft.namespace || 'local',
    environment: draft.environment || 'browser',
    inputSchema,
    selectedId,
    output: {
      nodeId: draft.output?.nodeId || '',
      path: draft.output?.path || ''
    },
    operatorFingerprints: { ...(draft.operatorFingerprints || {}) },
    dependencyEdges: dependencyEdgesFromDraft(draft),
    routeEdges: routeEdgesFromDraft(draft),
    nodes
  };
  hydrateDynamicOutputPathsFromDraft(builder, draft);
  ensureBuilderOutput(builder);
  return builder;
}

function hydrateDynamicOutputPathsFromDraft(builder, draft) {
  const outputNode = builder.nodes.find((node) => node.id === draft.output?.nodeId);
  if (outputNode && draft.output?.path) {
    const reference = outputReferenceFromSelectionPath(specForNode(outputNode), draft.output.path);
    rememberDynamicOutputPath(builder, outputNode.id, reference.port, reference.path);
  }
  for (const node of draft.nodes || []) {
    collectNodePathBindings(node.inputs, (binding) => {
      rememberDynamicOutputPath(builder, binding.nodeId, binding.sourcePort || '', binding.path || '');
    });
    collectNodePathBindings(node.config, (binding) => {
      rememberDynamicOutputPath(builder, binding.nodeId, binding.sourcePort || '', binding.path || '');
    });
  }
  for (const edge of draft.edges || []) {
    if (canonicalEdgeKind(edge.kind) !== 'data') {
      continue;
    }
    rememberDynamicOutputPath(
      builder,
      edge.source?.nodeId || edge.source || '',
      edge.source?.port || edge.sourcePort || '',
      edge.source?.path || edge.sourcePath || ''
    );
  }
}

function collectNodePathBindings(value, consumer) {
  if (!value || typeof value !== 'object') {
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item) => collectNodePathBindings(item, consumer));
    return;
  }
  if (value.kind === 'nodePath' && value.nodeId) {
    consumer(value);
  }
  for (const item of Object.values(value.fields || {})) {
    collectNodePathBindings(item, consumer);
  }
  for (const [key, item] of Object.entries(value)) {
    if (key === 'fields') {
      continue;
    }
    collectNodePathBindings(item, consumer);
  }
}

function outputReferenceFromSelectionPath(spec, selectionPath) {
  const outputPorts = outputPortsForSpec(spec);
  const primaryPort = outputPorts[0]?.name || spec.outputPort || 'output';
  const value = String(selectionPath || '');
  if (!value) {
    return { port: primaryPort, path: '' };
  }
  const [first, ...rest] = value.split('.');
  const namedPort = outputPorts.find((port) => port.name === first);
  if (namedPort && (outputPorts.length > 1 || first !== 'output')) {
    return { port: first, path: rest.join('.') };
  }
  return { port: primaryPort, path: value };
}

function rememberDynamicOutputPath(builder, nodeId, portName, path) {
  const node = builder.nodes.find((item) => item.id === nodeId);
  if (!node || node.type !== 'customOperator' || !path) {
    return;
  }
  const spec = specForNode(node);
  const resolvedPort = portName || outputPortsForSpec(spec)[0]?.name || spec.outputPort || 'output';
  const portSchema = schemaForPort(spec, 'source', resolvedPort);
  if (!isSchemaPathDslSafe(portSchema, path)) {
    return;
  }
  if (!schemaAtPath(portSchema, path)) {
    return;
  }
  if (dslSafeSchemaFieldDescriptors(portSchema).some((field) => field.path === path)) {
    return;
  }
  const key = outputKeyForPortPath(spec, resolvedPort, path);
  node.customOutputPorts = node.customOutputPorts || {};
  node.customOutputPaths = node.customOutputPaths || {};
  node.customOutputPorts[key] = resolvedPort;
  node.customOutputPaths[key] = path;
}

function visualDraftEdgeFromBuilderEdge(edge) {
  const kind = canonicalEdgeKind(edge.kind);
  const controlEdge = kind === 'dependency' || kind === 'route';
  return {
    id: `${kind}:${edge.source}:${edge.sourcePort || ''}.${edge.sourcePath || ''}->${edge.target}:${edge.targetPort || ''}.${edge.targetPath || ''}${kind === 'route' ? `:${edge.condition || 'otherwise'}` : ''}`,
    kind,
    source: {
      nodeId: edge.source,
      port: controlEdge ? '' : (edge.sourcePort || 'output'),
      path: controlEdge ? '' : (edge.sourcePath || '')
    },
    target: {
      nodeId: edge.target,
      port: controlEdge ? '' : (edge.targetPort || 'inputs'),
      path: controlEdge ? '' : (edge.targetPath || '')
    },
    condition: kind === 'route' ? (edge.condition || 'otherwise') : ''
  };
}

function dependencyEdgesFromDraft(draft) {
  return (draft.edges || [])
    .filter((edge) => canonicalEdgeKind(edge.kind) === 'dependency')
    .map((edge) => ({
      source: edge.source?.nodeId || '',
      target: edge.target?.nodeId || '',
      label: 'depends'
    }))
    .filter((edge) => edge.source && edge.target && edge.source !== edge.target);
}

function routeEdgesFromDraft(draft) {
  return (draft.edges || [])
    .filter((edge) => canonicalEdgeKind(edge.kind) === 'route')
    .map((edge) => ({
      source: edge.source?.nodeId || '',
      target: edge.target?.nodeId || '',
      condition: edge.condition || 'otherwise',
      label: edge.condition || 'otherwise'
    }))
    .filter((edge) => edge.source && edge.target && edge.source !== edge.target);
}

function builderNodeFromDraftNode(node, draft, layoutNodes) {
  const layoutNode = layoutNodes[node.id] || {};
  const position = node.position || layoutNode.position || {};
  const base = {
    id: node.id,
    x: Math.max(40, Math.round(position.x ?? 80)),
    y: Math.max(80, Math.round(position.y ?? 210))
  };
  if (node.operatorRef?.startsWith('resource:') || node.operatorRef === 'httpResource') {
    const resourceId = node.operatorRef?.startsWith('resource:')
      ? node.operatorRef.slice('resource:'.length)
      : String(node.config?.resourceId || 'loan-applicant-service.getProfile');
    const spec = OPERATOR_TYPES[`resource:${resourceId}`] || OPERATOR_TYPES.httpResource;
    const paramInputs = Object.fromEntries(Object.entries(node.inputs || {})
      .map(([key, binding]) => [key, expressionFromBinding(binding, draft)]));
    if (!Object.keys(paramInputs).length) {
      Object.assign(paramInputs, defaultResourceParamInputs(spec));
    }
    const inputName = Object.keys(paramInputs)[0] || defaultParamNameForOperator(spec);
    return {
      ...base,
      type: 'httpResource',
      paletteType: OPERATOR_TYPES[`resource:${resourceId}`] ? `resource:${resourceId}` : '',
      resourceId,
      paramName: inputName,
      applicantExpr: paramInputs[inputName] || `ctx.${inputName}`,
      paramInputs,
      config: { ...(node.config || {}) }
    };
  }
  if (node.operatorRef === 'bloge:decisionTable') {
    const inputConfig = node.config?.inputs || {};
    return {
      ...base,
      type: 'decisionTable',
      paletteType: '',
      hitPolicy: node.config?.hitPolicy || 'unique',
      scoreSource: expressionFromConfig(inputConfig.score, draft) || 'ctx.score',
      amountSource: expressionFromConfig(inputConfig.amount, draft) || 'ctx.amount',
      rules: decisionRulesFromDraft(node)
    };
  }
  if (node.operatorRef === 'bloge:transform') {
    const policyNode = policyNodeFromDraft(node, draft);
    const assignments = node.config?.assignments;
    const policyCleared = Boolean(assignments && typeof assignments === 'object' && !policyNode);
    return {
      ...base,
      type: 'transform',
      paletteType: '',
      policyNode: policyNode || (policyCleared ? '' : firstDecisionTableIdFromNodes(draft.nodes || [])),
      policyNodeCleared: policyCleared
    };
  }
  return {
    ...base,
    type: 'customOperator',
    paletteType: node.operatorRef,
    config: { ...(node.config || {}) },
    customInputs: Object.fromEntries(Object.entries(node.inputs || {})
      .map(([key, binding]) => [key, expressionFromBinding(binding, draft)])),
    customInputPorts: Object.fromEntries(Object.entries(node.inputs || {})
      .map(([key, binding]) => [key, binding.targetPort || inputPortForInputPath(specForNode({
        type: 'customOperator',
        paletteType: node.operatorRef
      }), bindingTargetPathForKey(key, binding))])),
    customInputPaths: Object.fromEntries(Object.entries(node.inputs || {})
      .map(([key, binding]) => [key, bindingTargetPathForKey(key, binding)]))
  };
}

function expressionFromConfig(value, draft) {
  if (value && typeof value === 'object' && value.kind) {
    return expressionFromBinding(value, draft);
  }
  return String(value || '');
}

function expressionFromBinding(binding, draft) {
  if (!binding) {
    return '';
  }
  if (binding.kind === 'contextPath') {
    return binding.path ? `ctx${dslReferenceSuffixForSchemaPath(binding.path)}` : 'ctx';
  }
  if (binding.kind === 'nodePath') {
    const source = (draft.nodes || []).find((node) => node.id === binding.nodeId);
    const sourcePort = binding.sourcePort || (source?.operatorRef?.startsWith('resource:') ? 'payload' : 'output');
    const payloadSegment = sourcePort && sourcePort !== 'output' ? `.${sourcePort}` : '';
    const pathSegment = dslReferenceSuffixForSchemaPath(binding.path);
    return `${binding.nodeId}.output${payloadSegment}${pathSegment}`;
  }
  if (binding.kind === 'expression') {
    return binding.expr || '{}';
  }
  if (binding.kind === 'constant') {
    return JSON.stringify(binding.value);
  }
  if (binding.kind === 'objectTemplate') {
    const fields = Object.entries(binding.fields || {})
      .map(([key, nested]) => `${key}: ${expressionFromBinding(nested, draft)}`)
      .join(', ');
    return `{ ${fields} }`;
  }
  return JSON.stringify(binding.value ?? null);
}

function decisionRulesFromDraft(node) {
  const rules = Array.isArray(node.config?.rules) ? node.config.rules : [];
  if (!rules.length) {
    return defaultDecisionRules();
  }
  return rules.map((rule, index) => ({
    id: rule.id || `R${index + 1}`,
    score: rule.otherwise ? 'otherwise' : String(rule.conditions?.score || 'score >= 700'),
    amount: rule.otherwise ? 'otherwise' : String(rule.conditions?.amount || 'amount <= 300000'),
    decision: String(rule.output?.decision || 'matched'),
    rate: Number(rule.output?.rate || 0),
    maxTerm: Number(rule.output?.maxTerm || 0),
    reviewLane: String(rule.output?.reviewLane || ''),
    otherwise: Boolean(rule.otherwise)
  }));
}

function policyNodeFromDraft(node, draft) {
  const assignment = node.config?.assignments?.policy;
  if (typeof assignment === 'string') {
    const match = assignment.match(/^([A-Za-z_][A-Za-z0-9_]*)\.output/);
    if (match) {
      return match[1];
    }
  }
  const edge = (draft.edges || []).find((item) =>
    canonicalEdgeKind(item.kind) === 'data' && item.target?.nodeId === node.id
  );
  return edge?.source?.nodeId || '';
}

function firstDecisionTableIdFromNodes(nodes) {
  return nodes.find((node) => node.operatorRef === 'bloge:decisionTable')?.id || 'loanPolicy';
}

function builderNodeToDraftNode(node, builder) {
  if (node.type === 'httpResource') {
    const paramInputs = resourceParamInputs(node, specForNode(node));
    const operatorRef = node.paletteType?.startsWith('resource:') && node.paletteType.slice('resource:'.length) === node.resourceId
      ? node.paletteType
      : `resource:${node.resourceId}`;
    return {
      id: node.id,
      operatorRef,
      label: labelForNode(node),
      inputs: Object.fromEntries(nonBlankInputEntries(paramInputs)
        .map(([key, expression]) => [key, bindingFromExpression(expression, {
          targetPort: specForNode(node).inputPort || 'params',
          builder
        })])),
      config: { timeout: '3s', retryAttempts: 1, ...(node.config || {}) },
      position: { x: node.x, y: node.y }
    };
  }
  if (node.type === 'decisionTable') {
    return {
      id: node.id,
      operatorRef: 'bloge:decisionTable',
      label: labelForNode(node),
      inputs: {},
      config: {
        inputs: {
          score: node.scoreSource,
          amount: node.amountSource
        },
        hitPolicy: node.hitPolicy || 'unique',
        outputType: '{ decision: String, rate: Decimal, maxTerm: Int, reviewLane: String, ruleId: String }',
        rules: node.rules.map((rule) => ({
          id: rule.id,
          otherwise: Boolean(rule.otherwise),
          conditions: rule.otherwise ? {} : { score: rule.score, amount: rule.amount },
          output: {
            decision: rule.decision,
            rate: Number(rule.rate),
            maxTerm: Number(rule.maxTerm),
            reviewLane: rule.reviewLane,
            ruleId: rule.id
          }
        }))
      },
      position: { x: node.x, y: node.y }
    };
  }
  if (node.type === 'customOperator') {
    return {
      id: node.id,
      operatorRef: node.paletteType,
      label: labelForNode(node),
      inputs: Object.fromEntries(nonBlankInputEntries(node.customInputs || {})
              .map(([key, expression]) => [key, bindingFromExpression(expression, {
                targetPort: customInputPortForKey(node, specForNode(node), key),
                targetPath: customInputPathForKey(node, key),
                builder
              })])),
      config: { ...(node.config || {}) },
      position: { x: node.x, y: node.y }
    };
  }
  const decisionNode = builder.nodes.find((item) => item.id === node.policyNode)
    || (node.policyNodeCleared ? null : builder.nodes.find((item) => item.type === 'decisionTable'));
  const resourceNode = builder.nodes.find((item) => item.type === 'httpResource');
  const previous = orderedBuilderNodes(builder).filter((item) => item.id !== node.id).at(-1);
  const assignments = node.policyNodeCleared
    ? {
        result: '{}'
      }
    : decisionNode
    ? {
        applicant: resourceNode ? `${resourceNode.id}.output.payload` : `{ score: ${decisionNode.scoreSource}, segment: ctx.segment }`,
        requestedAmount: decisionNode.amountSource,
        policy: `${decisionNode.id}.output`
      }
    : {
        result: previous ? `${previous.id}.output` : '{}'
      };
  return {
    id: node.id,
    operatorRef: 'bloge:transform',
    label: labelForNode(node),
    inputs: {},
    config: { assignments },
    position: { x: node.x, y: node.y }
  };
}

function commonExecutionConfigToDsl(config = {}) {
  const lines = [];
  const timeout = expressionFromConfig(config.timeout);
  if (timeout) {
    lines.push(`    timeout = ${timeout}`);
  }
  const retryAttempts = expressionFromConfig(config.retryAttempts);
  if (retryAttempts) {
    lines.push(`    retry = { attempts: ${retryAttempts}, backoff: 200ms }`);
  }
  return lines.join('\n');
}

function bindingFromExpression(expression, options = {}) {
  const value = String(expression || '').trim();
  const withTargetPort = (binding) => {
    const metadata = {};
    if (options.targetPort) {
      metadata.targetPort = options.targetPort;
    }
    if (options.targetPath) {
      metadata.targetPath = options.targetPath;
    }
    return Object.keys(metadata).length ? { ...binding, ...metadata } : binding;
  };
  const source = connectionSourceFromExpression(value, options.builder || state.builder);
  if (source) {
    if (source.nodeId === CONTEXT_SOURCE_ID) {
      return withTargetPort({ kind: 'contextPath', path: source.path || '' });
    }
    return withTargetPort({
      kind: 'nodePath',
      nodeId: source.nodeId,
      sourcePort: source.port || 'output',
      path: source.path || ''
    });
  }
  return withTargetPort({ kind: 'expression', expr: value || '{}' });
}

function nonBlankInputEntries(inputs) {
  return Object.entries(inputs || {})
    .filter(([, expression]) => String(expression || '').trim());
}

function quote(value) {
  return `"${String(value ?? '').replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`;
}

function numberValue(value) {
  const number = Number(value);
  return Number.isFinite(number) ? String(number) : '0';
}

function labelForNode(node) {
  if (node.type === 'httpResource') {
    return specForNode(node).label || 'HTTP Resource';
  }
  if (node.type === 'decisionTable') {
    return 'Decision Table';
  }
  if (node.type === 'transform') {
    return 'Transform';
  }
  if (node.type === 'customOperator') {
    return specForNode(node).label || readableName(node.id);
  }
  return readableName(node.id);
}

function readableName(value) {
  if (!value) return '';
  return String(value)
    .replaceAll('_', ' ')
    .replaceAll('-', ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/^./, (char) => char.toUpperCase());
}

function baseIdForResource(resourceId) {
  const parts = String(resourceId || 'resource')
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean);
  if (!parts.length) {
    return 'resource';
  }
  return parts.map((part, index) => {
    const lower = part.charAt(0).toLowerCase() + part.slice(1);
    return index === 0 ? lower : lower.charAt(0).toUpperCase() + lower.slice(1);
  }).join('');
}

function fillTemplate(template, values) {
  return template.replace(/\{([^}]+)\}/g, (_, key) => encodeURIComponent(values[key] ?? ''));
}

function replacePlaceholders(value, values) {
  if (typeof value === 'string') {
    const exact = value.match(/^\{([^}]+)\}$/);
    if (exact) {
      return values[exact[1]] ?? '';
    }
    return value.replace(/\{([^}]+)\}/g, (_, key) => values[key] ?? '');
  }
  if (Array.isArray(value)) {
    return value.map((item) => replacePlaceholders(item, values));
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, replacePlaceholders(item, values)]));
  }
  return value;
}

function renderDiagram() {
  const svg = $('diagram');
  configureComposerDropTarget(svg);
  const nodes = state.layout?.nodes || [];
  const edges = state.layout?.edges || [];
  const executed = state.lastPayload && currentDecisionTable();
  const width = Math.max(760, ...nodes.map((node) => node.position.x + node.size.width + 80));
  const height = Math.max(520, ...nodes.map((node) => node.position.y + node.size.height + 80));
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.setAttribute('preserveAspectRatio', 'xMinYMin meet');
  svg.innerHTML = `
    <defs>
      <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto">
        <path d="M0,0 L0,6 L9,3 z" fill="#8b98a8"></path>
      </marker>
    </defs>
  `;
  const byId = Object.fromEntries(nodes.map((node) => [node.id, node]));
  for (const edge of edges) {
    const source = byId[edge.source];
    const target = byId[edge.target];
    if (!source || !target) continue;
    const sourceBuilder = state.builder.nodes.find((node) => node.id === edge.source);
    const targetBuilder = state.builder.nodes.find((node) => node.id === edge.target);
    const sourcePoint = sourceHandlePoint(source, sourceBuilder, edge);
    const targetPoint = targetHandlePoint(target, targetBuilder, edge);
    const x1 = sourcePoint.x;
    const y1 = sourcePoint.y;
    const x2 = targetPoint.x;
    const y2 = targetPoint.y;
    const mid = (x1 + x2) / 2;
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('class', `edge ${edge.kind === 'dependency' ? 'dependency' : ''} ${edge.kind === 'route' ? 'route' : ''} ${executed ? 'executed' : ''}`);
    path.setAttribute('marker-end', 'url(#arrow)');
    path.setAttribute('d', `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`);
    svg.appendChild(path);
    const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    label.setAttribute('class', 'edge-label');
    label.setAttribute('x', String(mid - 20));
    label.setAttribute('y', String((y1 + y2) / 2 - 6));
    label.textContent = edge.label || '';
    svg.appendChild(label);
  }
  if (state.connectionDrag) {
    const source = byId[state.connectionDrag.source.nodeId];
    const sourceBuilder = state.builder.nodes.find((node) => node.id === state.connectionDrag.source.nodeId);
    if (source) {
      const sourcePoint = sourceHandlePoint(source, sourceBuilder, state.connectionDrag.source);
      const targetPoint = state.connectionDrag.current || sourcePoint;
      const mid = (sourcePoint.x + targetPoint.x) / 2;
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('class', 'edge connection-preview');
      path.setAttribute('marker-end', 'url(#arrow)');
      path.setAttribute('d', `M ${sourcePoint.x} ${sourcePoint.y} C ${mid} ${sourcePoint.y}, ${mid} ${targetPoint.y}, ${targetPoint.x} ${targetPoint.y}`);
      svg.appendChild(path);
    }
  }
  for (const node of nodes) {
    const group = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    group.setAttribute('class', `node ${node.kind} ${state.selectedNodeId === node.id ? 'selected' : ''} ${executed ? 'executed' : ''}`);
    group.setAttribute('data-node-id', node.id);
    group.setAttribute('tabindex', '0');
    group.setAttribute('role', 'button');
    group.setAttribute('aria-label', `${node.label} node`);
    if (isComposerSelected()) {
      group.classList.add('draggable-node');
      group.addEventListener('pointerdown', (event) => startNodeDrag(event, node));
    }
    group.addEventListener('click', () => {
      if (state.suppressNodeClick) {
        state.suppressNodeClick = false;
        return;
      }
      state.selectedNodeId = node.id;
      if (isComposerSelected()) {
        state.builder.selectedId = node.id;
        renderSelectedOperatorEditor();
        renderNodeDetails(selectedBuilderNode() || node);
      } else {
        renderNodeDetails(node);
      }
      renderDiagram();
    });
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', String(node.position.x));
    rect.setAttribute('y', String(node.position.y));
    rect.setAttribute('width', String(node.size.width));
    rect.setAttribute('height', String(node.size.height));
    group.appendChild(rect);
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    title.setAttribute('class', 'node-title');
    title.setAttribute('x', String(node.position.x + 12));
    title.setAttribute('y', String(node.position.y + 28));
    title.textContent = node.label;
    group.appendChild(title);
    const meta = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    meta.setAttribute('class', 'node-meta');
    meta.setAttribute('x', String(node.position.x + 12));
    meta.setAttribute('y', String(node.position.y + 52));
    meta.textContent = node.operatorRef || node.kind;
    group.appendChild(meta);
    if (isComposerSelected()) {
      renderPortHandles(group, node);
    }
    svg.appendChild(group);
  }
}

function renderPortHandles(group, visualNode) {
  const builderNode = state.builder.nodes.find((node) => node.id === visualNode.id);
  if (!builderNode) return;
  const sourceHandles = sourceHandlesForNode(builderNode);
  const targetHandles = canvasTargetHandlesForNode(builderNode);

  sourceHandles.forEach((handle, index) => {
    const point = portPoint(visualNode, index, sourceHandles.length, 'source');
    const circle = createPortCircle(point, 'source', builderNode, handle);
    circle.addEventListener('pointerdown', (event) => startConnectionDrag(event, handle));
    group.appendChild(circle);
  });

  targetHandles.forEach((handle, index) => {
    const point = portPoint(visualNode, index, targetHandles.length, 'target');
    const circle = createPortCircle(point, 'target', builderNode, handle);
    circle.addEventListener('pointerdown', (event) => event.stopPropagation());
    if (state.connectionDrag) {
      const compatibility = connectionCompatibility(state.connectionDrag.source, handle);
      circle.classList.toggle('compatible', compatibility.ok);
      circle.classList.toggle('incompatible', !compatibility.ok);
    }
    group.appendChild(circle);
  });
}

function createPortCircle(point, role, node, handle) {
  const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
  circle.setAttribute('class', `port-handle ${role}`);
  circle.setAttribute('cx', String(point.x));
  circle.setAttribute('cy', String(point.y));
  circle.setAttribute('r', '6');
  circle.dataset.portRole = role;
  circle.dataset.nodeId = node.id;
  circle.dataset.port = handle.port;
  circle.dataset.path = handle.path || '';
  const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
  const type = handle.type ? `: ${handle.type}` : '';
  title.textContent = `${endpointLabel(handle)}${type}`;
  circle.appendChild(title);
  return circle;
}

function sourceHandlePoint(visualNode, builderNode, endpoint) {
  const handles = builderNode ? sourceHandlesForNode(builderNode) : [];
  const index = Math.max(0, handles.findIndex((handle) =>
    handle.port === (endpoint.sourcePort || endpoint.port || 'output')
      && (handle.path || '') === (endpoint.sourcePath || endpoint.path || '')
  ));
  return portPoint(visualNode, index, Math.max(1, handles.length), 'source');
}

function targetHandlePoint(visualNode, builderNode, endpoint) {
  const handles = builderNode ? canvasTargetHandlesForNode(builderNode) : [];
  const index = Math.max(0, handles.findIndex((handle) =>
    handle.port === (endpoint.targetPort || endpoint.port || 'inputs')
      && (handle.path || '') === (endpoint.targetPath || endpoint.path || '')
  ));
  return portPoint(visualNode, index, Math.max(1, handles.length), 'target');
}

function portPoint(visualNode, index, total, role) {
  const size = visualNode.size || NODE_SIZE;
  const x = role === 'source'
    ? visualNode.position.x + size.width + 2
    : visualNode.position.x - 2;
  const y = visualNode.position.y + size.height * ((index + 1) / (total + 1));
  return { x, y };
}

function sourceHandlesForNode(node) {
  const spec = specForNode(node);
  if (spec.lowering?.mode === 'branch') {
    return [{
      nodeId: node.id,
      port: 'route',
      path: '',
      kind: 'route',
      type: 'route'
    }];
  }
  if (node.type === 'decisionTable') {
    return ['decision', 'rate', 'maxTerm', 'reviewLane', 'ruleId'].map((path) => ({
      nodeId: node.id,
      port: spec.outputPort || 'output',
      path,
      type: path === 'rate' ? 'number' : (path === 'maxTerm' ? 'integer' : 'string')
    }));
  }
  return outputPortsForSpec(spec).flatMap((port) => {
    const portName = port.name || spec.outputPort || 'output';
    const root = {
      nodeId: node.id,
      port: portName,
      path: '',
      type: schemaType(port.schema?.schema),
      schema: port.schema?.schema || {}
    };
    const seenPaths = new Set();
    const fields = [
      ...dslSafeSchemaFieldDescriptors(port.schema),
      ...dynamicOutputFieldDescriptors(node, spec, portName, port.schema)
    ].filter((field) => {
      if (seenPaths.has(field.path)) {
        return false;
      }
      seenPaths.add(field.path);
      return true;
    });
    return [
      { ...root, dslPathSafe: true },
      ...fields.map((field) => ({
        nodeId: node.id,
        port: portName,
        path: field.path,
        type: schemaType(field.schema),
        schema: field.schema,
        dslPathSafe: true
      }))
    ];
  });
}

function dynamicOutputFieldDescriptors(node, spec, portName, portSchema) {
  if (node.type !== 'customOperator') {
    return [];
  }
  return Object.keys(node.customOutputPaths || {})
    .filter((key) => customOutputPortForKey(node, spec, key) === portName)
    .map((key) => {
      const path = customOutputPathForKey(node, key);
      return {
        path,
        schema: schemaAtPath(portSchema, path) || {},
        dslPathSafe: isSchemaPathDslSafe(portSchema, path)
      };
    })
    .filter((field) => field.path && field.dslPathSafe && schemaAtPath(portSchema, field.path));
}

function outputPathOptionsForNode(node) {
  if (!node) {
    return [];
  }
  const spec = specForNode(node);
  const outputPorts = outputPortsForSpec(spec);
  const options = [{
    value: '',
    label: 'Full output',
    type: outputPorts.length === 1 ? schemaType(outputPorts[0]?.schema?.schema) : ''
  }];
  if (outputPorts.length > 1) {
    for (const port of outputPorts) {
      options.push({
        value: port.name || spec.outputPort || 'output',
        label: `${port.name || spec.outputPort || 'output'} port`,
        type: schemaType(port.schema?.schema)
      });
    }
  }
  for (const handle of sourceHandlesForNode(node)) {
    const value = outputSelectionPathForHandle(node, handle);
    if (!value) {
      continue;
    }
    options.push({
      value,
      label: endpointLabel(handle),
      type: handle.type || schemaType(handle.schema)
    });
  }
  const seen = new Set();
  return options.filter((option) => {
    if (seen.has(option.value)) {
      return false;
    }
    seen.add(option.value);
    return true;
  });
}

function outputSelectionPathForHandle(node, handle) {
  const spec = specForNode(node);
  const outputPorts = outputPortsForSpec(spec);
  const port = handle.port || spec.outputPort || 'output';
  const path = handle.path || '';
  if (!path) {
    return outputPorts.length > 1 || port !== 'output' ? port : '';
  }
  return outputPorts.length > 1 ? `${port}.${path}` : path;
}

function targetHandlesForNode(node) {
  const spec = specForNode(node);
  if (node.type === 'httpResource') {
    return inputPortsForSpec(spec).flatMap((port) => {
      const portName = port.name || spec.inputPort || 'params';
      const fields = dslSafeSchemaFieldDescriptors(port.schema);
      const targets = fields.length
        ? fields
        : Object.keys(resourceParamInputs(node, spec)).map((path) => ({
          path,
          schema: schemaAtPath(port.schema, path) || {},
          required: requiredInputNamesForPort(port).includes(path),
          dslPathSafe: isDslPathSafe(path)
        })).filter((field) => field.dslPathSafe);
      return targets.map((field) => ({
        nodeId: node.id,
        port: portName,
        key: field.path,
        path: field.path,
        type: schemaType(field.schema),
        schema: field.schema,
        required: field.required,
        dslPathSafe: true
      }));
    });
  }
  if (node.type === 'decisionTable') {
    return [
      { nodeId: node.id, port: spec.inputPort || 'inputs', path: 'score', type: 'number', required: true },
      { nodeId: node.id, port: spec.inputPort || 'inputs', path: 'amount', type: 'number', required: true }
    ];
  }
  if (node.type === 'customOperator') {
    return inputPortsForSpec(spec).flatMap((port) => {
      const portName = port.name || spec.inputPort || 'inputs';
      const seenPaths = new Set();
      const targets = [
        ...dslSafeSchemaFieldDescriptors(port.schema),
        ...dynamicInputFieldDescriptors(node, spec, portName, port.schema)
      ].filter((field) => {
        if (seenPaths.has(field.path)) {
          return false;
        }
        seenPaths.add(field.path);
        return true;
      });
      return [
        {
          nodeId: node.id,
          port: portName,
          key: inputKeyForPortPath(spec, portName, ''),
          path: '',
          type: schemaType(port.schema?.schema),
          schema: port.schema?.schema || {},
          required: Boolean(port.required) && !targets.some((field) => field.required),
          dslPathSafe: true
        },
        ...targets.map((field) => ({
          nodeId: node.id,
          port: portName,
          key: field.key || inputKeyForPortPath(spec, portName, field.path),
          path: field.path,
          type: schemaType(field.schema),
          schema: field.schema,
          required: field.required,
          dslPathSafe: true
        }))
      ];
    });
  }
  return [{
    nodeId: node.id,
    port: spec.inputPort || 'inputs',
    path: 'input',
    type: ''
  }];
}

function dynamicInputFieldDescriptors(node, spec, portName, portSchema) {
  if (node.type !== 'customOperator') {
    return [];
  }
  return Object.keys(node.customInputPaths || node.customInputs || {})
    .filter((key) => customInputPortForKey(node, spec, key) === portName)
    .map((key) => {
      const path = customInputPathForKey(node, key);
      return {
        key,
        path,
        schema: schemaAtPath(portSchema, path) || {},
        required: requiredInputNamesForPort({ schema: portSchema }).includes(path),
        dslPathSafe: isSchemaPathDslSafe(portSchema, path)
      };
    })
    .filter((field) => field.path && field.dslPathSafe && schemaAtPath(portSchema, field.path));
}

function canvasTargetHandlesForNode(node) {
  return [
    ...targetHandlesForNode(node),
    ...configTargetsForNode(node),
    ...dependencyTargetsForNode(node),
    ...routeTargetsForNode(node)
  ];
}

function routeTargetsForNode(node) {
  if (!node) {
    return [];
  }
  return [{
    nodeId: node.id,
    port: 'route',
    path: '',
    kind: 'route',
    type: 'route',
    condition: 'otherwise'
  }];
}

function dependencyTargetsForNode(node) {
  if (!nodeSupportsDependencyTarget(node)) {
    return [];
  }
  return [{
    nodeId: node.id,
    port: 'dependency',
    path: '',
    kind: 'dependency',
    type: 'dependency'
  }];
}

function nodeSupportsDependencyTarget(node) {
  if (!node) {
    return false;
  }
  if (node.type === 'httpResource') {
    return true;
  }
  if (node.type !== 'customOperator') {
    return false;
  }
  const spec = specForNode(node);
  return spec.lowering?.mode !== 'transform';
}

function schemaType(schema) {
  const type = rawSchemaType(schema);
  const values = schemaEnumValues(schema);
  if (values.length) {
    return `enum<${values.map(String).join('|')}>`;
  }
  if (type === 'array') {
    const itemType = schemaType(schema?.items);
    const label = itemType ? `array<${itemType}>` : 'array';
    return schemaAllowsNull(schema) ? `${label}|null` : label;
  }
  return type ? `${String(type)}${schemaAllowsNull(schema) && type !== 'null' ? '|null' : ''}` : '';
}

function sourceCandidatesForTarget(target) {
  const candidates = contextSourceHandles().map((source) => ({
    source,
    compatibility: connectionCompatibility(source, target)
  }));
  for (const node of state.builder.nodes) {
    if (node.id === target.nodeId) {
      continue;
    }
    for (const source of sourceHandlesForNode(node)) {
      candidates.push({
        source,
        compatibility: connectionCompatibility(source, target)
      });
    }
  }
  return candidates;
}

function bindingStatusForTarget(node, target, expression) {
  const value = String(expression || '').trim();
  if (!value) {
    return target.required
      ? { level: 'error', message: 'Required input is not bound.' }
      : { level: 'info', message: 'Optional input is empty.' };
  }
  const source = connectionSourceFromExpression(value);
  if (!source) {
    const literalSchema = staticExpressionLiteralSchema(value);
    if (literalSchema) {
      const targetSchema = target.schema || (target.type ? { type: target.type } : {});
      const compatibilityIssue = schemaCompatibilityIssue(literalSchema, targetSchema);
      return compatibilityIssue
        ? {
          level: 'error',
          message: `Type mismatch: ${schemaType(literalSchema)} cannot feed ${schemaType(targetSchema)}. Reason: ${compatibilityIssue}.`
        }
        : { level: 'success', message: 'Literal expression matches target schema.' };
    }
    return { level: 'info', message: 'Manual expression; schema can be checked after it targets a port.' };
  }
  if (source.nodeId === CONTEXT_SOURCE_ID) {
    const compatibility = connectionCompatibility(source, target);
    if (!compatibility.ok) {
      return { level: 'error', message: compatibility.message };
    }
    return { level: 'success', message: `Bound to ${endpointLabel(source)}.` };
  }
  const sourceNode = state.builder.nodes.find((item) => item.id === source.nodeId);
  if (!sourceNode) {
    return { level: 'error', message: `Source node '${source.nodeId}' does not exist.` };
  }
  const sourceHandle = sourceHandlesForNode(sourceNode).find((handle) =>
    handle.port === source.port && (handle.path || '') === (source.path || '')
  );
  if (!sourceHandle) {
    return { level: 'error', message: `Source path '${source.path || source.port}' is not exposed.` };
  }
  const compatibility = connectionCompatibility(sourceHandle, target);
  if (!compatibility.ok) {
    return { level: 'error', message: compatibility.message };
  }
  return { level: 'success', message: `Bound to ${endpointLabel(sourceHandle)}.` };
}

function bindingSourceValue(source) {
  return encodeURIComponent(JSON.stringify({
    nodeId: source.nodeId,
    port: source.port,
    path: source.path || ''
  }));
}

function sourceFromBindingValue(value) {
  if (!value) {
    return null;
  }
  try {
    const parsed = JSON.parse(decodeURIComponent(value));
    if (parsed.nodeId === CONTEXT_SOURCE_ID) {
      return contextSourceForPath(parsed.path || '');
    }
    const node = state.builder.nodes.find((item) => item.id === parsed.nodeId);
    if (!node) {
      return parsed;
    }
    return sourceHandlesForNode(node).find((handle) =>
      handle.port === parsed.port && (handle.path || '') === (parsed.path || '')
    ) || parsed;
  } catch {
    return null;
  }
}

function endpointLabel(endpoint) {
  if (!endpoint) {
    return '';
  }
  if (endpoint.nodeId === CONTEXT_SOURCE_ID) {
    return endpoint.path ? `ctx.${endpoint.path}` : 'ctx';
  }
  return [
    endpoint.nodeId,
    endpoint.port || 'output',
    endpoint.path || ''
  ].filter(Boolean).join('.');
}

function schemaAtPath(schemaEnvelope, path) {
  if (!path) {
    return schemaEnvelope?.schema || { type: 'object' };
  }
  let current = schemaEnvelope?.schema || {};
  for (const segment of String(path).split('.')) {
    if (!segment) continue;
    if (rawSchemaType(current) === 'array') {
      const index = arrayIndexSegment(segment);
      if (index === null) {
        return null;
      }
      const itemSchema = arrayItemSchemaForIndex(current, index);
      if (!itemSchema) {
        return null;
      }
      current = itemSchema;
      continue;
    }
    const properties = current.properties || {};
    if (!Object.prototype.hasOwnProperty.call(properties, segment)) {
      if (!propertyNameAllowedBySchema(current, segment)) {
        return null;
      }
      const pattern = patternPropertySchema(current, segment);
      if (pattern) {
        current = pattern;
        continue;
      }
      const additional = additionalPropertySchema(current);
      if (!additional) {
        return null;
      }
      current = additional;
      continue;
    }
    current = properties[segment] || {};
  }
  return current;
}

function arrayIndexSegment(segment) {
  if (!/^(0|[1-9]\d*)$/.test(String(segment))) {
    return null;
  }
  return Number(segment);
}

function additionalPropertySchema(schema) {
  const residual = residualPropertiesPolicy(schema);
  if (residual === true) {
    return {};
  }
  return residual && typeof residual === 'object' && !Array.isArray(residual)
    ? residual
    : null;
}

function patternPropertySchema(schema, propertyName) {
  const matches = matchingPatternPropertySchemas(schema, propertyName);
  return matches.length === 1 ? matches[0] : null;
}

function propertyNameAllowedBySchema(schema, propertyName) {
  const propertyNameSchema = schemaPropertyNameSchema(schema);
  return !propertyNameSchema
    || schemaValueMatchesSchema(String(propertyName), effectivePropertyNameSchema(propertyNameSchema));
}

function currentGraphInputSchema(builder = state.builder) {
  if (builder?.inputSchema) {
    try {
      const schema = normalizeGraphInputSchemaEnvelope(builder.inputSchema);
      if (!graphInputSchemaStructuralDiagnostics(schema).length) {
        return schema;
      }
    } catch {
    }
  }
  try {
    return parseGraphInputSchemaText(state.graphInputSchemaText);
  } catch {
    return schemaEnvelopeFromContextText(state.customContextText);
  }
}

function defaultGraphInputSchema() {
  return schemaEnvelopeFromContextText(pretty(DEFAULT_COMPOSER_CONTEXT));
}

function syncGraphInputSchemaTextFromBuilder(options = {}) {
  const render = options.render !== false;
  state.graphInputSchemaText = pretty(currentGraphInputSchema(state.builder));
  state.graphInputSchemaMessage = null;
  state.graphInputSchemaDiagnostics = [];
  const textarea = $('graph-input-schema');
  if (textarea && textarea.value !== state.graphInputSchemaText) {
    textarea.value = state.graphInputSchemaText;
  }
  if (render) {
    renderGraphInputSchemaStatus();
    renderSelectedOperatorEditor();
    renderGraphOutputEditor();
    renderDiagram();
  }
}

function updateGraphInputSchemaFromText(text) {
  state.graphInputSchemaText = text;
  try {
    const schema = parseGraphInputSchemaText(text);
    state.builder.inputSchema = schema;
    state.graphInputSchemaMessage = {
      level: 'success',
      message: graphInputSchemaSummary(schema)
    };
    state.graphInputSchemaDiagnostics = [];
    renderGraphInputSchemaStatus();
    renderSelectedOperatorEditor();
    renderGraphOutputEditor();
    renderDiagram();
  } catch (error) {
    const diagnostics = normalizeDiagnostics(error.diagnostics);
    state.graphInputSchemaMessage = {
      level: 'error',
      message: diagnosticMessage(diagnostics, `Invalid schema: ${error.message}`)
    };
    state.graphInputSchemaDiagnostics = diagnostics;
    renderGraphInputSchemaStatus();
  }
}

function renderGraphInputSchemaStatus() {
  const target = $('graph-input-schema-status');
  if (!target) return;
  const message = state.graphInputSchemaMessage;
  target.hidden = !message;
  target.textContent = message?.message || '';
  target.className = `schema-status ${message?.level || 'info'}`;
  renderDiagnosticList($('graph-input-schema-diagnostics'), state.graphInputSchemaDiagnostics);
}

function graphInputSchemaSummary(schemaEnvelope) {
  const fields = schemaFieldDescriptors(schemaEnvelope);
  if (!fields.length) {
    return 'Graph input schema is valid; no named ctx fields are declared.';
  }
  const required = fields.filter((field) => field.required).length;
  return `Graph input schema is valid: ${fields.length} ctx fields, ${required} required.`;
}

function parseGraphInputSchemaText(text) {
  let value;
  try {
    value = JSON.parse(text || '{}');
  } catch (error) {
    throw new Error(error.message);
  }
  const schema = normalizeGraphInputSchemaEnvelope(value);
  const diagnostics = graphInputSchemaStructuralDiagnostics(schema);
  if (diagnostics.length) {
    const error = new Error(diagnosticMessage(diagnostics, 'Graph input schema is invalid.'));
    error.diagnostics = diagnostics;
    throw error;
  }
  return schema;
}

function normalizeGraphInputSchemaEnvelope(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('schema must be a JSON object.');
  }
  const schema = Object.prototype.hasOwnProperty.call(value, 'schema')
    ? value.schema
    : value;
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) {
    throw new Error('schema must contain an object schema.');
  }
  return {
    format: String(value.format || SUPPORTED_SCHEMA_FORMAT),
    version: String(value.version || SUPPORTED_SCHEMA_VERSION),
    schema: resolveLocalSchemaRefs(schema)
  };
}

function resolveLocalSchemaRefs(schema) {
  const root = deepCloneSchemaValue(schema);
  const resolved = resolveLocalSchemaRefValue(root, root, []);
  return isPlainObject(resolved) ? resolved : root;
}

function resolveLocalSchemaRefValue(value, root, stack) {
  if (Array.isArray(value)) {
    return value.map((item) => resolveLocalSchemaRefValue(item, root, stack));
  }
  if (!isPlainObject(value)) {
    return value;
  }
  const ref = expandableLocalSchemaRef(value);
  if (ref) {
    if (stack.includes(ref)) {
      return value;
    }
    const target = resolveSchemaJsonPointer(root, ref);
    if (!isPlainObject(target)) {
      return value;
    }
    stack.push(ref);
    const resolvedTarget = resolveLocalSchemaRefValue(deepCloneSchemaValue(target), root, stack);
    stack.pop();
    if (!isPlainObject(resolvedTarget)) {
      return value;
    }
    const merged = { ...resolvedTarget };
    for (const [key, item] of Object.entries(value)) {
      if (key !== '$ref') {
        merged[key] = deepCloneSchemaValue(item);
      }
    }
    return merged;
  }
  const resolved = {};
  for (const [key, item] of Object.entries(value)) {
    resolved[key] = resolveLocalSchemaRefValue(item, root, stack);
  }
  return flattenObjectAllOf(resolved);
}

function flattenObjectAllOf(schema) {
  if (!Array.isArray(schema?.allOf) || !schema.allOf.length) {
    return schema;
  }
  const fragments = [];
  for (const fragment of schema.allOf) {
    if (!isPlainObject(fragment) || !objectCompositionSchema(fragment)) {
      return schema;
    }
    if (Object.keys(fragment).some((key) => SCHEMA_DECLARATION_KEYS.has(key))) {
      return schema;
    }
    fragments.push(fragment);
  }

  const sibling = { ...schema };
  delete sibling.allOf;
  if (Object.keys(sibling).some((key) => !SCHEMA_ANNOTATION_KEYS.has(key) && !SCHEMA_DECLARATION_KEYS.has(key))) {
    if (!objectCompositionSchema(sibling)) {
      return schema;
    }
    fragments.push(sibling);
  }

  const merged = { type: 'object' };
  const properties = {};
  const required = [];
  const patternProperties = {};
  const dependentRequired = {};
  const dependentSchemas = {};
  let additionalProperties;
  let unevaluatedProperties;
  let propertyNames;
  let minProperties;
  let maxProperties;

  for (const fragment of fragments) {
    if (!mergeObjectKeyword(properties, fragment, 'properties')
      || !mergeObjectKeyword(patternProperties, fragment, 'patternProperties')
      || !mergeObjectKeyword(dependentSchemas, fragment, 'dependentSchemas')
      || !mergeDependentRequiredKeyword(dependentRequired, fragment)
      || !mergeRequiredKeyword(required, fragment)) {
      return schema;
    }

    additionalProperties = mergeResidualPolicy(additionalProperties, residualPolicy(fragment, 'additionalProperties'));
    unevaluatedProperties = mergeResidualPolicy(unevaluatedProperties, residualPolicy(fragment, 'unevaluatedProperties'));
    if (additionalProperties === UNSUPPORTED_ALL_OF_MERGE || unevaluatedProperties === UNSUPPORTED_ALL_OF_MERGE) {
      return schema;
    }

    if (Object.prototype.hasOwnProperty.call(fragment, 'propertyNames')) {
      if (!isPlainObject(fragment.propertyNames)) {
        return schema;
      }
      if (propertyNames !== undefined && !schemaValuesEqual(propertyNames, fragment.propertyNames)) {
        return schema;
      }
      propertyNames = deepCloneSchemaValue(fragment.propertyNames);
    }
    minProperties = maxOptionalNumber(minProperties, propertyBound(fragment, 'minProperties'));
    maxProperties = minOptionalNumber(maxProperties, propertyBound(fragment, 'maxProperties'));
    if (minProperties === UNSUPPORTED_ALL_OF_MERGE || maxProperties === UNSUPPORTED_ALL_OF_MERGE) {
      return schema;
    }
  }

  if (Object.keys(properties).length) merged.properties = properties;
  if (required.length) merged.required = required;
  if (Object.keys(patternProperties).length) merged.patternProperties = patternProperties;
  if (Object.keys(dependentRequired).length) merged.dependentRequired = dependentRequired;
  if (Object.keys(dependentSchemas).length) merged.dependentSchemas = dependentSchemas;
  if (additionalProperties !== undefined) merged.additionalProperties = additionalProperties;
  if (unevaluatedProperties !== undefined) merged.unevaluatedProperties = unevaluatedProperties;
  if (propertyNames !== undefined) merged.propertyNames = propertyNames;
  if (minProperties !== undefined) merged.minProperties = minProperties;
  if (maxProperties !== undefined) merged.maxProperties = maxProperties;
  for (const key of SCHEMA_ANNOTATION_KEYS) {
    if (Object.prototype.hasOwnProperty.call(sibling, key)) {
      merged[key] = deepCloneSchemaValue(sibling[key]);
    }
  }
  for (const key of SCHEMA_DECLARATION_KEYS) {
    if (Object.prototype.hasOwnProperty.call(sibling, key)) {
      merged[key] = deepCloneSchemaValue(sibling[key]);
    }
  }
  return merged;
}

function objectCompositionSchema(schema) {
  return schema?.type === 'object'
    || (schema?.type === undefined && ['properties', 'required', 'additionalProperties',
      'unevaluatedProperties', 'patternProperties', 'propertyNames', 'dependentRequired',
      'dependentSchemas', 'minProperties', 'maxProperties'].some((key) => Object.prototype.hasOwnProperty.call(schema, key)));
}

function mergeObjectMap(target, source) {
  for (const [key, value] of Object.entries(source)) {
    const copy = deepCloneSchemaValue(value);
    if (Object.prototype.hasOwnProperty.call(target, key) && !schemaValuesEqual(target[key], copy)) {
      return false;
    }
    target[key] = copy;
  }
  return true;
}

function mergeObjectKeyword(target, source, key) {
  if (!Object.prototype.hasOwnProperty.call(source, key)) {
    return true;
  }
  if (!isPlainObject(source[key])) {
    return false;
  }
  return mergeObjectMap(target, source[key]);
}

function mergeDependentRequiredKeyword(target, source) {
  if (!Object.prototype.hasOwnProperty.call(source, 'dependentRequired')) {
    return true;
  }
  if (!isPlainObject(source.dependentRequired)) {
    return false;
  }
  for (const [key, values] of Object.entries(source.dependentRequired)) {
    if (!Array.isArray(target[key])) {
      target[key] = [];
    }
    if (!mergeUniqueStrings(target[key], values)) {
      return false;
    }
  }
  return true;
}

function mergeRequiredKeyword(target, source) {
  return !Object.prototype.hasOwnProperty.call(source, 'required')
    || mergeUniqueStrings(target, source.required);
}

function mergeUniqueStrings(target, values) {
  if (!Array.isArray(values)) {
    return false;
  }
  const seen = new Set();
  for (const value of values) {
    if (typeof value !== 'string' || !value.trim() || seen.has(value)) {
      return false;
    }
    seen.add(value);
    if (!target.includes(value)) {
      target.push(value);
    }
  }
  return true;
}

const UNSUPPORTED_ALL_OF_MERGE = Symbol('unsupportedAllOfMerge');

function residualPolicy(source, key) {
  if (!Object.prototype.hasOwnProperty.call(source, key)) {
    return undefined;
  }
  if (source[key] === true || source[key] === false) {
    return source[key];
  }
  return UNSUPPORTED_ALL_OF_MERGE;
}

function mergeResidualPolicy(current, next) {
  if (next === undefined || next === true) {
    return current;
  }
  if (next === false) {
    return false;
  }
  return UNSUPPORTED_ALL_OF_MERGE;
}

function propertyBound(source, key) {
  if (!Object.prototype.hasOwnProperty.call(source, key)) {
    return undefined;
  }
  return Number.isInteger(source[key]) && source[key] >= 0
    ? source[key]
    : UNSUPPORTED_ALL_OF_MERGE;
}

function maxOptionalNumber(current, next) {
  if (next === undefined) {
    return current;
  }
  if (next === UNSUPPORTED_ALL_OF_MERGE) {
    return next;
  }
  return current === undefined ? next : Math.max(current, next);
}

function minOptionalNumber(current, next) {
  if (next === undefined) {
    return current;
  }
  if (next === UNSUPPORTED_ALL_OF_MERGE) {
    return next;
  }
  return current === undefined ? next : Math.min(current, next);
}

function expandableLocalSchemaRef(schema) {
  const ref = schema?.$ref;
  if (typeof ref !== 'string' || !ref.startsWith(LOCAL_SCHEMA_DEFS_REF_PREFIX)) {
    return '';
  }
  return Object.keys(schema).every((key) => SCHEMA_REF_ANNOTATION_KEYS.has(key)) ? ref : '';
}

function resolveSchemaJsonPointer(root, ref) {
  if (!ref.startsWith('#/')) {
    return undefined;
  }
  let current = root;
  for (const rawToken of ref.slice(2).split('/')) {
    const token = decodeJsonPointerToken(rawToken);
    if (Array.isArray(current)) {
      const index = arrayIndexSegment(token);
      if (index === null || index >= current.length) {
        return undefined;
      }
      current = current[index];
    } else if (isPlainObject(current)) {
      current = current[token];
    } else {
      return undefined;
    }
    if (current === undefined || current === null) {
      return undefined;
    }
  }
  return current;
}

function decodeJsonPointerToken(token) {
  return String(token).replace(/~1/g, '/').replace(/~0/g, '~');
}

function deepCloneSchemaValue(value) {
  if (Array.isArray(value)) {
    return value.map(deepCloneSchemaValue);
  }
  if (isPlainObject(value)) {
    const copy = {};
    for (const [key, item] of Object.entries(value)) {
      copy[key] = deepCloneSchemaValue(item);
    }
    return copy;
  }
  return value;
}

function isPlainObject(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function graphInputSchemaStructuralDiagnostics(schemaEnvelope) {
  const diagnostics = [];
  validateSchemaEnvelope(schemaEnvelope, diagnostics);
  validateSchemaStructure(schemaEnvelope?.schema || {}, 'schema', diagnostics);
  return diagnostics;
}

function validateSchemaEnvelope(schemaEnvelope, diagnostics) {
  if ((schemaEnvelope?.format || SUPPORTED_SCHEMA_FORMAT) !== SUPPORTED_SCHEMA_FORMAT) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.formatUnsupported',
      `Unsupported schema format '${schemaEnvelope?.format}'; visual authoring supports '${SUPPORTED_SCHEMA_FORMAT}'.`,
      'format'
    ));
  }
  if ((schemaEnvelope?.version || SUPPORTED_SCHEMA_VERSION) !== SUPPORTED_SCHEMA_VERSION) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.versionUnsupported',
      `Unsupported schema version '${schemaEnvelope?.version}'; visual authoring supports ${SUPPORTED_SCHEMA_VERSION}.`,
      'version'
    ));
  }
}

function validateSchemaStructure(schema, path, diagnostics) {
  validateUnsupportedSchemaKeywords(schema, path, diagnostics);
  const invalidTypeArray = validateSchemaTypeArray(schema, path, diagnostics);
  validateSchemaDefinitions(schema, path, diagnostics);
  const kind = rawSchemaType(schema);
  if (invalidTypeArray) {
    return;
  }
  if (!kind) {
    return;
  }
  if (!SUPPORTED_SCHEMA_KINDS.has(kind)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.unsupportedType',
      `Unsupported schema type/kind '${kind}'.`,
      `${path}/type`
    ));
    return;
  }
  validateSchemaEnum(schema, kind, path, diagnostics);
  validateSchemaConst(schema, kind, path, diagnostics);
  validateSchemaNumericBounds(schema, kind, path, diagnostics);
  validateSchemaNumericMultipleOf(schema, kind, path, diagnostics);
  validateSchemaStringLengthBounds(schema, kind, path, diagnostics);
  validateSchemaStringPattern(schema, kind, path, diagnostics);
  validateSchemaStringFormat(schema, kind, path, diagnostics);
  validateSchemaArrayItemBounds(schema, kind, path, diagnostics);
  validateSchemaArrayUniqueItems(schema, kind, path, diagnostics);
  validateSchemaArrayPrefixItems(schema, kind, path, diagnostics);
  validateSchemaArrayContains(schema, kind, path, diagnostics);
  validateSchemaObjectPropertyBounds(schema, kind, path, diagnostics);
  validateSchemaObjectPatternProperties(schema, kind, path, diagnostics);
  validateSchemaObjectPropertyNames(schema, kind, path, diagnostics);
  validateSchemaObjectDependentRequired(schema, kind, path, diagnostics);
  validateSchemaObjectDependentSchemas(schema, kind, path, diagnostics);
  validateSchemaUnevaluatedProperties(schema, kind, path, diagnostics);
  if (kind === 'object') {
    const properties = validatedSchemaObjectProperties(schema, path, diagnostics);
    validateSchemaAdditionalProperties(schema, path, diagnostics);
    for (const required of validatedSchemaRequiredNames(schema, path, diagnostics)) {
      if (!Object.prototype.hasOwnProperty.call(properties, required)) {
        diagnostics.push(graphInputSchemaDiagnostic(
          'visual.schema.requiredUnknown',
          `Required property '${required}' is not declared in properties.`,
          `${path}/required`
        ));
      }
    }
    for (const [name, childSchema] of Object.entries(properties)) {
      if (!isDslFieldName(name)) {
        diagnostics.push(graphInputSchemaDiagnostic(
          'visual.inputSchema.dslField.invalid',
          `Graph inputSchema property '${name}' cannot be rendered as a BLOGE DSL path segment.`,
          `${path}/properties/${name}`
        ));
      }
      if (!childSchema || typeof childSchema !== 'object' || Array.isArray(childSchema)) {
        diagnostics.push(graphInputSchemaDiagnostic(
          'visual.schema.propertyInvalid',
          `Property '${name}' must be a schema object.`,
          `${path}/properties/${name}`
        ));
        continue;
      }
      validateSchemaStructure(childSchema, `${path}/properties/${name}`, diagnostics);
    }
  } else if (kind === 'array') {
    const items = schema?.items;
    if (!items || typeof items !== 'object' || Array.isArray(items)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.arrayItemsMissing',
        'Array schema must declare an item schema.',
        `${path}/items`
      ));
      return;
    }
    validateSchemaStructure(items, `${path}/items`, diagnostics);
  } else if (kind === 'enum') {
    validateCustomSchemaEnumValues(schema, path, diagnostics);
  }
}

function validateSchemaTypeArray(schema, path, diagnostics) {
  const type = schema?.type;
  if (!Array.isArray(type)) {
    return false;
  }
  if (!type.length) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.typeArrayInvalid',
      'Schema type array must contain one supported type, optionally plus null.',
      `${path}/type`
    ));
    return true;
  }
  let invalid = false;
  let concreteTypes = 0;
  const seen = new Set();
  type.forEach((item, index) => {
    const target = `${path}/type/${index}`;
    if (typeof item !== 'string' || !item.trim()) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.typeArrayInvalid',
        'Schema type array entries must be non-blank strings.',
        target
      ));
      invalid = true;
      return;
    }
    if (seen.has(item)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.typeArrayDuplicate',
        `Schema type array entry '${item}' is duplicated.`,
        target
      ));
      invalid = true;
      return;
    }
    seen.add(item);
    if (!SUPPORTED_SCHEMA_KINDS.has(item)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.unsupportedType',
        `Unsupported schema type/kind '${item}'.`,
        target
      ));
      invalid = true;
      return;
    }
    if (item !== 'null') {
      concreteTypes += 1;
    }
  });
  if (concreteTypes > 1) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.typeUnionUnsupported',
      'Schema type arrays support one concrete type, optionally plus null.',
      `${path}/type`
    ));
    invalid = true;
  }
  return invalid;
}

function validateSchemaDefinitions(schema, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, '$defs')) {
    return;
  }
  const definitions = schema.$defs;
  if (!isPlainObject(definitions)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.defsInvalid',
      'Schema $defs must be an object whose values are schemas.',
      `${path}/$defs`
    ));
    return;
  }
  for (const [name, definition] of Object.entries(definitions)) {
    const target = `${path}/$defs/${name}`;
    if (!name.trim()) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.defsInvalid',
        'Schema $defs keys must be non-blank names.',
        target
      ));
      continue;
    }
    if (!isPlainObject(definition)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.defsInvalid',
        `Schema $defs entry '${name}' must be a schema object.`,
        target
      ));
      continue;
    }
    validateSchemaStructure(definition, target, diagnostics);
  }
}

function validateUnsupportedSchemaKeywords(schema, path, diagnostics) {
  for (const keyword of UNSUPPORTED_SCHEMA_REFERENCE_KEYWORDS) {
    if (Object.prototype.hasOwnProperty.call(schema || {}, keyword)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.refUnsupported',
        `Schema reference keyword '${keyword}' is not supported by visual authoring schemas.`,
        `${path}/${keyword}`
      ));
    }
  }
  for (const keyword of UNSUPPORTED_SCHEMA_COMPOSITION_KEYWORDS) {
    if (Object.prototype.hasOwnProperty.call(schema || {}, keyword)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.compositionUnsupported',
        `JSON Schema composition keyword '${keyword}' is not supported by visual authoring schemas.`,
        `${path}/${keyword}`
      ));
    }
  }
  for (const keyword of UNSUPPORTED_SCHEMA_CONSTRAINT_KEYWORDS) {
    if (Object.prototype.hasOwnProperty.call(schema || {}, keyword)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.constraintUnsupported',
        `JSON Schema constraint keyword '${keyword}' is not supported by visual authoring schemas.`,
        `${path}/${keyword}`
      ));
    }
  }
}

function graphInputSchemaDiagnostic(code, message, target) {
  return {
    level: 'ERROR',
    code,
    message,
    target: `/inputSchema/${target}`,
    line: -1,
    column: -1
  };
}

function validatedSchemaRequiredNames(schema, path, diagnostics) {
  const required = schema?.required;
  if (required === undefined) {
    return [];
  }
  if (!Array.isArray(required)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.requiredInvalid',
      'Object schema required must be an array of property names.',
      `${path}/required`
    ));
    return [];
  }
  const names = [];
  const seen = new Set();
  required.forEach((item, index) => {
    if (typeof item !== 'string' || !item.trim()) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.requiredInvalid',
        'Object schema required entries must be non-blank strings.',
        `${path}/required/${index}`
      ));
      return;
    }
    if (seen.has(item)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.requiredDuplicate',
        `Object schema required entry '${item}' is duplicated.`,
        `${path}/required/${index}`
      ));
      return;
    }
    seen.add(item);
    names.push(item);
  });
  return names;
}

function validatedSchemaObjectProperties(schema, path, diagnostics) {
  const properties = schema?.properties;
  if (properties === undefined) {
    return {};
  }
  if (!properties || typeof properties !== 'object' || Array.isArray(properties)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.propertiesInvalid',
      'Object schema properties must be an object whose values are schemas.',
      `${path}/properties`
    ));
    return {};
  }
  return properties;
}

function validateSchemaAdditionalProperties(schema, path, diagnostics) {
  const additional = schema?.additionalProperties;
  if (additional === undefined || typeof additional === 'boolean') {
    return;
  }
  if (additional && typeof additional === 'object' && !Array.isArray(additional)) {
    validateSchemaStructure(additional, `${path}/additionalProperties`, diagnostics);
    return;
  }
  diagnostics.push(graphInputSchemaDiagnostic(
    'visual.schema.additionalPropertiesInvalid',
    'Object schema additionalProperties must be a boolean or schema object.',
    `${path}/additionalProperties`
  ));
}

function validateSchemaUnevaluatedProperties(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'unevaluatedProperties')) {
    return;
  }
  if (kind !== 'object') {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.unevaluatedPropertiesConstraintTypeMismatch',
      'Object unevaluatedProperties constraints require schema type/kind object.',
      path
    ));
  }
  const unevaluated = schema?.unevaluatedProperties;
  if (typeof unevaluated === 'boolean') {
    return;
  }
  if (unevaluated && typeof unevaluated === 'object' && !Array.isArray(unevaluated)) {
    validateSchemaStructure(unevaluated, `${path}/unevaluatedProperties`, diagnostics);
    return;
  }
  diagnostics.push(graphInputSchemaDiagnostic(
    'visual.schema.unevaluatedPropertiesInvalid',
    'Object schema unevaluatedProperties must be a boolean or schema object.',
    `${path}/unevaluatedProperties`
  ));
}

function validateSchemaEnum(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'enum')) {
    return;
  }
  const values = schema.enum;
  if (!Array.isArray(values) || !values.length) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.enumInvalid',
      'Schema enum must be a non-empty array.',
      `${path}/enum`
    ));
    return;
  }
  validateSchemaEnumValues(values, `${path}/enum`, diagnostics);
  values.forEach((value, index) => {
    if (!schemaValueMatchesDeclaredType(value, schema, kind)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.enumTypeMismatch',
        `Enum value at index ${index} must match schema type '${kind}'.`,
        `${path}/enum/${index}`
      ));
    }
    if (numericType(kind) && !numericValueMatchesBounds(value, schema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.enumConstraintMismatch',
        `Enum value at index ${index} must satisfy numeric bounds.`,
        `${path}/enum/${index}`
      ));
    }
    if (numericType(kind) && !numericValueMatchesMultipleOf(value, schema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.enumConstraintMismatch',
        `Enum value at index ${index} must satisfy numeric multipleOf constraint.`,
        `${path}/enum/${index}`
      ));
    }
    if (stringType(kind) && !stringValueMatchesLengthBounds(value, schema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.enumConstraintMismatch',
        `Enum value at index ${index} must satisfy string length constraints.`,
        `${path}/enum/${index}`
      ));
    }
    if (stringType(kind) && !stringValueMatchesPattern(value, schema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.enumConstraintMismatch',
        `Enum value at index ${index} must satisfy string pattern constraint.`,
        `${path}/enum/${index}`
      ));
    }
    if (stringType(kind) && !stringValueMatchesFormat(value, schema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.enumConstraintMismatch',
        `Enum value at index ${index} must satisfy string format constraint.`,
        `${path}/enum/${index}`
      ));
    }
    if (arrayType(kind) && Array.isArray(value) && !arrayValueMatchesSchema(value, schema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.enumConstraintMismatch',
        `Enum value at index ${index} must satisfy array schema constraints.`,
        `${path}/enum/${index}`
      ));
    }
    if (kind === 'object'
        && value !== null
        && typeof value === 'object'
        && !Array.isArray(value)
        && !objectValueMatchesSchema(value, schema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.enumConstraintMismatch',
        `Enum value at index ${index} must satisfy object schema constraints.`,
        `${path}/enum/${index}`
      ));
    }
  });
}

function validateCustomSchemaEnumValues(schema, path, diagnostics) {
  const values = schema?.values;
  if (!Array.isArray(values) || !values.length) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.enumValuesMissing',
      'Enum schema must declare non-empty values.',
      `${path}/values`
    ));
    return;
  }
  validateSchemaEnumValues(values, `${path}/values`, diagnostics);
}

function validateSchemaConst(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'const')) {
    return;
  }
  const constValue = schema.const;
  if (!schemaValueMatchesDeclaredType(constValue, schema, kind)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constTypeMismatch',
      `Const value must match schema type/kind '${kind}'.`,
      `${path}/const`
    ));
  }
  if (Array.isArray(schema?.enum) && !schema.enum.some((value) => schemaValuesEqual(value, constValue))) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constEnumMismatch',
      `Const value must be one of enum ${valueDomainLabel(schema.enum)}.`,
      `${path}/const`
    ));
  }
  if (kind === 'enum' && Array.isArray(schema?.values)
      && !schema.values.some((value) => schemaValuesEqual(value, constValue))) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constEnumMismatch',
      `Const value must be one of enum values ${valueDomainLabel(schema.values)}.`,
      `${path}/const`
    ));
  }
  if (numericType(kind) && schemaValueMatchesType(constValue, kind)
      && !numericValueMatchesBounds(constValue, schema)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constConstraintMismatch',
      'Const value must satisfy numeric bounds.',
      `${path}/const`
    ));
  }
  if (numericType(kind) && schemaValueMatchesType(constValue, kind)
      && !numericValueMatchesMultipleOf(constValue, schema)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constConstraintMismatch',
      'Const value must satisfy numeric multipleOf constraint.',
      `${path}/const`
    ));
  }
  if (stringType(kind) && schemaValueMatchesType(constValue, kind)
      && !stringValueMatchesLengthBounds(constValue, schema)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constConstraintMismatch',
      'Const value must satisfy string length constraints.',
      `${path}/const`
    ));
  }
  if (stringType(kind) && schemaValueMatchesType(constValue, kind)
      && !stringValueMatchesPattern(constValue, schema)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constConstraintMismatch',
      'Const value must satisfy string pattern constraint.',
      `${path}/const`
    ));
  }
  if (stringType(kind) && schemaValueMatchesType(constValue, kind)
      && !stringValueMatchesFormat(constValue, schema)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constConstraintMismatch',
      'Const value must satisfy string format constraint.',
      `${path}/const`
    ));
  }
  if (arrayType(kind) && schemaValueMatchesType(constValue, kind)
      && !arrayValueMatchesSchema(constValue, schema)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constConstraintMismatch',
      'Const value must satisfy array schema constraints.',
      `${path}/const`
    ));
  }
  if (kind === 'object' && schemaValueMatchesType(constValue, kind)
      && !objectValueMatchesSchema(constValue, schema)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.constConstraintMismatch',
      'Const value must satisfy object schema constraints.',
      `${path}/const`
    ));
  }
}

function validateSchemaNumericBounds(schema, kind, path, diagnostics) {
  if (!schemaHasNumericBounds(schema)) {
    return;
  }
  const validNumericKind = numericType(kind);
  if (!validNumericKind) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.numericConstraintTypeMismatch',
      'Numeric bounds require schema type/kind integer, number, or decimal.',
      path
    ));
  }
  for (const keyword of ['minimum', 'maximum', 'exclusiveMinimum', 'exclusiveMaximum']) {
    if (!Object.prototype.hasOwnProperty.call(schema || {}, keyword)) {
      continue;
    }
    const value = schema[keyword];
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.numericConstraintInvalid',
        `Numeric constraint '${keyword}' must be a finite number.`,
        `${path}/${keyword}`
      ));
    }
  }
  if (!validNumericKind || !numericBoundariesValid(schema)) {
    return;
  }
  const lower = schemaLowerBound(schema);
  const upper = schemaUpperBound(schema);
  if (lower && upper && numericBoundsContradict(lower, upper)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.numericBoundsInvalid',
      `Numeric lower bound ${numericLowerLabel(lower)} is incompatible with upper bound ${numericUpperLabel(upper)}.`,
      path
    ));
  }
}

function validateSchemaNumericMultipleOf(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'multipleOf')) {
    return;
  }
  if (!numericType(kind)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.multipleOfConstraintTypeMismatch',
      'Numeric multipleOf constraints require schema type/kind integer, number, or decimal.',
      path
    ));
  }
  if (numericMultipleOfValue(schema.multipleOf) === null) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.multipleOfConstraintInvalid',
      'Numeric multipleOf constraint must be a finite number greater than zero.',
      `${path}/multipleOf`
    ));
  }
}

function validateSchemaStringLengthBounds(schema, kind, path, diagnostics) {
  if (!schemaHasStringLengthBounds(schema)) {
    return;
  }
  const validStringKind = stringType(kind);
  if (!validStringKind) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.stringLengthConstraintTypeMismatch',
      'String length constraints require schema type/kind string, duration, or datetime.',
      path
    ));
  }
  for (const keyword of ['minLength', 'maxLength']) {
    if (!Object.prototype.hasOwnProperty.call(schema || {}, keyword)) {
      continue;
    }
    if (stringLengthBoundaryValue(schema[keyword]) === null) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.stringLengthConstraintInvalid',
        `String length constraint '${keyword}' must be a non-negative integer.`,
        `${path}/${keyword}`
      ));
    }
  }
  if (!validStringKind || !stringLengthBoundariesValid(schema)) {
    return;
  }
  const minimum = schemaMinLength(schema);
  const maximum = schemaMaxLength(schema);
  if (minimum !== null && maximum !== null && minimum > maximum) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.stringLengthBoundsInvalid',
      `String minLength ${minimum} is greater than maxLength ${maximum}.`,
      path
    ));
  }
}

function validateSchemaStringPattern(schema, kind, path, diagnostics) {
  if (!schemaHasStringPattern(schema)) {
    return;
  }
  if (!stringType(kind)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.patternConstraintTypeMismatch',
      'String pattern constraints require schema type/kind string, duration, or datetime.',
      path
    ));
  }
  if (typeof schema.pattern !== 'string') {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.patternConstraintInvalid',
      'String pattern constraint must be a string.',
      `${path}/pattern`
    ));
    return;
  }
  try {
    new RegExp(schema.pattern);
  } catch {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.patternConstraintInvalid',
      'String pattern constraint must be a valid regular expression.',
      `${path}/pattern`
    ));
  }
}

function validateSchemaStringFormat(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'format')) {
    return;
  }
  if (!stringType(kind)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.formatConstraintTypeMismatch',
      'String format constraints require schema type/kind string, duration, or datetime.',
      path
    ));
  }
  if (typeof schema.format !== 'string' || !SUPPORTED_SCHEMA_STRING_FORMATS.has(schema.format)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.formatConstraintInvalid',
      `String format constraint must be one of ${[...SUPPORTED_SCHEMA_STRING_FORMATS].join(', ')}.`,
      `${path}/format`
    ));
  }
}

function validateSchemaArrayItemBounds(schema, kind, path, diagnostics) {
  if (!schemaHasArrayItemBounds(schema)) {
    return;
  }
  const validArrayKind = arrayType(kind);
  if (!validArrayKind) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.arrayItemConstraintTypeMismatch',
      'Array item count constraints require schema type/kind array.',
      path
    ));
  }
  for (const keyword of ['minItems', 'maxItems']) {
    if (!Object.prototype.hasOwnProperty.call(schema || {}, keyword)) {
      continue;
    }
    if (arrayItemBoundaryValue(schema[keyword]) === null) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.arrayItemConstraintInvalid',
        `Array item count constraint '${keyword}' must be a non-negative integer.`,
        `${path}/${keyword}`
      ));
    }
  }
  if (!validArrayKind || !arrayItemBoundariesValid(schema)) {
    return;
  }
  const minimum = schemaMinItems(schema);
  const maximum = schemaMaxItems(schema);
  if (minimum !== null && maximum !== null && minimum > maximum) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.arrayItemBoundsInvalid',
      `Array minItems ${minimum} is greater than maxItems ${maximum}.`,
      path
    ));
  }
}

function validateSchemaArrayUniqueItems(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'uniqueItems')) {
    return;
  }
  if (!arrayType(kind)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.uniqueItemsConstraintTypeMismatch',
      'Array uniqueItems constraints require schema type/kind array.',
      path
    ));
  }
  if (typeof schema.uniqueItems !== 'boolean') {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.uniqueItemsConstraintInvalid',
      'Array uniqueItems constraint must be a boolean.',
      `${path}/uniqueItems`
    ));
  }
}

function validateSchemaArrayPrefixItems(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'prefixItems')) {
    return;
  }
  if (!arrayType(kind)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.prefixItemsConstraintTypeMismatch',
      'Array prefixItems constraints require schema type/kind array.',
      path
    ));
  }
  const prefixItems = schema?.prefixItems;
  if (!Array.isArray(prefixItems)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.prefixItemsInvalid',
      'Array schema prefixItems must be an array of schema objects.',
      `${path}/prefixItems`
    ));
    return;
  }
  prefixItems.forEach((itemSchema, index) => {
    const itemPath = `${path}/prefixItems/${index}`;
    if (!itemSchema || typeof itemSchema !== 'object' || Array.isArray(itemSchema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.prefixItemsInvalid',
        `Array schema prefixItems entry ${index} must be a schema object.`,
        itemPath
      ));
      return;
    }
    validateSchemaStructure(itemSchema, itemPath, diagnostics);
  });
}

function validateSchemaArrayContains(schema, kind, path, diagnostics) {
  if (!schemaHasArrayContains(schema)) {
    return;
  }
  const validArrayKind = arrayType(kind);
  if (!validArrayKind) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.containsConstraintTypeMismatch',
      'Array contains constraints require schema type/kind array.',
      path
    ));
  }
  const contains = schema?.contains;
  if (!contains || typeof contains !== 'object' || Array.isArray(contains)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.containsConstraintInvalid',
      'Array contains constraint must be a schema object.',
      `${path}/contains`
    ));
  } else {
    validateSchemaStructure(contains, `${path}/contains`, diagnostics);
  }
  for (const keyword of ['minContains', 'maxContains']) {
    if (!Object.prototype.hasOwnProperty.call(schema || {}, keyword)) {
      continue;
    }
    if (arrayItemBoundaryValue(schema[keyword]) === null) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.containsConstraintInvalid',
        `Array contains constraint '${keyword}' must be a non-negative integer.`,
        `${path}/${keyword}`
      ));
    }
  }
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'contains')
      && (Object.prototype.hasOwnProperty.call(schema || {}, 'minContains')
        || Object.prototype.hasOwnProperty.call(schema || {}, 'maxContains'))) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.containsConstraintInvalid',
      'Array minContains/maxContains constraints require a contains schema.',
      path
    ));
  }
  if (!validArrayKind || !contains || typeof contains !== 'object' || Array.isArray(contains)
      || !arrayContainsBoundariesValid(schema)) {
    return;
  }
  const minimum = schemaMinContains(schema);
  const maximum = schemaMaxContains(schema);
  if (minimum !== null && maximum !== null && minimum > maximum) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.containsBoundsInvalid',
      `Array minContains ${minimum} is greater than maxContains ${maximum}.`,
      path
    ));
  }
}

function validateSchemaObjectPropertyBounds(schema, kind, path, diagnostics) {
  if (!schemaHasObjectPropertyBounds(schema)) {
    return;
  }
  const validObjectKind = kind === 'object';
  if (!validObjectKind) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.objectPropertyConstraintTypeMismatch',
      'Object property count constraints require schema type/kind object.',
      path
    ));
  }
  for (const keyword of ['minProperties', 'maxProperties']) {
    if (!Object.prototype.hasOwnProperty.call(schema || {}, keyword)) {
      continue;
    }
    if (objectPropertyBoundaryValue(schema[keyword]) === null) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.objectPropertyConstraintInvalid',
        `Object property count constraint '${keyword}' must be a non-negative integer.`,
        `${path}/${keyword}`
      ));
    }
  }
  if (!validObjectKind || !objectPropertyBoundariesValid(schema)) {
    return;
  }
  const minimum = explicitSchemaMinProperties(schema);
  const maximum = explicitSchemaMaxProperties(schema);
  if (minimum !== null && maximum !== null && minimum > maximum) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.objectPropertyBoundsInvalid',
      `Object minProperties ${minimum} is greater than maxProperties ${maximum}.`,
      path
    ));
  }
}

function validateSchemaObjectPatternProperties(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'patternProperties')) {
    return;
  }
  if (kind !== 'object') {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.patternPropertiesConstraintTypeMismatch',
      'Object patternProperties constraints require schema type/kind object.',
      path
    ));
  }
  const patternProperties = schemaPatternProperties(schema);
  if (!patternProperties) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.patternPropertiesInvalid',
      'Object schema patternProperties must be an object whose values are schemas.',
      `${path}/patternProperties`
    ));
    return;
  }
  for (const [pattern, childSchema] of Object.entries(patternProperties)) {
    try {
      new RegExp(pattern);
    } catch {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.patternPropertiesPatternInvalid',
        `Object patternProperties key '${pattern}' must be a valid regular expression.`,
        `${path}/patternProperties/${pattern}`
      ));
    }
    if (!childSchema || typeof childSchema !== 'object' || Array.isArray(childSchema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.patternPropertiesInvalid',
        `Object patternProperties entry '${pattern}' must be a schema object.`,
        `${path}/patternProperties/${pattern}`
      ));
      continue;
    }
    validateSchemaStructure(childSchema, `${path}/patternProperties/${pattern}`, diagnostics);
  }
}

function validateSchemaObjectPropertyNames(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'propertyNames')) {
    return;
  }
  if (kind !== 'object') {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.propertyNamesConstraintTypeMismatch',
      'Object propertyNames constraints require schema type/kind object.',
      path
    ));
  }
  const propertyNameSchema = schemaPropertyNameSchema(schema);
  if (!propertyNameSchema) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.propertyNamesConstraintInvalid',
      'Object propertyNames constraint must be a schema object.',
      `${path}/propertyNames`
    ));
    return;
  }
  const propertyNameKind = rawSchemaType(propertyNameSchema);
  if (propertyNameKind && !stringType(propertyNameKind)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.propertyNamesConstraintTypeMismatch',
      'Object propertyNames constraint must use a string-compatible schema.',
      `${path}/propertyNames`
    ));
  }
  validateSchemaStructure(effectivePropertyNameSchema(propertyNameSchema), `${path}/propertyNames`, diagnostics);
}

function validateSchemaObjectDependentRequired(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'dependentRequired')) {
    return;
  }
  if (kind !== 'object') {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.dependentRequiredConstraintTypeMismatch',
      'Object dependentRequired constraints require schema type/kind object.',
      path
    ));
  }
  const dependentRequired = schema?.dependentRequired;
  if (!dependentRequired || typeof dependentRequired !== 'object' || Array.isArray(dependentRequired)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.dependentRequiredInvalid',
      'Object schema dependentRequired must be an object whose values are arrays of property names.',
      `${path}/dependentRequired`
    ));
    return;
  }
  const properties = schemaObjectProperties(schema);
  for (const [trigger, dependencies] of Object.entries(dependentRequired)) {
    const triggerPath = `${path}/dependentRequired/${trigger}`;
    if (!trigger.trim()) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.dependentRequiredInvalid',
        'Object schema dependentRequired keys must be non-blank property names.',
        triggerPath
      ));
    } else if (!Object.prototype.hasOwnProperty.call(properties, trigger)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.dependentRequiredUnknown',
        `Dependent-required trigger property '${trigger}' is not declared in properties.`,
        triggerPath
      ));
    }
    if (!Array.isArray(dependencies)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.dependentRequiredInvalid',
        `Object schema dependentRequired entry '${trigger}' must be an array of property names.`,
        triggerPath
      ));
      continue;
    }
    const seen = new Set();
    dependencies.forEach((dependency, index) => {
      const dependencyPath = `${triggerPath}/${index}`;
      if (typeof dependency !== 'string' || !dependency.trim()) {
        diagnostics.push(graphInputSchemaDiagnostic(
          'visual.schema.dependentRequiredInvalid',
          'Object schema dependentRequired entries must be non-blank strings.',
          dependencyPath
        ));
        return;
      }
      if (seen.has(dependency)) {
        diagnostics.push(graphInputSchemaDiagnostic(
          'visual.schema.dependentRequiredDuplicate',
          `Dependent-required property '${dependency}' is duplicated.`,
          dependencyPath
        ));
      }
      seen.add(dependency);
      if (!Object.prototype.hasOwnProperty.call(properties, dependency)) {
        diagnostics.push(graphInputSchemaDiagnostic(
          'visual.schema.dependentRequiredUnknown',
          `Dependent-required property '${dependency}' is not declared in properties.`,
          dependencyPath
        ));
      }
    });
  }
}

function validateSchemaObjectDependentSchemas(schema, kind, path, diagnostics) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'dependentSchemas')) {
    return;
  }
  if (kind !== 'object') {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.dependentSchemasConstraintTypeMismatch',
      'Object dependentSchemas constraints require schema type/kind object.',
      path
    ));
  }
  const dependentSchemas = schema?.dependentSchemas;
  if (!dependentSchemas || typeof dependentSchemas !== 'object' || Array.isArray(dependentSchemas)) {
    diagnostics.push(graphInputSchemaDiagnostic(
      'visual.schema.dependentSchemasInvalid',
      'Object schema dependentSchemas must be an object whose values are schema objects.',
      `${path}/dependentSchemas`
    ));
    return;
  }
  const properties = schemaObjectProperties(schema);
  for (const [trigger, dependentSchema] of Object.entries(dependentSchemas)) {
    const triggerPath = `${path}/dependentSchemas/${trigger}`;
    if (!trigger.trim()) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.dependentSchemasInvalid',
        'Object schema dependentSchemas keys must be non-blank property names.',
        triggerPath
      ));
    } else if (!Object.prototype.hasOwnProperty.call(properties, trigger)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.dependentSchemasUnknown',
        `Dependent-schema trigger property '${trigger}' is not declared in properties.`,
        triggerPath
      ));
    }
    if (!dependentSchema || typeof dependentSchema !== 'object' || Array.isArray(dependentSchema)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.dependentSchemasInvalid',
        `Object schema dependentSchemas entry '${trigger}' must be a schema object.`,
        triggerPath
      ));
      continue;
    }
    validateSchemaStructure(effectiveDependentObjectSchema(dependentSchema), triggerPath, diagnostics);
  }
}

function validateSchemaEnumValues(values, path, diagnostics) {
  const seen = new Set();
  values.forEach((value, index) => {
    const key = JSON.stringify(value);
    if (seen.has(key)) {
      diagnostics.push(graphInputSchemaDiagnostic(
        'visual.schema.enumDuplicate',
        `Enum value '${String(value)}' is duplicated.`,
        `${path}/${index}`
      ));
      return;
    }
    seen.add(key);
  });
}

function enumValueMatchesKind(value, kind) {
  if (kind === 'duration' || kind === 'datetime') {
    return typeof value === 'string';
  }
  return schemaValueMatchesType(value, kind);
}

function schemaValueMatchesDeclaredType(value, schema, kind) {
  return value === null && schemaAllowsNull(schema) || schemaValueMatchesType(value, kind);
}

function schemaObjectProperties(schema) {
  const properties = schema?.properties;
  return properties && typeof properties === 'object' && !Array.isArray(properties)
    ? properties
    : {};
}

function schemaPatternProperties(schema) {
  const patternProperties = schema?.patternProperties;
  return patternProperties && typeof patternProperties === 'object' && !Array.isArray(patternProperties)
    ? patternProperties
    : null;
}

function matchingPatternPropertySchemas(schema, propertyName) {
  const patternProperties = schemaPatternProperties(schema);
  if (!patternProperties) {
    return [];
  }
  const matches = [];
  for (const [pattern, childSchema] of Object.entries(patternProperties)) {
    if (patternMatches(pattern, propertyName) && childSchema && typeof childSchema === 'object' && !Array.isArray(childSchema)) {
      matches.push(childSchema);
    }
  }
  return matches;
}

function patternMatches(pattern, value) {
  try {
    return new RegExp(pattern).test(value);
  } catch {
    return false;
  }
}

function schemaPropertyNameSchema(schema) {
  const propertyNames = schema?.propertyNames;
  return propertyNames && typeof propertyNames === 'object' && !Array.isArray(propertyNames)
    ? propertyNames
    : null;
}

function effectivePropertyNameSchema(propertyNameSchema) {
  if (!propertyNameSchema) {
    return null;
  }
  return rawSchemaType(propertyNameSchema)
    ? propertyNameSchema
    : { ...propertyNameSchema, type: 'string' };
}

function schemaRequiredNames(schema) {
  return Array.isArray(schema?.required) ? schema.required.map(String) : [];
}

function schemaEnvelopeFromContextText(text) {
  try {
    const value = JSON.parse(text || '{}');
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      return {
        format: 'json-schema',
        version: '2020-12',
        schema: schemaFromValue(value)
      };
    }
  } catch {
    // Invalid JSON while editing should not make the draft impossible to save.
  }
  return {
    format: 'json-schema',
    version: '2020-12',
    schema: { type: 'object', additionalProperties: true }
  };
}

function schemaFromValue(value) {
  if (value === null) return { type: 'null' };
  if (Array.isArray(value)) {
    return {
      type: 'array',
      items: value.length ? schemaFromValue(value[0]) : { type: 'any' }
    };
  }
  if (typeof value === 'object') {
    const properties = {};
    for (const [key, item] of Object.entries(value)) {
      properties[key] = schemaFromValue(item);
    }
    return {
      type: 'object',
      properties,
      required: Object.keys(properties),
      additionalProperties: false
    };
  }
  if (typeof value === 'number') {
    return { type: Number.isInteger(value) ? 'integer' : 'number' };
  }
  if (typeof value === 'boolean') return { type: 'boolean' };
  return { type: 'string' };
}

function contextSourceHandles(builder = state.builder) {
  const inputSchema = currentGraphInputSchema(builder);
  const seen = new Set();
  const fieldHandles = [
    ...schemaFieldDescriptors(inputSchema),
    ...dynamicContextFieldDescriptors(inputSchema)
  ]
    .filter((field) => {
      if (!field.path || !field.dslPathSafe || seen.has(field.path)) {
        return false;
      }
      seen.add(field.path);
      return true;
    })
    .map((field) => ({
      nodeId: CONTEXT_SOURCE_ID,
      port: 'ctx',
      path: field.path,
      type: schemaType(field.schema),
      schema: field.schema,
      dslPathSafe: true
    }));
  return [
    {
      nodeId: CONTEXT_SOURCE_ID,
      port: 'ctx',
      path: '',
      type: schemaType(inputSchema.schema),
      schema: inputSchema.schema || {},
      dslPathSafe: true
    },
    ...fieldHandles
  ];
}

function dynamicContextFieldDescriptors(inputSchema) {
  let context;
  try {
    context = JSON.parse(state.customContextText || '{}');
  } catch {
    return [];
  }
  if (!context || typeof context !== 'object' || Array.isArray(context)) {
    return [];
  }
  const fields = [];
  collectDynamicContextFields(inputSchema, context, '', fields);
  return fields;
}

function collectDynamicContextFields(inputSchema, value, prefix, fields) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return;
  }
  for (const [key, item] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key;
    const schema = schemaAtPath(inputSchema, path);
    if (!schema) {
      continue;
    }
    fields.push({ path, schema, required: false, dslPathSafe: isSchemaPathDslSafe(inputSchema, path) });
    collectDynamicContextFields(inputSchema, item, path, fields);
  }
}

function contextSourceForPath(path, builder = state.builder) {
  const inputSchema = currentGraphInputSchema(builder);
  const schema = schemaAtPath(inputSchema, path);
  return {
    nodeId: CONTEXT_SOURCE_ID,
    port: 'ctx',
    path: path || '',
    type: schemaType(schema),
    schema,
    dslPathSafe: isSchemaPathDslSafe(inputSchema, path || '')
  };
}

function connectionAlreadyApplied(source, target, builder = state.builder) {
  const key = connectionKey(source, target, target?.kind === 'dependency' ? 'dependency' : (target?.kind === 'route' ? 'route' : 'data'));
  return Boolean(key) && builderEdges(builder, { includeFallback: false, includeConfig: true })
    .some((edge) => connectionKey(
      {
        nodeId: edge.source,
        port: edge.sourcePort || '',
        path: edge.sourcePath || ''
      },
      {
        nodeId: edge.target,
        port: edge.targetPort || '',
        path: edge.targetPath || '',
        condition: edge.condition || ''
      },
      edge.kind || 'data'
    ) === key);
}

function connectionKey(source, target, kind = 'data') {
  if (!source || !target || source.nodeId === CONTEXT_SOURCE_ID) {
    return '';
  }
  const edgeKind = canonicalEdgeKind(kind);
  if (edgeKind === 'dependency') {
    return [
      edgeKind,
      source.nodeId,
      target.nodeId
    ].map((item) => String(item || '').trim()).join(':');
  }
  if (edgeKind === 'route') {
    return [
      edgeKind,
      source.nodeId,
      target.nodeId,
      routeConditionKey(target.condition || 'otherwise')
    ].map((item) => String(item || '').trim()).join(':');
  }
  return [
    edgeKind,
    source.nodeId,
    source.port || '',
    source.path || '',
    target.nodeId,
    target.port || '',
    target.path || ''
  ].map((item) => String(item || '').trim()).join(':');
}

function routeConditionKey(condition) {
  const value = String(condition || '').trim();
  if (!value || value.toLowerCase() === 'otherwise') {
    return 'otherwise';
  }
  return routeConditionLiteralKey(routeConditionLiteralValue(value));
}

function routeConditionLiteralValue(condition) {
  if (condition === 'true') return true;
  if (condition === 'false') return false;
  if (condition === 'null') return null;
  if (/^[-+]?\d+$/.test(condition)) {
    const value = Number(condition);
    return Number.isFinite(value) ? value : condition;
  }
  if (/^[-+]?(?:\d+|\d+\.\d*|\d*\.\d+)(?:[eE][-+]?\d+)?$/.test(condition)) {
    const value = Number(condition);
    return Number.isFinite(value) ? value : condition;
  }
  const parsedString = parseStaticStringLiteral(condition);
  return parsedString.matched ? parsedString.value : condition;
}

function routeConditionLiteralKey(value) {
  if (value === null) return 'null';
  if (typeof value === 'string') return `string:${value}`;
  if (typeof value === 'boolean') return `boolean:${value}`;
  if (typeof value === 'number') return `number:${numberLabel(value)}`;
  return `${typeof value}:${String(value)}`;
}

function connectionCompatibility(source, target) {
  if (!source || !target) {
    return { ok: false, message: 'Connection endpoint is missing.' };
  }
  if (source.nodeId === target.nodeId) {
    return { ok: false, message: 'A node cannot connect to itself.' };
  }
  if (wouldCreateCycle(source.nodeId, target.nodeId)) {
    return { ok: false, message: 'This connection would create a cycle.' };
  }
  if (target.kind === 'dependency') {
    return source.nodeId === CONTEXT_SOURCE_ID
      ? { ok: false, message: 'Dependency edges must start from an operator node.' }
      : { ok: true, message: '' };
  }
  if (target.kind === 'route') {
    if (source.nodeId === CONTEXT_SOURCE_ID) {
      return { ok: false, message: 'Route edges must start from a branch operator node.' };
    }
    if (source.kind !== 'route') {
      return { ok: false, message: 'Route targets require a route source handle.' };
    }
    return { ok: true, message: '' };
  }
  if (source.dslPathSafe === false) {
    return { ok: false, message: `Source path '${source.path || source.port}' cannot be rendered as a BLOGE DSL path.` };
  }
  if (target.dslPathSafe === false) {
    return { ok: false, message: `Target path '${target.path || target.port}' cannot be rendered as a BLOGE DSL path.` };
  }
  const sourceNode = state.builder.nodes.find((node) => node.id === source.nodeId);
  const targetNode = state.builder.nodes.find((node) => node.id === target.nodeId);
  const sourceSchema = source.schema
    || (source.type
    ? { type: source.type }
    : (sourceNode ? schemaAtPath(schemaForPort(specForNode(sourceNode), 'source', source.port), source.path) : null));
  const targetSchema = target.schema
    || (target.type
    ? { type: target.type }
    : (targetNode ? schemaAtPath(schemaForPort(specForNode(targetNode), 'target', target.port), target.path) : null));
  if (sourceSchema === null) {
    return { ok: false, message: `Source path '${source.path}' is not exposed.` };
  }
  if (targetSchema === null && targetNode?.type !== 'transform') {
    return { ok: false, message: `Target path '${target.path}' is not accepted.` };
  }
  const sourceType = schemaType(sourceSchema);
  const targetType = schemaType(targetSchema);
  const compatibilityIssue = schemaCompatibilityIssue(sourceSchema, targetSchema);
  if (compatibilityIssue) {
    return {
      ok: false,
      message: `Type mismatch: ${sourceType} cannot feed ${targetType}. Reason: ${compatibilityIssue}.`
    };
  }
  return { ok: true, message: '' };
}

function schemasCompatible(sourceSchema, targetSchema) {
  return !schemaCompatibilityIssue(sourceSchema, targetSchema);
}

function staticExpressionLiteralSchema(expression) {
  const value = String(expression || '').trim();
  if (!value) {
    return null;
  }
  if (value === 'null') {
    return literalEnumSchema(null);
  }
  if (value === 'true' || value === 'false') {
    return literalEnumSchema(value === 'true');
  }
  const stringLiteral = parseStaticStringLiteral(value);
  if (stringLiteral.matched) {
    return literalEnumSchema(stringLiteral.value);
  }
  if (/^[-+]?\d+$/.test(value)) {
    return literalEnumSchema(Number(value));
  }
  if (/^[-+]?(?:\d+\.\d*|\d*\.\d+|\d+[eE][-+]?\d+|\d+\.\d*[eE][-+]?\d+|\d*\.\d+[eE][-+]?\d+)$/.test(value)) {
    return literalEnumSchema(Number(value));
  }
  return null;
}

function parseStaticStringLiteral(value) {
  if (value.length < 2) {
    return { matched: false, value: '' };
  }
  const quote = value[0];
  if ((quote !== '"' && quote !== "'") || value[value.length - 1] !== quote) {
    return { matched: false, value: '' };
  }
  if (quote === '"') {
    try {
      const parsed = JSON.parse(value);
      return typeof parsed === 'string'
        ? { matched: true, value: parsed }
        : { matched: false, value: '' };
    } catch {
      return { matched: false, value: '' };
    }
  }
  let result = '';
  let escaped = false;
  for (let i = 1; i < value.length - 1; i += 1) {
    const char = value[i];
    if (escaped) {
      result += unescapedStaticChar(char);
      escaped = false;
    } else if (char === '\\') {
      escaped = true;
    } else if (char === quote) {
      return { matched: false, value: '' };
    } else {
      result += char;
    }
  }
  return escaped ? { matched: false, value: '' } : { matched: true, value: result };
}

function unescapedStaticChar(value) {
  if (value === 'n') return '\n';
  if (value === 'r') return '\r';
  if (value === 't') return '\t';
  if (value === 'b') return '\b';
  if (value === 'f') return '\f';
  return value;
}

function literalEnumSchema(value) {
  return {
    type: 'enum',
    values: [value]
  };
}

function schemaCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  const sourceType = rawSchemaType(sourceSchema);
  const targetType = rawSchemaType(targetSchema);
  if (!sourceType || !targetType || sourceType === 'any' || targetType === 'any' || sourceType === 'opaque' || targetType === 'opaque') {
    return '';
  }
  if (schemaMayProduceNull(sourceSchema) && !schemaValueMatchesSchema(null, targetSchema)) {
    return reasonAt(path, `source may produce null but target ${schemaType(targetSchema)} does not allow null`);
  }
  if (sourceType === 'null') {
    return schemaValueMatchesSchema(null, targetSchema)
      ? ''
      : reasonAt(path, `source type null cannot feed target type ${schemaType(targetSchema)}`);
  }
  if (sourceType === 'array' && targetType === 'array') {
    return arrayPrefixItemsCompatibilityIssue(sourceSchema, targetSchema, path)
      || arrayItemsCompatibilityIssue(sourceSchema, targetSchema, path)
      || arrayItemBoundsCompatibilityIssue(sourceSchema, targetSchema, path)
      || arrayUniqueItemsCompatibilityIssue(sourceSchema, targetSchema, path)
      || arrayContainsCompatibilityIssue(sourceSchema, targetSchema, path);
  }
  if (sourceType === 'object' && targetType === 'object') {
    return objectSchemaCompatibilityIssue(sourceSchema, targetSchema, path);
  }
  const targetEnumValues = schemaEnumValues(targetSchema);
  if (targetEnumValues.length) {
    const sourceEnumValues = schemaEnumValues(sourceSchema);
    if (!sourceEnumValues.length) {
      return reasonAt(path, `target enum ${valueDomainLabel(targetEnumValues)} requires a finite source enum domain, but source is ${schemaType(sourceSchema)}`);
    }
    const outside = sourceEnumValues.filter((value) =>
      !targetEnumValues.some((targetValue) => schemaValuesEqual(targetValue, value))
    );
    return outside.length
      ? reasonAt(path, `source enum value(s) ${valueDomainLabel(outside)} are outside target enum ${valueDomainLabel(targetEnumValues)}`)
      : '';
  }
  const sourceEnumValues = schemaEnumValues(sourceSchema);
  if (sourceEnumValues.length) {
    const incompatible = sourceEnumValues.filter((value) => !schemaValueMatchesSchema(value, targetSchema));
    return incompatible.length
      ? reasonAt(path, `source enum value(s) ${valueDomainLabel(incompatible)} do not match target schema ${schemaType(targetSchema)}`)
      : '';
  }
  if (numericType(sourceType) && numericType(targetType)) {
    return numericBoundsCompatibilityIssue(sourceSchema, targetSchema, path)
      || numericMultipleOfCompatibilityIssue(sourceSchema, targetSchema, path);
  }
  if (stringType(sourceType) && stringType(targetType)) {
    return stringFormatCompatibilityIssue(sourceSchema, targetSchema, path)
      || stringPatternCompatibilityIssue(sourceSchema, targetSchema, path)
      || stringLengthCompatibilityIssue(sourceSchema, targetSchema, path);
  }
  return sourceType === targetType
    ? ''
    : reasonAt(path, `source type ${schemaType(sourceSchema)} cannot feed target type ${schemaType(targetSchema)}`);
}

function numericBoundsCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (!numericType(rawSchemaType(sourceSchema)) || !numericType(rawSchemaType(targetSchema))) {
    return '';
  }
  const targetLower = schemaLowerBound(targetSchema);
  if (targetLower) {
    const sourceLower = schemaLowerBound(sourceSchema);
    if (!sourceLower) {
      return reasonAt(path, `target requires ${numericLowerLabel(targetLower)} but source has no lower bound`);
    }
    if (!lowerBoundAtLeast(sourceLower, targetLower)) {
      return reasonAt(path, `source lower bound ${numericLowerLabel(sourceLower)} is weaker than target lower bound ${numericLowerLabel(targetLower)}`);
    }
  }
  const targetUpper = schemaUpperBound(targetSchema);
  if (targetUpper) {
    const sourceUpper = schemaUpperBound(sourceSchema);
    if (!sourceUpper) {
      return reasonAt(path, `target requires ${numericUpperLabel(targetUpper)} but source has no upper bound`);
    }
    if (!upperBoundAtMost(sourceUpper, targetUpper)) {
      return reasonAt(path, `source upper bound ${numericUpperLabel(sourceUpper)} is weaker than target upper bound ${numericUpperLabel(targetUpper)}`);
    }
  }
  return '';
}

function numericMultipleOfCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (!numericType(rawSchemaType(sourceSchema)) || !numericType(rawSchemaType(targetSchema))) {
    return '';
  }
  const targetMultipleOf = numericMultipleOfValue(targetSchema?.multipleOf);
  if (targetMultipleOf === null) {
    return '';
  }
  const sourceMultipleOf = numericMultipleOfValue(sourceSchema?.multipleOf);
  if (sourceMultipleOf === null) {
    return reasonAt(path, `target requires multipleOf ${numberLabel(targetMultipleOf)} but source has no multipleOf`);
  }
  if (!numericValueIsMultipleOf(sourceMultipleOf, targetMultipleOf)) {
    return reasonAt(path, `source multipleOf ${numberLabel(sourceMultipleOf)} is weaker than target multipleOf ${numberLabel(targetMultipleOf)}`);
  }
  return '';
}

function stringLengthCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (!stringType(rawSchemaType(sourceSchema)) || !stringType(rawSchemaType(targetSchema))) {
    return '';
  }
  const targetMinimum = schemaMinLength(targetSchema);
  if (targetMinimum !== null) {
    const sourceMinimum = schemaMinLength(sourceSchema);
    if (sourceMinimum === null) {
      return reasonAt(path, `target requires length >= ${targetMinimum} but source has no minLength`);
    }
    if (sourceMinimum < targetMinimum) {
      return reasonAt(path, `source minLength ${sourceMinimum} is weaker than target minLength ${targetMinimum}`);
    }
  }
  const targetMaximum = schemaMaxLength(targetSchema);
  if (targetMaximum !== null) {
    const sourceMaximum = schemaMaxLength(sourceSchema);
    if (sourceMaximum === null) {
      return reasonAt(path, `target requires length <= ${targetMaximum} but source has no maxLength`);
    }
    if (sourceMaximum > targetMaximum) {
      return reasonAt(path, `source maxLength ${sourceMaximum} is weaker than target maxLength ${targetMaximum}`);
    }
  }
  return '';
}

function stringPatternCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (!stringType(rawSchemaType(sourceSchema)) || !stringType(rawSchemaType(targetSchema))) {
    return '';
  }
  const targetPattern = schemaPatternValue(targetSchema);
  if (targetPattern === null) {
    return '';
  }
  const sourcePattern = schemaPatternValue(sourceSchema);
  if (sourcePattern === targetPattern) {
    return '';
  }
  if (sourcePattern === null) {
    return reasonAt(path, `target requires pattern '${targetPattern}' but source has no pattern`);
  }
  return reasonAt(path, `source pattern '${sourcePattern}' cannot be proven compatible with target pattern '${targetPattern}'`);
}

function stringFormatCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (!stringType(rawSchemaType(sourceSchema)) || !stringType(rawSchemaType(targetSchema))) {
    return '';
  }
  const targetFormat = schemaFormatValue(targetSchema);
  if (targetFormat === null) {
    return '';
  }
  const sourceFormat = schemaFormatValue(sourceSchema);
  if (sourceFormat === targetFormat) {
    return '';
  }
  if (sourceFormat === null) {
    return reasonAt(path, `target requires format '${targetFormat}' but source has no format`);
  }
  return reasonAt(path, `source format '${sourceFormat}' cannot feed target format '${targetFormat}'`);
}

function arrayItemBoundsCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (!arrayType(rawSchemaType(sourceSchema)) || !arrayType(rawSchemaType(targetSchema))) {
    return '';
  }
  const targetMinimum = schemaMinItems(targetSchema);
  if (targetMinimum !== null) {
    const sourceMinimum = schemaMinItems(sourceSchema);
    if (sourceMinimum === null) {
      return reasonAt(path, `target requires item count >= ${targetMinimum} but source has no minItems`);
    }
    if (sourceMinimum < targetMinimum) {
      return reasonAt(path, `source minItems ${sourceMinimum} is weaker than target minItems ${targetMinimum}`);
    }
  }
  const targetMaximum = schemaMaxItems(targetSchema);
  if (targetMaximum !== null) {
    const sourceMaximum = schemaMaxItems(sourceSchema);
    if (sourceMaximum === null) {
      return reasonAt(path, `target requires item count <= ${targetMaximum} but source has no maxItems`);
    }
    if (sourceMaximum > targetMaximum) {
      return reasonAt(path, `source maxItems ${sourceMaximum} is weaker than target maxItems ${targetMaximum}`);
    }
  }
  return '';
}

function arrayItemsCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  const targetItems = schemaItemsSchema(targetSchema);
  if (!targetItems) {
    return '';
  }
  const firstUniformIndex = Math.max(schemaPrefixItems(sourceSchema).length, schemaPrefixItems(targetSchema).length);
  const sourceMaximum = schemaMaxItems(sourceSchema);
  if (sourceMaximum !== null && sourceMaximum <= firstUniformIndex) {
    return '';
  }
  const sourceItems = schemaItemsSchema(sourceSchema);
  if (!sourceItems) {
    return reasonAt(path, 'target requires items but source does not constrain additional array items');
  }
  return schemaCompatibilityIssue(sourceItems, targetItems, appendCompatibilityPath(path, 'items'));
}

function arrayPrefixItemsCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (!arrayType(rawSchemaType(sourceSchema)) || !arrayType(rawSchemaType(targetSchema))) {
    return '';
  }
  const targetPrefixItems = schemaPrefixItems(targetSchema);
  const sourcePrefixItems = schemaPrefixItems(sourceSchema);
  if (!targetPrefixItems.length && sourcePrefixItems.length <= targetPrefixItems.length) {
    return '';
  }
  const sourceValues = schemaEnumValues(sourceSchema);
  if (sourceValues.length
      && sourceValues.every(Array.isArray)
      && sourceValues.every((value) => arrayValueMatchesSchema(value, targetSchema))) {
    return '';
  }
  const sourceMaximum = schemaMaxItems(sourceSchema);
  for (let index = 0; index < targetPrefixItems.length; index += 1) {
    if (sourceMaximum !== null && sourceMaximum <= index) {
      continue;
    }
    const sourceItem = sourcePrefixItems[index] || schemaItemsSchema(sourceSchema);
    if (!sourceItem) {
      return reasonAt(path, `target requires prefixItems[${index}] but source does not constrain that array item`);
    }
    const nested = schemaCompatibilityIssue(sourceItem, targetPrefixItems[index], appendCompatibilityPath(path, `prefixItems/${index}`));
    if (nested) {
      return nested;
    }
  }
  const targetItems = schemaItemsSchema(targetSchema);
  if (targetItems) {
    for (let index = targetPrefixItems.length; index < sourcePrefixItems.length; index += 1) {
      if (sourceMaximum !== null && sourceMaximum <= index) {
        continue;
      }
      const nested = schemaCompatibilityIssue(sourcePrefixItems[index], targetItems, appendCompatibilityPath(path, `prefixItems/${index}`));
      if (nested) {
        return nested;
      }
    }
  }
  return '';
}

function arrayUniqueItemsCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (!arrayType(rawSchemaType(sourceSchema)) || !arrayType(rawSchemaType(targetSchema)) || targetSchema?.uniqueItems !== true) {
    return '';
  }
  if (sourceSchema?.uniqueItems === true) {
    return '';
  }
  const sourceValues = schemaEnumValues(sourceSchema);
  if (sourceValues.length && sourceValues.every(Array.isArray) && sourceValues.every(arrayItemsUnique)) {
    return '';
  }
  return reasonAt(path, 'target requires uniqueItems=true but source does not guarantee uniqueness');
}

function arrayContainsCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (!arrayType(rawSchemaType(sourceSchema)) || !arrayType(rawSchemaType(targetSchema))) {
    return '';
  }
  const targetContains = schemaContainsSchema(targetSchema);
  if (!targetContains) {
    return '';
  }
  const targetMinimum = schemaMinContains(targetSchema);
  const targetMaximum = schemaMaxContains(targetSchema);
  if ((targetMinimum === null || targetMinimum === 0) && targetMaximum === null) {
    return '';
  }
  const sourceValues = schemaEnumValues(sourceSchema);
  if (sourceValues.length
      && sourceValues.every(Array.isArray)
      && sourceValues.every((value) => arrayValueMatchesSchema(value, targetSchema))) {
    return '';
  }
  const sourceContains = schemaContainsSchema(sourceSchema);
  if (!sourceContains) {
    return reasonAt(path, 'target requires contains but source does not guarantee matching array items');
  }
  const containsIssue = schemaCompatibilityIssue(sourceContains, targetContains, appendCompatibilityPath(path, 'contains'));
  if (containsIssue) {
    return containsIssue;
  }
  if (targetMinimum !== null) {
    const sourceMinimum = schemaMinContains(sourceSchema);
    if (sourceMinimum === null) {
      return reasonAt(path, `target requires contains count >= ${targetMinimum} but source has no minContains`);
    }
    if (sourceMinimum < targetMinimum) {
      return reasonAt(path, `source minContains ${sourceMinimum} is weaker than target minContains ${targetMinimum}`);
    }
  }
  if (targetMaximum !== null) {
    const sourceMaximum = schemaMaxContains(sourceSchema);
    if (sourceMaximum === null) {
      return reasonAt(path, `target requires contains count <= ${targetMaximum} but source has no maxContains`);
    }
    if (sourceMaximum > targetMaximum) {
      return reasonAt(path, `source maxContains ${sourceMaximum} is weaker than target maxContains ${targetMaximum}`);
    }
  }
  return '';
}

function objectSchemasCompatible(sourceSchema, targetSchema) {
  return !objectSchemaCompatibilityIssue(sourceSchema, targetSchema);
}

function objectSchemaCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  const sourceProperties = schemaObjectProperties(sourceSchema);
  const targetProperties = schemaObjectProperties(targetSchema);
  const sourceRequired = new Set(schemaRequiredNames(sourceSchema));
  for (const required of schemaRequiredNames(targetSchema)) {
    const childPath = appendCompatibilityPath(path, required);
    const sourceProperty = sourceProperties[required];
    const targetProperty = targetProperties[required];
    if (!sourceProperty) {
      return reasonAt(childPath, `source object does not declare required field '${required}'`);
    }
    if (!targetProperty) {
      return reasonAt(childPath, `target schema requires undeclared field '${required}'`);
    }
    if (!sourceRequired.has(required)) {
      return reasonAt(childPath, `source object does not guarantee required field '${required}'`);
    }
    const nested = schemaCompatibilityIssue(sourceProperty, targetProperty, childPath);
    if (nested) {
      return nested;
    }
  }
  const targetResidual = residualPropertiesPolicy(targetSchema);
  const targetResidualKeyword = residualPropertiesKeyword(targetSchema);
  for (const [propertyName, sourceProperty] of Object.entries(sourceProperties)) {
    const childPath = appendCompatibilityPath(path, propertyName);
    const targetProperty = targetProperties[propertyName];
    const targetPatternSchemas = matchingPatternPropertySchemas(targetSchema, propertyName);
    if (targetProperty) {
      const nested = schemaCompatibilityIssue(sourceProperty, targetProperty, childPath);
      if (nested) {
        return nested;
      }
    }
    for (const targetPatternSchema of targetPatternSchemas) {
      const nested = schemaCompatibilityIssue(sourceProperty, targetPatternSchema, childPath);
      if (nested) {
        return nested;
      }
    }
    if (targetProperty || targetPatternSchemas.length) {
      continue;
    } else if (targetResidual === false) {
      return reasonAt(childPath, `source object declares additional field '${propertyName}' but target ${targetResidualKeyword}=false`);
    } else if (targetResidual && typeof targetResidual === 'object' && !Array.isArray(targetResidual)) {
      const nested = schemaCompatibilityIssue(sourceProperty, targetResidual, childPath);
      if (nested) {
        return nested;
      }
    }
  }
  const sourceResidual = residualPropertiesPolicy(sourceSchema);
  if (targetResidual === false && sourceResidual !== false) {
    return reasonAt(path, `source object allows undeclared additional fields but target ${targetResidualKeyword}=false`);
  }
  if (targetResidual && typeof targetResidual === 'object' && !Array.isArray(targetResidual)) {
    if (sourceResidual === undefined || sourceResidual === true) {
      return reasonAt(path, `source object allows unconstrained additional fields but target ${targetResidualKeyword} requires ${schemaType(targetResidual)}`);
    }
    if (sourceResidual && typeof sourceResidual === 'object' && !Array.isArray(sourceResidual)) {
      const nested = schemaCompatibilityIssue(sourceResidual, targetResidual, appendCompatibilityPath(path, targetResidualKeyword));
      if (nested) {
        return nested;
      }
    }
  }
  return objectPatternPropertiesCompatibilityIssue(sourceSchema, targetSchema, path)
    || objectPropertyNamesCompatibilityIssue(sourceSchema, targetSchema, path)
    || objectDependentRequiredCompatibilityIssue(sourceSchema, targetSchema, path)
    || objectDependentSchemasCompatibilityIssue(sourceSchema, targetSchema, path)
    || objectPropertyBoundsCompatibilityIssue(sourceSchema, targetSchema, path);
}

function objectPatternPropertiesCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  const targetPatterns = schemaPatternProperties(targetSchema);
  if (!targetPatterns || !Object.keys(targetPatterns).length) {
    return '';
  }
  const sourceValues = schemaEnumValues(sourceSchema);
  if (sourceValues.length
      && sourceValues.every((value) => value !== null && typeof value === 'object' && !Array.isArray(value))
      && sourceValues.every((value) => objectValueMatchesSchema(value, targetSchema))) {
    return '';
  }
  const sourcePatterns = schemaPatternProperties(sourceSchema);
  if ((!sourcePatterns || !Object.keys(sourcePatterns).length) && residualPropertiesPolicy(sourceSchema) === false) {
    return '';
  }
  if (sourcePatterns && canonicalSchemaValueKey(sourcePatterns) === canonicalSchemaValueKey(targetPatterns)) {
    return '';
  }
  return reasonAt(path, 'target requires patternProperties but source does not guarantee matching dynamic fields');
}

function objectPropertyNamesCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  const targetPropertyNames = schemaPropertyNameSchema(targetSchema);
  if (!targetPropertyNames) {
    return '';
  }
  const sourceValues = schemaEnumValues(sourceSchema);
  if (sourceValues.length
      && sourceValues.every((value) => value !== null && typeof value === 'object' && !Array.isArray(value))
      && sourceValues.every((value) => objectValueMatchesPropertyNames(value, targetSchema))) {
    return '';
  }
  const targetEffective = effectivePropertyNameSchema(targetPropertyNames);
  if (residualPropertiesPolicy(sourceSchema) === false
      && Object.keys(schemaObjectProperties(sourceSchema)).every((name) => schemaValueMatchesSchema(name, targetEffective))) {
    return '';
  }
  const sourcePropertyNames = schemaPropertyNameSchema(sourceSchema);
  if (sourcePropertyNames
      && canonicalSchemaValueKey(effectivePropertyNameSchema(sourcePropertyNames)) === canonicalSchemaValueKey(targetEffective)) {
    return '';
  }
  return reasonAt(path, 'target requires propertyNames but source does not guarantee matching property names');
}

function objectDependentRequiredCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  const targetDependencies = schemaDependentRequired(targetSchema);
  if (!Object.keys(targetDependencies).length) {
    return '';
  }
  const sourceValues = schemaEnumValues(sourceSchema);
  if (sourceValues.length
      && sourceValues.every((value) => value !== null && typeof value === 'object' && !Array.isArray(value))
      && sourceValues.every((value) => objectValueMatchesSchema(value, targetSchema))) {
    return '';
  }
  const sourceRequired = new Set(schemaRequiredNames(sourceSchema));
  const sourceDependencies = schemaDependentRequired(sourceSchema);
  for (const [trigger, dependencies] of Object.entries(targetDependencies)) {
    if (sourceCannotContainProperty(sourceSchema, trigger)) {
      continue;
    }
    const sourceTriggerDependencies = sourceDependencies[trigger] || [];
    for (const dependency of dependencies) {
      if (!sourceRequired.has(dependency) && !sourceTriggerDependencies.includes(dependency)) {
        return reasonAt(path, `target requires dependentRequired '${trigger}' -> '${dependency}' but source does not guarantee the dependency`);
      }
    }
  }
  return '';
}

function objectDependentSchemasCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  const targetDependencies = schemaDependentSchemas(targetSchema);
  if (!Object.keys(targetDependencies).length) {
    return '';
  }
  const sourceValues = schemaEnumValues(sourceSchema);
  if (sourceValues.length
      && sourceValues.every((value) => value !== null && typeof value === 'object' && !Array.isArray(value))
      && sourceValues.every((value) => objectValueMatchesSchema(value, targetSchema))) {
    return '';
  }
  const sourceDependencies = schemaDependentSchemas(sourceSchema);
  for (const [trigger, targetDependentSchema] of Object.entries(targetDependencies)) {
    if (sourceCannotContainProperty(sourceSchema, trigger)) {
      continue;
    }
    const dependencyPath = appendCompatibilityPath(path, `dependentSchemas/${trigger}`);
    if (!schemaCompatibilityIssue(sourceSchema, targetDependentSchema, dependencyPath)) {
      continue;
    }
    const sourceDependentSchema = sourceDependencies[trigger];
    if (sourceDependentSchema
        && !schemaCompatibilityIssue(sourceDependentSchema, targetDependentSchema, dependencyPath)) {
      continue;
    }
    return reasonAt(path, `target requires dependentSchemas '${trigger}' but source does not guarantee the dependent schema`);
  }
  return '';
}

function objectPropertyBoundsCompatibilityIssue(sourceSchema, targetSchema, path = '') {
  if (rawSchemaType(sourceSchema) !== 'object' || rawSchemaType(targetSchema) !== 'object') {
    return '';
  }
  const targetMinimum = schemaMinProperties(targetSchema);
  if (targetMinimum !== null) {
    const sourceMinimum = schemaMinProperties(sourceSchema);
    if (sourceMinimum === null) {
      return reasonAt(path, `target requires property count >= ${targetMinimum} but source has no minProperties`);
    }
    if (sourceMinimum < targetMinimum) {
      return reasonAt(path, `source minProperties ${sourceMinimum} is weaker than target minProperties ${targetMinimum}`);
    }
  }
  const targetMaximum = schemaMaxProperties(targetSchema);
  if (targetMaximum !== null) {
    const sourceMaximum = schemaMaxProperties(sourceSchema);
    if (sourceMaximum === null) {
      return reasonAt(path, `target requires property count <= ${targetMaximum} but source has no maxProperties`);
    }
    if (sourceMaximum > targetMaximum) {
      return reasonAt(path, `source maxProperties ${sourceMaximum} is weaker than target maxProperties ${targetMaximum}`);
    }
  }
  return '';
}

function appendCompatibilityPath(path, segment) {
  return path ? `${path}.${segment}` : segment;
}

function reasonAt(path, reason) {
  return path ? `at '${path}': ${reason}` : reason;
}

function valueDomainLabel(values) {
  return `[${values.map(String).join(', ')}]`;
}

function schemaEnumValues(schema) {
  if (Object.prototype.hasOwnProperty.call(schema || {}, 'const')) {
    return [schema.const];
  }
  if (Array.isArray(schema?.enum)) {
    return uniqueSchemaValues(schema.enum);
  }
  if (rawSchemaType(schema) === 'enum' && Array.isArray(schema?.values)) {
    return uniqueSchemaValues(schema.values);
  }
  return [];
}

function uniqueSchemaValues(values) {
  const seen = new Set();
  const unique = [];
  for (const value of values) {
    const key = schemaValueKey(value);
    if (!seen.has(key)) {
      seen.add(key);
      unique.push(value);
    }
  }
  return unique;
}

function schemaValuesEqual(left, right) {
  return schemaValueKey(left) === schemaValueKey(right);
}

function schemaValueKey(value) {
  return JSON.stringify(value);
}

function canonicalSchemaValueKey(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalSchemaValueKey).join(',')}]`;
  }
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalSchemaValueKey(value[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

function schemaValueMatchesSchema(value, schema) {
  const type = rawSchemaType(schema);
  if (value === null && schemaAllowsNull(schema)) {
    const values = schemaEnumValues(schema);
    return !values.length || values.some((allowed) => schemaValuesEqual(allowed, null));
  }
  if (!schemaValueMatchesType(value, type)) {
    return false;
  }
  const values = schemaEnumValues(schema);
  if (values.length && !values.some((allowed) => schemaValuesEqual(allowed, value))) {
    return false;
  }
  return numericValueMatchesBounds(value, schema)
    && numericValueMatchesMultipleOf(value, schema)
    && stringValueMatchesLengthBounds(value, schema)
    && stringValueMatchesPattern(value, schema)
    && stringValueMatchesFormat(value, schema)
    && arrayValueMatchesSchema(value, schema)
    && objectValueMatchesSchema(value, schema);
}

function schemaValueMatchesType(value, type) {
  if (!type || type === 'any' || type === 'opaque') {
    return true;
  }
  if (type === 'integer') {
    return typeof value === 'number' && Number.isInteger(value);
  }
  if (type === 'number' || type === 'decimal') {
    return typeof value === 'number';
  }
  if (type === 'string' || type === 'duration' || type === 'datetime') {
    return typeof value === 'string';
  }
  if (type === 'boolean') {
    return typeof value === 'boolean';
  }
  if (type === 'object') {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }
  if (type === 'array') {
    return Array.isArray(value);
  }
  if (type === 'null') {
    return value === null;
  }
  return true;
}

function rawSchemaType(schema) {
  if (!schema) return '';
  const declared = schema.kind || schema.type;
  if (Array.isArray(declared)) {
    return nullableTypePrimary(declared);
  }
  return declared
    || (schema.properties ? 'object' : '')
    || (schema.items ? 'array' : '')
    || (Object.prototype.hasOwnProperty.call(schema, 'const') ? schemaTypeForValue(schema.const) : '');
}

function nullableTypePrimary(types) {
  let primary = '';
  let concreteTypes = 0;
  for (const item of types) {
    if (typeof item !== 'string' || !item.trim()) {
      return String(types);
    }
    if (item !== 'null') {
      primary = item;
      concreteTypes += 1;
    }
  }
  if (concreteTypes > 1) {
    return String(types);
  }
  return primary || 'null';
}

function schemaMayProduceNull(schema) {
  const values = schemaEnumValues(schema);
  return values.length
    ? values.some((value) => schemaValuesEqual(value, null))
    : schemaAllowsNull(schema);
}

function schemaAllowsNull(schema) {
  const declared = schema?.kind ?? schema?.type;
  if (Array.isArray(declared)) {
    return declared.includes('null');
  }
  if (declared === 'null') {
    return true;
  }
  return declared === undefined
    && Object.prototype.hasOwnProperty.call(schema || {}, 'const')
    && schema.const === null;
}

function schemaTypeForValue(value) {
  if (value === null) return 'null';
  if (typeof value === 'string') return 'string';
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'number') return Number.isInteger(value) ? 'integer' : 'number';
  if (Array.isArray(value)) return 'array';
  if (value && typeof value === 'object') return 'object';
  return '';
}

function numericType(type) {
  return type === 'number' || type === 'integer' || type === 'decimal';
}

function stringType(type) {
  return type === 'string' || type === 'duration' || type === 'datetime';
}

function arrayType(type) {
  return type === 'array';
}

function schemaHasNumericBounds(schema) {
  return Object.prototype.hasOwnProperty.call(schema || {}, 'minimum')
    || Object.prototype.hasOwnProperty.call(schema || {}, 'maximum')
    || Object.prototype.hasOwnProperty.call(schema || {}, 'exclusiveMinimum')
    || Object.prototype.hasOwnProperty.call(schema || {}, 'exclusiveMaximum');
}

function schemaHasStringLengthBounds(schema) {
  return Object.prototype.hasOwnProperty.call(schema || {}, 'minLength')
    || Object.prototype.hasOwnProperty.call(schema || {}, 'maxLength');
}

function schemaHasStringPattern(schema) {
  return Object.prototype.hasOwnProperty.call(schema || {}, 'pattern');
}

function stringLengthBoundariesValid(schema) {
  return (!Object.prototype.hasOwnProperty.call(schema || {}, 'minLength') || stringLengthBoundaryValue(schema.minLength) !== null)
    && (!Object.prototype.hasOwnProperty.call(schema || {}, 'maxLength') || stringLengthBoundaryValue(schema.maxLength) !== null);
}

function stringValueMatchesLengthBounds(value, schema) {
  if (typeof value !== 'string') {
    return true;
  }
  const length = stringCodePointLength(value);
  const minimum = schemaMinLength(schema);
  if (minimum !== null && length < minimum) {
    return false;
  }
  const maximum = schemaMaxLength(schema);
  return maximum === null || length <= maximum;
}

function stringValueMatchesPattern(value, schema) {
  if (typeof value !== 'string') {
    return true;
  }
  const pattern = schemaPatternValue(schema);
  if (pattern === null) {
    return true;
  }
  try {
    return new RegExp(pattern).test(value);
  } catch {
    return true;
  }
}

function stringValueMatchesFormat(value, schema) {
  if (typeof value !== 'string') {
    return true;
  }
  const format = schemaFormatValue(schema);
  return format === null || stringMatchesFormat(value, format);
}

function schemaPatternValue(schema) {
  return typeof schema?.pattern === 'string' ? schema.pattern : null;
}

function schemaFormatValue(schema) {
  return typeof schema?.format === 'string' && SUPPORTED_SCHEMA_STRING_FORMATS.has(schema.format)
    ? schema.format
    : null;
}

function stringMatchesFormat(value, format) {
  if (format === 'email') {
    return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value);
  }
  if (format === 'uuid') {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
  }
  if (format === 'uri') {
    try {
      const parsed = new URL(value);
      return Boolean(parsed.protocol);
    } catch {
      return false;
    }
  }
  if (format === 'date') {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (!match) {
      return false;
    }
    const date = new Date(`${value}T00:00:00Z`);
    return date.getUTCFullYear() === Number(match[1])
      && date.getUTCMonth() + 1 === Number(match[2])
      && date.getUTCDate() === Number(match[3]);
  }
  if (format === 'date-time') {
    return /^\d{4}-\d{2}-\d{2}T.+(?:Z|[+-]\d{2}:\d{2})$/.test(value) && !Number.isNaN(Date.parse(value));
  }
  if (format === 'duration') {
    return /^P(?=\d+D|T\d)(?:\d+D)?(?:T(?=\d)(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d+)?S)?)?$/.test(value);
  }
  return true;
}

function schemaMinLength(schema) {
  return stringLengthBoundaryValue(schema?.minLength);
}

function schemaMaxLength(schema) {
  return stringLengthBoundaryValue(schema?.maxLength);
}

function stringLengthBoundaryValue(value) {
  return typeof value === 'number' && Number.isFinite(value) && Number.isInteger(value) && value >= 0
    ? value
    : null;
}

function stringCodePointLength(value) {
  return Array.from(String(value ?? '')).length;
}

function schemaHasArrayItemBounds(schema) {
  return Object.prototype.hasOwnProperty.call(schema || {}, 'minItems')
    || Object.prototype.hasOwnProperty.call(schema || {}, 'maxItems');
}

function schemaHasArrayContains(schema) {
  return Object.prototype.hasOwnProperty.call(schema || {}, 'contains')
    || Object.prototype.hasOwnProperty.call(schema || {}, 'minContains')
    || Object.prototype.hasOwnProperty.call(schema || {}, 'maxContains');
}

function schemaHasObjectPropertyBounds(schema) {
  return Object.prototype.hasOwnProperty.call(schema || {}, 'minProperties')
    || Object.prototype.hasOwnProperty.call(schema || {}, 'maxProperties');
}

function arrayItemBoundariesValid(schema) {
  return (!Object.prototype.hasOwnProperty.call(schema || {}, 'minItems') || arrayItemBoundaryValue(schema.minItems) !== null)
    && (!Object.prototype.hasOwnProperty.call(schema || {}, 'maxItems') || arrayItemBoundaryValue(schema.maxItems) !== null);
}

function arrayContainsBoundariesValid(schema) {
  return (!Object.prototype.hasOwnProperty.call(schema || {}, 'minContains') || arrayItemBoundaryValue(schema.minContains) !== null)
    && (!Object.prototype.hasOwnProperty.call(schema || {}, 'maxContains') || arrayItemBoundaryValue(schema.maxContains) !== null);
}

function arrayValueMatchesSchema(value, schema) {
  if (!Array.isArray(value) || rawSchemaType(schema) !== 'array') {
    return true;
  }
  if (!arrayValueMatchesItemBounds(value, schema)) {
    return false;
  }
  if (!arrayValueMatchesUniqueItems(value, schema)) {
    return false;
  }
  if (!arrayValueMatchesContains(value, schema)) {
    return false;
  }
  return value.every((item, index) => {
    const itemSchema = arrayItemSchemaForIndex(schema, index);
    return !itemSchema || schemaValueMatchesSchema(item, itemSchema);
  });
}

function arrayValueMatchesItemBounds(value, schema) {
  if (!Array.isArray(value)) {
    return true;
  }
  const size = value.length;
  const minimum = explicitSchemaMinItems(schema);
  if (minimum !== null && size < minimum) {
    return false;
  }
  const maximum = explicitSchemaMaxItems(schema);
  return maximum === null || size <= maximum;
}

function arrayValueMatchesUniqueItems(value, schema) {
  return !Array.isArray(value) || schema?.uniqueItems !== true || arrayItemsUnique(value);
}

function arrayValueMatchesContains(value, schema) {
  if (!Array.isArray(value)) {
    return true;
  }
  const contains = schemaContainsSchema(schema);
  if (!contains) {
    return true;
  }
  const matches = value.filter((item) => schemaValueMatchesSchema(item, contains)).length;
  const minimum = schemaMinContains(schema);
  if (minimum !== null && matches < minimum) {
    return false;
  }
  const maximum = schemaMaxContains(schema);
  return maximum === null || matches <= maximum;
}

function arrayItemSchemaForIndex(schema, index) {
  const prefixItems = schemaPrefixItems(schema);
  if (index < prefixItems.length) {
    return prefixItems[index];
  }
  return schemaItemsSchema(schema);
}

function schemaPrefixItems(schema) {
  const prefixItems = schema?.prefixItems;
  return Array.isArray(prefixItems)
    ? prefixItems.filter((itemSchema) => itemSchema && typeof itemSchema === 'object' && !Array.isArray(itemSchema))
    : [];
}

function schemaItemsSchema(schema) {
  const items = schema?.items;
  return items && typeof items === 'object' && !Array.isArray(items) ? items : null;
}

function arrayItemsUnique(value) {
  const seen = new Set();
  for (const item of value) {
    const key = schemaValueKey(item);
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
  }
  return true;
}

function objectValueMatchesSchema(value, schema) {
  if (value === null || typeof value !== 'object' || Array.isArray(value) || rawSchemaType(schema) !== 'object') {
    return true;
  }
  if (!objectValueMatchesPropertyBounds(value, schema)) {
    return false;
  }
  if (!objectValueMatchesPropertyNames(value, schema)) {
    return false;
  }
  if (!objectValueMatchesPatternProperties(value, schema)) {
    return false;
  }
  if (!objectValueMatchesDependentRequired(value, schema)) {
    return false;
  }
  if (!objectValueMatchesDependentSchemas(value, schema)) {
    return false;
  }
  const properties = schemaObjectProperties(schema);
  for (const required of schemaRequiredNames(schema)) {
    if (!Object.prototype.hasOwnProperty.call(value, required) || value[required] === null) {
      return false;
    }
  }
  const residual = residualPropertiesPolicy(schema);
  for (const [key, item] of Object.entries(value)) {
    const patternSchemas = matchingPatternPropertySchemas(schema, key);
    if (Object.prototype.hasOwnProperty.call(properties, key)) {
      if (!schemaValueMatchesSchema(item, properties[key])) {
        return false;
      }
    }
    for (const patternSchema of patternSchemas) {
      if (!schemaValueMatchesSchema(item, patternSchema)) {
        return false;
      }
    }
    if (Object.prototype.hasOwnProperty.call(properties, key) || patternSchemas.length) {
      continue;
    } else if (residual === false) {
      return false;
    } else if (residual && typeof residual === 'object' && !Array.isArray(residual)
        && !schemaValueMatchesSchema(item, residual)) {
      return false;
    }
  }
  return true;
}

function objectValueMatchesPropertyBounds(value, schema) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return true;
  }
  const size = Object.keys(value).length;
  const minimum = explicitSchemaMinProperties(schema);
  if (minimum !== null && size < minimum) {
    return false;
  }
  const maximum = explicitSchemaMaxProperties(schema);
  return maximum === null || size <= maximum;
}

function objectValueMatchesPropertyNames(value, schema) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return true;
  }
  const propertyNameSchema = schemaPropertyNameSchema(schema);
  if (!propertyNameSchema) {
    return true;
  }
  const effectiveSchema = effectivePropertyNameSchema(propertyNameSchema);
  return Object.keys(value).every((name) => schemaValueMatchesSchema(name, effectiveSchema));
}

function objectValueMatchesPatternProperties(value, schema) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return true;
  }
  for (const [key, item] of Object.entries(value)) {
    for (const patternSchema of matchingPatternPropertySchemas(schema, key)) {
      if (!schemaValueMatchesSchema(item, patternSchema)) {
        return false;
      }
    }
  }
  return true;
}

function objectValueMatchesDependentRequired(value, schema) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return true;
  }
  const dependencies = schemaDependentRequired(schema);
  for (const [trigger, required] of Object.entries(dependencies)) {
    if (!presentObjectProperty(value, trigger)) {
      continue;
    }
    if (!required.every((dependency) => presentObjectProperty(value, dependency))) {
      return false;
    }
  }
  return true;
}

function objectValueMatchesDependentSchemas(value, schema) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return true;
  }
  const dependencies = schemaDependentSchemas(schema);
  for (const [trigger, dependentSchema] of Object.entries(dependencies)) {
    if (!presentObjectProperty(value, trigger)) {
      continue;
    }
    if (!schemaValueMatchesSchema(value, dependentSchema)) {
      return false;
    }
  }
  return true;
}

function schemaDependentRequired(schema) {
  const dependentRequired = schema?.dependentRequired;
  if (!dependentRequired || typeof dependentRequired !== 'object' || Array.isArray(dependentRequired)) {
    return {};
  }
  const result = {};
  for (const [trigger, dependencies] of Object.entries(dependentRequired)) {
    result[trigger] = Array.isArray(dependencies)
      ? dependencies.filter((dependency) => typeof dependency === 'string' && dependency.trim())
      : [];
  }
  return result;
}

function schemaDependentSchemas(schema) {
  const dependentSchemas = schema?.dependentSchemas;
  if (!dependentSchemas || typeof dependentSchemas !== 'object' || Array.isArray(dependentSchemas)) {
    return {};
  }
  const result = {};
  for (const [trigger, dependentSchema] of Object.entries(dependentSchemas)) {
    if (dependentSchema && typeof dependentSchema === 'object' && !Array.isArray(dependentSchema)) {
      result[trigger] = effectiveDependentObjectSchema(dependentSchema);
    }
  }
  return result;
}

function effectiveDependentObjectSchema(schema) {
  const effective = { ...(schema || {}) };
  if (!rawSchemaType(effective)
      && (Object.prototype.hasOwnProperty.call(effective, 'required')
        || Object.prototype.hasOwnProperty.call(effective, 'dependentRequired')
        || Object.prototype.hasOwnProperty.call(effective, 'dependentSchemas')
        || Object.prototype.hasOwnProperty.call(effective, 'minProperties')
        || Object.prototype.hasOwnProperty.call(effective, 'maxProperties')
        || Object.prototype.hasOwnProperty.call(effective, 'propertyNames')
        || Object.prototype.hasOwnProperty.call(effective, 'patternProperties')
        || Object.prototype.hasOwnProperty.call(effective, 'unevaluatedProperties'))) {
    effective.type = 'object';
  }
  return effective;
}

function presentObjectProperty(value, property) {
  return Object.prototype.hasOwnProperty.call(value, property) && value[property] !== null;
}

function sourceCannotContainProperty(sourceSchema, property) {
  return residualPropertiesPolicy(sourceSchema) === false
    && !Object.prototype.hasOwnProperty.call(schemaObjectProperties(sourceSchema), property)
    && !matchingPatternPropertySchemas(sourceSchema, property).length;
}

function residualPropertiesPolicy(schema) {
  if (Object.prototype.hasOwnProperty.call(schema || {}, 'additionalProperties')) {
    return schema.additionalProperties;
  }
  return schema?.unevaluatedProperties;
}

function residualPropertiesKeyword(schema) {
  return Object.prototype.hasOwnProperty.call(schema || {}, 'additionalProperties')
    ? 'additionalProperties'
    : 'unevaluatedProperties';
}

function schemaMinItems(schema) {
  const explicit = explicitSchemaMinItems(schema);
  if (explicit !== null) {
    return explicit;
  }
  const values = schemaEnumValues(schema);
  if (values.length && values.every(Array.isArray)) {
    return Math.min(...values.map((value) => value.length));
  }
  return null;
}

function schemaMaxItems(schema) {
  const explicit = explicitSchemaMaxItems(schema);
  if (explicit !== null) {
    return explicit;
  }
  const values = schemaEnumValues(schema);
  if (values.length && values.every(Array.isArray)) {
    return Math.max(...values.map((value) => value.length));
  }
  return null;
}

function explicitSchemaMinItems(schema) {
  return arrayItemBoundaryValue(schema?.minItems);
}

function explicitSchemaMaxItems(schema) {
  return arrayItemBoundaryValue(schema?.maxItems);
}

function schemaContainsSchema(schema) {
  const contains = schema?.contains;
  return contains && typeof contains === 'object' && !Array.isArray(contains) ? contains : null;
}

function schemaMinContains(schema) {
  if (!Object.prototype.hasOwnProperty.call(schema || {}, 'contains')) {
    return null;
  }
  const explicit = arrayItemBoundaryValue(schema?.minContains);
  return explicit === null ? 1 : explicit;
}

function schemaMaxContains(schema) {
  return arrayItemBoundaryValue(schema?.maxContains);
}

function schemaMinProperties(schema) {
  const explicit = explicitSchemaMinProperties(schema);
  if (explicit !== null) {
    return explicit;
  }
  const values = schemaEnumValues(schema);
  if (values.length && values.every((value) => value !== null && typeof value === 'object' && !Array.isArray(value))) {
    return Math.min(...values.map((value) => Object.keys(value).length));
  }
  return null;
}

function schemaMaxProperties(schema) {
  const explicit = explicitSchemaMaxProperties(schema);
  if (explicit !== null) {
    return explicit;
  }
  const values = schemaEnumValues(schema);
  if (values.length && values.every((value) => value !== null && typeof value === 'object' && !Array.isArray(value))) {
    return Math.max(...values.map((value) => Object.keys(value).length));
  }
  return null;
}

function explicitSchemaMinProperties(schema) {
  return objectPropertyBoundaryValue(schema?.minProperties);
}

function explicitSchemaMaxProperties(schema) {
  return objectPropertyBoundaryValue(schema?.maxProperties);
}

function objectPropertyBoundariesValid(schema) {
  return (!Object.prototype.hasOwnProperty.call(schema || {}, 'minProperties') || objectPropertyBoundaryValue(schema.minProperties) !== null)
    && (!Object.prototype.hasOwnProperty.call(schema || {}, 'maxProperties') || objectPropertyBoundaryValue(schema.maxProperties) !== null);
}

function objectPropertyBoundaryValue(value) {
  return typeof value === 'number' && Number.isFinite(value) && Number.isInteger(value) && value >= 0
    ? value
    : null;
}

function arrayItemBoundaryValue(value) {
  return typeof value === 'number' && Number.isFinite(value) && Number.isInteger(value) && value >= 0
    ? value
    : null;
}

function numericBoundariesValid(schema) {
  return (!Object.prototype.hasOwnProperty.call(schema || {}, 'minimum') || numericBoundaryValue(schema.minimum) !== null)
    && (!Object.prototype.hasOwnProperty.call(schema || {}, 'maximum') || numericBoundaryValue(schema.maximum) !== null)
    && (!Object.prototype.hasOwnProperty.call(schema || {}, 'exclusiveMinimum') || numericBoundaryValue(schema.exclusiveMinimum) !== null)
    && (!Object.prototype.hasOwnProperty.call(schema || {}, 'exclusiveMaximum') || numericBoundaryValue(schema.exclusiveMaximum) !== null);
}

function numericValueMatchesBounds(value, schema) {
  if (typeof value !== 'number') {
    return true;
  }
  const lower = schemaLowerBound(schema);
  if (lower && !numericLowerAccepts(lower, value)) {
    return false;
  }
  const upper = schemaUpperBound(schema);
  return !upper || numericUpperAccepts(upper, value);
}

function numericValueMatchesMultipleOf(value, schema) {
  if (typeof value !== 'number') {
    return true;
  }
  const multipleOf = numericMultipleOfValue(schema?.multipleOf);
  return multipleOf === null || numericValueIsMultipleOf(value, multipleOf);
}

function numericMultipleOfValue(value) {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
    ? value
    : null;
}

function numericValueIsMultipleOf(value, multipleOf) {
  if (!Number.isFinite(value) || !Number.isFinite(multipleOf) || multipleOf <= 0) {
    return true;
  }
  const quotient = value / multipleOf;
  const nearest = Math.round(quotient);
  const tolerance = 1.0e-9 * Math.max(1, Math.abs(quotient));
  return Math.abs(quotient - nearest) <= tolerance;
}

function numberLabel(value) {
  return String(value);
}

function schemaLowerBound(schema) {
  const minimum = numericBoundary(schema?.minimum, false);
  const exclusiveMinimum = numericBoundary(schema?.exclusiveMinimum, true);
  if (!minimum) return exclusiveMinimum;
  if (!exclusiveMinimum) return minimum;
  if (minimum.value > exclusiveMinimum.value) return minimum;
  if (minimum.value < exclusiveMinimum.value) return exclusiveMinimum;
  return exclusiveMinimum.exclusive ? exclusiveMinimum : minimum;
}

function schemaUpperBound(schema) {
  const maximum = numericBoundary(schema?.maximum, false);
  const exclusiveMaximum = numericBoundary(schema?.exclusiveMaximum, true);
  if (!maximum) return exclusiveMaximum;
  if (!exclusiveMaximum) return maximum;
  if (maximum.value < exclusiveMaximum.value) return maximum;
  if (maximum.value > exclusiveMaximum.value) return exclusiveMaximum;
  return exclusiveMaximum.exclusive ? exclusiveMaximum : maximum;
}

function numericBoundary(value, exclusive) {
  const boundaryValue = numericBoundaryValue(value);
  return boundaryValue === null ? null : { value: boundaryValue, exclusive };
}

function numericBoundaryValue(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function lowerBoundAtLeast(source, target) {
  return source.value > target.value
    || (source.value === target.value && (source.exclusive || !target.exclusive));
}

function upperBoundAtMost(source, target) {
  return source.value < target.value
    || (source.value === target.value && (source.exclusive || !target.exclusive));
}

function numericBoundsContradict(lower, upper) {
  return lower.value > upper.value
    || (lower.value === upper.value && (lower.exclusive || upper.exclusive));
}

function numericLowerAccepts(boundary, candidate) {
  return boundary.exclusive ? candidate > boundary.value : candidate >= boundary.value;
}

function numericUpperAccepts(boundary, candidate) {
  return boundary.exclusive ? candidate < boundary.value : candidate <= boundary.value;
}

function numericLowerLabel(boundary) {
  return boundary.exclusive ? `value > ${trimNumericLabel(boundary.value)}` : `value >= ${trimNumericLabel(boundary.value)}`;
}

function numericUpperLabel(boundary) {
  return boundary.exclusive ? `value < ${trimNumericLabel(boundary.value)}` : `value <= ${trimNumericLabel(boundary.value)}`;
}

function trimNumericLabel(value) {
  return Number.isInteger(value) ? String(value) : String(value);
}

function wouldCreateCycle(sourceId, targetId) {
  if (!sourceId || !targetId || sourceId === targetId) {
    return true;
  }
  const outgoing = new Map();
  for (const node of state.builder.nodes) {
    outgoing.set(node.id, []);
  }
  for (const edge of builderEdges(state.builder, { includeFallback: false, includeConfig: true })) {
    if (!outgoing.has(edge.source)) {
      outgoing.set(edge.source, []);
    }
    outgoing.get(edge.source).push(edge.target);
  }
  outgoing.get(sourceId)?.push(targetId);
  const seen = new Set();
  const stack = [targetId];
  while (stack.length) {
    const nodeId = stack.pop();
    if (nodeId === sourceId) {
      return true;
    }
    if (seen.has(nodeId)) {
      continue;
    }
    seen.add(nodeId);
    stack.push(...(outgoing.get(nodeId) || []));
  }
  return false;
}

function configureComposerDropTarget(svg) {
  svg.classList.toggle('composer-drop-target', isComposerSelected());
  svg.ondragover = null;
  svg.ondragleave = null;
  svg.ondrop = null;
  if (!isComposerSelected()) {
    svg.classList.remove('drop-active');
    return;
  }
  svg.ondragover = (event) => {
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'copy';
    }
    svg.classList.add('drop-active');
  };
  svg.ondragleave = () => {
    svg.classList.remove('drop-active');
  };
  svg.ondrop = (event) => {
    event.preventDefault();
    svg.classList.remove('drop-active');
    const type = event.dataTransfer.getData('application/x-bloge-operator')
      || event.dataTransfer.getData('text/plain');
    addBuilderNodeAtClientPoint(type, event.clientX, event.clientY);
    state.draggingOperatorType = null;
  };
}

function svgPoint(svg, event) {
  return svgPointFromClient(svg, event.clientX, event.clientY);
}

function svgPointFromClient(svg, clientX, clientY) {
  const point = svg.createSVGPoint();
  point.x = clientX;
  point.y = clientY;
  const transform = svg.getScreenCTM();
  if (!transform) {
    return { x: 80, y: 210 };
  }
  return point.matrixTransform(transform.inverse());
}

function addBuilderNodeAtClientPoint(type, clientX, clientY) {
  const svg = $('diagram');
  if (!svg || !pointInsideElement(svg, clientX, clientY)) return null;
  const point = svgPointFromClient(svg, clientX, clientY);
  return addBuilderNode(type, {
    x: point.x - NODE_SIZE.width / 2,
    y: point.y - NODE_SIZE.height / 2
  });
}

function pointInsideElement(element, clientX, clientY) {
  const rect = element.getBoundingClientRect();
  return clientX >= rect.left
    && clientX <= rect.right
    && clientY >= rect.top
    && clientY <= rect.bottom;
}

function startPaletteDrag(event, button) {
  if (!isComposerSelected() || event.button !== 0) return;
  state.paletteDrag = {
    pointerId: event.pointerId,
    type: button.dataset.operatorType,
    startX: event.clientX,
    startY: event.clientY,
    active: false
  };
  state.draggingOperatorType = button.dataset.operatorType;
  button.classList.add('drag-origin');
  try {
    button.setPointerCapture(event.pointerId);
  } catch {
    // Pointer capture is best-effort; document-level listeners handle the rest.
  }
}

function handleDocumentPointerMove(event) {
  if (state.paletteDrag) {
    movePaletteDrag(event);
  }
  if (state.nodeDrag) {
    moveNodeDrag(event);
  }
  if (state.connectionDrag) {
    moveConnectionDrag(event);
  }
}

function handleDocumentPointerUp(event) {
  if (state.paletteDrag) {
    finishPaletteDrag(event);
  }
  if (state.nodeDrag) {
    finishNodeDrag(event);
  }
  if (state.connectionDrag) {
    finishConnectionDrag(event);
  }
}

function movePaletteDrag(event) {
  const drag = state.paletteDrag;
  if (!drag || event.pointerId !== drag.pointerId) return;
  const moved = Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY);
  if (!drag.active && moved < DRAG_START_THRESHOLD) return;
  drag.active = true;
  event.preventDefault();
  updateDragPreview(drag.type, event.clientX, event.clientY);
  setDiagramDropActive(pointInsideElement($('diagram'), event.clientX, event.clientY));
}

function finishPaletteDrag(event) {
  const drag = state.paletteDrag;
  if (!drag || event.pointerId !== drag.pointerId) return;
  const wasActive = drag.active;
  if (wasActive) {
    event.preventDefault();
    addBuilderNodeAtClientPoint(drag.type, event.clientX, event.clientY);
    state.suppressPaletteClick = true;
    setTimeout(() => {
      state.suppressPaletteClick = false;
    }, 0);
  }
  cancelPaletteDrag();
}

function cancelPaletteDrag() {
  state.paletteDrag = null;
  state.draggingOperatorType = null;
  setDiagramDropActive(false);
  removeDragPreview();
  document.querySelectorAll('.operator-card.drag-origin').forEach((card) => {
    card.classList.remove('drag-origin');
  });
}

function updateDragPreview(type, clientX, clientY) {
  if (!dragPreview) {
    dragPreview = document.createElement('div');
    dragPreview.className = 'drag-preview';
    document.body.appendChild(dragPreview);
  }
  const spec = OPERATOR_TYPES[type];
  dragPreview.textContent = spec?.label || type;
  dragPreview.style.left = `${clientX}px`;
  dragPreview.style.top = `${clientY}px`;
  dragPreview.classList.toggle('over-canvas', pointInsideElement($('diagram'), clientX, clientY));
  document.body.classList.add('dragging-operator');
}

function removeDragPreview() {
  if (dragPreview) {
    dragPreview.remove();
    dragPreview = null;
  }
  document.body.classList.remove('dragging-operator');
}

function setDiagramDropActive(active) {
  const svg = $('diagram');
  if (!svg) return;
  svg.classList.toggle('drop-active', Boolean(active));
}

function startConnectionDrag(event, source) {
  if (!isComposerSelected() || event.button !== 0) return;
  event.preventDefault();
  event.stopPropagation();
  const svg = $('diagram');
  state.connectionDrag = {
    pointerId: event.pointerId,
    source: { ...source },
    current: svgPointFromClient(svg, event.clientX, event.clientY)
  };
  setConnectionMessage('', 'info');
  document.body.classList.add('connecting-edge');
  renderDiagram();
}

function moveConnectionDrag(event) {
  const drag = state.connectionDrag;
  if (!drag || event.pointerId !== drag.pointerId) return;
  event.preventDefault();
  const svg = $('diagram');
  drag.current = svgPointFromClient(svg, event.clientX, event.clientY);
  const target = connectionTargetAtPoint(event);
  if (target) {
    const compatibility = connectionCompatibility(drag.source, target);
    setConnectionMessage(compatibility.ok
      ? (connectionAlreadyApplied(drag.source, target)
        ? 'Connection already exists.'
        : `${endpointLabel(drag.source)} -> ${endpointLabel(target)}`)
      : compatibility.message,
      compatibility.ok ? 'info' : 'error');
  } else {
    renderConnectionStatus();
  }
  renderDiagram();
}

function finishConnectionDrag(event) {
  const drag = state.connectionDrag;
  if (!drag || event.pointerId !== drag.pointerId) return;
  event.preventDefault();
  let target = connectionTargetAtPoint(event);
  state.connectionDrag = null;
  document.body.classList.remove('connecting-edge');
  if (target) {
    if (target.kind === 'route') {
      const condition = routeConditionForConnection(target);
      if (condition === null) {
        renderDiagram();
        return;
      }
      target = { ...target, condition };
    }
    const compatibility = connectionCompatibility(drag.source, target);
    if (compatibility.ok) {
      if (connectionAlreadyApplied(drag.source, target)) {
        setConnectionMessage('Connection already exists.', 'info');
        renderDiagram();
        return;
      }
      setConnectionMessage('Checking connection with server...', 'info');
      renderDiagram();
      checkVisualConnectionOnServer(drag.source, target)
        .then((serverCheck) => {
          if (serverCheck.accepted) {
            const checkedTarget = targetWithServerBindingKey(target, serverCheck);
            applyConnection(drag.source, checkedTarget);
            setConnectionMessage(
              `Connected ${endpointLabel(drag.source)} -> ${endpointLabel(checkedTarget)}.`,
              'success'
            );
          } else {
            setConnectionMessage(serverCheck.message, 'error');
          }
          renderDiagram();
        })
        .catch((error) => {
          setConnectionMessage(error.message, 'error');
          renderDiagram();
        });
      return;
    } else {
      setConnectionMessage(compatibility.message, 'error');
    }
  }
  renderDiagram();
}

async function checkVisualConnectionOnServer(source, target) {
  const kind = target.kind === 'dependency'
    ? 'dependency'
    : (target.kind === 'route' ? 'route' : 'data');
  const response = await fetch('/api/visual/connections/check', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      draft: builderToVisualDraft(state.builder),
      kind,
      condition: kind === 'route' ? (target.condition || 'otherwise') : '',
      source: {
        nodeId: source.nodeId,
        port: source.port || '',
        path: source.path || ''
      },
      target: {
        nodeId: target.nodeId,
        port: target.port || '',
        path: target.path || ''
      }
    })
  });
  if (!response.ok) {
    throw new Error(`Connection check failed with ${response.status}`);
  }
  const payload = await response.json();
  const diagnostics = normalizeDiagnostics(payload.diagnostics);
  if (diagnostics.length) {
    setVisualCheck(
      payload.accepted ? 'Connection accepted with diagnostics.' : 'Connection rejected.',
      visualCheckLevel(diagnostics, Boolean(payload.accepted)),
      diagnostics
    );
  }
  return {
    accepted: Boolean(payload.accepted),
    bindingKey: payload.bindingKey || '',
    diagnostics,
    message: diagnosticMessage(diagnostics, 'Connection rejected by server.'),
    payload
  };
}

function targetWithServerBindingKey(target, serverCheck) {
  if (!serverCheck?.bindingKey || target.port === 'config' || target.kind === 'dependency' || target.kind === 'route') {
    return target;
  }
  return { ...target, key: serverCheck.bindingKey };
}

function connectionTargetAtPoint(event) {
  const element = document.elementFromPoint(event.clientX, event.clientY);
  const handle = element?.closest?.('[data-port-role="target"]');
  if (!handle) {
    return null;
  }
  const node = state.builder.nodes.find((item) => item.id === handle.dataset.nodeId);
  if (!node) {
    return null;
  }
  const candidate = canvasTargetHandlesForNode(node).find((item) =>
    item.port === handle.dataset.port && (item.path || '') === (handle.dataset.path || '')
  );
  return candidate || null;
}

function applyConnection(source, target) {
  const node = state.builder.nodes.find((item) => item.id === target.nodeId);
  if (!node) return;
  if (target.kind === 'dependency') {
    addDependencyEdge(source, target);
    state.builder.selectedId = node.id;
    state.selectedNodeId = node.id;
    syncComposerFromBuilder({ render: false });
    renderInputForm();
    return;
  }
  if (target.kind === 'route') {
    addRouteEdge(source, target);
    state.builder.selectedId = node.id;
    state.selectedNodeId = node.id;
    syncComposerFromBuilder({ render: false });
    renderInputForm();
    return;
  }
  const expression = expressionForConnectionSource(source);
  if (target.port === 'config') {
    node.config = node.config || {};
    setConfigValueAtPath(node.config, target.path, {
      kind: 'expression',
      expr: expression
    });
  } else if (node.type === 'httpResource') {
    setResourceParamExpression(node, target.path || defaultParamNameForOperator(specForNode(node)), expression);
  } else if (node.type === 'decisionTable') {
    if (target.path === 'amount') {
      node.amountSource = expression;
    } else {
      node.scoreSource = expression;
    }
  } else if (node.type === 'customOperator') {
    const key = target.key || target.path;
    node.customInputs = node.customInputs || {};
    node.customInputs[key] = expression;
    node.customInputPorts = node.customInputPorts || {};
    node.customInputPorts[key] = target.port || inputPortForInputPath(specForNode(node), target.path);
    node.customInputPaths = node.customInputPaths || {};
    node.customInputPaths[key] = target.path;
  } else if (node.type === 'transform') {
    node.policyNode = source.nodeId;
    delete node.policyNodeCleared;
  }
  state.builder.selectedId = node.id;
  state.selectedNodeId = node.id;
  syncComposerFromBuilder({ render: false });
  renderInputForm();
}

function addDependencyEdge(source, target) {
  state.builder.dependencyEdges = state.builder.dependencyEdges || [];
  const edge = {
    source: source.nodeId,
    target: target.nodeId,
    sourcePort: source.port || 'output',
    sourcePath: source.path || '',
    label: 'depends'
  };
  const key = connectionKey(
    { nodeId: edge.source, port: edge.sourcePort, path: edge.sourcePath },
    { nodeId: edge.target, port: 'dependency', path: '' },
    'dependency'
  );
  const exists = state.builder.dependencyEdges.some((item) => connectionKey(
    { nodeId: item.source, port: item.sourcePort || 'output', path: item.sourcePath || '' },
    { nodeId: item.target, port: 'dependency', path: '' },
    'dependency'
  ) === key);
  if (!exists) {
    state.builder.dependencyEdges.push(edge);
  }
}

function addRouteEdge(source, target) {
  state.builder.routeEdges = state.builder.routeEdges || [];
  const edge = {
    source: source.nodeId,
    target: target.nodeId,
    condition: target.condition || 'otherwise',
    label: target.condition || 'otherwise'
  };
  const key = connectionKey(
    { nodeId: edge.source, port: 'route', path: '' },
    { nodeId: edge.target, port: 'route', path: '', condition: edge.condition },
    'route'
  );
  const exists = state.builder.routeEdges.some((item) => connectionKey(
    { nodeId: item.source, port: 'route', path: '' },
    { nodeId: item.target, port: 'route', path: '', condition: item.condition || 'otherwise' },
    'route'
  ) === key);
  if (!exists) {
    state.builder.routeEdges.push(edge);
  }
}

function routeConditionForConnection(target) {
  const current = target.condition || 'otherwise';
  const value = window.prompt('Route condition', current);
  if (value === null) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed || 'otherwise';
}

function expressionForConnectionSource(source) {
  if (source.nodeId === CONTEXT_SOURCE_ID) {
    return source.path ? `ctx${dslReferenceSuffixForSchemaPath(source.path)}` : 'ctx';
  }
  const payloadSegment = source.port && source.port !== 'output' ? `.${source.port}` : '';
  const pathSegment = dslReferenceSuffixForSchemaPath(source.path);
  return `${source.nodeId}.output${payloadSegment}${pathSegment}`;
}

function startNodeDrag(event, visualNode) {
  if (!isComposerSelected() || event.button !== 0) return;
  const builderNode = state.builder.nodes.find((node) => node.id === visualNode.id);
  if (!builderNode) return;
  const svg = $('diagram');
  const point = svgPointFromClient(svg, event.clientX, event.clientY);
  state.nodeDrag = {
    pointerId: event.pointerId,
    nodeId: builderNode.id,
    startX: event.clientX,
    startY: event.clientY,
    offsetX: point.x - builderNode.x,
    offsetY: point.y - builderNode.y,
    active: false
  };
  state.builder.selectedId = builderNode.id;
  state.selectedNodeId = builderNode.id;
  renderSelectedOperatorEditor();
  renderNodeDetails(builderNode);
  try {
    event.currentTarget.setPointerCapture(event.pointerId);
  } catch {
    // Pointer capture is best-effort; document-level listeners handle the rest.
  }
}

function moveNodeDrag(event) {
  const drag = state.nodeDrag;
  if (!drag || event.pointerId !== drag.pointerId) return;
  const moved = Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY);
  if (!drag.active && moved < DRAG_START_THRESHOLD) return;
  drag.active = true;
  event.preventDefault();
  const svg = $('diagram');
  const point = svgPointFromClient(svg, event.clientX, event.clientY);
  const position = clampNodePosition(point.x - drag.offsetX, point.y - drag.offsetY);
  const node = state.builder.nodes.find((item) => item.id === drag.nodeId);
  if (!node) return;
  node.x = position.x;
  node.y = position.y;
  state.layout = layoutFromBuilder(state.builder);
  renderNodeDetails(node);
  renderDiagram();
}

function finishNodeDrag(event) {
  const drag = state.nodeDrag;
  if (!drag || event.pointerId !== drag.pointerId) return;
  if (drag.active) {
    event.preventDefault();
    state.suppressNodeClick = true;
    setTimeout(() => {
      state.suppressNodeClick = false;
    }, 0);
    syncComposerFromBuilder({ render: false });
    renderNodeDetails(selectedBuilderNode());
    renderDiagram();
  }
  state.nodeDrag = null;
}

function renderDecisionTable() {
  const section = $('decision-table-section');
  const target = $('decision-table');
  const model = currentDecisionTable();
  if (!model) {
    section.hidden = true;
    target.innerHTML = '';
    return;
  }

  section.hidden = false;
  $('decision-table-title').textContent = `${model.title} - hit=${model.hitPolicy}`;
  const inputHeaders = model.inputs.map((column) =>
    `<th scope="col" class="input-column">${escapeHtml(column.label)}</th>`
  ).join('');
  const outputHeaders = model.outputs.map((column) =>
    `<th scope="col">${escapeHtml(column.label)}</th>`
  ).join('');
  const rows = model.rows.map((row) => {
    const inputCells = model.inputs.map((column) =>
      `<td>${escapeHtml(row.conditions[column.key] ?? '')}</td>`
    ).join('');
    const outputCells = model.outputs.map((column) =>
      `<td>${escapeHtml(row.output[column.key] ?? '')}</td>`
    ).join('');
    return `
      <tr data-rule-id="${escapeHtml(row.id)}" title="${escapeHtml(row.explanation)}">
        <th scope="row">${escapeHtml(row.id)}</th>
        ${inputCells}
        ${outputCells}
      </tr>
    `;
  }).join('');
  target.innerHTML = `
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">Rule</th>
            ${inputHeaders}
            ${outputHeaders}
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    </div>
  `;
}

function renderNodeDetails(node) {
  if (!node) {
    $('node-details').textContent = pretty({});
    return;
  }
  $('node-details').textContent = pretty(node);
}

async function runScenario() {
  closeStream();
  state.lastPayload = null;
  highlightDecisionRow(null);
  renderDecisionSummary(null);
  renderDiagram();
  if (isComposerSelected()) {
    await runCustomGraph();
    return;
  }
  const values = inputValues();
  const run = state.selected.run;
  const url = fillTemplate(run.pathTemplate, values);
  $('output').textContent = pretty({ status: 'running', url });
  if (run.mode === 'stream') {
    streamScenario(url);
    return;
  }
  const options = {
    method: run.method,
    headers: run.headers || {}
  };
  if (run.mode === 'post') {
    options.body = JSON.stringify(replacePlaceholders(run.bodyTemplate, values));
  }
  const response = await fetch(url, options);
  const payload = await response.json();
  state.lastPayload = payload;
  $('output').textContent = pretty({ status: response.status, payload });
  highlightDecisionRow(payload);
  renderDecisionSummary(payload);
  renderDiagram();
}

async function runCustomGraph() {
  const dslBox = $('composer-dsl');
  if (dslBox) {
    state.customDsl = dslBox.value;
  }
  let context;
  try {
    context = JSON.parse(state.customContextText || '{}');
  } catch (error) {
    $('output').textContent = pretty({ status: 'invalid_context', error: error.message });
    return;
  }
  if (!context || Array.isArray(context) || typeof context !== 'object') {
    $('output').textContent = pretty({ status: 'invalid_context', error: 'Context JSON must be an object.' });
    return;
  }

  $('output').textContent = pretty({ status: 'running', graph: 'customLoanPolicy' });
  const outputNode = composerOutputNode();
  const builderDsl = builderToDsl(state.builder);
  const useVisualDraft = state.customDsl === builderDsl || state.customDsl === state.lastGeneratedVisualDsl;
  const response = await fetch(useVisualDraft ? '/api/visual/drafts/run' : '/api/gateway/examples/compose/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(useVisualDraft
      ? {
          draft: builderToVisualDraft(state.builder),
          context,
          outputNode
        }
      : {
          dsl: state.customDsl,
          context,
          outputNode
        })
  });
  const payload = await response.json();
  if (useVisualDraft) {
    const diagnostics = normalizeDiagnostics(payload.diagnostics);
    const ok = Boolean(payload.validated && payload.compiled && payload.success);
    setVisualCheck(
      ok ? 'Run completed.' : (payload.validated === false ? 'Visual validation failed.' : 'Run returned errors.'),
      visualCheckLevel(diagnostics, ok),
      diagnostics
    );
  }
  if (payload.generatedDsl) {
    state.customDsl = payload.generatedDsl;
    state.lastGeneratedVisualDsl = payload.generatedDsl;
    if (dslBox) {
      dslBox.value = payload.generatedDsl;
    }
  }
  if (payload.layout) {
    state.layout = layoutFromBuilder(state.builder);
    renderNodeDetails(selectedBuilderNode() || state.layout.nodes?.[0]);
  }
  if (Object.prototype.hasOwnProperty.call(payload, 'decisionTable')) {
    state.customDecisionTable = payload.decisionTable;
  }
  state.lastPayload = payload.output == null ? null : { data: payload.output, composer: payload };
  $('output').textContent = pretty({ status: response.status, payload });
  renderDecisionTable();
  highlightDecisionRow(state.lastPayload);
  renderDecisionSummary(state.lastPayload);
  renderDiagram();
  if (useVisualDraft) {
    await loadRunHistory();
  }
}

function composerOutputNode() {
  return ensureBuilderOutput(state.builder).nodeId;
}

function highlightDecisionRow(payload) {
  const rows = document.querySelectorAll('[data-rule-id]');
  rows.forEach((row) => row.classList.remove('matched'));
  const ruleId = payloadData(payload)?.policy?.ruleId;
  if (!ruleId) return;
  const matched = document.querySelector(`[data-rule-id="${CSS.escape(String(ruleId))}"]`);
  if (matched) {
    matched.classList.add('matched');
  }
}

function renderDecisionSummary(payload) {
  const section = $('decision-summary-section');
  const target = $('decision-summary');
  const data = payloadData(payload);
  const policy = data?.policy;
  const applicant = data?.applicant;
  if (!policy || !applicant) {
    section.hidden = true;
    target.innerHTML = '';
    return;
  }

  section.hidden = false;
  target.innerHTML = `
    <div class="decision-hero ${escapeHtml(policy.decision)}">
      <span>${escapeHtml(policy.ruleId)}</span>
      <strong>${escapeHtml(policy.decision)}</strong>
    </div>
    <dl class="decision-facts">
      <div><dt>Score</dt><dd>${escapeHtml(applicant.score)}</dd></div>
      <div><dt>Amount</dt><dd>${escapeHtml(data.requestedAmount)}</dd></div>
      <div><dt>Rate</dt><dd>${escapeHtml(policy.rate)}%</dd></div>
      <div><dt>Term</dt><dd>${escapeHtml(policy.maxTerm)} mo</dd></div>
      <div><dt>Lane</dt><dd>${escapeHtml(policy.reviewLane)}</dd></div>
      <div><dt>Segment</dt><dd>${escapeHtml(applicant.segment)}</dd></div>
    </dl>
  `;
}

function streamScenario(url) {
  const frames = { meta: [], token: [], citation: [] };
  state.eventSource = new EventSource(url);
  for (const eventName of Object.keys(frames)) {
    state.eventSource.addEventListener(eventName, (event) => {
      frames[eventName].push(JSON.parse(event.data));
      $('output').textContent = pretty(frames);
    });
  }
  state.eventSource.onerror = () => {
    closeStream();
  };
}

function closeStream() {
  if (state.eventSource) {
    state.eventSource.close();
    state.eventSource = null;
  }
}

async function loadResources() {
  closeStream();
  $('output').textContent = pretty({ status: 'loading resources' });
  const response = await fetch('/admin/resources');
  $('output').textContent = pretty(await response.json());
}

function installComposerDragHandlers() {
  document.addEventListener('pointermove', handleDocumentPointerMove);
  document.addEventListener('pointerup', handleDocumentPointerUp);
  document.addEventListener('pointercancel', () => {
    cancelPaletteDrag();
    state.nodeDrag = null;
  });
}

$('run-scenario').addEventListener('click', runScenario);
$('load-resources').addEventListener('click', loadResources);
installComposerDragHandlers();

loadScenarios().catch((error) => {
  $('output').textContent = pretty({ error: error.message });
});
