import { describe, expect, it } from 'vitest';

import type { VisualLibraryAuthoringCompileResult } from '../types';
import {
  groupAuthoringDiagnostics,
  presentLibraryReadiness,
} from './readinessPresentation';

describe('library readiness presentation', () => {
  it('states that a design-ready catalog with zero bindings cannot execute', () => {
    const view = presentLibraryReadiness(preview({
      state: 'DESIGN_READY',
      designReady: true,
      productionReady: false,
      runtimeParity: [
        parity('OPERATOR', 'support:lookup'),
        parity('FUNCTION', 'support.normalize'),
      ],
    }));

    expect(view).toMatchObject({
      tone: 'review',
      title: 'Design valid; runtime unbound',
      summary: '0/2 declared assets can execute in this deployment.',
      boundRuntimeCount: 0,
      runtimeAssetCount: 2,
    });
  });

  it('distinguishes production runtime readiness from design validity', () => {
    const view = presentLibraryReadiness(preview({
      state: 'RUNTIME_BOUND',
      designReady: true,
      productionReady: true,
      runtimeParity: [{
        ...parity('FUNCTION', 'trim'),
        state: 'BOUND',
        executableReady: true,
      }],
    }));

    expect(view.title).toBe('Ready to execute');
    expect(view.tone).toBe('ready');
  });

  it('uses singular wording when a non-importable draft has no emitted error', () => {
    const result = preview({
      state: 'INVALID',
      designReady: false,
      productionReady: false,
      runtimeParity: [],
    });
    result.readiness.importable = false;

    expect(presentLibraryReadiness(result).summary).toBe(
      '1 blocking Contract problem must be resolved.',
    );
  });

  it('groups repeated diagnostics by code, target, and root cause', () => {
    const diagnostics = Array.from({ length: 3 }, (_, index) => ({
      level: 'WARNING',
      code: 'RG.AUTHORING.RUNTIME_UNBOUND',
      message: `Asset is not bound (${index}).`,
      authoringPath: '/operators/support:lookup',
      metadata: { rootCause: 'missing-runtime-binding' },
    }));

    expect(groupAuthoringDiagnostics(diagnostics)).toEqual([
      expect.objectContaining({
        code: 'RG.AUTHORING.RUNTIME_UNBOUND',
        occurrences: 3,
      }),
    ]);
  });
});

function preview(options: {
  state: string;
  designReady: boolean;
  productionReady: boolean;
  runtimeParity: VisualLibraryAuthoringCompileResult['runtimeParity'];
}): VisualLibraryAuthoringCompileResult {
  return {
    schemaVersion: 'bloge.visualLibraryCompileResult.v1',
    draftId: 'draft',
    authoringRevision: 1,
    authoringFingerprint: 'sha256:authoring',
    compileFingerprint: 'sha256:compile',
    compilerVersion: '1',
    grammarVersion: '1',
    catalogFingerprint: 'sha256:catalog',
    runtimeParity: options.runtimeParity,
    previewAuthority: 'SERVER_AUTHORITATIVE',
    canonicalFingerprint: 'sha256:canonical',
    sourceMap: [],
    diagnostics: [],
    confirmationRequests: [],
    readiness: {
      state: options.state,
      importable: true,
      strongSchemaReady: true,
      designReady: options.designReady,
      productionReady: options.productionReady,
      gates: [],
    },
  };
}

function parity(
  assetKind: 'OPERATOR' | 'FUNCTION',
  assetRef: string,
): NonNullable<VisualLibraryAuthoringCompileResult['runtimeParity']>[number] {
  return {
    assetKind,
    assetRef,
    runtimeProfile: 'default',
    state: 'DOCUMENTED_ONLY',
    executableReady: false,
    declaredFingerprint: 'sha256:declared',
    runtimeFingerprint: '',
    reasonCode: 'RUNTIME_BINDING_MISSING',
    message: 'No exact runtime binding was discovered.',
  };
}
