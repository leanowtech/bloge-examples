const state = {
  scenarios: [],
  selected: null,
  layout: null,
  selectedNodeId: null,
  eventSource: null
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
  state.scenarios = await response.json();
  renderScenarioButtons();
  await selectScenario(state.scenarios[0].graphName);
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
  const response = await fetch(scenario.diagramPath);
  state.layout = await response.json();
  renderScenarioButtons();
  renderScenario();
}

function renderScenario() {
  $('scenario-title').textContent = state.selected.title;
  $('scenario-pattern').textContent = state.selected.pattern;
  $('concepts').innerHTML = state.selected.concepts.map((concept) => `<span class="chip">${concept}</span>`).join('');
  renderInputForm();
  renderDecisionTable();
  renderDiagram();
  renderNodeDetails(state.layout.nodes[0]);
  $('output').textContent = pretty({});
}

function renderInputForm() {
  const form = $('input-form');
  form.innerHTML = '';
  for (const [key, value] of Object.entries(state.selected.sampleInput)) {
    const wrapper = document.createElement('div');
    wrapper.className = 'field';
    wrapper.innerHTML = `<label for="input-${key}">${key}</label><input id="input-${key}" name="${key}" value="${value}">`;
    form.appendChild(wrapper);
  }
}

function inputValues() {
  const values = {};
  for (const field of $('input-form').querySelectorAll('input')) {
    values[field.name] = field.value;
  }
  return values;
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
  const nodes = state.layout.nodes;
  const edges = state.layout.edges;
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
    path.setAttribute('class', 'edge');
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
    group.setAttribute('class', `node ${node.kind} ${state.selectedNodeId === node.id ? 'selected' : ''}`);
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
  const model = state.selected.decisionTable;
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
  highlightDecisionRow(null);
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
  $('output').textContent = pretty({ status: response.status, payload });
  highlightDecisionRow(payload);
}

function highlightDecisionRow(payload) {
  const rows = document.querySelectorAll('[data-rule-id]');
  rows.forEach((row) => row.classList.remove('matched'));
  const ruleId = payload?.data?.policy?.ruleId;
  if (!ruleId) return;
  const matched = document.querySelector(`[data-rule-id="${CSS.escape(String(ruleId))}"]`);
  if (matched) {
    matched.classList.add('matched');
  }
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
