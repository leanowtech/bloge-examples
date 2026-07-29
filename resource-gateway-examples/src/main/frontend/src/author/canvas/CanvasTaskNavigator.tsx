import { useMemo, useState } from 'react';

import type { CanvasLayoutQualityReport } from './layoutQuality';

export type CanvasTaskMode = 'overview' | 'focus' | 'inspect';

export interface CanvasTaskNode {
  id: string;
  label: string;
  operatorRef: string;
  pinned: boolean;
}

interface CanvasTaskNavigatorProps {
  mode: CanvasTaskMode;
  nodes: CanvasTaskNode[];
  selectedNodeId: string;
  nodeCount: number;
  edgeCount: number;
  pathNodeCount: number;
  zoomPercent: string;
  mapVisible: boolean;
  layoutPlanning: boolean;
  layoutPreview: boolean;
  layoutQuality: CanvasLayoutQualityReport | null;
  layoutNotice: string;
  canUndoLayout: boolean;
  onModeChange: (mode: CanvasTaskMode) => void;
  onSelectNode: (nodeId: string) => void;
  onFitAll: () => void;
  onToggleMap: () => void;
  onTogglePin: () => void;
  onApplyLayout: () => void;
  onCancelLayout: () => void;
  onUndoLayout: () => void;
}

/** Task-oriented canvas navigation that stays outside the graph rendering surface. */
export default function CanvasTaskNavigator({
  mode,
  nodes,
  selectedNodeId,
  nodeCount,
  edgeCount,
  pathNodeCount,
  zoomPercent,
  mapVisible,
  layoutPlanning,
  layoutPreview,
  layoutQuality,
  layoutNotice,
  canUndoLayout,
  onModeChange,
  onSelectNode,
  onFitAll,
  onToggleMap,
  onTogglePin,
  onApplyLayout,
  onCancelLayout,
  onUndoLayout,
}: CanvasTaskNavigatorProps) {
  const [query, setQuery] = useState('');
  const selected = nodes.find((node) => node.id === selectedNodeId) ?? null;
  const matches = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return [];
    return nodes
      .filter((node) => (
        node.id.toLowerCase().includes(normalized)
        || node.label.toLowerCase().includes(normalized)
        || node.operatorRef.toLowerCase().includes(normalized)
      ))
      .slice(0, 6);
  }, [nodes, query]);

  return (
    <section
      className={`canvas-task-navigator mode-${mode}`}
      data-testid="canvas-navigator"
      aria-label="Canvas task navigator"
    >
      <div className="canvas-task-modes" role="group" aria-label="Canvas reading mode">
        {(['overview', 'focus', 'inspect'] as const).map((candidate) => (
          <button
            key={candidate}
            type="button"
            data-testid={`canvas-task-mode:${candidate}`}
            aria-pressed={mode === candidate}
            disabled={candidate !== 'overview' && !selected}
            title={candidate === 'overview'
              ? 'Fit and read the complete graph shape'
              : candidate === 'focus'
                ? 'Emphasize the selected node and its complete business path'
                : 'Open the selected node context'}
            onClick={() => onModeChange(candidate)}
          >
            {candidate[0].toUpperCase() + candidate.slice(1)}
          </button>
        ))}
      </div>

      <nav className="canvas-focus-breadcrumb" aria-label="Canvas focus breadcrumb">
        <button type="button" onClick={() => onModeChange('overview')}>All nodes</button>
        {selected && (
          <>
            <span aria-hidden="true">/</span>
            <strong title={selected.operatorRef}>{selected.label}</strong>
          </>
        )}
        {mode === 'focus' && (
          <>
            <span aria-hidden="true">/</span>
            <em>{pathNodeCount} in path</em>
          </>
        )}
      </nav>

      <div className="canvas-node-finder">
        <label>
          <span className="visually-hidden">Find canvas node</span>
          <input
            type="search"
            value={query}
            placeholder="Find node..."
            aria-label="Find canvas node"
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        {query.trim() && (
          <div className="canvas-node-results" role="listbox" aria-label="Matching canvas nodes">
            {matches.map((node) => (
              <button
                key={node.id}
                type="button"
                role="option"
                aria-selected={node.id === selectedNodeId}
                data-testid={`canvas-node-result:${node.id}`}
                onClick={() => {
                  onSelectNode(node.id);
                  setQuery('');
                }}
              >
                <strong>{node.label}</strong>
                <code>{node.operatorRef}</code>
              </button>
            ))}
            {matches.length === 0 && <span>No matching nodes</span>}
          </div>
        )}
      </div>

      <div className="canvas-task-facts">
        <span>{nodeCount} nodes</span>
        <span>{edgeCount} edges</span>
        <strong data-testid="canvas-zoom-readout">{zoomPercent}</strong>
      </div>

      <div className="canvas-task-actions">
        <button type="button" data-testid="navigator-fit-all" onClick={onFitAll} title="Fit all nodes">
          Fit
        </button>
        <button
          type="button"
          data-testid="navigator-map-toggle"
          aria-pressed={mapVisible}
          onClick={onToggleMap}
          title={mapVisible ? 'Hide the overview navigator' : 'Show the overview navigator'}
        >
          {mapVisible ? 'Hide map' : 'Map'}
        </button>
        <button
          type="button"
          data-testid="navigator-pin-node"
          aria-pressed={Boolean(selected?.pinned)}
          disabled={!selected}
          onClick={onTogglePin}
          title="Keep the selected node fixed during Auto Layout"
        >
          {selected?.pinned ? 'Unpin' : 'Pin'}
        </button>
        {canUndoLayout && !layoutPreview && (
          <button type="button" data-testid="navigator-undo-layout" onClick={onUndoLayout}>
            Undo
          </button>
        )}
      </div>

      {(layoutPlanning || layoutPreview || layoutNotice) && (
        <div
          className={`canvas-layout-review ${layoutQuality?.status.toLowerCase() ?? 'pending'}`}
          data-testid="canvas-layout-review"
          role="status"
          aria-live="polite"
        >
          <span data-testid="layout-notice">
            {layoutPlanning
              ? 'Computing layout preview...'
              : layoutQuality?.summary || layoutNotice}
          </span>
          {layoutQuality?.edgeLabelCollisionDetails?.[0] && (
            <small>
              {layoutQuality.edgeLabelCollisionDetails[0].edgeId} label intersects{' '}
              {layoutQuality.edgeLabelCollisionDetails[0].nodeId}
            </small>
          )}
          {(layoutPlanning || layoutPreview) && (
            <div>
              {layoutPreview && (
                <button type="button" data-testid="layout-apply" onClick={onApplyLayout}>
                  Apply
                </button>
              )}
              <button type="button" data-testid="layout-cancel" onClick={onCancelLayout}>
                Cancel
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
