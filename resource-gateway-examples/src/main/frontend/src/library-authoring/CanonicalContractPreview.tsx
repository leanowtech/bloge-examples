import type {
  VisualLibraryAuthoringCommitResult,
  VisualLibraryAuthoringCompileResult,
} from '../types';
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
    ? 'Ready'
    : runtimeParity.length > 0
      ? `${boundRuntimeCount}/${runtimeParity.length} bound`
      : 'Not checked';

  return (
    <aside className="library-contract-preview" aria-label="Canonical contract preview">
      <header>
        <div className="library-contract-heading">
          <span>Server-authoritative</span>
          <h2>Contract Preview</h2>
        </div>
        <button
          type="button"
          className="secondary compact"
          onClick={onValidate}
          disabled={previewBusy}
        >
          {previewBusy ? 'Validating...' : 'Validate now'}
        </button>
      </header>

      <section className="library-readiness" data-state={readiness.tone}>
        <div className="library-readiness-summary">
          <span>Readiness</span>
          <strong>{readiness.title}</strong>
          <small>{readiness.summary}</small>
          <code>{readiness.machineState}</code>
        </div>
        <dl>
          <div><dt>Design import</dt><dd>{preview?.readiness.importable ? 'Ready' : 'Blocked'}</dd></div>
          <div><dt>Strong schema</dt><dd>{preview?.readiness.strongSchemaReady ? 'Ready' : 'Review'}</dd></div>
          <div><dt>Runtime</dt><dd>{runtimeLabel}</dd></div>
        </dl>
        <p className="library-readiness-action"><strong>Next</strong>{readiness.nextAction}</p>
      </section>

      {runtimeParity.length > 0 && (
        <section className="library-preview-runtime" data-testid="library-preview-runtime">
          <header>
            <h3>Runtime parity</h3>
            <span>{boundRuntimeCount} bound / {runtimeParity.length} total</span>
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
                <b>{parity.state.replace(/_/g, ' ')}</b>
                <small>{parity.message}</small>
              </li>
            ))}
          </ol>
        </section>
      )}

      <section className="library-preview-diagnostics">
        <header>
          <h3>Diagnostics</h3>
          <span>
            {errors.length} error groups / {warnings.length} warning groups
            {diagnosticOccurrences > groupedDiagnostics.length
              ? ` · ${diagnosticOccurrences} occurrences`
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
                  <p>{diagnostic.message}</p>
                  <small>
                    {diagnostic.authoringPath}
                    {diagnostic.occurrences > 1 ? ` · ${diagnostic.occurrences} occurrences` : ''}
                  </small>
                </button>
              </li>
            ))}
          </ol>
        ) : (
          <p>{preview ? 'No diagnostics.' : 'A preview appears after the first autosave.'}</p>
        )}
      </section>

      {preview?.confirmationRequests.length ? (
        <section className="library-confirmations">
          <header><h3>Confirm facts</h3><span>{preview.confirmationRequests.length}</span></header>
          {preview.confirmationRequests.map((confirmation) => (
            <button
              type="button"
              key={`${confirmation.code}:${confirmation.authoringPath}`}
              onClick={() => onDiagnostic(confirmation.authoringPath)}
            >
              <strong>{confirmation.question}</strong>
              <span>{confirmation.authoringPath}</span>
            </button>
          ))}
        </section>
      ) : null}

      <details className="library-canonical-json">
        <summary>Generated canonical contract</summary>
        <pre>{preview?.canonicalLibrary
          ? JSON.stringify(preview.canonicalLibrary, null, 2)
          : 'No canonical contract yet.'}</pre>
      </details>

      <section className="library-commit">
        <label>
          <span>Commit reason</span>
          <input
            value={commitReason}
            onChange={(event) => onCommitReasonChange(event.target.value)}
            placeholder="Why this design revision is ready"
          />
        </label>
        <button
          type="button"
          className="primary"
          disabled={!preview?.readiness.importable || !commitReason.trim() || commitBusy || Boolean(commitResult)}
          onClick={onCommit}
          data-testid="library-commit"
        >
          {commitBusy ? 'Committing...' : commitResult ? 'Imported' : 'Import Design Catalog'}
        </button>
        {commitResult && (
          <p className="library-commit-receipt" data-testid="library-commit-receipt">
            Imported {commitResult.library.libraryId} revision {commitResult.targetRevision}.
          </p>
        )}
      </section>
    </aside>
  );
}
