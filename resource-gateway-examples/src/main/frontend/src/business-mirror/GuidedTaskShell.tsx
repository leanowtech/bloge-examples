import { ArrowRight, Check, CircleAlert, ListChecks, Target } from 'lucide-react';
import type { ReactNode } from 'react';

import { useI18n } from '../i18n/I18nProvider';
import type { MessageId } from '../i18n/messageCatalog';
import type { BusinessMirrorGap, BusinessMirrorTaskId } from './domain';
import {
  BUSINESS_MIRROR_TASK_ORDER,
  remediationDescriptorForGap,
  type StepContract,
} from './guidance';

const TASK_LABELS: Record<BusinessMirrorTaskId, MessageId> = {
  problem: 'businessMirror.task.problem',
  boundary: 'businessMirror.task.boundary',
  capabilities: 'businessMirror.task.capabilities',
  scenarios: 'businessMirror.task.scenarios',
  rehearsal: 'businessMirror.task.rehearsal',
  evidence: 'businessMirror.task.evidence',
  calibrate: 'businessMirror.task.calibrate',
};

const INPUT_LABELS: Record<string, MessageId> = {
  domain: 'businessMirror.field.domain',
  taxonomy: 'businessMirror.problem.taxonomy',
  problemCode: 'businessMirror.field.problemCode',
  businessGoal: 'businessMirror.field.goal',
  expectedOutcome: 'businessMirror.field.outcome',
  accountableOwner: 'businessMirror.field.owner',
  contract: 'businessMirror.boundary.contract',
  state: 'businessMirror.boundary.state',
  effect: 'businessMirror.boundary.effect',
  ownerConfirmation: 'businessMirror.guidance.input.ownerConfirmation',
  executable: 'businessMirror.guidance.input.executable',
  solution: 'businessMirror.capability.l1',
  carrier: 'businessMirror.capability.l2',
  channel: 'businessMirror.capability.l3',
  scenarioInventory: 'businessMirror.scenario.inventory',
  scenarioPack: 'businessMirror.scenario.pack',
  discoveredSuiteDisposition: 'businessMirror.guidance.input.discoveredSuiteDisposition',
  mirrorPlan: 'businessMirror.guidance.input.mirrorPlan',
  evidencePortfolio: 'businessMirror.guidance.input.evidencePortfolio',
  ownerTasks: 'businessMirror.evidence.ownerTasks',
  fidelity: 'businessMirror.calibrate.fidelity',
  outcome: 'businessMirror.calibrate.outcome',
  approval: 'businessMirror.calibrate.approval',
};

interface GuidedTaskShellProps {
  contract: StepContract;
  gaps: BusinessMirrorGap[];
  progress: 'BLOCKED' | 'REVIEW' | 'COMPLETE';
  readOnly: boolean;
  inputStates: Record<string, GuidedInputState>;
  onRemediate(gap: BusinessMirrorGap): void;
  onTask(task: BusinessMirrorTaskId): void;
  children: ReactNode;
}

export type GuidedInputState = 'READY' | 'MISSING' | 'REVIEW';

export default function GuidedTaskShell({
  contract,
  gaps,
  progress,
  readOnly,
  inputStates,
  onRemediate,
  onTask,
  children,
}: GuidedTaskShellProps) {
  const { m } = useI18n();
  const taskGaps = gaps.filter((gap) => remediationDescriptorForGap(gap).taskId === contract.id);
  const primaryGap = taskGaps.find((gap) => gap.severity === 'BLOCKING') ?? taskGaps[0] ?? null;
  const primaryDescriptor = primaryGap ? remediationDescriptorForGap(primaryGap) : null;
  const stepNumber = BUSINESS_MIRROR_TASK_ORDER.indexOf(contract.id) + 1;
  const nextTask = contract.nextStep;
  const progressMessage: MessageId = progress === 'BLOCKED'
    ? 'businessMirror.task.blocked'
    : progress === 'REVIEW'
      ? 'businessMirror.task.review'
      : 'businessMirror.task.complete';
  const nextInput = primaryDescriptor
    ? contract.inputs.find((input) => input.fieldPath === primaryDescriptor.fieldPath)
    : null;
  const nextInputLabel = nextInput ? INPUT_LABELS[nextInput.id] : undefined;
  const blockingInputIds = new Set(contract.inputs
    .filter((input) => taskGaps.some((gap) => (
      remediationDescriptorForGap(gap).fieldPath === input.fieldPath
    )))
    .map((input) => input.id));
  const resolvedInputStates = Object.fromEntries(contract.inputs.map((input) => [
    input.id,
    blockingInputIds.has(input.id) ? 'MISSING' : (inputStates[input.id] ?? 'REVIEW'),
  ])) as Record<string, GuidedInputState>;
  const readyInputCount = Object.values(resolvedInputStates)
    .filter((state) => state === 'READY').length;

  return (
    <div className="business-mirror-guided-task" data-guided-task={contract.id}>
      <header className="business-mirror-guided-header">
        <div className="guided-header-meta">
          <span>{m('businessMirror.guidance.step', {
            current: stepNumber,
            total: BUSINESS_MIRROR_TASK_ORDER.length,
          })}</span>
          <span className={`guided-status ${progress.toLowerCase()}`}>{m(progressMessage)}</span>
          {readOnly && <span className="guided-read-only">{m('businessMirror.guidance.readOnly')}</span>}
        </div>
        <p className="guided-task-label">{m(TASK_LABELS[contract.id])}</p>
        <h3>{m(contract.question as MessageId)}</h3>
        <p>{m(contract.why as MessageId)}</p>
      </header>

      <details className="business-mirror-guided-inputs" open>
        <summary>
          <ListChecks aria-hidden="true" size={17} />
          <span>{m('businessMirror.guidance.inputs')}</span>
          <strong>{m('businessMirror.guidance.inputProgress', {
            ready: readyInputCount,
            total: contract.inputs.length,
          })}</strong>
        </summary>
        <p>{m('businessMirror.guidance.authorityNote')}</p>
        <ul>
          {contract.inputs.map((input) => {
            const state = resolvedInputStates[input.id];
            const missing = state === 'MISSING';
            return (
              <li key={input.id} className={state.toLowerCase()}>
                {state === 'READY'
                  ? <Check aria-hidden="true" size={15} />
                  : missing
                  ? <CircleAlert aria-hidden="true" size={15} />
                  : <Target aria-hidden="true" size={15} />}
                <span>{m(INPUT_LABELS[input.id] ?? 'businessMirror.guidance.input.unknown')}</span>
                <small>{m(state === 'READY'
                  ? 'businessMirror.guidance.inputReady'
                  : blockingInputIds.has(input.id)
                    ? 'businessMirror.guidance.inputNeedsAction'
                    : state === 'MISSING'
                      ? 'businessMirror.guidance.inputNotBound'
                      : 'businessMirror.guidance.inputReview')}</small>
              </li>
            );
          })}
        </ul>
      </details>

      {primaryGap && primaryDescriptor && (
        <section className="business-mirror-next-action" aria-labelledby="business-mirror-next-action-title">
          <Target aria-hidden="true" size={19} />
          <span>
            <small>{m('businessMirror.guidance.nextBestAction')}</small>
            <strong id="business-mirror-next-action-title">
              {m('businessMirror.guidance.completeInput', {
                input: nextInputLabel ? m(nextInputLabel) : primaryGap.code,
              })}
            </strong>
            <span>{m('businessMirror.guidance.blockingImpact')}</span>
          </span>
          <button type="button" onClick={() => onRemediate(primaryGap)}>
            <Target aria-hidden="true" size={16} />
            {m('businessMirror.guidance.openControl')}
          </button>
          <details>
            <summary>{m('businessMirror.guidance.technicalDetail')}</summary>
            <code>{primaryGap.code}</code>
            <code>{primaryDescriptor.fieldPath}</code>
          </details>
        </section>
      )}

      <div className="business-mirror-guided-content">{children}</div>

      <footer className="business-mirror-guided-footer">
        <span>
          <strong>{m('businessMirror.guidance.completion')}</strong>
          <small>{m(primaryGap
            ? 'businessMirror.guidance.completionBlocked'
            : progress === 'REVIEW'
              ? 'businessMirror.guidance.completionReview'
              : 'businessMirror.guidance.completionReady')}</small>
        </span>
        {primaryGap ? (
          <button type="button" className="primary" onClick={() => onRemediate(primaryGap)}>
            {m('businessMirror.guidance.continueStep')}
            <ArrowRight aria-hidden="true" size={16} />
          </button>
        ) : nextTask ? (
          <button type="button" className="primary" onClick={() => onTask(nextTask)}>
            {m('businessMirror.guidance.nextStep', { step: m(TASK_LABELS[nextTask]) })}
            <ArrowRight aria-hidden="true" size={16} />
          </button>
        ) : null}
      </footer>
    </div>
  );
}
