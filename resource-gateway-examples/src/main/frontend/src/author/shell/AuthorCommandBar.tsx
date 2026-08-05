import type { AuthorMode } from './authorWorkspaceState';
import type { AuthorCommandAvailability } from '../task/taskStateProjection';
import { useI18n } from '../../i18n/I18nProvider';
import { statusMessageId } from '../../i18n/messageCatalog';

interface AuthorCommandBarProps {
  graphName: string;
  draftRevision: number;
  nodeCount: number;
  edgeCount: number;
  mode: AuthorMode;
  primaryCommand: AuthorCommandAvailability;
  draftStatus: string;
  contractStatus: string;
  runStatus: string;
  evidenceStatus: string;
  proofStrength: string;
  promotionStatus: string;
  promotionSummary: string;
  exportUrl: string;
  exportName: string;
  exportDisabled: boolean;
  layoutDisabled: boolean;
  validationDisabled: boolean;
  onModeChange: (mode: AuthorMode) => void;
  onPrimaryAction: () => void;
  onPrimaryRemediation: () => void;
  onImport: () => void;
  onAutoLayout: () => void;
  onValidate: () => void;
}

const MODES: Array<{ key: AuthorMode; label: string }> = [
  { key: 'compose', label: 'Compose' },
  { key: 'contract', label: 'Contract' },
  { key: 'scenarios', label: 'Scenarios' },
  { key: 'evidence', label: 'Evidence' },
];

/** Compact command surface for the task-oriented Author Workspace v2. */
export default function AuthorCommandBar({
  graphName,
  draftRevision,
  nodeCount,
  edgeCount,
  mode,
  primaryCommand,
  draftStatus,
  contractStatus,
  runStatus,
  evidenceStatus,
  proofStrength,
  promotionStatus,
  promotionSummary,
  exportUrl,
  exportName,
  exportDisabled,
  layoutDisabled,
  validationDisabled,
  onModeChange,
  onPrimaryAction,
  onPrimaryRemediation,
  onImport,
  onAutoLayout,
  onValidate,
}: AuthorCommandBarProps) {
  const { m, t } = useI18n();
  const status = (value: string) => {
    const id = statusMessageId(value);
    return id ? m(id) : t(value);
  };
  return (
    <header className="author-command-bar" data-testid="author-command-bar">
      <div className="author-draft-identity">
        <strong title={graphName}>{graphName}</strong>
        <span>{t('Draft r{revision} · {nodes} nodes · {edges} edges', {
          revision: draftRevision,
          nodes: nodeCount,
          edges: edgeCount,
        })}</span>
      </div>
      <nav className="author-mode-tabs" aria-label={t('Author task mode')}>
        {MODES.map((candidate) => (
          <button
            key={candidate.key}
            type="button"
            className={candidate.key === mode ? 'active' : ''}
            aria-pressed={candidate.key === mode}
            data-testid={`author-mode:${candidate.key}`}
            onClick={() => onModeChange(candidate.key)}
          >
            {t(candidate.label)}
          </button>
        ))}
      </nav>
      <div className="author-truth-status" aria-label={t('Author readiness dimensions')}>
        <span data-state={draftStatus.toLowerCase()} data-testid="author-status:draft">
          <small>{t('Draft')}</small>
          <strong>{status(draftStatus)}</strong>
        </span>
        <span data-state={contractStatus.toLowerCase()} data-testid="author-status:contract">
          <small>{t('Contract')}</small>
          <strong>{status(contractStatus)}</strong>
        </span>
        <span data-state={runStatus.toLowerCase()} data-testid="author-status:runnable">
          <small>{t('Runnable')}</small>
          <strong>{status(runStatus)}</strong>
        </span>
        <span
          data-state={evidenceStatus.toLowerCase()}
          data-testid="author-status:evidence"
          title={status(proofStrength)}
        >
          <small>{t('Evidence')}</small>
          <strong>{status(evidenceStatus)}</strong>
        </span>
        <span
          data-state={promotionStatus.toLowerCase()}
          data-testid="author-promotion-verdict"
          title={t(promotionSummary)}
        >
          <small>{t('Gate')}</small>
          <strong>{status(promotionStatus)}</strong>
        </span>
      </div>
      <div className="author-secondary-actions">
        {mode === 'compose' && (
          <>
            <button type="button" className="secondary compact" onClick={onImport}>
              {t('Import')}
            </button>
            <button
              type="button"
              className="secondary compact"
              onClick={onAutoLayout}
              disabled={layoutDisabled}
            >
              {t('Auto layout')}
            </button>
          </>
        )}
        <button
          type="button"
          className="secondary compact"
          onClick={onValidate}
          disabled={validationDisabled}
        >
          {t('Validate graph')}
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
          {t('Export draft')}
        </a>
      </div>
      <div className="author-primary-command" data-command-state={primaryCommand.state.toLowerCase()}>
        <button
          type="button"
          className="primary author-primary-action"
          data-testid="author-primary-action"
          aria-describedby={primaryCommand.state === 'BLOCKED' ? 'author-primary-blocker' : undefined}
          onClick={onPrimaryAction}
          disabled={!primaryCommand.enabled}
        >
          {primaryCommand.labelId ? m(primaryCommand.labelId) : t(primaryCommand.label)}
        </button>
        {primaryCommand.state === 'BLOCKED' && (
          <div className="author-command-explanation" id="author-primary-blocker" role="status">
            <span>{primaryCommand.messageId ? m(primaryCommand.messageId) : t(primaryCommand.message)}</span>
            {primaryCommand.remediation && (
              <button type="button" onClick={onPrimaryRemediation}>
                {primaryCommand.remediation.labelId
                  ? m(primaryCommand.remediation.labelId)
                  : t(primaryCommand.remediation.label)}
              </button>
            )}
          </div>
        )}
      </div>
    </header>
  );
}
