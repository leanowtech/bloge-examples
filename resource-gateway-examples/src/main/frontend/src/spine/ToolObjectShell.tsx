import type { ReactNode } from 'react';

import ObjectBreadcrumb from './ObjectBreadcrumb';
import ToolThreadRail from './ToolThreadRail';
import type { ToolCoordinate } from './authorSpine';
import './authorSpine.css';

export interface ToolObjectShellProps {
  coordinate: ToolCoordinate;
  children: ReactNode;
}

/** Composes the coordinate-aware breadcrumb and lifecycle rail around a workspace. */
export default function ToolObjectShell({ coordinate, children }: ToolObjectShellProps) {
  const selectedNodeId = new URLSearchParams(window.location.search).get('nodeId')?.trim() || undefined;
  return (
    <div className="spine-object-shell">
      <ObjectBreadcrumb coordinate={coordinate} selectedNodeId={selectedNodeId} />
      <ToolThreadRail coordinate={coordinate} />
      <div className="spine-object-content">{children}</div>
    </div>
  );
}
