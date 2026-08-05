import type {
  VisualLibraryAuthoringCommitResult,
  VisualLibraryAuthoringCompileResult,
} from '../types';
import { useI18n } from '../i18n/I18nProvider';
import {
  groupAuthoringDiagnostics,
  presentLibraryReadiness,
} from './readinessPresentation';

interface CanonicalContractPreviewProps {
  preview: VisualLibraryAuthoringCompileResult | null;
  previewBusy: boolean;
  commitBusy: boolean;
  commitReason: string;
  commitResult: VisualLibraryAuthoringCommitResult | null;
  onCommitReasonChange: (value: string) => void;
  onValidate: () => void;
  onCommit: () => void;
  onDiagnostic: (authoringPath: string) => void;
}

export default function CanonicalContractPreview({
  preview,
  previewBusy,
  commitBusy,
  commitReason,
  commitResult,
  onCommitReasonChange,
  onValidate,
  onCommit,
  onDiagnostic,
}: CanonicalContractPreviewProps) {
  const { t } = useI18n();
  const groupedDiagnostics = groupAuthoringDiagnostics(preview?.diagnostics ?? []);
  const errors = groupedDiagnostics.filter((diagnostic) => diagnostic.level === 'ERROR');
  const warnings = groupedDiagnostics.filter((diagnostic) => diagnostic.level === 'WARNING');
  const diagnosticOccurrences = groupedDiagnostics.reduce(
    (total, diagnostic) => total + diagnostic.occurrences,
    0,
  );
  const runtimeParity = preview?.runtimeParity ?? [];
  const readiness = presentLibraryReadiness(preview);
  const boundRuntimeCount = readiness.boundRuntimeCount;
  const runtimeLabel = preview?.readiness.productionReady
    ? t('Ready')
    : runtimeParity.length > 0
      ? t('{bound}/{total} bound', { bound: boundRuntimeCount, total: runtimeParity.length })
      : t('Not checked');

  return (
    <aside className="library-contract-preview" aria-label={t('Canonical contract preview')}>
      <header>
        <div className="library-contract-heading">
          <span>{t('Server-authoritative')}</span>
          <h2>{t('Contract Preview')}</h2>
        </div>
        <button
          type="button"
          className="secondary compact"
          onClick={onValidate}
          disabled={previewBusy}
        >
          {previewBusy ? t('Validating...') : t('Validate now')}
        </button>
      </header>

      <section className="library-readiness" data-state={readiness.tone}>
        <div className="library-readiness-summary">
          <span>{t('Readiness')}</span>
          <strong>{t(readiness.title)}</strong>
          <small>{t(readiness.summary)}</small>
          <code>{readiness.machineState}</code>
        </div>
        <dl>
          <div><dt>{t('Design import')}</dt><dd>{preview?.readiness.importable ? t('Ready') : t('Blocked')}</dd></div>
          <div><dt>{t('Strong schema')}</dt><dd>{preview?.readiness.strongSchemaReady ? t('Ready') : t('Review')}</dd></div>
          <div><dt>{t('Runtime')}</dt><dd>{runtimeLabel}</dd></div>
        </dl>
        <p className="library-readiness-action"><strong>{t('Next')}</strong>{t(readiness.nextAction)}</p>
      </section>

      {runtimeParity.length > 0 && (
        <section className="library-preview-runtime" data-testid="library-preview-runtime">
          <header>
            <h3>{t('Runtime parity')}</h3>
            <span>{t('{bound} bound / {total} total', { bound: boundRuntimeCount, total: runtimeParity.length })}</span>
          </header>
          <ol>
            {runtimeParity.map((parity, index) => (
              <li
                key={`${parity.assetKind}:${parity.assetRef}:${parity.runtimeProfile}:${index}`}
                data-state={parity.state}
              >
                <div>
                  <span>{parity.assetKind}</span>
                  <strong>{parity.assetRef}</strong>
                </div>
                <b>{t(parity.state.replace(/_/g, ' '))}</b>
                <small>{t(parity.message)}</small>
              </li>
            ))}
          </ol>
        </section>
      )}

      <section className="library-preview-diagnostics">
        <header>
          <h3>{t('Diagnostics')}</h3>
          <span>
            {t('{errors} error groups / {warnings} warning groups', { errors: errors.length, warnings: warnings.length })}
            {diagnosticOccurrences > groupedDiagnostics.length
              ? t(' · {count} occurrences', { count: diagnosticOccurrences })
              : ''}
          </span>
        </header>
        {groupedDiagnostics.length ? (
          <ol>
            {groupedDiagnostics.map((diagnostic) => (
              <li key={`${diagnostic.code}:${diagnostic.authoringPath}:${diagnostic.message}`} data-level={diagnostic.level}>
                <button type="button" onClick={() => onDiagnostic(diagnostic.authoringPath)}>
                  <span>{diagnostic.level}</span>
                  <strong>{diagnostic.code}</strong>
                  <p>{t(diagnostic.message)}</p>
                  <small>
                    {diagnostic.authoringPath}
                    {diagnostic.occurrences > 1 ? t(' · {count} occurrences', { count: diagnostic.occurrences }) : ''}
                  </small>
                </button>
              </li>
            ))}
          </ol>
        ) : (
          <p>{preview ? t('No diagnostics.') : t('A preview appears after the first autosave.')}</p>
        )}
      </section>

      {preview?.confirmationRequests.length ? (
        <section className="library-confirmations">
          <header><h3>{t('Confirm facts')}</h3><span>{preview.confirmationRequests.length}</span></header>
          {preview.confirmationRequests.map((confirmation) => (
            <button
              type="button"
              key={`${confirmation.code}:${confirmation.authoringPath}`}
              onClick={() => onDiagnostic(confirmation.authoringPath)}
            >
              <strong>{t(confirmation.question)}</strong>
              <span>{confirmation.authoringPath}</span>
            </button>
          ))}
        </section>
      ) : null}

      <details className="library-canonical-json">
        <summary>{t('Generated canonical contract')}</summary>
        <pre>{preview?.canonicalLibrary
          ? JSON.stringify(preview.canonicalLibrary, null, 2)
          : t('No canonical contract yet.')}</pre>
      </details>

      <section className="library-commit">
        <label>
          <span>{t('Commit reason')}</span>
          <input
            value={commitReason}
            onChange={(event) => onCommitReasonChange(event.target.value)}
            placeholder={t('Why this design revision is ready')}
          />
        </label>
        <button
          type="button"
          className="primary"
          disabled={!preview?.readiness.importable || !commitReason.trim() || commitBusy || Boolean(commitResult)}
          onClick={onCommit}
          data-testid="library-commit"
        >
          {commitBusy ? t('Committing...') : commitResult ? t('Imported') : t('Import Design Catalog')}
        </button>
        {commitResult && (
          <p className="library-commit-receipt" data-testid="library-commit-receipt">
            {t('Imported {libraryId} revision {revision}.', {
              libraryId: commitResult.library.libraryId,
              revision: commitResult.targetRevision,
            })}
          </p>
        )}
      </section>
    </aside>
  );
}
