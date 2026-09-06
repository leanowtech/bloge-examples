import {
  AlertTriangle,
  BarChart3,
  CheckCircle2,
  Database,
  Eye,
  FileCheck2,
  LoaderCircle,
  ShieldCheck,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';

import { useI18n } from '../i18n/I18nProvider';
import {
  createBusinessSolutionAssetsApi,
  reviewerBearerHeaders,
  type BusinessGoldenCatalog,
  type BusinessGoldenMaterial,
  type BusinessSolutionCoverage,
  type BusinessSolutionAssetsApi,
  type BusinessFixtureGroup,
  type BusinessCoverageObligation,
} from './api/businessSolutionApi';

/** Human review surface for protected business GOLDEN material and related Fixture metadata. */
export default function BusinessSolutionAssets({
  solutionRef,
  journeyRef,
  api,
}: {
  solutionRef: string;
  journeyRef: string;
  api?: BusinessSolutionAssetsApi;
}) {
  const { t } = useI18n();
  const [credentialInput, setCredentialInput] = useState('');
  const [activeCredential, setActiveCredential] = useState('');
  const [golden, setGolden] = useState<BusinessGoldenCatalog | null>(null);
  const [fixtures, setFixtures] = useState<BusinessFixtureGroup[]>([]);
  const [coverage, setCoverage] = useState<BusinessSolutionCoverage | null>(null);
  const [material, setMaterial] = useState<BusinessGoldenMaterial | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const resolvedApi = useMemo(() => api ?? (activeCredential
    ? createBusinessSolutionAssetsApi(() => reviewerBearerHeaders(activeCredential))
    : null), [activeCredential, api]);

  useEffect(() => {
    if (!resolvedApi) {
      setLoading(false);
      setGolden(null);
      setFixtures([]);
      setCoverage(null);
      return undefined;
    }
    let active = true;
    setLoading(true);
    setError('');
    setMaterial(null);
    Promise.all([
      resolvedApi.golden(solutionRef, journeyRef),
      resolvedApi.fixtures(solutionRef),
      resolvedApi.coverage(solutionRef),
    ])
      .then(([catalog, fixtureGroups, coverageStatus]) => {
        if (!active) return;
        setGolden(catalog);
        setFixtures(fixtureGroups);
        setCoverage(coverageStatus);
        setLoading(false);
      })
      .catch((cause: unknown) => {
        if (!active) return;
        setError(message(cause));
        setLoading(false);
      });
    return () => { active = false; };
  }, [journeyRef, resolvedApi, solutionRef]);

  const openMaterial = async (caseId: string) => {
    if (!resolvedApi) return;
    setError('');
    try {
      setMaterial(await resolvedApi.goldenMaterial(solutionRef, journeyRef, caseId));
    } catch (cause) {
      setError(message(cause));
    }
  };

  if (!resolvedApi) {
    return <section className="correctness-reviewer-credential" data-testid="business-reviewer-credential">
      <ShieldCheck size={22} />
      <div><strong>{t('Connect a human reviewer')}</strong>
        <p>{t('This credential stays in page memory and is used only for protected business asset requests.')}</p></div>
      <form onSubmit={(event) => {
        event.preventDefault();
        const credential = credentialInput.trim();
        if (!credential) return;
        setActiveCredential(credential);
        setCredentialInput('');
      }}>
        <label>{t('Reviewer credential')}
          <input type="password" autoComplete="off" value={credentialInput}
            onChange={(event) => setCredentialInput(event.target.value)} />
        </label>
        <button type="submit" disabled={!credentialInput.trim()}>{t('Open protected business assets')}</button>
      </form>
    </section>;
  }

  if (loading) {
    return <p className="correctness-empty" role="status"><LoaderCircle className="spin" size={20} />{t('Loading business assets')}</p>;
  }
  if (error && !golden) return <section className="correctness-empty error" role="alert">
    <p>{error}</p><button type="button" onClick={() => {
      setActiveCredential('');
      setError('');
    }}>{t('Change reviewer credential')}</button>
  </section>;

  return <div className="correctness-business-assets">
    {coverage && <BusinessCoverage coverage={coverage} />}
    <section className="correctness-authoring-panel" data-testid="business-golden-assets">
      <header className="correctness-authoring-heading">
        <div><FileCheck2 size={18} /><strong>{t('Business Golden')}</strong>
          <span>{t('Approved business expectations are retained as protected assets.')}</span></div>
        <span className="correctness-coordinate">{golden?.caseSetRef} · r{golden?.revision}</span>
      </header>
      <div className="correctness-business-asset-list">
        {golden?.cases.map((item) => <article key={item.caseId}>
          <div><strong>{item.caseId}</strong><span>{t(item.lifecycle)} · {t(item.qualityState)}</span></div>
          <p>{t('{facts} facts · {assumptions} dependency assumptions', {
            facts: item.factCount, assumptions: item.assumptionCount,
          })}</p>
          <code>{shortFingerprint(item.goldenCaseFingerprint)}</code>
          <button type="button" disabled={!item.materialViewable} onClick={() => openMaterial(item.caseId)}>
            <Eye size={16} />{t('Load protected data')}
          </button>
        </article>)}
      </div>
      {material && <section className="correctness-protected-business-material" aria-label={t('Protected business Golden material')}>
        <header><ShieldCheck size={18} /><div><strong>{material.businessIntent}</strong><small>{material.oracleOwner}</small></div></header>
        <ReadOnlyValues title={t('Given business facts')} value={material.givenFacts} />
        <ReadOnlyValues title={t('Dependency assumptions')} value={material.dependencyAssumptions} />
        <ReadOnlyValues title={t('Expected business outcome')} value={material.expectedOutcome} />
      </section>}
      {error && <p className="error" role="alert">{error}</p>}
    </section>

    <section className="correctness-authoring-panel" data-testid="business-fixture-assets">
      <header className="correctness-authoring-heading"><div><Database size={18} /><strong>{t('Business Fixtures')}</strong>
        <span>{t('Protected test data grouped by the business capability that uses it.')}</span></div></header>
      <div className="correctness-business-fixture-groups">
        {fixtures.map((group) => <article key={`${group.capabilityKind}:${group.capabilityRef}`}>
          <header><strong>{group.businessLabel}</strong><small>{t(group.capabilityKind)}</small></header>
          {group.fixtures.length === 0
            ? <p>{t('No Fixture asset has been linked yet.')}</p>
            : <ul>{group.fixtures.map((fixture) => <li key={fixture.fixtureAssetId}>
              <strong>{fixture.name}</strong><span>{fixture.variantKey} · {t(fixture.lifecycle)}</span>
              <small>{t(fixture.classification)} · {t('{count} uses', { count: fixture.usageCount })}</small>
            </li>)}</ul>}
        </article>)}
      </div>
    </section>
  </div>;
}

function BusinessCoverage({ coverage }: { coverage: BusinessSolutionCoverage }) {
  const { t } = useI18n();
  const groups = groupCoverage(coverage.obligations);
  const percent = coverage.summary.total === 0
    ? 0 : Math.round((coverage.summary.covered / coverage.summary.total) * 100);
  return <section className="correctness-authoring-panel correctness-business-coverage"
    data-testid="business-solution-coverage">
    <header className="correctness-authoring-heading">
      <div><BarChart3 size={18} /><strong>{t('Business situation coverage')}</strong>
        <span>{t('See which decision paths and controlled failures still need approved business examples.')}</span></div>
      <span className="correctness-coordinate">{coverage.inventoryId} · r{coverage.inventoryRevision}</span>
    </header>
    <div className="correctness-business-coverage-summary">
      <div><strong>{t('{covered} of {total} covered', {
        covered: coverage.summary.covered, total: coverage.summary.total,
      })}</strong><span>{percent}%</span></div>
      <div className="correctness-business-coverage-track" aria-label={t('Business coverage progress')}>
        <span style={{ width: `${percent}%` }} />
      </div>
      <p>{t('{count} still need business examples', { count: coverage.summary.uncovered })}
        {' · '}{t('{count} high-risk gaps', { count: coverage.summary.highRiskUncovered })}</p>
    </div>
    <div className="correctness-business-coverage-groups">
      {groups.map((group) => <article key={group.dimension}>
        <header><strong>{t(dimensionLabel(group.dimension))}</strong>
          <small>{t('{covered} of {total} covered', {
            covered: group.items.filter((item) => item.covered).length,
            total: group.items.length,
          })}</small></header>
        <ul>{group.items.map((item) => <li key={item.id}
          data-coverage={item.covered ? 'covered' : 'uncovered'}>
          {item.covered ? <CheckCircle2 size={17} /> : <AlertTriangle size={17} />}
          <div><strong>{item.id}</strong><span>{t(item.risk)}</span>
            <small>{item.byCaseIds.length > 0
              ? t('Covered by {cases}', { cases: item.byCaseIds.join(', ') })
              : t('No approved Golden covers this obligation.')}</small></div>
        </li>)}</ul>
      </article>)}
    </div>
  </section>;
}

function groupCoverage(obligations: BusinessCoverageObligation[]) {
  const dimensions: BusinessCoverageObligation['dimension'][] = [
    'RULE', 'OTHERWISE', 'DEPENDENCY_FAULT',
  ];
  return dimensions.map((dimension) => ({
    dimension,
    items: obligations.filter((item) => item.dimension === dimension),
  })).filter((group) => group.items.length > 0);
}

function dimensionLabel(dimension: BusinessCoverageObligation['dimension']): string {
  if (dimension === 'RULE') return 'Decision rules';
  if (dimension === 'OTHERWISE') return 'Fallback paths';
  return 'Dependency failures';
}

function ReadOnlyValues({ title, value }: { title: string; value: unknown }) {
  return <div className="correctness-readonly-values"><strong>{title}</strong><pre>{JSON.stringify(value, null, 2)}</pre></div>;
}

function shortFingerprint(value: string): string {
  return value.length > 22 ? `${value.slice(0, 18)}…` : value;
}

function message(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
