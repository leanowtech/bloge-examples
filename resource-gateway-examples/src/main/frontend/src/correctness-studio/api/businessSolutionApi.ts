import { exchangeCorrectnessApi } from '../../api';

export interface BusinessGoldenCaseSummary {
  caseId: string;
  lifecycle: string;
  qualityState: string;
  factCount: number;
  assumptionCount: number;
  goldenCaseFingerprint: string;
  materialViewable: boolean;
}

export interface BusinessGoldenCatalog {
  solutionRef: string;
  journeyRef: string;
  caseSetRef: string;
  revision: number;
  approvalState: string;
  cases: BusinessGoldenCaseSummary[];
}

export interface BusinessGoldenMaterial {
  caseId: string;
  businessIntent: string;
  givenFacts: Record<string, unknown>;
  dependencyAssumptions: Array<Record<string, unknown>>;
  expectedOutcome: Record<string, unknown>;
  oracleOwner: string;
}

export interface BusinessFixtureSummary {
  fixtureAssetId: string;
  revision: number;
  name: string;
  variantKey: string;
  lifecycle: string;
  classification: string;
  schemaFingerprint: string;
  usageCount: number;
}

export interface BusinessFixtureGroup {
  capabilityKind: 'FEATURE' | 'INSTRUCTION';
  capabilityRef: string;
  businessLabel: string;
  fixtures: BusinessFixtureSummary[];
}

export interface BusinessFixtureMaterial {
  fixtureAssetId: string;
  name: string;
  variantKey: string;
  classification: string;
  payload: unknown;
}

export interface FeatureSuiteSummary {
  featureRef: string;
  revision: number;
  status: string;
  caseCount: number;
  coverageTargetCount: number;
  evidenceFingerprint: string;
  materialViewable: boolean;
}

export interface FeatureSuiteMaterial {
  featureRef: string;
  evaluationRef: string;
  cases: Array<{
    caseId: string;
    intent: string;
    givenInputs: Record<string, unknown>;
    nodeBehaviors: Array<Record<string, unknown>>;
    expectedOutput: unknown;
    coverageTargets: string[];
  }>;
}

export interface BusinessCoverageObligation {
  id: string;
  obligationFingerprint: string;
  dimension: 'RULE' | 'OTHERWISE' | 'DEPENDENCY_FAULT';
  risk: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  covered: boolean;
  byCaseIds: string[];
}

export interface BusinessSolutionCoverage {
  solutionRef: string;
  inventoryId: string;
  inventoryRevision: number;
  solutionFingerprint: string;
  obligations: BusinessCoverageObligation[];
  summary: {
    total: number;
    covered: number;
    uncovered: number;
    highRiskUncovered: number;
  };
}

export interface BusinessSolutionAssetsApi {
  golden(solutionRef: string, journeyRef: string): Promise<BusinessGoldenCatalog>;
  goldenMaterial(solutionRef: string, journeyRef: string, caseId: string): Promise<BusinessGoldenMaterial>;
  fixtures(solutionRef: string): Promise<BusinessFixtureGroup[]>;
  fixtureMaterial(solutionRef: string, fixtureAssetId: string): Promise<BusinessFixtureMaterial>;
  featureSuites(solutionRef: string): Promise<FeatureSuiteSummary[]>;
  featureSuiteMaterial(solutionRef: string, featureRef: string): Promise<FeatureSuiteMaterial>;
  coverage(solutionRef: string): Promise<BusinessSolutionCoverage>;
}

/** Supplies request-local human identity headers without replacing the legacy global provider. */
export type BusinessReviewerHeadersProvider = () => Record<string, string>;

/** Converts one page-memory reviewer credential to a request-local bearer header. */
export function reviewerBearerHeaders(credential: string): Record<string, string> {
  const token = credential.trim();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Creates the business-only API with a fresh human header lookup for each request. */
export function createBusinessSolutionAssetsApi(
  reviewerHeaders: BusinessReviewerHeadersProvider,
): BusinessSolutionAssetsApi {
  const exchange = <T>(path: string) => exchangeCorrectnessApi<T>(
    path, 'SOLUTION_GOLDEN_REVIEW', { identityHeaders: reviewerHeaders() },
  );
  return {
    golden(solutionRef, journeyRef) {
      const query = new URLSearchParams({ journeyRef });
      return exchange<BusinessGoldenCatalog>(
        `/api/solution/golden-review/${encodeURIComponent(solutionRef)}?${query}`,
      );
    },
    goldenMaterial(solutionRef, journeyRef, caseId) {
      const query = new URLSearchParams({ journeyRef });
      return exchange<BusinessGoldenMaterial>(
        `/api/solution/golden-review/${encodeURIComponent(solutionRef)}`
          + `/cases/${encodeURIComponent(caseId)}/material?${query}`,
      );
    },
    fixtures(solutionRef) {
      return exchange<BusinessFixtureGroup[]>(
        `/api/agent-tdd/solutions/${encodeURIComponent(solutionRef)}/fixtures`,
      );
    },
    fixtureMaterial(solutionRef, fixtureAssetId) {
      return exchange<BusinessFixtureMaterial>(
        `/api/agent-tdd/solutions/${encodeURIComponent(solutionRef)}`
          + `/fixtures/${encodeURIComponent(fixtureAssetId)}/material`,
      );
    },
    featureSuites(solutionRef) {
      const query = new URLSearchParams({ solutionRef });
      return exchange<FeatureSuiteSummary[]>(`/api/solution/feature-suite-review?${query}`);
    },
    featureSuiteMaterial(solutionRef, featureRef) {
      const query = new URLSearchParams({ solutionRef });
      return exchange<FeatureSuiteMaterial>(
        `/api/solution/feature-suite-review/${encodeURIComponent(featureRef)}/material?${query}`,
      );
    },
    coverage(solutionRef) {
      return exchange<BusinessSolutionCoverage>(
        `/api/solution/coverage/${encodeURIComponent(solutionRef)}`,
      );
    },
  };
}

/** Fail-closed default; the page creates a reviewer-bound instance after credential entry. */
export const businessSolutionAssetsApi = createBusinessSolutionAssetsApi(() => ({}));
