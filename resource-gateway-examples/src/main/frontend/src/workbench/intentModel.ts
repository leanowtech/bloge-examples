export type ProficiencyMode = 'GUIDED' | 'EXPERT';
export type GuidedStep = 'STEP_BASIS' | 'STEP_RULES' | 'STEP_ACTIONS';
export type DraftStatus = 'DRAFTING' | 'COMPILING' | 'COMPILED' | 'COMPILE_ERROR' | 'READY_FOR_TEST';

export interface SlotResponses {
  basis: string;
  rules: string;
  actions: string;
}

export interface IntentExpressionInput {
  sessionId: string;
  authorId: string;
  proficiencyMode: ProficiencyMode;
  domainContext: { contextFingerprint: string };
  utterance?: string;
  slotResponses?: SlotResponses;
}

export interface SafeDiagnostic {
  code: string;
  businessMessage: string;
}

export interface FourEntityDraft {
  draftId: string;
  status: 'COMPILED' | 'READY_FOR_TEST';
  features: Array<{ name: string; state: string }>;
  rules: Array<{ when: string; then: string }>;
  otherwise: string;
  instructions: string[];
  coverageGaps: string[];
  diagnostics: SafeDiagnostic[];
  contextFingerprint: string;
}

export interface IntentState {
  sessionId: string;
  authorId: string;
  mode: ProficiencyMode;
  step: GuidedStep;
  status: DraftStatus;
  slots: SlotResponses;
  utterance: string;
  contextFingerprint: string;
  prompt: string;
  diagnostics: SafeDiagnostic[];
  draft: FourEntityDraft | null;
}

export type IntentEvent =
  | { type: 'SET_MODE'; mode: ProficiencyMode }
  | { type: 'ANSWER_SLOT'; slot: keyof SlotResponses; value: string }
  | { type: 'EDIT_UTTERANCE'; value: string }
  | { type: 'NEXT_STEP' }
  | { type: 'COMPILE_STARTED'; currentContextFingerprint: string }
  | { type: 'COMPILE_SUCCEEDED'; draft: FourEntityDraft }
  | { type: 'COMPILE_FAILED'; diagnostics: SafeDiagnostic[] };

const PROMPTS: Record<GuidedStep, string> = {
  STEP_BASIS: '请说明判断依据：例如责任方、时间范围或用户选择。',
  STEP_RULES: '每种情况下应该如何决定？请同时说明其余情况如何处理。',
  STEP_ACTIONS: '可能采取哪些业务处置？哪些会写入业务系统或转人工？',
};

/** Creates the default guided authoring state without any implementation syntax. */
export function createIntentState(
  sessionId: string,
  authorId: string,
  contextFingerprint: string,
): IntentState {
  return {
    sessionId, authorId, mode: 'GUIDED', step: 'STEP_BASIS', status: 'DRAFTING',
    slots: { basis: '', rules: '', actions: '' }, utterance: '', contextFingerprint,
    prompt: PROMPTS.STEP_BASIS, diagnostics: [], draft: null,
  };
}

/** Applies one explicit UI event while retaining answers across proficiency-mode changes. */
export function intentReducer(state: IntentState, event: IntentEvent): IntentState {
  switch (event.type) {
    case 'SET_MODE':
      return { ...state, mode: event.mode, status: 'DRAFTING', diagnostics: [] };
    case 'ANSWER_SLOT':
      return { ...state, slots: { ...state.slots, [event.slot]: event.value }, status: 'DRAFTING' };
    case 'EDIT_UTTERANCE':
      return { ...state, utterance: event.value, status: 'DRAFTING' };
    case 'NEXT_STEP': {
      const next = state.step === 'STEP_BASIS' ? 'STEP_RULES'
        : state.step === 'STEP_RULES' ? 'STEP_ACTIONS' : 'STEP_ACTIONS';
      return { ...state, step: next, prompt: PROMPTS[next] };
    }
    case 'COMPILE_STARTED':
      if (event.currentContextFingerprint !== state.contextFingerprint) {
        return { ...state, status: 'COMPILE_ERROR', diagnostics: [{
          code: 'CONTEXT_DRIFT', businessMessage: '业务积木已更新，请刷新后重新检查。',
        }] };
      }
      return { ...state, status: 'COMPILING', diagnostics: [] };
    case 'COMPILE_SUCCEEDED':
      if (event.draft.contextFingerprint !== state.contextFingerprint) {
        return { ...state, status: 'COMPILE_ERROR', diagnostics: [{
          code: 'CONTEXT_DRIFT', businessMessage: '业务积木已更新，请刷新后重新检查。',
        }] };
      }
      return { ...state, status: event.draft.status, draft: event.draft,
        diagnostics: event.draft.diagnostics };
    case 'COMPILE_FAILED':
      return { ...state, status: 'COMPILE_ERROR', diagnostics: event.diagnostics };
  }
}

/** Builds the Agent-orchestration request and never includes DSL, graph, or implementation fields. */
export function toExpressionInput(state: IntentState): IntentExpressionInput {
  const base = {
    sessionId: state.sessionId,
    authorId: state.authorId,
    proficiencyMode: state.mode,
    domainContext: { contextFingerprint: state.contextFingerprint },
  };
  return state.mode === 'EXPERT'
    ? { ...base, utterance: state.utterance }
    : { ...base, slotResponses: { ...state.slots } };
}

/** Returns whether all four-entity concerns are present strongly enough to suggest expert mode. */
export function suggestsExpertMode(text: string): boolean {
  const normalized = text.trim();
  return normalized.length >= 80
    && /(依据|事实|根据)/.test(normalized)
    && /(如果|当|规则|其余)/.test(normalized)
    && /(处置|退款|维持|转人工)/.test(normalized);
}
