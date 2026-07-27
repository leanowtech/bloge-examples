// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { contractDraftFromGraphDraft } from './domain';
import ContractScenarioWorkspace from './ContractScenarioWorkspace';
import { scenarioDraftSetFromCanvas } from './scenarioAuthoring';
import {
  graphDraft,
  nodes,
  successfulResponse,
} from './testFixtures';

describe('ContractScenarioWorkspace', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
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

  it('opens with a searchable Contract field tree and four workspace views', async () => {
    await renderWorkspace();

    expect(text()).toContain('Graph Contract');
    expect(text()).toContain('applicantId');
    expect(text()).toContain('approved');
    expect(tabs()).toEqual(['Interface', 'Scenarios 1', 'Compatibility', 'Run Evidence']);
    expect(button('Load Scenario').disabled).toBe(false);
    expect(button('Save Scenario').disabled).toBe(false);
    expect(button('Publish').disabled).toBe(true);
  });

  it('blocks Scenario lifecycle actions until the Graph has an exact stored revision', async () => {
    await renderWorkspace({ unsaved: true });

    expect(button('Save Graph').disabled).toBe(false);
    expect(button('Load Scenario').disabled).toBe(true);
    expect(button('Save Scenario').disabled).toBe(true);
    expect(button('Publish').disabled).toBe(true);
  });

  it('edits Given graph input through native form controls', async () => {
    const onChange = vi.fn();
    await renderWorkspace({ onChange });
    await clickTab('Scenarios 1');
    const applicant = input('applicantId');

    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(applicant, 'A-42');
      applicant.dispatchEvent(new Event('input', { bubbles: true }));
    });

    expect(onChange).toHaveBeenCalled();
    const latest = onChange.mock.calls[onChange.mock.calls.length - 1]?.[0];
    expect(latest.scenarios[0].given.input.applicantId).toBe('A-42');
  });

  it('keeps Advanced JSON synchronized after graphical edits', async () => {
    await renderControlledWorkspace();
    await clickTab('Scenarios 1');
    const applicant = input('applicantId');

    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(applicant, 'A-99');
      applicant.dispatchEvent(new Event('input', { bubbles: true }));
    });

    expect(textarea('Advanced Scenario JSON').value).toContain('"applicantId": "A-99"');
  });

  it('compiles, runs, and presents assertion evidence', async () => {
    const onRun = vi.fn().mockResolvedValue(successfulResponse());
    await renderWorkspace({ onRun });
    await clickTab('Scenarios 1');

    await act(async () => {
      button('Run & Compare').click();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onRun).toHaveBeenCalledWith(expect.objectContaining({
      context: expect.objectContaining({ applicantId: '' }),
      outputNode: 'decide',
    }));
    expect(text()).toContain('All assertions passed');
    expect(text()).toContain('Terminal output');
    expect(text()).toContain('eligible');
  });

  it('exposes stale coordinates and delegates explicit rebase', async () => {
    const onRebase = vi.fn();
    await renderWorkspace({ stale: true, onRebase });

    expect(text()).toContain('Scenarios target an older graph or Contract.');
    await act(async () => button('Rebase scenarios').click());
    expect(onRebase).toHaveBeenCalledOnce();
  });

  async function renderWorkspace(options: {
    stale?: boolean;
    unsaved?: boolean;
    onChange?: ReturnType<typeof vi.fn>;
    onRebase?: ReturnType<typeof vi.fn>;
    onRun?: ReturnType<typeof vi.fn>;
  } = {}) {
    const draft = options.unsaved
      ? { ...graphDraft(), draftId: '', revision: 0 }
      : graphDraft();
    const targetFingerprint = fingerprint('a');
    const contractFingerprint = fingerprint('b');
    const contract = contractDraftFromGraphDraft(draft, targetFingerprint);
    const draftSet = scenarioDraftSetFromCanvas(
      options.stale ? { ...contract.target, fingerprint: fingerprint('c') } : contract.target,
      contractFingerprint,
      draft,
      nodes(),
      [{
        id: 'approved',
        name: 'Approved applicant',
        context: { applicantId: '', profile: { age: 18, tags: [] } },
        fixtures: {
          score: { output: { score: 720 } },
          decide: { output: successfulResponse().output },
        },
        hasExpectedOutput: true,
        expectedOutput: successfulResponse().output,
      }],
    );
    await act(async () => {
      root = createRoot(host);
      root.render(
        <ContractScenarioWorkspace
          open
          graphDraft={draft}
          contract={contract}
          contractFingerprint={contractFingerprint}
          scenarioDraftSet={draftSet}
          nodes={nodes()}
          lastRun={null}
          onScenarioDraftSetChange={options.onChange ?? vi.fn()}
          onContractChange={vi.fn()}
          onImportWorkspace={vi.fn().mockResolvedValue(undefined)}
          onSaveGraphDraft={vi.fn().mockResolvedValue(undefined)}
          onRebase={options.onRebase ?? vi.fn()}
          onRun={options.onRun ?? vi.fn().mockResolvedValue(successfulResponse())}
          onClose={vi.fn()}
        />,
      );
    });
  }

  async function renderControlledWorkspace() {
    const draft = graphDraft();
    const targetFingerprint = fingerprint('a');
    const contractFingerprint = fingerprint('b');
    const contract = contractDraftFromGraphDraft(draft, targetFingerprint);
    const initialDraftSet = scenarioDraftSetFromCanvas(
      contract.target,
      contractFingerprint,
      draft,
      nodes(),
      [{
        id: 'approved',
        name: 'Approved applicant',
        context: { applicantId: '', profile: { age: 18, tags: [] } },
        fixtures: {},
        hasExpectedOutput: true,
        expectedOutput: successfulResponse().output,
      }],
    );

    function ControlledWorkspace() {
      const [draftSet, setDraftSet] = useState(initialDraftSet);
      return (
        <ContractScenarioWorkspace
          open
          graphDraft={draft}
          contract={contract}
          contractFingerprint={contractFingerprint}
          scenarioDraftSet={draftSet}
          nodes={nodes()}
          lastRun={null}
          onScenarioDraftSetChange={setDraftSet}
          onContractChange={vi.fn()}
          onImportWorkspace={vi.fn().mockResolvedValue(undefined)}
          onSaveGraphDraft={vi.fn().mockResolvedValue(undefined)}
          onRebase={vi.fn()}
          onRun={vi.fn().mockResolvedValue(successfulResponse())}
          onClose={vi.fn()}
        />
      );
    }

    await act(async () => {
      root = createRoot(host);
      root.render(<ControlledWorkspace />);
    });
  }
});

function tabs(): string[] {
  return Array.from(document.querySelectorAll('[role="tab"]'))
    .map((entry) => entry.textContent?.trim() ?? '');
}

async function clickTab(label: string) {
  await act(async () => button(label).click());
}

function button(label: string): HTMLButtonElement {
  const candidate = Array.from(document.querySelectorAll('button'))
    .find((entry) => entry.textContent?.trim() === label);
  if (!(candidate instanceof HTMLButtonElement)) {
    throw new Error(`Missing button: ${label}`);
  }
  return candidate;
}

function input(label: string): HTMLInputElement {
  const candidate = document.querySelector(`[aria-label="${label}"]`);
  if (!(candidate instanceof HTMLInputElement)) {
    throw new Error(`Missing input: ${label}`);
  }
  return candidate;
}

function textarea(label: string): HTMLTextAreaElement {
  const candidate = document.querySelector(`textarea[aria-label="${label}"]`);
  if (!(candidate instanceof HTMLTextAreaElement)) {
    throw new Error(`Missing textarea: ${label}`);
  }
  return candidate;
}

function text(): string {
  return document.body.textContent ?? '';
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
