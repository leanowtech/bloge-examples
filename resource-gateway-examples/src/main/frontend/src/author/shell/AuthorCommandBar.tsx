import type { AuthorMode, AuthorPrimaryAction } from './authorWorkspaceState';

interface AuthorCommandBarProps {
  graphName: string;
  draftRevision: number;
  nodeCount: number;
  edgeCount: number;
  mode: AuthorMode;
  primaryAction: AuthorPrimaryAction;
  primaryDisabled: boolean;
  executionStatus: string;
  assertionStatus: string;
  contractStatus: string;
  governanceStatus: string;
  exportUrl: string;
  exportName: string;
  exportDisabled: boolean;
  layoutDisabled: boolean;
  validationDisabled: boolean;
  onModeChange: (mode: AuthorMode) => void;
  onPrimaryAction: () => void;
  onImport: () => void;
  onAutoLayout: () => void;
  onValidate: () => void;
}

const MODES: Array<{ key: AuthorMode; label: string }> = [
  { key: 'compose', label: 'Compose' },
  { key: 'contract', label: 'Contract' },
  { key: 'test', label: 'Test' },
  { key: 'review', label: 'Review' },
];

/** Compact command surface for the task-oriented Author Workspace v2. */
export default function AuthorCommandBar({
  graphName,
  draftRevision,
  nodeCount,
  edgeCount,
  mode,
  primaryAction,
  primaryDisabled,
  executionStatus,
  assertionStatus,
  contractStatus,
  governanceStatus,
  exportUrl,
  exportName,
  exportDisabled,
  layoutDisabled,
  validationDisabled,
  onModeChange,
  onPrimaryAction,
  onImport,
  onAutoLayout,
  onValidate,
}: AuthorCommandBarProps) {
  return (
    <header className="author-command-bar" data-testid="author-command-bar">
      <div className="author-draft-identity">
        <strong title={graphName}>{graphName}</strong>
        <span>Draft r{draftRevision} · {nodeCount} nodes · {edgeCount} edges</span>
      </div>
      <nav className="author-mode-tabs" aria-label="Author task mode">
        {MODES.map((candidate) => (
          <button
            key={candidate.key}
            type="button"
            className={candidate.key === mode ? 'active' : ''}
            aria-pressed={candidate.key === mode}
            data-testid={`author-mode:${candidate.key}`}
            onClick={() => onModeChange(candidate.key)}
          >
            {candidate.label}
          </button>
        ))}
      </nav>
      <div className="author-truth-status" aria-label="Author readiness dimensions">
        <span data-state={executionStatus.toLowerCase()}>
          <small>Execution</small>
          <strong>{executionStatus}</strong>
        </span>
        <span data-state={assertionStatus.toLowerCase()}>
          <small>Assertions</small>
          <strong>{assertionStatus}</strong>
        </span>
        <span data-state={contractStatus.toLowerCase()}>
          <small>Contract</small>
          <strong>{contractStatus}</strong>
        </span>
        <span data-state={governanceStatus.toLowerCase()}>
          <small>Governance</small>
          <strong>{governanceStatus}</strong>
        </span>
      </div>
      <div className="author-secondary-actions">
        <button type="button" className="secondary compact" onClick={onImport}>
          Import
        </button>
        <button
          type="button"
          className="secondary compact"
          onClick={onAutoLayout}
          disabled={layoutDisabled}
        >
          Auto layout
        </button>
        <button
          type="button"
          className="secondary compact"
          onClick={onValidate}
          disabled={validationDisabled}
        >
          Validate graph
        </button>
        <a
          className={`toolbar-link compact ${exportDisabled ? 'disabled' : ''}`}
          data-testid="author-draft-export-v2"
          href={exportUrl}
          download={exportName}
          aria-disabled={exportDisabled}
          onClick={(event) => {
            if (exportDisabled) {
              event.preventDefault();
            }
          }}
        >
          Export
        </a>
      </div>
      <button
        type="button"
        className="primary author-primary-action"
        data-testid="author-primary-action"
        onClick={onPrimaryAction}
        disabled={primaryDisabled}
      >
        {primaryAction.label}
      </button>
    </header>
  );
}
