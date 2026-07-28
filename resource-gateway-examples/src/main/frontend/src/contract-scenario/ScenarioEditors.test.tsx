// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AssertionBuilder from './AssertionBuilder';
import DependencyBehaviorEditor from './DependencyBehaviorEditor';
import { contractDraftFromGraphDraft } from './domain';
import type { AssertionDraft, DependencyBehaviorDraft } from './domain';
import { scenarioDraftSetFromCanvas } from './scenarioAuthoring';
import { graphDraft, nodes } from './testFixtures';

describe('Scenario graphical editors', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
  });

  it('edits failure, timeout, and built-in function behavior without raw JSON', async () => {
    const changes = vi.fn();
    const dependency = initialDependency();

    function ControlledEditor() {
      const [value, setValue] = useState(dependency);
      return (
        <DependencyBehaviorEditor
          dependency={value}
          nodes={nodes()}
          onChange={(next) => {
            changes(next);
            setValue(next);
          }}
        />
      );
    }

    await render(<ControlledEditor />);
    await click('Error');
    expect(input(`Error code for ${dependency.dependencyId}`).value)
      .toBe('SCENARIO_DEPENDENCY_ERROR');

    await click('Timeout');
    await change(input(`Duration for ${dependency.dependencyId}`), '250');
    expect(lastDependency(changes).behavior).toMatchObject({
      kind: 'TIMEOUT',
      after: 'PT0.25S',
      errorCode: 'SCENARIO_TIMEOUT',
    });

    await select(selectElement(`Selector kind for ${dependency.dependencyId}`), 'FUNCTION');
    await change(input(`Function reference for ${dependency.dependencyId}`), 'money.round');

    expect(lastDependency(changes).selector).toMatchObject({
      nodeId: '',
      operatorRef: '',
      resourceRef: '',
      functionRef: 'money.round',
    });
    expect(document.querySelector('details')?.open).toBe(false);
  });

  it('switches node, edge, and invocation assertions with valid scope coordinates', async () => {
    const changes = vi.fn();
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const dependencies = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    ).scenarios[0].dependencies;
    const initial: AssertionDraft = {
      assertionId: 'assertion-1',
      scope: 'OUTPUT_PATH',
      nodeId: '',
      fromNodeId: '',
      toNodeId: '',
      path: '',
      operator: 'EQUALS',
      expected: {},
    };

    function ControlledBuilder() {
      const [value, setValue] = useState(initial);
      return (
        <AssertionBuilder
          assertion={value}
          contract={contract}
          nodes={nodes()}
          dependencies={dependencies}
          onChange={(next) => {
            changes(next);
            setValue(next);
          }}
          onRemove={vi.fn()}
        />
      );
    }

    await render(<ControlledBuilder />);
    expect(options('Assertion path for assertion-1')).toEqual([
      '',
      'decision',
      'decision.approved',
      'decision.reason',
      '__custom__',
    ]);
    await select(selectElement('Assertion path for assertion-1'), 'decision.approved');
    expect(lastAssertion(changes)).toMatchObject({
      path: 'decision.approved',
      expected: false,
    });

    await select(selectElement('Assertion scope for assertion-1'), 'NODE_STATUS');
    expect(lastAssertion(changes)).toMatchObject({
      scope: 'NODE_STATUS',
      nodeId: 'score',
      operator: 'STATUS',
      expected: 'SUCCESS',
    });

    await select(selectElement('Assertion scope for assertion-1'), 'EDGE_TRANSFER');
    expect(lastAssertion(changes)).toMatchObject({
      scope: 'EDGE_TRANSFER',
      fromNodeId: 'score',
      toNodeId: 'decide',
      operator: 'USED',
    });

    await select(selectElement('Assertion scope for assertion-1'), 'INVOCATION');
    expect(lastAssertion(changes)).toMatchObject({
      scope: 'INVOCATION',
      nodeId: 'score-behavior',
      operator: 'USED',
      expected: 1,
    });
    expect(options('Assertion operator for assertion-1')).toEqual(['USED', 'NOT_USED']);
  });

  async function render(element: React.ReactNode) {
    await act(async () => {
      root = createRoot(host);
      root.render(element);
    });
  }
});

function initialDependency(): DependencyBehaviorDraft {
  const draft = graphDraft();
  const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
  return scenarioDraftSetFromCanvas(
    contract.target,
    fingerprint('b'),
    draft,
    nodes(),
    [],
  ).scenarios[0].dependencies[0];
}

async function click(label: string) {
  const candidate = Array.from(document.querySelectorAll('button'))
    .find((entry) => entry.textContent?.trim() === label);
  if (!(candidate instanceof HTMLButtonElement)) throw new Error(`Missing button: ${label}`);
  await act(async () => candidate.click());
}

async function change(control: HTMLInputElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(control, value);
    control.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function select(control: HTMLSelectElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(control, value);
    control.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

function input(label: string): HTMLInputElement {
  const candidate = document.querySelector(`[aria-label="${label}"]`);
  if (!(candidate instanceof HTMLInputElement)) throw new Error(`Missing input: ${label}`);
  return candidate;
}

function selectElement(label: string): HTMLSelectElement {
  const candidate = document.querySelector(`select[aria-label="${label}"]`);
  if (!(candidate instanceof HTMLSelectElement)) throw new Error(`Missing select: ${label}`);
  return candidate;
}

function options(label: string): string[] {
  return Array.from(selectElement(label).options).map((option) => option.value);
}

function lastDependency(changes: ReturnType<typeof vi.fn>): DependencyBehaviorDraft {
  return changes.mock.lastCall?.[0] as DependencyBehaviorDraft;
}

function lastAssertion(changes: ReturnType<typeof vi.fn>): AssertionDraft {
  return changes.mock.lastCall?.[0] as AssertionDraft;
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
