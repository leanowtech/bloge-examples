import { describe, expect, it } from 'vitest';

import {
  createTaskReturnCoordinate,
  parseTaskCoordinate,
  parseTaskReturnCoordinate,
  parseTaskViewportRestore,
  taskReturnHref,
  taskCoordinateUrl,
  withTaskReturnCoordinate,
  type TaskCoordinate,
} from './taskCoordinate';

describe('TaskCoordinate', () => {
  it('parses legacy Author links into a complete conservative coordinate', () => {
    expect(parseTaskCoordinate(
      '/author/?authorMode=scenarios&draftId=draft-7&revision=4&nodeId=policy'
      + '&scenarioId=decline&runId=run-9&tenantId=tenant-a&environment=staging&role=reviewer',
      { namespace: 'risk', capabilityFingerprint: 'cap:7' },
    )).toEqual({
      tenantId: 'tenant-a',
      namespace: 'risk',
      environment: 'staging',
      draftId: 'draft-7',
      revision: 4,
      surface: 'SCENARIO',
      subjectKind: 'CASE',
      subjectRef: 'decline',
      selectionFingerprint: '',
      role: 'REVIEWER',
      capabilityFingerprint: 'cap:7',
      selection: { nodeId: 'policy', caseId: 'decline', runId: 'run-9' },
    });
  });

  it('round-trips draft, node, case, run, environment, role, and unrelated host state', () => {
    const coordinate = taskCoordinate();
    const href = taskCoordinateUrl('/author/?lang=zh-CN&hostSession=abc#evidence', coordinate);
    const restored = parseTaskCoordinate(href);

    expect(restored).toEqual(coordinate);
    expect(href).toContain('lang=zh-CN');
    expect(href).toContain('hostSession=abc');
    expect(href).toContain('#evidence');
  });

  it('carries a bounded return coordinate across surfaces', () => {
    const returning = createTaskReturnCoordinate(
      '/rehearsals/?jobId=job-2&entry=3',
      { ...taskCoordinate(), surface: 'EVIDENCE', subjectKind: 'RUN', subjectRef: 'run-9' },
      { scrollX: 18, scrollY: 640, focusId: 'rehearsal-entry-3' },
    );
    const authorHref = withTaskReturnCoordinate('/author/?draftId=draft-7&nodeId=policy', returning);

    expect(parseTaskReturnCoordinate(authorHref)).toEqual(returning);
    expect(taskReturnHref(returning)).toBe(
      '/rehearsals/?jobId=job-2&entry=3&restoreScrollX=18&restoreScrollY=640'
      + '&restoreFocusId=rehearsal-entry-3',
    );
  });

  it('consumes viewport restoration coordinates without losing task state', () => {
    expect(parseTaskViewportRestore(
      '/rehearsals/?jobId=job-2&entry=3&restoreScrollY=640&restoreFocusId=rehearsal-entry-3#proof',
    )).toEqual({
      scrollX: 0,
      scrollY: 640,
      focusId: 'rehearsal-entry-3',
      cleanHref: '/rehearsals/?jobId=job-2&entry=3#proof',
    });
    expect(parseTaskViewportRestore('/rehearsals/?jobId=job-2')).toBeNull();
  });

  it('rejects malformed and cross-origin return payloads', () => {
    expect(parseTaskReturnCoordinate('/author/?returnCoordinate=%7Bbad')).toBeNull();
    const malicious = encodeURIComponent(JSON.stringify({
      href: 'https://attacker.example/phish',
      coordinate: taskCoordinate(),
      scrollX: 0,
      scrollY: 0,
      focusId: '',
    }));
    expect(parseTaskReturnCoordinate(`/author/?returnCoordinate=${malicious}`)).toBeNull();
  });
});

function taskCoordinate(): TaskCoordinate {
  return {
    tenantId: 'tenant-a',
    namespace: 'risk',
    environment: 'production',
    draftId: 'draft-7',
    revision: 4,
    surface: 'EVIDENCE',
    subjectKind: 'RUN',
    subjectRef: 'run-9',
    selectionFingerprint: 'sha256:selection',
    role: 'OWNER',
    capabilityFingerprint: 'sha256:capabilities',
    selection: { nodeId: 'policy', caseId: 'decline', runId: 'run-9' },
  };
}
