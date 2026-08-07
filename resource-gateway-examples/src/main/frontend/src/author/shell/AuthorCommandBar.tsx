import { useState } from 'react';
import { ChevronDown, ChevronUp, SlidersHorizontal } from 'lucide-react';

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
  const { d, m, t } = useI18n();
  const [mobileTruthOpen, setMobileTruthOpen] = useState(false);
  const [mobileToolsOpen, setMobileToolsOpen] = useState(false);
  const status = (value: string) => {
    const id = statusMessageId(value);
    return id ? m(id) : d(value);
  };
  const truthDimensions = [
    { key: 'draft', label: 'Draft', value: draftStatus },
    { key: 'contract', label: 'Contract', value: contractStatus },
    { key: 'runnable', label: 'Runnable', value: runStatus },
    { key: 'evidence', label: 'Evidence', value: evidenceStatus, title: status(proofStrength) },
    { key: 'gate', label: 'Gate', value: promotionStatus, title: promotionSummary },
  ];
  const activeModeLabel = MODES.find((candidate) => candidate.key === mode)?.label ?? mode;
  const surfaceHandoffDetail = mode === 'scenarios'
    ? 'Run commands below show their exact Case scope before execution.'
    : mode === 'contract'
      ? 'Contract actions below apply to the visible target and revision.'
      : 'Evidence actions below apply to the visible run and findings.';
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
            {d(candidate.label)}
          </button>
        ))}
      </nav>
      <div className="author-truth-status" aria-label={t('Author readiness dimensions')}>
        {truthDimensions.map((dimension) => (
          <span
            key={dimension.key}
            data-state={dimension.value.toLowerCase()}
            data-testid={dimension.key === 'gate'
              ? 'author-promotion-verdict'
              : `author-status:${dimension.key}`}
            title={dimension.title}
          >
            <small>{d(dimension.label)}</small>
            <strong>{status(dimension.value)}</strong>
          </span>
        ))}
      </div>
      <div className="author-mobile-truth">
        <button
          type="button"
          className="author-mobile-truth-toggle"
          aria-expanded={mobileTruthOpen}
          aria-controls="author-mobile-truth-detail"
          onClick={() => setMobileTruthOpen((open) => !open)}
        >
          <span>{t('Readiness')}</span>
          <strong>{status(primaryCommand.state)}</strong>
          {mobileTruthOpen
            ? <ChevronUp aria-hidden="true" size={16} />
            : <ChevronDown aria-hidden="true" size={16} />}
        </button>
        {mobileTruthOpen && (
          <div id="author-mobile-truth-detail" className="author-mobile-truth-detail">
            {truthDimensions.map((dimension) => (
              <span key={dimension.key} data-state={dimension.value.toLowerCase()}>
                <small>{d(dimension.label)}</small>
                <strong>{status(dimension.value)}</strong>
              </span>
            ))}
          </div>
        )}
      </div>
      <div className="author-secondary-command-group">
        <button
          type="button"
          className="author-mobile-tools-toggle"
          aria-expanded={mobileToolsOpen}
          aria-controls="author-mobile-tools-detail"
          onClick={() => setMobileToolsOpen((open) => !open)}
        >
          <SlidersHorizontal aria-hidden="true" size={16} />
          <span>{t('Tools')}</span>
          <strong>{t('{count} commands', { count: mode === 'compose' ? 4 : 2 })}</strong>
          {mobileToolsOpen
            ? <ChevronUp aria-hidden="true" size={16} />
            : <ChevronDown aria-hidden="true" size={16} />}
        </button>
        <div
          id="author-mobile-tools-detail"
          className={`author-secondary-actions ${mobileToolsOpen ? 'mobile-open' : ''}`}
        >
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
      </div>
      <div className="author-primary-command" data-command-state={primaryCommand.state.toLowerCase()}>
        {primaryCommand.owner === 'TASK_SURFACE' ? (
          <div
            className="author-surface-command-handoff"
            data-testid="author-surface-command-handoff"
            data-command-owner="task-surface"
            role="status"
          >
            <strong>{t('Use {surface} actions', { surface: d(activeModeLabel) })}</strong>
            <span>{d(surfaceHandoffDetail)}</span>
          </div>
        ) : (
          <button
            type="button"
            className="primary author-primary-action"
            data-testid="author-primary-action"
            aria-describedby={primaryCommand.state === 'BLOCKED' ? 'author-primary-blocker' : undefined}
            onClick={onPrimaryAction}
            disabled={!primaryCommand.enabled}
          >
            {primaryCommand.labelId ? m(primaryCommand.labelId) : d(primaryCommand.label)}
          </button>
        )}
        {primaryCommand.owner !== 'TASK_SURFACE' && primaryCommand.state === 'BLOCKED' && (
          <div className="author-command-explanation" id="author-primary-blocker" role="status">
            <span>{primaryCommand.messageId ? m(primaryCommand.messageId) : d(primaryCommand.message)}</span>
            {primaryCommand.remediation && (
              <button type="button" onClick={onPrimaryRemediation}>
                {primaryCommand.remediation.labelId
                  ? m(primaryCommand.remediation.labelId)
                  : d(primaryCommand.remediation.label)}
              </button>
            )}
          </div>
        )}
      </div>
    </header>
  );
}
