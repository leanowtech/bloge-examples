import { GitFork, RefreshCw, TriangleAlert } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import useDialogFocusTrap from '../accessibility/useDialogFocusTrap';
import {
  projectSaveConflictComparison,
  shortConflictFingerprint,
  type SaveConflictSnapshot,
} from './saveConflictModel';

interface SaveConflictResolutionDialogProps {
  open: boolean;
  subjectLabel: string;
  local: SaveConflictSnapshot;
  authoritative: SaveConflictSnapshot | null;
  authorityLoading?: boolean;
  busyAction?: 'fork' | 'reload' | '';
  error?: string;
  onFork: () => void;
  onReload: () => void;
  onRetryAuthority?: () => void;
}

export default function SaveConflictResolutionDialog({
  open,
  subjectLabel,
  local,
  authoritative,
  authorityLoading = false,
  busyAction = '',
  error = '',
  onFork,
  onReload,
  onRetryAuthority,
}: SaveConflictResolutionDialogProps) {
  const { d, t } = useI18n();
  const dialogRef = useRef<HTMLElement>(null);
  const [confirmReload, setConfirmReload] = useState(false);
  useDialogFocusTrap({
    open,
    dialogRef,
    onDismiss: () => setConfirmReload(false),
    initialFocusKey: `${subjectLabel}:${authoritative?.revision ?? 'loading'}:${confirmReload}`,
  });
  useEffect(() => {
    if (!open) setConfirmReload(false);
  }, [open]);
  const rows = useMemo(() => authoritative
    ? projectSaveConflictComparison(local, authoritative)
    : [], [authoritative, local]);
  const differenceCount = rows.filter((row) => row.changed).length
    + (authoritative && local.revision !== authoritative.revision ? 1 : 0);

  if (!open) return null;
  const actionsDisabled = authorityLoading || !authoritative || Boolean(busyAction);

  return (
    <div className="rule-editor-backdrop save-conflict-backdrop" role="presentation">
      <section
        ref={dialogRef}
        className="save-conflict-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="save-conflict-title"
        aria-describedby="save-conflict-description"
        tabIndex={-1}
        data-testid="save-conflict-dialog"
      >
        <header className="save-conflict-heading">
          <span aria-hidden="true"><TriangleAlert size={19} /></span>
          <div>
            <p>{t('Concurrent edit detected')}</p>
            <h2 id="save-conflict-title">{t('Choose how to preserve your work')}</h2>
            <small id="save-conflict-description">
              {t('{subject} changed after this workspace was opened. Nothing has been overwritten.', {
                subject: subjectLabel,
              })}
            </small>
          </div>
        </header>

        {authorityLoading && (
          <p className="save-conflict-loading" role="status">
            {t('Loading the latest authoritative revision...')}
          </p>
        )}
        {!authorityLoading && !authoritative && (
          <div className="save-conflict-authority-error">
            <p className="save-conflict-error" role="alert">
              {t('The latest revision could not be loaded. Your local work remains recoverable.')}
            </p>
            {onRetryAuthority && (
              <button type="button" className="secondary" onClick={onRetryAuthority}>
                <RefreshCw size={15} aria-hidden="true" />
                {t('Retry comparison')}
              </button>
            )}
          </div>
        )}
        {authoritative && (
          <>
            <div className="save-conflict-verdict" role="status">
              <strong>{t('{count} differences found', { count: differenceCount })}</strong>
              <span>{t('Forking keeps both revisions. Reloading discards only this local copy.')}</span>
            </div>
            <div className="save-conflict-comparison" data-testid="save-conflict-comparison">
              <div className="save-conflict-column-heading">
                <span>{t('Your local work')}</span>
                <strong>{t('Base revision {revision}', { revision: local.revision })}</strong>
                <code title={local.fingerprint}>{shortConflictFingerprint(local.fingerprint)}</code>
              </div>
              <div className="save-conflict-column-heading authoritative">
                <span>{t('Latest saved revision')}</span>
                <strong>{t('Revision {revision}', { revision: authoritative.revision })}</strong>
                <code title={authoritative.fingerprint}>
                  {shortConflictFingerprint(authoritative.fingerprint)}
                </code>
              </div>
              {rows.map((row) => (
                <div
                  key={row.id}
                  className={`save-conflict-row${row.changed ? ' changed' : ''}`}
                  data-conflict-fact={row.id}
                >
                  <span>{d(row.label)}</span>
                  <strong>{row.localValue}</strong>
                  <strong>{row.authoritativeValue}</strong>
                </div>
              ))}
            </div>
          </>
        )}

        {error && <p className="save-conflict-error" role="alert">{error}</p>}
        {confirmReload && (
          <div className="save-conflict-discard-confirmation" role="alert">
            <strong>{t('Discard this local copy?')}</strong>
            <span>{t('Local edits and uncommitted test changes will be removed from this workspace. This cannot be undone.')}</span>
          </div>
        )}
        <footer className="save-conflict-actions">
          <button
            type="button"
            className="primary"
            disabled={actionsDisabled}
            onClick={onFork}
            data-dialog-initial-focus
            data-testid="save-conflict-fork"
          >
            <GitFork size={15} aria-hidden="true" />
            {busyAction === 'fork' ? t('Forking...') : t('Fork local work')}
          </button>
          {!confirmReload ? (
            <button
              type="button"
              className="secondary"
              disabled={actionsDisabled}
              onClick={() => setConfirmReload(true)}
              data-testid="save-conflict-reload"
            >
              <RefreshCw size={15} aria-hidden="true" />
              {t('Reload latest')}
            </button>
          ) : (
            <>
              <button
                type="button"
                className="danger"
                disabled={actionsDisabled}
                onClick={onReload}
                data-testid="save-conflict-confirm-reload"
              >
                <RefreshCw size={15} aria-hidden="true" />
                {busyAction === 'reload' ? t('Reloading...') : t('Discard local and reload')}
              </button>
              <button
                type="button"
                className="secondary"
                disabled={Boolean(busyAction)}
                onClick={() => setConfirmReload(false)}
              >
                {t('Keep comparing')}
              </button>
            </>
          )}
        </footer>
      </section>
    </div>
  );
}
