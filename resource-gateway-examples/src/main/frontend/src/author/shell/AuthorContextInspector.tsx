import type { AuthorMode } from './authorWorkspaceState';

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
  onEditNode: () => void;
  onOpenNodeContract: () => void;
  onOpenTest: () => void;
  onOpenGraphContract: () => void;
}

/** Selection-scoped inspector used by the v2 shell instead of stacked global panels. */
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
  onEditNode,
  onOpenNodeContract,
  onOpenTest,
  onOpenGraphContract,
}: AuthorContextInspectorProps) {
  return (
    <section className="author-context-inspector-v2" data-testid="author-context-inspector">
      <header>
        <span>{mode}</span>
        <h2>{selectedNode ? selectedNode.label : graphName}</h2>
        <p>{selectedNode ? selectedNode.operatorRef : 'Graph authoring context'}</p>
      </header>
      {selectedNode ? (
        <>
          <dl>
            <div><dt>Type</dt><dd>{selectedNode.visualLabel}</dd></div>
            <div><dt>Readiness</dt><dd>{selectedNode.readiness}</dd></div>
            <div><dt>Contract</dt><dd>{selectedNode.inputCount} inputs / {selectedNode.outputCount} outputs</dd></div>
            <div><dt>Node</dt><dd>{selectedNode.id}</dd></div>
          </dl>
          <div className="author-context-actions">
            <button type="button" className="primary compact" onClick={onEditNode}>Edit node</button>
            <button type="button" className="secondary compact" onClick={onOpenNodeContract}>
              Contract
            </button>
            <button type="button" className="secondary compact" onClick={onOpenTest}>Test</button>
          </div>
        </>
      ) : (
        <>
          <dl>
            <div><dt>Input Contract</dt><dd>{inputFieldCount} fields</dd></div>
            <div><dt>Output Contract</dt><dd>{outputFieldCount} fields</dd></div>
          </dl>
          <div className="author-context-actions">
            <button type="button" className="secondary compact" onClick={onOpenGraphContract}>
              Open Contract
            </button>
            <button type="button" className="secondary compact" onClick={onOpenTest}>
              Open Test
            </button>
          </div>
        </>
      )}
      {mode === 'review' && (
        <div className="author-review-summary">
          <h3>Latest review</h3>
          <dl>
            <div><dt>Execution</dt><dd>{executionStatus}</dd></div>
            <div><dt>Assertions</dt><dd>{assertionStatus}</dd></div>
            <div><dt>Contract</dt><dd>{contractStatus}</dd></div>
            <div><dt>Governance</dt><dd>{governanceStatus}</dd></div>
          </dl>
          <p>{resultMessage || 'Run a Scenario to create review evidence.'}</p>
        </div>
      )}
    </section>
  );
}
