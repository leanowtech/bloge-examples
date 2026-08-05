import {
  type CSSProperties,
  useMemo,
  useRef,
  useState,
} from 'react';

import {
  applyLibraryAuthoringSamples,
  BlogeApiRequestError,
  inferLibraryAuthoringSamples,
} from '../api';
import useDialogFocusTrap from '../author/accessibility/useDialogFocusTrap';
import { useI18n } from '../i18n/I18nProvider';
import type {
  VisualLibraryAuthoringDraft,
  VisualOperatorAuthoring,
  VisualSampleFieldObservation,
  VisualSampleInferenceDecision,
  VisualSampleInferenceRequest,
  VisualSampleInferenceResult,
  VisualSamplePortDirection,
} from '../types';
import GovernedFixtureSavePanel, {
  type GovernedFixtureSaveLaunch,
} from './GovernedFixtureSavePanel';

export interface SampleInferenceLaunch {
  operatorKey: string;
  direction: VisualSamplePortDirection;
  portName?: string;
  sampleText?: string;
}

interface SampleInferenceReviewProps extends SampleInferenceLaunch {
  operator: VisualOperatorAuthoring;
  prepareDraft: () => Promise<VisualLibraryAuthoringDraft>;
  fixtureAvailable: boolean;
  onApplied: (draft: VisualLibraryAuthoringDraft) => void;
  onConflict: () => void;
  onClose: () => void;
}

export const DEFAULT_SAMPLE_TEXT = `[
  {
    "customerId": "cus-1001",
    "subject": "Refund delayed",
    "priority": "HIGH",
    "createdAt": "2026-07-28T10:30:00Z",
    "metadata": { "channel": "email" }
  },
  {
    "customerId": "cus-1002",
    "subject": "Cannot sign in",
    "priority": "LOW",
    "createdAt": "2026-07-29T08:00:00Z",
    "metadata": { "channel": "chat" }
  }
]`;

export default function SampleInferenceReview({
  operatorKey,
  operator,
  direction: initialDirection,
  portName: initialPortName,
  sampleText: initialSampleText,
  prepareDraft,
  fixtureAvailable,
  onApplied,
  onConflict,
  onClose,
}: SampleInferenceReviewProps) {
  const { t } = useI18n();
  const [direction, setDirection] = useState<VisualSamplePortDirection>(initialDirection);
  const [portName, setPortName] = useState(
    initialPortName?.trim() || (initialDirection === 'INPUT' ? 'request' : 'response'),
  );
  const [sampleText, setSampleText] = useState(initialSampleText || DEFAULT_SAMPLE_TEXT);
  const [suggestEnums, setSuggestEnums] = useState(true);
  const [suggestFormats, setSuggestFormats] = useState(true);
  const [request, setRequest] = useState<VisualSampleInferenceRequest | null>(null);
  const [result, setResult] = useState<VisualSampleInferenceResult | null>(null);
  const [decisions, setDecisions] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<'infer' | 'apply' | null>(null);
  const [error, setError] = useState('');
  const [fixtureLaunch, setFixtureLaunch] = useState<GovernedFixtureSaveLaunch | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  useDialogFocusTrap({
    open: fixtureLaunch === null,
    dialogRef,
    onDismiss: () => {
      if (busy === null) {
        onClose();
      }
    },
    initialFocusKey: result ? 'review' : 'samples',
  });

  const parsed = useMemo(() => {
    try {
      const samples = parseSampleText(sampleText);
      return { samples, error: '' };
    } catch (parseError) {
      return {
        samples: [] as unknown[],
        error: parseError instanceof Error ? parseError.message : 'Invalid sample JSON.',
      };
    }
  }, [sampleText]);

  const ports = direction === 'INPUT' ? operator.input ?? {} : operator.output ?? {};
  const existingPortKey = Object.keys(ports)
    .find((key) => key === portName || key.replace(/\?$/, '') === portName);
  const existingPort = existingPortKey ? ports[existingPortKey] : undefined;
  const confirmations = result?.confirmationRequests ?? [];
  const resolvedCount = confirmations.filter((item) => decisions[item.confirmationId]).length;
  const allResolved = resolvedCount === confirmations.length;
  const needsSampleReview = confirmations.some(
    (item) => decisions[item.confirmationId] === 'REVIEW_SAMPLES',
  );
  const applyDisabled = busy !== null || !result || !request || !allResolved || needsSampleReview;

  const invalidateResult = () => {
    setRequest(null);
    setResult(null);
    setDecisions({});
    setError('');
  };

  const analyze = async () => {
    if (parsed.error || !portName.trim()) {
      setError(parsed.error || 'Port name is required.');
      return;
    }
    setBusy('infer');
    setError('');
    try {
      const stored = await prepareDraft();
      const nextRequest: VisualSampleInferenceRequest = {
        schemaVersion: 'bloge.visualSampleInferenceRequest.v1',
        target: {
          assetKind: 'OPERATOR',
          assetRef: operatorKey,
          portDirection: direction,
          portName: portName.trim(),
        },
        samples: parsed.samples,
        options: {
          suggestEnums,
          suggestFormats,
          persistPayload: false,
        },
        idempotencyKey: inferenceId(),
      };
      const nextResult = await inferLibraryAuthoringSamples(
        stored.draftId,
        stored.revision,
        nextRequest,
      );
      setRequest(nextRequest);
      setResult(nextResult);
      setDecisions({});
    } catch (inferenceError) {
      handleFailure(inferenceError, onConflict, setError);
    } finally {
      setBusy(null);
    }
  };

  const useRecommendations = () => {
    if (!result) {
      return;
    }
    setDecisions(Object.fromEntries(
      result.confirmationRequests.map((item) => [item.confirmationId, item.recommendedValue]),
    ));
  };

  const apply = async () => {
    if (applyDisabled || !result || !request) {
      return;
    }
    const explicitDecisions: VisualSampleInferenceDecision[] = confirmations.map((item) => ({
      confirmationId: item.confirmationId,
      value: decisions[item.confirmationId],
    }));
    setBusy('apply');
    setError('');
    try {
      const stored = await applyLibraryAuthoringSamples(
        result.draftId,
        result.authoringRevision,
        request,
        result.evidenceFingerprint,
        explicitDecisions,
      );
      onApplied(stored);
    } catch (applyError) {
      handleFailure(applyError, onConflict, setError);
      setBusy(null);
    }
  };

  return (
    <div className="sample-inference-backdrop">
      <div
        className="sample-inference-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sample-inference-title"
        tabIndex={-1}
        ref={dialogRef}
        data-testid="sample-inference-dialog"
      >
        <header className="sample-inference-heading">
          <div>
            <span>{t('Observed to declared')}</span>
            <h2 id="sample-inference-title">{t('Infer an operator port from samples')}</h2>
            <p>{operatorKey}</p>
          </div>
          <button
            type="button"
            className="sample-inference-close"
            aria-label={t('Close sample inference')}
            title={t('Close')}
            onClick={onClose}
            disabled={busy !== null}
          >
            x
          </button>
        </header>

        {!result ? (
          <div className="sample-inference-input">
            <section className="sample-inference-target">
              <header>
                <h3>{t('Target port')}</h3>
                <span>{t('Exact draft coordinate')}</span>
              </header>
              <div className="sample-inference-target-grid">
                <fieldset>
                  <legend>{t('Direction')}</legend>
                  <div className="segmented-control">
                    {(['INPUT', 'OUTPUT'] as const).map((value) => (
                      <button
                        key={value}
                        type="button"
                        className={direction === value ? 'active' : ''}
                        aria-pressed={direction === value}
                        onClick={() => {
                          setDirection(value);
                          invalidateResult();
                        }}
                      >
                        {value === 'INPUT' ? t('Input') : t('Output')}
                      </button>
                    ))}
                  </div>
                </fieldset>
                <label>
                  <span>{t('Port name')}</span>
                  <input
                    value={portName}
                    list="sample-inference-port-names"
                    onChange={(event) => {
                      setPortName(event.target.value);
                      invalidateResult();
                    }}
                    data-testid="sample-inference-port-name"
                  />
                  <datalist id="sample-inference-port-names">
                    {Object.keys(ports).map((key) => (
                      <option key={key} value={key.replace(/\?$/, '')} />
                    ))}
                  </datalist>
                </label>
              </div>
              <div className="sample-inference-options">
                <label>
                  <input
                    type="checkbox"
                    checked={suggestEnums}
                    onChange={(event) => setSuggestEnums(event.target.checked)}
                  />
                  <span>{t('Suggest enums')}</span>
                </label>
                <label>
                  <input
                    type="checkbox"
                    checked={suggestFormats}
                    onChange={(event) => setSuggestFormats(event.target.checked)}
                  />
                  <span>{t('Detect date formats')}</span>
                </label>
              </div>
            </section>

            <section className="sample-inference-source">
              <header>
                <div>
                  <h3>{t('Representative JSON')}</h3>
                  <span>{t('JSON array, one object, or NDJSON')}</span>
                </div>
                <strong data-state={parsed.error ? 'invalid' : 'valid'}>
                  {parsed.error ? t('Invalid JSON') : t('{count} samples ready', { count: parsed.samples.length })}
                </strong>
              </header>
              <textarea
                value={sampleText}
                onChange={(event) => {
                  setSampleText(event.target.value);
                  invalidateResult();
                }}
                spellCheck={false}
                aria-label={t('Representative JSON samples')}
                data-dialog-initial-focus
                data-testid="sample-inference-samples"
              />
              {parsed.error && <p className="library-inline-error">{t(parsed.error)}</p>}
              <p className="sample-inference-privacy">
                {t('Raw JSON stays in this review session and is sent only for infer and apply. Persisted drafts retain fingerprints, counts, decisions, and declared schema.')}
              </p>
            </section>
          </div>
        ) : (
          <div className="sample-inference-review">
            <section className="sample-inference-summary" aria-label={t('Inference summary')}>
              <div><span>{t('Samples')}</span><strong>{result.sampleCount}</strong></div>
              <div><span>{t('Observed facts')}</span><strong>{result.observations.length}</strong></div>
              <div><span>{t('Decisions')}</span><strong>{resolvedCount}/{confirmations.length}</strong></div>
              <div>
                <span>{t('Evidence')}</span>
                <strong title={result.evidenceFingerprint}>
                  {shortFingerprint(result.evidenceFingerprint)}
                </strong>
              </div>
            </section>

            <div className="sample-inference-review-columns">
              <section className="sample-inference-evidence">
                <header>
                  <div>
                    <h3>{t('Candidate structure')}</h3>
                    <span>{t(directionLabel(result.target.portDirection))} / {result.target.portName}</span>
                  </div>
                  <button type="button" className="secondary compact" onClick={invalidateResult}>
                    {t('Edit samples')}
                  </button>
                </header>
                <div className="sample-inference-before-after">
                  <div>
                    <span>{t('Current')}</span>
                    <strong>{existingPort === undefined ? t('New port') : t(schemaNodeLabel(existingPort))}</strong>
                  </div>
                  <div aria-hidden="true">{t('->')}</div>
                  <div>
                    <span>{t('Inferred')}</span>
                    <strong>{t(schemaNodeLabel(result.candidate))}</strong>
                  </div>
                </div>
                <CandidateTree name={result.target.portName} node={result.candidate} />

                <header className="sample-inference-facts-heading">
                  <div>
                    <h3>{t('Observed facts')}</h3>
                    <span>{t('Values are not retained')}</span>
                  </div>
                </header>
                <div className="sample-inference-facts">
                  {result.observations.map((observation) => (
                    <ObservationRow key={observation.factId} observation={observation} />
                  ))}
                </div>
                {result.diagnostics.length > 0 && (
                  <div className="sample-inference-diagnostics">
                    {result.diagnostics.map((diagnostic) => (
                      <p key={`${diagnostic.code}:${diagnostic.authoringPath}`}>
                        <strong>{diagnostic.code}</strong>
                        <span>{t(diagnostic.message)}</span>
                      </p>
                    ))}
                  </div>
                )}
              </section>

              <section className="sample-inference-confirmation-queue">
                <header>
                  <div>
                    <h3>{t('Confirmation queue')}</h3>
                    <span>{t('Observed facts never become declared implicitly')}</span>
                  </div>
                  {confirmations.length > 0 && (
                    <button
                      type="button"
                      className="secondary compact"
                      onClick={useRecommendations}
                      data-testid="sample-inference-use-recommendations"
                    >
                      {t('Use {count} recommendations', { count: confirmations.length })}
                    </button>
                  )}
                </header>
                {confirmations.length === 0 ? (
                  <div className="sample-inference-no-confirmations">
                    <strong>{t('No ambiguous facts')}</strong>
                    <span>{t('The conservative candidate can be applied as shown.')}</span>
                  </div>
                ) : (
                  <ol>
                    {confirmations.map((confirmation, index) => (
                      <li
                        key={confirmation.confirmationId}
                        data-blocking={confirmation.blocking}
                        data-resolved={Boolean(decisions[confirmation.confirmationId])}
                      >
                        <div>
                          <span>{index + 1}</span>
                          <div>
                            <strong>{t(confirmationLabel(confirmation.code))}</strong>
                            <small>{relativeFactPath(confirmation.authoringPath, result.target)}</small>
                          </div>
                          {confirmation.blocking && <em>{t('Blocking')}</em>}
                        </div>
                        <p>{t(confirmation.question)}</p>
                        <fieldset>
                          <legend>{t('Decision')}</legend>
                          <div className="sample-inference-decision-options">
                            {confirmation.allowedValues.map((value) => (
                              <label
                                key={value}
                                className={decisions[confirmation.confirmationId] === value
                                  ? 'selected' : ''}
                              >
                                <input
                                  type="radio"
                                  name={confirmation.confirmationId}
                                  value={value}
                                  checked={decisions[confirmation.confirmationId] === value}
                                  onChange={() => setDecisions((current) => ({
                                    ...current,
                                    [confirmation.confirmationId]: value,
                                  }))}
                                />
                                <span>{t(decisionLabel(value))}</span>
                                {value === confirmation.recommendedValue && <small>{t('Recommended')}</small>}
                              </label>
                            ))}
                          </div>
                        </fieldset>
                      </li>
                    ))}
                  </ol>
                )}
              </section>
            </div>
          </div>
        )}

        {error && (
          <p className="sample-inference-error" role="alert" data-testid="sample-inference-error">
            {t(error)}
          </p>
        )}

        <footer className="sample-inference-footer">
          <div>
            {result && !allResolved && (
              <span>{t('{count} decisions remaining', { count: confirmations.length - resolvedCount })}</span>
            )}
            {result && needsSampleReview && (
              <span>{t('Review samples or choose Keep unknown before apply.')}</span>
            )}
          </div>
          <button type="button" className="secondary" onClick={onClose} disabled={busy !== null}>
            {t('Cancel')}
          </button>
          {result && request && (
            <button
              type="button"
              className="secondary"
              disabled={busy !== null || !fixtureAvailable}
              title={fixtureAvailable
                ? t('Persist these samples as a classified, expiring fixture')
                : t('Fixture persistence is unavailable in this deployment')}
              onClick={() => setFixtureLaunch({
                draftId: result.draftId,
                authoringRevision: result.authoringRevision,
                sourceKind: 'SAMPLE',
                assetKind: 'OPERATOR',
                assetRef: operatorKey,
                payload: {
                  target: request.target,
                  samples: request.samples,
                  options: request.options,
                },
                suggestedFixtureId: sampleFixtureId(operatorKey, request.target.portName),
              })}
              data-testid="sample-inference-save-fixture"
            >
              {t('Save samples as fixture')}
            </button>
          )}
          {!result ? (
            <button
              type="button"
              className="primary"
              onClick={() => void analyze()}
              disabled={busy !== null || Boolean(parsed.error) || !portName.trim()}
              data-testid="sample-inference-analyze"
            >
              {busy === 'infer' ? t('Analyzing...') : t('Analyze samples')}
            </button>
          ) : (
            <button
              type="button"
              className="primary"
              onClick={() => void apply()}
              disabled={applyDisabled}
              data-testid="sample-inference-apply"
            >
              {busy === 'apply' ? t('Applying...') : t('Apply declared schema')}
            </button>
          )}
        </footer>
        {fixtureLaunch && (
          <GovernedFixtureSavePanel
            {...fixtureLaunch}
            presentation="sheet"
            onConflict={onConflict}
            onClose={() => setFixtureLaunch(null)}
          />
        )}
      </div>
    </div>
  );
}

export function parseSampleText(source: string): unknown[] {
  const normalized = source.trim();
  if (!normalized) {
    throw new Error('Add at least one JSON sample.');
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(normalized);
  } catch (jsonError) {
    const lines = normalized.split(/\r?\n/).filter((line) => line.trim());
    try {
      parsed = lines.map((line) => JSON.parse(line));
    } catch {
      throw new Error(jsonError instanceof Error ? jsonError.message : 'Invalid JSON samples.');
    }
  }
  const samples = Array.isArray(parsed) ? parsed : [parsed];
  if (samples.length === 0) {
    throw new Error('Add at least one JSON sample.');
  }
  if (samples.length > 100) {
    throw new Error('Use at most 100 samples per inference.');
  }
  return samples;
}

function CandidateTree({ name, node }: { name: string; node: unknown }) {
  return (
    <div className="sample-candidate-tree" role="tree">
      <CandidateNode name={name} node={node} depth={0} />
    </div>
  );
}

function CandidateNode({ name, node, depth }: { name: string; node: unknown; depth: number }) {
  const { t } = useI18n();
  const fields = schemaFields(node);
  return (
    <>
      <div
        className="sample-candidate-node"
        role="treeitem"
        style={{ '--tree-indent': `${depth * 16}px` } as CSSProperties}
      >
        <span aria-hidden="true">{fields ? (depth === 0 ? 'v' : '+') : '-'}</span>
        <strong>{name.replace(/\?$/, '')}</strong>
        {name.endsWith('?') && <em>{t('optional')}</em>}
        <small>{t(schemaNodeLabel(node))}</small>
      </div>
      {fields && Object.entries(fields).map(([fieldName, field]) => (
        <CandidateNode key={`${depth}:${fieldName}`} name={fieldName} node={field} depth={depth + 1} />
      ))}
    </>
  );
}

function ObservationRow({ observation }: { observation: VisualSampleFieldObservation }) {
  const { t } = useI18n();
  return (
    <div className="sample-inference-fact">
      <div>
        <strong>{lastPathSegment(observation.authoringPath)}</strong>
        <span>{observation.suggestedType}</span>
      </div>
      <dl>
        <div><dt>{t('Present')}</dt><dd>{observation.presenceCount}/{observation.sampleCount}</dd></div>
        <div><dt>{t('Null')}</dt><dd>{observation.nullCount}</dd></div>
        <div><dt>{t('Distinct')}</dt><dd>{observation.distinctCount}</dd></div>
      </dl>
      <div className="sample-inference-fact-badges">
        {observation.requiredCandidate && <span>{t('required')}</span>}
        {observation.nullableCandidate && <span>{t('nullable')}</span>}
        {observation.formatCandidate && <span>{observation.formatCandidate}</span>}
        {observation.sensitive && <span data-alert="true">{t('sensitive')}</span>}
        {observation.conflictTypes.length > 0 && <span data-alert="true">{t('type conflict')}</span>}
      </div>
      {observation.widenReasons.length > 0 && (
        <p>{observation.widenReasons.map((reason) => t(reason)).join('; ')}</p>
      )}
    </div>
  );
}

function schemaFields(node: unknown): Record<string, unknown> | null {
  if (!node || typeof node !== 'object' || Array.isArray(node)) {
    return null;
  }
  const fields = (node as { fields?: unknown }).fields;
  return fields && typeof fields === 'object' && !Array.isArray(fields)
    ? fields as Record<string, unknown>
    : null;
}

function schemaNodeLabel(node: unknown): string {
  if (typeof node === 'string') {
    return node;
  }
  if (node && typeof node === 'object' && !Array.isArray(node)) {
    const value = node as { fields?: unknown; enum?: unknown[]; additionalProperties?: boolean };
    if (value.fields && typeof value.fields === 'object') {
      return value.additionalProperties === false ? 'object / closed' : 'object / open';
    }
    if (Array.isArray(value.enum)) {
      return `enum / ${value.enum.length} values`;
    }
  }
  return node === undefined ? 'not declared' : 'json';
}

function confirmationLabel(code: string): string {
  const labels: Record<string, string> = {
    RG_AUTHORING_INFERENCE_PRESENCE_CONFIRMATION_REQUIRED: 'Field presence',
    RG_AUTHORING_INFERENCE_NULLABILITY_CONFIRMATION_REQUIRED: 'Null handling',
    RG_AUTHORING_INFERENCE_FORMAT_CONFIRMATION_REQUIRED: 'String format',
    RG_AUTHORING_INFERENCE_ENUM_CONFIRMATION_REQUIRED: 'Business enum',
    RG_AUTHORING_INFERENCE_OBJECT_CLOSURE_CONFIRMATION_REQUIRED: 'Object openness',
    RG_AUTHORING_INFERENCE_TYPE_CONFLICT_CONFIRMATION_REQUIRED: 'Type conflict',
    RG_AUTHORING_INFERENCE_SENSITIVE_HANDLING_REQUIRED: 'Sensitive field',
  };
  return labels[code.replace(/\./g, '_')] ?? code.replace('RG.AUTHORING.INFERENCE_', '')
    .replace(/_CONFIRMATION_REQUIRED|_HANDLING_REQUIRED/g, '')
    .toLowerCase()
    .replace(/(^|_)([a-z])/g, (_, prefix, letter: string) => `${prefix ? ' ' : ''}${letter.toUpperCase()}`);
}

function decisionLabel(value: string): string {
  const labels: Record<string, string> = {
    REQUIRED: 'Required',
    OPTIONAL: 'Optional',
    NULLABLE: 'Allow null',
    NON_NULL: 'Reject null',
    KEEP_JSON_NULLABLE: 'Keep nullable JSON',
    STRING: 'Keep string',
    DATE: 'Use date',
    DATETIME: 'Use datetime',
    KEEP_STRING: 'Keep string',
    DECLARE_ENUM: 'Declare enum',
    OPEN: 'Allow extra fields',
    CLOSED: 'Reject extra fields',
    REVIEW_SAMPLES: 'Review samples',
    KEEP_UNKNOWN: 'Keep unknown',
    DECLARE_TYPE_ONLY: 'Declare type only',
    REMOVE_FIELD: 'Remove field',
  };
  return labels[value] ?? value.toLowerCase().replace(/_/g, ' ');
}

function relativeFactPath(
  path: string,
  target: VisualSampleInferenceResult['target'],
): string {
  const targetPath = `/operators/${pointer(target.assetRef)}/${
    target.portDirection === 'INPUT' ? 'input' : 'output'
  }/${pointer(target.portName)}`;
  const relative = path.startsWith(targetPath) ? path.slice(targetPath.length) : path;
  return relative ? relative.split('/').filter(Boolean).map(decodePointer).join(' / ') : target.portName;
}

function directionLabel(direction: VisualSamplePortDirection): string {
  return direction === 'INPUT' ? 'Input' : 'Output';
}

function lastPathSegment(path: string): string {
  const segments = path.split('/').filter(Boolean);
  const segment = segments[segments.length - 1] ?? '/';
  return decodePointer(segment).replace(/\?$/, '');
}

function pointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}

function decodePointer(value: string): string {
  return value.replace(/~1/g, '/').replace(/~0/g, '~');
}

function inferenceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `visual-${crypto.randomUUID()}`;
  }
  return `visual-${Date.now().toString(36)}`;
}

function sampleFixtureId(operatorKey: string, portName: string): string {
  return `sample:${operatorKey}:${portName}`
    .replace(/[^A-Za-z0-9._:-]+/g, '-')
    .replace(/^-+/, '')
    .slice(0, 160);
}

function shortFingerprint(fingerprint: string): string {
  return fingerprint.length > 18
    ? `${fingerprint.slice(0, 10)}...${fingerprint.slice(-6)}`
    : fingerprint;
}

function handleFailure(
  error: unknown,
  onConflict: () => void,
  setError: (message: string) => void,
) {
  if (error instanceof BlogeApiRequestError && error.status === 412) {
    onConflict();
  }
  setError(error instanceof Error ? error.message : 'Sample inference failed.');
}
