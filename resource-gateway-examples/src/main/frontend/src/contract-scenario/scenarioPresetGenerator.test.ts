import { describe, expect, it } from 'vitest';

import { contractDraftFromGraphDraft } from './domain';
import { graphDraft, nodes } from './testFixtures';
import {
  generateMeaningfulFixture,
  generateScenarioPreset,
  generateScenarioPresetSuite,
} from './scenarioPresetGenerator';

describe('scenario preset generator', () => {
  it('uses field semantics and schema boundaries instead of placeholder-only values', () => {
    const fixture = generateMeaningfulFixture({
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        properties: {
          applicantId: { type: 'string' },
          email: { type: 'string', format: 'email' },
          income: { type: 'number', minimum: 0, maximum: 10000 },
          segment: { type: 'string', enum: ['STANDARD', 'PREMIUM'] },
        },
      },
    }, 'GOLDEN');

    expect(fixture).toEqual({
      applicantId: 'applicant-1001',
      email: 'alex.chen@example.test',
      income: 1200,
      segment: 'STANDARD',
    });
  });

  it('creates honest presets and does not invent an oracle for negative cases', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, 'sha256:graph');
    const negative = generateScenarioPreset({
      sequence: 2,
      caseType: 'NEGATIVE',
      graphDraft: draft,
      contract,
      nodes: nodes(),
    });
    const boundary = generateScenarioPreset({
      sequence: 3,
      caseType: 'BOUNDARY',
      graphDraft: draft,
      contract,
      nodes: nodes(),
    });

    expect(negative.tags).toContain('needs-oracle');
    expect(negative.then.assertions).toEqual([]);
    expect(negative.given.input).toMatchObject({ applicantId: 'applicant-blocked' });
    expect(boundary.given.input).toMatchObject({ profile: { age: 18 } });
    expect(boundary.then.assertions).toHaveLength(1);
  });

  it('creates the four high-value starting intents as one deterministic suite', () => {
    const draft = graphDraft();
    const suite = generateScenarioPresetSuite({
      graphDraft: draft,
      contract: contractDraftFromGraphDraft(draft, 'sha256:graph'),
      nodes: nodes(),
    }, 7);

    expect(suite.map((scenario) => scenario.caseType))
      .toEqual(['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION']);
    expect(suite.map((scenario) => scenario.scenarioId))
      .toEqual(['scenario-7', 'scenario-8', 'scenario-9', 'scenario-10']);
  });
});
