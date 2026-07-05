import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  buildGatewayRunRequest,
  fetchGatewayDiagram,
  fetchGatewayScenarios,
  importOperatorLibraryText,
  runGatewayScenario,
  validateOperatorLibraryText,
} from './api';

describe('operator library API client', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('validates pasted operator-library source as text', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      valid: true,
      diagnostics: [],
      profile: { libraryId: 'risk-policy', operatorCount: 1 },
    })));

    const result = await validateOperatorLibraryText('schemaVersion: bloge.visualOperatorLibrary.v1');

    expect(result.valid).toBe(true);
    expect(fetchMock).toHaveBeenCalledWith('/admin/visual-operator-libraries/validate-text', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: 'schemaVersion: bloge.visualOperatorLibrary.v1',
    });
  });

  it('imports pasted operator-library source with author-canvas audit metadata', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      libraryId: 'risk-policy',
      operators: [],
    }), { status: 201 }));

    const result = await importOperatorLibraryText('{"libraryId":"risk-policy","operators":[]}');

    expect(result.libraryId).toBe('risk-policy');
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/admin/visual-operator-libraries/import-text?');
    expect(String(url)).toContain('actor=author-canvas');
    expect(String(url)).toContain('changeSource=react-author');
    expect(init).toMatchObject({
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: '{"libraryId":"risk-policy","operators":[]}',
    });
  });

  it('surfaces server diagnostics when import validation fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      valid: false,
      diagnostics: [{
        code: 'visual.library.schemaVersion',
        message: 'Unsupported schema version.',
      }],
    }), { status: 400, statusText: 'Bad Request' }));

    await expect(importOperatorLibraryText('{}'))
      .rejects.toThrow('Request failed: 400 Unsupported schema version.');
  });

  it('loads gateway showcase scenarios in backend-defined order', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify([
      { graphName: 'userDashboard', title: 'User Dashboard' },
      { graphName: 'loanDecisionPolicy', title: 'Loan Decision Policy' },
      { graphName: 'aiEnrichedSearch', title: 'AI Enriched Search' },
    ])));

    const scenarios = await fetchGatewayScenarios();

    expect(fetchMock).toHaveBeenCalledWith('/api/gateway/examples/scenarios');
    expect(scenarios.map((scenario) => scenario.graphName)).toEqual([
      'userDashboard',
      'loanDecisionPolicy',
      'aiEnrichedSearch',
    ]);
  });

  it('loads one gateway showcase diagram from the provided path', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      schemaVersion: 'bloge.visualLayout.v1',
      rootId: 'loanDecisionPolicy',
      nodes: [{ id: 'loanPolicy', label: 'Loan Policy Matrix' }],
      edges: [],
    })));

    const diagram = await fetchGatewayDiagram('/api/gateway/examples/scenarios/loanDecisionPolicy/diagram');

    expect(fetchMock).toHaveBeenCalledWith('/api/gateway/examples/scenarios/loanDecisionPolicy/diagram');
    expect(diagram.rootId).toBe('loanDecisionPolicy');
    expect(diagram.nodes[0].id).toBe('loanPolicy');
  });

  it('resolves and runs a gateway showcase request scenario', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: true,
      data: { policy: { ruleId: 'R1' } },
    }), { status: 200 }));

    const result = await runGatewayScenario({
      mode: 'request',
      method: 'GET',
      pathTemplate: '/api/gateway/loan-policy/{applicantId}?amount={amount}',
    }, { applicantId: 'prime customer', amount: 450000 });

    expect(fetchMock).toHaveBeenCalledWith('/api/gateway/loan-policy/prime%20customer?amount=450000', {
      method: 'GET',
      headers: {},
    });
    expect(result.status).toBe(200);
    expect(result.url).toBe('/api/gateway/loan-policy/prime%20customer?amount=450000');
    expect(result.payload).toMatchObject({ success: true });
  });

  it('builds a gateway showcase POST body from nested placeholders', () => {
    const request = buildGatewayRunRequest({
      mode: 'post',
      method: 'POST',
      pathTemplate: '/api/gateway/resources/execute',
      bodyTemplate: {
        resourceId: '{resourceId}',
        params: { userId: '{userId}' },
      },
      headers: { 'Content-Type': 'application/json' },
    }, { resourceId: 'user-service.getProfile', userId: 'u1' });

    expect(request).toMatchObject({
      mode: 'post',
      url: '/api/gateway/resources/execute',
      init: {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      },
    });
    expect(request.init.body).toBe(JSON.stringify({
      resourceId: 'user-service.getProfile',
      params: { userId: 'u1' },
    }));
  });
});
