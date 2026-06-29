const COMPOSER_GRAPH = '__composer';
const CONTEXT_SOURCE_ID = '__ctx';

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
  libraryId: 'risk-policy',
  displayName: 'Risk policy operators',
  version: '1.0.0',
  owner: 'risk-team',
  operators: [
    {
      operatorRef: 'risk:eligibility',
      display: {
        name: 'Eligibility',
        description: 'Evaluates a reusable eligibility predicate.',
        tags: ['risk', 'policy']
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

const NODE_SIZE = { width: 184, height: 76 };
const DRAG_START_THRESHOLD = 4;

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
  draftMessage: null,
  visualCheck: {
    message: 'Not checked',
    level: 'info',
    diagnostics: []
  },
  operatorLibraries: [],
  selectedLibraryId: '',
  libraryImportText: pretty(SAMPLE_OPERATOR_LIBRARY),
  libraryMessage: null,
  draggingOperatorType: null,
  paletteDrag: null,
  nodeDrag: null,
  connectionDrag: null,
  connectionMessage: null,
  suppressPaletteClick: false,
  suppressNodeClick: false,
  customDsl: DEFAULT_COMPOSER_DSL,
  lastGeneratedVisualDsl: '',
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

async function loadScenarios() {
  await loadVisualOperatorCatalog();
  await loadOperatorLibraries({ render: false });
  await loadDraftList({ render: false });
  const response = await fetch('/api/gateway/examples/scenarios');
  state.customContextText = pretty(DEFAULT_COMPOSER_CONTEXT);
  syncComposerFromBuilder({ render: false });
  state.scenarios = [COMPOSER_SCENARIO, ...await response.json()];
  renderScenarioButtons();
  await selectScenario(COMPOSER_GRAPH);
}

async function loadVisualOperatorCatalog() {
  try {
    resetDynamicOperatorTypes();
    const response = await fetch('/api/visual/operators');
    if (!response.ok) {
      return;
    }
    const payload = await response.json();
    const operators = Array.isArray(payload.operators) ? payload.operators : [];
    state.visualOperators = operators;
    for (const operator of operators) {
      const operatorRef = operator.operatorRef || '';
      if (!operatorRef || operatorRef === 'httpResource' || operatorRef === 'bloge:decisionTable' || operatorRef === 'bloge:transform') {
        continue;
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
          inputPort: primaryInput.name,
          outputPort: primaryOutput.name,
          baseId: baseIdForResource(operatorRef),
          inputPorts,
          outputPorts,
          inputSchema: primaryInput.schema,
          outputSchema: primaryOutput.schema,
          configSchema: operator.configSchema,
          lowering: operator.lowering
        };
        continue;
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
        inputPort: primaryInput.name,
        outputPort: primaryOutput.name,
        baseId: baseIdForResource(resourceId),
        resourceId,
        inputPorts,
        outputPorts,
        inputSchema: primaryInput.schema,
        outputSchema: primaryOutput.schema,
        lowering: operator.lowering
      };
    }
  } catch (error) {
    console.debug('Visual operator catalog unavailable', error);
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
  if (Array.isArray(spec?.inputPorts) && spec.inputPorts.length) {
    return spec.inputPorts;
  }
  return [{ name: spec?.inputPort || 'inputs', schema: spec?.inputSchema || null, required: true }];
}

function outputPortsForSpec(spec) {
  if (Array.isArray(spec?.outputPorts) && spec.outputPorts.length) {
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
        <div class="panel-title">Operator Palette</div>
        <div id="operator-palette" class="operator-palette"></div>
        <div id="connection-status" class="connection-status" hidden></div>
      </div>
      <div class="builder-panel">
        <div class="panel-title">Operator Libraries</div>
        <div class="library-controls">
          <select id="library-select" aria-label="Imported operator libraries"></select>
          <button id="import-library" class="secondary compact" type="button">Import</button>
          <button id="reload-libraries" class="secondary compact" type="button">Reload</button>
          <button id="delete-library" class="secondary compact danger" type="button">Delete</button>
        </div>
        <textarea id="operator-library-json" class="library-editor" spellcheck="false"></textarea>
        <div id="library-status" class="library-status" hidden></div>
      </div>
      <div class="builder-panel">
        <div class="panel-title">Drafts</div>
        <div class="draft-controls">
          <select id="draft-select" aria-label="Stored graph drafts"></select>
          <button id="save-draft" class="secondary compact" type="button">Save</button>
          <button id="load-draft" class="secondary compact" type="button">Load</button>
          <button id="delete-draft" class="secondary compact danger" type="button">Delete</button>
        </div>
        <div id="draft-status" class="draft-status" hidden></div>
      </div>
      <div id="selected-operator-editor" class="builder-panel"></div>
      <div class="builder-panel">
        <div class="panel-title">Server Check</div>
        <div class="visual-check-actions">
          <button id="validate-visual-draft" class="secondary compact" type="button">Validate</button>
          <button id="compile-visual-draft" class="secondary compact" type="button">Compile</button>
        </div>
        <div id="visual-check-status" class="visual-check-status"></div>
        <div id="visual-diagnostics" class="visual-diagnostics"></div>
      </div>
      <div class="field">
        <label for="composer-dsl">DSL Preview</label>
        <textarea id="composer-dsl" class="code-editor" spellcheck="false"></textarea>
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
    $('composer-context').value = state.customContextText;
    renderOperatorPalette();
    renderConnectionStatus();
    renderOperatorLibraryControls();
    renderDraftControls();
    renderSelectedOperatorEditor();
    renderVisualCheck();
    $('composer-dsl').addEventListener('input', (event) => {
      state.customDsl = event.target.value;
    });
    $('composer-context').addEventListener('input', (event) => {
      state.customContextText = event.target.value;
      renderSelectedOperatorEditor();
    });
    $('reset-composer').addEventListener('click', resetComposer);
    $('validate-visual-draft').addEventListener('click', validateVisualDraft);
    $('compile-visual-draft').addEventListener('click', compileVisualDraft);
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
    selectedId: 'loanPolicy',
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

function builderEdges(builder = state.builder) {
  const edges = [];
  const add = (edge) => {
    if (!edge.source || !edge.target || edge.source === edge.target) return;
    const key = [
      edge.source,
      edge.sourcePort || '',
      edge.sourcePath || '',
      edge.target,
      edge.targetPort || '',
      edge.targetPath || ''
    ].join(':');
    if (edges.some((item) => item.key === key)) return;
    edges.push({ key, label: '', ...edge });
  };

  const decisions = builder.nodes.filter((node) => node.type === 'decisionTable');
  const transforms = builder.nodes.filter((node) => node.type === 'transform');

  for (const transform of transforms) {
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
  }

  if (edges.length === 0) {
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
      targetPort: node.customInputPorts?.[inputName]
        || inputPortForInputPath(spec, node.customInputPaths?.[inputName] || inputName),
      targetPath: node.customInputPaths?.[inputName] || inputName,
      label: inputName
    }));
  }
  return [];
}

function connectionSourceFromExpression(expression, builder = state.builder) {
  const value = String(expression || '').trim();
  const contextMatch = value.match(/^ctx(?:\.(.+))?$/);
  if (contextMatch) {
    return contextSourceForPath(contextMatch[1] || '', builder);
  }
  const outputMatch = value.match(/^([A-Za-z_][A-Za-z0-9_]*)\.output(?:\..+)?$/);
  if (outputMatch) {
    const sourceNode = builder.nodes.find((node) => node.id === outputMatch[1]);
    if (sourceNode) {
      const handle = sourceHandlesForNode(sourceNode).find((candidate) =>
        expressionForConnectionSource(candidate) === value
      );
      if (handle) {
        return {
          nodeId: handle.nodeId,
          port: handle.port,
          path: handle.path || ''
        };
      }
    }
  }
  const match = value.match(/^([A-Za-z_][A-Za-z0-9_]*)\.output(?:\.(payload))?(?:\.(.+))?$/);
  if (!match) {
    return null;
  }
  return {
    nodeId: match[1],
    port: match[2] ? 'payload' : 'output',
    path: match[3] || ''
  };
}

function selectedBuilderNode() {
  return state.builder.nodes.find((node) => node.id === state.builder.selectedId) || null;
}

function specForNode(node) {
  return OPERATOR_TYPES[node.paletteType] || OPERATOR_TYPES[node.type] || OPERATOR_TYPES.transform;
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
  state.draftMessage = null;
  state.visualCheck = { message: 'Not checked', level: 'info', diagnostics: [] };
  state.customDsl = builderToDsl(state.builder);
  state.lastGeneratedVisualDsl = '';
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
  target.innerHTML = Object.entries(OPERATOR_TYPES).map(([type, spec]) => `
    <button
      class="operator-card ${escapeHtml(spec.kind)}"
      type="button"
      data-operator-type="${escapeHtml(type)}"
      data-testid="operator-${escapeHtml(type)}">
      <strong>${escapeHtml(spec.label)}</strong>
      <span>${escapeHtml(spec.kind)}</span>
    </button>
  `).join('');
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
    renderOperatorLibraryControls();
  };
  editor.oninput = () => {
    state.libraryImportText = editor.value;
  };

  const importButton = $('import-library');
  const reloadButton = $('reload-libraries');
  const deleteButton = $('delete-library');
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
}

function setLibraryMessage(text, level = 'info') {
  state.libraryMessage = text ? { text, level } : null;
  renderLibraryStatus();
}

function renderVisualCheck() {
  const status = $('visual-check-status');
  const list = $('visual-diagnostics');
  if (!status || !list) return;
  const check = state.visualCheck || {};
  status.textContent = check.message || 'Not checked';
  status.className = `visual-check-status ${check.level || 'info'}`;
  const diagnostics = check.diagnostics || [];
  if (!diagnostics.length) {
    list.innerHTML = '';
    list.hidden = true;
    return;
  }
  list.hidden = false;
  list.innerHTML = diagnostics.map((diagnostic) => {
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

function visualCheckLevel(diagnostics, success = true) {
  if (diagnostics.some((diagnostic) => String(diagnostic.level || '').toUpperCase() === 'ERROR')) {
    return 'error';
  }
  if (diagnostics.some((diagnostic) => String(diagnostic.level || '').toUpperCase() === 'WARNING')) {
    return 'warning';
  }
  return success ? 'success' : 'error';
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
  const response = await fetch('/admin/visual-operator-libraries', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(library)
  });
  const text = await response.text();
  if (!response.ok) {
    setLibraryMessage(text || `Import failed with ${response.status}`, 'error');
    return;
  }
  const stored = JSON.parse(text);
  state.selectedLibraryId = stored.libraryId;
  state.libraryImportText = pretty(stored);
  await loadOperatorLibraries({ render: false });
  await loadVisualOperatorCatalog();
  renderOperatorPalette();
  renderOperatorLibraryControls();
  setLibraryMessage(`Imported ${stored.libraryId}.`, 'success');
}

async function deleteSelectedOperatorLibrary() {
  if (!state.selectedLibraryId || !confirm(`Delete operator library ${state.selectedLibraryId}?`)) return;
  const deletedId = state.selectedLibraryId;
  const response = await fetch(`/admin/visual-operator-libraries/${encodeURIComponent(deletedId)}`, {
    method: 'DELETE'
  });
  if (!response.ok) {
    setLibraryMessage(`Delete failed with ${response.status}`, 'error');
    return;
  }
  state.selectedLibraryId = '';
  state.libraryImportText = pretty(SAMPLE_OPERATOR_LIBRARY);
  await loadOperatorLibraries({ render: false });
  await loadVisualOperatorCatalog();
  renderOperatorPalette();
  renderOperatorLibraryControls();
  setLibraryMessage(`Deleted ${deletedId}.`, 'success');
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
  select.addEventListener('change', () => {
    state.currentDraftId = select.value;
    const draft = state.drafts.find((item) => item.draftId === select.value);
    state.currentDraftRevision = draft?.revision || 0;
    state.draftMessage = null;
    renderDraftControls();
  });

  const saveButton = $('save-draft');
  const loadButton = $('load-draft');
  const deleteButton = $('delete-draft');
  if (saveButton) {
    saveButton.addEventListener('click', saveCurrentDraft);
  }
  if (loadButton) {
    loadButton.disabled = !state.currentDraftId;
    loadButton.addEventListener('click', loadSelectedDraft);
  }
  if (deleteButton) {
    deleteButton.disabled = !state.currentDraftId;
    deleteButton.addEventListener('click', deleteSelectedDraft);
  }
  renderDraftStatus();
}

function renderDraftStatus() {
  const target = $('draft-status');
  if (!target) return;
  const current = state.currentDraftId
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
    }
    if (options.render !== false) {
      renderDraftControls();
    }
  } catch (error) {
    setDraftMessage(error.message, 'error');
  }
}

async function saveCurrentDraft() {
  const draft = builderToVisualDraft(state.builder);
  const draftId = state.currentDraftId;
  const response = await fetch(draftId ? `/api/visual/drafts/${encodeURIComponent(draftId)}` : '/api/visual/drafts', {
    method: draftId ? 'PUT' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(draft)
  });
  if (!response.ok) {
    setDraftMessage(`Save failed with ${response.status}`, 'error');
    return;
  }
  const stored = await response.json();
  state.currentDraftId = stored.draftId || '';
  state.currentDraftRevision = stored.revision || 0;
  setDraftMessage(`Saved ${state.currentDraftId}@${state.currentDraftRevision}.`, 'success');
  await loadDraftList();
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
  state.currentDraftId = draft.draftId || '';
  state.currentDraftRevision = draft.revision || 0;
  state.lastPayload = null;
  state.lastGeneratedVisualDsl = '';
  syncComposerFromBuilder({ render: false });
  setDraftMessage(`Loaded ${state.currentDraftId}@${state.currentDraftRevision}.`, 'success');
  renderScenario();
}

async function deleteSelectedDraft() {
  if (!state.currentDraftId || !confirm(`Delete draft ${state.currentDraftId}?`)) return;
  const deletedId = state.currentDraftId;
  const response = await fetch(`/api/visual/drafts/${encodeURIComponent(deletedId)}`, { method: 'DELETE' });
  if (!response.ok) {
    setDraftMessage(`Delete failed with ${response.status}`, 'error');
    return;
  }
  state.currentDraftId = '';
  state.currentDraftRevision = 0;
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

  for (const input of target.querySelectorAll('[data-config-field]')) {
    const eventName = input.type === 'checkbox' || input.tagName === 'SELECT' ? 'change' : 'input';
    input.addEventListener(eventName, () => {
      setConfigValueFromInput(node, input);
      updateConfigFieldStatus(node, input);
      syncComposerFromBuilder({ render: false });
      renderDiagram();
    });
  }

  for (const select of target.querySelectorAll('[data-binding-source]')) {
    select.addEventListener('change', () => {
      const source = sourceFromBindingValue(select.value);
      const bindingTarget = bindingTargetFromElement(select);
      if (!source || !bindingTarget) return;
      applyConnection(source, bindingTarget);
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

function operatorEditorBody(node) {
  if (node.type === 'httpResource') {
    return `
      <div class="operator-fields">
        ${textField('Node', node.id, '', true)}
        ${textField('Resource ID', node.resourceId, 'resourceId')}
      </div>
      ${renderConfigPanel(node)}
      ${renderInputBindingsPanel(node)}
    `;
  }
  if (node.type === 'customOperator') {
    const spec = specForNode(node);
    return `
      <div class="operator-fields">
        ${textField('Node', node.id, '', true)}
        ${textField('Operator', spec.visualOperatorRef || node.paletteType, '', true)}
      </div>
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
      ${textField('Policy Node', node.policyNode || firstDecisionTableId(), 'policyNode')}
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
  const value = node.config?.[field.path];
  const type = rawSchemaType(field.schema);
  const attr = `data-config-field="${escapeHtml(field.path)}"`;
  const values = Array.isArray(field.schema?.values) ? field.schema.values : [];
  if (type === 'enum' && values.length) {
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

function unknownConfigRows(node, spec) {
  const schema = spec.configSchema?.schema || {};
  if (schema.additionalProperties !== false) {
    return [];
  }
  const declared = new Set(Object.keys(schema.properties || {}));
  return Object.keys(node.config || {})
    .filter((key) => !declared.has(key))
    .map((key) => `
      <div class="binding-row error">
        <div class="binding-row-head">
          <div>
            <strong>${escapeHtml(readableName(key))}</strong>
            <span>unknown · config</span>
          </div>
        </div>
        <div class="binding-status">Not declared by configSchema.</div>
      </div>
    `);
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
      node.config[field.path] = '';
    } else {
      delete node.config[field.path];
    }
    return;
  }
  node.config[field.path] = parseConfigInputValue(input, field.schema);
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
  if (input.type === 'checkbox' || type === 'boolean') {
    return Boolean(input.checked);
  }
  if (type === 'enum' && Array.isArray(schema?.values)) {
    return schema.values.find((item) => String(item) === input.value) ?? input.value;
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
  const hasValue = Object.prototype.hasOwnProperty.call(node.config || {}, field.path);
  const value = node.config?.[field.path];
  if (!hasValue || value === null || value === '') {
    return field.required
      ? { level: 'error', message: 'Required config is missing.' }
      : { level: 'info', message: 'Optional config is empty.' };
  }
  if (!configValueMatchesSchema(value, field.schema)) {
    return { level: 'error', message: `Expected ${schemaType(field.schema) || 'schema-compatible'} value.` };
  }
  return { level: 'success', message: 'Config matches configSchema.' };
}

function configValueMatchesSchema(value, schema) {
  const type = rawSchemaType(schema);
  if (!type || type === 'any' || type === 'opaque') return true;
  if (type === 'enum') {
    return Array.isArray(schema?.values) && schema.values.some((item) => item === value || String(item) === String(value));
  }
  if (type === 'boolean') return typeof value === 'boolean';
  if (type === 'integer') return Number.isInteger(Number(value));
  if (type === 'number' || type === 'decimal') return Number.isFinite(Number(value));
  if (type === 'object') return value !== null && typeof value === 'object' && !Array.isArray(value);
  if (type === 'array') return Array.isArray(value);
  if (type === 'null') return value === null;
  return typeof value === 'string';
}

function renderInputBindingsPanel(node) {
  const targets = targetHandlesForNode(node);
  if (!targets.length) {
    return '';
  }
  return `
    <div class="binding-panel">
      <div class="binding-panel-title">
        <span>Input Bindings</span>
        <small>schema checked</small>
      </div>
      ${targets.map((target) => renderInputBindingRow(node, target)).join('')}
    </div>
  `;
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
  for (const field of configFieldDescriptors(spec.configSchema)) {
    if (Object.prototype.hasOwnProperty.call(field.schema || {}, 'default')) {
      config[field.path] = field.schema.default;
    }
  }
  return config;
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
    return node.policyNode ? `${node.policyNode}.output` : '';
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
    node.policyNode = source?.nodeId || node.policyNode;
  }
}

function requiredInputNamesForPort(port) {
  const required = port?.schema?.schema?.required;
  return Array.isArray(required) ? required : [];
}

function configFieldDescriptors(configSchema) {
  const schema = configSchema?.schema || {};
  const properties = schema.properties || {};
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
  return Object.entries(properties).map(([name, childSchema]) => ({
    path: name,
    schema: childSchema && typeof childSchema === 'object' ? childSchema : {},
    required: required.has(name)
  }));
}

function schemaProperties(schemaEnvelope) {
  return schemaEnvelope?.schema?.properties || {};
}

function schemaFieldDescriptors(schemaEnvelope) {
  return schemaFieldsFromSchema(schemaEnvelope?.schema || {}, '', true);
}

function schemaDefaultInputFields(schemaEnvelope) {
  const fields = schemaFieldDescriptors(schemaEnvelope);
  const leafFields = fields.filter((field) => !hasSchemaProperties(field.schema));
  const preferred = leafFields.length ? leafFields : fields;
  const required = preferred.filter((field) => field.required);
  return required.length ? required : preferred;
}

function schemaFieldsFromSchema(schema, prefix, parentRequired) {
  const properties = schema?.properties || {};
  const required = new Set(Array.isArray(schema?.required) ? schema.required.map(String) : []);
  return Object.entries(properties).flatMap(([name, childSchema]) => {
    const path = prefix ? `${prefix}.${name}` : name;
    const normalizedSchema = childSchema && typeof childSchema === 'object' ? childSchema : {};
    const fieldRequired = parentRequired && required.has(name);
    const hasNestedRequired = Array.isArray(normalizedSchema.required) && normalizedSchema.required.length > 0;
    return [
      { path, schema: normalizedSchema, required: fieldRequired && !hasNestedRequired },
      ...schemaFieldsFromSchema(normalizedSchema, path, fieldRequired)
    ];
  });
}

function hasSchemaProperties(schema) {
  return Boolean(schema?.properties && Object.keys(schema.properties).length);
}

function schemaDeclaresPath(schemaEnvelope, path) {
  if (!path) {
    return true;
  }
  let current = schemaEnvelope?.schema || {};
  for (const segment of String(path).split('.')) {
    if (!segment) continue;
    const properties = current.properties || {};
    if (!Object.prototype.hasOwnProperty.call(properties, segment)) {
      return false;
    }
    current = properties[segment] || {};
  }
  return true;
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

function syncComposerFromBuilder(options = {}) {
  const render = options.render !== false;
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
  if (render && isComposerSelected()) {
    renderDecisionTable();
    renderNodeDetails(selectedBuilderNode() || state.layout.nodes[0]);
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
  const edges = builderEdges(builder).map((edge) => ({
    id: `${edge.source}:${edge.sourcePort || ''}.${edge.sourcePath || ''}->${edge.target}:${edge.targetPort || ''}.${edge.targetPath || ''}`,
    source: edge.source,
    target: edge.target,
    sourcePort: edge.sourcePort || '',
    sourcePath: edge.sourcePath || '',
    targetPort: edge.targetPort || '',
    targetPath: edge.targetPath || '',
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
  if (node.type === 'httpResource') {
    const params = resourceParamInputs(node, specForNode(node));
    const paramBody = Object.entries(params)
      .map(([name, expression]) => `${name}: ${expression || 'null'}`)
      .join(', ');
    return `  node ${node.id} : httpResource {\n    input {\n      resourceId = ${quote(node.resourceId)}\n      params = { ${paramBody} }\n    }\n    timeout = 3s\n    retry = { attempts: 1, backoff: 200ms }\n  }`;
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
    const decisionNode = builder.nodes.find((item) => item.id === node.policyNode)
      || builder.nodes.find((item) => item.type === 'decisionTable');
    const resourceNode = builder.nodes.find((item) => item.type === 'httpResource');
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
    return customNodeToDsl(node);
  }
  return '';
}

function customNodeToDsl(node) {
  const spec = specForNode(node);
  const inputs = customInputTemplateValues(node);
  if (spec.lowering?.mode === 'transform' && spec.lowering?.parameters?.assignments) {
    const assignments = Object.entries(spec.lowering.parameters.assignments).map(([key, template]) =>
      `    ${key} = ${renderTemplateExpression(String(template), inputs)}`
    ).join('\n');
    return `  transform ${node.id} {\n${assignments || '    result = {}'}\n  }`;
  }
  const executable = spec.lowering?.operatorRef || spec.operatorRef || spec.visualOperatorRef || node.paletteType;
  const inputLines = customDslInputEntries(node).map(([key, expression]) =>
    `      ${key} = ${expression || 'null'}`
  ).join('\n');
  return `  node ${node.id} : ${executable} {\n    input {\n${inputLines}\n    }\n  }`;
}

function customInputTemplateValues(node) {
  const values = {};
  for (const [key, expression] of Object.entries(node.customInputs || {})) {
    const targetPath = node.customInputPaths?.[key] || key;
    const targetPort = node.customInputPorts?.[key] || '';
    values[targetPath] = expression;
    if (targetPort) {
      values[`${targetPort}.${targetPath}`] = expression;
    }
    if (key !== targetPath) {
      values[key] = expression;
    }
  }
  return values;
}

function customDslInputEntries(node) {
  return Object.entries(node.customInputs || {}).map(([key, expression]) => [
    node.customInputPaths?.[key] || key,
    expression
  ]);
}

function renderTemplateExpression(template, inputs) {
  let expression = template;
  for (const [name, value] of Object.entries(inputs || {})) {
    expression = expression
      .replaceAll(`{{input.${name}}}`, value || 'null')
      .replaceAll(`{{${name}}}`, value || 'null');
  }
  return expression;
}

function builderToVisualDraft(builder = state.builder) {
  const layout = layoutFromBuilder(builder);
  return {
    schemaVersion: 'bloge.visualGraphDraft.v1',
    draftId: state.currentDraftId || '',
    revision: state.currentDraftRevision || 0,
    graphName: builder.graphName,
    tenantId: 'demo-tenant',
    namespace: 'local',
    environment: 'browser',
    status: 'DRAFT',
    inputSchema: currentGraphInputSchema(builder),
    nodes: builder.nodes.map((node) => builderNodeToDraftNode(node, builder)),
    edges: builderEdges(builder).map((edge) => ({
      id: `${edge.source}:${edge.sourcePort || ''}.${edge.sourcePath || ''}->${edge.target}:${edge.targetPort || ''}.${edge.targetPath || ''}`,
      kind: 'data',
      source: { nodeId: edge.source, port: edge.sourcePort || 'output', path: edge.sourcePath || '' },
      target: { nodeId: edge.target, port: edge.targetPort || 'inputs', path: edge.targetPath || '' }
    })),
    visualLayout: layout,
    output: { nodeId: composerOutputNode(), path: '' }
  };
}

function builderFromVisualDraft(draft) {
  const layoutNodes = Object.fromEntries((draft.visualLayout?.nodes || [])
    .map((node) => [node.id, node]));
  const nodes = (draft.nodes || []).map((node) => builderNodeFromDraftNode(node, draft, layoutNodes));
  const selectedId = nodes[0]?.id || null;
  return {
    graphName: draft.graphName || 'visualGraph',
    inputSchema: draft.inputSchema || null,
    selectedId,
    nodes
  };
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
    return {
      ...base,
      type: 'transform',
      paletteType: '',
      policyNode: policyNodeFromDraft(node, draft) || firstDecisionTableIdFromNodes(draft.nodes || [])
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
      }), binding.targetPath || key)])),
    customInputPaths: Object.fromEntries(Object.entries(node.inputs || {})
      .map(([key, binding]) => [key, binding.targetPath || key]))
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
    return `ctx.${binding.path || ''}`;
  }
  if (binding.kind === 'nodePath') {
    const source = (draft.nodes || []).find((node) => node.id === binding.nodeId);
    const sourcePort = binding.sourcePort || (source?.operatorRef?.startsWith('resource:') ? 'payload' : 'output');
    const payloadSegment = sourcePort && sourcePort !== 'output' ? `.${sourcePort}` : '';
    const pathSegment = binding.path ? `.${binding.path}` : '';
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
  const edge = (draft.edges || []).find((item) => item.target?.nodeId === node.id);
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
                targetPort: node.customInputPorts?.[key]
                  || inputPortForInputPath(specForNode(node), node.customInputPaths?.[key] || key),
                targetPath: node.customInputPaths?.[key] || key,
                builder
              })])),
      config: { ...(node.config || {}) },
      position: { x: node.x, y: node.y }
    };
  }
  const decisionNode = builder.nodes.find((item) => item.id === node.policyNode)
    || builder.nodes.find((item) => item.type === 'decisionTable');
  const resourceNode = builder.nodes.find((item) => item.type === 'httpResource');
  const previous = orderedBuilderNodes(builder).filter((item) => item.id !== node.id).at(-1);
  const assignments = decisionNode
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
  if (value.startsWith('ctx.')) {
    return withTargetPort({ kind: 'contextPath', path: value.slice(4) });
  }
  const source = connectionSourceFromExpression(value, options.builder || state.builder);
  if (source) {
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
    path.setAttribute('class', `edge ${executed ? 'executed' : ''}`);
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
  const targetHandles = targetHandlesForNode(builderNode);

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
  title.textContent = `${node.id}.${handle.path || handle.port}${type}`;
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
  const handles = builderNode ? targetHandlesForNode(builderNode) : [];
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
  if (node.type === 'decisionTable') {
    return ['decision', 'rate', 'maxTerm', 'reviewLane', 'ruleId'].map((path) => ({
      nodeId: node.id,
      port: spec.outputPort || 'output',
      path,
      type: path === 'rate' ? 'number' : (path === 'maxTerm' ? 'integer' : 'string')
    }));
  }
  return outputPortsForSpec(spec).flatMap((port) => {
    const fields = schemaFieldDescriptors(port.schema);
    if (!fields.length) {
      return [{
        nodeId: node.id,
        port: port.name || spec.outputPort || 'output',
        path: '',
        type: schemaType(port.schema?.schema),
        schema: port.schema?.schema || {}
      }];
    }
    return fields.map((field) => ({
      nodeId: node.id,
      port: port.name || spec.outputPort || 'output',
      path: field.path,
      type: schemaType(field.schema),
      schema: field.schema
    }));
  });
}

function targetHandlesForNode(node) {
  const spec = specForNode(node);
  if (node.type === 'httpResource') {
    return inputPortsForSpec(spec).flatMap((port) => {
      const fields = schemaFieldDescriptors(port.schema);
      const targets = fields.length
        ? fields
        : Object.keys(resourceParamInputs(node, spec)).map((path) => ({
          path,
          schema: schemaAtPath(port.schema, path) || {},
          required: requiredInputNamesForPort(port).includes(path)
        }));
      return targets.map((field) => ({
        nodeId: node.id,
        port: port.name || spec.inputPort || 'params',
        key: field.path,
        path: field.path,
        type: schemaType(field.schema),
        schema: field.schema,
        required: field.required
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
      const fields = schemaFieldDescriptors(port.schema);
      const targets = fields.length
        ? fields
        : Object.keys(node.customInputs || {}).map((path) => ({
          path,
          schema: schemaAtPath(port.schema, path) || {},
          required: requiredInputNamesForPort(port).includes(path)
        }));
      return targets.map((field) => ({
        nodeId: node.id,
        port: port.name || spec.inputPort || 'inputs',
        key: inputKeyForPortPath(spec, port.name || spec.inputPort || 'inputs', field.path),
        path: field.path,
        type: schemaType(field.schema),
        schema: field.schema,
        required: field.required
      }));
    });
  }
  return [{
    nodeId: node.id,
    port: spec.inputPort || 'inputs',
    path: 'input',
    type: ''
  }];
}

function schemaType(schema) {
  const type = rawSchemaType(schema);
  if (type === 'array') {
    const itemType = schemaType(schema?.items);
    return itemType ? `array<${itemType}>` : 'array';
  }
  return type ? String(type) : '';
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
  return `${endpoint.nodeId}.${endpoint.path || endpoint.port}`;
}

function schemaAtPath(schemaEnvelope, path) {
  if (!path) {
    return schemaEnvelope?.schema || { type: 'object' };
  }
  let current = schemaEnvelope?.schema || {};
  for (const segment of String(path).split('.')) {
    if (!segment) continue;
    const properties = current.properties || {};
    if (!Object.prototype.hasOwnProperty.call(properties, segment)) {
      return allowsAdditionalProperties(current) ? {} : null;
    }
    current = properties[segment] || {};
  }
  return current;
}

function allowsAdditionalProperties(schema) {
  return schema?.additionalProperties === true
    || (schema?.additionalProperties && typeof schema.additionalProperties === 'object');
}

function currentGraphInputSchema(builder = state.builder) {
  return builder?.inputSchema || schemaEnvelopeFromContextText(state.customContextText);
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
  return schemaFieldDescriptors(inputSchema).map((field) => ({
    nodeId: CONTEXT_SOURCE_ID,
    port: 'ctx',
    path: field.path,
    type: schemaType(field.schema),
    schema: field.schema
  }));
}

function contextSourceForPath(path, builder = state.builder) {
  const schema = schemaAtPath(currentGraphInputSchema(builder), path);
  return {
    nodeId: CONTEXT_SOURCE_ID,
    port: 'ctx',
    path: path || '',
    type: schemaType(schema),
    schema
  };
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
  if (!schemasCompatible(sourceSchema, targetSchema)) {
    return { ok: false, message: `Type mismatch: ${sourceType} cannot feed ${targetType}.` };
  }
  return { ok: true, message: '' };
}

function schemasCompatible(sourceSchema, targetSchema) {
  const sourceType = rawSchemaType(sourceSchema);
  const targetType = rawSchemaType(targetSchema);
  if (!sourceType || !targetType || sourceType === 'any' || targetType === 'any' || sourceType === 'opaque' || targetType === 'opaque') {
    return true;
  }
  if (sourceType === 'array' && targetType === 'array') {
    return !sourceSchema?.items || !targetSchema?.items || schemasCompatible(sourceSchema.items, targetSchema.items);
  }
  return sourceType === targetType || (numericType(sourceType) && numericType(targetType));
}

function rawSchemaType(schema) {
  if (!schema) return '';
  return schema.kind || schema.type || (schema.properties ? 'object' : (schema.items ? 'array' : ''));
}

function numericType(type) {
  return type === 'number' || type === 'integer' || type === 'decimal';
}

function wouldCreateCycle(sourceId, targetId) {
  if (!sourceId || !targetId || sourceId === targetId) {
    return true;
  }
  const outgoing = new Map();
  for (const node of state.builder.nodes) {
    outgoing.set(node.id, []);
  }
  for (const edge of builderEdges()) {
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
      ? `${drag.source.nodeId}.${drag.source.path || drag.source.port} -> ${target.nodeId}.${target.path || target.port}`
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
  const target = connectionTargetAtPoint(event);
  if (target) {
    const compatibility = connectionCompatibility(drag.source, target);
    if (compatibility.ok) {
      applyConnection(drag.source, target);
      setConnectionMessage(
        `Connected ${drag.source.nodeId}.${drag.source.path || drag.source.port} -> ${target.nodeId}.${target.path || target.port}.`,
        'success'
      );
    } else {
      setConnectionMessage(compatibility.message, 'error');
    }
  }
  state.connectionDrag = null;
  document.body.classList.remove('connecting-edge');
  renderDiagram();
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
  const candidate = targetHandlesForNode(node).find((item) =>
    item.port === handle.dataset.port && (item.path || '') === (handle.dataset.path || '')
  );
  return candidate || null;
}

function applyConnection(source, target) {
  const node = state.builder.nodes.find((item) => item.id === target.nodeId);
  if (!node) return;
  const expression = expressionForConnectionSource(source);
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
    node.policyNode = source.nodeId;
  }
  state.builder.selectedId = node.id;
  state.selectedNodeId = node.id;
  syncComposerFromBuilder({ render: false });
  renderInputForm();
}

function expressionForConnectionSource(source) {
  if (source.nodeId === CONTEXT_SOURCE_ID) {
    return source.path ? `ctx.${source.path}` : 'ctx';
  }
  const payloadSegment = source.port && source.port !== 'output' ? `.${source.port}` : '';
  const pathSegment = source.path ? `.${source.path}` : '';
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
}

function composerOutputNode() {
  const ordered = orderedBuilderNodes();
  const lastTransform = [...ordered].reverse().find((node) => node.type === 'transform');
  return (lastTransform || ordered[ordered.length - 1])?.id || 'response';
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
