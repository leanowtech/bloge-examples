import { describe, expect, it } from 'vitest';

import { evaluateTaskCommandAuthority, type TaskCommandRisk } from './commandAuthority';
import type { TaskCoordinate, TaskRole } from './taskCoordinate';

describe('CommandAuthority', () => {
  it.each([
    ['OWNER', 'MUTATE', 'ALLOW'],
    ['EDITOR', 'EXECUTE', 'ALLOW'],
    ['REVIEWER', 'EXECUTE', 'ALLOW'],
    ['REVIEWER', 'MUTATE', 'DENY'],
    ['VIEWER', 'EXECUTE', 'DENY'],
    ['VIEWER', 'READ', 'ALLOW'],
  ] as Array<[TaskRole, TaskCommandRisk, string]>)('%s + %s -> %s', (role, risk, decision) => {
    expect(policy({ role }, risk).decision).toBe(decision);
  });

  it('requires an explicit confirmation for every production destructive command', () => {
    ['DELETE_NODE', 'DELETE_EDGE', 'IMPORT_DSL', 'LOAD_EXAMPLE'].forEach((commandId) => {
      const result = policy({ environment: 'production' }, 'DESTRUCTIVE', commandId);
      expect(result).toMatchObject({
        decision: 'REQUIRE_CONFIRMATION',
        enabled: true,
        requiresExplicitConfirmation: true,
        reasonCode: 'RG.AUTHOR.COMMAND.PRODUCTION_CONFIRMATION',
        environmentTone: 'DANGER',
      });
    });
  });

  it('denies cross-tenant commands before role and environment policy', () => {
    const result = evaluateTaskCommandAuthority({
      commandId: 'DELETE_NODE',
      risk: 'DESTRUCTIVE',
      coordinate: coordinate({ tenantId: 'tenant-b', environment: 'production', role: 'OWNER' }),
      sessionTenantId: 'tenant-a',
    });

    expect(result).toMatchObject({
      decision: 'DENY',
      reasonCode: 'RG.AUTHOR.COMMAND.CROSS_TENANT',
      requiresExplicitConfirmation: false,
    });
  });

  it('denies a command when its advertised capability is absent', () => {
    const result = evaluateTaskCommandAuthority({
      commandId: 'RUN',
      risk: 'EXECUTE',
      coordinate: coordinate(),
      sessionTenantId: 'tenant-a',
      requiredCapability: 'RUN_GRAPH',
      grantedCapabilities: ['READ_GRAPH'],
    });

    expect(result.reasonCode).toBe('RG.AUTHOR.COMMAND.CAPABILITY_MISSING');
  });
});

function policy(
  override: Partial<TaskCoordinate>,
  risk: TaskCommandRisk,
  commandId = 'COMMAND',
) {
  const taskCoordinate = coordinate(override);
  return evaluateTaskCommandAuthority({
    commandId,
    risk,
    coordinate: taskCoordinate,
    sessionTenantId: taskCoordinate.tenantId,
  });
}

function coordinate(override: Partial<TaskCoordinate> = {}): TaskCoordinate {
  return {
    tenantId: 'tenant-a',
    namespace: 'risk',
    environment: 'test',
    draftId: 'draft-7',
    revision: 4,
    surface: 'COMPOSE',
    subjectKind: 'GRAPH',
    subjectRef: 'riskPolicy',
    selectionFingerprint: '',
    role: 'EDITOR',
    capabilityFingerprint: 'cap:demo',
    selection: { nodeId: '', caseId: '', runId: '' },
    ...override,
  };
}
