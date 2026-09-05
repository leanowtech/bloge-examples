import { describe, expect, it } from 'vitest';

import { createIntentState, intentReducer, toExpressionInput } from './intentModel';

describe('business intent state machine', () => {
  it('guides basis, rules and actions while retaining answers across mode switches', () => {
    let state = createIntentState('session-1', 'owner-1', 'sha256:context');
    expect(state.mode).toBe('GUIDED');
    expect(state.step).toBe('STEP_BASIS');
    expect(state.prompt).toContain('判断依据');

    state = intentReducer(state, { type: 'ANSWER_SLOT', slot: 'basis', value: '责任方、免费取消时段和争议订单' });
    state = intentReducer(state, { type: 'NEXT_STEP' });
    expect(state.step).toBe('STEP_RULES');
    state = intentReducer(state, { type: 'ANSWER_SLOT', slot: 'rules', value: '平台无责且在免费时段全额免除，其余转人工' });
    state = intentReducer(state, { type: 'SET_MODE', mode: 'EXPERT' });
    state = intentReducer(state, { type: 'SET_MODE', mode: 'GUIDED' });

    expect(state.slots.basis).toContain('责任方');
    expect(state.slots.rules).toContain('全额免除');
    expect(state.step).toBe('STEP_RULES');
  });

  it('fails closed when the authoring context drifts during compilation', () => {
    let state = createIntentState('session-1', 'owner-1', 'sha256:old');
    state = intentReducer(state, { type: 'ANSWER_SLOT', slot: 'basis', value: '责任方' });
    state = intentReducer(state, { type: 'ANSWER_SLOT', slot: 'rules', value: '其余转人工' });
    state = intentReducer(state, { type: 'ANSWER_SLOT', slot: 'actions', value: '退款、维持收费、转人工' });
    state = intentReducer(state, { type: 'COMPILE_STARTED', currentContextFingerprint: 'sha256:new' });

    expect(state.status).toBe('COMPILE_ERROR');
    expect(state.diagnostics).toEqual([{ code: 'CONTEXT_DRIFT', businessMessage: '业务积木已更新，请刷新后重新检查。' }]);
  });

  it('builds the exact guided or expert expression input without DSL fields', () => {
    let guided = createIntentState('session-1', 'owner-1', 'sha256:context');
    guided = intentReducer(guided, { type: 'ANSWER_SLOT', slot: 'basis', value: '责任方' });
    guided = intentReducer(guided, { type: 'ANSWER_SLOT', slot: 'rules', value: '其余转人工' });
    guided = intentReducer(guided, { type: 'ANSWER_SLOT', slot: 'actions', value: '退款或转人工' });
    expect(toExpressionInput(guided)).toEqual(expect.objectContaining({
      proficiencyMode: 'GUIDED', slotResponses: guided.slots,
      domainContext: { contextFingerprint: 'sha256:context' },
    }));
    expect(JSON.stringify(toExpressionInput(guided)).toLowerCase()).not.toContain('dsl');

    let expert = intentReducer(guided, { type: 'SET_MODE', mode: 'EXPERT' });
    expert = intentReducer(expert, { type: 'EDIT_UTTERANCE', value: '请按责任方和免费时段决定退款或转人工。' });
    expect(toExpressionInput(expert)).toEqual(expect.objectContaining({
      proficiencyMode: 'EXPERT', utterance: '请按责任方和免费时段决定退款或转人工。',
    }));
  });
});
