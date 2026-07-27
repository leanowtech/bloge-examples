// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';

import { contractDraftFromGraphDraft } from './domain';
import { canonicalJson, sha256Fingerprint } from './fingerprint';
import SchemaValueForm from './SchemaValueForm';
import {
  compareScenarioRun,
  rebaseScenarioDraftSet,
  scenarioDraftSetFromCanvas,
  scenarioSetIsCurrent,
} from './scenarioAuthoring';
import {
  projectSchemaFields,
  schemaAtPath,
  valueAtPath,
  withValueAtPath,
} from './schemaWorkbench';
import {
  graphDraft,
  inputSchema,
  nodes,
  outputSchema,
  successfulResponse,
} from './testFixtures';

describe('Contract Scenario authoring workbench', () => {
  it('canonicalizes key order and produces stable SHA-256 coordinates', async () => {
    expect(canonicalJson({ z: 2, a: { y: true, x: 1 } }))
      .toBe('{"a":{"x":1,"y":true},"z":2}');
    const left = await sha256Fingerprint({ z: 2, a: 1 });
    const right = await sha256Fingerprint({ a: 1, z: 2 });

    expect(left).toBe(right);
    expect(left).toMatch(/^sha256:[0-9a-f]{64}$/);
  });

  it('rejects cyclic values before hashing', () => {
    const value: Record<string, unknown> = {};
    value.self = value;
    expect(() => canonicalJson(value)).toThrow('cyclic');
  });

  it('projects nested object and array fields with required metadata', () => {
    const rows = projectSchemaFields(inputSchema());

    expect(rows.map((row) => [row.path, row.type, row.required])).toEqual([
      ['applicantId', 'string', true],
      ['profile', 'object', true],
      ['profile.age', 'integer', true],
      ['profile.tags', 'array', false],
      ['profile.tags[]', 'string', false],
    ]);
  });

  it('reads and immutably writes dotted and pointer paths', () => {
    const original = { profile: { age: 36, active: true } };

    expect(valueAtPath(original, 'profile.age')).toBe(36);
    expect(valueAtPath(original, '/profile/active')).toBe(true);
    expect(withValueAtPath(original, 'profile.age', 40)).toEqual({
      profile: { age: 40, active: true },
    });
    expect(original.profile.age).toBe(36);
  });

  it('locates the closest schema for expected output path forms', () => {
    expect(schemaAtPath(outputSchema(), 'decision.approved').type).toBe('boolean');
    expect(schemaAtPath(outputSchema(), '/decision/reason').type).toBe('string');
    expect(schemaAtPath(outputSchema(), 'missing')).toEqual({});
  });

  it('projects existing table cases into Given, dependencies, and Then', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const draftSet = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [{
        id: 'approved',
        name: 'Approved applicant',
        context: { applicantId: 'A-1', profile: { age: 36, tags: [] } },
        fixtures: { score: { output: { score: 720 } } },
        hasExpectedOutput: true,
        expectedOutput: { decision: { approved: true, reason: 'eligible' } },
      }],
    );

    expect(draftSet.scenarios).toHaveLength(1);
    expect(draftSet.scenarios[0].given.input).toMatchObject({ applicantId: 'A-1' });
    expect(draftSet.scenarios[0].dependencies.map((entry) => entry.behavior.kind))
      .toEqual(['RETURN', 'REAL']);
    expect(draftSet.scenarios[0].then.assertions[0].expected)
      .toEqual({ decision: { approved: true, reason: 'eligible' } });
  });

  it('makes rebase explicit and records the previous target coordinate', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const draftSet = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    );
    const nextTarget = { ...contract.target, revision: 3, fingerprint: fingerprint('c') };
    const rebased = rebaseScenarioDraftSet(draftSet, nextTarget, fingerprint('d'));

    expect(scenarioSetIsCurrent(draftSet, fingerprint('c'), fingerprint('d'))).toBe(false);
    expect(scenarioSetIsCurrent(rebased, fingerprint('c'), fingerprint('d'))).toBe(true);
    expect(rebased.metadata.provenance.rebasedFromTargetFingerprint).toBe(fingerprint('a'));
  });

  it('compares whole output and path equality with actionable results', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const draftSet = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    );
    const scenario = {
      ...draftSet.scenarios[0],
      then: {
        assertions: [
          assertion('whole', '', { decision: { approved: true, reason: 'eligible' } }),
          assertion('reason', 'decision.reason', 'eligible'),
          assertion('wrong', '/decision/approved', false),
        ],
      },
    };
    const comparison = compareScenarioRun(scenario, successfulResponse());

    expect(comparison.passed).toBe(false);
    expect(comparison.results.map((entry) => entry.passed)).toEqual([true, true, false]);
    expect(comparison.results[2].actual).toBe(true);
  });

  it('blocks comparison success when the underlying graph run fails', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const scenario = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    ).scenarios[0];
    const response = {
      ...successfulResponse(),
      success: false,
      errors: ['score timeout'],
    };

    expect(compareScenarioRun(scenario, response)).toMatchObject({
      passed: false,
      diagnostics: [{ code: 'visual.scenario.run.failed', message: 'score timeout' }],
    });
  });

  it('preserves an incomplete open JSON draft until it becomes valid', async () => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    const host = document.createElement('div');
    document.body.appendChild(host);
    const root = createRoot(host);
    const onChange = vi.fn();
    await act(async () => root.render(
      <SchemaValueForm
        schema={{ type: 'object' }}
        value={{ existing: true }}
        onChange={onChange}
        label="Open value"
      />,
    ));
    const editor = host.querySelector('textarea');
    expect(editor).toBeInstanceOf(HTMLTextAreaElement);

    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(editor, '{"unfinished":');
      editor?.dispatchEvent(new Event('input', { bubbles: true }));
    });

    expect(editor?.value).toBe('{"unfinished":');
    expect(onChange).not.toHaveBeenCalled();
    await act(async () => root.unmount());
    host.remove();
  });
});

function assertion(assertionId: string, path: string, expected: unknown) {
  return {
    assertionId,
    scope: 'OUTPUT_PATH' as const,
    nodeId: '',
    fromNodeId: '',
    toNodeId: '',
    path,
    operator: 'EQUALS' as const,
    expected,
  };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
