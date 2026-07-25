import type {
  ScenarioRehearsalRemediationComparison,
} from './types';

interface RehearsalRemediationComparisonProps {
  comparison: ScenarioRehearsalRemediationComparison;
}

function statusTone(value: string): string {
  if (value === 'RESOLVED' || value === 'STILL_READY') {
    return 'success';
  }
  if (value === 'REGRESSED') {
    return 'danger';
  }
  if (value === 'STILL_BLOCKED') {
    return 'warning';
  }
  return 'neutral';
}

/** Truth-preserving before/after view over two independently verified root-signed workbooks. */
export default function RehearsalRemediationComparison({
  comparison,
}: RehearsalRemediationComparisonProps) {
  return (
    <div className="remediation-comparison" data-testid="remediation-comparison">
      <div className="remediation-section-heading">
        <div>
          <h4>Signed evidence comparison</h4>
          <p>No synthetic score: every transition is derived from two independently verified workbooks.</p>
        </div>
        <span className={`status-label ${statusTone(comparison.gateTransition)}`}>
          {comparison.gateTransition.replace(/_/g, ' ')}
        </span>
      </div>
      <div className="comparison-workbooks">
        <ComparisonWorkbook label="Predecessor" snapshot={comparison.predecessor} />
        <span className="comparison-arrow" aria-hidden="true">→</span>
        <ComparisonWorkbook label="Successor" snapshot={comparison.successor} />
      </div>
      <div className="blocker-diff">
        <BlockerGroup label="Resolved" values={comparison.resolvedBlockers} tone="success" />
        <BlockerGroup label="Remaining" values={comparison.remainingBlockers} tone="warning" />
        <BlockerGroup label="Introduced" values={comparison.introducedBlockers} tone="danger" />
      </div>
      <div className="comparison-entry-table">
        <div className="comparison-entry-head">
          <span>Entry</span>
          <span>Plan</span>
          <span>Gate transition</span>
          <span>Blocker movement</span>
        </div>
        {comparison.entries.map((entry) => (
          <div className="comparison-entry-row" key={entry.entryId}>
            <strong>#{entry.entryIndex} {entry.entryId}</strong>
            <span>{entry.planChanged ? 'Changed' : 'Unchanged'}</span>
            <span className={`status-label ${statusTone(entry.gateTransition)}`}>
              {entry.gateTransition.replace(/_/g, ' ')}
            </span>
            <span>
              {entry.resolvedBlockers.length} resolved · {entry.remainingBlockers.length} remaining ·{' '}
              {entry.introducedBlockers.length} introduced
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

function ComparisonWorkbook({
  label,
  snapshot,
}: {
  label: string;
  snapshot: ScenarioRehearsalRemediationComparison['predecessor'];
}) {
  return (
    <div>
      <span>{label}</span>
      <strong>{snapshot.jobId}</strong>
      <dl>
        <div><dt>Gate</dt><dd>{snapshot.gateReady ? 'Ready' : 'Blocked'}</dd></div>
        <div><dt>Passed</dt><dd>{snapshot.summary.passedItems}</dd></div>
        <div><dt>Failed</dt><dd>{snapshot.summary.failedItems}</dd></div>
        <div><dt>Cases</dt><dd>{snapshot.correctnessSummary.totalCases}</dd></div>
      </dl>
    </div>
  );
}

function BlockerGroup({
  label,
  values,
  tone,
}: {
  label: string;
  values: string[];
  tone: string;
}) {
  return (
    <div className={tone}>
      <strong>{label} · {values.length}</strong>
      <span>{values.length > 0 ? values.join(' · ') : 'None'}</span>
    </div>
  );
}
