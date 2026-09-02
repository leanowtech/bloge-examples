import { useEffect, useMemo, useState } from 'react';
import { Play, ShieldAlert } from 'lucide-react';

import { runCallerDirectedSimulation } from './callerSimulationApi';
import {
  bindingPlan,
  buildSimulationCommandV2,
  fixtureRef,
  previewFixtureConditions,
  type ExactFixtureSubjectRefV2,
  type FixtureBindingDraft,
  type FixturePlan,
  type FixtureSelection,
  type FixtureTarget,
  type SimulationRunV2,
} from './callerSimulationModel';
import { readFixtureSet } from './flowApi';
import type { FixtureSetView } from './flowModel';
import type { FixtureSetSummary } from './model';

export interface CallerSimulationTarget {
  key: string;
  label: string;
  target: FixtureTarget;
  fixtures: FixtureSetSummary[];
}

/**
 * Unified Feed/Prove panel for API Resources, reusable DAGs, Operators and built-in Functions.
 *
 * Business input is edited independently from the Fixture Plan. The component submits only exact
 * Fixture coordinates and static targets; outputs, protected material and runtime Invocation Keys
 * are never accepted from the browser. The server remains the authority for matching and evidence.
 */
export default function CallerDirectedSimulationPanel({ subject, targets, initialInput = '{}', onRun }: {
  subject: ExactFixtureSubjectRefV2;
  targets: CallerSimulationTarget[];
  initialInput?: string;
  onRun?: (run: SimulationRunV2) => void;
}) {
  const [inputSource, setInputSource] = useState(initialInput);
  const [planKind, setPlanKind] = useState<FixturePlan['kind']>('NONE');
  const [unmatched, setUnmatched] = useState<'BLOCK' | 'REAL'>('BLOCK');
  const [choices, setChoices] = useState<Record<string, Choice>>({});
  const [fixtureViews, setFixtureViews] = useState<Record<string, FixtureSetView>>({});
  const [run, setRun] = useState<SimulationRunV2 | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const allFixtures = useMemo(() => uniqueFixtures(targets.flatMap((target) => target.fixtures)), [targets]);

  useEffect(() => setInputSource(initialInput), [initialInput]);

  useEffect(() => {
    const fixtureIds = Object.values(choices).map((choice) => choice.fixtureSetId).filter(Boolean);
    const missing = fixtureIds.filter((id) => !fixtureViews[id]);
    if (missing.length === 0) return undefined;
    let cancelled = false;
    void Promise.all(missing.map(async (fixtureSetId) => {
      const summary = allFixtures.find((fixture) => fixture.fixtureSetId === fixtureSetId);
      if (!summary) return null;
      const stored = await readFixtureSet(summary.fixtureSetId, summary.revision);
      return [fixtureSetId, stored.value] as const;
    })).then((entries) => {
      if (!cancelled && entries.some((entry) => entry !== null)) setFixtureViews((current) => ({
        ...current, ...Object.fromEntries(entries.filter((entry) => entry !== null)),
      }));
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    });
    return () => { cancelled = true; };
  }, [allFixtures, choices, fixtureViews]);

  const execute = async () => {
    setBusy(true);
    setMessage('');
    try {
      const input = JSON.parse(inputSource) as unknown;
      const fixturePlan = selectedPlan(planKind, unmatched, targets, choices, allFixtures);
      const result = await runCallerDirectedSimulation(
        buildSimulationCommandV2(subject, input, fixturePlan), operationKey(subject),
      );
      setRun(result);
      onRun?.(result);
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  return <section className="caller-simulation" data-testid="caller-simulation-panel">
    <header>
      <div><p className="eyebrow">Feed</p><h2>Caller-directed simulation</h2></div>
      <span className="object-status">v2 · zero network by default</span>
    </header>

    <div className="caller-simulation-grid">
      <section data-testid="caller-input-section">
        <h3>1. Input</h3>
        <p>Business input is independent from Fixture selection.</p>
        <textarea data-testid="caller-simulation-input" rows={8} value={inputSource}
          onChange={(event) => setInputSource(event.target.value)} />
      </section>
      <section data-testid="caller-plan-section">
        <h3>2. Fixture Plan</h3>
        <label className="object-field"><span>Plan</span><select data-testid="caller-plan-kind"
          value={planKind} onChange={(event) => setPlanKind(event.target.value as FixturePlan['kind'])}>
          <option value="NONE">None</option>
          <option value="CASE_CONTROLS">Saved Case controls</option>
          <option value="BINDINGS">Per-target bindings</option>
        </select></label>
        {planKind !== 'NONE' && <label className="object-field"><span>Unmatched target</span>
          <select data-testid="caller-unmatched" value={unmatched}
            onChange={(event) => setUnmatched(event.target.value as 'BLOCK' | 'REAL')}>
            <option value="BLOCK">Block · no external call</option>
            <option value="REAL">Real · requires explicit server authorization</option>
          </select></label>}
        {unmatched === 'REAL' && planKind !== 'NONE' && <p className="caller-real-warning" role="alert">
          <ShieldAlert aria-hidden="true" /> Real execution is requested. The server still denies all external
          writes and requires an exact read grant.
        </p>}
      </section>
    </div>

    {planKind === 'CASE_CONTROLS' && <CaseControlsEditor fixtures={allFixtures}
      choice={choices.__caseControls} onChange={(choice) => setChoices({ ...choices, __caseControls: choice })} />}
    {planKind === 'BINDINGS' && <div className="caller-binding-list" data-testid="caller-binding-list">
      {targets.map((target) => <BindingEditor key={target.key} target={target}
        choice={choices[target.key]} view={fixtureViews[choices[target.key]?.fixtureSetId ?? '']}
        input={parsePreviewInput(inputSource)}
        onChange={(choice) => setChoices({ ...choices, [target.key]: choice })} />)}
    </div>}

    <button type="button" className="primary-object-action" data-testid="run-caller-simulation"
      disabled={busy || planKind === 'CASE_CONTROLS' && allFixtures.length === 0}
      onClick={() => { void execute(); }}>
      <Play aria-hidden="true" /> {busy ? 'Running...' : 'Run Fixture Plan'}
    </button>
    {message && <p className="object-message" role="status" data-testid="caller-simulation-message">{message}</p>}
    {run && <ResolvedEvidence run={run} />}
  </section>;
}

interface Choice {
  fixtureSetId: string;
  selectionKind: FixtureSelection['kind'];
  caseId: string;
  conditionId: string;
}

function BindingEditor({ target, choice, view, input, onChange }: {
  target: CallerSimulationTarget; choice?: Choice; view?: FixtureSetView; input: unknown;
  onChange: (choice: Choice) => void;
}) {
  const selected = target.fixtures.find((fixture) => fixture.fixtureSetId === choice?.fixtureSetId);
  const next = (change: Partial<Choice>) => onChange({
    fixtureSetId: choice?.fixtureSetId ?? '', selectionKind: choice?.selectionKind ?? 'EXACT_CASE',
    caseId: choice?.caseId ?? '', conditionId: choice?.conditionId ?? '', ...change,
  });
  const conditions = view?.cases.flatMap((fixtureCase) => fixtureCase.when
    ? [{ caseId: fixtureCase.caseId, conditionId: fixtureCase.when.conditionId }] : []) ?? [];
  const preview = view ? previewFixtureConditions(view, input) : [];
  return <article className="caller-binding" data-testid={`caller-binding:${target.key}`}>
    <header><strong>{target.label}</strong><code>{targetLabel(target.target)}</code></header>
    <div className="caller-binding-fields">
      <label className="object-field"><span>Fixture Set</span><select
        data-testid={`caller-fixture:${target.key}`} value={choice?.fixtureSetId ?? ''}
        onChange={(event) => next({ fixtureSetId: event.target.value, caseId: '', conditionId: '' })}>
        <option value="">Choose an exact Fixture revision</option>
        {target.fixtures.map((fixture) => <option key={`${fixture.fixtureSetId}:${fixture.revision}`}
          value={fixture.fixtureSetId}>{fixture.displayName} · r{fixture.revision} · {fixture.status}</option>)}
      </select></label>
      <label className="object-field"><span>Selection</span><select
        data-testid={`caller-selection:${target.key}`} value={choice?.selectionKind ?? 'EXACT_CASE'}
        onChange={(event) => next({ selectionKind: event.target.value as FixtureSelection['kind'] })}>
        <option value="EXACT_CASE">Exact Case</option><option value="MATCH_CONDITION">Condition</option>
        <option value="AUTO_MATCH">Auto match</option>
      </select></label>
      {(choice?.selectionKind ?? 'EXACT_CASE') === 'EXACT_CASE' && <label className="object-field"><span>Case</span>
        <select data-testid={`caller-case:${target.key}`} value={choice?.caseId ?? ''}
          onChange={(event) => next({ caseId: event.target.value })}>
          <option value="">Choose Case</option>{selected?.cases.map((fixtureCase) =>
            <option key={fixtureCase.caseId} value={fixtureCase.caseId}>{fixtureCase.name}</option>)}
        </select></label>}
      {choice?.selectionKind === 'MATCH_CONDITION' && <label className="object-field"><span>Condition</span>
        <select data-testid={`caller-condition:${target.key}`} value={choice.conditionId}
          onChange={(event) => next({ conditionId: event.target.value })}>
          <option value="">Choose stable condition</option>{conditions.map((condition) =>
            <option key={condition.conditionId} value={condition.conditionId}>{condition.conditionId}</option>)}
        </select></label>}
    </div>
    {(choice?.selectionKind === 'MATCH_CONDITION' || choice?.selectionKind === 'AUTO_MATCH') && <p
      className="caller-match-preview" data-testid={`caller-match-preview:${target.key}`}>
      Match preview: {preview.length === 0 ? 'No saved conditions'
        : preview.map((item) => `${item.conditionId}=${item.matched ? 'match' : 'no match'}`).join(' · ')}
    </p>}
  </article>;
}

function CaseControlsEditor({ fixtures, choice, onChange }: {
  fixtures: FixtureSetSummary[]; choice?: Choice; onChange: (choice: Choice) => void;
}) {
  const selected = fixtures.find((fixture) => fixture.fixtureSetId === choice?.fixtureSetId);
  return <article className="caller-binding" data-testid="caller-case-controls">
    <header><strong>Saved reusable plan</strong><code>CASE_CONTROLS</code></header>
    <div className="caller-binding-fields">
      <label className="object-field"><span>Fixture Set</span><select data-testid="caller-controls-fixture"
        value={choice?.fixtureSetId ?? ''} onChange={(event) => onChange({
          fixtureSetId: event.target.value, selectionKind: 'EXACT_CASE', caseId: '', conditionId: '',
        })}>
        <option value="">Choose Fixture Set</option>{fixtures.map((fixture) => <option
          key={`${fixture.fixtureSetId}:${fixture.revision}`} value={fixture.fixtureSetId}>
          {fixture.displayName} · r{fixture.revision}</option>)}
      </select></label>
      <label className="object-field"><span>Case</span><select data-testid="caller-controls-case"
        value={choice?.caseId ?? ''} onChange={(event) => onChange({
          fixtureSetId: choice?.fixtureSetId ?? '', selectionKind: 'EXACT_CASE',
          caseId: event.target.value, conditionId: '',
        })}>
        <option value="">Choose Case controls</option>{selected?.cases.map((fixtureCase) =>
          <option key={fixtureCase.caseId} value={fixtureCase.caseId}>{fixtureCase.name}</option>)}
      </select></label>
    </div>
  </article>;
}

function ResolvedEvidence({ run }: { run: SimulationRunV2 }) {
  return <section className="resolved-evidence" data-testid="resolved-simulation-evidence">
    <header><div><p className="eyebrow">Prove</p><h3>Resolved Evidence</h3></div>
      <strong>{run.status}</strong></header>
    <div className="simulation-summary">
      <div><span>Execution</span><strong>{run.verdicts.execution}</strong></div>
      <div><span>Assertions</span><strong>{run.verdicts.assertions}</strong></div>
      <div><span>Contract</span><strong>{run.verdicts.contract}</strong></div>
      <div><span>Governance</span><strong>{run.verdicts.governance}</strong></div>
      <div><span>Aggregate</span><strong>{run.verdicts.aggregate}</strong></div>
    </div>
    <pre data-testid="caller-simulation-output">{JSON.stringify(run.output ?? null, null, 2)}</pre>
    <ol>{run.invocations.map((invocation) => <li key={invocation.invocationKey}
      data-testid={`caller-invocation:${invocation.invocationKey}`}>
      <strong>{targetLabel(invocation.target)}</strong> · {invocation.status} · {invocation.execution}
      {' · '}{invocation.matchedBy}{invocation.fixtureCase
        ? ` · ${invocation.fixtureCase.fixtureSetId}@${invocation.fixtureCase.revision}/${invocation.fixtureCase.caseId}`
        : ''}{' · '}{invocation.egress.decision} · {invocation.egress.attempted ? 'EGRESS' : 'NO_EGRESS'}
    </li>)}</ol>
  </section>;
}

function selectedPlan(kind: FixturePlan['kind'], unmatched: 'BLOCK' | 'REAL',
  targets: CallerSimulationTarget[], choices: Record<string, Choice>, fixtures: FixtureSetSummary[]): FixturePlan {
  if (kind === 'NONE') return { kind: 'NONE' };
  if (kind === 'CASE_CONTROLS') {
    const choice = choices.__caseControls;
    const fixture = fixtures.find((candidate) => candidate.fixtureSetId === choice?.fixtureSetId);
    if (!fixture || !choice?.caseId) throw new Error('Choose one saved Fixture Case plan.');
    return { kind, fixtureSet: fixtureRef(fixture), caseId: choice.caseId, unmatched };
  }
  const drafts: FixtureBindingDraft[] = targets.flatMap((target) => {
    const choice = choices[target.key];
    const fixture = target.fixtures.find((candidate) => candidate.fixtureSetId === choice?.fixtureSetId);
    if (!choice || !fixture) return [];
    return [{ target: target.target, fixture, selectionKind: choice.selectionKind,
      caseId: choice.caseId, conditionId: choice.conditionId }];
  });
  if (drafts.length === 0) throw new Error('Add at least one Fixture binding.');
  return bindingPlan(unmatched, drafts);
}

function uniqueFixtures(fixtures: FixtureSetSummary[]): FixtureSetSummary[] {
  return [...new Map(fixtures.map((fixture) =>
    [`${fixture.fixtureSetId}:${fixture.revision}:${fixture.fingerprint}`, fixture])).values()];
}

function targetLabel(target: FixtureTarget): string {
  if (target.kind === 'SUBJECT') return 'Subject';
  const path = target.nodePath.join(' / ');
  return target.kind === 'NODE_PATH' ? path : `${path} / call:${target.callSiteId}`;
}

function parsePreviewInput(source: string): unknown {
  try { return JSON.parse(source) as unknown; } catch { return null; }
}

function operationKey(subject: ExactFixtureSubjectRefV2): string {
  const nonce = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `caller-simulation:${subject.kind}:${nonce}`;
}

function errorMessage(failure: unknown): string {
  if (failure instanceof SyntaxError) return 'Input must be valid JSON.';
  return failure instanceof Error ? failure.message : 'The Simulation did not complete.';
}
