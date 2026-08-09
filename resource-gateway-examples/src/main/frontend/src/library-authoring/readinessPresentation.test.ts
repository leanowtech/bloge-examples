import { describe, expect, it } from 'vitest';

import type { VisualLibraryAuthoringCompileResult } from '../types';
import {
  groupAuthoringDiagnostics,
  groupRuntimeParity,
  presentLibraryReadiness,
  presentRuntimeParity,
  selectedAssetRootBlocker,
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
      title: { messageId: 'library.readiness.runtimeUnbound.title' },
      summary: {
        messageId: 'library.readiness.runtimeUnbound.summary',
        params: { bound: 0, total: 2 },
      },
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

    expect(view.title.messageId).toBe('library.readiness.ready.title');
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

    expect(presentLibraryReadiness(result).summary).toEqual({
      messageId: 'library.readiness.designBlocked.summary',
      params: { count: 1 },
    });
  });

  it('projects runtime wire states to stable messages and retains protocol detail separately', () => {
    const runtime = parity('OPERATOR', 'support:lookup');

    expect(presentRuntimeParity(runtime)).toEqual({
      state: { messageId: 'library.runtime.state.documentedOnly' },
      detail: {
        messageId: 'library.runtime.detail.documentedOnly',
        rawCode: 'RUNTIME_BINDING_MISSING',
        rawDetail: 'No exact runtime binding was discovered.',
      },
      rawCode: 'RUNTIME_BINDING_MISSING',
      rawDetail: 'No exact runtime binding was discovered.',
    });
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

  it('aggregates repeated runtime warnings by root reason', () => {
    const groups = groupRuntimeParity([
      parity('OPERATOR', 'support:lookup'),
      parity('OPERATOR', 'support:search'),
      { ...parity('FUNCTION', 'support.normalize'), reasonCode: 'FUNCTION_SIGNATURE_UNKNOWN' },
    ]);

    expect(groups).toHaveLength(2);
    const bindingGroup = groups.find((group) => group.reasonCode === 'RUNTIME_BINDING_MISSING');
    expect(bindingGroup).toMatchObject({
      reasonCode: 'RUNTIME_BINDING_MISSING',
      occurrences: 2,
    });
    expect(bindingGroup?.items.map((item) => item.assetRef)).toEqual(['support:lookup', 'support:search']);
  });

  it('selects one design root blocker before runtime symptoms for the current asset', () => {
    const result = preview({
      state: 'INVALID',
      designReady: false,
      productionReady: false,
      runtimeParity: [parity('OPERATOR', 'support:lookup')],
    });
    result.diagnostics = [{
      level: 'ERROR',
      code: 'RG.AUTHORING.INPUT_REQUIRED',
      message: 'Input Contract is missing.',
      authoringPath: '/operators/support:lookup/input',
      metadata: {},
    }, {
      level: 'WARNING',
      code: 'RG.AUTHORING.DESCRIPTION_MISSING',
      message: 'Description is missing.',
      authoringPath: '/operators/support:lookup/description',
      metadata: {},
    }];

    expect(selectedAssetRootBlocker(result, 'operator', 'support:lookup')).toMatchObject({
      source: 'DESIGN',
      tone: 'blocked',
      title: { messageId: 'library.blocker.designError.title' },
      rawCode: 'RG.AUTHORING.INPUT_REQUIRED',
    });
  });

  it('uses the leading runtime reason when the selected asset has no design blocker', () => {
    const result = preview({
      state: 'DESIGN_READY',
      designReady: true,
      productionReady: false,
      runtimeParity: [parity('FUNCTION', 'support.normalize')],
    });

    expect(selectedAssetRootBlocker(result, 'function', 'support.normalize')).toMatchObject({
      source: 'RUNTIME',
      title: { messageId: 'library.blocker.runtime.title' },
      rawCode: 'RUNTIME_BINDING_MISSING',
    });
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
