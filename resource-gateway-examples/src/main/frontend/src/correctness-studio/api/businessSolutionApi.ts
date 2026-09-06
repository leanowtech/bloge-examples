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

export interface BusinessSolutionAssetsApi {
  golden(solutionRef: string, journeyRef: string): Promise<BusinessGoldenCatalog>;
  goldenMaterial(solutionRef: string, journeyRef: string, caseId: string): Promise<BusinessGoldenMaterial>;
  fixtures(solutionRef: string): Promise<BusinessFixtureGroup[]>;
}

/** Production API used by the business world of Correctness Studio. */
export const businessSolutionAssetsApi: BusinessSolutionAssetsApi = {
  golden(solutionRef, journeyRef) {
    const query = new URLSearchParams({ journeyRef });
    return exchangeCorrectnessApi(
      `/api/solution/golden-review/${encodeURIComponent(solutionRef)}?${query}`,
      'SOLUTION_GOLDEN_REVIEW',
    );
  },
  goldenMaterial(solutionRef, journeyRef, caseId) {
    const query = new URLSearchParams({ journeyRef });
    return exchangeCorrectnessApi(
      `/api/solution/golden-review/${encodeURIComponent(solutionRef)}`
        + `/cases/${encodeURIComponent(caseId)}/material?${query}`,
      'SOLUTION_GOLDEN_REVIEW',
    );
  },
  fixtures(solutionRef) {
    return exchangeCorrectnessApi(
      `/api/agent-tdd/solutions/${encodeURIComponent(solutionRef)}/fixtures`,
      'AGENT_TDD_GOVERNED_WRITE',
    );
  },
};
