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
