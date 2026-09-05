import { useReducer, useState } from 'react';

import {
  createIntentState,
  intentReducer,
  suggestsExpertMode,
  toExpressionInput,
  type FourEntityDraft,
  type IntentExpressionInput,
} from './intentModel';
import './intentWorkbench.css';

export interface IntentWorkbenchProps {
  sessionId: string;
  authorId: string;
  contextFingerprint: string;
  compile: (input: IntentExpressionInput) => Promise<FourEntityDraft>;
}

/** Business-first two-mode shell whose compiler is supplied by the connected Agent host. */
export default function IntentWorkbench({
  sessionId, authorId, contextFingerprint, compile,
}: IntentWorkbenchProps) {
  const [state, dispatch] = useReducer(intentReducer,
    createIntentState(sessionId, authorId, contextFingerprint));
  const [busy, setBusy] = useState(false);
  const slot = state.step === 'STEP_BASIS' ? 'basis'
    : state.step === 'STEP_RULES' ? 'rules' : 'actions';

  const submit = async () => {
    dispatch({ type: 'COMPILE_STARTED', currentContextFingerprint: contextFingerprint });
    setBusy(true);
    try {
      const draft = await compile(toExpressionInput(state));
      dispatch({ type: 'COMPILE_SUCCEEDED', draft });
    } catch {
      dispatch({ type: 'COMPILE_FAILED', diagnostics: [{
        code: 'AGENT_ORCHESTRATION_UNAVAILABLE',
        businessMessage: '暂时无法检查草案，请确认 Codex Agent 已连接后重试。',
      }] });
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="intent-workbench" data-testid="intent-workbench">
      <header>
        <p className="eyebrow">业务解法工作台</p>
        <h1>说清业务意图，系统负责形成可验证草案</h1>
        <p>你只需要描述判断依据、业务规则和处置结果，不需要编写技术定义。</p>
      </header>
      <div className="intent-mode-switch" role="group" aria-label="表达方式">
        <button data-testid="mode-guided" type="button" aria-pressed={state.mode === 'GUIDED'}
          onClick={() => dispatch({ type: 'SET_MODE', mode: 'GUIDED' })}>分步引导</button>
        <button data-testid="mode-expert" type="button" aria-pressed={state.mode === 'EXPERT'}
          onClick={() => dispatch({ type: 'SET_MODE', mode: 'EXPERT' })}>一次说完整</button>
      </div>
      <div className="intent-columns">
        <section className="intent-expression" aria-label="表达业务意图">
          {state.mode === 'GUIDED' ? (
            <>
              <p className="intent-step">{state.step === 'STEP_BASIS' ? '第 1 步：判断依据'
                : state.step === 'STEP_RULES' ? '第 2 步：业务规则' : '第 3 步：业务处置'}</p>
              <label>{state.prompt}
                <textarea data-testid="intent-answer" value={state.slots[slot]}
                  onChange={(event) => dispatch({ type: 'ANSWER_SLOT', slot, value: event.target.value })} />
              </label>
              {state.step !== 'STEP_ACTIONS' ? (
                <button data-testid="intent-next" type="button" disabled={!state.slots[slot].trim()}
                  onClick={() => dispatch({ type: 'NEXT_STEP' })}>继续</button>
              ) : (
                <button data-testid="intent-compile" type="button" disabled={busy || !state.slots.actions.trim()}
                  onClick={() => void submit()}>{busy ? '正在检查…' : '形成业务草案'}</button>
              )}
            </>
          ) : (
            <>
              <label>请完整描述判断依据、不同情况和对应处置。
                <textarea data-testid="expert-utterance" value={state.utterance}
                  onChange={(event) => dispatch({ type: 'EDIT_UTTERANCE', value: event.target.value })} />
              </label>
              {suggestsExpertMode(state.utterance) && <p>信息较完整，可以直接形成草案。</p>}
              <button data-testid="intent-compile" type="button" disabled={busy || !state.utterance.trim()}
                onClick={() => void submit()}>{busy ? '正在检查…' : '形成业务草案'}</button>
            </>
          )}
          {state.diagnostics.map((item) => <p className="intent-diagnostic" key={item.code}>{item.businessMessage}</p>)}
        </section>
        <DraftPreview draft={state.draft} status={state.status} />
      </div>
    </main>
  );
}

function DraftPreview({ draft, status }: { draft: FourEntityDraft | null; status: string }) {
  return (
    <section className="intent-preview" aria-label="业务草案预览">
      <div className="intent-preview-heading">
        <h2>业务草案</h2>
        <span>{status === 'READY_FOR_TEST' ? '可进入测试' : status === 'COMPILE_ERROR' ? '需要补充' : '编辑中'}</span>
      </div>
      {!draft && <p>完成左侧表达后，这里会展示判断依据、规则、处置和覆盖缺口。</p>}
      {draft && <>
        <h3>判断依据</h3>
        <ul>{draft.features.map((feature) => <li key={feature.name}>{feature.name} · {feature.state}</li>)}</ul>
        <h3>规则</h3>
        <ol>{draft.rules.map((rule) => <li key={`${rule.when}:${rule.then}`}>
          当 {rule.when}，则 {rule.then}</li>)}</ol>
        <p><strong>其余情况：</strong>{draft.otherwise}</p>
        <h3>处置</h3>
        <ul>{draft.instructions.map((instruction) => <li key={instruction}>{instruction}</li>)}</ul>
        <h3>待补充</h3>
        <p>{draft.coverageGaps.length ? draft.coverageGaps.join('；') : '没有发现覆盖缺口'}</p>
      </>}
    </section>
  );
}
