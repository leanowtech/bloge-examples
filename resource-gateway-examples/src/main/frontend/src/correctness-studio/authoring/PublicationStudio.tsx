import { PackageCheck, RefreshCw, ScanSearch } from 'lucide-react';
import { useMemo, useState } from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import {
  fetchScenarioDraftSet,
  previewCorrectnessCompilation,
  publishCorrectness,
} from '../api/correctnessAuthoringApi';
import type {
  CorrectnessCompilationCoordinate,
  CorrectnessCompilationReport,
  StoredScenarioDraftSetV2,
} from '../model/authoring';
import type { CorrectnessWorkspaceProjection, ExactAssetRef } from '../model/domain';
import { commandId, MutationState, useExactAsset } from './shared';

export default function PublicationStudio({
  workspace,
  compilationAvailable,
  publicationAvailable,
  onPublished,
}: {
  workspace: CorrectnessWorkspaceProjection;
  compilationAvailable: boolean;
  publicationAvailable: boolean;
  onPublished(): void;
}) {
  const { t } = useI18n();
  const scenarios = useExactAsset<StoredScenarioDraftSetV2>(
    compilationAvailable,
    workspace.cases.scenarioDraftSetRef,
    fetchScenarioDraftSet,
  );
  const coordinate = useMemo(() => buildCoordinate(workspace, scenarios.value), [workspace, scenarios.value]);
  const blockers = useMemo(() => coordinateBlockers(workspace, scenarios.value, coordinate), [workspace, scenarios.value, coordinate]);
  const [preview, setPreview] = useState<CorrectnessCompilationReport | null>(null);
  const [publishedId, setPublishedId] = useState('');
  const [mutation, setMutation] = useState<{
    tone: 'idle' | 'busy' | 'success' | 'error'; message: string;
  }>({ tone: 'idle', message: '' });

  if (!compilationAvailable && !publicationAvailable) return null;

  const compile = async () => {
    if (!coordinate || blockers.length > 0) return;
    setMutation({ tone: 'busy', message: t('Compiling exact correctness closure') });
    setPublishedId('');
    try {
      const response = await previewCorrectnessCompilation(
        coordinate,
        commandId(`compile:${workspace.definition.definitionRef.id}`, workspace.definition.definitionRef.revision),
      );
      setPreview(response.data);
      setMutation(response.data.publishable
        ? { tone: 'success', message: t('Compilation is publishable') }
        : { tone: 'error', message: t('Compilation is blocked by diagnostics') });
    } catch (cause) {
      setPreview(null);
      setMutation({ tone: 'error', message: errorMessage(cause) });
    }
  };

  const publish = async () => {
    if (!coordinate || !preview?.publishable || !publicationAvailable) return;
    setMutation({ tone: 'busy', message: t('Publishing immutable correctness manifest') });
    try {
      const response = await publishCorrectness(
        coordinate,
        commandId(`publish:${preview.compilationFingerprint}`, workspace.definition.definitionRef.revision),
      );
      const id = response.data.publication.publication.publicationId;
      setPublishedId(id);
      setMutation({ tone: 'success', message: t('Correctness publication committed') });
      onPublished();
    } catch (cause) {
      setMutation({ tone: 'error', message: errorMessage(cause) });
    }
  };

  return (
    <section className="correctness-publication-studio" data-testid="publication-studio">
      <header><div><strong>{t('Publication Studio')}</strong><span>{t('Compile the exact authoring closure before any governed run.')}</span></div>{workspace.lastPublication && <small>{t('Current')}: {workspace.lastPublication.publicationRef.id}</small>}</header>
      <div className="correctness-publication-coordinate">
        <CoordinateCount label={t('Definition')} count={coordinate ? 1 : 0} />
        <CoordinateCount label={t('Coverage')} count={coordinate ? 1 : 0} />
        <CoordinateCount label={t('Cases')} count={scenarios.value?.scenarioDraftSet.scenarios.length ?? 0} />
        <CoordinateCount label={t('Oracles')} count={coordinate?.oracleRefs.length ?? 0} />
        <CoordinateCount label={t('Assertions')} count={coordinate?.assertionSetRefs.length ?? 0} />
        <CoordinateCount label={t('Fixtures')} count={coordinate?.fixtureAssetRefs.length ?? 0} />
      </div>
      {blockers.length > 0 && <div className="correctness-publication-blockers">{blockers.map((blocker) => <p key={blocker}>{t(blocker)}</p>)}</div>}
      {preview && <div className="correctness-publication-report" data-publishable={preview.publishable}>
        <div><strong>{preview.publishable ? t('Publishable') : t('Blocked')}</strong><span>{preview.compilerVersion} · {shortFingerprint(preview.compilationFingerprint)}</span></div>
        <span>{preview.compiledAssets.length} {t('compiled assets')}</span>
        <span>{preview.sourceMap.length} {t('source mappings')}</span>
        <span>{preview.riskSummary.realDependencyCount} {t('real dependencies')}</span>
        <span>{preview.riskSummary.controlledDependencyCount} {t('controlled dependencies')}</span>
        {preview.diagnostics.map((item) => <p key={`${item.code}:${item.fieldPath}`} data-severity={item.severity}>{t(item.severity)} · {item.code} · {item.fieldPath}</p>)}
      </div>}
      <footer><MutationState state={mutation} />{publishedId && <span className="correctness-publication-id"><PackageCheck size={16} />{publishedId}</span>}<button type="button" onClick={compile} disabled={!compilationAvailable || !coordinate || blockers.length > 0 || mutation.tone === 'busy'}><ScanSearch size={17} />{t('Compile preview')}</button><button type="button" className="correctness-primary-command" onClick={publish} disabled={!publicationAvailable || preview?.publishable !== true || mutation.tone === 'busy'}><PackageCheck size={17} />{t('Publish revision')}</button><button type="button" title={t('Refresh workspace')} onClick={onPublished}><RefreshCw size={17} /></button></footer>
    </section>
  );
}

function CoordinateCount({ label, count }: { label: string; count: number }) {
  return <span><strong>{count}</strong><small>{label}</small></span>;
}

function buildCoordinate(
  workspace: CorrectnessWorkspaceProjection,
  stored: StoredScenarioDraftSetV2 | null,
): CorrectnessCompilationCoordinate | null {
  if (!stored || !workspace.coverage.inventoryRef || !workspace.cases.scenarioDraftSetRef) return null;
  const cases = stored.scenarioDraftSet.scenarios;
  return {
    definitionRef: workspace.definition.definitionRef,
    inventoryRef: workspace.coverage.inventoryRef,
    scenarioDraftSetRef: workspace.cases.scenarioDraftSetRef,
    oracleRefs: uniqueRefs(cases.flatMap((item) => item.oracleRefs)),
    assertionSetRefs: uniqueRefs(cases.flatMap((item) => item.assertionSetRefs)),
    fixtureAssetRefs: uniqueRefs([
      ...workspace.fixtures.rows.map((item) => item.descriptorRef),
      ...cases.flatMap((item) => item.sourceRefs.filter((ref) => ref.kind === 'FIXTURE_ASSET')),
    ]),
    target: workspace.target,
  };
}

function coordinateBlockers(
  workspace: CorrectnessWorkspaceProjection,
  scenarios: StoredScenarioDraftSetV2 | null,
  coordinate: CorrectnessCompilationCoordinate | null,
): string[] {
  const blockers: string[] = [];
  if (!workspace.coverage.inventoryRef) blockers.push('Freeze a Coverage Inventory before compilation.');
  if (!workspace.cases.scenarioDraftSetRef || !scenarios) blockers.push('Load an exact Scenario Draft Set before compilation.');
  if (coordinate && coordinate.oracleRefs.length === 0) blockers.push('Bind at least one approved Business Oracle.');
  if (coordinate && coordinate.assertionSetRefs.length === 0) blockers.push('Bind at least one valid Assertion Set.');
  return blockers;
}

function uniqueRefs(values: ExactAssetRef[]): ExactAssetRef[] {
  const byCoordinate = new Map(values.map((item) => [
    `${item.kind}:${item.id}:${item.revision}:${item.fingerprint}`, item,
  ]));
  return [...byCoordinate.values()].sort((left, right) => (
    `${left.kind}:${left.id}:${left.revision}`.localeCompare(`${right.kind}:${right.id}:${right.revision}`)
  ));
}

function shortFingerprint(value: string): string {
  return value.length > 22 ? `${value.slice(0, 12)}...${value.slice(-7)}` : value;
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
