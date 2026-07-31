import { describe, expect, it } from 'vitest';

import { projectAuthorDiagnostics } from './authorDiagnostics';

describe('projectAuthorDiagnostics', () => {
  it('orders governance blockers before runtime errors and warnings', () => {
    const items = projectAuthorDiagnostics({
      error: 'network unavailable',
      validation: {
        valid: false,
        diagnostics: [{
          level: 'warning',
          code: 'SCHEMA_WARNING',
          message: 'Schema is open.',
          target: '/nodes/transform/output',
        }],
      },
      run: null,
      scenarioResults: {},
      governance: {
        draftId: 'draft-1',
        currentRevision: 1,
        currentDraftFingerprint: 'sha256:draft',
        freshness: 'CURRENT',
        result: {
          gateResultId: 'gate-1',
          target: { draftId: 'draft-1', revision: 1, draftFingerprint: 'sha256:draft' },
          status: 'BLOCKED',
          issues: [{
            issueId: 'owner',
            severity: 'BLOCKING',
            code: 'OWNER_REQUIRED',
            message: 'Owner approval is missing.',
            targetPath: '/nodes/policy',
            recommendedAction: 'Request owner approval',
            deepLink: 'https://governance.example/gates/gate-1',
            requiredRole: 'Business owner',
            owner: 'Customer Operations',
            auditRequirement: 'Retain signed approval.',
          }],
        },
      },
      dslDiagnostics: [],
    });

    expect(items.map((item) => item.severity)).toEqual(['BLOCKING', 'ERROR', 'WARNING']);
    expect(items[0]).toMatchObject({
      scope: 'GOVERNANCE',
      nodeId: 'policy',
      coordinate: '/nodes/policy',
      recommendedAction: 'Request owner approval',
      deepLink: 'https://governance.example/gates/gate-1',
      requiredRole: 'Business owner',
      owner: 'Customer Operations',
      auditRequirement: 'Retain signed approval.',
    });
  });

  it('turns failed Scenario rows and runtime diagnostics into distinct scoped items', () => {
    const items = projectAuthorDiagnostics({
      error: '',
      validation: null,
      run: {
        validated: true,
        compiled: true,
        success: false,
        graphName: 'risk',
        outputNode: 'policy',
        output: null,
        results: {},
        statusMap: {},
        mockedNodeIds: [],
        realNodeIds: [],
        terminalOutputConforms: false,
        diagnostics: [{
          level: 'error',
          code: 'NODE_FAILED',
          message: 'Policy failed.',
          target: '/nodes/policy',
        }],
        errors: [],
        generatedDsl: '',
      },
      scenarioResults: {
        boundary: {
          id: 'boundary',
          name: 'Boundary case',
          status: 'failed',
          detail: 'Output mismatch.',
        },
      },
      governance: null,
      dslDiagnostics: [],
    });

    expect(items).toEqual(expect.arrayContaining([
      expect.objectContaining({ scope: 'RUN', code: 'NODE_FAILED', nodeId: 'policy' }),
      expect.objectContaining({ scope: 'SCENARIO', code: 'ASSERTION_FAILED' }),
    ]));
  });

  it('groups repeated diagnostics by root cause and preserves the occurrence count', () => {
    const repeated = {
      level: 'warning',
      code: 'bloge.dsl',
      message: "Path 'profile.output.payload' - field 'payload' not found.",
      target: '/nodes/profile/output',
    };
    const items = projectAuthorDiagnostics({
      error: '',
      validation: null,
      run: {
        validated: true,
        compiled: true,
        success: true,
        graphName: 'risk',
        outputNode: 'response',
        output: {},
        results: {},
        statusMap: {},
        mockedNodeIds: ['profile'],
        realNodeIds: ['response'],
        terminalOutputConforms: true,
        diagnostics: [repeated, repeated, repeated],
        errors: [],
        generatedDsl: '',
      },
      scenarioResults: {},
      governance: null,
      dslDiagnostics: [],
    });

    expect(items).toEqual([
      expect.objectContaining({
        code: 'bloge.dsl',
        occurrenceCount: 3,
        nodeId: 'profile',
      }),
    ]);
  });

  it('promotes effective Contract conflicts into the shared repair queue', () => {
    const items = projectAuthorDiagnostics({
      error: '',
      validation: null,
      run: null,
      scenarioResults: {},
      governance: null,
      dslDiagnostics: [],
      effectiveContract: {
        target: { nodeId: 'decision', operatorRef: 'decision-table' },
        declaredInputs: [],
        declaredOutputs: [],
        inferredOutputs: [],
        observedOutputs: [],
        activeBindings: [],
        conflicts: [{
          path: 'inputs.score',
          code: 'MULTIPLE_SOURCES',
          message: 'The target receives two authoritative sources.',
          types: ['number'],
        }],
        confidence: 'CONFLICTED',
        provenance: {
          declared: 'Operator catalog',
          inferred: [],
          bound: ['edge-1', 'edge-2'],
          observed: 'No run observation',
        },
      },
    });

    expect(items).toEqual([
      expect.objectContaining({
        severity: 'ERROR',
        scope: 'CONTRACT',
        code: 'EFFECTIVE_CONTRACT_MULTIPLE_SOURCES',
        nodeId: 'decision',
        coordinate: '/nodes/decision/contract/inputs.score',
      }),
    ]);
  });

  it('projects dirty and stale lifecycle reasons into the shared repair queue', () => {
    const items = projectAuthorDiagnostics({
      error: '',
      validation: null,
      run: null,
      scenarioResults: {},
      governance: null,
      dslDiagnostics: [],
      readinessReasons: [
        {
          code: 'DRAFT_DIRTY',
          dimension: 'DRAFT',
          message: 'The current graph differs from its saved revision.',
          action: { label: 'Save current draft' },
        },
        {
          code: 'EXECUTION_STALE',
          dimension: 'EXECUTION',
          message: 'The retained run targets an older authoring snapshot.',
          action: { label: 'Run current Scenario' },
        },
      ],
    });

    expect(items).toEqual([
      expect.objectContaining({
        scope: 'GRAPH',
        source: 'readiness',
        code: 'DRAFT_DIRTY',
        recommendedAction: 'Save current draft',
      }),
      expect.objectContaining({
        scope: 'EXECUTION',
        source: 'readiness',
        code: 'EXECUTION_STALE',
        recommendedAction: 'Run current Scenario',
      }),
    ]);
  });
});
