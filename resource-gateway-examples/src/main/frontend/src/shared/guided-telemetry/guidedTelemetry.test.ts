import { describe, expect, it, vi } from 'vitest';

import {
  createGuidedAuthoringTelemetry,
  createGuidedTelemetryEvent,
  guidedTelemetryDurationBucket,
  guidedTelemetryResultCountBucket,
  guidedTelemetryScopeHash,
  type GuidedTelemetryScope,
} from './guidedTelemetry';

describe('guided authoring telemetry', () => {
  it('accepts only the payload-free event contract', () => {
    expect(createGuidedTelemetryEvent('REMEDIATION_STARTED', {
      gapCode: 'ACCOUNTABLE_OWNER_MISSING', actionKind: 'OPEN_PICKER', sameStep: true,
    }).metadata).toEqual({
      gapCode: 'ACCOUNTABLE_OWNER_MISSING', actionKind: 'OPEN_PICKER', sameStep: true,
    });
  });

  it.each(['query', 'fixture', 'payload', 'context', 'operatorId', 'fingerprint'])
    ('rejects sensitive or identifying metadata key %s', (key) => {
      expect(() => createGuidedTelemetryEvent('REFERENCE_SEARCH_COMPLETED', {
        kind: 'GRAPH', [key]: 'sensitive-value',
      } as never)).toThrow();
    });

  it('rejects non-enum strings, full fingerprints, and unknown fields', () => {
    expect(() => createGuidedTelemetryEvent('REFERENCE_SEARCH_COMPLETED', {
      kind: 'customer-id' as never,
    })).toThrow();
    expect(() => createGuidedTelemetryEvent('WORKSPACE_LAUNCHER_OPENED', {
      surface: 'CORRECTNESS', scopeHash: `sha256:${'a'.repeat(64)}`,
    })).toThrow();
    expect(() => createGuidedTelemetryEvent('WORKSPACE_LAUNCHER_OPENED', {
      surface: 'CORRECTNESS', unexpected: 'value',
    } as never)).toThrow();
    expect(() => createGuidedTelemetryEvent('REMEDIATION_STARTED', {
      gapCode: 'CUSTOMER_ACCOUNT_123' as never, actionKind: 'OPEN_PICKER', sameStep: true,
    })).toThrow();
    expect(() => createGuidedTelemetryEvent('REMEDIATION_STARTED', {
      gapCode: 'ACCOUNTABLE_OWNER_MISSING', actionKind: true as never, sameStep: true,
    })).toThrow();
    expect(() => createGuidedTelemetryEvent('REMEDIATION_STARTED', {
      gapCode: 'ACCOUNTABLE_OWNER_MISSING', actionKind: 'OPEN_PICKER', sameStep: 1 as never,
    })).toThrow();
  });

  it('uses bounded buckets and a short hash derived from the existing safe hash utility', () => {
    expect(guidedTelemetryDurationBucket(0)).toBe('LT_100_MS');
    expect(guidedTelemetryDurationBucket(5_000)).toBe('GT_5_S');
    expect(guidedTelemetryResultCountBucket(0)).toBe('ZERO');
    expect(guidedTelemetryResultCountBucket(7)).toBe('SIX_TO_TWENTY');
    expect(guidedTelemetryScopeHash({ tenantId: 'tenant-a', projectId: 'project-a' }))
      .toMatch(/^sha256:[0-9a-f]{16}$/);
    const hash = guidedTelemetryScopeHash({
      tenantId: 'tenant-a', projectId: 'project-a', payload: 'must-not-affect-scope',
    } as unknown as GuidedTelemetryScope);
    expect(hash).toBe(guidedTelemetryScopeHash({ tenantId: 'tenant-a', projectId: 'project-a' }));
  });

  it('does not send by default and isolates a failing injected sink', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    expect(createGuidedAuthoringTelemetry().record('WORKSPACE_LAUNCHER_OPENED', {
      surface: 'CORRECTNESS', entryKind: 'GUIDED',
    })).not.toBeNull();
    const recorder = createGuidedAuthoringTelemetry(() => { throw new Error('sink down'); });
    expect(recorder.record('WORKSPACE_LAUNCHER_OPENED', {
      surface: 'CORRECTNESS', entryKind: 'GUIDED',
    })).toBeNull();
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});
