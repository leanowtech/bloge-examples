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

const COMPOSER_LAYOUT = {
  schemaVersion: 'bloge.visualLayout.v1',
  rootId: 'customLoanPolicy',
  executionMode: 'GRAPH',
  nodes: [
    {
      id: 'loanPolicy',
      kind: 'decision-table',
      operatorRef: '',
      label: 'Loan Policy',
      position: { x: 80, y: 210 },
      size: { width: 184, height: 76 },
      group: null,
      annotations: { kind: 'decision-table' }
    },
    {
      id: 'response',
      kind: 'transform',
      operatorRef: '',
      label: 'Response',
      position: { x: 360, y: 210 },
      size: { width: 184, height: 76 },
      group: null,
      annotations: { kind: 'transform' }
    }
  ],
  edges: [
    { id: 'loanPolicy->response:', source: 'loanPolicy', target: 'response', label: '' }
  ],
  groups: [],
  viewport: { x: 0, y: 0, zoom: 1 }
};

const COMPOSER_SCENARIO = {
  graphName: COMPOSER_GRAPH,
  title: 'Custom Composer',
  pattern: 'Editable DSL + decision_table',
  concepts: ['Self orchestration', 'Decision table', 'Live diagnostics'],
  sampleInput: {},
  samplePresets: [],
  diagramPath: '',
  decisionTable: null
};

const state = {
  scenarios: [],
  selected: null,
  layout: null,
  selectedNodeId: null,
  eventSource: null,
  lastPayload: null,
  customDsl: DEFAULT_COMPOSER_DSL,
  customContextText: '',
  customDecisionTable: DEFAULT_COMPOSER_DECISION_TABLE
};

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
    state.layout = COMPOSER_LAYOUT;
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
  $('concepts').innerHTML = state.selected.concepts.map((concept) => `<span class="chip">${concept}</span>`).join('');
  $('inspector').classList.toggle('composer-mode', isComposerSelected());
  renderInputForm();
  renderDecisionTable();
  renderDiagram();
  renderNodeDetails(state.layout?.nodes?.[0]);
  renderDecisionSummary(null);
  $('output').textContent = pretty({});
}

function renderInputForm() {
  const form = $('input-form');
  form.innerHTML = '';
  $('input-title').textContent = isComposerSelected() ? 'Composer' : 'Sample Input';
  $('run-scenario').textContent = isComposerSelected() ? 'Run Custom Graph' : 'Run';
  if (isComposerSelected()) {
    form.innerHTML = `
      <div class="field">
        <label for="composer-dsl">BLOGE DSL</label>
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

function isComposerSelected() {
  return state.selected?.graphName === COMPOSER_GRAPH;
}

function currentDecisionTable() {
  return isComposerSelected() ? state.customDecisionTable : state.selected?.decisionTable;
}

function payloadData(payload) {
  if (!payload) {
    return null;
  }
  return payload.data ?? payload.output ?? payload;
}

function resetComposer() {
  state.customDsl = DEFAULT_COMPOSER_DSL;
  state.customContextText = pretty(DEFAULT_COMPOSER_CONTEXT);
  state.customDecisionTable = DEFAULT_COMPOSER_DECISION_TABLE;
  state.layout = COMPOSER_LAYOUT;
  state.selectedNodeId = null;
  state.lastPayload = null;
  renderScenario();
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
    group.setAttribute('tabindex', '0');
    group.setAttribute('role', 'button');
    group.addEventListener('click', () => {
      state.selectedNodeId = node.id;
      renderDiagram();
      renderNodeDetails(node);
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
  const response = await fetch('/api/gateway/examples/compose/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      dsl: state.customDsl,
      context,
      outputNode: 'response'
    })
  });
  const payload = await response.json();
  if (payload.layout) {
    state.layout = payload.layout;
    renderNodeDetails(state.layout.nodes?.[0]);
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

$('run-scenario').addEventListener('click', runScenario);
$('load-resources').addEventListener('click', loadResources);

loadScenarios().catch((error) => {
  $('output').textContent = pretty({ error: error.message });
});
