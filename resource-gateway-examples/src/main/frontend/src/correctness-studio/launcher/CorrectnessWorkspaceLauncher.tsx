import { type FormEvent, useCallback, useEffect, useState } from 'react';
import {
  AlertTriangle,
  BadgeCheck,
  BookOpenCheck,
  LoaderCircle,
  Search,
  SlidersHorizontal,
} from 'lucide-react';

import { useI18n } from '../../i18n/I18nProvider';
import AsyncReferenceCombobox, {
  type AsyncReferenceComboboxLabels,
} from '../../shared/reference-picker/AsyncReferenceCombobox';
import type {
  ReferenceCandidate,
  ReferenceCandidateSearch,
  ReferenceErrorStatus,
} from '../../shared/reference-picker/types';
import type {
  CorrectnessDeploymentCapabilities,
  CorrectnessTargetKind,
  CorrectnessWorkspaceCoordinate,
} from '../model/domain';

type DefinitionState = 'idle' | 'loading' | 'unique' | 'multiple' | 'empty'
  | ReferenceErrorStatus;

export interface CorrectnessWorkspaceLauncherProps {
  deployment: CorrectnessDeploymentCapabilities;
  searchTargets: (
    kind: CorrectnessTargetKind,
    ...parameters: Parameters<ReferenceCandidateSearch>
  ) => ReturnType<ReferenceCandidateSearch>;
  searchDefinitions: (
    target: ReferenceCandidate,
    ...parameters: Parameters<ReferenceCandidateSearch>
  ) => ReturnType<ReferenceCandidateSearch>;
  onOpen(value: CorrectnessWorkspaceCoordinate): void;
  pickerDebounceMs?: number;
}

const TARGET_KINDS: CorrectnessTargetKind[] = ['GRAPH', 'OPERATOR', 'FUNCTION'];

export default function CorrectnessWorkspaceLauncher({
  deployment,
  searchTargets,
  searchDefinitions,
  onOpen,
  pickerDebounceMs = 250,
}: CorrectnessWorkspaceLauncherProps) {
  const { t } = useI18n();
  const catalogAvailable = deployment.features.correctnessTargetCatalogApi === true
    && deployment.features.guidedWorkspaceLauncher === true;
  const [targetKind, setTargetKind] = useState<CorrectnessTargetKind>('GRAPH');
  const [target, setTarget] = useState<ReferenceCandidate | null>(null);
  const [definition, setDefinition] = useState<ReferenceCandidate | null>(null);
  const [definitionState, setDefinitionState] = useState<DefinitionState>('idle');
  const [definitionCount, setDefinitionCount] = useState(0);
  const [probeEpoch, setProbeEpoch] = useState(0);

  const loadTargets = useCallback<ReferenceCandidateSearch>(
    (request, signal) => searchTargets(targetKind, request, signal),
    [searchTargets, targetKind],
  );
  const loadDefinitions = useCallback<ReferenceCandidateSearch>(
    (request, signal) => {
      if (!target) return Promise.reject(new Error('Select a target before loading definitions.'));
      return searchDefinitions(target, request, signal);
    },
    [searchDefinitions, target],
  );

  useEffect(() => {
    setDefinition(null);
    setDefinitionCount(0);
    if (!target || !catalogAvailable) {
      setDefinitionState('idle');
      return undefined;
    }
    const controller = new AbortController();
    setDefinitionState('loading');
    searchDefinitions(target, { query: '', cursor: null, limit: 20 }, controller.signal)
      .then((page) => {
        if (controller.signal.aborted) return;
        const selectable = page.items.filter(isCandidateSelectable);
        setDefinitionCount(selectable.length);
        if (selectable.length === 0) {
          setDefinitionState('empty');
        } else if (selectable.length === 1) {
          setDefinition(selectable[0]);
          setDefinitionState('unique');
        } else {
          setDefinitionState('multiple');
        }
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted) return;
        setDefinitionState(referenceFailureStatus(failure));
      });
    return () => controller.abort();
  }, [catalogAvailable, probeEpoch, searchDefinitions, target]);

  const changeTargetKind = (kind: CorrectnessTargetKind) => {
    setTargetKind(kind);
    setTarget(null);
    setDefinition(null);
    setDefinitionState('idle');
  };
  const openWorkspace = () => {
    if (!target || !definition) return;
    onOpen({
      targetKind,
      targetId: target.id,
      targetFingerprint: target.fingerprint,
      definitionId: definition.id,
      caseLimit: 100,
    });
  };
  const pickerLabels = referencePickerLabels(t);

  return (
    <main className="correctness-connect">
      <header className="correctness-connect-heading">
        <span><BookOpenCheck aria-hidden="true" size={22} /></span>
        <div>
          <p className="eyebrow">{t('GUIDED WORKSPACE')}</p>
          <h2>{t('Open a correctness workspace')}</h2>
        </div>
      </header>
      <p>{t('Choose the business asset to verify. Its exact revision and correctness definition are bound for you.')}</p>

      {catalogAvailable ? (
        <div className="correctness-launcher-flow">
          <section className="correctness-launcher-step" aria-labelledby="correctness-target-step">
            <div className="correctness-launcher-step-heading">
              <span>1</span>
              <div>
                <h3 id="correctness-target-step">{t('Choose a business target')}</h3>
                <p>{t('Search by business name, owner, ID, or scope.')}</p>
              </div>
            </div>
            <div className="correctness-target-kind" role="group" aria-label={t('Target kind')}>
              {TARGET_KINDS.map((kind) => (
                <button
                  aria-pressed={targetKind === kind}
                  key={kind}
                  onClick={() => changeTargetKind(kind)}
                  type="button"
                >
                  {t(targetKindLabel(kind))}
                </button>
              ))}
            </div>
            <AsyncReferenceCombobox
              debounceMs={pickerDebounceMs}
              id="correctness-target-picker"
              labels={{
                ...pickerLabels,
                inputLabel: t('Business target'),
                placeholder: t('Search business targets'),
                loading: t('Loading business targets...'),
                empty: t('No matching business targets.'),
              }}
              loadCandidates={loadTargets}
              minQueryLength={0}
              onChange={setTarget}
              value={target}
            />
          </section>

          <section
            className="correctness-launcher-step"
            aria-labelledby="correctness-definition-step"
            data-state={definitionState}
          >
            <div className="correctness-launcher-step-heading">
              <span>2</span>
              <div>
                <h3 id="correctness-definition-step">{t('Bind the correctness definition')}</h3>
                <p>{t('The definition determines the business truth, coverage denominator, and test assets.')}</p>
              </div>
            </div>
            {!target && <LauncherNotice text={t('Choose a business target first.')} />}
            {definitionState === 'loading' && (
              <LauncherNotice icon={<LoaderCircle className="spin" size={18} />} text={t('Finding correctness definitions...')} />
            )}
            {definitionState === 'unique' && definition && (
              <div className="correctness-definition-resolution" role="status">
                <BadgeCheck aria-hidden="true" size={20} />
                <span>
                  <small>{t('Automatically selected')}</small>
                  <strong>{definition.displayName}</strong>
                  <em>{definition.owner?.displayName ?? t('No owner')} · r{definition.revision}</em>
                </span>
              </div>
            )}
            {definitionState === 'multiple' && (
              <>
                <p className="correctness-definition-choice">
                  {t('{count} definitions match this exact target. Choose the business authority to open.', { count: definitionCount })}
                </p>
                <AsyncReferenceCombobox
                  debounceMs={pickerDebounceMs}
                  id="correctness-definition-picker"
                  labels={{
                    ...pickerLabels,
                    inputLabel: t('Correctness definition'),
                    placeholder: t('Search correctness definitions'),
                    loading: t('Loading correctness definitions...'),
                    empty: t('No matching correctness definitions.'),
                  }}
                  loadCandidates={loadDefinitions}
                  minQueryLength={0}
                  onChange={setDefinition}
                  value={definition}
                />
              </>
            )}
            {definitionState === 'empty' && (
              <LauncherNotice
                icon={<AlertTriangle size={18} />}
                text={t('This target has no correctness definition yet. Create one before opening the workspace.')}
                tone="warning"
              />
            )}
            {(definitionState === 'error' || definitionState === 'unavailable') && (
              <LauncherNotice
                action={{ label: t('Retry'), onClick: () => setProbeEpoch((value) => value + 1) }}
                icon={<AlertTriangle size={18} />}
                text={definitionState === 'unavailable'
                  ? t('The correctness definition directory is unavailable. Try again or use advanced exact coordinates.')
                  : t('Correctness definitions could not be loaded. Try again or use advanced exact coordinates.')}
                tone="warning"
              />
            )}
          </section>

          <button
            className="correctness-primary-command correctness-launcher-open"
            disabled={!target || !definition}
            onClick={openWorkspace}
            type="button"
          >
            <Search aria-hidden="true" size={18} />{t('Open correctness workspace')}
          </button>
        </div>
      ) : (
        <LauncherNotice
          icon={<AlertTriangle size={18} />}
          text={t('This deployment does not enable guided target selection. Use advanced exact coordinates or enable the launcher capability.')}
          tone="warning"
        />
      )}

      <details className="correctness-exact-coordinate-panel">
        <summary><SlidersHorizontal aria-hidden="true" size={17} />{t('Advanced exact coordinates')}</summary>
        <p>{t('Use exact IDs only for deep-link recovery or protocol troubleshooting.')}</p>
        <AdvancedExactCoordinateForm initialKind={targetKind} onOpen={onOpen} />
      </details>
    </main>
  );
}

function AdvancedExactCoordinateForm({
  initialKind,
  onOpen,
}: {
  initialKind: CorrectnessTargetKind;
  onOpen(value: CorrectnessWorkspaceCoordinate): void;
}) {
  const { t } = useI18n();
  const [targetKind, setTargetKind] = useState<CorrectnessTargetKind>(initialKind);
  const [targetId, setTargetId] = useState('');
  const [targetFingerprint, setTargetFingerprint] = useState('');
  const [definitionId, setDefinitionId] = useState('');
  useEffect(() => setTargetKind(initialKind), [initialKind]);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!targetId.trim() || !targetFingerprint.trim()) return;
    onOpen({
      targetKind,
      targetId: targetId.trim(),
      targetFingerprint: targetFingerprint.trim(),
      definitionId: definitionId.trim() || undefined,
      caseLimit: 100,
    });
  };
  return (
    <form className="correctness-exact-coordinate-form" onSubmit={submit}>
      <label>{t('Target kind')}
        <select value={targetKind} onChange={(event) => setTargetKind(event.target.value as CorrectnessTargetKind)}>
          {TARGET_KINDS.map((kind) => <option key={kind} value={kind}>{t(targetKindLabel(kind))}</option>)}
        </select>
      </label>
      <label>{t('Target ID')}<input required value={targetId} onChange={(event) => setTargetId(event.target.value)} placeholder="loan-decision" /></label>
      <label>{t('Target fingerprint')}<input required value={targetFingerprint} onChange={(event) => setTargetFingerprint(event.target.value)} placeholder="sha256:..." /></label>
      <label>{t('Definition ID')} <span>{t('optional')}</span><input value={definitionId} onChange={(event) => setDefinitionId(event.target.value)} placeholder="correctness-loan-decision" /></label>
      <button type="submit" disabled={!targetId.trim() || !targetFingerprint.trim()}>
        <Search aria-hidden="true" size={18} />{t('Open with exact coordinates')}
      </button>
    </form>
  );
}

function LauncherNotice({
  action,
  icon,
  text,
  tone = 'neutral',
}: {
  action?: { label: string; onClick(): void };
  icon?: React.ReactNode;
  text: string;
  tone?: 'neutral' | 'warning';
}) {
  return (
    <div className="correctness-launcher-notice" data-tone={tone} role={tone === 'warning' ? 'alert' : 'status'}>
      {icon}<span>{text}</span>
      {action && <button type="button" onClick={action.onClick}>{action.label}</button>}
    </div>
  );
}

function referencePickerLabels(t: (key: string) => string): AsyncReferenceComboboxLabels {
  return {
    inputLabel: t('Search references'),
    placeholder: t('Search by name, ID, owner, or scope'),
    loading: t('Loading references...'),
    empty: t('No matching references.'),
    error: t('References could not be loaded.'),
    unavailable: t('The reference directory is unavailable.'),
    retry: t('Retry'),
    loadMore: t('Load more'),
    loadingMore: t('Loading more...'),
    selected: t('Selected reference'),
    exactReference: t('Exact reference'),
    disabled: t('Unavailable for selection'),
  };
}

function targetKindLabel(kind: CorrectnessTargetKind): string {
  if (kind === 'GRAPH') return 'Graph';
  if (kind === 'OPERATOR') return 'Operator';
  return 'Function';
}

function isCandidateSelectable(candidate: ReferenceCandidate): boolean {
  return !candidate.disabledReasonCode && candidate.compatibility !== 'INCOMPATIBLE';
}

function referenceFailureStatus(failure: unknown): ReferenceErrorStatus {
  return typeof failure === 'object' && failure !== null && 'status' in failure
    && (failure as { status?: unknown }).status === 'unavailable'
    ? 'unavailable'
    : 'error';
}
