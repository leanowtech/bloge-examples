import { useEffect, useMemo, useState } from 'react';
import { fetchScenarioOperatorContract, saveScenarioDraftSet } from '../api';
import type { ExactTargetRef, EnterpriseScope, ScenarioContractProjection, ScenarioDraftSet } from '../contract-scenario/domain';
import {
  enumerateFromEditor,
  operatorScenarioDraftSetId,
  scenarioSetIsStale,
  type DecisionEditorSnapshot,
} from './decisionScenarioModel';
import type { DecisionOutputKind, EnumerationResult } from './decisionScenario';
import './decisionScenario.css';
import { useI18n } from '../i18n/I18nProvider';

/** Props for the spine-gated decision scenario authoring surface. */
export interface DecisionScenarioWorkbenchProps {
  editor: DecisionEditorSnapshot;
  tableId: string;
  target: ExactTargetRef;
  scope: EnterpriseScope;
  owner: string;
  persisted: ScenarioDraftSet | null;
  onPersistedChange: (draftSet: ScenarioDraftSet) => void;
  onOutputKindChange?: (outputKind: DecisionOutputKind) => void;
  /** Opens the persisted set in the canonical Scenarios surface. */
  onOpenScenarios?: () => void;
  operatorRef?: string;
}

/** Generates, previews and persists decision-table scenarios through the authoritative endpoint. */
export function DecisionScenarioWorkbench(props: DecisionScenarioWorkbenchProps) {
  const { t } = useI18n();
  const [mode, setMode] = useState<'per-rule' | 'combinatorial'>('per-rule');
  const [cap, setCap] = useState(10);
  const [outputKind, setOutputKind] = useState<DecisionOutputKind>(props.editor.outputKind ?? 'object');
  const [preview, setPreview] = useState<EnumerationResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [authority, setAuthority] = useState<ScenarioContractProjection | null>(null);
  const stale = useMemo(() => scenarioSetIsStale({ ...props.editor, outputKind }, props.persisted, props.tableId), [props.editor, outputKind, props.persisted, props.tableId]);
  useEffect(() => { setOutputKind(props.editor.outputKind ?? 'object'); }, [props.editor.outputKind]);

  const generate = async () => {
    setError(null);
    setSaved(false);
    try {
      const operatorRef = props.operatorRef ?? props.target.id;
      const projection = await fetchScenarioOperatorContract(operatorRef);
      setAuthority(projection);
      const scenarioDraftSetId = await operatorScenarioDraftSetId(operatorRef);
      const enumerated = enumerateFromEditor({ ...props.editor, outputKind }, { mode, cap: Math.min(10_000, Math.max(1, cap)), target: projection.contract.target, scope: projection.scope, owner: props.owner, contractFingerprint: projection.contractFingerprint, scenarioDraftSetId }, props.tableId);
      setPreview({
        ...enumerated,
        draftSet: {
          ...enumerated.draftSet,
          metadata: {
            ...enumerated.draftSet.metadata,
            provenance: {
              ...enumerated.draftSet.metadata.provenance,
              operatorRef: props.operatorRef ?? props.target.id,
              sourceNodeId: props.tableId,
            },
          },
        },
      });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('Unable to enumerate decision scenarios.'));
      setPreview(null);
    }
  };
  const persist = async () => {
    if (!preview || !authority) { setError(t('Load the authoritative contract before saving generated scenarios.')); return; }
    setBusy(true);
    setError(null);
    try {
      const stored = await saveScenarioDraftSet(preview.draftSet);
      props.onPersistedChange(stored.draftSet);
      setSaved(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('Unable to save generated scenarios. Retry.'));
    } finally { setBusy(false); }
  };
  return (
    <section className="decision-scenario-workbench" data-testid="decision-scenario-workbench">
      <div className="decision-scenario-controls">
        <label><span>{t('Output kind')}</span><select aria-label={t('Decision output kind')} value={outputKind} onChange={(event) => { const next = event.target.value as DecisionOutputKind; setOutputKind(next); props.onOutputKindChange?.(next); }}><option value="object">object</option><option value="scalar">scalar</option><option value="plan">plan</option><option value="dispatch">{t('dispatch (model-only)')}</option></select></label>
        <label><span>{t('Scenario enumeration mode')}</span><select aria-label={t('Scenario enumeration mode')} value={mode} onChange={(event) => setMode(event.target.value as typeof mode)}><option value="per-rule">{t('per rule')}</option><option value="combinatorial">{t('combinatorial')}</option></select></label>
        <label><span>{t('Cap')}</span><input aria-label={t('Scenario enumeration cap')} type="number" min={1} max={10000} value={cap} onChange={(event) => setCap(Number(event.target.value))} /></label>
        <button type="button" className="secondary compact" onClick={generate} data-testid="generate-decision-scenarios">{stale ? t('Re-enumerate stale scenarios') : t('Generate scenarios')}</button>
      </div>
      {outputKind === 'dispatch' && <p className="decision-scenario-warning">{t('Dispatch output is modeled only; no dispatch is executed.')}</p>}
      {stale && <ScenarioStalenessNotice onReenumerate={() => void generate()} />}
      {preview && <div className="decision-scenario-preview" data-testid="decision-scenario-preview"><span>{t('{count} scenarios', { count: preview.scenarios.length })}</span><span>{t('source {fingerprint}', { fingerprint: preview.metadata.sourceFingerprint })}</span>{preview.metadata.truncated && <strong>{t('Truncated at cap; review stratified sample.')}</strong>}{preview.metadata.opaqueColumns.length > 0 && <strong>{t('Opaque columns: {columns}; not exhaustive.', { columns: preview.metadata.opaqueColumns.join(', ') })}</strong>}<button type="button" className="primary compact" onClick={() => void persist()} disabled={busy}>{busy ? t('Saving…') : t('Save generated set')}</button>{saved && props.onOpenScenarios && <button type="button" className="secondary compact" data-testid="open-generated-scenarios" onClick={props.onOpenScenarios}>{t('Open Scenarios')}</button>}</div>}
      {error && <div className="decision-scenario-error" role="alert"><span>{error}</span>{preview && <button type="button" className="secondary compact" onClick={() => void persist()} disabled={busy}>{t('Retry')}</button>}</div>}
    </section>
  );
}

/** Stale-source diagnostic with an explicit deterministic re-enumeration action. */
export function ScenarioStalenessNotice({ onReenumerate }: { onReenumerate: () => void }) {
  const { t } = useI18n();
  return <div className="decision-scenario-stale" role="status" data-testid="decision-scenario-stale"><span>{t('Decision table changed; generated scenarios are stale.')}</span><button type="button" className="secondary compact" onClick={onReenumerate}>{t('Re-enumerate')}</button></div>;
}
