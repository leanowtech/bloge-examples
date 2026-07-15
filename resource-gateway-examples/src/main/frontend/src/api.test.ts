import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  adaptCapabilityCatalogText,
  batchCommitDslImports,
  batchReportDslImports,
  buildGatewayRunRequest,
  checkDslRewriteGate,
  commitDslImport,
  fetchGatewayDiagram,
  fetchGatewayScenarios,
  fetchGovernanceGateView,
  fetchGraphDraft,
  fetchOperatorCatalog,
  fetchVisualGraphRun,
  governOperatorTestCase,
  governOperatorTestSuite,
  importOperatorLibraryText,
  previewDslImport,
  resetOperatorTestHeadersProvider,
  runOperatorTestCase,
  runGatewayScenario,
  resetBlogeApiTransport,
  setBlogeApiTransport,
  validateDraft,
  validateOperatorLibraryText,
} from './api';

describe('operator library API client', () => {
  afterEach(() => {
    resetBlogeApiTransport();
    resetOperatorTestHeadersProvider();
    vi.restoreAllMocks();
  });

  it('runs a native operator through target discovery and a SPY micro-graph fixture', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === '/api/testing/targets/operators/customer.normalize') {
        expect(init?.headers).toMatchObject({
          Authorization: 'Bearer bloge-aneke-demo-token',
          'X-Purpose': 'TEST_EXECUTION',
        });
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.testOperatorTargetDescriptor.v2',
          target: { kind: 'OPERATOR', id: 'customer.normalize', fingerprint: 'sha256:target' },
          testabilityClass: 'EXECUTABLE_UNIT',
          executionSupported: true,
          certificationEligible: true,
          certificationRequirements: [],
          certificationGaps: [],
        }));
      }
      if (url === '/api/testing/targets/operators/customer.normalize/executions') {
        const body = JSON.parse(String(init?.body));
        expect(body).toMatchObject({
          target: { kind: 'OPERATOR', id: 'customer.normalize', fingerprint: 'sha256:target' },
          input: { name: 'Ada' },
          fixtureBundle: {
            fixtureBundleId: 'canvas-customer.normalize-uppercase-case-1',
            rules: [{
              selector: { nodeId: 'subject', operatorRef: 'customer.normalize' },
              behavior: { kind: 'SPY', boundary: 'NODE' },
            }],
            assertions: [{ nodeId: 'subject', path: '', expected: { normalized: 'ADA' } }],
          },
          metadata: { caseRef: 'uppercase / case #1' },
        });
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.testExecutionResponse.v1',
          runId: 'run-native-1',
          target: body.target,
          evidence: {
            status: 'PASSED',
            evidenceClass: 'EXPLORATORY',
            diagnostics: [],
            nodeTrace: [{ nodeId: 'subject', status: 'SUCCESS', output: { normalized: 'ADA' } }],
            assertionResults: [{ scope: 'OUTPUT_PATH', path: '', passed: true }],
          },
        }));
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const result = await runOperatorTestCase({
      operatorRef: 'visual:normalize',
      lowering: { mode: 'native', operatorRef: 'customer.normalize' },
      ports: { inputs: [], outputs: [] },
    }, { name: 'Ada' }, { normalized: 'ADA' }, null, 'uppercase / case #1');

    expect(result.response.runId).toBe('run-native-1');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('fails closed before execution when target discovery classifies an operator as opaque', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      schemaVersion: 'bloge.testOperatorTargetDescriptor.v2',
      target: { kind: 'OPERATOR', id: 'legacy.external', fingerprint: 'sha256:opaque' },
      testabilityClass: 'OPAQUE_RUNTIME',
      executionSupported: true,
      certificationEligible: false,
      certificationRequirements: [],
      certificationGaps: ['Binding has no formal operator composability manifest.'],
    })));

    await expect(runOperatorTestCase({
      operatorRef: 'legacy.external',
      lowering: { mode: 'native' },
      ports: { inputs: [], outputs: [] },
    }, {}, {}, null, 'unsafe')).rejects
      .toThrow('Binding has no formal operator composability manifest.');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('fails closed before execution for an unsupported or future target class', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      schemaVersion: 'bloge.testOperatorTargetDescriptor.v2',
      target: { kind: 'OPERATOR', id: 'stream.events', fingerprint: 'sha256:stream' },
      testabilityClass: 'UNSUPPORTED_EXECUTION_MODEL',
      executionSupported: true,
      certificationEligible: false,
      certificationRequirements: [],
      certificationGaps: ['Streaming execution is not supported by protocol v1.'],
    })));

    await expect(runOperatorTestCase({
      operatorRef: 'stream.events',
      lowering: { mode: 'native' },
      ports: { inputs: [], outputs: [] },
    }, {}, {}, null, 'stream')).rejects
      .toThrow('Streaming execution is not supported by protocol v1.');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('registers a content-addressed immutable fixture and executes the operator by stored ref', async () => {
    let storedFixtureId = '';
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === '/api/testing/targets/operators/customer.normalize') {
        expect(init?.headers).toMatchObject({
          Authorization: 'Bearer bloge-aneke-demo-token',
          'X-Purpose': 'TEST_FIXTURE_WRITE',
        });
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.testOperatorTargetDescriptor.v2',
          target: { kind: 'OPERATOR', id: 'customer.normalize', fingerprint: 'sha256:target' },
          testabilityClass: 'EXECUTABLE_UNIT',
          executionSupported: true,
          certificationEligible: true,
          certificationRequirements: [],
          certificationGaps: [],
        }));
      }
      if (url.startsWith('/api/testing/fixture-bundles/')) {
        expect(init).toMatchObject({
          method: 'PUT',
          headers: {
            Authorization: 'Bearer bloge-aneke-demo-token',
            'X-Purpose': 'TEST_FIXTURE_WRITE',
            'Content-Type': 'application/json',
          },
        });
        const requestedFixtureId = decodeURIComponent(url.slice('/api/testing/fixture-bundles/'.length));
        expect(requestedFixtureId).toMatch(/^canvas-customer\.normalize-uppercase-case-[0-9a-f]{64}$/);
        if (storedFixtureId) {
          expect(requestedFixtureId).toBe(storedFixtureId);
        } else {
          storedFixtureId = requestedFixtureId;
        }
        const body = JSON.parse(String(init?.body));
        expect(body).toMatchObject({
          schemaVersion: 'bloge.fixtureBundleRegistrationRequest.v1',
          target: { kind: 'OPERATOR', id: 'customer.normalize', fingerprint: 'sha256:target' },
          fixtureBundle: {
            fixtureBundleId: storedFixtureId,
            revision: 1,
            rules: [{ behavior: { kind: 'SPY', boundary: 'NODE' } }],
          },
        });
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.storedFixtureBundle.v1',
          tenantId: 'tenant-a',
          environmentId: 'test',
          fixtureBundleId: storedFixtureId,
          revision: 1,
          fingerprint: 'sha256:stored-fixture',
          createdAt: '2026-07-15T12:00:00Z',
          createdBy: 'author-canvas',
        }));
      }
      if (url === '/api/testing/targets/operators/customer.normalize/executions') {
        expect(init?.headers).toMatchObject({
          Authorization: 'Bearer bloge-aneke-demo-token',
          'X-Purpose': 'TEST_EXECUTION',
        });
        const body = JSON.parse(String(init?.body));
        expect(body.fixtureBundle).toBeNull();
        expect(body.fixtureBundleRef).toEqual({
          fixtureBundleId: storedFixtureId,
          revision: 1,
          fingerprint: 'sha256:stored-fixture',
        });
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.testExecutionResponse.v1',
          runId: 'run-governed-1',
          target: body.target,
          evidence: {
            status: 'PASSED',
            evidenceClass: 'CERTIFIABLE',
            diagnostics: [],
            nodeTrace: [{ nodeId: 'subject', status: 'SUCCESS', output: { normalized: 'ADA' } }],
            assertionResults: [{ scope: 'OUTPUT_PATH', path: '', passed: true }],
          },
        }));
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const result = await governOperatorTestCase({
      operatorRef: 'visual:normalize',
      lowering: { mode: 'native', operatorRef: 'customer.normalize' },
      ports: { inputs: [], outputs: [] },
    }, { name: 'Ada', locale: 'en' }, { normalized: 'ADA', locale: 'en' }, null, 'uppercase case');

    const repeated = await governOperatorTestCase({
      operatorRef: 'visual:normalize',
      lowering: { mode: 'native', operatorRef: 'customer.normalize' },
      ports: { inputs: [], outputs: [] },
    }, { locale: 'en', name: 'Ada' }, { locale: 'en', normalized: 'ADA' }, null, 'uppercase case');

    expect(result.response.runId).toBe('run-governed-1');
    expect(repeated.storedFixture?.fixtureBundleId).toBe(storedFixtureId);
    expect(result.storedFixture).toMatchObject({
      fixtureBundleId: storedFixtureId,
      revision: 1,
      fingerprint: 'sha256:stored-fixture',
    });
    expect(fetchMock).toHaveBeenCalledTimes(6);
  });

  it('fails closed before execution when the fixture registry returns a different identity', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/testing/targets/operators/customer.normalize') {
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.testOperatorTargetDescriptor.v2',
          target: { kind: 'OPERATOR', id: 'customer.normalize', fingerprint: 'sha256:target' },
          testabilityClass: 'EXECUTABLE_UNIT',
          executionSupported: true,
          certificationEligible: true,
          certificationRequirements: [],
          certificationGaps: [],
        }));
      }
      if (url.startsWith('/api/testing/fixture-bundles/')) {
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.storedFixtureBundle.v1',
          tenantId: 'tenant-a',
          environmentId: 'test',
          fixtureBundleId: 'different-fixture',
          revision: 1,
          fingerprint: '',
          createdAt: '2026-07-15T12:00:00Z',
          createdBy: 'author-canvas',
        }));
      }
      throw new Error(`Execution must not start after inconsistent fixture registration: ${url}`);
    });

    await expect(governOperatorTestCase({
      operatorRef: 'visual:normalize',
      lowering: { mode: 'native', operatorRef: 'customer.normalize' },
      ports: { inputs: [], outputs: [] },
    }, { name: 'Ada' }, { normalized: 'ADA' }, null, 'uppercase case')).rejects
      .toThrow('Fixture registry returned an inconsistent stored fixture identity.');

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('publishes multiple operator rows as one immutable suite and executes the exact revision', async () => {
    const targetFingerprint = `sha256:${'a'.repeat(64)}`;
    const fixtureFingerprints = [`sha256:${'b'.repeat(64)}`, `sha256:${'c'.repeat(64)}`];
    const storedFixtureIds: string[] = [];
    let storedSuiteId = '';
    let storedSuiteFingerprint = '';
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === '/api/testing/targets/operators/customer.normalize') {
        expect(init?.headers).toMatchObject({ 'X-Purpose': 'TEST_SUITE_WRITE' });
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.testOperatorTargetDescriptor.v2',
          target: { kind: 'OPERATOR', id: 'customer.normalize', fingerprint: targetFingerprint },
          testabilityClass: 'EXECUTABLE_UNIT',
          executionSupported: true,
          certificationEligible: true,
          certificationRequirements: [],
          certificationGaps: [],
        }));
      }
      if (url.startsWith('/api/testing/fixture-bundles/')) {
        expect(init).toMatchObject({ method: 'PUT', headers: { 'X-Purpose': 'TEST_FIXTURE_WRITE' } });
        const fixtureId = decodeURIComponent(url.slice('/api/testing/fixture-bundles/'.length));
        const body = JSON.parse(String(init?.body));
        expect(fixtureId).toMatch(/^canvas-customer\.normalize-(golden|boundary)-[0-9a-f]{64}$/);
        expect(body.fixtureBundle.fixtureBundleId).toBe(fixtureId);
        storedFixtureIds.push(fixtureId);
        const fingerprint = fixtureFingerprints[storedFixtureIds.length - 1];
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.storedFixtureBundle.v1',
          tenantId: 'tenant-a',
          environmentId: 'test',
          fixtureBundleId: fixtureId,
          revision: 1,
          fingerprint,
          createdAt: '2026-07-15T12:00:00Z',
          createdBy: 'author-canvas',
        }));
      }
      if (url.startsWith('/api/testing/suites/') && !url.endsWith('/executions')) {
        expect(init).toMatchObject({ method: 'PUT', headers: { 'X-Purpose': 'TEST_SUITE_WRITE' } });
        storedSuiteId = decodeURIComponent(url.slice('/api/testing/suites/'.length));
        expect(storedSuiteId).toMatch(/^canvas-customer\.normalize-node-n1-[0-9a-f]{64}$/);
        const body = JSON.parse(String(init?.body));
        expect(body).toMatchObject({
          schemaVersion: 'bloge.testSuiteRegistrationRequest.v1',
          testSuite: {
            schemaVersion: 'bloge.testSuite.v1',
            suiteId: storedSuiteId,
            revision: 1,
            target: { kind: 'OPERATOR', id: 'customer.normalize', fingerprint: targetFingerprint },
            classification: 'INTERNAL',
            cases: [
              {
                caseId: 'golden',
                caseType: 'GOLDEN',
                input: { name: 'Ada' },
                fixtureBundleRef: { fixtureBundleId: storedFixtureIds[0], revision: 1 },
              },
              {
                caseId: 'boundary',
                caseType: 'BOUNDARY',
                input: { name: '' },
                fixtureBundleRef: { fixtureBundleId: storedFixtureIds[1], revision: 1 },
              },
            ],
            coveragePolicy: {
              minimumCases: 2,
              requiredCaseTypes: ['BOUNDARY', 'GOLDEN'],
              minimumAssertionsPerCase: 1,
              requireAllFixtureRulesConsumed: true,
            },
            promotionPolicy: {
              requireAllCasesPassed: true,
              minimumCertifiableCases: 2,
              requireTargetCertificationEligible: true,
            },
          },
        });
        storedSuiteFingerprint = `sha256:${'d'.repeat(64)}`;
        return new Response(JSON.stringify({
          schemaVersion: 'bloge.storedTestSuite.v1',
          tenantId: 'tenant-a',
          environmentId: 'test',
          suiteId: storedSuiteId,
          revision: 1,
          fingerprint: storedSuiteFingerprint,
          suite: body.testSuite,
          createdAt: '2026-07-15T12:00:00Z',
          createdBy: 'author-canvas',
        }));
      }
      if (url === `/api/testing/suites/${encodeURIComponent(storedSuiteId)}/executions`) {
        expect(init).toMatchObject({ method: 'POST', headers: { 'X-Purpose': 'TEST_EXECUTION' } });
        const body = JSON.parse(String(init?.body));
        expect(body).toMatchObject({
          schemaVersion: 'bloge.testSuiteExecutionRequest.v1',
          suiteRef: { suiteId: storedSuiteId, revision: 1, fingerprint: storedSuiteFingerprint },
          strategy: 'COLLECT_ALL',
          metadata: { source: 'author-canvas', visualOperatorRef: 'visual:normalize' },
        });
        expect(body.clientRequestId).toMatch(/^canvas-suite-[0-9a-f]{64}$/);
        return new Response(JSON.stringify(suiteExecutionResponse(
          'suite-run-1', body.clientRequestId, body.suiteRef,
          { kind: 'OPERATOR', id: 'customer.normalize', fingerprint: targetFingerprint },
          [
            { caseId: 'golden', caseType: 'GOLDEN', fixtureBundleId: storedFixtureIds[0],
              fixtureFingerprint: fixtureFingerprints[0], runId: 'run-golden' },
            { caseId: 'boundary', caseType: 'BOUNDARY', fixtureBundleId: storedFixtureIds[1],
              fixtureFingerprint: fixtureFingerprints[1], runId: 'run-boundary' },
          ],
        )));
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const result = await governOperatorTestSuite({
      operatorRef: 'visual:normalize',
      lowering: { mode: 'native', operatorRef: 'customer.normalize' },
      ports: { inputs: [], outputs: [] },
    }, 'node n1', [
      { caseId: 'golden', caseType: 'GOLDEN', input: { name: 'Ada' },
        expectedOutput: { normalized: 'ADA' }, transportResponse: null },
      { caseId: 'boundary', caseType: 'BOUNDARY', input: { name: '' },
        expectedOutput: { normalized: '' }, transportResponse: null },
    ]);

    expect(result.storedFixtures).toHaveLength(2);
    expect(result.storedSuite).toMatchObject({ suiteId: storedSuiteId, fingerprint: storedSuiteFingerprint });
    expect(result.response.evidence).toMatchObject({
      status: 'PASSED',
      coverage: { status: 'SATISFIED' },
      promotion: { status: 'ELIGIBLE' },
    });
    expect(fetchMock).toHaveBeenCalledTimes(5);
  });

  it('does not execute a Canvas suite when the registry rebinds its identity', async () => {
    const fetchMock = mockSingleCaseSuiteProtocol({ storedSuiteId: 'different-suite' });

    await expect(publishSingleGoldenSuite())
      .rejects.toThrow('Suite registry returned an inconsistent stored suite identity.');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('does not execute a Canvas suite when the registry rewrites its policy', async () => {
    const fetchMock = mockSingleCaseSuiteProtocol({ storedMinimumCertifiableCases: 0 });

    await expect(publishSingleGoldenSuite())
      .rejects.toThrow('Suite registry returned an inconsistent stored suite identity.');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('rejects a suite execution response bound to another Canvas request intent', async () => {
    const fetchMock = mockSingleCaseSuiteProtocol({ executionSuiteId: 'different-suite' });

    await expect(publishSingleGoldenSuite())
      .rejects.toThrow('Suite runner returned a response for a different execution intent.');
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('rejects a suite execution response that rewrites a Canvas case intent', async () => {
    const fetchMock = mockSingleCaseSuiteProtocol({ executionCaseType: 'NEGATIVE' });

    await expect(publishSingleGoldenSuite())
      .rejects.toThrow('Suite runner returned a response for a different execution intent.');
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it.each([
    ['a pending child hidden by a PASSED aggregate', { executionCaseStatus: 'PENDING' }],
    ['a PASSED case without a child run reference', { executionRunId: '' }],
    ['an ELIGIBLE aggregate backed only by exploratory evidence', { executionEvidenceClass: 'EXPLORATORY' }],
    ['a SATISFIED aggregate below the assertion policy', {
      executionAssertionsEvaluated: 0,
      executionAssertionsPassed: 0,
    }],
  ])('rejects %s', async (_description, mutation) => {
    const fetchMock = mockSingleCaseSuiteProtocol(mutation);

    await expect(publishSingleGoldenSuite())
      .rejects.toThrow('Suite runner returned a response for a different execution intent.');
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('can route visual API calls through a custom transport', async () => {
    const transport = vi.fn(async () => new Response(JSON.stringify({
      operators: [{ operatorRef: 'local:riskScore' }],
      builtInFunctions: [{ name: 'coalesce' }],
    })));

    setBlogeApiTransport(transport);

    const catalog = await fetchOperatorCatalog();

    expect(catalog.operators[0].operatorRef).toBe('local:riskScore');
    expect(catalog.builtInFunctions?.[0].name).toBe('coalesce');
    expect(transport).toHaveBeenCalledWith('/api/visual/operators', undefined);
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

  it('sends explicit warning acknowledgement and audit reason', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      libraryId: 'side-effect-demo',
      operators: [],
    }), { status: 201 }));

    await importOperatorLibraryText('{}', true, 'Reviewed unmanaged write warning');

    const [url] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('ackWarnings=true');
    expect(String(url)).toContain('reason=Reviewed+unmanaged+write+warning');
  });

  it('adapts pasted capability catalog source as a visual library draft', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      library: { libraryId: 'risk-capabilities', operators: [] },
      validation: { valid: true, diagnostics: [] },
      projectionReview: { coverageStatus: 'FULL' },
    })));

    const result = await adaptCapabilityCatalogText('schemaVersion: bloge.capabilityCatalog.v1');

    expect(result.library?.libraryId).toBe('risk-capabilities');
    expect(fetchMock).toHaveBeenCalledWith('/admin/visual-operator-libraries/from-capability-catalog-text', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: 'schemaVersion: bloge.capabilityCatalog.v1',
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

  it('loads operator catalog functions from the visual catalog envelope', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      operators: [{ operatorRef: 'bloge:transform' }],
      builtInFunctions: [{
        name: 'coalesce',
        signatures: [{ label: 'coalesce(value, fallback)' }],
      }],
    })));

    const catalog = await fetchOperatorCatalog();

    expect(fetchMock).toHaveBeenCalledWith('/api/visual/operators');
    expect(catalog.operators).toHaveLength(1);
    expect(catalog.builtInFunctions?.[0].name).toBe('coalesce');
  });

  it('loads draft, governance, and run deep-link resources with encoded identifiers', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/visual/drafts/draft%2F42') {
        return new Response(JSON.stringify({ graphName: 'linkedGraph', nodes: [], edges: [], output: { nodeId: '' } }));
      }
      if (url === '/api/visual/governance-gates/drafts/draft%2F42') {
        return new Response(JSON.stringify({ draftId: 'draft/42', freshness: 'CURRENT', currentRevision: 3 }));
      }
      if (url === '/api/visual/runs/run%2F99') {
        return new Response(JSON.stringify({ runId: 'run/99', draftId: 'draft/42', success: true }));
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    const [draft, gate, run] = await Promise.all([
      fetchGraphDraft('draft/42'),
      fetchGovernanceGateView('draft/42'),
      fetchVisualGraphRun('run/99'),
    ]);

    expect(draft.graphName).toBe('linkedGraph');
    expect(gate.freshness).toBe('CURRENT');
    expect(run.draftId).toBe('draft/42');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('validates a transient visual graph draft', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      valid: true,
      diagnostics: [],
      readiness: { state: 'design-only', title: 'Design-only draft' },
      actionReadiness: { state: 'design-artifact-ready' },
    })));

    const result = await validateDraft({
      schemaVersion: 'bloge.visualGraphDraft.v1',
      graphName: 'visualGraph',
      nodes: [{ id: 'n1', operatorRef: 'risk:eligibility', position: { x: 0, y: 0 } }],
      edges: [],
      output: { nodeId: 'n1' },
    });

    expect(result.valid).toBe(true);
    expect(result.readiness?.state).toBe('design-only');
    expect(fetchMock).toHaveBeenCalledWith('/api/visual/drafts/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        schemaVersion: 'bloge.visualGraphDraft.v1',
        graphName: 'visualGraph',
        nodes: [{ id: 'n1', operatorRef: 'risk:eligibility', position: { x: 0, y: 0 } }],
        edges: [],
        output: { nodeId: 'n1' },
      }),
    });
  });

  it('previews schema-neutral BLOGE DSL import as an editable visual projection', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      schemaVersion: 'bloge.dslVisualProjection.v1',
      sourceId: 'migrated-eligibility.bloge',
      draft: {
        schemaVersion: 'bloge.visualGraphDraft.v1',
        graphName: 'migratedEligibility',
        nodes: [{ id: 'eligibility', operatorRef: 'risk:eligibility', position: { x: 120, y: 120 } }],
        edges: [],
        visualLayout: { import: { schemaNeutral: true } },
        output: { nodeId: 'eligibility', path: '' },
      },
      coverage: { projectedNodeCount: 1, edgeCount: 0 },
      roundTrip: {
        supported: true,
        status: 'SUPPORTED',
        message: 'Generated DSL re-parsed into the same canonical visual semantics as the source DSL.',
        generatedDsl: 'graph migratedEligibility {}',
        sourceFingerprint: 'same',
        generatedFingerprint: 'same',
        diagnostics: [],
      },
      diagnostics: [],
    })));

    const result = await previewDslImport({
      sourceId: 'migrated-eligibility.bloge',
      dsl: 'graph migratedEligibility {}',
      operatorLibraryIds: ['risk-policy'],
      mode: 'preview',
    });

    expect(result.draft.graphName).toBe('migratedEligibility');
    expect(result.roundTrip?.status).toBe('SUPPORTED');
    expect(result.roundTrip?.generatedDsl).toContain('graph migratedEligibility');
    expect(fetchMock).toHaveBeenCalledWith('/api/visual/dsl-imports/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sourceId: 'migrated-eligibility.bloge',
        dsl: 'graph migratedEligibility {}',
        operatorLibraryIds: ['risk-policy'],
        mode: 'preview',
      }),
    });
  });

  it('commits schema-neutral BLOGE DSL import as a governed visual draft', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      schemaVersion: 'bloge.visualGraphDraftImportResult.v1',
      imported: true,
      draft: {
        schemaVersion: 'bloge.visualGraphDraft.v1',
        draftId: 'stored-draft-1',
        revision: 1,
        graphName: 'migratedEligibility',
        nodes: [{ id: 'eligibility', operatorRef: 'risk:eligibility', position: { x: 120, y: 120 } }],
        edges: [],
        visualLayout: { import: { schemaNeutral: true } },
        output: { nodeId: 'eligibility', path: '' },
      },
      diagnostics: [],
      validation: { valid: true, diagnostics: [] },
    }), { status: 201 }));

    const result = await commitDslImport({
      sourceId: 'migrated-eligibility.bloge',
      dsl: 'graph migratedEligibility {}',
      operatorLibraryIds: ['risk-policy'],
      mode: 'commit',
    });

    expect(result.imported).toBe(true);
    expect(result.draft?.draftId).toBe('stored-draft-1');
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/visual/dsl-imports/commit?');
    expect(String(url)).toContain('actor=author-canvas');
    expect(String(url)).toContain('changeSource=legacy-dsl-import');
    expect(init).toMatchObject({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sourceId: 'migrated-eligibility.bloge',
        dsl: 'graph migratedEligibility {}',
        operatorLibraryIds: ['risk-policy'],
        mode: 'commit',
      }),
    });
  });

  it('checks schema-neutral BLOGE DSL rewrite gate before source replacement', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      schemaVersion: 'bloge.dslRewriteGate.v1',
      sourceId: 'migrated-eligibility.bloge',
      allowed: true,
      decision: 'ALLOW_REWRITE',
      message: 'Generated DSL has the same canonical visual semantics as the source projection.',
      generatedDsl: 'graph migratedEligibility {}',
      roundTrip: { supported: true, status: 'SUPPORTED', diagnostics: [] },
      diagnostics: [],
    })));

    const result = await checkDslRewriteGate({
      sourceId: 'migrated-eligibility.bloge',
      dsl: 'graph migratedEligibility {}',
      operatorLibraryIds: ['risk-policy'],
      mode: 'rewrite-gate',
    });

    expect(result.allowed).toBe(true);
    expect(result.decision).toBe('ALLOW_REWRITE');
    expect(fetchMock).toHaveBeenCalledWith('/api/visual/dsl-imports/rewrite-gate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sourceId: 'migrated-eligibility.bloge',
        dsl: 'graph migratedEligibility {}',
        operatorLibraryIds: ['risk-policy'],
        mode: 'rewrite-gate',
      }),
    });
  });

  it('requests a repository-level schema-neutral DSL batch report', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      schemaVersion: 'bloge.dslImportBatchReport.v1',
      mode: 'batch-report',
      summary: {
        sourceCount: 2,
        renderableSourceCount: 2,
        fullyProjectedSourceCount: 1,
        repairableSourceCount: 1,
      },
      items: [
        { sourceId: 'loan-approval.bloge', renderable: true, rewriteDecision: 'ALLOW_REWRITE' },
      ],
    })));

    const result = await batchReportDslImports({
      operatorLibraryIds: ['risk-policy'],
      mode: 'batch-report',
      includeDrafts: false,
      sources: [
        { sourceId: 'loan-approval.bloge', dsl: 'graph loanApproval {}' },
        { sourceId: 'fraud-review.bloge', dsl: 'graph fraudReview {}' },
      ],
    });

    expect(result.summary?.sourceCount).toBe(2);
    expect(result.items?.[0].rewriteDecision).toBe('ALLOW_REWRITE');
    expect(fetchMock).toHaveBeenCalledWith('/api/visual/dsl-imports/batch-report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        operatorLibraryIds: ['risk-policy'],
        mode: 'batch-report',
        includeDrafts: false,
        sources: [
          { sourceId: 'loan-approval.bloge', dsl: 'graph loanApproval {}' },
          { sourceId: 'fraud-review.bloge', dsl: 'graph fraudReview {}' },
        ],
      }),
    });
  });

  it('requests a governed DSL batch commit with author-canvas audit metadata', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      schemaVersion: 'bloge.dslImportBatchCommitResult.v1',
      mode: 'batch-commit',
      commitPolicy: 'rewrite-allowed',
      summary: {
        sourceCount: 2,
        committedSourceCount: 1,
        skippedSourceCount: 1,
      },
      items: [
        { sourceId: 'loan-approval.bloge', committed: true, commitDecision: 'COMMITTED_REWRITE_ALLOWED' },
      ],
    })));

    const result = await batchCommitDslImports({
      operatorLibraryIds: ['risk-policy'],
      mode: 'batch-commit',
      commitPolicy: 'rewrite-allowed',
      sources: [
        { sourceId: 'loan-approval.bloge', dsl: 'graph loanApproval {}' },
        { sourceId: 'fraud-review.bloge', dsl: 'graph fraudReview {}' },
      ],
    });

    expect(result.summary?.committedSourceCount).toBe(1);
    expect(result.items?.[0].commitDecision).toBe('COMMITTED_REWRITE_ALLOWED');
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/visual/dsl-imports/batch-commit?');
    expect(String(url)).toContain('actor=author-canvas');
    expect(String(url)).toContain('changeSource=legacy-dsl-batch-import');
    expect(init).toMatchObject({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        operatorLibraryIds: ['risk-policy'],
        mode: 'batch-commit',
        commitPolicy: 'rewrite-allowed',
        sources: [
          { sourceId: 'loan-approval.bloge', dsl: 'graph loanApproval {}' },
          { sourceId: 'fraud-review.bloge', dsl: 'graph fraudReview {}' },
        ],
      }),
    });
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

function executableTarget(operatorId: string, fingerprint: string) {
  return {
    schemaVersion: 'bloge.testOperatorTargetDescriptor.v2',
    target: { kind: 'OPERATOR', id: operatorId, fingerprint },
    testabilityClass: 'EXECUTABLE_UNIT',
    executionSupported: true,
    certificationEligible: true,
    certificationRequirements: [],
    certificationGaps: [],
  };
}

function storedFixture(fixtureBundleId: string, fingerprint: string) {
  return {
    schemaVersion: 'bloge.storedFixtureBundle.v1',
    tenantId: 'tenant-a',
    environmentId: 'test',
    fixtureBundleId,
    revision: 1,
    fingerprint,
    createdAt: '2026-07-15T12:00:00Z',
    createdBy: 'author-canvas',
  };
}

function suiteExecutionResponse(
  suiteRunId: string,
  clientRequestId: string,
  suiteRef: { suiteId: string; revision: number; fingerprint: string },
  target: { kind: string; id: string; fingerprint: string },
  cases: Array<{
    caseId: string;
    caseType: string;
    fixtureBundleId: string;
    fixtureFingerprint: string;
    runId: string;
  }>,
) {
  return {
    schemaVersion: 'bloge.testSuiteExecutionResponse.v1',
    suiteRunId,
    evidenceFingerprint: `sha256:${'e'.repeat(64)}`,
    evidence: {
      schemaVersion: 'bloge.testSuiteRunEvidence.v1',
      suiteRunId,
      clientRequestId,
      status: 'PASSED',
      executionPurpose: 'TEST_SUITE_EXECUTION',
      suiteRef,
      target,
      startedAt: '2026-07-15T12:00:00Z',
      completedAt: '2026-07-15T12:00:01Z',
      caseResults: cases.map((testCase) => ({
        caseId: testCase.caseId,
        caseType: testCase.caseType,
        fixtureBundleRef: {
          fixtureBundleId: testCase.fixtureBundleId,
          revision: 1,
          fingerprint: testCase.fixtureFingerprint,
        },
        status: 'PASSED',
        runId: testCase.runId,
        evidenceStatus: 'PASSED',
        evidenceClass: 'CERTIFIABLE',
        assertionsEvaluated: 1,
        assertionsPassed: 1,
        diagnosticCode: '',
        diagnostic: '',
      })),
      coverage: {
        status: 'SATISFIED',
        minimumCases: cases.length,
        completedCases: cases.length,
        requiredCaseTypes: [...new Set(cases.map((testCase) => testCase.caseType))].sort(),
        observedCaseTypes: [...new Set(cases.map((testCase) => testCase.caseType))].sort(),
        missingCaseTypes: [],
        requiredInvocationSiteIds: [],
        observedInvocationSiteIds: ['/root/subject#primary'],
        missingInvocationSiteIds: [],
        requiredEdgeTransfers: [],
        observedEdgeTransfers: [],
        missingEdgeTransfers: [],
        minimumAssertionsPerCase: 1,
        assertionDensityViolations: [],
        fixtureConsumptionViolations: [],
        allCasesCompleted: true,
      },
      promotion: {
        status: 'ELIGIBLE',
        reasons: [],
        allCasesPassed: true,
        certifiableCases: cases.length,
        minimumCertifiableCases: cases.length,
        targetCertificationEligible: true,
        coverageSatisfied: true,
        allCasesCompleted: true,
      },
      diagnostics: [],
      metadata: {},
    },
  };
}

interface SingleCaseSuiteProtocolMutation {
  storedSuiteId?: string;
  storedMinimumCertifiableCases?: number;
  executionSuiteId?: string;
  executionCaseType?: string;
  executionCaseStatus?: string;
  executionRunId?: string;
  executionEvidenceClass?: string;
  executionAssertionsEvaluated?: number;
  executionAssertionsPassed?: number;
}

function mockSingleCaseSuiteProtocol(
  mutation: SingleCaseSuiteProtocolMutation = {},
) {
  const targetFingerprint = `sha256:${'a'.repeat(64)}`;
  const fixtureFingerprint = `sha256:${'b'.repeat(64)}`;
  const suiteFingerprint = `sha256:${'d'.repeat(64)}`;
  let fixtureId = '';
  let suiteId = '';
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input);
    if (url.includes('/targets/operators/')) {
      return new Response(JSON.stringify(executableTarget('customer.normalize', targetFingerprint)));
    }
    if (url.includes('/fixture-bundles/')) {
      fixtureId = decodeURIComponent(url.slice('/api/testing/fixture-bundles/'.length));
      return new Response(JSON.stringify(storedFixture(fixtureId, fixtureFingerprint)));
    }
    if (url.includes('/suites/') && !url.endsWith('/executions')) {
      suiteId = decodeURIComponent(url.slice('/api/testing/suites/'.length));
      const body = JSON.parse(String(init?.body));
      if (mutation.storedMinimumCertifiableCases !== undefined) {
        body.testSuite.promotionPolicy.minimumCertifiableCases = mutation.storedMinimumCertifiableCases;
      }
      return new Response(JSON.stringify({
        schemaVersion: 'bloge.storedTestSuite.v1',
        tenantId: 'tenant-a',
        environmentId: 'test',
        suiteId: mutation.storedSuiteId ?? suiteId,
        revision: 1,
        fingerprint: suiteFingerprint,
        suite: body.testSuite,
        createdAt: '2026-07-15T12:00:00Z',
        createdBy: 'author-canvas',
      }));
    }
    if (url.endsWith('/executions')) {
      const body = JSON.parse(String(init?.body));
      const response = suiteExecutionResponse(
        'suite-run-negative',
        body.clientRequestId,
        {
          ...body.suiteRef,
          suiteId: mutation.executionSuiteId ?? body.suiteRef.suiteId,
        },
        { kind: 'OPERATOR', id: 'customer.normalize', fingerprint: targetFingerprint },
        [{
          caseId: 'golden',
          caseType: mutation.executionCaseType ?? 'GOLDEN',
          fixtureBundleId: fixtureId,
          fixtureFingerprint,
          runId: mutation.executionRunId ?? 'run-golden',
        }],
      );
      const result = response.evidence.caseResults[0];
      result.status = mutation.executionCaseStatus ?? result.status;
      result.evidenceClass = mutation.executionEvidenceClass ?? result.evidenceClass;
      result.assertionsEvaluated = mutation.executionAssertionsEvaluated ?? result.assertionsEvaluated;
      result.assertionsPassed = mutation.executionAssertionsPassed ?? result.assertionsPassed;
      return new Response(JSON.stringify(response));
    }
    throw new Error(`Unexpected fetch: ${url}`);
  });
}

function publishSingleGoldenSuite() {
  return governOperatorTestSuite({
    operatorRef: 'visual:normalize',
    lowering: { mode: 'native', operatorRef: 'customer.normalize' },
    ports: { inputs: [], outputs: [] },
  }, 'node n1', [{
    caseId: 'golden',
    caseType: 'GOLDEN',
    input: {},
    expectedOutput: {},
    transportResponse: null,
  }]);
}
