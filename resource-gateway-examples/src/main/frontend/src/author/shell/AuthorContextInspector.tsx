import {
  useEffect,
  useState,
  type ReactNode,
} from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import type { AuthorMode } from './authorWorkspaceState';

type InspectorTab = 'config' | 'data' | 'test' | 'contract' | 'advanced';

interface AuthorContextInspectorProps {
  mode: AuthorMode;
  selectedNode: {
    id: string;
    label: string;
    operatorRef: string;
    visualLabel: string;
    readiness: string;
    inputCount: number;
    outputCount: number;
  } | null;
  graphName: string;
  inputFieldCount: number;
  outputFieldCount: number;
  executionStatus: string;
  assertionStatus: string;
  contractStatus: string;
  governanceStatus: string;
  resultMessage: string;
  runProvenance: string;
  dataContent: ReactNode;
  advancedContent: ReactNode;
  onEditNode: () => void;
  onOpenNodeContract: () => void;
  onOpenScenarios: () => void;
  onOpenGraphContract: () => void;
}

const TABS: Array<{ key: InspectorTab; label: string }> = [
  { key: 'config', label: 'Config' },
  { key: 'data', label: 'Data' },
  { key: 'test', label: 'Scenarios' },
  { key: 'contract', label: 'Contract' },
  { key: 'advanced', label: 'Advanced' },
];

/** Selection-scoped inspector with stable task tabs across Graph and Node contexts. */
export default function AuthorContextInspector({
  mode,
  selectedNode,
  graphName,
  inputFieldCount,
  outputFieldCount,
  executionStatus,
  assertionStatus,
  contractStatus,
  governanceStatus,
  resultMessage,
  runProvenance,
  dataContent,
  advancedContent,
  onEditNode,
  onOpenNodeContract,
  onOpenScenarios,
  onOpenGraphContract,
}: AuthorContextInspectorProps) {
  const { t } = useI18n();
  const [activeTab, setActiveTab] = useState<InspectorTab>(() => defaultTab(mode, selectedNode));
  const selectedNodeId = selectedNode?.id ?? '';

  useEffect(() => {
    setActiveTab(defaultTab(mode, selectedNode));
  }, [mode, selectedNodeId]);

  return (
    <section className="author-context-inspector-v2" data-testid="author-context-inspector">
      <header>
        <span>{t(mode[0].toUpperCase() + mode.slice(1))}</span>
        <h2>{selectedNode ? selectedNode.label : graphName}</h2>
        <p>{selectedNode ? selectedNode.operatorRef : t('Graph authoring context')}</p>
      </header>
      <div className="author-inspector-tabs" role="tablist" aria-label={t('Inspector views')}>
        {TABS.map((tab) => (
          <button
            type="button"
            role="tab"
            key={tab.key}
            className={activeTab === tab.key ? 'active' : ''}
            aria-selected={activeTab === tab.key}
            aria-controls={`author-inspector-panel-${tab.key}`}
            data-testid={`inspector-tab:${tab.key}`}
            onClick={() => setActiveTab(tab.key)}
          >
            {t(tab.label)}
          </button>
        ))}
      </div>
      <div
        id={`author-inspector-panel-${activeTab}`}
        className={`author-inspector-panel ${activeTab}`}
        role="tabpanel"
        tabIndex={0}
      >
        {activeTab === 'config' && (
          selectedNode ? (
            <>
              <dl>
                <div><dt>{t('Type')}</dt><dd>{t(selectedNode.visualLabel)}</dd></div>
                <div><dt>{t('Readiness')}</dt><dd>{t(selectedNode.readiness)}</dd></div>
                <div><dt>{t('Node')}</dt><dd>{selectedNode.id}</dd></div>
              </dl>
              <div className="author-context-actions">
                <button type="button" className="primary compact" onClick={onEditNode}>{t('Edit node')}</button>
              </div>
            </>
          ) : (
            <>
              <dl>
                <div><dt>{t('Graph')}</dt><dd>{graphName}</dd></div>
                <div><dt>{t('Input')}</dt><dd>{t('{count} fields', { count: inputFieldCount })}</dd></div>
                <div><dt>{t('Output')}</dt><dd>{t('{count} fields', { count: outputFieldCount })}</dd></div>
              </dl>
              <p className="author-inspector-note">{t('Select a node to edit its configuration.')}</p>
            </>
          )
        )}
        {activeTab === 'data' && dataContent}
        {activeTab === 'test' && (
          <>
            <div className="author-context-actions">
              <button type="button" className="primary compact" onClick={onOpenScenarios}>
                {t('Open Scenarios')}
              </button>
            </div>
            <div className="author-review-summary">
              <h3>{t('Latest result')}</h3>
              <dl>
                <div><dt>{t('Execution')}</dt><dd>{t(executionStatus)}</dd></div>
                <div><dt>{t('Assertions')}</dt><dd>{t(assertionStatus)}</dd></div>
                <div><dt>{t('Contract')}</dt><dd>{t(contractStatus)}</dd></div>
                <div><dt>{t('Governance')}</dt><dd>{t(governanceStatus)}</dd></div>
              </dl>
              <p>{resultMessage ? t(resultMessage) : t('No Scenario result yet.')}</p>
              {runProvenance && (
                <small className="author-run-provenance" data-testid="author-run-provenance">
                  {runProvenance}
                </small>
              )}
            </div>
          </>
        )}
        {activeTab === 'contract' && (
          <>
            <dl>
              {selectedNode ? (
                <>
                  <div><dt>{t('Readiness')}</dt><dd>{t(selectedNode.readiness)}</dd></div>
                  <div><dt>{t('Ports')}</dt><dd>{t('{inputs} in / {outputs} out', {
                    inputs: selectedNode.inputCount,
                    outputs: selectedNode.outputCount,
                  })}</dd></div>
                </>
              ) : (
                <>
                  <div><dt>{t('Input Contract')}</dt><dd>{t('{count} fields', { count: inputFieldCount })}</dd></div>
                  <div><dt>{t('Output Contract')}</dt><dd>{t('{count} fields', { count: outputFieldCount })}</dd></div>
                </>
              )}
            </dl>
            <div className="author-context-actions">
              <button
                type="button"
                className="primary compact"
                onClick={selectedNode ? onOpenNodeContract : onOpenGraphContract}
              >
                {t('Open Contract Workspace')}
              </button>
            </div>
          </>
        )}
        {activeTab === 'advanced' && advancedContent}
      </div>
    </section>
  );
}

function defaultTab(
  mode: AuthorMode,
  selectedNode: AuthorContextInspectorProps['selectedNode'],
): InspectorTab {
  if (mode === 'contract') {
    return 'contract';
  }
  if (mode === 'scenarios' || mode === 'evidence') {
    return 'test';
  }
  return selectedNode ? 'config' : 'data';
}
