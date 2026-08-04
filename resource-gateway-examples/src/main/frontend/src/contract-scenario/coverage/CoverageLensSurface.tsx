import { useEffect, useMemo, useState } from 'react';

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
        if (!cancelled) setProjectionError(error instanceof Error ? error.message : 'Coverage projection failed.');
      });
    return () => {
      cancelled = true;
    };
  }, [contract, draftSet, evidenceByCase, graphDraft]);

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
      setGenerationError(error instanceof Error ? error.message : 'Candidate generation failed.');
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
      setGenerationError(error instanceof Error ? error.message : 'Candidate acceptance failed.');
    }
  };

  if (projectionError) {
    return <div className="coverage-lens-error" role="alert">{projectionError}</div>;
  }
  if (!projection) {
    return <div className="coverage-lens-loading">Building coverage inventory...</div>;
  }

  return (
    <div className="coverage-lens" data-testid="coverage-lens">
      <div className="coverage-dimension-strip" aria-label="Coverage dimensions">
        {projection.dimensions.map((entry) => (
          <button
            type="button"
            key={entry.dimension}
            aria-label={DIMENSION_LABELS[entry.dimension]}
            aria-pressed={activeDimension === entry.dimension}
            onClick={() => setActiveDimension(entry.dimension)}
          >
            <span>{DIMENSION_LABELS[entry.dimension]}</span>
            <strong>{entry.covered} / {entry.total}</strong>
            <small>{entry.gaps.length} gaps</small>
          </button>
        ))}
      </div>

      <div className="coverage-lens-body">
        <section className="coverage-gap-region" aria-labelledby="coverage-gap-title">
          <header className="coverage-section-heading">
            <div>
              <span>{DIMENSION_LABELS[activeDimension]}</span>
              <h3 id="coverage-gap-title">Coverage gaps</h3>
            </div>
            <strong>{dimension?.gaps.length ?? 0} open</strong>
          </header>

          {dimension && dimension.gaps.length > 0 ? (
            <div className="coverage-gap-table-wrap">
              <table className="coverage-gap-table">
                <thead>
                  <tr>
                    <th>Gap</th>
                    <th>Coordinate</th>
                    <th>Next action</th>
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
                            {focusedFactId === gap.factId ? 'Targeted' : 'Target gap'}
                          </button>
                        ) : (
                          <span>{ACTION_LABELS[gap.action]}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="coverage-empty-dimension">
              <strong>No open {DIMENSION_LABELS[activeDimension].toLocaleLowerCase()} gaps</strong>
              <span>Every denominator fact in this dimension has a named covering case.</span>
            </div>
          )}
        </section>

        <section className="coverage-generation-region" aria-labelledby="coverage-generation-title">
          <header className="coverage-section-heading">
            <div>
              <span>Deterministic generators</span>
              <h3 id="coverage-generation-title">Candidate review</h3>
            </div>
            <div className="coverage-generator-capability" title="Combination generation requires an audited generator adapter.">
              Pairwise <strong>Not installed</strong>
            </div>
          </header>

          <div className="coverage-generation-controls">
            <label>
              <span>Seed</span>
              <input
                type="number"
                aria-label="Generation seed"
                value={seed}
                onChange={(event) => setSeed(Number(event.target.value))}
              />
            </label>
            <label>
              <span>Max cases</span>
              <input
                type="number"
                min="1"
                max="500"
                aria-label="Maximum generated cases"
                value={maxCandidates}
                onChange={(event) => setMaxCandidates(Number(event.target.value))}
              />
            </label>
            <label>
              <span>Work units</span>
              <input
                type="number"
                min="1"
                max="10000"
                aria-label="Maximum generation work units"
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
              {generating ? 'Generating...' : 'Generate candidates'}
            </button>
          </div>

          <div className="coverage-generation-scope">
            <span>{focusedFact ? `Target: ${focusedFact.label}` : 'Scope: all supported gaps'}</span>
            <span>At most {maxCandidates} cases / {maxWorkUnits} work units</span>
            {focusedFact && (
              <button type="button" className="link-button" onClick={() => setFocusedFactId('')}>
                Clear target
              </button>
            )}
          </div>

          {generationError && <div className="coverage-generation-error" role="alert">{generationError}</div>}
          {staleCandidates && (
            <div className="coverage-candidate-stale" role="status">
              Source changed. Regenerate before accepting another candidate.
            </div>
          )}

          {!candidateSet ? (
            <div className="coverage-candidate-empty">
              <strong>No generated candidates</strong>
              <span>Generation starts only from the explicit command above.</span>
            </div>
          ) : (
            <>
              <div className="coverage-candidate-summary">
                <span>{candidateSet.emittedCandidateCount} generated</span>
                <span>{candidateSet.supportedFactCount} supported gaps</span>
                <span>{candidateSet.budget.consumedWorkUnits} work units</span>
                {candidateSet.truncated && <strong>Budget limited</strong>}
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
                          <strong>{candidate.proposal.name}</strong>
                          <small>{candidate.rationale}</small>
                        </div>
                        <div className="coverage-candidate-proof">
                          <span className="coverage-needs-oracle">Needs oracle</span>
                          <code title={candidate.generatorVersion}>
                            {candidate.generatorId} v{candidate.generatorVersion}
                          </code>
                          <small>{candidate.contributionFactIds.length} named contribution</small>
                        </div>
                        <div className="coverage-candidate-actions">
                          <button
                            type="button"
                            className="primary compact"
                            disabled={disabled || staleCandidates || !current || accepted}
                            onClick={() => accept(candidate)}
                          >
                            {accepted ? 'Accepted' : 'Accept'}
                          </button>
                          <button
                            type="button"
                            className="secondary compact"
                            disabled={accepted}
                            onClick={() => setRejectedCandidateIds((currentIds) => (
                              new Set(currentIds).add(candidate.candidateId)
                            ))}
                          >
                            Reject
                          </button>
                        </div>
                      </article>
                    );
                  })}
                </div>
              ) : (
                <div className="coverage-candidate-empty">
                  <strong>No candidates in review</strong>
                  <span>{candidateSet.emittedCandidateCount === 0
                    ? 'The selected gap has no installed generator.'
                    : `${rejectedCandidateIds.size} candidates rejected from this ephemeral set.`}</span>
                </div>
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}
