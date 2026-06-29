const COMPOSER_GRAPH = '__composer';

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
    baseId: 'fetchApplicant'
  },
  decisionTable: {
    label: 'Decision Table',
    kind: 'decision-table',
    operatorRef: '',
    baseId: 'loanPolicy'
  },
  transform: {
    label: 'Transform',
    kind: 'transform',
    operatorRef: '',
    baseId: 'response'
  }
};

const NODE_SIZE = { width: 184, height: 76 };
const DRAG_START_THRESHOLD = 4;

const state = {
  scenarios: [],
  selected: null,
  layout: null,
  selectedNodeId: null,
  eventSource: null,
  lastPayload: null,
  builder: createDefaultBuilder(),
  draggingOperatorType: null,
  paletteDrag: null,
  nodeDrag: null,
  suppressPaletteClick: false,
  suppressNodeClick: false,
  customDsl: DEFAULT_COMPOSER_DSL,
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
  const response = await fetch('/api/gateway/examples/scenarios');
  state.customContextText = pretty(DEFAULT_COMPOSER_CONTEXT);
  syncComposerFromBuilder({ render: false });
  state.scenarios = [COMPOSER_SCENARIO, ...await response.json()];
  renderScenarioButtons();
  await selectScenario(COMPOSER_GRAPH);
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
      </div>
      <div id="selected-operator-editor" class="builder-panel"></div>
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
    renderSelectedOperatorEditor();
    $('composer-dsl').addEventListener('input', (event) => {
      state.customDsl = event.target.value;
    });
    $('composer-context').addEventListener('input', (event) => {
      state.customContextText = event.target.value;
    });
    $('reset-composer').addEventListener('click', resetComposer);
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
  const rank = { httpResource: 0, decisionTable: 1, transform: 2 };
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
  const add = (source, target, label = '') => {
    if (!source || !target || source === target) return;
    if (edges.some((edge) => edge.source === source && edge.target === target)) return;
    edges.push({ source, target, label });
  };

  const resources = builder.nodes.filter((node) => node.type === 'httpResource');
  const decisions = builder.nodes.filter((node) => node.type === 'decisionTable');
  const transforms = builder.nodes.filter((node) => node.type === 'transform');

  for (const decision of decisions) {
    for (const resource of resources) {
      if ([decision.scoreSource, decision.amountSource].some((source) => String(source).startsWith(`${resource.id}.`))) {
        add(resource.id, decision.id, 'facts');
      }
    }
  }

  for (const transform of transforms) {
    const decision = builder.nodes.find((node) => node.id === transform.policyNode)
      || decisions[0];
    if (decision) {
      add(decision.id, transform.id, 'policy');
    } else {
      const previous = orderedBuilderNodes(builder).filter((node) => node.id !== transform.id).at(-1);
      add(previous?.id, transform.id);
    }
  }

  if (edges.length === 0) {
    const ordered = orderedBuilderNodes(builder);
    for (let i = 0; i < ordered.length - 1; i++) {
      add(ordered[i].id, ordered[i + 1].id);
    }
  }
  return edges;
}

function selectedBuilderNode() {
  return state.builder.nodes.find((node) => node.id === state.builder.selectedId) || null;
}

function payloadData(payload) {
  if (!payload) {
    return null;
  }
  return payload.data ?? payload.output ?? payload;
}

function resetComposer() {
  state.builder = createDefaultBuilder();
  state.customDsl = builderToDsl(state.builder);
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
        <strong>${escapeHtml(OPERATOR_TYPES[node.type]?.label || node.type)}</strong>
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
        ${textField('Applicant Expr', node.applicantExpr, 'applicantExpr')}
      </div>
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
        ${textField('Score Expr', node.scoreSource, 'scoreSource')}
        ${textField('Amount Expr', node.amountSource, 'amountSource')}
      </div>
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
  if (!OPERATOR_TYPES[type]) return;
  const ordered = orderedBuilderNodes();
  const last = ordered[ordered.length - 1];
  const firstDecision = state.builder.nodes.find((node) => node.type === 'decisionTable');
  const fallbackX = type === 'httpResource' && firstDecision ? firstDecision.x - 280 : (last ? last.x + 280 : 80);
  const fallbackY = type === 'httpResource' && firstDecision ? firstDecision.y : (last ? last.y : 210);
  const point = nonOverlappingNodePosition(position?.x ?? fallbackX, position?.y ?? fallbackY);
  const node = createBuilderNode(type, point.x, point.y);
  state.builder.nodes.push(node);
  state.builder.selectedId = node.id;
  state.selectedNodeId = node.id;
  if (type === 'httpResource') {
    applyResourceDefaults(node);
  }
  syncComposerFromBuilder();
  renderInputForm();
  renderDiagram();
  return node;
}

function createBuilderNode(type, x, y) {
  const spec = OPERATOR_TYPES[type];
  const id = uniqueNodeId(spec.baseId);
  const base = {
    id,
    type,
    x: Math.max(40, Math.round(x)),
    y: Math.max(80, Math.round(y))
  };
  if (type === 'httpResource') {
    return {
      ...base,
      resourceId: 'loan-applicant-service.getProfile',
      applicantExpr: 'ctx.applicantId'
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
  return {
    ...base,
    policyNode: firstDecisionTableId()
  };
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
    decisionNode.scoreSource = `${resourceNode.id}.output.payload.score`;
    decisionNode.amountSource = 'ctx.requestedAmount';
  }
  let context;
  try {
    context = JSON.parse(state.customContextText || '{}');
  } catch {
    context = {};
  }
  context.applicantId = context.applicantId || 'prime';
  context.requestedAmount = context.requestedAmount || 450000;
  state.customContextText = pretty(context);
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
    const spec = OPERATOR_TYPES[node.type] || OPERATOR_TYPES.transform;
    return {
      id: node.id,
      kind: spec.kind,
      operatorRef: spec.operatorRef,
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
    id: `${edge.source}->${edge.target}:`,
    source: edge.source,
    target: edge.target,
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
    return `  node ${node.id} : httpResource {\n    input {\n      resourceId = ${quote(node.resourceId)}\n      params = { applicantId: ${node.applicantExpr || 'ctx.applicantId'} }\n    }\n    timeout = 3s\n    retry = { attempts: 1, backoff: 200ms }\n  }`;
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
  return '';
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
    return 'HTTP Resource';
  }
  if (node.type === 'decisionTable') {
    return 'Decision Table';
  }
  if (node.type === 'transform') {
    return 'Transform';
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
    const x1 = source.position.x + source.size.width;
    const y1 = source.position.y + source.size.height / 2;
    const x2 = target.position.x;
    const y2 = target.position.y + target.size.height / 2;
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
    svg.appendChild(group);
  }
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
}

function handleDocumentPointerUp(event) {
  if (state.paletteDrag) {
    finishPaletteDrag(event);
  }
  if (state.nodeDrag) {
    finishNodeDrag(event);
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
  const response = await fetch('/api/gateway/examples/compose/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      dsl: state.customDsl,
      context,
      outputNode
    })
  });
  const payload = await response.json();
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
