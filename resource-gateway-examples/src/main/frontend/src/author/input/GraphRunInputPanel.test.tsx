// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { SchemaEnvelope } from '../../types';
import GraphRunInputPanel from './GraphRunInputPanel';

const inputSchema: SchemaEnvelope = {
  schema: {
    type: 'object',
    required: ['requestId', 'tier'],
    properties: {
      requestId: { type: 'string', minLength: 1 },
      tier: { type: 'string', enum: ['standard', 'priority'] },
      secret: { type: 'string', writeOnly: true },
    },
  },
};

describe('GraphRunInputPanel', () => {
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

  it('renders contract-generated controls, exact readiness, masking, and direct binding', async () => {
    const bind = vi.fn();

    function Controlled() {
      const [value, setValue] = useState<Record<string, unknown>>({});
      return (
        <GraphRunInputPanel
          inputSchema={inputSchema}
          value={value}
          selectedNodeLabel="Risk decision"
          onChange={setValue}
          onBind={bind}
          onOpenContract={vi.fn()}
        />
      );
    }

    await act(async () => {
      root = createRoot(host);
      root.render(<Controlled />);
    });

    expect(text('[data-testid="run-input-readiness"]')).toContain('2 required, 2 missing');
    expect(input('secret').type).toBe('password');
    await change(input('requestId'), 'req-7');
    await select('tier', JSON.stringify('priority'));
    expect(text('[data-testid="run-input-readiness"]')).toContain('2 required, complete');

    const bindButton = host.querySelector('[data-testid="graph-input-bind:requestId"]');
    expect(bindButton).toBeInstanceOf(HTMLButtonElement);
    await act(async () => (bindButton as HTMLButtonElement).click());
    expect(bind).toHaveBeenCalledWith('requestId');
  });
});

function input(label: string): HTMLInputElement {
  const control = document.querySelector(`input[aria-label="${label}"]`);
  if (!(control instanceof HTMLInputElement)) throw new Error(`Missing input: ${label}`);
  return control;
}

async function change(control: HTMLInputElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(control, value);
    control.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function select(label: string, value: string) {
  const control = document.querySelector(`select[aria-label="${label}"]`);
  if (!(control instanceof HTMLSelectElement)) throw new Error(`Missing select: ${label}`);
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(control, value);
    control.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

function text(selector: string): string {
  return document.querySelector(selector)?.textContent ?? '';
}
