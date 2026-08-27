// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ToolPaletteFacets from './ToolPaletteFacets';
import type { OperatorDefinition } from '../types';

const operators = [
  { operatorRef: 'resource:orders.lookup' },
  { operatorRef: 'built-in:string.concat' },
  { operatorRef: 'publication:loan-tool-v1' },
] as unknown as OperatorDefinition[];

describe('ToolPaletteFacets', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => act(() => root.unmount()));

  it('separates composition-ready publications from external resources', () => {
    act(() => root.render(<ToolPaletteFacets operators={operators} />));
    expect(host.textContent).toContain('Published tools');
    expect(host.querySelectorAll('li')).toHaveLength(3);
    click('[data-testid="tool-palette-publication"]');
    expect(host.querySelectorAll('li')).toHaveLength(1);
    expect(host.textContent).toContain('publication:loan-tool-v1');
    click('[data-testid="tool-palette-resource"]');
    expect(host.textContent).toContain('resource:orders.lookup');
    expect(host.querySelectorAll('li')).toHaveLength(1);
  });

  it('keeps add actions out of non-composable built-ins', () => {
    const addOperator = vi.fn();
    act(() => root.render(<ToolPaletteFacets operators={operators} onAddOperator={addOperator} />));
    click('[data-testid="tool-palette-publication"]');
    host.querySelector<HTMLButtonElement>('li button')?.click();
    expect(addOperator).toHaveBeenCalledWith('publication:loan-tool-v1');
    expect(host.querySelectorAll('li button')).toHaveLength(1);
  });

  it('keeps frozen publication port information visible while preserving its add ref', () => {
    const publication = {
      operatorRef: 'publication:loan-tool-v1',
      display: { name: 'Loan tool' },
      ports: {
        inputs: [{ name: 'request', schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'result', schema: { schema: { type: 'string' } } }],
      },
    } as unknown as OperatorDefinition;
    const addOperator = vi.fn();
    act(() => root.render(<ToolPaletteFacets operators={[publication]} onAddOperator={addOperator} />));
    click('[data-testid="tool-palette-publication"]');
    expect(host.textContent).toContain('Loan tool');
    expect(host.textContent).toContain('request');
    expect(host.textContent).toContain('result');
    expect(host.textContent).toContain('request: object');
    expect(host.textContent).toContain('result: string');
    host.querySelector<HTMLButtonElement>('li button')?.click();
    expect(addOperator).toHaveBeenCalledWith('publication:loan-tool-v1');
  });

  function click(selector: string): void {
    act(() => host.querySelector<HTMLButtonElement>(selector)?.click());
  }
});
