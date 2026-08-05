import { useMemo, useRef, useState } from 'react';
import { X } from 'lucide-react';

import { useI18n } from '../../i18n/I18nProvider';
import type { ScenarioDraftSet } from '../domain';
import {
  createScenarioMaterializationPlan,
  deriveScenarioImportTargets,
  diffScenarioImport,
  materializeScenarioImport,
  parseScenarioImport,
  ScenarioImportError,
  suggestScenarioImportBindings,
  type ScenarioColumnBinding,
  type ScenarioImportClassification,
  type ScenarioImportExecutionRequest,
  type ScenarioImportConverter,
  type ScenarioImportDiff,
  type ScenarioImportKind,
  type ScenarioImportPreview,
  type ScenarioImportTarget,
  type ScenarioImportValueSemantics,
  type ScenarioMaterializationReceipt,
  type ScenarioMaterializationResult,
} from './scenarioImportModel';

interface ScenarioImportWorkbenchProps {
  open: boolean;
  draftSet: ScenarioDraftSet;
  onMaterialize: (result: ScenarioMaterializationResult) => void;
  executeMaterialization?: (request: ScenarioImportExecutionRequest) => Promise<ScenarioMaterializationResult>;
  onClose: () => void;
}

type ImportStep = 'SOURCE' | 'PREVIEW' | 'MAP' | 'REVIEW' | 'RECEIPT';
const STEPS: ImportStep[] = ['SOURCE', 'PREVIEW', 'MAP', 'REVIEW', 'RECEIPT'];
const SEMANTICS: ScenarioImportValueSemantics[] = ['VALUE', 'EMPTY', 'NULL', 'MISSING', 'DEFAULT'];
const CONVERTERS: ScenarioImportConverter[] = ['IDENTITY', 'STRING', 'NUMBER', 'BOOLEAN', 'JSON'];

export default function ScenarioImportWorkbench({
  open,
  draftSet,
  onMaterialize,
  executeMaterialization,
  onClose,
}: ScenarioImportWorkbenchProps) {
  const { t } = useI18n();
  const [step, setStep] = useState<ImportStep>('SOURCE');
  const [kind, setKind] = useState<ScenarioImportKind>('CSV');
  const [sourceText, setSourceText] = useState('');
  const [preview, setPreview] = useState<ScenarioImportPreview | null>(null);
  const [bindings, setBindings] = useState<ScenarioColumnBinding[]>([]);
  const [identitySourcePath, setIdentitySourcePath] = useState('');
  const [classification, setClassification] = useState<ScenarioImportClassification>(
    draftSet.metadata.classification,
  );
  const [diff, setDiff] = useState<ScenarioImportDiff | null>(null);
  const [receipt, setReceipt] = useState<ScenarioMaterializationReceipt | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const targets = useMemo(() => deriveScenarioImportTargets(draftSet), [draftSet]);
  const mappedSourcePaths = new Set(bindings.map((binding) => binding.sourcePath));
  const lowConfidenceCount = bindings.filter((binding) => (
    binding.confidence < 0.95 && !binding.confirmed
  )).length;

  if (!open) return null;

  const parseSource = async () => {
    setBusy(true);
    setError('');
    try {
      const nextPreview = await parseScenarioImport(sourceText, kind);
      const suggested = suggestScenarioImportBindings(nextPreview, targets);
      setPreview(nextPreview);
      setBindings(suggested);
      setIdentitySourcePath(nextPreview.columns.some((column) => column.sourcePath === '/id') ? '/id' : '');
      const previous = materializationReceiptFrom(draftSet);
      setDiff(previous ? await diffScenarioImport(nextPreview, previous) : null);
      setStep('PREVIEW');
    } catch (cause) {
      setError(importErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  const materialize = async () => {
    if (!preview) return;
    setBusy(true);
    setError('');
    try {
      const plan = await createScenarioMaterializationPlan({
        preview,
        draftSet,
        bindings,
        identitySourcePath: identitySourcePath || undefined,
        classification,
        conflictPolicy: diff ? 'REPLACE_EXACT_ID' : 'FAIL',
      });
      const result = executeMaterialization
        ? await executeMaterialization({
          schemaVersion: 'bloge.scenarioImportMaterializationRequest.v1',
          sourceText,
          plan,
          draftSet,
          templateScenarioId: draftSet.scenarios[0]?.scenarioId ?? '',
        })
        : await materializeScenarioImport({
          preview,
          plan,
          draftSet,
          actor: draftSet.metadata.owner || 'author-canvas',
          materializedAt: new Date().toISOString(),
        });
      setReceipt(result.receipt);
      onMaterialize(result);
      setStep('RECEIPT');
    } catch (cause) {
      setError(importErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  const reset = () => {
    setStep('SOURCE');
    setSourceText('');
    setPreview(null);
    setBindings([]);
    setIdentitySourcePath('');
    setDiff(null);
    setReceipt(null);
    setError('');
  };

  return (
    <section className="scenario-import-workbench" aria-label="Import Scenario cases" data-testid="scenario-import-workbench">
      <header className="scenario-import-head">
        <div>
          <span className="eyebrow">DATA TO SCENARIOS</span>
          <h3>Import cases</h3>
          <p>{stepDescription(step)}</p>
        </div>
        <button type="button" className="icon-button" aria-label={t('Close import cases')} title={t('Close')} onClick={onClose}>
          <X size={14} aria-hidden="true" />
        </button>
      </header>

      <ol className="scenario-import-steps" aria-label="Import progress">
        {STEPS.map((candidate, index) => {
          const activeIndex = STEPS.indexOf(step);
          return (
            <li
              key={candidate}
              data-state={index === activeIndex ? 'active' : index < activeIndex ? 'complete' : 'pending'}
              aria-current={candidate === step ? 'step' : undefined}
            >
              <span>{index + 1}</span>
              <strong>{stepLabel(candidate)}</strong>
            </li>
          );
        })}
      </ol>

      {error && <div className="scenario-import-error" role="alert">{error}</div>}

      <div className="scenario-import-body">
        {step === 'SOURCE' && (
          <SourceStep
            kind={kind}
            sourceText={sourceText}
            busy={busy}
            fileInputRef={fileInputRef}
            onKindChange={setKind}
            onSourceTextChange={setSourceText}
            onFile={async (file) => {
              if (!file) return;
              setKind(file.name.toLocaleLowerCase().endsWith('.json') ? 'JSON' : 'CSV');
              setSourceText(await file.text());
            }}
            onSample={() => {
              setKind('JSON');
              setSourceText(sampleJson(draftSet));
            }}
            onParse={() => void parseSource()}
          />
        )}

        {step === 'PREVIEW' && preview && (
          <PreviewStep preview={preview} onBack={() => setStep('SOURCE')} onNext={() => setStep('MAP')} />
        )}

        {step === 'MAP' && preview && (
          <MappingStep
            preview={preview}
            targets={targets}
            bindings={bindings}
            identitySourcePath={identitySourcePath}
            classification={classification}
            onBindingsChange={setBindings}
            onIdentityChange={setIdentitySourcePath}
            onClassificationChange={setClassification}
            onBack={() => setStep('PREVIEW')}
            onNext={() => {
              if (bindings.length === 0) {
                setError('Map at least one source column before review.');
                return;
              }
              if (lowConfidenceCount > 0) {
                setError('Confirm every low-confidence mapping before review.');
                return;
              }
              setError('');
              setStep('REVIEW');
            }}
          />
        )}

        {step === 'REVIEW' && preview && (
          <ReviewStep
            preview={preview}
            bindings={bindings}
            identitySourcePath={identitySourcePath}
            classification={classification}
            diff={diff}
            busy={busy}
            onBack={() => setStep('MAP')}
            onMaterialize={() => void materialize()}
          />
        )}

        {step === 'RECEIPT' && receipt && (
          <ReceiptStep
            receipt={receipt}
            onDone={onClose}
            onImportAnother={reset}
          />
        )}
      </div>

      {step !== 'SOURCE' && step !== 'RECEIPT' && preview && (
        <footer className="scenario-import-footnote">
          <span>{preview.rowCount} source rows</span>
          <span>{bindings.length}/{preview.columnCount} columns mapped</span>
          <span>{mappedSourcePaths.size === preview.columnCount ? 'All columns handled' : `${preview.columnCount - mappedSourcePaths.size} ignored`}</span>
          <code>{shortFingerprint(preview.source.fingerprint)}</code>
        </footer>
      )}
    </section>
  );
}

function SourceStep({
  kind,
  sourceText,
  busy,
  fileInputRef,
  onKindChange,
  onSourceTextChange,
  onFile,
  onSample,
  onParse,
}: {
  kind: ScenarioImportKind;
  sourceText: string;
  busy: boolean;
  fileInputRef: React.RefObject<HTMLInputElement>;
  onKindChange: (kind: ScenarioImportKind) => void;
  onSourceTextChange: (value: string) => void;
  onFile: (file: File | undefined) => void;
  onSample: () => void;
  onParse: () => void;
}) {
  return (
    <div className="scenario-import-source">
      <div className="scenario-import-source-actions">
        <div className="scenario-view-switch" role="group" aria-label="Import format">
          {(['CSV', 'JSON'] as const).map((value) => (
            <button type="button" key={value} aria-pressed={kind === value} onClick={() => onKindChange(value)}>{value}</button>
          ))}
        </div>
        <button type="button" className="secondary" onClick={() => fileInputRef.current?.click()}>Choose file</button>
        <input
          ref={fileInputRef}
          type="file"
          accept=".csv,.json,text/csv,application/json"
          className="visually-hidden"
          onChange={(event) => onFile(event.target.files?.[0])}
        />
        <button type="button" className="secondary" onClick={onSample}>Load sample</button>
      </div>
      <label>
        <span>Source snapshot</span>
        <textarea
          aria-label="Scenario import source"
          spellCheck={false}
          value={sourceText}
          placeholder={kind === 'CSV' ? 'caseId,name,amount\nprime,Prime approval,5000' : '[{"caseId":"prime","name":"Prime approval"}]'}
          onChange={(event) => onSourceTextChange(event.target.value)}
        />
      </label>
      <div className="scenario-import-policy">
        <span>UTF-8</span><span>1 MiB max</span><span>500 rows</span><span>100 columns</span><span>32 KiB per cell</span>
      </div>
      <div className="scenario-import-actions end">
        <button type="button" className="primary" disabled={busy || !sourceText.trim()} onClick={onParse}>
          {busy ? 'Inspecting...' : 'Inspect source'}
        </button>
      </div>
    </div>
  );
}

function PreviewStep({
  preview,
  onBack,
  onNext,
}: {
  preview: ScenarioImportPreview;
  onBack: () => void;
  onNext: () => void;
}) {
  return (
    <div className="scenario-import-preview">
      <div className="scenario-import-metrics">
        <Metric label="Rows" value={preview.rowCount} />
        <Metric label="Columns" value={preview.columnCount} />
        <Metric label="Sensitive" value={preview.columns.filter((column) => column.sensitive).length} tone="warning" />
        <Metric label="Warnings" value={preview.warnings.length} tone={preview.warnings.length ? 'warning' : 'ok'} />
      </div>
      {preview.warnings.length > 0 && (
        <div className="scenario-import-warnings">
          {preview.warnings.map((entry) => (
            <div key={`${entry.code}:${entry.path}`}><code>{entry.path}</code><span>{entry.message}</span></div>
          ))}
        </div>
      )}
      <div className="scenario-import-preview-table">
        <table>
          <thead><tr>{preview.columns.map((column) => <th key={column.sourcePath}>{column.label}{column.sensitive ? ' (masked)' : ''}</th>)}</tr></thead>
          <tbody>
            {preview.sampleRows.map((row) => (
              <tr key={row.rowId}>{preview.columns.map((column) => (
                <td key={column.sourcePath}>{previewValue(row.values[column.sourcePath])}</td>
              ))}</tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="scenario-import-actions">
        <button type="button" className="secondary" onClick={onBack}>Back</button>
        <button type="button" className="primary" disabled={preview.rowCount === 0 || preview.columnCount === 0} onClick={onNext}>Map columns</button>
      </div>
    </div>
  );
}

function MappingStep({
  preview,
  targets,
  bindings,
  identitySourcePath,
  classification,
  onBindingsChange,
  onIdentityChange,
  onClassificationChange,
  onBack,
  onNext,
}: {
  preview: ScenarioImportPreview;
  targets: ScenarioImportTarget[];
  bindings: ScenarioColumnBinding[];
  identitySourcePath: string;
  classification: ScenarioImportClassification;
  onBindingsChange: (bindings: ScenarioColumnBinding[]) => void;
  onIdentityChange: (value: string) => void;
  onClassificationChange: (value: ScenarioImportClassification) => void;
  onBack: () => void;
  onNext: () => void;
}) {
  const update = (sourcePath: string, change: (binding: ScenarioColumnBinding | undefined) => ScenarioColumnBinding | undefined) => {
    const current = bindings.find((binding) => binding.sourcePath === sourcePath);
    const next = change(current);
    onBindingsChange([
      ...bindings.filter((binding) => binding.sourcePath !== sourcePath),
      ...(next ? [next] : []),
    ]);
  };
  return (
    <div className="scenario-import-mapping">
      <div className="scenario-import-map-options">
        <label>Row identity
          <select value={identitySourcePath} onChange={(event) => onIdentityChange(event.target.value)}>
            <option value="">Canonical row hash</option>
            {preview.columns.map((column) => <option value={column.sourcePath} key={column.sourcePath}>{column.label}</option>)}
          </select>
        </label>
        <label>Classification
          <select value={classification} onChange={(event) => onClassificationChange(event.target.value as ScenarioImportClassification)}>
            {(['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'] as const).map((value) => <option value={value} key={value}>{value}</option>)}
          </select>
        </label>
      </div>
      <div className="scenario-import-map-list">
        {preview.columns.map((column) => {
          const binding = bindings.find((candidate) => candidate.sourcePath === column.sourcePath);
          return (
            <div className="scenario-import-map-row" key={column.sourcePath} data-mapped={Boolean(binding)}>
              <div className="scenario-import-source-column">
                <strong>{column.label}</strong>
                <code>{column.sourcePath}</code>
                <span>{column.inferredType} · {column.missingCount} missing · {column.emptyCount} empty</span>
              </div>
              <span className="scenario-import-map-arrow" aria-hidden="true">-&gt;</span>
              <label>Target
                <select
                  aria-label={`Target for ${column.label}`}
                  value={binding?.target.targetId ?? ''}
                  onChange={(event) => update(column.sourcePath, () => {
                    const target = targets.find((candidate) => candidate.targetId === event.target.value);
                    return target ? manualBinding(column.sourcePath, target) : undefined;
                  })}
                >
                  <option value="">Ignore</option>
                  {(['CASE', 'GIVEN', 'DEPENDENCY', 'THEN'] as const).map((group) => (
                    <optgroup label={group} key={group}>
                      {targets.filter((target) => target.group === group).map((target) => (
                        <option value={target.targetId} key={target.targetId}>{target.label}</option>
                      ))}
                    </optgroup>
                  ))}
                </select>
              </label>
              {binding ? (
                <>
                  <label>Convert
                    <select
                      aria-label={`Converter for ${column.label}`}
                      value={binding.converter}
                      onChange={(event) => update(column.sourcePath, (current) => current && ({
                        ...current,
                        converter: event.target.value as ScenarioImportConverter,
                      }))}
                    >
                      {CONVERTERS.map((value) => <option value={value} key={value}>{value}</option>)}
                    </select>
                  </label>
                  <label>Empty cell
                    <select
                      aria-label={`Empty semantics for ${column.label}`}
                      value={binding.valueSemantics}
                      onChange={(event) => update(column.sourcePath, (current) => current && ({
                        ...current,
                        valueSemantics: event.target.value as ScenarioImportValueSemantics,
                        defaultValue: event.target.value === 'DEFAULT' ? current.defaultValue ?? '' : undefined,
                      }))}
                    >
                      {SEMANTICS.map((value) => <option value={value} key={value}>{semanticsLabel(value)}</option>)}
                    </select>
                  </label>
                  {binding.valueSemantics === 'DEFAULT' && (
                    <label>Default
                      <input
                        aria-label={`Default value for ${column.label}`}
                        value={String(binding.defaultValue ?? '')}
                        onChange={(event) => update(column.sourcePath, (current) => current && ({ ...current, defaultValue: event.target.value }))}
                      />
                    </label>
                  )}
                  <div className="scenario-import-confidence" data-confidence={confidenceTone(binding.confidence)}>
                    <span>{Math.round(binding.confidence * 100)}%</span>
                    <small>{binding.reason.replace(/_/g, ' ')}</small>
                    {binding.confidence < 0.95 && (
                      <label><input
                        type="checkbox"
                        checked={binding.confirmed}
                        onChange={(event) => update(column.sourcePath, (current) => current && ({ ...current, confirmed: event.target.checked }))}
                      /> Confirm</label>
                    )}
                  </div>
                </>
              ) : <span className="scenario-import-ignored">Ignored</span>}
            </div>
          );
        })}
      </div>
      <div className="scenario-import-actions">
        <button type="button" className="secondary" onClick={onBack}>Back</button>
        <button type="button" className="primary" onClick={onNext}>Review plan</button>
      </div>
    </div>
  );
}

function ReviewStep({
  preview,
  bindings,
  identitySourcePath,
  classification,
  diff,
  busy,
  onBack,
  onMaterialize,
}: {
  preview: ScenarioImportPreview;
  bindings: ScenarioColumnBinding[];
  identitySourcePath: string;
  classification: ScenarioImportClassification;
  diff: ScenarioImportDiff | null;
  busy: boolean;
  onBack: () => void;
  onMaterialize: () => void;
}) {
  return (
    <div className="scenario-import-review">
      <div className="scenario-import-review-grid">
        <ReviewFact label="Source" value={`${preview.source.kind} / ${preview.rowCount} rows`} />
        <ReviewFact label="Mapping" value={`${bindings.length} bindings`} />
        <ReviewFact label="Identity" value={identitySourcePath || 'Canonical row hash'} />
        <ReviewFact label="Classification" value={classification} />
        <ReviewFact label="Contract" value={shortFingerprint(preview.source.fingerprint)} />
        <ReviewFact label="Rejected now" value="0 preflight blockers" />
      </div>
      {diff && (
        <div className="scenario-import-diff" aria-label="Import diff">
          <Metric label="Added" value={diff.added.length} tone="ok" />
          <Metric label="Changed" value={diff.changed.length} tone="warning" />
          <Metric label="Removed" value={diff.removed.length} tone="warning" />
          <Metric label="Unchanged" value={diff.unchanged.length} />
        </div>
      )}
      <div className="scenario-import-review-note">
        Materialization writes canonical Scenarios only. Runtime execution never reads this source snapshot.
      </div>
      <div className="scenario-import-actions">
        <button type="button" className="secondary" onClick={onBack}>Back</button>
        <button type="button" className="primary" disabled={busy} onClick={onMaterialize}>
          {busy ? 'Materializing...' : `Materialize ${preview.rowCount} cases`}
        </button>
      </div>
    </div>
  );
}

function ReceiptStep({
  receipt,
  onDone,
  onImportAnother,
}: {
  receipt: ScenarioMaterializationReceipt;
  onDone: () => void;
  onImportAnother: () => void;
}) {
  return (
    <div className="scenario-import-receipt">
      <div className="scenario-import-receipt-verdict" data-ok={receipt.rejectedRowCount === 0}>
        <span aria-hidden="true">{receipt.rejectedRowCount === 0 ? 'OK' : '!'}</span>
        <div><strong>{receipt.acceptedRowCount} cases materialized</strong><p>{receipt.rejectedRowCount} rejected rows</p></div>
      </div>
      <dl>
        <dt>Receipt</dt><dd><code>{receipt.receiptId}</code></dd>
        <dt>Plan</dt><dd><code>{receipt.planFingerprint}</code></dd>
        <dt>Source</dt><dd><code>{receipt.sourceFingerprint}</code></dd>
        <dt>Mapping</dt><dd><code>{receipt.mappingFingerprint}</code></dd>
        <dt>Contract</dt><dd><code>{receipt.contractFingerprint}</code></dd>
        <dt>Actor</dt><dd>{receipt.actor}</dd>
        <dt>Created</dt><dd>{receipt.materializedAt}</dd>
      </dl>
      {receipt.rejectedRowCount > 0 && (
        <div className="scenario-import-rejections">
          {receipt.rows.filter((row) => row.status === 'REJECTED').map((row) => (
            <div key={row.rowFingerprint}><code>{row.diagnosticCode}</code><span>{shortFingerprint(row.rowFingerprint)}</span></div>
          ))}
        </div>
      )}
      <div className="scenario-import-actions">
        <button type="button" className="secondary" onClick={onImportAnother}>Import another</button>
        <button type="button" className="primary" onClick={onDone}>Done</button>
      </div>
    </div>
  );
}

function Metric({ label, value, tone = 'neutral' }: { label: string; value: number; tone?: 'neutral' | 'warning' | 'ok' }) {
  return <div className="scenario-import-metric" data-tone={tone}><span>{label}</span><strong>{value}</strong></div>;
}

function ReviewFact({ label, value }: { label: string; value: string }) {
  return <div><span>{label}</span><strong>{value}</strong></div>;
}

function manualBinding(sourcePath: string, target: ScenarioImportTarget): ScenarioColumnBinding {
  return {
    bindingId: `${sourcePath}->${target.targetId}`,
    sourcePath,
    target,
    confidence: 1,
    reason: 'MANUAL',
    confirmed: true,
    converter: 'IDENTITY',
    valueSemantics: 'VALUE',
  };
}

function materializationReceiptFrom(draftSet: ScenarioDraftSet): ScenarioMaterializationReceipt | null {
  const value = draftSet.metadata.provenance.scenarioImportReceipt;
  if (value === null || typeof value !== 'object') return null;
  const candidate = value as Partial<ScenarioMaterializationReceipt>;
  return candidate.schemaVersion === 'bloge.scenarioMaterializationReceipt.v1'
    && Array.isArray(candidate.rows)
    ? candidate as ScenarioMaterializationReceipt
    : null;
}

function importErrorMessage(cause: unknown): string {
  if (cause instanceof ScenarioImportError) return `${cause.code}: ${cause.message}`;
  return cause instanceof Error ? cause.message : 'Import could not be completed.';
}

function previewValue(value: unknown): string {
  if (value === undefined) return 'MISSING';
  if (value === null) return 'NULL';
  if (value === '') return 'EMPTY';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function semanticsLabel(value: ScenarioImportValueSemantics): string {
  switch (value) {
    case 'VALUE': return 'Keep value';
    case 'EMPTY': return 'Empty string';
    case 'NULL': return 'Set null';
    case 'MISSING': return 'Omit field';
    case 'DEFAULT': return 'Use default';
  }
}

function confidenceTone(confidence: number): 'high' | 'medium' | 'low' {
  if (confidence >= 0.95) return 'high';
  if (confidence >= 0.75) return 'medium';
  return 'low';
}

function stepLabel(step: ImportStep): string {
  switch (step) {
    case 'SOURCE': return 'Source';
    case 'PREVIEW': return 'Preview';
    case 'MAP': return 'Map';
    case 'REVIEW': return 'Review';
    case 'RECEIPT': return 'Receipt';
  }
}

function stepDescription(step: ImportStep): string {
  switch (step) {
    case 'SOURCE': return 'Choose a bounded CSV or JSON snapshot.';
    case 'PREVIEW': return 'Inspect shape, masking and parser warnings.';
    case 'MAP': return 'Bind source columns to canonical Scenario fields.';
    case 'REVIEW': return 'Freeze the exact materialization closure.';
    case 'RECEIPT': return 'Use the receipt to review and reproduce this import.';
  }
}

function shortFingerprint(value: string): string {
  return value.length > 24 ? `${value.slice(0, 18)}...${value.slice(-6)}` : value;
}

function sampleJson(draftSet: ScenarioDraftSet): string {
  const templateInput = draftSet.scenarios[0]?.given.input;
  const input = templateInput !== null && typeof templateInput === 'object' && !Array.isArray(templateInput)
    ? Object.fromEntries(Object.entries(templateInput as Record<string, unknown>).slice(0, 3))
    : { input: templateInput ?? 'sample' };
  const caseTypes = ['GOLDEN', 'BOUNDARY', 'NEGATIVE', 'REGRESSION', 'GOLDEN'];
  return JSON.stringify(Array.from({ length: 5 }, (_, index) => ({
    id: `import-example-${index + 1}`,
    name: `Import example ${index + 1}`,
    caseType: caseTypes[index],
    tags: ['imported', index === 1 ? 'boundary' : 'demo'],
    ...input,
  })), null, 2);
}
