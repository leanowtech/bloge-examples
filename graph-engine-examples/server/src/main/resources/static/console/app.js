const CONSOLE_VIEWS = new Set(['operations', 'graphs', 'instances', 'deployments', 'operators', 'authoring', 'tasks']);

const state = {
  view: initialViewFromPath(),
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

function initialViewFromPath() {
  const segment = window.location.pathname.replace(/^\/console\/?/, '').split('/')[0];
  return CONSOLE_VIEWS.has(segment) ? segment : 'operations';
}

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

function showDiagram() {
  $('diagram').classList.remove('hidden');
  $('tool-panel').classList.add('hidden');
}

function showToolPanel() {
  $('diagram').classList.add('hidden');
  $('tool-panel').classList.remove('hidden');
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
  showDiagram();
  if (state.view === 'operations') {
    await loadOperationsOverview();
  } else if (state.view === 'graphs') {
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
  } else if (state.view === 'authoring') {
    loadAuthoring();
  } else {
    $('list-title').textContent = 'Queues';
    await loadQueues();
  }
}

async function loadOperationsOverview() {
  $('list-title').textContent = 'Operations';
  const snapshot = await api('/api/v1/operations/snapshot');
  state.list = operationsRows(snapshot);
  renderList(
    state.list,
    (item) => item.id,
    (item) => item.title,
    (item) => item.meta
  );
  state.selected = snapshot;
  $('entity-kicker').textContent = `Operations · ${snapshot.health || 'UNKNOWN'}`;
  $('entity-title').textContent = `${snapshot.tenantId || 'default'} / ${snapshot.namespace || 'default'}`;
  setOutput('details', snapshot);
  setOutput('runtime', {
    actionItems: snapshot.actionItems || [],
    sloIndicators: snapshot.sloIndicators || [],
    recentDeadLetters: snapshot.recentDeadLetters || []
  });
  renderOperationsActions();
  renderOperationsPanel(snapshot);
}

function operationsRows(snapshot) {
  const actionItems = (snapshot.actionItems || []).map((item, index) => ({
    id: `action-${index}-${item.code}`,
    kind: 'action',
    title: item.code,
    meta: `${item.severity || 'WARNING'} · ${item.targetType || 'scope'}`,
    payload: item
  }));
  const sloItems = (snapshot.sloIndicators || [])
    .filter((item) => item.health && item.health !== 'OK')
    .map((item, index) => ({
      id: `slo-${index}-${item.code}`,
      kind: 'slo',
      title: item.code,
      meta: `${item.health || 'WARNING'} · ${item.metricName || 'metric'}`,
      payload: item
    }));
  const deadLetters = (snapshot.recentDeadLetters || []).map((item, index) => ({
    id: `dead-letter-${index}-${item.itemId}`,
    kind: 'dead-letter',
    title: item.nodeId || item.itemId,
    meta: `${item.definitionKey || 'graph'} · ${item.deadLetterReason || item.lastError || item.itemId}`,
    payload: item
  }));
  const rows = [...actionItems, ...sloItems, ...deadLetters];
  if (!rows.length) {
    rows.push({
      id: 'health-ok',
      kind: 'health',
      title: 'No action items',
      meta: snapshot.generatedAt || 'snapshot',
      payload: { health: snapshot.health, generatedAt: snapshot.generatedAt }
    });
  }
  return rows;
}

function renderOperationsActions() {
  const actions = $('actions');
  actions.innerHTML = '';
  actions.appendChild(actionButton('Queues', () => activateTab('tasks'), 'primary'));
  actions.appendChild(actionButton('Instances', () => activateTab('instances')));
  actions.appendChild(actionButton('Deployments', () => activateTab('deployments')));
}

function selectOperationsRow(item) {
  state.selected = item;
  $('entity-kicker').textContent = `Operations · ${item.kind}`;
  $('entity-title').textContent = item.title || 'Operations';
  setOutput('details', item.payload || item);
  renderOperationsActions();
}

function renderOperationsPanel(snapshot) {
  clearDiagram();
  showToolPanel();
  const panel = $('tool-panel');
  panel.innerHTML = '';
  const overview = document.createElement('div');
  overview.className = 'operations-overview';

  const health = document.createElement('section');
  health.className = `operations-health ${healthClass(snapshot.health)}`;
  const healthLabel = document.createElement('span');
  healthLabel.textContent = 'Health';
  const healthValue = document.createElement('strong');
  healthValue.textContent = snapshot.health || 'UNKNOWN';
  const generatedAt = document.createElement('span');
  generatedAt.textContent = snapshot.generatedAt ? `Generated ${snapshot.generatedAt}` : 'Generated';
  health.appendChild(healthLabel);
  health.appendChild(healthValue);
  health.appendChild(generatedAt);
  overview.appendChild(health);

  const metrics = document.createElement('section');
  metrics.className = 'operations-metrics';
  [
    ['Sampled instances', snapshot.sampledInstanceCount, `active ${snapshot.activeInstanceCount || 0}`],
    ['Terminal instances', snapshot.terminalInstanceCount, `failed ${countOf(snapshot.instancesByStatus, 'FAILED')}`],
    ['Deployments', snapshot.deploymentCount, `active ${snapshot.activeDeploymentCount || 0}`],
    ['Dead letters', snapshot.deadLetterCount, (snapshot.recentDeadLetters || []).length ? 'recent samples' : 'none'],
    ['Running', countOf(snapshot.instancesByStatus, 'RUNNING'), `suspended ${countOf(snapshot.instancesByStatus, 'SUSPENDED')}`],
    ['Sample limit', snapshot.sampleLimit, snapshot.truncated ? 'truncated' : 'complete sample']
  ].forEach(([label, value, meta]) => metrics.appendChild(metricCard(label, value, meta)));
  overview.appendChild(metrics);

  overview.appendChild(summarySection('SLO Indicators', snapshot.sloIndicators || [], renderSloIndicator));
  overview.appendChild(summarySection('Action Items', snapshot.actionItems || [], renderActionItem));
  overview.appendChild(summarySection('Recent Dead Letters', snapshot.recentDeadLetters || [], renderDeadLetterItem));
  panel.appendChild(overview);
}

function metricCard(label, value, meta) {
  const card = document.createElement('div');
  card.className = 'metric-card';
  const caption = document.createElement('span');
  caption.textContent = label;
  const number = document.createElement('strong');
  number.textContent = value ?? 0;
  const detail = document.createElement('small');
  detail.textContent = meta || '';
  card.appendChild(caption);
  card.appendChild(number);
  card.appendChild(detail);
  return card;
}

function summarySection(title, items, renderItem) {
  const section = document.createElement('section');
  section.className = 'operations-section';
  const heading = document.createElement('h3');
  heading.textContent = title;
  section.appendChild(heading);
  if (!items.length) {
    const empty = document.createElement('p');
    empty.className = 'empty-inline';
    empty.textContent = 'No rows';
    section.appendChild(empty);
    return section;
  }
  const list = document.createElement('div');
  list.className = 'operations-summary-list';
  items.forEach((item) => list.appendChild(renderItem(item)));
  section.appendChild(list);
  return section;
}

function renderActionItem(item) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = `summary-row ${healthClass(item.severity)}`;
  row.addEventListener('click', () => setOutput('details', item));
  row.innerHTML = `<strong>${escapeHtml(item.code)}</strong><span>${escapeHtml(item.message || '')}</span><small>${escapeHtml(item.targetType || 'scope')} ${escapeHtml(item.targetId || '')}</small>`;
  return row;
}

function renderSloIndicator(item) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = `summary-row ${healthClass(item.health)}`;
  row.addEventListener('click', () => setOutput('details', item));
  row.innerHTML = `<strong>${escapeHtml(item.code)}</strong><span>${escapeHtml(item.metricName || '')}: ${escapeHtml(formatObservedValue(item))}</span><small>${escapeHtml(thresholdText(item))}</small>`;
  return row;
}

function formatObservedValue(item) {
  const value = item.observedValue ?? 0;
  const unit = item.unit || 'count';
  return `${value} ${unit}`;
}

function thresholdText(item) {
  const parts = [];
  if (item.warningThreshold !== null && item.warningThreshold !== undefined) {
    parts.push(`warning ${item.warningThreshold}`);
  }
  if (item.criticalThreshold !== null && item.criticalThreshold !== undefined) {
    parts.push(`critical ${item.criticalThreshold}`);
  }
  if (item.actionCode) {
    parts.push(`action ${item.actionCode}`);
  }
  return parts.length ? parts.join(' · ') : (item.message || 'no threshold');
}

function renderDeadLetterItem(item) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'summary-row critical';
  row.addEventListener('click', () => setOutput('details', item));
  row.innerHTML = `<strong>${escapeHtml(item.nodeId || item.itemId)}</strong><span>${escapeHtml(item.deadLetterReason || item.lastError || '')}</span><small>${escapeHtml(item.definitionKey || '')} ${escapeHtml(item.deadLetteredAt || '')}</small>`;
  return row;
}

function countOf(values, key) {
  return values && values[key] ? values[key] : 0;
}

function healthClass(value) {
  return String(value || 'ok').toLowerCase();
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[char]));
}

async function loadQueues() {
  const result = {};
  for (const [key, path] of Object.entries({
    tasks: '/api/v1/tasks',
    deadLetters: '/api/v1/dead-letters'
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
  renderQueueTool();
}

function loadAuthoring() {
  $('list-title').textContent = 'Authoring';
  const modes = [
    { id: 'validate', title: 'DSL Validate', meta: '/api/v1/ai/validate' },
    { id: 'generate', title: 'AI Generate', meta: '/api/v1/ai/generate' },
    { id: 'diff', title: 'Version Diff', meta: '/api/v1/graphs/{key}/versions/{left}/diff/{right}' }
  ];
  state.list = modes;
  renderList(modes, (item) => item.id, (item) => item.title, (item) => item.meta);
  selectAuthoringMode(modes[0]);
}

function selectAuthoringMode(mode) {
  closeEvents();
  state.selected = mode;
  $('entity-kicker').textContent = 'Authoring';
  $('entity-title').textContent = mode.title;
  $('actions').innerHTML = '';
  setOutput('details', { endpoint: mode.meta });
  setOutput('runtime', {});
  clearDiagram();
  showToolPanel();
  if (mode.id === 'validate') renderValidateTool();
  else if (mode.id === 'generate') renderGenerateTool();
  else renderDiffTool();
}

function renderValidateTool() {
  const panel = $('tool-panel');
  panel.innerHTML = '';
  const grid = toolGrid();
  const dsl = textArea('', 'Paste BLOGE DSL source');
  const card = toolCard('Validate Raw DSL');
  card.classList.add('wide');
  card.appendChild(field('DSL source', dsl));
  card.appendChild(actionButton('Validate DSL', async () => {
    setOutput('runtime', await api('/api/v1/ai/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dslSource: dsl.value })
    }));
  }, 'primary'));
  grid.appendChild(card);
  panel.appendChild(grid);
}

function renderGenerateTool() {
  const panel = $('tool-panel');
  panel.innerHTML = '';
  const grid = toolGrid();
  const request = textArea('', 'Describe the graph you want to generate');
  const model = textInput('configured-model', 'Model');
  const card = toolCard('Generate Draft DSL');
  card.classList.add('wide');
  card.appendChild(field('Request', request));
  card.appendChild(field('Model', model));
  card.appendChild(actionButton('Generate Draft', async () => {
    setOutput('runtime', await api('/api/v1/ai/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        naturalLanguageRequest: request.value,
        model: model.value,
        fewShotExampleCount: 1,
        maxRepairRounds: 1,
        temperature: 0.2,
        maxTokens: 2048
      })
    }));
  }, 'primary'));
  grid.appendChild(card);
  panel.appendChild(grid);
}

function renderDiffTool() {
  const panel = $('tool-panel');
  panel.innerHTML = '';
  const grid = toolGrid();
  const definitionKey = textInput('', 'definition key');
  const leftVersion = textInput('', 'left version');
  const rightVersion = textInput('', 'right version');
  const card = toolCard('Compare Versions');
  card.classList.add('wide');
  card.appendChild(field('Definition key', definitionKey));
  card.appendChild(field('Left version', leftVersion));
  card.appendChild(field('Right version', rightVersion));
  card.appendChild(actionButton('Load Diff', async () => {
    const key = encodeURIComponent(definitionKey.value);
    const left = encodeURIComponent(leftVersion.value);
    const right = encodeURIComponent(rightVersion.value);
    setOutput('runtime', await api(`/api/v1/graphs/${key}/versions/${left}/diff/${right}`));
  }, 'primary'));
  grid.appendChild(card);
  panel.appendChild(grid);
}

function renderQueueTool() {
  showToolPanel();
  const panel = $('tool-panel');
  panel.innerHTML = '';
  const grid = toolGrid();
  const workerId = textInput('console-worker', 'worker id');
  const workerTopic = textInput('default', 'worker topic');
  const limit = textInput('5', 'limit');
  const leaseDuration = textInput('PT30S', 'lease duration');
  const workerCard = toolCard('Remote Worker');
  workerCard.appendChild(field('Worker id', workerId));
  workerCard.appendChild(field('Worker topic', workerTopic));
  workerCard.appendChild(field('Poll limit', limit));
  workerCard.appendChild(field('Lease duration', leaseDuration));
  workerCard.appendChild(actionButton('Register', async () => {
    setOutput('runtime', await api('/api/v1/remote-workers/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ workerId: workerId.value, workerTopic: workerTopic.value })
    }));
  }, 'primary'));
  workerCard.appendChild(actionButton('Poll Jobs', async () => {
    setOutput('runtime', await api(`/api/v1/remote-workers/${encodeURIComponent(workerTopic.value)}/poll`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        workerId: workerId.value,
        limit: Number.parseInt(limit.value, 10) || null,
        leaseDuration: leaseDuration.value || null
      })
    }));
  }));

  const itemId = textInput('', 'item id');
  const leaseToken = textInput('', 'lease token');
  const expectedRevision = textInput('0', 'expected revision');
  const callbackPayload = textArea('{}', 'JSON output or error text');
  const callbackCard = toolCard('Worker Job Callback');
  callbackCard.appendChild(field('Item id', itemId));
  callbackCard.appendChild(field('Lease token', leaseToken));
  callbackCard.appendChild(field('Expected revision', expectedRevision));
  callbackCard.appendChild(field('Output / error', callbackPayload));
  callbackCard.appendChild(actionButton('Heartbeat', async () => {
    setOutput('runtime', await api(`/api/v1/remote-workers/items/${encodeURIComponent(itemId.value)}/heartbeat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ leaseToken: leaseToken.value, leaseDuration: leaseDuration.value || null })
    }));
  }));
  callbackCard.appendChild(actionButton('Complete', async () => {
    await api(`/api/v1/remote-workers/items/${encodeURIComponent(itemId.value)}/complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        leaseToken: leaseToken.value,
        expectedRevision: Number.parseInt(expectedRevision.value, 10),
        output: parseJson(callbackPayload.value)
      })
    });
    setOutput('runtime', { completed: itemId.value });
  }, 'primary'));
  callbackCard.appendChild(actionButton('Fail', async () => {
    await api(`/api/v1/remote-workers/items/${encodeURIComponent(itemId.value)}/fail`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        leaseToken: leaseToken.value,
        expectedRevision: Number.parseInt(expectedRevision.value, 10),
        error: callbackPayload.value
      })
    });
    setOutput('runtime', { failed: itemId.value });
  }));

  grid.appendChild(workerCard);
  grid.appendChild(callbackCard);
  panel.appendChild(grid);
}

function toolGrid() {
  const grid = document.createElement('div');
  grid.className = 'tool-grid';
  return grid;
}

function toolCard(title) {
  const card = document.createElement('section');
  card.className = 'tool-card';
  const heading = document.createElement('h3');
  heading.textContent = title;
  card.appendChild(heading);
  return card;
}

function field(label, control) {
  const wrapper = document.createElement('label');
  wrapper.className = 'field';
  const caption = document.createElement('span');
  caption.textContent = label;
  wrapper.appendChild(caption);
  wrapper.appendChild(control);
  return wrapper;
}

function textInput(value, placeholder) {
  const input = document.createElement('input');
  input.value = value;
  input.placeholder = placeholder;
  return input;
}

function textArea(value, placeholder) {
  const textarea = document.createElement('textarea');
  textarea.value = value;
  textarea.placeholder = placeholder;
  return textarea;
}

function parseJson(value) {
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
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
      if (state.view === 'operations') selectOperationsRow(item);
      else if (state.view === 'graphs') selectGraph(item).catch(showError);
      else if (state.view === 'instances') selectInstance(item).catch(showError);
      else if (state.view === 'authoring') selectAuthoringMode(item);
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
  tab.classList.toggle('active', tab.dataset.view === state.view);
  tab.addEventListener('click', () => activateTab(tab.dataset.view));
});
$('refresh').addEventListener('click', () => loadView().catch(showError));

loadView().catch(showError);
