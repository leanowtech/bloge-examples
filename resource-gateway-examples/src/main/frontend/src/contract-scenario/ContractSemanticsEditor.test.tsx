// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ContractSemanticsEditor from './ContractSemanticsEditor';
import { contractDraftFromGraphDraft } from './domain';
import type { ContractDraft } from './domain';
import { graphDraft } from './testFixtures';

describe('ContractSemanticsEditor', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    root = null;
    host.remove();
  });

  it('authors write guarantees, stable errors, and invariants without raw JSON', async () => {
    const changes = vi.fn();
    const initial = contractDraftFromGraphDraft(graphDraft(), fingerprint('a'));

    function Controlled() {
      const [contract, setContract] = useState(initial);
      return (
        <ContractSemanticsEditor
          contract={contract}
          onChange={(next) => {
            changes(next);
            setContract(next);
          }}
        />
      );
    }

    await act(async () => {
      root = createRoot(host);
      root.render(<Controlled />);
    });
    await select('Contract effect', 'WRITE');
    expect(input('Side effect protocol').value).toBe('');
    await change(input('Contract idempotency'), 'IDEMPOTENCY_KEY:/requestId');
    await change(input('Side effect protocol'), 'bloge.sideEffectProtocol.v1');
    await change(input('Side effect reconciler'), 'crm.reconcile');

    await click('Stable errors', 'Add');
    await change(input('Error code 1'), 'CRM_UNAVAILABLE');
    await check(input('Error retryable 1'));

    await click('Contract invariants', 'Add');
    await change(input('Invariant id 1'), 'request-id-required');
    await change(input('Invariant expression 1'), 'exists(ctx.requestId)');

    expect(lastContract(changes)).toMatchObject({
      executionSemantics: {
        effect: 'WRITE',
        idempotency: 'IDEMPOTENCY_KEY:/requestId',
        sideEffectProtocol: {
          protocol: 'bloge.sideEffectProtocol.v1',
          reconcilerRef: 'crm.reconcile',
        },
      },
      errorContract: [{
        code: 'CRM_UNAVAILABLE',
        retryable: true,
      }],
      invariants: [{
        invariantId: 'request-id-required',
        expression: 'exists(ctx.requestId)',
      }],
    });
  });
});

async function select(label: string, value: string) {
  const control = document.querySelector(`select[aria-label="${label}"]`);
  if (!(control instanceof HTMLSelectElement)) throw new Error(`Missing select: ${label}`);
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(control, value);
    control.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

async function change(control: HTMLInputElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(control, value);
    control.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function check(control: HTMLInputElement) {
  await act(async () => {
    control.click();
  });
}

async function click(sectionTitle: string, buttonLabel: string) {
  const section = Array.from(document.querySelectorAll('section'))
    .find((candidate) => candidate.querySelector('h3')?.textContent === sectionTitle);
  const button = Array.from(section?.querySelectorAll('button') ?? [])
    .find((candidate) => candidate.textContent?.trim() === buttonLabel);
  if (!(button instanceof HTMLButtonElement)) {
    throw new Error(`Missing ${buttonLabel} in ${sectionTitle}`);
  }
  await act(async () => button.click());
}

function input(label: string): HTMLInputElement {
  const control = document.querySelector(`input[aria-label="${label}"]`);
  if (!(control instanceof HTMLInputElement)) throw new Error(`Missing input: ${label}`);
  return control;
}

function lastContract(changes: ReturnType<typeof vi.fn>): ContractDraft {
  return changes.mock.lastCall?.[0] as ContractDraft;
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
