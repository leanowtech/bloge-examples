import {
  useMemo,
  useRef,
  useState,
} from 'react';
import { X } from 'lucide-react';

import {
  BlogeApiRequestError,
  saveLibraryAuthoringFixture,
} from '../api';
import useDialogFocusTrap from '../author/accessibility/useDialogFocusTrap';
import { useI18n } from '../i18n/I18nProvider';
import type {
  VisualAuthoringFixtureAssetKind,
  VisualAuthoringFixtureClassification,
  VisualAuthoringFixtureReceipt,
  VisualAuthoringFixtureSaveRequest,
  VisualAuthoringFixtureSourceKind,
} from '../types';

export interface GovernedFixtureSaveLaunch {
  draftId: string;
  authoringRevision: number;
  sourceKind: VisualAuthoringFixtureSourceKind;
  assetKind: VisualAuthoringFixtureAssetKind;
  assetRef: string;
  payload: unknown;
  suggestedFixtureId?: string;
}

interface GovernedFixtureSavePanelProps extends GovernedFixtureSaveLaunch {
  presentation?: 'dialog' | 'sheet';
  onConflict: () => void;
  onClose: () => void;
}

export default function GovernedFixtureSavePanel({
  draftId,
  authoringRevision,
  sourceKind,
  assetKind,
  assetRef,
  payload,
  suggestedFixtureId,
  presentation = 'dialog',
  onConflict,
  onClose,
}: GovernedFixtureSavePanelProps) {
  const { t , d } = useI18n();
  const dialogRef = useRef<HTMLDivElement>(null);
  const [fixtureId, setFixtureId] = useState(
    suggestedFixtureId?.trim() || defaultFixtureId(sourceKind, assetRef),
  );
  const [expectedRevision, setExpectedRevision] = useState(0);
  const [classification, setClassification] =
    useState<VisualAuthoringFixtureClassification>('INTERNAL');
  const [retentionDays, setRetentionDays] = useState(7);
  const [redactionText, setRedactionText] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [receipt, setReceipt] = useState<VisualAuthoringFixtureReceipt | null>(null);
  useDialogFocusTrap({
    open: presentation === 'dialog',
    dialogRef,
    onDismiss: () => {
      if (!busy) {
        onClose();
      }
    },
    initialFocusKey: receipt ? 'receipt' : `${sourceKind}:${assetRef}`,
  });

  const redaction = useMemo(() => parseRedactionPaths(redactionText), [redactionText]);
  const redactionPlaceholder = useMemo(
    () => suggestedRedactionPaths(payload).join('\n'),
    [payload],
  );
  const payloadPreview = useMemo(
    () => JSON.stringify(redactedFixturePreview(payload, redaction.paths), null, 2),
    [payload, redaction.paths],
  );
  const fixtureIdValid = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/.test(fixtureId.trim());
  const canSave = !busy
    && fixtureIdValid
    && redaction.error === ''
    && retentionDays >= 1
    && retentionDays <= 30
    && expectedRevision >= 0
    && confirmed;

  const save = async () => {
    if (!canSave) {
      return;
    }
    setBusy(true);
    setError('');
    const request: VisualAuthoringFixtureSaveRequest = {
      schemaVersion: 'bloge.visualAuthoringFixtureSaveRequest.v1',
      fixtureId: fixtureId.trim(),
      expectedFixtureRevision: expectedRevision,
      sourceKind,
      assetKind,
      assetRef,
      classification,
      retentionDays,
      redactionPaths: redaction.paths,
      payload,
    };
    try {
      setReceipt(await saveLibraryAuthoringFixture(
        draftId,
        authoringRevision,
        request,
      ));
    } catch (reason) {
      if (reason instanceof BlogeApiRequestError && reason.status === 412) {
        onConflict();
      }
      setError(reason instanceof Error ? reason.message : 'Fixture save failed.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      className={`governed-fixture-overlay ${presentation}`}
      role="presentation"
      data-testid={`governed-fixture-${presentation}`}
    >
      <div
        ref={dialogRef}
        className={`governed-fixture-dialog ${presentation}`}
        role={presentation === 'dialog' ? 'dialog' : 'complementary'}
        aria-modal={presentation === 'dialog' ? 'true' : undefined}
        aria-labelledby="governed-fixture-title"
        tabIndex={-1}
        data-testid="governed-fixture-panel"
      >
        <header className="governed-fixture-heading">
          <div>
            <span>{t('Governed test data')}</span>
            <h2 id="governed-fixture-title">
              {receipt ? t('Fixture saved') : t('Save as fixture')}
            </h2>
            <p>{assetRef}</p>
          </div>
          <button
            type="button"
            className="icon-button"
            aria-label={t('Close fixture panel')}
            title={t('Close')}
            onClick={onClose}
            disabled={busy}
          >
            <X size={14} aria-hidden="true" />
          </button>
        </header>

        {receipt ? (
          <FixtureReceipt receipt={receipt} />
        ) : (
          <div className="governed-fixture-form">
            <section className="governed-fixture-coordinate">
              <div>
                <span>{t('Source')}</span>
                <strong>{d(sourceLabel(sourceKind))}</strong>
              </div>
              <div>
                <span>{t('Asset')}</span>
                <strong>{d(assetKind.toLowerCase())}</strong>
              </div>
              <div>
                <span>{t('Draft revision')}</span>
                <strong>{authoringRevision}</strong>
              </div>
            </section>

            <section className="governed-fixture-fields">
              <label className="governed-fixture-id">
                <span>{t('Fixture id')}</span>
                <input
                  value={fixtureId}
                  onChange={(event) => setFixtureId(event.target.value)}
                  aria-invalid={!fixtureIdValid}
                  data-dialog-initial-focus
                  data-testid="governed-fixture-id"
                />
                {!fixtureIdValid && (
                  <small>{t('Use 1-160 letters, numbers, dots, colons, underscores, or dashes.')}</small>
                )}
              </label>
              <label>
                <span>{t('Data classification')}</span>
                <select
                  value={classification}
                  onChange={(event) => setClassification(
                    event.target.value as VisualAuthoringFixtureClassification,
                  )}
                >
                  <option value="PUBLIC">{t('Public')}</option>
                  <option value="INTERNAL">{t('Internal')}</option>
                  <option value="CONFIDENTIAL">{t('Confidential')}</option>
                  <option value="RESTRICTED">{t('Restricted')}</option>
                </select>
              </label>
              <label>
                <span>{t('Retention')}</span>
                <select
                  value={retentionDays}
                  onChange={(event) => setRetentionDays(Number(event.target.value))}
                >
                  <option value={1}>{t('1 day')}</option>
                  <option value={7}>{t('7 days')}</option>
                  <option value={14}>{t('14 days')}</option>
                  <option value={30}>{t('30 days')}</option>
                </select>
              </label>
            </section>

            <section className="governed-fixture-redaction">
              <header>
                <div>
                  <h3>{t('Redaction paths')}</h3>
                  <span>{t('One JSON Pointer per line')}</span>
                </div>
                <strong>{redaction.paths.length}/64</strong>
              </header>
              <textarea
                value={redactionText}
                onChange={(event) => setRedactionText(event.target.value)}
                placeholder={redactionPlaceholder}
                spellCheck={false}
                aria-label={t('Fixture redaction paths')}
                aria-invalid={Boolean(redaction.error)}
              />
              {redaction.error ? (
                <p className="library-inline-error">{d(redaction.error)}</p>
              ) : (
                <p>{t('Sensitive key names are also redacted automatically before encryption.')}</p>
              )}
            </section>

            <section className="governed-fixture-preview">
              <header>
                <div>
                  <h3>{t('Payload preview')}</h3>
                  <span>{t('Automatic and explicit redaction applied')}</span>
                </div>
              </header>
              <pre data-testid="governed-fixture-preview">{payloadPreview}</pre>
            </section>

            <details className="governed-fixture-advanced">
              <summary>{t('Updating an existing fixture')}</summary>
              <label>
                <span>{t('Last observed fixture revision')}</span>
                <input
                  type="number"
                  min={0}
                  value={expectedRevision}
                  onChange={(event) => setExpectedRevision(
                    Math.max(0, Number(event.target.value) || 0),
                  )}
                />
              </label>
              <p>{t('Leave this at 0 for a new fixture. Updates require the exact latest revision.')}</p>
            </details>

            <label className="governed-fixture-confirmation">
              <input
                type="checkbox"
                checked={confirmed}
                onChange={(event) => setConfirmed(event.target.checked)}
                data-testid="governed-fixture-confirm"
              />
              <span>{t('I confirm this payload is test data and the selected classification, retention, and redaction rules are appropriate.')}</span>
            </label>
          </div>
        )}

        {error && <p className="governed-fixture-error" role="alert">{d(error)}</p>}

        <footer className="governed-fixture-footer">
          <p>
            {receipt
              ? t('The encrypted payload was persisted but is intentionally absent from this receipt.')
              : t('Available only in isolated test and staging deployments.')}
          </p>
          {receipt ? (
            <button
              type="button"
              className="primary"
              onClick={onClose}
              data-dialog-initial-focus
            >
              {t('Done')}
            </button>
          ) : (
            <>
              <button type="button" className="secondary" onClick={onClose} disabled={busy}>
                {t('Cancel')}
              </button>
              <button
                type="button"
                className="primary"
                onClick={() => void save()}
                disabled={!canSave}
                data-testid="governed-fixture-save"
              >
                {busy ? t('Encrypting...') : t('Encrypt and save')}
              </button>
            </>
          )}
        </footer>
      </div>
    </div>
  );
}

function FixtureReceipt({ receipt }: { receipt: VisualAuthoringFixtureReceipt }) {
  const { locale, t , d } = useI18n();
  return (
    <div className="governed-fixture-receipt" data-testid="governed-fixture-receipt">
      <section>
        <span>{t('Fixture')}</span>
        <strong>{receipt.fixtureId}</strong>
        <small>{t('revision {revision}', { revision: receipt.revision })}</small>
      </section>
      <dl>
        <div><dt>{t('Classification')}</dt><dd>{d(receipt.classification)}</dd></div>
        <div><dt>{t('Expires')}</dt><dd>{formatTimestamp(receipt.expiresAt, locale)}</dd></div>
        <div><dt>{t('Redacted paths')}</dt><dd>{receipt.redactedPaths.length}</dd></div>
        <div><dt>{t('Payload returned')}</dt><dd>{t('No')}</dd></div>
      </dl>
      <div>
        <span>{t('Payload fingerprint')}</span>
        <code title={receipt.payloadFingerprint}>
          {shortFingerprint(receipt.payloadFingerprint)}
        </code>
      </div>
      <div>
        <span>{t('Artifact fingerprint')}</span>
        <code title={receipt.artifactFingerprint}>
          {shortFingerprint(receipt.artifactFingerprint)}
        </code>
      </div>
    </div>
  );
}

function parseRedactionPaths(source: string): { paths: string[]; error: string } {
  const paths = [...new Set(source.split(/\r?\n/)
    .map((path) => path.trim())
    .filter(Boolean))];
  if (paths.length > 64) {
    return { paths, error: 'Use at most 64 redaction paths.' };
  }
  const invalid = paths.find((path) => !/^\/(?:[^~]|~[01])*$/.test(path));
  return invalid
    ? { paths, error: `${invalid} is not a valid JSON Pointer.` }
    : { paths, error: '' };
}

function suggestedRedactionPaths(payload: unknown): string[] {
  const paths: string[] = [];
  const preferredRoots = ['inputs', 'config', 'mockedOutputs', 'args', 'expect', 'samples', 'target'];
  const visit = (value: unknown, path: string, depth: number) => {
    if (paths.length >= 2 || depth > 12) {
      return;
    }
    if (Array.isArray(value)) {
      if (value.length === 0) {
        return;
      }
      value.forEach((entry, index) => visit(entry, `${path}/${index}`, depth + 1));
      return;
    }
    if (value !== null && typeof value === 'object') {
      const entries = Object.entries(value as Record<string, unknown>);
      if (entries.length === 0) {
        return;
      }
      entries.forEach(([key, entry]) => {
        visit(entry, `${path}/${escapeJsonPointer(key)}`, depth + 1);
      });
      return;
    }
    if (path) {
      paths.push(path);
    }
  };
  if (payload !== null && typeof payload === 'object' && !Array.isArray(payload)) {
    const object = payload as Record<string, unknown>;
    preferredRoots
      .filter((key) => Object.prototype.hasOwnProperty.call(object, key))
      .forEach((key) => visit(object[key], `/${escapeJsonPointer(key)}`, 1));
  }
  if (paths.length === 0) {
    visit(payload, '', 0);
  }
  return paths;
}

function escapeJsonPointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}

function defaultFixtureId(sourceKind: VisualAuthoringFixtureSourceKind, assetRef: string): string {
  const source = sourceKind.toLowerCase().replace(/_test_case$/, '');
  const asset = assetRef.replace(/[^A-Za-z0-9._:-]+/g, '-').replace(/^-+/, '') || 'asset';
  return `${source}:${asset}:${Date.now().toString(36)}`.slice(0, 160);
}

function sourceLabel(sourceKind: VisualAuthoringFixtureSourceKind): string {
  const labels: Record<VisualAuthoringFixtureSourceKind, string> = {
    SAMPLE: 'Representative samples',
    OPERATOR_TEST_CASE: 'Operator test case',
    FUNCTION_TEST_CASE: 'Function test case',
  };
  return labels[sourceKind];
}

function shortFingerprint(fingerprint: string): string {
  return fingerprint.length > 24
    ? `${fingerprint.slice(0, 14)}...${fingerprint.slice(-8)}`
    : fingerprint;
}

function formatTimestamp(value: string, locale: string): string {
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? value : new Date(timestamp).toLocaleString(locale);
}

/** Produces a display-only fixture preview without mutating or returning raw sensitive values. */
export function redactedFixturePreview(
  payload: unknown,
  explicitPaths: string[],
): unknown {
  const pointers = new Set(explicitPaths);
  const visit = (value: unknown, pointer: string, key: string): unknown => {
    if (pointers.has(pointer) || sensitiveKey(key)) {
      return '[REDACTED]';
    }
    if (Array.isArray(value)) {
      return value.map((entry, index) => visit(entry, `${pointer}/${index}`, String(index)));
    }
    if (value !== null && typeof value === 'object') {
      return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(
        ([childKey, childValue]) => [
          childKey,
          visit(
            childValue,
            `${pointer}/${escapeJsonPointer(childKey)}`,
            childKey,
          ),
        ],
      ));
    }
    return value;
  };
  return visit(payload, '', '');
}

function sensitiveKey(value: string): boolean {
  return /(?:password|passwd|secret|token|credential|api[_-]?key|authorization)/i.test(value);
}
