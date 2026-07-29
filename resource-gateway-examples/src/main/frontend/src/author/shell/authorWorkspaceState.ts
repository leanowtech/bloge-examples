export type AuthorMode = 'compose' | 'contract' | 'scenarios' | 'evidence';

export type AuthorPrimaryActionKind =
  | 'focus-palette'
  | 'fix-input'
  | 'run'
  | 'review-failures'
  | 'review-result';

export interface AuthorPrimaryAction {
  kind: AuthorPrimaryActionKind;
  label: string;
  targetMode: AuthorMode;
}

export interface AuthorPrimaryActionContext {
  nodeCount: number;
  busy: boolean;
  hasInputErrors: boolean;
  hasRunResult: boolean;
  runSuccessful: boolean;
}

/**
 * Derives the only visually-primary command from the current authoring state.
 *
 * Keeping this projection pure prevents the journey strip, toolbar, and canvas coach from each
 * inventing a competing next action. Secondary commands remain available but never masquerade as
 * the recommended next step.
 */
export function resolveAuthorPrimaryAction(
  context: AuthorPrimaryActionContext,
): AuthorPrimaryAction {
  if (context.nodeCount === 0) {
    return { kind: 'focus-palette', label: 'Add first operator', targetMode: 'compose' };
  }
  if (context.hasInputErrors) {
    return { kind: 'fix-input', label: 'Fix required input', targetMode: 'scenarios' };
  }
  if (!context.hasRunResult) {
    return {
      kind: 'run',
      label: context.busy ? 'Running scenario...' : 'Run scenario',
      targetMode: 'scenarios',
    };
  }
  if (!context.runSuccessful) {
    return { kind: 'review-failures', label: 'Review failures', targetMode: 'evidence' };
  }
  return { kind: 'review-result', label: 'Review result', targetMode: 'evidence' };
}
