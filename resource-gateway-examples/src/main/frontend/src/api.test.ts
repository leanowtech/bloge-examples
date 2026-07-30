import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  adaptCapabilityCatalogText,
  applyLibraryAuthoringSamples,
  batchCommitDslImports,
  batchReportDslImports,
  buildGatewayRunRequest,
  checkDslRewriteGate,
  commitLibraryAuthoringDraft,
  commitDslImport,
  decideScenarioRehearsalRemediation,
  draftLibraryAuthoringFunctionTest,
  draftLibraryAuthoringOperatorTest,
  fetchGatewayDiagram,
  fetchGatewayScenarios,
  fetchGovernanceGateView,
  fetchGraphDraft,
  fetchLibraryAuthoringCatalogs,
  fetchLibraryAuthoringDraft,
  fetchLibraryAuthoringDrafts,
  fetchOperatorCatalog,
  fetchScenarioRehearsalBatchItems,
  fetchScenarioRehearsalBatchJobs,
  fetchScenarioRehearsalBatchWorkbook,
  fetchScenarioRehearsalRemediationComparison,
  fetchScenarioRehearsalRemediationLineage,
  fetchScenarioRehearsalWorkbook,
  fetchScenarioCompatibility,
  fetchScenarioDraftSet,
  fetchScenarioGraphContract,
  fetchVisualGraphRun,
  governOperatorTestCase,
  governOperatorTestSuite,
  importOperatorLibraryText,
  inferLibraryAuthoringSamples,
  previewDslImport,
  previewLibraryAuthoringDraft,
  previewScenarioRehearsalRemediation,
  publishScenarioDraftSet,
  resetOperatorTestHeadersProvider,
  resetRehearsalRemediationCredentialsProvider,
  runLibraryAuthoringFunctionTest,
  runLibraryAuthoringOperatorTest,
  runOperatorTestCase,
  runGatewayScenario,
  resetBlogeApiTransport,
  setBlogeApiTransport,
  setRehearsalRemediationCredentialsProvider,
  saveGraphDraft,
  saveLibraryAuthoringDraft,
  saveLibraryAuthoringFixture,
  saveScenarioDraftSet,
  submitScenarioRehearsalRemediation,
  validateDraft,
  validateOperatorLibraryText,
} from './api';
import type {
  VisualAuthoringFixtureSaveRequest,
  VisualFunctionTestSuite,
  VisualLibraryAuthoringCompileResult,
  VisualLibraryAuthoringDocument,
  VisualOperatorContractTestSuite,
  VisualSampleInferenceRequest,
  VisualSampleInferenceResult,
} from './types';

describe('operator library API client', () => {
  afterEach(() => {
    resetBlogeApiTransport();
    resetOperatorTestHeadersProvider();
    resetRehearsalRemediationCredentialsProvider();
    vi.restoreAllMocks();
  });

  it('discovers governed fixture availability and fences explicit fixture saves', async () => {
    const request: VisualAuthoringFixtureSaveRequest = {
      schemaVersion: 'bloge.visualAuthoringFixtureSaveRequest.v1',
      fixtureId: 'operator:demo.echo:golden',
      expectedFixtureRevision: 0,
      sourceKind: 'OPERATOR_TEST_CASE',
      assetKind: 'OPERATOR',
      assetRef: 'demo:echo',
      classification: 'INTERNAL',
      retentionDays: 7,
      redactionPaths: ['/inputs/token'],
      payload: { inputs: { token: 'secret' } },
    };
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({
        schemaVersion: 'bloge.visualLibraryAuthoringCatalogs.v1',
        limits: { maximumAuthoringFixtureRetentionDays: 30 },
        features: { governedFixturePersistence: true },
      }))
      .mockResolvedValueOnce(jsonResponse({
        schemaVersion: 'bloge.visualAuthoringFixtureReceipt.v1',
        fixtureId: request.fixtureId,
        revision: 1,
        payloadReturned: false,
      }));

    const catalogs = await fetchLibraryAuthoringCatalogs();
    const receipt = await saveLibraryAuthoringFixture('draft/one', 4, request);

    expect(catalogs.features.governedFixturePersistence).toBe(true);
    expect(receipt.payloadReturned).toBe(false);
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/admin/visual-operator-library-authoring/drafts/draft%2Fone/fixtures',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: 'Bearer bloge-aneke-demo-token',
          'X-Purpose': 'TEST_FIXTURE_WRITE',
          'Content-Type': 'application/json',
          'If-Match': '"4"',
        }),
        body: JSON.stringify(request),
      }),
    );
  });

  it('fences progressive library save, inference apply, preview, and commit with exact revisions', async () => {
    const document: VisualLibraryAuthoringDocument = {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: { id: 'support-tools', version: '1.0.0', owner: 'support-team' },
      operators: {},
      functions: {},
      types: {},
    };
    const stored = {
      schemaVersion: 'bloge.visualLibraryAuthoringDraft.v1',
      draftId: 'support-draft',
      revision: 3,
      sourceMode: 'QUICK',
      document,
      fingerprint: 'sha256:draft',
      createdAt: '2026-07-30T00:00:00Z',
      updatedAt: '2026-07-30T00:00:01Z',
      savedBy: 'visual-library-workbench',
    };
    const preview: VisualLibraryAuthoringCompileResult = {
      schemaVersion: 'bloge.visualLibraryCompileResult.v1',
      draftId: 'support-draft',
      authoringRevision: 3,
      authoringFingerprint: 'sha256:authoring',
      compileFingerprint: 'sha256:compile',
      compilerVersion: '1',
      grammarVersion: '1',
      catalogFingerprint: 'sha256:catalog',
      previewAuthority: 'SERVER_AUTHORITATIVE',
      canonicalFingerprint: 'sha256:canonical',
      sourceMap: [],
      diagnostics: [],
      confirmationRequests: [],
      readiness: {
        state: 'READY',
        importable: true,
        strongSchemaReady: true,
        designReady: true,
        productionReady: false,
        gates: [],
      },
      diff: {
        libraryId: 'support-tools',
        baseRevision: 11,
        changed: true,
        addedOperatorCount: 0,
        removedOperatorCount: 0,
        changedOperatorCount: 0,
      },
    };
    const inferenceRequest: VisualSampleInferenceRequest = {
      schemaVersion: 'bloge.visualSampleInferenceRequest.v1',
      target: {
        assetKind: 'OPERATOR',
        assetRef: 'support:classify',
        portDirection: 'INPUT',
        portName: 'request',
      },
      samples: [{ priority: 'HIGH' }, { priority: 'LOW' }],
      options: {
        suggestEnums: true,
        suggestFormats: true,
        persistPayload: false,
      },
      idempotencyKey: 'support-inference-1',
    };
    const evidenceFingerprint = `sha256:${'a'.repeat(64)}`;
    const confirmationId = `sha256:${'b'.repeat(64)}`;
    const inferenceResult: VisualSampleInferenceResult = {
      schemaVersion: 'bloge.visualSampleInferenceResult.v1',
      draftId: 'support-draft',
      authoringRevision: 3,
      target: inferenceRequest.target,
      evidenceFingerprint,
      inferencerVersion: 'sample-inferencer-v1',
      redactionProfileVersion: 'redaction-v1',
      sampleCount: 2,
      candidate: {
        fields: { priority: 'string' },
        additionalProperties: true,
      },
      observations: [],
      confirmationRequests: [{
        confirmationId,
        factId: `sha256:${'c'.repeat(64)}`,
        code: 'RG.AUTHORING.INFERENCE_ENUM_CONFIRMATION_REQUIRED',
        authoringPath: '/operators/support:classify/input/request/priority',
        question: 'Do the values form a complete enum?',
        recommendedValue: 'KEEP_STRING',
        allowedValues: ['KEEP_STRING', 'DECLARE_ENUM'],
        blocking: false,
      }],
      diagnostics: [],
      payloadPersisted: false,
    };
    const decisions = [{ confirmationId, value: 'KEEP_STRING' }];
    const applied = {
      ...stored,
      revision: 4,
      document: {
        ...document,
        operators: {
          'support:classify': {
            input: {
              request: inferenceResult.candidate,
            },
          },
        },
      },
    };
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      const url = String(input);
      calls.push({ url, init });
      if (url.endsWith('/drafts') && !init) {
        return jsonResponse([stored]);
      }
      if (url.endsWith('/drafts/support-draft') && !init) {
        return jsonResponse(stored);
      }
      if (url.endsWith('/drafts/support-draft') && init?.method === 'PUT') {
        const headers = new Headers(init.headers);
        expect(headers.get('If-Match')).toBe('"2"');
        expect(JSON.parse(String(init.body))).toEqual({
          sourceMode: 'QUICK',
          document,
          actor: 'visual-library-workbench',
        });
        return jsonResponse(stored);
      }
      if (url.endsWith('/drafts/support-draft/infer/samples/apply')) {
        expect(new Headers(init?.headers).get('If-Match')).toBe('"3"');
        expect(JSON.parse(String(init?.body))).toEqual({
          schemaVersion: 'bloge.visualSampleInferenceApplyRequest.v1',
          inference: inferenceRequest,
          evidenceFingerprint,
          decisions,
          actor: 'visual-library-workbench',
        });
        return jsonResponse(applied);
      }
      if (url.endsWith('/drafts/support-draft/infer/samples')) {
        expect(new Headers(init?.headers).get('If-Match')).toBe('"3"');
        expect(JSON.parse(String(init?.body))).toEqual(inferenceRequest);
        return jsonResponse(inferenceResult);
      }
      if (url.endsWith('/drafts/support-draft/preview')) {
        expect(new Headers(init?.headers).get('If-Match')).toBe('"3"');
        return jsonResponse(preview);
      }
      if (url.endsWith('/drafts/support-draft/commit')) {
        expect(new Headers(init?.headers).get('If-Match')).toBe('"3"');
        expect(JSON.parse(String(init?.body))).toMatchObject({
          authoringFingerprint: 'sha256:authoring',
          compileFingerprint: 'sha256:compile',
          catalogFingerprint: 'sha256:catalog',
          canonicalFingerprint: 'sha256:canonical',
          targetRevision: 11,
          actor: 'visual-library-workbench',
          reason: 'contract reviewed',
        });
        return jsonResponse({ schemaVersion: 'bloge.visualLibraryAuthoringCommitResult.v1' });
      }
      throw new Error(`Unexpected request: ${url}`);
    });

    await expect(fetchLibraryAuthoringDrafts()).resolves.toEqual([stored]);
    await expect(fetchLibraryAuthoringDraft('support-draft')).resolves.toEqual(stored);
    await expect(saveLibraryAuthoringDraft('support-draft', 2, document)).resolves.toEqual(stored);
    await expect(inferLibraryAuthoringSamples('support-draft', 3, inferenceRequest))
      .resolves.toEqual(inferenceResult);
    await expect(applyLibraryAuthoringSamples(
      'support-draft',
      3,
      inferenceRequest,
      evidenceFingerprint,
      decisions,
    )).resolves.toEqual(applied);
    await expect(previewLibraryAuthoringDraft('support-draft', 3)).resolves.toEqual(preview);
    await expect(commitLibraryAuthoringDraft('support-draft', 3, preview, 'contract reviewed'))
      .resolves.toMatchObject({ schemaVersion: 'bloge.visualLibraryAuthoringCommitResult.v1' });
    expect(calls).toHaveLength(7);
  });

  it('fences authoring operator and function test drafts and runs to one exact revision', async () => {
    const operatorSuite: VisualOperatorContractTestSuite = {
      schemaVersion: 'bloge.visualOperatorContractTestSuiteRequest.v1',
      operatorRef: 'demo:echo',
      cases: [],
    };
    const functionSuite: VisualFunctionTestSuite = {
      schemaVersion: 'bloge.visualAuthoringFunctionTestSuite.v1',
      functionRef: 'trim',
      cases: [],
    };
    const calls: Array<{ url: string; body: unknown; ifMatch: string | null }> = [];
    setBlogeApiTransport(async (input, init) => {
      const url = String(input);
      const body = JSON.parse(String(init?.body));
      calls.push({
        url,
        body,
        ifMatch: new Headers(init?.headers).get('If-Match'),
      });
      return jsonResponse({ route: url });
    });

    await draftLibraryAuthoringOperatorTest('test draft', 7, 'demo:echo');
    await runLibraryAuthoringOperatorTest('test draft', 7, operatorSuite);
    await draftLibraryAuthoringFunctionTest('test draft', 7, 'trim');
    await runLibraryAuthoringFunctionTest('test draft', 7, functionSuite);

    expect(calls).toEqual([
      expect.objectContaining({
        url: '/admin/visual-operator-library-authoring/drafts/test%20draft/tests/operators/draft',
        ifMatch: '"7"',
        body: expect.objectContaining({
          schemaVersion: 'bloge.visualAuthoringOperatorTestDraftRequest.v1',
          draft: expect.objectContaining({ operatorRef: 'demo:echo' }),
        }),
      }),
      expect.objectContaining({
        url: '/admin/visual-operator-library-authoring/drafts/test%20draft/tests/operators/run',
        ifMatch: '"7"',
        body: {
          schemaVersion: 'bloge.visualAuthoringOperatorTestRunRequest.v1',
          suite: operatorSuite,
        },
      }),
      expect.objectContaining({
        url: '/admin/visual-operator-library-authoring/drafts/test%20draft/tests/functions/draft',
        ifMatch: '"7"',
        body: {
          schemaVersion: 'bloge.visualAuthoringFunctionTestDraftRequest.v1',
          functionRef: 'trim',
        },
      }),
      expect.objectContaining({
        url: '/admin/visual-operator-library-authoring/drafts/test%20draft/tests/functions/run',
        ifMatch: '"7"',
        body: {
          schemaVersion: 'bloge.visualAuthoringFunctionTestRunRequest.v1',
          suite: functionSuite,
        },
      }),
    ]);
  });

  it('saves, loads, and publishes an exact Scenario revision with separate purposes', async () => {
    const draftSet = {
      schemaVersion: 'bloge.scenarioDraftSet.v1' as const,
      scenarioDraftSetId: 'loan-scenarios',
      revision: 3,
      scope: {
        tenantId: 'local-demo',
        organizationId: 'resource-gateway',
        projectId: 'loanGraph',
        environment: 'test',
        region: 'local',
      },
      target: {
        kind: 'GRAPH' as const,
        id: 'loan-graph',
        revision: 2,
        fingerprint: `sha256:${'a'.repeat(64)}`,
      },
      contractFingerprint: `sha256:${'b'.repeat(64)}`,
      scenarios: [],
      metadata: {
        owner: 'canvas-author',
        classification: 'INTERNAL' as const,
        createdAt: null,
        updatedAt: null,
        provenance: {},
      },
    };
    const stored = {
      schemaVersion: 'bloge.storedScenarioDraftSet.v1' as const,
      scenarioDraftSetId: draftSet.scenarioDraftSetId,
      revision: 4,
      fingerprint: `sha256:${'c'.repeat(64)}`,
      draftSet: { ...draftSet, revision: 4 },
      savedAt: '2026-07-27T00:00:00Z',
      savedBy: 'canvas-author',
    };
    const publication = {
      schemaVersion: 'bloge.storedScenarioPublication.v1' as const,
      stateVersion: 2,
      fingerprint: `sha256:${'d'.repeat(64)}`,
      report: {
        schemaVersion: 'bloge.scenarioPublicationReport.v1' as const,
        publicationId: 'scenario-publication-1',
        scope: draftSet.scope,
        source: {
          scenarioDraftSetId: draftSet.scenarioDraftSetId,
          revision: 4,
          fingerprint: stored.fingerprint,
          targetKind: 'GRAPH' as const,
          targetId: draftSet.target.id,
          targetFingerprint: draftSet.target.fingerprint,
          contractFingerprint: draftSet.contractFingerprint,
          compilerSchemaVersion: 'bloge.scenarioGovernedCompilationPlan.v1' as const,
          compilationPlanFingerprint: `sha256:${'e'.repeat(64)}`,
        },
        runtimeTarget: {
          kind: 'GRAPH' as const,
          id: 'loanGraph',
          fingerprint: `sha256:${'f'.repeat(64)}`,
        },
        status: 'PUBLISHED' as const,
        attempt: 1,
        fixtures: [],
        suite: null,
        diagnostics: [],
        failure: { stage: '', code: '', retryable: false },
        startedAt: '2026-07-27T00:00:00Z',
        updatedAt: '2026-07-27T00:00:01Z',
        completedAt: '2026-07-27T00:00:01Z',
        actor: 'canvas-author',
      },
    };
    const compatibility = {
      schemaVersion: 'bloge.contractCompatibilityReport.v1' as const,
      scenarioDraftSetId: draftSet.scenarioDraftSetId,
      scenarioRevision: 4,
      target: draftSet.target,
      baselineContractFingerprint: draftSet.contractFingerprint,
      currentContractFingerprint: draftSet.contractFingerprint,
      policy: 'STRICT' as const,
      classification: 'UNCHANGED' as const,
      findings: [],
      impactedScenarios: [],
      migrations: [],
      generatedAt: '2026-07-27T00:00:00Z',
      reportFingerprint: `sha256:${'9'.repeat(64)}`,
    };
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.includes('?expectedRevision=3')) {
        expect(init).toMatchObject({
          method: 'PUT',
          headers: expect.objectContaining({
            'X-Purpose': 'TEST_SUITE_WRITE',
            'Content-Type': 'application/json',
          }),
        });
        expect(JSON.parse(String(init?.body))).toEqual(draftSet);
        return new Response(JSON.stringify(stored));
      }
      if (url.endsWith('/loan-scenarios')) {
        expect(init?.headers).toMatchObject({ 'X-Purpose': 'TEST_SUITE_READ' });
        return new Response(JSON.stringify(stored));
      }
      if (url.endsWith('/loan-scenarios/publications?revision=4')) {
        expect(init).toMatchObject({
          method: 'POST',
          headers: expect.objectContaining({ 'X-Purpose': 'TEST_SCENARIO_PUBLISH' }),
        });
        return new Response(JSON.stringify(publication));
      }
      if (url.endsWith('/loan-scenarios/compatibility?revision=4')) {
        expect(init?.headers).toMatchObject({ 'X-Purpose': 'TEST_SUITE_READ' });
        return new Response(JSON.stringify(compatibility));
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await expect(saveScenarioDraftSet(draftSet)).resolves.toEqual(stored);
    await expect(fetchScenarioDraftSet('loan-scenarios')).resolves.toEqual(stored);
    await expect(fetchScenarioCompatibility('loan-scenarios', 4)).resolves.toEqual(compatibility);
    await expect(publishScenarioDraftSet('loan-scenarios', 4)).resolves.toEqual(publication);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('persists a Graph before loading its authoritative Scenario Contract coordinate', async () => {
    const draft = {
      schemaVersion: 'bloge.visualGraphDraft.v1',
      graphName: 'loanGraph',
      tenantId: 'tenant-a',
      environment: 'test',
      nodes: [],
      edges: [],
      output: { nodeId: '' },
    };
    const stored = { ...draft, draftId: 'loan-graph', revision: 1 };
    const projection = {
      schemaVersion: 'bloge.scenarioContractProjection.v1' as const,
      scope: {
        tenantId: 'tenant-a',
        organizationId: 'knowledge-governance',
        projectId: 'tool-studio',
        environment: 'test',
        region: 'local',
      },
      contract: {
        schemaVersion: 'bloge.contractDraft.v1' as const,
        target: {
          kind: 'GRAPH' as const,
          id: 'loan-graph',
          revision: 1,
          fingerprint: `sha256:${'a'.repeat(64)}`,
        },
        inputSchema: { format: 'json-schema', version: '2020-12', schema: {} },
        outputSchema: { format: 'json-schema', version: '2020-12', schema: {} },
        errorContract: [],
        executionSemantics: {
          effect: 'UNKNOWN' as const,
          idempotency: 'UNKNOWN',
          streaming: null,
          durable: null,
        },
        invariants: [],
        compatibilityPolicy: {
          mode: 'STRICT' as const,
          unknownBlocksAutomaticMigration: true,
        },
        fieldMetadata: {},
        source: 'AUTHORED' as const,
        confidence: 'OPAQUE' as const,
      },
      contractFingerprint: `sha256:${'b'.repeat(64)}`,
    };
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.startsWith('/api/visual/drafts?')) {
        expect(init?.method).toBe('POST');
        expect(JSON.parse(String(init?.body))).toEqual(draft);
        return new Response(JSON.stringify(stored));
      }
      if (url.endsWith('/scenario-draft-sets/targets/graphs/loan-graph/contract')) {
        expect(init?.headers).toMatchObject({ 'X-Purpose': 'TEST_SUITE_READ' });
        return new Response(JSON.stringify(projection));
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await expect(saveGraphDraft(draft)).resolves.toEqual(stored);
    await expect(fetchScenarioGraphContract('loan-graph')).resolves.toEqual(projection);
    expect(fetchMock).toHaveBeenCalledTimes(2);
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
          schemaVersion: 'bloge.storedFixtureBundle.v2',
          tenantId: 'tenant-a',
          organizationId: 'knowledge-governance',
          projectId: 'tool-studio',
          environmentId: 'test',
          region: 'region-a',
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
          schemaVersion: 'bloge.storedTestSuite.v2',
          tenantId: 'tenant-a',
          organizationId: 'knowledge-governance',
          projectId: 'tool-studio',
          environmentId: 'test',
          region: 'region-a',
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

  it('reads exact-scope Scenario rehearsal discovery pages with governance-purpose authentication', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      expect(init?.headers).toMatchObject({
        Authorization: 'Bearer bloge-aneke-demo-token',
        'X-Purpose': 'GOVERNANCE_EVIDENCE_INGESTION',
      });
      if (url.includes('/items?')) {
        expect(url).toBe('/api/mirror/rehearsal-jobs/job-1/items?startIndex=100&limit=25');
        return mirrorEnvelope(
          'SCENARIO_REHEARSAL_BATCH_ITEM_PAGE',
          'resourceGateway.scenarioRehearsalBatchItemPage.v1',
          {
            schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemPage.v1',
            jobId: 'job-1',
            manifestFingerprint: 'sha256:manifest',
            items: [],
            nextIndex: null,
          },
        );
      }
      expect(url).toContain('/api/mirror/rehearsal-jobs?limit=10');
      expect(url).toContain('beforeCreatedAt=2026-07-25T10%3A00%3A00Z');
      expect(url).toContain('beforeJobId=job-2');
      return mirrorEnvelope(
        'SCENARIO_REHEARSAL_BATCH_JOB_PAGE',
        'resourceGateway.scenarioRehearsalBatchJobPage.v1',
        {
          schemaVersion: 'resourceGateway.scenarioRehearsalBatchJobPage.v1',
          scope: {
            tenantId: 'tenant-a',
            organizationId: 'knowledge',
            projectId: 'tool-studio',
            environmentId: 'test',
            region: 'sg',
          },
          jobs: [],
          nextCursor: null,
        },
      );
    });

    const jobs = await fetchScenarioRehearsalBatchJobs(10, {
      createdAt: '2026-07-25T10:00:00Z',
      jobId: 'job-2',
    });
    const items = await fetchScenarioRehearsalBatchItems('job-1', 100, 25);

    expect(jobs.scope.projectId).toBe('tool-studio');
    expect(items.jobId).toBe('job-1');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('reads terminal root and child workbooks without exposing a write API', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      expect(init?.method).toBeUndefined();
      expect(init?.headers).toMatchObject({
        'X-Purpose': 'GOVERNANCE_EVIDENCE_INGESTION',
      });
      if (url.endsWith('/rehearsal-jobs/job%2F1/workbook-seed')) {
        return mirrorEnvelope(
          'SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED',
          'resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1',
          {
            schemaVersion: 'resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1',
            jobId: 'job/1',
            entries: [],
            blockers: [],
          },
        );
      }
      expect(url).toBe('/api/mirror/scenarios/runs/run%2F1/workbook-seed');
      return mirrorEnvelope(
        'SCENARIO_REHEARSAL_WORKBOOK_SEED',
        'resourceGateway.scenarioRehearsalWorkbookSeed.v1',
        {
          schemaVersion: 'resourceGateway.scenarioRehearsalWorkbookSeed.v1',
          runId: 'run/1',
          cases: [],
          blockers: [],
        },
      );
    });

    const root = await fetchScenarioRehearsalBatchWorkbook('job/1');
    const child = await fetchScenarioRehearsalWorkbook('run/1');

    expect(root.jobId).toBe('job/1');
    expect(child.runId).toBe('run/1');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('fails closed when a Mirror endpoint returns a different payload contract', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(mirrorEnvelope(
      'SCENARIO_REHEARSAL_BATCH_ITEM_PAGE',
      'resourceGateway.scenarioRehearsalBatchItemPage.v1',
      {},
    ));

    await expect(fetchScenarioRehearsalBatchJobs()).rejects
      .toThrow('Mirror response contract mismatch for SCENARIO_REHEARSAL_BATCH_JOB_PAGE.');
  });

  it('fails before transport when a reviewed remediation role is not configured', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch');

    await expect(previewScenarioRehearsalRemediation('scenario-batch-1', {
      schemaVersion: 'resourceGateway.scenarioRehearsalRemediationPreviewRequest.v1',
      previewRequestId: 'preview-1',
      expectedWorkbookSeedFingerprint: `sha256:${'a'.repeat(64)}`,
      strategy: 'RERUN_EXACT',
      replacements: [],
      governanceTicketRef: {
        kind: 'GOVERNANCE_REVIEW_TICKET',
        id: 'ANEKE-42',
        revision: 1,
        fingerprint: `sha256:${'b'.repeat(64)}`,
      },
      reasonCode: 'TRANSIENT_EXECUTION_RECHECK',
    })).rejects.toThrow('Owner remediation identity is not configured by the host.');

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('routes every remediation operation through its role-separated credential slot and exact contract', async () => {
    const planFingerprint = `sha256:${'c'.repeat(64)}`;
    const approvalFingerprint = `sha256:${'d'.repeat(64)}`;
    setRehearsalRemediationCredentialsProvider((slot) => ({
      headers: {
        Authorization: `Bearer ${slot.toLowerCase()}-credential`,
        'X-Purpose': 'CALLER_CANNOT_OVERRIDE_PURPOSE',
        'Content-Type': 'text/plain',
      },
      principalLabel: `${slot.toLowerCase()}@example.test`,
      expiresAt: '2026-08-01T00:00:00Z',
    }));
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      expect(init?.headers).toMatchObject({
        'X-Purpose': 'MIRROR_REHEARSAL_REMEDIATION',
      });
      if (url.endsWith('/rehearsal-jobs/scenario-batch-1/remediations')) {
        expect(init?.headers).toMatchObject({
          Authorization: 'Bearer owner-credential',
          'Content-Type': 'application/json',
        });
        expect(JSON.parse(String(init?.body))).toMatchObject({
          previewRequestId: 'preview-1',
          expectedWorkbookSeedFingerprint: `sha256:${'a'.repeat(64)}`,
          strategy: 'RERUN_EXACT',
          replacements: [],
          reasonCode: 'TRANSIENT_EXECUTION_RECHECK',
        });
        return mirrorEnvelope(
          'SCENARIO_REHEARSAL_REMEDIATION_PLAN',
          'resourceGateway.scenarioRehearsalRemediationPlan.v1',
          {
            schemaVersion: 'resourceGateway.scenarioRehearsalRemediationPlan.v1',
            remediationId: 'scenario-remediation-1',
            planFingerprint,
          },
        );
      }
      if (url.endsWith('/rehearsal-remediations/scenario-remediation-1/approvals')) {
        const command = JSON.parse(String(init?.body));
        expect(init?.headers).toMatchObject({
          Authorization: command.role === 'OWNER'
            ? 'Bearer owner-credential'
            : 'Bearer independent_reviewer-credential',
          'Content-Type': 'application/json',
        });
        return mirrorEnvelope(
          'SCENARIO_REHEARSAL_REMEDIATION_APPROVAL',
          'resourceGateway.scenarioRehearsalRemediationApproval.v1',
          {
            schemaVersion: 'resourceGateway.scenarioRehearsalRemediationApproval.v1',
            approvalFingerprint,
            role: command.role,
          },
        );
      }
      if (url.endsWith('/rehearsal-remediations/scenario-remediation-1/submissions')) {
        expect(init?.headers).toMatchObject({
          Authorization: 'Bearer owner-credential',
          'Content-Type': 'application/json',
        });
        expect(JSON.parse(String(init?.body))).toMatchObject({
          expectedApprovalGeneration: 2,
          expectedApprovalHeadFingerprint: approvalFingerprint,
        });
        return mirrorEnvelope(
          'SCENARIO_REHEARSAL_REMEDIATION_RECEIPT',
          'resourceGateway.scenarioRehearsalRemediationReceipt.v1',
          {
            schemaVersion: 'resourceGateway.scenarioRehearsalRemediationReceipt.v1',
            successorJobId: 'scenario-batch-2',
          },
        );
      }
      if (url.endsWith('/rehearsal-remediations/scenario-remediation-1/comparison')) {
        expect(init?.headers).toMatchObject({ Authorization: 'Bearer read-credential' });
        return mirrorEnvelope(
          'SCENARIO_REHEARSAL_REMEDIATION_COMPARISON',
          'resourceGateway.scenarioRehearsalRemediationComparison.v1',
          {
            schemaVersion: 'resourceGateway.scenarioRehearsalRemediationComparison.v1',
            gateTransition: 'RESOLVED',
          },
        );
      }
      expect(url).toBe('/api/mirror/rehearsal-remediations/scenario-remediation-1');
      expect(init?.headers).toMatchObject({ Authorization: 'Bearer read-credential' });
      return mirrorEnvelope(
        'SCENARIO_REHEARSAL_REMEDIATION_LINEAGE',
        'resourceGateway.scenarioRehearsalRemediationLineage.v1',
        {
          schemaVersion: 'resourceGateway.scenarioRehearsalRemediationLineage.v1',
          state: 'PENDING_APPROVAL',
        },
      );
    });
    const ticket = {
      kind: 'GOVERNANCE_REVIEW_TICKET',
      id: 'ANEKE-42',
      revision: 1,
      fingerprint: `sha256:${'b'.repeat(64)}`,
    };

    const plan = await previewScenarioRehearsalRemediation('scenario-batch-1', {
      schemaVersion: 'resourceGateway.scenarioRehearsalRemediationPreviewRequest.v1',
      previewRequestId: 'preview-1',
      expectedWorkbookSeedFingerprint: `sha256:${'a'.repeat(64)}`,
      strategy: 'RERUN_EXACT',
      replacements: [],
      governanceTicketRef: ticket,
      reasonCode: 'TRANSIENT_EXECUTION_RECHECK',
    });
    await fetchScenarioRehearsalRemediationLineage('scenario-remediation-1');
    await decideScenarioRehearsalRemediation('scenario-remediation-1', {
      schemaVersion: 'resourceGateway.scenarioRehearsalRemediationApprovalCommand.v1',
      commandId: 'owner-decision',
      remediationPlanFingerprint: planFingerprint,
      expectedApprovalGeneration: 0,
      role: 'OWNER',
      decision: 'APPROVE',
      governanceTicketRef: ticket,
      reasonCode: 'APPROVED_AS_REVIEWED',
    });
    await decideScenarioRehearsalRemediation('scenario-remediation-1', {
      schemaVersion: 'resourceGateway.scenarioRehearsalRemediationApprovalCommand.v1',
      commandId: 'reviewer-decision',
      remediationPlanFingerprint: planFingerprint,
      expectedApprovalGeneration: 1,
      role: 'INDEPENDENT_REVIEWER',
      decision: 'APPROVE',
      governanceTicketRef: ticket,
      reasonCode: 'APPROVED_AS_REVIEWED',
    });
    await submitScenarioRehearsalRemediation('scenario-remediation-1', {
      schemaVersion: 'resourceGateway.scenarioRehearsalRemediationSubmitCommand.v1',
      commandId: 'submit-1',
      remediationPlanFingerprint: planFingerprint,
      expectedApprovalGeneration: 2,
      expectedApprovalHeadFingerprint: approvalFingerprint,
      reasonCode: 'APPROVALS_COMPLETE',
    });
    await fetchScenarioRehearsalRemediationComparison('scenario-remediation-1');

    expect(plan.remediationId).toBe('scenario-remediation-1');
    expect(fetchMock).toHaveBeenCalledTimes(6);
  });
});

function mirrorEnvelope(payloadKind: string, payloadSchemaVersion: string, payload: unknown): Response {
  return new Response(JSON.stringify({
    protocol: 'ToolStudioResourceGatewayProtocol',
    protocolVersion: '1.0.0',
    resourceGatewayVersion: '1.0.0',
    schemaVersion: 'toolStudio.integrationEnvelope.v1',
    producedAt: '2026-07-25T10:00:00Z',
    payloadKind,
    payloadSchemaVersion,
    payloadFingerprint: 'sha256:payload',
    payload,
  }));
}

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

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init.headers },
  });
}
