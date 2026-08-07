import { useMemo, useState } from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import type { MessageDescriptor } from '../../i18n/messageCatalog';
import type {
  CanvasPerceptualQualityReport,
  CanvasSemanticMode,
  CanvasTopologyLane,
} from './canvasSemantics';
import type { CanvasLayoutQualityReport } from './layoutQuality';
import {
  presentCanvasPerceptualQuality,
  presentCanvasQuality,
  presentLayoutCollision,
} from './layoutQualityPresentation';
import type {
  LayoutAcceptanceDecision,
  LayoutRegression,
} from './layoutAcceptance';

export type CanvasTaskMode = CanvasSemanticMode;

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
  layoutAcceptance: LayoutAcceptanceDecision | null;
  perceptualQuality: CanvasPerceptualQualityReport;
  topologyLanes: CanvasTopologyLane[];
  layoutNotice: MessageDescriptor | null;
  canUndoLayout: boolean;
  onModeChange: (mode: CanvasTaskMode) => void;
  onSelectNode: (nodeId: string) => void;
  onFitAll: () => void;
  onToggleMap: () => void;
  onTogglePin: () => void;
  onApplyLayout: () => void;
  onOverrideLayout: () => void;
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
  layoutAcceptance,
  perceptualQuality,
  topologyLanes,
  layoutNotice,
  canUndoLayout,
  onModeChange,
  onSelectNode,
  onFitAll,
  onToggleMap,
  onTogglePin,
  onApplyLayout,
  onOverrideLayout,
  onCancelLayout,
  onUndoLayout,
}: CanvasTaskNavigatorProps) {
  const { t, m } = useI18n();
  const [query, setQuery] = useState('');
  const selected = nodes.find((node) => node.id === selectedNodeId) ?? null;
  const perceptualPresentation = presentCanvasPerceptualQuality(perceptualQuality);
  const candidatePerception = layoutAcceptance?.candidate.perception ?? perceptualQuality;
  const candidatePresentation = layoutQuality
    ? presentCanvasQuality(layoutQuality, candidatePerception)
    : null;
  const collisionPresentation = layoutQuality?.edgeLabelCollisionDetails?.[0]
    ? presentLayoutCollision(layoutQuality.edgeLabelCollisionDetails[0])
    : null;
  const perceptualTitle = perceptualPresentation.reasons.length > 0
    ? perceptualPresentation.reasons
      .map((reason) => m(reason.messageId, reason.params))
      .join(' ')
    : m(perceptualPresentation.perception.messageId, perceptualPresentation.perception.params);
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
      data-complex={nodeCount >= 9}
      aria-label={t('Canvas task navigator')}
    >
      <div className="canvas-task-modes" role="group" aria-label={t('Canvas reading mode')}>
        {(['overview', 'focus', 'inspect'] as const).map((candidate) => (
          <button
            key={candidate}
            type="button"
            data-testid={`canvas-task-mode:${candidate}`}
            aria-pressed={mode === candidate}
            disabled={candidate !== 'overview' && !selected}
            title={candidate === 'overview'
              ? t('Fit and read the complete graph shape')
              : candidate === 'focus'
                ? t('Emphasize the selected node and its complete business path')
                : t('Open the selected node context')}
            onClick={() => onModeChange(candidate)}
          >
            {t(candidate[0].toUpperCase() + candidate.slice(1))}
          </button>
        ))}
      </div>

      <nav className="canvas-focus-breadcrumb" aria-label={t('Canvas focus breadcrumb')}>
        <button type="button" onClick={() => onModeChange('overview')}>{t('All nodes')}</button>
        {selected && (
          <>
            <span aria-hidden="true">/</span>
            <strong title={selected.operatorRef}>{selected.label}</strong>
          </>
        )}
        {mode === 'focus' && (
          <>
            <span aria-hidden="true">/</span>
            <em>{t('{count} in path', { count: pathNodeCount })}</em>
          </>
        )}
      </nav>

      <div className="canvas-node-finder">
        <label>
          <span className="visually-hidden">{t('Find canvas node')}</span>
          <input
            type="search"
            value={query}
            placeholder={t('Find node...')}
            aria-label={t('Find canvas node')}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        {query.trim() && (
          <div className="canvas-node-results" role="listbox" aria-label={t('Matching canvas nodes')}>
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
            {matches.length === 0 && <span>{t('No matching nodes')}</span>}
          </div>
        )}
      </div>

      <div className="canvas-task-facts">
        <span>{t('{count} nodes', { count: nodeCount })}</span>
        <span>{t('{count} edges', { count: edgeCount })}</span>
        <strong data-testid="canvas-zoom-readout">{zoomPercent}</strong>
        <span
          className={`canvas-readability-verdict ${perceptualQuality.status.toLowerCase()}`}
          data-testid="canvas-readability-verdict"
          title={perceptualTitle}
        >
          {t('Readability {status}', { status: t(perceptualQuality.status) })}
        </span>
      </div>

      <div className="canvas-task-actions">
        <button type="button" data-testid="navigator-fit-all" onClick={onFitAll} title={t('Fit all nodes')}>
          {t('Fit')}
        </button>
        <button
          type="button"
          data-testid="navigator-map-toggle"
          aria-pressed={mapVisible}
          onClick={onToggleMap}
          title={t(mapVisible ? 'Hide the overview navigator' : 'Show the overview navigator')}
        >
          {t(mapVisible ? 'Hide map' : 'Map')}
        </button>
        <button
          type="button"
          data-testid="navigator-pin-node"
          aria-pressed={Boolean(selected?.pinned)}
          disabled={!selected}
          onClick={onTogglePin}
          title={t('Keep the selected node fixed during Auto Layout')}
        >
          {t(selected?.pinned ? 'Unpin' : 'Pin')}
        </button>
        {canUndoLayout && !layoutPreview && (
          <button type="button" data-testid="navigator-undo-layout" onClick={onUndoLayout}>
            {t('Undo')}
          </button>
        )}
      </div>

      {nodeCount >= 9 && topologyLanes.length > 0 && (
        <nav className="canvas-topology-lanes" aria-label={t('Graph stage overview')}>
          <span>{t('Stages')}</span>
          {topologyLanes.map((lane) => (
            <button
              key={lane.id}
              type="button"
              data-testid={`canvas-topology-lane:${lane.id}`}
              title={lane.nodeIds.join(', ')}
              onClick={() => onSelectNode(lane.representativeNodeId)}
            >
              <strong>{lane.label}</strong>
              <span>{lane.nodeIds.length}</span>
            </button>
          ))}
        </nav>
      )}

      {(layoutPlanning || layoutPreview || layoutNotice) && (
        <div
          className={`canvas-layout-review ${
            layoutAcceptance?.decision === 'ALTERNATIVE_REQUIRED'
              ? 'review'
              : layoutQuality?.status.toLowerCase() ?? 'pending'
          }`}
          data-testid="canvas-layout-review"
          role="status"
          aria-live="polite"
        >
          <span data-testid="layout-notice">
            {layoutPlanning
              ? t('Computing layout preview...')
              : layoutAcceptance
                ? t(layoutAcceptance.decision === 'ACCEPTABLE'
                  ? 'Layout candidate preserves readability.'
                  : 'Layout candidate would reduce readability.')
              : candidatePresentation
                ? `${m(candidatePresentation.geometry.messageId, candidatePresentation.geometry.params)} · ${
                    m(candidatePresentation.perception.messageId, candidatePresentation.perception.params)
                  }`
                : layoutNotice
                  ? m(layoutNotice.messageId, layoutNotice.params)
                  : ''}
          </span>
          {layoutAcceptance && (
            <div className="canvas-layout-comparison" data-testid="layout-quality-comparison">
              <span>
                {t('Before')} <strong>{Math.round(layoutAcceptance.before.zoom * 100)}%</strong>
                {' · '}{t(layoutAcceptance.before.perception.status)}
                {' · '}{t('{size}px', {
                  size: layoutAcceptance.before.perception.effectiveTitleFontPx.toFixed(1),
                })}
              </span>
              <span aria-hidden="true">→</span>
              <span>
                {t('Candidate')} <strong>{Math.round(layoutAcceptance.candidate.zoom * 100)}%</strong>
                {' · '}{t(layoutAcceptance.candidate.perception.status)}
                {' · '}{t('{size}px', {
                  size: layoutAcceptance.candidate.perception.effectiveTitleFontPx.toFixed(1),
                })}
              </span>
            </div>
          )}
          {layoutAcceptance && candidatePresentation && (
            <small data-testid="layout-candidate-quality">
              {m(candidatePresentation.geometry.messageId, candidatePresentation.geometry.params)}
              {' · '}
              {m(candidatePresentation.perception.messageId, candidatePresentation.perception.params)}
            </small>
          )}
          {layoutAcceptance?.regressions.length ? (
            <ul className="canvas-layout-regressions" data-testid="layout-regressions">
              {layoutAcceptance.regressions.map((regression) => (
                <li key={regression.code}>{layoutRegressionMessage(regression, t)}</li>
              ))}
            </ul>
          ) : null}
          {collisionPresentation && (
            <small>
              {m(collisionPresentation.messageId, collisionPresentation.params)}
            </small>
          )}
          {(layoutPlanning || layoutPreview) && (
            <div className="canvas-layout-actions">
              {layoutPreview && (
                <button
                  type="button"
                  data-testid="layout-apply"
                  disabled={layoutAcceptance?.decision === 'ALTERNATIVE_REQUIRED'}
                  onClick={onApplyLayout}
                >
                  {t('Apply')}
                </button>
              )}
              <button type="button" data-testid="layout-cancel" onClick={onCancelLayout}>
                {t('Cancel')}
              </button>
            </div>
          )}
          {layoutPreview && layoutAcceptance?.decision === 'ALTERNATIVE_REQUIRED' && (
            <div className="canvas-layout-recommendation">
              <strong>{t('Keep current layout')}</strong>
              <span>{t('The candidate is available only as an advanced override.')}</span>
              <details data-testid="layout-override-review">
                <summary>{t('Advanced')}</summary>
                <button type="button" data-testid="layout-override" onClick={onOverrideLayout}>
                  {t('Apply anyway')}
                </button>
              </details>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

function layoutRegressionMessage(
  regression: LayoutRegression,
  t: (message: string, params?: Record<string, string | number>) => string,
): string {
  switch (regression.code) {
    case 'NODE_OVERLAP_REGRESSION':
      return t('Node overlaps increase from {before} to {candidate}.', {
        before: regression.before,
        candidate: regression.candidate,
      });
    case 'EDGE_LABEL_COLLISION_REGRESSION':
      return t('Edge label collisions increase from {before} to {candidate}.', {
        before: regression.before,
        candidate: regression.candidate,
      });
    case 'PERCEPTION_REGRESSION':
      return t('Perceptual quality changes from {before} to {candidate}.', {
        before: t(String(regression.before)),
        candidate: t(String(regression.candidate)),
      });
    case 'SMALL_GRAPH_ZOOM_FLOOR':
      return t('Small graph zoom would fall below 80%.');
    case 'TITLE_SIZE_FLOOR':
      return t('Effective node title size would fall below 12px.');
    case 'GRAPH_AREA_EXPANSION':
      return t('Graph area would expand by more than 25%.');
  }
}
