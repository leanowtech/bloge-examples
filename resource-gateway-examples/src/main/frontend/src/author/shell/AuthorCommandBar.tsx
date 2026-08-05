import type { AuthorMode, AuthorPrimaryAction } from './authorWorkspaceState';
import { useI18n } from '../../i18n/I18nProvider';

interface AuthorCommandBarProps {
  graphName: string;
  draftRevision: number;
  nodeCount: number;
  edgeCount: number;
  mode: AuthorMode;
  primaryAction: AuthorPrimaryAction;
  primaryDisabled: boolean;
  draftStatus: string;
  executionStatus: string;
  assertionStatus: string;
  contractStatus: string;
  governanceStatus: string;
  promotionStatus: string;
  promotionSummary: string;
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
  primaryAction,
  primaryDisabled,
  draftStatus,
  executionStatus,
  assertionStatus,
  contractStatus,
  governanceStatus,
  promotionStatus,
  promotionSummary,
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
  const { t } = useI18n();
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
        <span data-state={draftStatus.toLowerCase()}>
          <small>{t('Draft')}</small>
          <strong>{t(draftStatus)}</strong>
        </span>
        <span data-state={executionStatus.toLowerCase()}>
          <small>{t('Execution')}</small>
          <strong>{t(executionStatus)}</strong>
        </span>
        <span data-state={assertionStatus.toLowerCase()}>
          <small>{t('Assertions')}</small>
          <strong>{t(assertionStatus)}</strong>
        </span>
        <span data-state={contractStatus.toLowerCase()}>
          <small>{t('Contract')}</small>
          <strong>{t(contractStatus)}</strong>
        </span>
        <span data-state={governanceStatus.toLowerCase()}>
          <small>{t('Governance')}</small>
          <strong>{t(governanceStatus)}</strong>
        </span>
        <span
          data-state={promotionStatus.toLowerCase()}
          data-testid="author-promotion-verdict"
          title={t(promotionSummary)}
        >
          <small>{t('Promotion')}</small>
          <strong>{t(promotionStatus)}</strong>
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
      <button
        type="button"
        className="primary author-primary-action"
        data-testid="author-primary-action"
        onClick={onPrimaryAction}
        disabled={primaryDisabled}
      >
        {t(primaryAction.label)}
      </button>
    </header>
  );
}
