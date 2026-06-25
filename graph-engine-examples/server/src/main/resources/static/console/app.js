const state = {
  view: 'graphs',
  list: [],
  selected: null,
  selectedVersion: null,
  diagram: null,
  nodeStates: [],
  selectedNodeId: null,
  events: [],
  eventSource: null
};

const $ = (id) => document.getElementById(id);

function headers(extra = {}) {
  const roles = $('role-header').value.trim();
  return roles ? { ...extra, 'X-Graph-Engine-Roles': roles } : extra;
}

function pretty(value) {
  return JSON.stringify(value, null, 2);
}

async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: headers(options.headers || {}) });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${response.status} ${response.statusText}: ${text}`);
  }
  if (response.status === 204) return null;
  return response.json();
}

function setOutput(target, value) {
  $(target).textContent = pretty(value ?? {});
}

function activateTab(view) {
  state.view = view;
  closeEvents();
  for (const tab of document.querySelectorAll('.tab')) {
    tab.classList.toggle('active', tab.dataset.view === view);
  }
  loadView().catch(showError);
}

async function loadView() {
  state.selected = null;
  state.selectedVersion = null;
  state.diagram = null;
  state.nodeStates = [];
  state.selectedNodeId = null;
  state.events = [];
  $('actions').innerHTML = '';
  setOutput('details', {});
  setOutput('runtime', {});
  clearDiagram();
  if (state.view === 'graphs') {
    $('list-title').textContent = 'Graphs';
    state.list = await api('/api/v1/graphs');
    renderList(state.list, (item) => item.definitionKey, (item) => item.displayName || item.definitionKey, (item) => item.status || item.ownerTeam || '');
    if (state.list[0]) await selectGraph(state.list[0]);
  } else if (state.view === 'instances') {
    $('list-title').textContent = 'Instances';
    state.list = await api('/api/v1/instances');
    renderList(state.list, (item) => item.instanceId, (item) => item.definitionKey, (item) => `${item.status} · ${item.businessKey || item.instanceId}`);
    if (state.list[0]) await selectInstance(state.list[0]);
  } else if (state.view === 'deployments') {
    $('list-title').textContent = 'Deployments';
    state.list = await api('/api/v1/deployments');
    renderList(state.list, (item) => item.deploymentId, (item) => item.definitionKey, (item) => `${item.environment} · ${item.active ? 'active' : 'inactive'}`);
    if (state.list[0]) selectPlain(state.list[0], 'Deployment');
  } else if (state.view === 'operators') {
    $('list-title').textContent = 'Operators';
    state.list = await api('/api/v1/operators');
    renderList(state.list, (item) => item.name || item.operatorName, (item) => item.name || item.operatorName, (item) => item.owner || item.description || '');
    if (state.list[0]) selectPlain(state.list[0], 'Operator');
  } else {
    $('list-title').textContent = 'Queues';
    await loadQueues();
  }
}

async function loadQueues() {
  const result = {};
  for (const [key, path] of Object.entries({
    tasks: '/api/v1/tasks',
    deadLetters: '/api/v1/dead-letters',
    remoteWorkers: '/api/v1/remote-workers'
  })) {
    try {
      result[key] = await api(path);
    } catch (error) {
      result[key] = { unavailable: error.message };
    }
  }
  const combined = [
    ...(Array.isArray(result.tasks) ? result.tasks.map((item) => ({ kind: 'task', ...item })) : []),
    ...(Array.isArray(result.deadLetters) ? result.deadLetters.map((item) => ({ kind: 'dead-letter', ...item })) : [])
  ];
  state.list = combined;
  renderList(combined, (item) => item.taskId || item.itemId || item.kind, (item) => item.nodeId || item.definitionKey || item.kind, (item) => item.status || item.kind);
  $('entity-kicker').textContent = 'Queues';
  $('entity-title').textContent = 'Tasks / Workers / Dead Letters';
  setOutput('details', result);
}

function renderList(items, idOf, titleOf, metaOf) {
  const list = $('entity-list');
  list.innerHTML = '';
  if (!items.length) {
    list.innerHTML = '<div class="empty">No rows</div>';
    return;
  }
  for (const item of items) {
    const id = idOf(item);
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'entity-button';
    button.setAttribute('aria-current', state.selected && idOf(state.selected) === id ? 'true' : 'false');
    const title = document.createElement('strong');
    title.textContent = titleOf(item) || id;
    const meta = document.createElement('span');
    meta.textContent = metaOf(item) || id;
    button.appendChild(title);
    button.appendChild(meta);
    button.addEventListener('click', () => {
      if (state.view === 'graphs') selectGraph(item).catch(showError);
      else if (state.view === 'instances') selectInstance(item).catch(showError);
      else selectPlain(item, state.view);
    });
    list.appendChild(button);
  }
}

async function selectGraph(definition) {
  state.selected = definition;
  $('entity-kicker').textContent = `Graph · ${definition.status || 'ACTIVE'}`;
  $('entity-title').textContent = definition.displayName || definition.definitionKey;
  setOutput('details', definition);
  const versions = await api(`/api/v1/graphs/${encodeURIComponent(definition.definitionKey)}/versions`);
  setOutput('runtime', { versions });
  const version = versions[0];
  state.selectedVersion = version;
  renderGraphActions(definition, versions);
  if (version) {
    const diagram = await api(`/api/v1/graphs/${encodeURIComponent(definition.definitionKey)}/versions/${encodeURIComponent(version.version)}/diagram`);
    state.diagram = parseLayout(diagram.visualLayout);
    renderDiagram();
  }
}

function renderGraphActions(definition, versions) {
  const actions = $('actions');
  actions.innerHTML = '';
  const select = document.createElement('select');
  for (const version of versions) {
    const option = document.createElement('option');
    option.value = version.version;
    option.textContent = `${version.version} · ${version.status}`;
    select.appendChild(option);
  }
  select.addEventListener('change', async () => {
    state.selectedVersion = versions.find((item) => item.version === select.value);
    const diagram = await api(`/api/v1/graphs/${encodeURIComponent(definition.definitionKey)}/versions/${encodeURIComponent(select.value)}/diagram`);
    state.diagram = parseLayout(diagram.visualLayout);
    setOutput('runtime', { version: state.selectedVersion });
    renderDiagram();
  });
  if (versions.length) actions.appendChild(select);
  const validate = actionButton('Validate', async () => {
    setOutput('runtime', await api(`/api/v1/graphs/${encodeURIComponent(definition.definitionKey)}/versions/${encodeURIComponent(select.value)}/validate`, { method: 'POST' }));
  });
  const start = actionButton('Start', async () => {
    const version = select.value;
    const result = await api(`/api/v1/graphs/${encodeURIComponent(definition.definitionKey)}/instances`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        version,
        businessKey: `console-${Date.now()}`,
        initiator: 'console',
        variables: {}
      })
    });
    setOutput('runtime', result);
  }, 'primary');
  if (versions.length) {
    actions.appendChild(validate);
    actions.appendChild(start);
  }
}

async function selectInstance(instance) {
  state.selected = instance;
  $('entity-kicker').textContent = `Instance · ${instance.status}`;
  $('entity-title').textContent = instance.definitionKey;
  setOutput('details', instance);
  const diagram = await api(`/api/v1/instances/${encodeURIComponent(instance.instanceId)}/diagram`);
  state.diagram = parseLayout(diagram.visualLayout);
  state.nodeStates = diagram.nodeStates || [];
  renderDiagram();
  await loadInstanceRuntime(instance.instanceId);
  renderInstanceActions(instance);
}

async function loadInstanceRuntime(instanceId) {
  const runtime = {};
  for (const [key, path] of Object.entries({
    context: `/api/v1/instances/${encodeURIComponent(instanceId)}/context`,
    audit: `/api/v1/instances/${encodeURIComponent(instanceId)}/audit`,
    transitions: `/api/v1/instances/${encodeURIComponent(instanceId)}/transitions`,
    pendingSignals: `/api/v1/instances/${encodeURIComponent(instanceId)}/pending-signals`
  })) {
    try {
      runtime[key] = await api(path);
    } catch (error) {
      runtime[key] = { unavailable: error.message };
    }
  }
  setOutput('runtime', runtime);
}

function renderInstanceActions(instance) {
  const actions = $('actions');
  actions.innerHTML = '';
  actions.appendChild(actionButton('Events', () => streamEvents(instance.instanceId), 'primary'));
  actions.appendChild(actionButton('Poll Nodes', async () => {
    const nodes = await api(`/api/v1/instances/${encodeURIComponent(instance.instanceId)}/nodes?size=500`);
    state.nodeStates = nodes.items || [];
    renderDiagram();
    setOutput('runtime', nodes);
  }));
}

function selectPlain(item, label) {
  closeEvents();
  state.selected = item;
  $('entity-kicker').textContent = label;
  $('entity-title').textContent = item.definitionKey || item.name || item.operatorName || item.deploymentId || 'Selection';
  setOutput('details', item);
  setOutput('runtime', {});
  $('actions').innerHTML = '';
  clearDiagram();
}

function actionButton(label, handler, kind = 'secondary') {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = kind;
  button.textContent = label;
  button.addEventListener('click', () => Promise.resolve(handler()).catch(showError));
  return button;
}

function parseLayout(layout) {
  if (!layout) return { nodes: [], edges: [], groups: [] };
  if (typeof layout === 'string') {
    try {
      return JSON.parse(layout);
    } catch {
      return { nodes: [], edges: [], groups: [], raw: layout };
    }
  }
  return layout;
}

function clearDiagram() {
  $('diagram').innerHTML = '';
}

function renderDiagram() {
  const svg = $('diagram');
  const layout = normalizeLayout(state.diagram || {});
  const nodes = layout.nodes;
  const edges = layout.edges;
  const statusByNode = Object.fromEntries((state.nodeStates || []).map((node) => [node.nodeId, node]));
  const width = Math.max(760, ...nodes.map((node) => node.position.x + node.size.width + 80));
  const height = Math.max(540, ...nodes.map((node) => node.position.y + node.size.height + 80));
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.innerHTML = `
    <defs>
      <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto">
        <path d="M0,0 L0,6 L9,3 z" fill="#7e8b9b"></path>
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
    label.setAttribute('x', String(mid - 18));
    label.setAttribute('y', String((y1 + y2) / 2 - 6));
    label.textContent = edge.label || '';
    svg.appendChild(label);
  }
  for (const node of nodes) {
    const stateForNode = statusByNode[node.id];
    const statusClass = (stateForNode?.status || 'not_started').toLowerCase();
    const group = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    group.setAttribute('class', `node ${statusClass} ${state.selectedNodeId === node.id ? 'selected' : ''}`);
    group.addEventListener('click', () => {
      state.selectedNodeId = node.id;
      renderDiagram();
      setOutput('details', { node, state: stateForNode || null });
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
    title.textContent = node.label || node.id;
    group.appendChild(title);
    const meta = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    meta.setAttribute('class', 'node-meta');
    meta.setAttribute('x', String(node.position.x + 12));
    meta.setAttribute('y', String(node.position.y + 52));
    meta.textContent = stateForNode?.status || node.operatorRef || node.kind || 'node';
    group.appendChild(meta);
    svg.appendChild(group);
  }
}

function normalizeLayout(layout) {
  const nodes = (layout.nodes || []).map((node, index) => ({
    id: node.id,
    kind: node.kind || 'node',
    operatorRef: node.operatorRef || '',
    label: node.label || node.id,
    position: node.position || { x: 80 + index * 230, y: 120 },
    size: node.size || { width: 180, height: 72 },
    annotations: node.annotations || {}
  }));
  return { nodes, edges: layout.edges || [], groups: layout.groups || [] };
}

function streamEvents(instanceId) {
  closeEvents();
  state.events = [];
  state.eventSource = new EventSource(`/api/v1/instances/${encodeURIComponent(instanceId)}/events`);
  state.eventSource.onmessage = (event) => {
    try {
      state.events.push(JSON.parse(event.data));
    } catch {
      state.events.push({ raw: event.data });
    }
    setOutput('runtime', { events: state.events.slice(-80) });
  };
  state.eventSource.onerror = () => {
    closeEvents();
  };
}

function closeEvents() {
  if (state.eventSource) {
    state.eventSource.close();
    state.eventSource = null;
  }
}

function showError(error) {
  setOutput('runtime', { error: error.message });
}

document.querySelectorAll('.tab').forEach((tab) => {
  tab.addEventListener('click', () => activateTab(tab.dataset.view));
});
$('refresh').addEventListener('click', () => loadView().catch(showError));

loadView().catch(showError);
