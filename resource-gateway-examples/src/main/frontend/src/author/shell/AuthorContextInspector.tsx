import {
  useEffect,
  useState,
  type ReactNode,
} from 'react';

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
  dataContent: ReactNode;
  advancedContent: ReactNode;
  onEditNode: () => void;
  onOpenNodeContract: () => void;
  onOpenTest: () => void;
  onOpenGraphContract: () => void;
}

const TABS: Array<{ key: InspectorTab; label: string }> = [
  { key: 'config', label: 'Config' },
  { key: 'data', label: 'Data' },
  { key: 'test', label: 'Test' },
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
  dataContent,
  advancedContent,
  onEditNode,
  onOpenNodeContract,
  onOpenTest,
  onOpenGraphContract,
}: AuthorContextInspectorProps) {
  const [activeTab, setActiveTab] = useState<InspectorTab>(() => defaultTab(mode, selectedNode));
  const selectedNodeId = selectedNode?.id ?? '';

  useEffect(() => {
    setActiveTab(defaultTab(mode, selectedNode));
  }, [mode, selectedNodeId]);

  return (
    <section className="author-context-inspector-v2" data-testid="author-context-inspector">
      <header>
        <span>{mode}</span>
        <h2>{selectedNode ? selectedNode.label : graphName}</h2>
        <p>{selectedNode ? selectedNode.operatorRef : 'Graph authoring context'}</p>
      </header>
      <div className="author-inspector-tabs" role="tablist" aria-label="Inspector views">
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
            {tab.label}
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
                <div><dt>Type</dt><dd>{selectedNode.visualLabel}</dd></div>
                <div><dt>Readiness</dt><dd>{selectedNode.readiness}</dd></div>
                <div><dt>Node</dt><dd>{selectedNode.id}</dd></div>
              </dl>
              <div className="author-context-actions">
                <button type="button" className="primary compact" onClick={onEditNode}>Edit node</button>
              </div>
            </>
          ) : (
            <>
              <dl>
                <div><dt>Graph</dt><dd>{graphName}</dd></div>
                <div><dt>Input</dt><dd>{inputFieldCount} fields</dd></div>
                <div><dt>Output</dt><dd>{outputFieldCount} fields</dd></div>
              </dl>
              <p className="author-inspector-note">Select a node to edit its configuration.</p>
            </>
          )
        )}
        {activeTab === 'data' && dataContent}
        {activeTab === 'test' && (
          <>
            <div className="author-context-actions">
              <button type="button" className="primary compact" onClick={onOpenTest}>
                Open Test Workspace
              </button>
            </div>
            <div className="author-review-summary">
              <h3>Latest result</h3>
              <dl>
                <div><dt>Execution</dt><dd>{executionStatus}</dd></div>
                <div><dt>Assertions</dt><dd>{assertionStatus}</dd></div>
                <div><dt>Contract</dt><dd>{contractStatus}</dd></div>
                <div><dt>Governance</dt><dd>{governanceStatus}</dd></div>
              </dl>
              <p>{resultMessage || 'No Scenario result yet.'}</p>
            </div>
          </>
        )}
        {activeTab === 'contract' && (
          <>
            <dl>
              {selectedNode ? (
                <>
                  <div><dt>Readiness</dt><dd>{selectedNode.readiness}</dd></div>
                  <div><dt>Ports</dt><dd>{selectedNode.inputCount} in / {selectedNode.outputCount} out</dd></div>
                </>
              ) : (
                <>
                  <div><dt>Input Contract</dt><dd>{inputFieldCount} fields</dd></div>
                  <div><dt>Output Contract</dt><dd>{outputFieldCount} fields</dd></div>
                </>
              )}
            </dl>
            <div className="author-context-actions">
              <button
                type="button"
                className="primary compact"
                onClick={selectedNode ? onOpenNodeContract : onOpenGraphContract}
              >
                Open Contract Workspace
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
  if (mode === 'test' || mode === 'review') {
    return 'test';
  }
  return selectedNode ? 'config' : 'data';
}
