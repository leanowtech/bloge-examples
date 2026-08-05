import { useEffect, useMemo, useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider';

import type { GraphDraft } from '../../types';
import type { ContractDraft, ScenarioDraftSet } from '../domain';
import type { ScenarioTableEvidenceByCase } from '../table/scenarioTableModel';
import {
  buildCoverageProjection,
  coverageCandidateIsCurrent,
  generateCoverageCandidates,
  type CoverageCandidate,
  type CoverageCandidateSet,
  type CoverageDimensionId,
  type CoverageFact,
  type CoverageProjection,
} from './coverageModel';

interface CoverageLensSurfaceProps {
  graphDraft: GraphDraft;
  contract: ContractDraft;
  draftSet: ScenarioDraftSet;
  evidenceByCase: ScenarioTableEvidenceByCase;
  disabled?: boolean;
  onAcceptCandidate: (candidate: CoverageCandidate, projection: CoverageProjection) => void;
}

const DIMENSION_LABELS: Record<CoverageDimensionId, string> = {
  CASE: 'Case intent',
  CONTRACT: 'Contract',
  DAG: 'DAG path',
  DEPENDENCY: 'Dependency',
  ASSERTION: 'Assertion',
  EVIDENCE: 'Evidence',
};

const ACTION_LABELS: Record<CoverageFact['action'], string> = {
  GENERATE: 'Generate',
  AUTHOR_CASE: 'Author case',
  AUTHOR_ASSERTION: 'Add oracle',
  RUN: 'Run',
  INSTALL_GENERATOR: 'Install adapter',
};

export default function CoverageLensSurface({
  graphDraft,
  contract,
  draftSet,
  evidenceByCase,
  disabled = false,
  onAcceptCandidate,
}: CoverageLensSurfaceProps) {
  const { t } = useI18n();
  const [projection, setProjection] = useState<CoverageProjection | null>(null);
  const [projectionError, setProjectionError] = useState('');
  const [activeDimension, setActiveDimension] = useState<CoverageDimensionId>('CONTRACT');
  const [focusedFactId, setFocusedFactId] = useState('');
  const [seed, setSeed] = useState(42);
  const [maxCandidates, setMaxCandidates] = useState(20);
  const [maxWorkUnits, setMaxWorkUnits] = useState(50);
  const [candidateSet, setCandidateSet] = useState<CoverageCandidateSet | null>(null);
  const [generating, setGenerating] = useState(false);
  const [generationError, setGenerationError] = useState('');
  const [rejectedCandidateIds, setRejectedCandidateIds] = useState<Set<string>>(new Set());
  const [acceptedCandidateIds, setAcceptedCandidateIds] = useState<Set<string>>(new Set());

  useEffect(() => {
    let cancelled = false;
    setProjectionError('');
    void buildCoverageProjection(graphDraft, contract, draftSet, evidenceByCase)
      .then((next) => {
        if (!cancelled) setProjection(next);
      })
      .catch((error: unknown) => {
        if (!cancelled) setProjectionError(error instanceof Error ? error.message : t('Coverage projection failed.'));
      });
    return () => {
      cancelled = true;
    };
  }, [contract, draftSet, evidenceByCase, graphDraft, t]);

  const dimension = projection?.dimensions.find((entry) => entry.dimension === activeDimension) ?? null;
  const focusedFact = useMemo(() => projection?.dimensions
    .flatMap((entry) => entry.gaps)
    .find((fact) => fact.factId === focusedFactId) ?? null, [focusedFactId, projection]);
  const staleCandidates = Boolean(
    candidateSet && projection
      && candidateSet.candidates.some((candidate) => !coverageCandidateIsCurrent(candidate, projection)),
  );
  const visibleCandidates = candidateSet?.candidates.filter((candidate) => (
    !rejectedCandidateIds.has(candidate.candidateId)
  )) ?? [];

  const generate = async () => {
    if (!projection) return;
    setGenerating(true);
    setGenerationError('');
    try {
      const result = await generateCoverageCandidates(graphDraft, contract, draftSet, projection, {
        seed,
        maxCandidates,
        maxWorkUnits,
        selectedFactIds: focusedFact ? [focusedFact.factId] : undefined,
      });
      setCandidateSet(result);
      setRejectedCandidateIds(new Set());
      setAcceptedCandidateIds(new Set());
    } catch (error) {
      setGenerationError(error instanceof Error ? error.message : t('Candidate generation failed.'));
    } finally {
      setGenerating(false);
    }
  };

  const accept = (candidate: CoverageCandidate) => {
    if (!projection) return;
    setGenerationError('');
    try {
      onAcceptCandidate(candidate, projection);
      setAcceptedCandidateIds((current) => new Set(current).add(candidate.candidateId));
    } catch (error) {
      setGenerationError(error instanceof Error ? error.message : t('Candidate acceptance failed.'));
    }
  };

  if (projectionError) {
    return <div className="coverage-lens-error" role="alert">{projectionError}</div>;
  }
  if (!projection) {
    return <div className="coverage-lens-loading">{t('Building coverage inventory...')}</div>;
  }

  return (
    <div className="coverage-lens" data-testid="coverage-lens">
      <div className="coverage-dimension-strip" aria-label={t('Coverage dimensions')}>
        {projection.dimensions.map((entry) => (
          <button
            type="button"
            key={entry.dimension}
            aria-label={t(DIMENSION_LABELS[entry.dimension])}
            aria-pressed={activeDimension === entry.dimension}
            onClick={() => setActiveDimension(entry.dimension)}
          >
            <span>{t(DIMENSION_LABELS[entry.dimension])}</span>
            <strong>{entry.covered} / {entry.total}</strong>
            <small>{t('{count} gaps', { count: entry.gaps.length })}</small>
          </button>
        ))}
      </div>

      <div className="coverage-lens-body">
        <section className="coverage-gap-region" aria-labelledby="coverage-gap-title">
          <header className="coverage-section-heading">
            <div>
              <span>{t(DIMENSION_LABELS[activeDimension])}</span>
              <h3 id="coverage-gap-title">{t('Coverage gaps')}</h3>
            </div>
            <strong>{t('{count} open', { count: dimension?.gaps.length ?? 0 })}</strong>
          </header>

          {dimension && dimension.gaps.length > 0 ? (
            <div className="coverage-gap-table-wrap">
              <table className="coverage-gap-table">
                <thead>
                  <tr>
                    <th>{t('Gap')}</th>
                    <th>{t('Coordinate')}</th>
                    <th>{t('Next action')}</th>
                  </tr>
                </thead>
                <tbody>
                  {dimension.gaps.map((gap) => (
                    <tr key={gap.factId} className={focusedFactId === gap.factId ? 'selected' : ''}>
                      <td>
                        <strong>{gap.label}</strong>
                        <small>{gap.description}</small>
                      </td>
                      <td><code title={gap.coordinate}>{gap.coordinate}</code></td>
                      <td>
                        {gap.generation ? (
                          <button
                            type="button"
                            className="secondary compact"
                            aria-pressed={focusedFactId === gap.factId}
                            onClick={() => setFocusedFactId((current) => current === gap.factId ? '' : gap.factId)}
                          >
                            {t(focusedFactId === gap.factId ? 'Targeted' : 'Target gap')}
                          </button>
                        ) : (
                          <span>{t(ACTION_LABELS[gap.action])}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="coverage-empty-dimension">
              <strong>{t('No open {dimension} gaps', { dimension: t(DIMENSION_LABELS[activeDimension]) })}</strong>
              <span>{t('Every denominator fact in this dimension has a named covering case.')}</span>
            </div>
          )}
        </section>

        <section className="coverage-generation-region" aria-labelledby="coverage-generation-title">
          <header className="coverage-section-heading">
            <div>
              <span>{t('Deterministic generators')}</span>
              <h3 id="coverage-generation-title">{t('Candidate review')}</h3>
            </div>
            <div className="coverage-generator-capability" title={t('Combination generation requires an audited generator adapter.')}>
              {t('Pairwise')} <strong>{t('Not installed')}</strong>
            </div>
          </header>

          <div className="coverage-generation-controls">
            <label>
              <span>{t('Seed')}</span>
              <input
                type="number"
                aria-label={t('Generation seed')}
                value={seed}
                onChange={(event) => setSeed(Number(event.target.value))}
              />
            </label>
            <label>
              <span>{t('Max cases')}</span>
              <input
                type="number"
                min="1"
                max="500"
                aria-label={t('Maximum generated cases')}
                value={maxCandidates}
                onChange={(event) => setMaxCandidates(Number(event.target.value))}
              />
            </label>
            <label>
              <span>{t('Work units')}</span>
              <input
                type="number"
                min="1"
                max="10000"
                aria-label={t('Maximum generation work units')}
                value={maxWorkUnits}
                onChange={(event) => setMaxWorkUnits(Number(event.target.value))}
              />
            </label>
            <button
              type="button"
              className="primary compact"
              disabled={disabled || generating}
              onClick={() => void generate()}
            >
              {t(generating ? 'Generating...' : 'Generate candidates')}
            </button>
          </div>

          <div className="coverage-generation-scope">
            <span>{focusedFact ? t('Target: {target}', { target: t(focusedFact.label) }) : t('Scope: all supported gaps')}</span>
            <span>{t('At most {cases} cases / {units} work units', { cases: maxCandidates, units: maxWorkUnits })}</span>
            {focusedFact && (
              <button type="button" className="link-button" onClick={() => setFocusedFactId('')}>
                {t('Clear target')}
              </button>
            )}
          </div>

          {generationError && <div className="coverage-generation-error" role="alert">{generationError}</div>}
          {staleCandidates && (
            <div className="coverage-candidate-stale" role="status">
              {t('Source changed. Regenerate before accepting another candidate.')}
            </div>
          )}

          {!candidateSet ? (
            <div className="coverage-candidate-empty">
              <strong>{t('No generated candidates')}</strong>
              <span>{t('Generation starts only from the explicit command above.')}</span>
            </div>
          ) : (
            <>
              <div className="coverage-candidate-summary">
                <span>{t('{count} generated', { count: candidateSet.emittedCandidateCount })}</span>
                <span>{t('{count} supported gaps', { count: candidateSet.supportedFactCount })}</span>
                <span>{t('{count} work units', { count: candidateSet.budget.consumedWorkUnits })}</span>
                {candidateSet.truncated && <strong>{t('Budget limited')}</strong>}
              </div>
              {visibleCandidates.length > 0 ? (
                <div className="coverage-candidate-list">
                  {visibleCandidates.map((candidate) => {
                    const current = coverageCandidateIsCurrent(candidate, projection);
                    const accepted = acceptedCandidateIds.has(candidate.candidateId);
                    return (
                      <article className="coverage-candidate-row" key={candidate.candidateId}>
                        <div className="coverage-candidate-main">
                          <span>{candidate.proposal.caseType}</span>
                          <strong>{t(candidate.proposal.name)}</strong>
                          <small>{t(candidate.rationale)}</small>
                        </div>
                        <div className="coverage-candidate-proof">
                          <span className="coverage-needs-oracle">{t('Needs oracle')}</span>
                          <code title={candidate.generatorVersion}>
                            {candidate.generatorId} v{candidate.generatorVersion}
                          </code>
                          <small>{t('{count} named contribution', { count: candidate.contributionFactIds.length })}</small>
                        </div>
                        <div className="coverage-candidate-actions">
                          <button
                            type="button"
                            className="primary compact"
                            disabled={disabled || staleCandidates || !current || accepted}
                            onClick={() => accept(candidate)}
                          >
                            {t(accepted ? 'Accepted' : 'Accept')}
                          </button>
                          <button
                            type="button"
                            className="secondary compact"
                            disabled={accepted}
                            onClick={() => setRejectedCandidateIds((currentIds) => (
                              new Set(currentIds).add(candidate.candidateId)
                            ))}
                          >
                            {t('Reject')}
                          </button>
                        </div>
                      </article>
                    );
                  })}
                </div>
              ) : (
                <div className="coverage-candidate-empty">
                  <strong>{t('No candidates in review')}</strong>
                  <span>{candidateSet.emittedCandidateCount === 0
                    ? t('The selected gap has no installed generator.')
                    : t('{count} candidates rejected from this ephemeral set.', { count: rejectedCandidateIds.size })}</span>
                </div>
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}
