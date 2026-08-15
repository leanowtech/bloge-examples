import { BadgeCheck, CheckCheck, FilePlus2, Save, WandSparkles } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import {
  approveBusinessOracle,
  fetchAssertionSet,
  fetchBusinessOracle,
  fetchScenarioDraftSet,
  previewAssertionSet,
  saveAssertionSet,
  saveBusinessOracle,
  validateAssertionSet,
} from '../api/correctnessAuthoringApi';
import type {
  AssertionCompilationReport,
  AssertionSet,
  AssertionType,
  BusinessOracle,
  ExecutableAssertionSpec,
  StoredAssertionSet,
  StoredBusinessOracle,
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

export default function OracleStudio({ workspace, available }: {
  workspace: CorrectnessWorkspaceProjection;
  available: boolean;
}) {
  const { t } = useI18n();
  const scenarios = useExactAsset<StoredScenarioDraftSetV2>(
    available,
    workspace.cases.scenarioDraftSetRef,
    fetchScenarioDraftSet,
  );
  const [caseId, setCaseId] = useState('');
  const selectedCase = scenarios.value?.scenarioDraftSet.scenarios
    .find((item) => item.scenarioId === caseId)
    ?? scenarios.value?.scenarioDraftSet.scenarios[0]
    ?? null;

  useEffect(() => {
    if (!caseId && scenarios.value?.scenarioDraftSet.scenarios[0]) {
      setCaseId(scenarios.value.scenarioDraftSet.scenarios[0].scenarioId);
    }
  }, [caseId, scenarios.value]);

  const oracleRef = selectedCase?.oracleRefs[0] ?? null;
  const assertionRef = selectedCase?.assertionSetRefs[0] ?? null;
  const oracle = useExactAsset<StoredBusinessOracle>(available, oracleRef, fetchBusinessOracle);
  const assertions = useExactAsset<StoredAssertionSet>(available, assertionRef, fetchAssertionSet);
  const [oracleDraft, setOracleDraft] = useState<BusinessOracle | null>(null);
  const [assertionDraft, setAssertionDraft] = useState<AssertionSet | null>(null);
  const [selectedAssertionId, setSelectedAssertionId] = useState('');
  const [reviewComment, setReviewComment] = useState('');
  const [preview, setPreview] = useState<AssertionCompilationReport | null>(null);
  const [mutation, setMutation] = useState<{
    tone: 'idle' | 'busy' | 'success' | 'error'; message: string;
  }>({ tone: 'idle', message: '' });

  useEffect(() => { setOracleDraft(oracle.value?.oracle ?? null); }, [oracle.value]);
  useEffect(() => {
    setAssertionDraft(assertions.value?.assertionSet ?? null);
    setSelectedAssertionId(assertions.value?.assertionSet.assertions[0]?.assertionId ?? '');
    setPreview(null);
  }, [assertions.value]);

  const selectedAssertion = assertionDraft?.assertions
    .find((item) => item.assertionId === selectedAssertionId) ?? null;
  const unsupportedCount = useMemo(() => preview?.dispositions
    .filter((item) => item.status === 'UNSUPPORTED').length ?? 0, [preview]);

  const updateAssertion = (change: Partial<ExecutableAssertionSpec>) => {
    if (!assertionDraft || !selectedAssertion) return;
    setAssertionDraft({
      ...assertionDraft,
      lifecycle: 'DRAFT',
      assertions: assertionDraft.assertions.map((item) => (
        item.assertionId === selectedAssertion.assertionId ? { ...item, ...change } : item
      )),
    });
    setPreview(null);
  };

  const changeAssertionType = (type: AssertionType) => {
    if (!assertionDraft || !selectedAssertion) return;
    const replacement = defaultAssertion(type, selectedAssertion.assertionId);
    setAssertionDraft({
      ...assertionDraft,
      lifecycle: 'DRAFT',
      assertions: assertionDraft.assertions.map((item) => (
        item.assertionId === selectedAssertion.assertionId ? replacement : item
      )),
    });
    setPreview(null);
  };

  const addAssertion = () => {
    if (!assertionDraft) return;
    const id = uniqueId('assertion', assertionDraft.assertions.map((item) => item.assertionId));
    setAssertionDraft({
      ...assertionDraft,
      lifecycle: 'DRAFT',
      assertions: [...assertionDraft.assertions, defaultAssertion('OUTPUT', id)],
    });
    setSelectedAssertionId(id);
    setPreview(null);
  };

  const saveOracle = () => mutate(t('Saving business Oracle'), async () => {
    if (!oracleDraft) return;
    const response = await saveBusinessOracle(oracleDraft);
    oracle.setValue(response.data);
    setOracleDraft(response.data.oracle);
    return t('Business Oracle saved');
  });

  const approveOracle = () => mutate(t('Approving business Oracle'), async () => {
    if (!oracleDraft || !reviewComment.trim()) return;
    const response = await approveBusinessOracle(
      oracleDraft.oracleId,
      oracleDraft.revision,
      reviewComment.trim(),
      commandId(`approve:${oracleDraft.oracleId}`, oracleDraft.revision),
    );
    oracle.setValue(response.data.stored);
    setOracleDraft(response.data.stored.oracle);
    return t('Business Oracle approved');
  });

  const compilePreview = () => mutate(t('Compiling assertion preview'), async () => {
    if (!assertionDraft) return;
    const response = await previewAssertionSet(assertionDraft);
    setPreview(response.data);
    return response.data.compatibility.supported
      ? t('All assertions are supported')
      : t('{count} assertions are unsupported', { count: response.data.dispositions.filter((item) => item.status === 'UNSUPPORTED').length });
  });

  const saveAssertions = () => mutate(t('Saving Assertion Set'), async () => {
    if (!assertionDraft) return;
    const response = await saveAssertionSet(assertionDraft);
    assertions.setValue(response.data);
    setAssertionDraft(response.data.assertionSet);
    return t('Assertion Set saved');
  });

  const validateAssertions = () => mutate(t('Validating executable assertions'), async () => {
    if (!assertionDraft) return;
    const response = await validateAssertionSet(assertionDraft.assertionSetId, assertionDraft.revision);
    assertions.setValue(response.data.stored);
    setAssertionDraft(response.data.stored.assertionSet);
    setPreview(response.data.compilation);
    return t('Assertion Set is executable');
  });

  async function mutate(
    pending: string,
    operation: () => Promise<string | undefined>,
  ) {
    setMutation({ tone: 'busy', message: pending });
    try {
      const success = await operation();
      setMutation(success
        ? { tone: 'success', message: success }
        : { tone: 'idle', message: '' });
    } catch (cause) {
      setMutation({ tone: 'error', message: errorMessage(cause) });
    }
  }

  return (
    <AuthoringBoundary available={available}>
      <section className="correctness-authoring-panel" data-testid="oracle-studio">
        <header className="correctness-authoring-heading">
          <div><strong>{t('Oracle and Assertion Builder')}</strong><span>{t('Separate business approval from executable proof.')}</span></div>
          {scenarios.value && <label className="correctness-case-picker">{t('Case')}<select value={selectedCase?.scenarioId ?? ''} onChange={(event) => setCaseId(event.target.value)}>{scenarios.value.scenarioDraftSet.scenarios.map((item) => <option key={item.scenarioId} value={item.scenarioId}>{item.name}</option>)}</select></label>}
        </header>
        <AssetState state={scenarios.state} error={scenarios.error} />
        {!oracleRef && selectedCase && <p className="correctness-authoring-state error">{t('The selected Case has no exact Business Oracle reference.')}</p>}
        {!assertionRef && selectedCase && <p className="correctness-authoring-state error">{t('The selected Case has no exact Assertion Set reference.')}</p>}
        <div className="correctness-oracle-builder-grid">
          <section>
            <header><strong>{t('Business Oracle')}</strong><span>{t('Owner authority: what outcome is correct?')}</span></header>
            <AssetState state={oracle.state} error={oracle.error} />
            {oracleDraft && <div className="correctness-form-grid single">
              <label className="wide">{t('Correct outcome statement')}<textarea rows={4} value={oracleDraft.statement} onChange={(event) => setOracleDraft({ ...oracleDraft, lifecycle: 'PROPOSED', statement: event.target.value })} /></label>
              <label className="wide">{t('Forbidden outcomes')}<input value={oracleDraft.forbiddenOutcomes.join(', ')} onChange={(event) => setOracleDraft({ ...oracleDraft, lifecycle: 'PROPOSED', forbiddenOutcomes: splitValues(event.target.value) })} /></label>
              <p className="correctness-exact-ref">{t('Basis refs')}: {oracleDraft.basisRefs.length} · {t(oracleDraft.lifecycle)}</p>
              <label className="wide">{t('Review comment')}<input value={reviewComment} onChange={(event) => setReviewComment(event.target.value)} placeholder={t('Explain the independent business decision')} /></label>
              <div className="correctness-form-commands wide"><button type="button" onClick={saveOracle} disabled={mutation.tone === 'busy' || oracleDraft.lifecycle !== 'PROPOSED'}><Save size={16} />{t('Save Oracle')}</button><button type="button" className="correctness-primary-command" onClick={approveOracle} disabled={mutation.tone === 'busy' || oracleDraft.lifecycle !== 'PROPOSED' || !reviewComment.trim()}><BadgeCheck size={16} />{t('Approve Oracle')}</button></div>
            </div>}
          </section>
          <section>
            <header><strong>{t('Assertion Set')}</strong><span>{t('Executable authority: how is the outcome checked?')}</span></header>
            <AssetState state={assertions.state} error={assertions.error} />
            {assertionDraft && <>
              <div className="correctness-assertion-tabs">{assertionDraft.assertions.map((item) => <button key={item.assertionId} type="button" aria-pressed={item.assertionId === selectedAssertionId} onClick={() => setSelectedAssertionId(item.assertionId)}><strong>{item.assertionId}</strong><small>{t(item.type)} · {t(item.evaluationKind)}</small></button>)}<button type="button" title={t('Add assertion')} onClick={addAssertion}><FilePlus2 size={17} /></button></div>
              {selectedAssertion && <AssertionEditor value={selectedAssertion} onChange={updateAssertion} onTypeChange={changeAssertionType} />}
              {preview && <div className="correctness-compile-preview" data-supported={preview.compatibility.supported}><strong>{preview.compatibility.supported ? t('Executable preview') : t('Blocked preview')}</strong><span>{preview.dispositions.length} {t('assertions')} · {unsupportedCount} {t('unsupported')}</span>{preview.dispositions.map((item) => <small key={item.assertionId}>{item.assertionId} · {t(item.status)}{item.reasonCode ? ` · ${item.reasonCode}` : ''}</small>)}</div>}
              <div className="correctness-form-commands"><button type="button" onClick={compilePreview} disabled={mutation.tone === 'busy'}><WandSparkles size={16} />{t('Preview compile')}</button><button type="button" onClick={saveAssertions} disabled={mutation.tone === 'busy'}><Save size={16} />{t('Save Assertion Set')}</button><button type="button" className="correctness-primary-command" onClick={validateAssertions} disabled={mutation.tone === 'busy' || assertionDraft.lifecycle !== 'DRAFT' || preview?.compatibility.supported !== true}><CheckCheck size={16} />{t('Validate executable')}</button></div>
            </>}
          </section>
        </div>
        <footer className="correctness-authoring-footer"><MutationState state={mutation} /></footer>
      </section>
    </AuthoringBoundary>
  );
}

function AssertionEditor({ value, onChange, onTypeChange }: {
  value: ExecutableAssertionSpec;
  onChange(value: Partial<ExecutableAssertionSpec>): void;
  onTypeChange(value: AssertionType): void;
}) {
  const { t } = useI18n();
  const operators = operatorsFor(value.type);
  return <div className="correctness-form-grid assertion">
    <label>{t('Assertion type')}<select value={value.type} onChange={(event) => onTypeChange(event.target.value as AssertionType)}>{['OUTPUT', 'ERROR', 'NODE', 'EDGE', 'INVOCATION', 'STATE_EFFECT', 'GOVERNANCE'].map((type) => <option key={type}>{type}</option>)}</select></label>
    <label>{t('Evaluation')}<select value={value.evaluationKind} onChange={(event) => onChange({ evaluationKind: event.target.value as ExecutableAssertionSpec['evaluationKind'] })}>{value.type === 'GOVERNANCE' ? <option>GATE</option> : <><option>RUNTIME</option><option>EVIDENCE</option></>}</select></label>
    {value.type === 'OUTPUT' && <label>{t('Output path')}<input value={value.path ?? ''} onChange={(event) => onChange({ path: event.target.value })} placeholder="/decision/status" /></label>}
    {value.type === 'ERROR' && <><label>{t('Error code')}<input value={value.code ?? ''} onChange={(event) => onChange({ code: event.target.value })} /></label><label>{t('Error type')}<input value={value.errorType ?? ''} onChange={(event) => onChange({ errorType: event.target.value })} /></label></>}
    {value.type === 'NODE' && <label>{t('Node')}<input value={value.nodeId ?? ''} onChange={(event) => onChange({ nodeId: event.target.value })} /></label>}
    {value.type === 'EDGE' && <><label>{t('From node')}<input value={value.fromNodeId ?? ''} onChange={(event) => onChange({ fromNodeId: event.target.value })} /></label><label>{t('To node')}<input value={value.toNodeId ?? ''} onChange={(event) => onChange({ toNodeId: event.target.value })} /></label></>}
    {value.type === 'INVOCATION' && <label>{t('Operator ref')}<input value={value.operatorRef ?? ''} onChange={(event) => onChange({ operatorRef: event.target.value })} /></label>}
    {value.type === 'STATE_EFFECT' && <label>{t('State or effect')}<input value={value.stateOrEffect ?? ''} onChange={(event) => onChange({ stateOrEffect: event.target.value })} /></label>}
    {value.type !== 'ERROR' && <label>{t('Operator')}<select value={value.operator ?? operators[0]} onChange={(event) => onChange({ operator: event.target.value })}>{operators.map((operator) => <option key={operator}>{operator}</option>)}</select></label>}
    {value.type !== 'ERROR' && <label>{t('Expected value')}<input value={typeof value.expected === 'string' ? value.expected : JSON.stringify(value.expected ?? '')} onChange={(event) => onChange({ expected: inferPrimitive(event.target.value) })} /></label>}
  </div>;
}

function defaultAssertion(type: AssertionType, assertionId: string): ExecutableAssertionSpec {
  const base = { type, assertionId, evaluationKind: type === 'GOVERNANCE' ? 'GATE' as const : 'RUNTIME' as const };
  if (type === 'ERROR') return { ...base, code: 'EXPECTED_ERROR', errorType: '', retryable: null };
  if (type === 'OUTPUT') return { ...base, path: '/result', operator: 'EQUALS', expected: '' };
  if (type === 'NODE') return { ...base, nodeId: 'node-id', operator: 'STATUS', expected: 'SUCCESS' };
  if (type === 'EDGE') return { ...base, fromNodeId: 'from', toNodeId: 'to', operator: 'TRANSFER', expected: true };
  if (type === 'INVOCATION') return { ...base, operatorRef: 'operator-ref', operator: 'USED', expected: true };
  if (type === 'STATE_EFFECT') return { ...base, stateOrEffect: 'effect', operator: 'SIDE_EFFECT', expected: false };
  return { ...base, operator: 'EVIDENCE_EXPECTATION', expected: 'CERTIFIABLE' };
}

function operatorsFor(type: AssertionType): string[] {
  if (type === 'OUTPUT') return ['EQUALS', 'CONTAINS', 'RANGE', 'SET', 'SCHEMA', 'EXISTS', 'ABSENT'];
  if (type === 'NODE') return ['STATUS', 'SKIPPED', 'FALLBACK', 'RETRY_COUNT'];
  if (type === 'EDGE') return ['TRANSFER', 'SCHEMA', 'DATA_MINIMIZATION'];
  if (type === 'INVOCATION') return ['USED', 'NOT_USED', 'COUNT', 'INPUT_MATCH'];
  if (type === 'STATE_EFFECT') return ['STATE_TRANSITION', 'SIDE_EFFECT', 'COMPENSATION'];
  if (type === 'GOVERNANCE') return ['OWNER', 'RISK', 'BASIS', 'EVIDENCE_EXPECTATION'];
  return [];
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
