// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import SchemaValueEditor from './SchemaValueEditor';

describe('SchemaValueEditor', () => {
  let host: HTMLDivElement;
  let root: Root | null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    host.remove();
  });

  it('uses the visual schema path by default and round-trips Advanced JSON explicitly', async () => {
    const changes = vi.fn();
    function Controlled() {
      const [value, setValue] = useState<unknown>({ name: 'before' });
      return (
        <SchemaValueEditor
          schema={{
            type: 'object',
            properties: { name: { type: 'string' } },
            required: ['name'],
          }}
          value={value}
          onChange={(next) => {
            changes(next);
            setValue(next);
          }}
          label="Input"
        />
      );
    }
    await act(async () => {
      root = createRoot(host);
      root.render(<Controlled />);
    });

    expect(document.querySelector('[aria-label="name"]')).toBeInstanceOf(HTMLInputElement);
    const details = document.querySelector('.shared-schema-value-advanced');
    expect(details).toBeInstanceOf(HTMLDetailsElement);
    expect((details as HTMLDetailsElement).open).toBe(false);

    const textarea = document.querySelector('[aria-label="Input advanced JSON"]');
    expect(textarea).toBeInstanceOf(HTMLTextAreaElement);
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')
        ?.set?.call(textarea, '{"name":"after"}');
      textarea?.dispatchEvent(new Event('input', { bubbles: true }));
    });
    const apply = Array.from(document.querySelectorAll('button'))
      .find((button) => button.textContent === 'Apply valid JSON');
    await act(async () => apply?.click());

    expect(changes).toHaveBeenLastCalledWith({ name: 'after' });
  });

  it('keeps object, array, enum, nullable, and union values on the visual path', async () => {
    const changes = vi.fn();
    function Controlled() {
      const [value, setValue] = useState<unknown>({
        mode: 'FAST',
        tags: ['first'],
        comment: null,
        target: { email: 'a@example.test' },
      });
      return (
        <SchemaValueEditor
          schema={{
            type: 'object',
            properties: {
              mode: { type: 'string', enum: ['FAST', 'SAFE'] },
              tags: { type: 'array', items: { type: 'string' } },
              comment: { type: ['string', 'null'] },
              target: {
                oneOf: [
                  {
                    title: 'Email target',
                    type: 'object',
                    properties: { email: { type: 'string' } },
                    required: ['email'],
                  },
                  {
                    title: 'Queue target',
                    type: 'object',
                    properties: { queueId: { type: 'integer' } },
                    required: ['queueId'],
                  },
                ],
              },
            },
          }}
          value={value}
          onChange={(next) => {
            changes(next);
            setValue(next);
          }}
          label="Input"
        />
      );
    }
    await act(async () => {
      root = createRoot(host);
      root.render(<Controlled />);
    });

    expect(host.querySelector('[aria-label="mode"]')).toBeInstanceOf(HTMLSelectElement);
    expect(host.querySelector('[aria-label="tags"]')).toBeNull();
    expect(host.textContent).toContain('1 items');
    expect(host.querySelector('[aria-label="comment is null"]')).toBeInstanceOf(HTMLInputElement);
    expect(host.querySelector('[aria-label="target variant"]')).toBeInstanceOf(HTMLSelectElement);
    expect(host.querySelector('[aria-label="target.email"]')).toBeInstanceOf(HTMLInputElement);

    await changeSelect(
      host.querySelector<HTMLSelectElement>('[aria-label="target variant"]'),
      '1',
    );
    expect(changes).toHaveBeenLastCalledWith(expect.objectContaining({
      target: { queueId: 0 },
    }));
    expect(host.querySelector('[aria-label="target.queueId"]')).toBeInstanceOf(HTMLInputElement);

    await act(async () => {
      host.querySelector<HTMLInputElement>('[aria-label="comment is null"]')?.click();
    });
    expect(changes).toHaveBeenLastCalledWith(expect.objectContaining({ comment: '' }));
  });
});

async function changeSelect(select: HTMLSelectElement | null, value: string) {
  if (!select) throw new Error('Missing select');
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')
      ?.set?.call(select, value);
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
}
