import type { ReactNode } from 'react';

import type { AuthorMode } from './authorWorkspaceState';

interface AuthorSurfaceRouterProps {
  mode: AuthorMode;
  targetKind: 'graph' | 'operator';
  contextRailExpanded: boolean;
  onOpenContextRail: () => void;
  children: ReactNode;
}

/**
 * Owns the one-to-one mapping between a non-Compose task mode and the central authoring region.
 */
export default function AuthorSurfaceRouter({
  mode,
  targetKind,
  contextRailExpanded,
  onOpenContextRail,
  children,
}: AuthorSurfaceRouterProps) {
  if (mode === 'compose') {
    return null;
  }
  return (
    <section
      className="author-central-surface"
      data-testid={`author-surface:${mode}`}
      data-target-kind={targetKind}
    >
      <button
        type="button"
        className="secondary compact author-context-rail-launcher"
        data-testid="author-context-rail-launcher"
        aria-expanded={contextRailExpanded}
        onClick={onOpenContextRail}
      >
        Topology
      </button>
      {children}
    </section>
  );
}
