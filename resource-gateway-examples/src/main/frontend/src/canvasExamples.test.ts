import { describe, expect, it } from 'vitest';

import {
  CANVAS_EXAMPLE_TEMPLATES,
  exampleIncompatibleContractPaths,
} from './canvasExamples';
import type { OperatorDefinition } from './types';

describe('complete canvas example seeds', () => {
  it('ship a meaningful golden, negative, and boundary path for every graph', () => {
    expect(CANVAS_EXAMPLE_TEMPLATES).toHaveLength(3);
    for (const template of CANVAS_EXAMPLE_TEMPLATES) {
      expect(template.testCases?.map((testCase) => testCase.caseType)).toEqual([
        'GOLDEN',
        'NEGATIVE',
        'BOUNDARY',
      ]);
      expect(template.testCases).toHaveLength(3);
      expect(template.testCases?.every((testCase) => (
        testCase.name.length >= 12
        && Object.keys(testCase.context).length > 0
        && testCase.expectedOutput !== null
      ))).toBe(true);
    }
  });

  it('uses explicit business values instead of schema placeholders', () => {
    const serialized = JSON.stringify(CANVAS_EXAMPLE_TEMPLATES.flatMap(
      (template) => template.testCases ?? [],
    ));
    expect(serialized).not.toContain('"string"');
    expect(serialized).toContain('manual_review');
    expect(serialized).toContain('zero notification boundary');
  });

  it('detects contract drift before loading an example that depends on removed fields', () => {
    const loanExample = CANVAS_EXAMPLE_TEMPLATES[0];
    const operators = new Map<string, OperatorDefinition>([
      ['resource:loan-applicant-service.getProfile', {
        operatorRef: 'resource:loan-applicant-service.getProfile',
        ports: {
          inputs: [{
            name: 'params',
            schema: { schema: { type: 'object', properties: { applicantId: { type: 'string' } } } },
          }],
          outputs: [{
            name: 'payload',
            schema: {
              schema: {
                type: 'object',
                properties: {
                  applicantId: { type: 'string' },
                  score: { type: 'integer' },
                  income: { type: 'number' },
                  employmentYears: { type: 'number' },
                },
              },
            },
          }],
        },
      }],
      ['bloge:decisionTable', {
        operatorRef: 'bloge:decisionTable',
        ports: {
          inputs: [{
            name: 'inputs',
            schema: { schema: { type: 'object', additionalProperties: true } },
          }],
          outputs: [{
            name: 'output',
            schema: { schema: { type: 'object', additionalProperties: true } },
          }],
        },
      }],
      ['bloge:transform', {
        operatorRef: 'bloge:transform',
        ports: {
          inputs: [{
            name: 'inputs',
            schema: { schema: { type: 'object', additionalProperties: true } },
          }],
          outputs: [{
            name: 'output',
            schema: { schema: { type: 'object', additionalProperties: true } },
          }],
        },
      }],
    ]);

    expect(exampleIncompatibleContractPaths(loanExample, operators)).toEqual([
      'Fetch applicant.payload.segment',
    ]);
  });
});
