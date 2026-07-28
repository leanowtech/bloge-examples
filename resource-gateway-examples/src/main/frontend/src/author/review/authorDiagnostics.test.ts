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
});
