import { BadgeCheck, FilePlus2, Save, Send } from 'lucide-react';
import { useEffect, useState } from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import {
  approveScenario,
  fetchScenarioDraftSet,
  markScenarioReviewReady,
  saveScenarioDraftSet,
} from '../api/correctnessAuthoringApi';
import type {
  ControlledDependency,
  ScenarioDraftSetV2,
  ScenarioDraftV2,
  StoredScenarioDraftSetV2,
} from '../model/authoring';
import type { CorrectnessWorkspaceProjection } from '../model/domain';
import {
  AssetState,
  AuthoringBoundary,
  commandId,
  MutationState,
  useExactAsset,
} from './shared';

export default function CaseStudio({ workspace, available }: {
  workspace: CorrectnessWorkspaceProjection;
  available: boolean;
}) {
  const { t } = useI18n();
  const asset = useExactAsset<StoredScenarioDraftSetV2>(
    available,
    workspace.cases.scenarioDraftSetRef,
    fetchScenarioDraftSet,
  );
  const [draftSet, setDraftSet] = useState<ScenarioDraftSetV2 | null>(null);
  const [selectedCaseId, setSelectedCaseId] = useState('');
  const [selectedDependencyId, setSelectedDependencyId] = useState('');
  const [reviewComment, setReviewComment] = useState('');
  const [mutation, setMutation] = useState<{
    tone: 'idle' | 'busy' | 'success' | 'error'; message: string;
  }>({ tone: 'idle', message: '' });

  useEffect(() => {
    if (!asset.value) return;
    setDraftSet(asset.value.scenarioDraftSet);
    setSelectedCaseId((current) => current || asset.value?.scenarioDraftSet.scenarios[0]?.scenarioId || '');
  }, [asset.value]);

  const selected = draftSet?.scenarios.find((item) => item.scenarioId === selectedCaseId) ?? null;
  const dependency = selected?.dependencies.find((item) => item.dependencyId === selectedDependencyId)
    ?? selected?.dependencies[0] ?? null;

  useEffect(() => {
    setSelectedDependencyId(selected?.dependencies[0]?.dependencyId ?? '');
  }, [selectedCaseId]);

  const updateCase = (change: Partial<ScenarioDraftV2>) => {
    if (!draftSet || !selected) return;
    setDraftSet({
      ...draftSet,
      scenarios: draftSet.scenarios.map((item) => (
        item.scenarioId === selected.scenarioId ? { ...item, ...change } : item
      )),
    });
    setMutation({ tone: 'idle', message: '' });
  };

  const updateDependency = (change: Partial<ControlledDependency>) => {
    if (!selected || !dependency) return;
    updateCase({
      dependencies: selected.dependencies.map((item) => (
        item.dependencyId === dependency.dependencyId ? { ...item, ...change } : item
      )),
    });
  };

  const addCase = () => {
    if (!draftSet) return;
    const source = selected ?? draftSet.scenarios[0];
    if (!source) return;
    const scenarioId = uniqueId('business-case', draftSet.scenarios.map((item) => item.scenarioId));
    const next: ScenarioDraftV2 = {
      ...source,
      scenarioId,
      name: 'New business Case',
      businessIntent: 'Describe the business behavior this Case proves.',
      lifecycle: 'EXPLORATORY',
      obligationRefs: [], oracleRefs: [], assertionSetRefs: [], sourceRefs: [],
      review: { status: 'PENDING', reviewer: null, reviewedAt: null, comment: '' },
      tags: [],
    };
    setDraftSet({ ...draftSet, scenarios: [...draftSet.scenarios, next] });
    setSelectedCaseId(scenarioId);
  };

  const addDependency = () => {
    if (!selected) return;
    const dependencyId = uniqueId('dependency', selected.dependencies.map((item) => item.dependencyId));
    const next: ControlledDependency = {
      dependencyId,
      selector: {
        graphPath: '', nodeId: 'choose-node', operatorRef: '', resourceRef: '', functionRef: '',
        attempts: [], occurrences: [], correlationKey: '', pathMatches: [],
      },
      behavior: { kind: 'RETURN', boundary: 'NODE', value: { kind: 'INLINE', value: {} }, errorCode: '', delayMs: 0 },
      consumption: { required: true, minUses: 1, maxUses: 1, onExhausted: 'FAIL', onUnmatched: 'FAIL' },
    };
    updateCase({ dependencies: [...selected.dependencies, next] });
    setSelectedDependencyId(dependencyId);
  };

  const save = async () => {
    if (!draftSet) return;
    setMutation({ tone: 'busy', message: t('Saving exact revision') });
    try {
      const response = await saveScenarioDraftSet(draftSet);
      asset.setValue(response.data);
      setDraftSet(response.data.scenarioDraftSet);
      setMutation({ tone: 'success', message: t('Scenario set saved') });
    } catch (cause) {
      setMutation({ tone: 'error', message: errorMessage(cause) });
    }
  };

  const markReady = async () => {
    if (!draftSet || !selected || selected.assertionSetRefs.length === 0) return;
    setMutation({ tone: 'busy', message: t('Checking Case closure') });
    try {
      const response = await markScenarioReviewReady(
        draftSet.scenarioDraftSetId, selected.scenarioId, draftSet.revision,
      );
      asset.setValue(response.data.stored);
      setDraftSet(response.data.stored.scenarioDraftSet);
      setMutation({ tone: 'success', message: t('Case is ready for review') });
    } catch (cause) {
      setMutation({ tone: 'error', message: errorMessage(cause) });
    }
  };

  const approve = async () => {
    if (!draftSet || !selected || !reviewComment.trim()) return;
    setMutation({ tone: 'busy', message: t('Approving canonical Case') });
    try {
      const response = await approveScenario(
        draftSet.scenarioDraftSetId,
        selected.scenarioId,
        draftSet.revision,
        reviewComment.trim(),
        commandId(`approve:${selected.scenarioId}`, draftSet.revision),
      );
      asset.setValue(response.data.stored);
      setDraftSet(response.data.stored.scenarioDraftSet);
      setMutation({ tone: 'success', message: t('Canonical Case approved') });
    } catch (cause) {
      setMutation({ tone: 'error', message: errorMessage(cause) });
    }
  };

  return (
    <AuthoringBoundary available={available}>
      <section className="correctness-authoring-panel" data-testid="case-studio">
        <header className="correctness-authoring-heading">
          <div><strong>{t('Case Builder')}</strong><span>{t('Control inputs and dependencies without writing fixture JSON.')}</span></div>
          <button type="button" onClick={addCase} disabled={!draftSet}><FilePlus2 size={17} /><span>{t('Add Case')}</span></button>
        </header>
        <AssetState state={asset.state} error={asset.error} />
        {draftSet && (
          <div className="correctness-authoring-split">
            <div className="correctness-authoring-list" role="listbox" aria-label={t('Canonical Cases')}>
              {draftSet.scenarios.map((item) => (
                <button key={item.scenarioId} type="button" role="option" aria-selected={item.scenarioId === selectedCaseId} onClick={() => setSelectedCaseId(item.scenarioId)}>
                  <span className="correctness-risk" data-risk={item.risk}>{t(item.risk)}</span>
                  <strong>{item.name}</strong><small>{t(item.caseType)} · {t(item.lifecycle)}</small>
                </button>
              ))}
            </div>
            {selected && (
              <div className="correctness-case-editor">
                <div className="correctness-form-grid">
                  <label>{t('Case name')}<input value={selected.name} onChange={(event) => updateCase({ name: event.target.value })} /></label>
                  <label>{t('Case type')}<select value={selected.caseType} onChange={(event) => updateCase({ caseType: event.target.value as ScenarioDraftV2['caseType'] })}>
                    {['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION', 'PROPERTY'].map((value) => <option key={value}>{value}</option>)}
                  </select></label>
                  <label className="wide">{t('Business intent')}<textarea rows={2} value={selected.businessIntent} onChange={(event) => updateCase({ businessIntent: event.target.value })} /></label>
                  <label>{t('Risk')}<select value={selected.risk} onChange={(event) => updateCase({ risk: event.target.value as ScenarioDraftV2['risk'] })}>
                    {['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((value) => <option key={value}>{value}</option>)}
                  </select></label>
                  <label>{t('Tags')}<input value={selected.tags.join(', ')} onChange={(event) => updateCase({ tags: splitValues(event.target.value) })} /></label>
                </div>
                <InputSourceEditor workspace={workspace} scenario={selected} onChange={(input) => updateCase({ given: { input } })} />
                <section className="correctness-dependency-editor">
                  <header><div><strong>{t('Controlled dependencies')}</strong><span>{t('Choose which runtime calls are real, simulated, delayed, or forbidden.')}</span></div><button type="button" onClick={addDependency}>{t('Add dependency')}</button></header>
                  <div className="correctness-dependency-tabs">
                    {selected.dependencies.map((item) => <button type="button" key={item.dependencyId} aria-pressed={item.dependencyId === dependency?.dependencyId} onClick={() => setSelectedDependencyId(item.dependencyId)}>{item.dependencyId}<small>{t(item.behavior.kind)}</small></button>)}
                  </div>
                  {dependency && <DependencyEditor value={dependency} onChange={updateDependency} />}
                </section>
                <section className="correctness-proof-closure">
                  <strong>{t('Proof closure')}</strong>
                  <span>{selected.obligationRefs.length} {t('obligations')}</span>
                  <span>{selected.oracleRefs.length} {t('Oracles')}</span>
                  <span>{selected.assertionSetRefs.length} {t('Assertion Sets')}</span>
                  <span>{selected.sourceRefs.length} {t('Fixture or source refs')}</span>
                </section>
              </div>
            )}
          </div>
        )}
        {draftSet && selected && (
          <footer className="correctness-authoring-footer">
            <div className="correctness-review-command">
              <label>{t('Review comment')}<input value={reviewComment} onChange={(event) => setReviewComment(event.target.value)} placeholder={t('Record the independent review decision')} /></label>
              {selected.assertionSetRefs.length === 0 && <small>{t('Bind an exact Assertion Set before review.')}</small>}
            </div>
            <MutationState state={mutation} />
            <button type="button" onClick={save} disabled={mutation.tone === 'busy'}><Save size={17} />{t('Save draft')}</button>
            <button type="button" onClick={markReady} disabled={mutation.tone === 'busy' || selected.lifecycle !== 'EXPLORATORY' || selected.assertionSetRefs.length === 0}><Send size={17} />{t('Send to review')}</button>
            <button type="button" className="correctness-primary-command" onClick={approve} disabled={mutation.tone === 'busy' || selected.lifecycle !== 'REVIEW_READY' || !reviewComment.trim()}><BadgeCheck size={17} />{t('Approve canonical')}</button>
          </footer>
        )}
      </section>
    </AuthoringBoundary>
  );
}

function InputSourceEditor({ workspace, scenario, onChange }: {
  workspace: CorrectnessWorkspaceProjection;
  scenario: ScenarioDraftV2;
  onChange(value: ScenarioDraftV2['given']['input']): void;
}) {
  const { t } = useI18n();
  const source = scenario.given.input;
  const inline = source.kind === 'INLINE' ? objectValue(source.value) : {};
  const changeKind = (kind: 'INLINE' | 'FIXTURE_VARIANT') => {
    if (kind === 'INLINE') return onChange({ kind: 'INLINE', value: {} });
    const fixture = workspace.fixtures.rows[0];
    if (fixture) onChange({ kind: 'FIXTURE_VARIANT', fixtureAssetRef: fixture.descriptorRef, variantKey: fixture.variantKey });
  };
  return (
    <section className="correctness-input-source">
      <header><strong>{t('Given input')}</strong><span>{t('Select a governed source; advanced payload stays out of the workspace projection.')}</span></header>
      <div className="correctness-segmented" role="group" aria-label={t('Input source')}>
        {(['INLINE', 'FIXTURE_VARIANT'] as const).map((kind) => <button key={kind} type="button" aria-pressed={source.kind === kind} onClick={() => changeKind(kind)}>{t(kind)}</button>)}
      </div>
      {source.kind === 'INLINE' ? (
        <KeyValueEditor value={inline} onChange={(value) => onChange({ kind: 'INLINE', value })} />
      ) : source.kind === 'FIXTURE_VARIANT' ? (
        <label>{t('Fixture variant')}<select value={`${source.fixtureAssetRef.id}:${source.variantKey}`} onChange={(event) => {
          const fixture = workspace.fixtures.rows.find((item) => `${item.descriptorRef.id}:${item.variantKey}` === event.target.value);
          if (fixture) onChange({ kind: 'FIXTURE_VARIANT', fixtureAssetRef: fixture.descriptorRef, variantKey: fixture.variantKey });
        }}>{workspace.fixtures.rows.map((fixture) => <option key={`${fixture.descriptorRef.id}:${fixture.variantKey}`} value={`${fixture.descriptorRef.id}:${fixture.variantKey}`}>{fixture.name} · {fixture.variantKey}</option>)}</select></label>
      ) : <p>{t('This input source is preserved but edited by its specialized authority.')}</p>}
    </section>
  );
}

function KeyValueEditor({ value, onChange }: { value: Record<string, unknown>; onChange(value: Record<string, unknown>): void }) {
  const { t } = useI18n();
  const rows = Object.entries(value);
  return <div className="correctness-kv-editor">
    {rows.map(([key, current]) => <div key={key}><code>{key}</code><input aria-label={`${key} value`} value={String(current ?? '')} onChange={(event) => onChange({ ...value, [key]: inferPrimitive(event.target.value) })} /><button type="button" title={t('Remove field')} onClick={() => { const next = { ...value }; delete next[key]; onChange(next); }}>×</button></div>)}
    <button type="button" onClick={() => onChange({ ...value, [`field${rows.length + 1}`]: '' })}>{t('Add input field')}</button>
  </div>;
}

function DependencyEditor({ value, onChange }: { value: ControlledDependency; onChange(value: Partial<ControlledDependency>): void }) {
  const { t } = useI18n();
  return <div className="correctness-form-grid compact">
    <label>{t('Node')}<input value={value.selector.nodeId} onChange={(event) => onChange({ selector: { ...value.selector, nodeId: event.target.value } })} /></label>
    <label>{t('Operator ref')}<input value={value.selector.operatorRef} onChange={(event) => onChange({ selector: { ...value.selector, operatorRef: event.target.value } })} /></label>
    <label>{t('Behavior')}<select value={value.behavior.kind} onChange={(event) => onChange({ behavior: behaviorFor(event.target.value as ControlledDependency['behavior']['kind'], value.behavior) })}>
      {['REAL', 'RETURN', 'ERROR', 'DELAY', 'TIMEOUT', 'REPLAY', 'OBSERVE', 'MUST_NOT_CALL'].map((kind) => <option key={kind}>{kind}</option>)}
    </select></label>
    <label>{t('Boundary')}<select value={value.behavior.boundary} onChange={(event) => onChange({ behavior: { ...value.behavior, boundary: event.target.value as 'NODE' | 'TRANSPORT' } })}><option>NODE</option><option>TRANSPORT</option></select></label>
    {value.behavior.kind === 'ERROR' && <label>{t('Error code')}<input value={value.behavior.errorCode} onChange={(event) => onChange({ behavior: { ...value.behavior, errorCode: event.target.value } })} /></label>}
    {(value.behavior.kind === 'DELAY' || value.behavior.kind === 'TIMEOUT') && <label>{t('Delay ms')}<input type="number" min="0" value={value.behavior.delayMs} onChange={(event) => onChange({ behavior: { ...value.behavior, delayMs: Number(event.target.value) } })} /></label>}
    <label>{t('Minimum uses')}<input type="number" min="0" value={value.consumption.minUses} onChange={(event) => onChange({ consumption: { ...value.consumption, minUses: Number(event.target.value) } })} /></label>
    <label>{t('Maximum uses')}<input type="number" min={value.consumption.minUses} value={value.consumption.maxUses} onChange={(event) => onChange({ consumption: { ...value.consumption, maxUses: Number(event.target.value) } })} /></label>
  </div>;
}

function behaviorFor(kind: ControlledDependency['behavior']['kind'], current: ControlledDependency['behavior']): ControlledDependency['behavior'] {
  return {
    ...current,
    kind,
    errorCode: kind === 'ERROR' ? current.errorCode || 'EXPECTED_ERROR' : '',
    value: kind === 'RETURN' || kind === 'REPLAY' ? current.value ?? { kind: 'INLINE', value: {} } : null,
  };
}

function objectValue(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
}

function inferPrimitive(value: string): string | number | boolean | null {
  if (value === 'true') return true;
  if (value === 'false') return false;
  if (value === 'null') return null;
  const number = Number(value);
  return value.trim() && Number.isFinite(number) ? number : value;
}

function uniqueId(prefix: string, used: string[]): string {
  let suffix = used.length + 1;
  while (used.includes(`${prefix}-${suffix}`)) suffix += 1;
  return `${prefix}-${suffix}`;
}

function splitValues(value: string): string[] {
  return [...new Set(value.split(',').map((item) => item.trim()).filter(Boolean))].sort();
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
