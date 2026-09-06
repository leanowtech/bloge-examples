import { Database, Eye, FileCheck2, LoaderCircle, ShieldCheck } from 'lucide-react';
import { useEffect, useState } from 'react';

import { useI18n } from '../i18n/I18nProvider';
import {
  businessSolutionAssetsApi,
  type BusinessGoldenCatalog,
  type BusinessGoldenMaterial,
  type BusinessSolutionAssetsApi,
  type BusinessFixtureGroup,
} from './api/businessSolutionApi';

/** Human review surface for protected business GOLDEN material and related Fixture metadata. */
export default function BusinessSolutionAssets({
  solutionRef,
  journeyRef,
  api = businessSolutionAssetsApi,
}: {
  solutionRef: string;
  journeyRef: string;
  api?: BusinessSolutionAssetsApi;
}) {
  const { t } = useI18n();
  const [golden, setGolden] = useState<BusinessGoldenCatalog | null>(null);
  const [fixtures, setFixtures] = useState<BusinessFixtureGroup[]>([]);
  const [material, setMaterial] = useState<BusinessGoldenMaterial | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    setMaterial(null);
    Promise.all([api.golden(solutionRef, journeyRef), api.fixtures(solutionRef)])
      .then(([catalog, fixtureGroups]) => {
        if (!active) return;
        setGolden(catalog);
        setFixtures(fixtureGroups);
        setLoading(false);
      })
      .catch((cause: unknown) => {
        if (!active) return;
        setError(message(cause));
        setLoading(false);
      });
    return () => { active = false; };
  }, [api, journeyRef, solutionRef]);

  const openMaterial = async (caseId: string) => {
    setError('');
    try {
      setMaterial(await api.goldenMaterial(solutionRef, journeyRef, caseId));
    } catch (cause) {
      setError(message(cause));
    }
  };

  if (loading) {
    return <p className="correctness-empty" role="status"><LoaderCircle className="spin" size={20} />{t('Loading business assets')}</p>;
  }
  if (error && !golden) return <p className="correctness-empty error" role="alert">{error}</p>;

  return <div className="correctness-business-assets">
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

function ReadOnlyValues({ title, value }: { title: string; value: unknown }) {
  return <div className="correctness-readonly-values"><strong>{title}</strong><pre>{JSON.stringify(value, null, 2)}</pre></div>;
}

function shortFingerprint(value: string): string {
  return value.length > 22 ? `${value.slice(0, 18)}…` : value;
}

function message(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
