import { describe, expect, it } from 'vitest';

import type { GraphDraft } from '../../types';
import type { ContractDraft, ScenarioDraftSet } from '../domain';
import { tableDrivenScenarioBaseline } from '../tableDrivenTestingBaseline';
import {
  acceptCoverageCandidate,
  buildCoverageProjection,
  coverageCandidateIsCurrent,
  generateCoverageCandidates,
} from './coverageModel';

describe('coverage model', () => {
  it('projects six explainable dimensions without an opaque aggregate score', async () => {
    const projection = await buildCoverageProjection(graph(), contract(), draftSet());

    expect(projection.dimensions.map((dimension) => dimension.dimension)).toEqual([
      'CASE',
      'CONTRACT',
      'DAG',
      'DEPENDENCY',
      'ASSERTION',
      'EVIDENCE',
    ]);
    expect(projection.dimensions.every((dimension) => (
      dimension.covered + dimension.gaps.length === dimension.total
    ))).toBe(true);
    expect(projection.projectionFingerprint).toMatch(/^sha256:[a-f0-9]{64}$/);
    expect(projection).not.toHaveProperty('score');
    expect(projection).not.toHaveProperty('percentage');
  });

  it('locates required, null, enum, numeric, string, union, and error-contract gaps', async () => {
    const projection = await buildCoverageProjection(graph(), contract(), draftSet());
    const contractKinds = dimension(projection, 'CONTRACT').facts.map((fact) => fact.kind);

    expect(contractKinds).toEqual(expect.arrayContaining([
      'REQUIRED_MISSING_REJECTED',
      'NULL_REJECTED',
      'ENUM_VALUE',
      'INVALID_ENUM_REJECTED',
      'MINIMUM_ACCEPTED',
      'BELOW_MINIMUM_REJECTED',
      'MAXIMUM_ACCEPTED',
      'ABOVE_MAXIMUM_REJECTED',
      'MIN_LENGTH_ACCEPTED',
      'BELOW_MIN_LENGTH_REJECTED',
      'UNION_VARIANT',
      'ERROR_VARIANT',
    ]));
    expect(dimension(projection, 'CONTRACT').gaps.every((gap) => gap.coordinate)).toBe(true);
  });

  it('derives DAG paths, controlled behavior, assertions, and evidence independently', async () => {
    const projection = await buildCoverageProjection(graph(), contract(), draftSet(), {
      golden: {
        caseId: 'golden',
        runId: 'run-1',
        attempt: 1,
        durationMs: 21,
        execution: 'SUCCESS',
        assertions: 'PASSED',
        freshness: 'CURRENT',
        proofStrength: 'MOCK',
        firstFailure: null,
      },
    });

    expect(dimension(projection, 'DAG').facts.map((fact) => fact.kind)).toEqual(expect.arrayContaining([
      'CONDITIONAL_EDGE',
      'FALLBACK_PATH',
      'RETRY_PATH',
    ]));
    expect(dimension(projection, 'DEPENDENCY').facts.some((fact) => (
      fact.coordinate === 'score:TIMEOUT' && fact.coveredByCaseIds.length === 0
    ))).toBe(true);
    expect(dimension(projection, 'ASSERTION').facts.some((fact) => (
      fact.kind === 'OUTPUT_ORACLE' && fact.coveredByCaseIds.includes('golden')
    ))).toBe(true);
    expect(dimension(projection, 'EVIDENCE').facts.filter((fact) => (
      fact.coveredByCaseIds.includes('golden')
    ))).toHaveLength(3);
  });

  it('generates the same ordered candidates for an exact source, version, seed, and budget', async () => {
    const projection = await buildCoverageProjection(graph(), contract(), draftSet());
    const options = { seed: 42, maxCandidates: 12, maxWorkUnits: 12 };

    const first = await generateCoverageCandidates(graph(), contract(), draftSet(), projection, options);
    const second = await generateCoverageCandidates(graph(), contract(), draftSet(), projection, options);

    expect(first.candidates.map((candidate) => candidate.candidateFingerprint)).toEqual(
      second.candidates.map((candidate) => candidate.candidateFingerprint),
    );
    expect(first.candidateSetId).toBe(second.candidateSetId);
    expect(first.generatorVersions).toEqual({
      'bloge.schema-boundary': '1.0.0',
      'bloge.dependency-behavior': '1.0.0',
    });
  });

  it('changes deterministic ordering with seed and enforces both candidate and work budgets', async () => {
    const projection = await buildCoverageProjection(graph(), contract(), draftSet());
    const first = await generateCoverageCandidates(graph(), contract(), draftSet(), projection, {
      seed: 3,
      maxCandidates: 3,
      maxWorkUnits: 2,
    });
    const second = await generateCoverageCandidates(graph(), contract(), draftSet(), projection, {
      seed: 99,
      maxCandidates: 3,
      maxWorkUnits: 2,
    });

    expect(first.candidates).toHaveLength(2);
    expect(first.budget.consumedWorkUnits).toBe(2);
    expect(first.truncated).toBe(true);
    expect(first.candidates.map((candidate) => candidate.contributionFactIds[0])).not.toEqual(
      second.candidates.map((candidate) => candidate.contributionFactIds[0]),
    );
  });

  it('never invents a business oracle or marks a generated candidate promotion eligible', async () => {
    const projection = await buildCoverageProjection(graph(), contract(), draftSet());
    const result = await generateCoverageCandidates(graph(), contract(), draftSet(), projection, {
      seed: 7,
      maxCandidates: 100,
      maxWorkUnits: 100,
    });

    expect(result.candidates.length).toBeGreaterThan(0);
    for (const candidate of result.candidates) {
      expect(candidate.proposal.then.assertions).toEqual([]);
      expect(candidate.expectedBehavior.status).toBe('NEEDS_AUTHOR');
      expect(candidate.promotionEligible).toBe(false);
      expect(candidate.contributionFactIds.length).toBeGreaterThan(0);
      expect(candidate.source.coverageProjectionFingerprint).toBe(projection.projectionFingerprint);
      expect(candidate.candidateFingerprint).toMatch(/^sha256:[a-f0-9]{64}$/);
    }
    expect(result.candidates.some((candidate) => (
      candidate.proposal.tags.includes('error:RG.LOAN.REJECTED')
    ))).toBe(true);
    expect(result.candidates.some((candidate) => (
      candidate.proposal.dependencies.some((dependency) => dependency.behavior.kind === 'MUST_NOT_CALL')
    ))).toBe(true);
  });

  it('accepts only an exact current candidate and makes the remaining candidate set stale', async () => {
    const initial = draftSet();
    const projection = await buildCoverageProjection(graph(), contract(), initial);
    const result = await generateCoverageCandidates(graph(), contract(), initial, projection, {
      seed: 17,
      maxCandidates: 4,
      maxWorkUnits: 4,
    });
    const accepted = acceptCoverageCandidate(initial, projection, result.candidates[0]);

    expect(accepted.scenarios).toHaveLength(initial.scenarios.length + 1);
    expect(accepted.scenarios[accepted.scenarios.length - 1]?.scenarioId)
      .toBe(result.candidates[0].proposal.scenarioId);
    expect(accepted.metadata.provenance.lastCoverageCandidateAcceptance).toMatchObject({
      candidateId: result.candidates[0].candidateId,
      sourceProjectionFingerprint: projection.projectionFingerprint,
    });

    const nextProjection = await buildCoverageProjection(graph(), contract(), accepted);
    expect(coverageCandidateIsCurrent(result.candidates[1], nextProjection)).toBe(false);
    expect(() => acceptCoverageCandidate(accepted, nextProjection, result.candidates[1]))
      .toThrow(/stale/i);
  });

  it('does not generate anything merely by projecting and handles the 500-case baseline', async () => {
    const baseline = tableDrivenScenarioBaseline(500);
    const projection = await buildCoverageProjection(graph(), contract(), baseline);
    const factIds = projection.dimensions.flatMap((entry) => entry.facts.map((fact) => fact.factId));

    expect(projection.scenarioDraftSetId).toBe('table-driven-baseline-500');
    expect(new Set(factIds).size).toBe(factIds.length);
    expect(baseline.scenarios).toHaveLength(500);
    expect(baseline.scenarios.some((scenario) => scenario.given.provenance === 'GENERATED')).toBe(false);
  });
});

function dimension(
  projection: Awaited<ReturnType<typeof buildCoverageProjection>>,
  id: 'CASE' | 'CONTRACT' | 'DAG' | 'DEPENDENCY' | 'ASSERTION' | 'EVIDENCE',
) {
  const result = projection.dimensions.find((entry) => entry.dimension === id);
  if (!result) throw new Error(`Missing ${id} dimension.`);
  return result;
}

function graph(): GraphDraft {
  return {
    schemaVersion: 'bloge.visualGraphDraft.v1',
    draftId: 'loan-graph',
    revision: 4,
    graphName: 'loanGraph',
    nodes: [
      { id: 'score', operatorRef: 'risk:score', config: { retry: true, maxAttempts: 2 } },
      { id: 'decide', operatorRef: 'risk:decide', config: { fallbackNodeId: 'manual' } },
    ],
    edges: [{
      id: 'high-risk',
      kind: 'data',
      source: { nodeId: 'score' },
      target: { nodeId: 'decide' },
      condition: '$.score >= 700',
    }],
    nodeFixtures: { score: { output: { score: 720 } } },
    output: { nodeId: 'decide' },
  };
}

function contract(): ContractDraft {
  const fingerprint = `sha256:${'b'.repeat(64)}`;
  return {
    schemaVersion: 'bloge.contractDraft.v1',
    target: { kind: 'GRAPH', id: 'loanGraph', revision: 4, fingerprint },
    inputSchema: {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        required: ['applicantId', 'age', 'tier', 'identity'],
        properties: {
          applicantId: { type: 'string', minLength: 2, maxLength: 12 },
          age: { type: 'integer', minimum: 18, maximum: 90 },
          tier: { type: 'string', enum: ['STANDARD', 'PREMIUM'] },
          note: { type: ['string', 'null'] },
          identity: {
            oneOf: [
              { type: 'string', minLength: 1 },
              { type: 'integer', minimum: 1 },
            ],
          },
        },
      },
    },
    outputSchema: {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        required: ['decision'],
        properties: {
          decision: {
            type: 'object',
            properties: {
              approved: { type: 'boolean' },
              reason: { type: 'string' },
            },
          },
        },
      },
    },
    errorContract: [{
      code: 'RG.LOAN.REJECTED',
      type: 'LoanRejected',
      description: 'The loan cannot be approved.',
      retryable: false,
    }],
    executionSemantics: {
      effect: 'READ',
      idempotency: 'IDEMPOTENT',
      streaming: false,
      durable: true,
    },
    invariants: [],
    compatibilityPolicy: { mode: 'BACKWARD', unknownBlocksAutomaticMigration: true },
    fieldMetadata: {},
    source: 'AUTHORED',
    confidence: 'EXACT',
  };
}

function draftSet(): ScenarioDraftSet {
  const targetFingerprint = `sha256:${'b'.repeat(64)}`;
  const contractFingerprint = `sha256:${'c'.repeat(64)}`;
  return {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: 'loan-scenarios',
    revision: 3,
    scope: {
      tenantId: 'tenant-a',
      organizationId: 'credit',
      projectId: 'loan',
      environment: 'test',
      region: 'local',
    },
    target: { kind: 'GRAPH', id: 'loanGraph', revision: 4, fingerprint: targetFingerprint },
    contractFingerprint,
    scenarios: [{
      scenarioId: 'golden',
      name: 'Premium applicant',
      description: 'A happy path.',
      caseType: 'GOLDEN',
      tags: ['edge:high-risk'],
      given: {
        input: {
          applicantId: 'A123',
          age: 42,
          tier: 'PREMIUM',
          note: null,
          identity: 'passport',
        },
        provenance: 'AUTHORED',
      },
      dependencies: [{
        dependencyId: 'score-return',
        selector: {
          graphPath: '/root',
          nodeId: 'score',
          operatorRef: 'risk:score',
          resourceRef: '',
          functionRef: '',
          attempts: [1, 2],
          occurrences: [],
          correlationKey: '',
          pathEquals: {},
        },
        behavior: { kind: 'RETURN', boundary: 'NODE', output: { score: 720 } },
        consumption: {
          required: true,
          minUses: 1,
          maxUses: 2,
          onExhausted: 'FAIL',
          onUnmatched: 'FAIL',
        },
        schemaCheck: { mode: 'STRICT', waiverReason: '' },
        origin: 'AUTHORED',
      }],
      then: {
        assertions: [{
          assertionId: 'output',
          scope: 'OUTPUT_PATH',
          nodeId: '',
          fromNodeId: '',
          toNodeId: '',
          path: '$',
          operator: 'EQUALS',
          expected: { decision: { approved: true, reason: 'eligible' } },
        }],
      },
    }],
    metadata: {
      owner: 'credit-team',
      classification: 'INTERNAL',
      createdAt: '2026-08-04T00:00:00Z',
      updatedAt: '2026-08-04T00:00:00Z',
      provenance: { source: 'test' },
    },
  };
}
