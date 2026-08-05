import { useI18n } from '../../i18n/I18nProvider';
import type { AuthorMode } from './authorWorkspaceState';

export interface TopologyContextNode {
  id: string;
  label: string;
  operatorRef: string;
}

export interface TopologyContextEdge {
  source: string;
  target: string;
}

interface TopologyContextRailProps {
  mode: Exclude<AuthorMode, 'compose'>;
  graphName: string;
  nodes: TopologyContextNode[];
  edges: TopologyContextEdge[];
  selectedNodeId: string;
  scenarioId: string;
  runId: string;
  onSelectNode: (nodeId: string) => void;
  onRevealInCompose: () => void;
}

/** Lightweight graph context that stays visible beside non-Compose authoring surfaces. */
export default function TopologyContextRail({
  mode,
  graphName,
  nodes,
  edges,
  selectedNodeId,
  scenarioId,
  runId,
  onSelectNode,
  onRevealInCompose,
}: TopologyContextRailProps) {
  const { t } = useI18n();
  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null;
  const upstream = selectedNode
    ? relatedNodes(nodes, edges, selectedNode.id, 'upstream')
    : [];
  const downstream = selectedNode
    ? relatedNodes(nodes, edges, selectedNode.id, 'downstream')
    : [];

  return (
    <section
      className="topology-context-rail"
      data-testid="topology-context-rail"
      data-author-mode={mode}
    >
      <header>
        <span>{t('Topology')}</span>
        <h2 title={graphName}>{graphName}</h2>
        <p>{t(modeLabel(mode))}</p>
      </header>

      <dl className="topology-context-facts">
        <div><dt>{t('Nodes')}</dt><dd>{nodes.length}</dd></div>
        <div><dt>{t('Edges')}</dt><dd>{edges.length}</dd></div>
        <div><dt>{t('Target')}</dt><dd>{t(selectedNode ? 'Node' : 'Graph')}</dd></div>
      </dl>

      {selectedNode ? (
        <section className="topology-context-target" aria-label={t('Selected topology target')}>
          <span>{t('Selected node')}</span>
          <strong title={selectedNode.label}>{selectedNode.label}</strong>
          <code title={selectedNode.operatorRef}>{selectedNode.operatorRef}</code>
          <div className="topology-context-neighbors">
            <NeighborList
              label="Upstream"
              nodes={upstream}
              onSelectNode={onSelectNode}
            />
            <NeighborList
              label="Downstream"
              nodes={downstream}
              onSelectNode={onSelectNode}
            />
          </div>
        </section>
      ) : (
        <section className="topology-context-target graph" aria-label={t('Selected topology target')}>
          <span>{t('Selected target')}</span>
          <strong>{t('Graph contract')}</strong>
          <code>{graphName}</code>
        </section>
      )}

      {(scenarioId || runId) && (
        <dl className="topology-context-coordinates">
          {scenarioId && <div><dt>{t('Scenario')}</dt><dd title={scenarioId}>{scenarioId}</dd></div>}
          {runId && <div><dt>{t('Run')}</dt><dd title={runId}>{runId}</dd></div>}
        </dl>
      )}

      <button type="button" className="secondary compact" onClick={onRevealInCompose}>
        {t('Reveal in Compose')}
      </button>
    </section>
  );
}

function NeighborList({
  label,
  nodes,
  onSelectNode,
}: {
  label: string;
  nodes: TopologyContextNode[];
  onSelectNode: (nodeId: string) => void;
}) {
  const { t } = useI18n();
  return (
    <section>
      <span>{t(label)} · {nodes.length}</span>
      {nodes.length > 0 ? (
        <ul>
          {nodes.map((node) => (
            <li key={node.id}>
              <button type="button" title={node.operatorRef} onClick={() => onSelectNode(node.id)}>
                {node.label}
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <small>{t('None')}</small>
      )}
    </section>
  );
}

function relatedNodes(
  nodes: TopologyContextNode[],
  edges: TopologyContextEdge[],
  selectedNodeId: string,
  direction: 'upstream' | 'downstream',
): TopologyContextNode[] {
  const relatedIds = new Set(edges.flatMap((edge) => {
    if (direction === 'upstream' && edge.target === selectedNodeId) {
      return [edge.source];
    }
    if (direction === 'downstream' && edge.source === selectedNodeId) {
      return [edge.target];
    }
    return [];
  }));
  return nodes.filter((node) => relatedIds.has(node.id));
}

function modeLabel(mode: Exclude<AuthorMode, 'compose'>): string {
  if (mode === 'contract') return 'Contract context';
  if (mode === 'scenarios') return 'Scenario context';
  return 'Evidence context';
}
