import type {
  VisualAuthoringDiagnostic,
  VisualAuthoringRuntimeParity,
  VisualLibraryAuthoringCompileResult,
} from '../types';
import type { MessageDescriptor, MessageId } from '../i18n/messageCatalog';

export type ReadinessTone = 'ready' | 'review' | 'blocked' | 'pending';

export interface ReadinessPresentation {
  tone: ReadinessTone;
  title: MessageDescriptor;
  summary: MessageDescriptor;
  nextAction: MessageDescriptor;
  machineState: string;
  boundRuntimeCount: number;
  runtimeAssetCount: number;
}

export interface RuntimeParityPresentation {
  state: MessageDescriptor;
  detail: MessageDescriptor;
  rawCode: string;
  rawDetail: string;
}

export interface GroupedAuthoringDiagnostic extends VisualAuthoringDiagnostic {
  occurrences: number;
}

const RUNTIME_REASON_MESSAGE_IDS: Record<string, MessageId> = {
  'RG.AUTHORING.RUNTIME_OPERATOR_MISSING': 'library.runtime.reason.operatorMissing',
  'RG.AUTHORING.RUNTIME_FUNCTION_MISSING': 'library.runtime.reason.functionMissing',
  'RG.AUTHORING.RUNTIME_OPERATOR_BINDING_MISSING': 'library.runtime.reason.operatorBindingMissing',
  'RG.AUTHORING.RUNTIME_OPERATOR_CONTRACT_UNKNOWN': 'library.runtime.reason.operatorContractUnknown',
  'RG.AUTHORING.RUNTIME_FUNCTION_CONTRACT_UNKNOWN': 'library.runtime.reason.functionContractUnknown',
  'RG.AUTHORING.RUNTIME_OPERATOR_LOWERING_UNVERIFIED': 'library.runtime.reason.operatorLoweringUnverified',
  'RG.AUTHORING.RUNTIME_OPERATOR_DRIFT': 'library.runtime.reason.operatorDrift',
  'RG.AUTHORING.RUNTIME_FUNCTION_AMBIGUOUS': 'library.runtime.reason.functionAmbiguous',
  'RG.AUTHORING.RUNTIME_FUNCTION_POLICY_BLOCKED': 'library.runtime.reason.functionPolicyBlocked',
  'RG.AUTHORING.RUNTIME_FUNCTION_SIGNATURE_UNKNOWN': 'library.runtime.reason.functionSignatureUnknown',
  'RG.AUTHORING.RUNTIME_FUNCTION_SIGNATURE_DRIFT': 'library.runtime.reason.functionSignatureDrift',
  'RG.AUTHORING.RUNTIME_BINDING_CONFIRMATION_REQUIRED': 'library.runtime.reason.confirmationRequired',
  'RG.AUTHORING.RUNTIME_SIGNATURES_REQUIRED': 'library.runtime.reason.signaturesRequired',
};

/** Translates compiler and runtime coordinates into one conservative author-facing conclusion. */
export function presentLibraryReadiness(
  preview: VisualLibraryAuthoringCompileResult | null,
): ReadinessPresentation {
  if (!preview) {
    return presentation(
      'pending',
      'library.readiness.awaitingValidation.title',
      'library.readiness.awaitingValidation.summary',
      'library.readiness.awaitingValidation.action',
      'PENDING',
      0,
      0,
    );
  }
  const runtime = preview.runtimeParity ?? [];
  const bound = runtime.filter((item) => item.executableReady).length;
  const total = runtime.length;
  const errors = preview.diagnostics.filter((diagnostic) => (
    diagnostic.level.trim().toUpperCase() === 'ERROR'
  )).length;

  if (!preview.readiness.importable || errors > 0 || preview.readiness.state === 'INVALID') {
    const blockerCount = errors || 1;
    return presentation(
      'blocked',
      'library.readiness.designBlocked.title',
      'library.readiness.designBlocked.summary',
      'library.readiness.designBlocked.action',
      preview.readiness.state,
      bound,
      total,
      { count: blockerCount },
    );
  }
  if (preview.readiness.productionReady) {
    return presentation(
      'ready',
      'library.readiness.ready.title',
      'library.readiness.ready.summary',
      'library.readiness.ready.action',
      preview.readiness.state,
      bound,
      total,
      { count: total },
    );
  }
  if (preview.readiness.designReady) {
    if (total === 0) {
      return presentation(
        'review',
        'library.readiness.runtimeUnknown.title',
        'library.readiness.runtimeUnknown.summary',
        'library.readiness.runtimeUnknown.action',
        preview.readiness.state,
        bound,
        total,
      );
    }
    if (bound === 0) {
      return presentation(
        'review',
        'library.readiness.runtimeUnbound.title',
        'library.readiness.runtimeUnbound.summary',
        'library.readiness.runtimeUnbound.action',
        preview.readiness.state,
        bound,
        total,
        { bound, total },
      );
    }
    return presentation(
      'review',
      'library.readiness.runtimePartial.title',
      'library.readiness.runtimePartial.summary',
      'library.readiness.runtimePartial.action',
      preview.readiness.state,
      bound,
      total,
      { bound, total },
    );
  }
  return presentation(
    'review',
    'library.readiness.schemaReview.title',
    'library.readiness.schemaReview.summary',
    'library.readiness.schemaReview.action',
    preview.readiness.state,
    bound,
    total,
  );
}

/** Projects protocol runtime parity into stable product messages; raw protocol text stays technical. */
export function presentRuntimeParity(
  parity: VisualAuthoringRuntimeParity,
): RuntimeParityPresentation {
  const stateIds: Record<VisualAuthoringRuntimeParity['state'], MessageId> = {
    BOUND: 'library.runtime.state.bound',
    DRIFTED: 'library.runtime.state.drifted',
    DOCUMENTED_ONLY: 'library.runtime.state.documentedOnly',
    RUNTIME_DISCOVERED: 'library.runtime.state.discovered',
    BLOCKED_BY_POLICY: 'library.runtime.state.blockedByPolicy',
    UNKNOWN: 'library.runtime.state.unknown',
  };
  const detailIds: Record<VisualAuthoringRuntimeParity['state'], MessageId> = {
    BOUND: 'library.runtime.detail.bound',
    DRIFTED: 'library.runtime.detail.drifted',
    DOCUMENTED_ONLY: 'library.runtime.detail.documentedOnly',
    RUNTIME_DISCOVERED: 'library.runtime.detail.discovered',
    BLOCKED_BY_POLICY: 'library.runtime.detail.blockedByPolicy',
    UNKNOWN: 'library.runtime.detail.unknown',
  };
  const state = stateIds[parity.state] ?? 'library.runtime.state.unknown';
  const detail = RUNTIME_REASON_MESSAGE_IDS[parity.reasonCode]
    ?? detailIds[parity.state]
    ?? 'library.runtime.detail.unknown';
  return {
    state: { messageId: state },
    detail: {
      messageId: detail,
      rawCode: parity.reasonCode,
      rawDetail: parity.message,
    },
    rawCode: parity.reasonCode,
    rawDetail: parity.message,
  };
}

/** Groups diagnostics by stable code, target, and explicit root cause while retaining frequency. */
export function groupAuthoringDiagnostics(
  diagnostics: VisualAuthoringDiagnostic[],
): GroupedAuthoringDiagnostic[] {
  const grouped = new Map<string, GroupedAuthoringDiagnostic>();
  diagnostics.forEach((diagnostic) => {
    const rootCause = String(
      diagnostic.metadata?.rootCause
        ?? diagnostic.metadata?.rootCauseCode
        ?? diagnostic.message,
    );
    const key = `${diagnostic.code}:${diagnostic.authoringPath}:${rootCause}`;
    const existing = grouped.get(key);
    if (existing) {
      existing.occurrences += 1;
      return;
    }
    grouped.set(key, { ...diagnostic, occurrences: 1 });
  });
  return Array.from(grouped.values());
}

function presentation(
  tone: ReadinessTone,
  title: MessageId,
  summary: MessageId,
  nextAction: MessageId,
  machineState: string,
  boundRuntimeCount: number,
  runtimeAssetCount: number,
  params?: Record<string, string | number>,
): ReadinessPresentation {
  return {
    tone,
    title: { messageId: title },
    summary: { messageId: summary, params },
    nextAction: { messageId: nextAction },
    machineState,
    boundRuntimeCount,
    runtimeAssetCount,
  };
}
