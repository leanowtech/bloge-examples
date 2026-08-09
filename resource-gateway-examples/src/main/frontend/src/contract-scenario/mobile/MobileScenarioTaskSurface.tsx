import { Plus } from 'lucide-react';

import type { AuthorCommandAvailability } from '../../author/task/taskStateProjection';
import { useI18n } from '../../i18n/I18nProvider';
import {
  type ResponsiveTaskProjection,
  type ScenarioEditorStep,
  type ScenarioTaskIntent,
} from '../../ux/responsiveTaskProjection';
import type { ScenarioDraft } from '../domain';
import type { TableCaseEvidenceProjection } from '../table/scenarioTableModel';

export function MobileScenarioTaskBar({
  projection,
  intent,
  onIntentChange,
}: {
  projection: ResponsiveTaskProjection;
  intent: ScenarioTaskIntent;
  onIntentChange: (intent: ScenarioTaskIntent) => void;
}) {
  const { t , d } = useI18n();
  return (
    <section
      className="scenario-mobile-taskbar"
      data-testid="scenario-mobile-taskbar"
      data-task-id={projection.taskId}
      data-max-primary-actions={projection.maxPrimaryActions}
    >
      <div>
        <span>{t('Mobile task')}</span>
        <strong>{d(taskLabel(projection.taskId))}</strong>
      </div>
      <div className="scenario-mobile-intent-switch" role="tablist" aria-label={t('Scenario task intent')}>
        <button
          type="button"
          role="tab"
          aria-selected={intent === 'RUNNER'}
          onClick={() => onIntentChange('RUNNER')}
        >
          {t('Run')}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={intent === 'EDITOR'}
          onClick={() => onIntentChange('EDITOR')}
        >
          {t('Build')}
        </button>
      </div>
    </section>
  );
}

export function MobileScenarioCasePicker({
  scenarios,
  selectedScenarioId,
  onSelectScenario,
  onAddScenario,
}: {
  scenarios: ScenarioDraft[];
  selectedScenarioId: string;
  onSelectScenario: (scenarioId: string) => void;
  onAddScenario: () => void;
}) {
  const { t , d } = useI18n();
  return (
    <div className="scenario-mobile-case-picker" data-testid="scenario-mobile-case-picker">
      <label>
        <span>{t('Current case')}</span>
        <select
          aria-label={t('Current case')}
          value={selectedScenarioId}
          onChange={(event) => onSelectScenario(event.target.value)}
        >
          {scenarios.map((scenario) => (
            <option value={scenario.scenarioId} key={scenario.scenarioId}>
              {scenario.name} · {d(scenario.caseType)}
            </option>
          ))}
        </select>
      </label>
      <button type="button" className="icon-button" title={t('Add Scenario')} aria-label={t('Add Scenario')} onClick={onAddScenario}>
        <Plus size={16} aria-hidden="true" />
      </button>
    </div>
  );
}

export function MobileScenarioStepNav({
  activeStep,
  inputCount,
  dependencyCount,
  assertionCount,
  onStepChange,
}: {
  activeStep: ScenarioEditorStep;
  inputCount: number;
  dependencyCount: number;
  assertionCount: number;
  onStepChange: (step: ScenarioEditorStep) => void;
}) {
  const { t , d } = useI18n();
  const steps: Array<{ step: ScenarioEditorStep; label: string; count: number | null }> = [
    { step: 'GIVEN', label: 'Input', count: inputCount },
    { step: 'DEPENDENCIES', label: 'Fixtures', count: dependencyCount },
    { step: 'THEN', label: 'Expected', count: assertionCount },
    { step: 'REVIEW', label: 'Run', count: null },
  ];
  return (
    <div className="scenario-mobile-step-nav" role="tablist" aria-label={t('Case workflow')}>
      {steps.map((entry, index) => (
        <button
          type="button"
          role="tab"
          aria-selected={activeStep === entry.step}
          aria-controls={`scenario-mobile-step-${entry.step.toLocaleLowerCase()}`}
          key={entry.step}
          onClick={() => onStepChange(entry.step)}
        >
          <b>{index + 1}</b>
          <span>{d(entry.label)}{entry.count === null ? '' : ` · ${entry.count}`}</span>
        </button>
      ))}
    </div>
  );
}

export function MobileScenarioRunSummary({
  scenario,
  inputCount,
  controlledDependencyCount,
  assertionCount,
  evidence,
  runCommand,
  onRun,
  onRunRemediation,
  onEditStep,
}: {
  scenario: ScenarioDraft;
  inputCount: number;
  controlledDependencyCount: number;
  assertionCount: number;
  evidence?: TableCaseEvidenceProjection;
  runCommand: AuthorCommandAvailability;
  onRun: () => void;
  onRunRemediation: () => void;
  onEditStep: (step: ScenarioEditorStep) => void;
}) {
  const { m, t , d } = useI18n();
  return (
    <section className="scenario-mobile-run-summary" data-testid="scenario-mobile-run-summary">
      <header>
        <div>
          <span>{d(scenario.caseType)}</span>
          <h3>{scenario.name}</h3>
        </div>
        <strong data-state={runCommand.state}>{d(runCommand.state)}</strong>
      </header>
      <dl>
        <div><dt>{t('Input fields')}</dt><dd>{inputCount}</dd></div>
        <div><dt>{t('Controlled')}</dt><dd>{controlledDependencyCount}</dd></div>
        <div><dt>{t('Checks')}</dt><dd>{assertionCount}</dd></div>
        <div><dt>{t('Last result')}</dt><dd>{d(mobileEvidenceLabel(evidence))}</dd></div>
      </dl>
      <div className="scenario-mobile-run-edits">
        <button type="button" className="secondary" onClick={() => onEditStep('GIVEN')}>{t('Edit input')}</button>
        <button type="button" className="secondary" onClick={() => onEditStep('DEPENDENCIES')}>{t('Edit fixtures')}</button>
        <button type="button" className="secondary" onClick={() => onEditStep('THEN')}>{t('Edit expected')}</button>
      </div>
      {runCommand.state === 'BLOCKED' && (
        <div className="scenario-command-explanation" id="scenario-mobile-run-blocker" role="status">
          <span>{runCommand.messageId ? m(runCommand.messageId) : d(runCommand.message)}</span>
          {runCommand.remediation && (
            <button type="button" onClick={onRunRemediation}>
              {runCommand.remediation.labelId
                ? m(runCommand.remediation.labelId)
                : d(runCommand.remediation.label)}
            </button>
          )}
        </div>
      )}
      <button
        type="button"
        className="primary scenario-mobile-run-command"
        disabled={!runCommand.enabled}
        aria-describedby={runCommand.state === 'BLOCKED' ? 'scenario-mobile-run-blocker' : undefined}
        data-command-scope="CASE"
        data-scope-count="1"
        data-scope-target={scenario.scenarioId}
        data-scope-fingerprint={runCommand.scope?.fingerprint ?? ''}
        onClick={onRun}
      >
        {runCommand.state === 'RUNNING'
          ? t('Running current case...')
          : runCommand.labelId === 'author.command.rerun'
            ? t('Rerun current case')
            : t('Run current case')}
      </button>
    </section>
  );
}

function taskLabel(taskId: ResponsiveTaskProjection['taskId']): string {
  switch (taskId) {
    case 'MATRIX_RUN': return 'Run case collection';
    case 'CASE_RUN': return 'Run one case';
    case 'CASE_EDIT': return 'Build one case';
    case 'COVERAGE_REVIEW': return 'Review coverage';
    case 'DESKTOP_FULL': return 'Full workspace';
  }
}

function mobileEvidenceLabel(evidence?: TableCaseEvidenceProjection): string {
  if (!evidence || evidence.execution === 'NOT_RUN') return 'Not run';
  if (evidence.execution === 'RUNNING' || evidence.execution === 'QUEUED') return 'Running';
  if (evidence.execution === 'SUCCESS' && evidence.assertions === 'PASSED') return 'Checks passed';
  return 'Needs review';
}
