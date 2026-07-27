import type { ContractDraft, ScenarioDraftSet } from './domain';
import { scenarioSetIsCurrent } from './scenarioAuthoring';

interface ContractRailProps {
  source: string;
  contract: ContractDraft | null;
  contractFingerprint: string;
  scenarioDraftSet: ScenarioDraftSet | null;
  inputFieldCount: number;
  outputFieldCount: number;
  inputFields: string[];
  outputFields: string[];
  onOpen: () => void;
}

/** Compact always-visible entry point from the canvas into Contract and Scenario authoring. */
export default function ContractRail({
  source,
  contract,
  contractFingerprint,
  scenarioDraftSet,
  inputFieldCount,
  outputFieldCount,
  inputFields,
  outputFields,
  onOpen,
}: ContractRailProps) {
  const current = Boolean(
    contract
      && scenarioDraftSet
      && scenarioSetIsCurrent(
        scenarioDraftSet,
        contract.target.fingerprint,
        contractFingerprint,
      ),
  );
  const status = !contract
    ? 'Indexing'
    : !scenarioDraftSet
      ? 'No scenarios'
      : current
        ? 'Current'
        : 'Review changes';

  return (
    <section className="contract-rail" aria-label="Graph contract" data-testid="author-graph-contract">
      <button
        type="button"
        className="contract-rail-open"
        onClick={onOpen}
        disabled={!contract}
        data-testid="contract-workspace-open"
      >
        <span className="contract-rail-title">
          <small>Graph Contract</small>
          <strong>{source}</strong>
        </span>
        <span className="contract-rail-stat">
          <small>Input {inputFieldCount} fields</small>
          <strong>ctx</strong>
          <span>{fieldPreview(inputFields)}</span>
        </span>
        <span className="contract-rail-arrow" aria-hidden="true">→</span>
        <span className="contract-rail-stat">
          <small>Output {outputFieldCount} fields</small>
          <strong>public result</strong>
          <span>{fieldPreview(outputFields)}</span>
        </span>
        <span className={`contract-rail-status ${current ? 'current' : 'review'}`}>
          {status}
        </span>
        <span className="contract-rail-action">Open workspace</span>
      </button>
    </section>
  );
}

function fieldPreview(fields: string[]): string {
  return fields.length > 0 ? fields.join(', ') : 'open object';
}
