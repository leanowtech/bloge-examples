import type {
  VisualAuthoringDiagnostic,
  VisualLibraryAuthoringCompileResult,
} from '../types';

export type ReadinessTone = 'ready' | 'review' | 'blocked' | 'pending';

export interface ReadinessPresentation {
  tone: ReadinessTone;
  title: string;
  summary: string;
  nextAction: string;
  machineState: string;
  boundRuntimeCount: number;
  runtimeAssetCount: number;
}

export interface GroupedAuthoringDiagnostic extends VisualAuthoringDiagnostic {
  occurrences: number;
}

/** Translates compiler and runtime coordinates into one conservative author-facing conclusion. */
export function presentLibraryReadiness(
  preview: VisualLibraryAuthoringCompileResult | null,
): ReadinessPresentation {
  if (!preview) {
    return presentation(
      'pending',
      'Awaiting validation',
      'No server-authoritative Contract preview is available yet.',
      'Validate the current draft.',
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
      'Design blocked',
      `${blockerCount} blocking Contract problem${blockerCount === 1 ? '' : 's'} must be resolved.`,
      'Open the first blocking diagnostic.',
      preview.readiness.state,
      bound,
      total,
    );
  }
  if (preview.readiness.productionReady) {
    return presentation(
      'ready',
      'Ready to execute',
      `All ${total} runtime asset${total === 1 ? '' : 's'} are bound to this exact Contract.`,
      'Run the Contract test suite before promotion.',
      preview.readiness.state,
      bound,
      total,
    );
  }
  if (preview.readiness.designReady) {
    if (total === 0) {
      return presentation(
        'review',
        'Design valid; runtime not verified',
        'The Contract can be imported, but this deployment did not provide runtime inventory evidence.',
        'Connect or discover runtime inventory.',
        preview.readiness.state,
        bound,
        total,
      );
    }
    if (bound === 0) {
      return presentation(
        'review',
        'Design valid; runtime unbound',
        `0/${total} declared assets can execute in this deployment.`,
        'Bind an exact runtime implementation or keep this catalog design-only.',
        preview.readiness.state,
        bound,
        total,
      );
    }
    return presentation(
      'review',
      'Design valid; runtime partially bound',
      `${bound}/${total} declared assets can execute in this deployment.`,
      'Resolve the remaining runtime bindings.',
      preview.readiness.state,
      bound,
      total,
    );
  }
  return presentation(
    'review',
    'Schema review required',
    'The catalog can be documented, but unresolved types prevent a strong Contract.',
    'Replace unresolved types or explicitly accept an open schema.',
    preview.readiness.state,
    bound,
    total,
  );
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
  title: string,
  summary: string,
  nextAction: string,
  machineState: string,
  boundRuntimeCount: number,
  runtimeAssetCount: number,
): ReadinessPresentation {
  return {
    tone,
    title,
    summary,
    nextAction,
    machineState,
    boundRuntimeCount,
    runtimeAssetCount,
  };
}
