import type { TaskCoordinate, TaskRole } from './taskCoordinate';

export type TaskCommandRisk = 'READ' | 'EXECUTE' | 'MUTATE' | 'DESTRUCTIVE';
export type TaskCommandDecision = 'ALLOW' | 'REQUIRE_CONFIRMATION' | 'DENY';

export interface TaskCommandAuthorityInput {
  commandId: string;
  risk: TaskCommandRisk;
  coordinate: TaskCoordinate;
  sessionTenantId: string;
  grantedCapabilities?: string[];
  requiredCapability?: string;
}

export interface TaskCommandPolicy {
  commandId: string;
  decision: TaskCommandDecision;
  enabled: boolean;
  reasonCode: string;
  environmentTone: 'NEUTRAL' | 'CAUTION' | 'DANGER';
  requiresExplicitConfirmation: boolean;
}

/** One authority for command visibility, execution, and production safeguards. */
export function evaluateTaskCommandAuthority(
  input: TaskCommandAuthorityInput,
): TaskCommandPolicy {
  const environment = input.coordinate.environment.trim().toLowerCase();
  const environmentTone = productionLike(environment)
    ? 'DANGER' as const
    : stagingLike(environment) ? 'CAUTION' as const : 'NEUTRAL' as const;
  const base = { commandId: input.commandId, environmentTone };
  const sessionTenant = input.sessionTenantId.trim();
  if (sessionTenant && sessionTenant !== input.coordinate.tenantId.trim()) {
    return denied(base, 'RG.AUTHOR.COMMAND.CROSS_TENANT');
  }
  if (
    input.requiredCapability
    && !(input.grantedCapabilities ?? []).includes(input.requiredCapability)
  ) {
    return denied(base, 'RG.AUTHOR.COMMAND.CAPABILITY_MISSING');
  }
  if (!roleAllows(input.coordinate.role, input.risk)) {
    return denied(base, `RG.AUTHOR.COMMAND.ROLE_${input.coordinate.role}`);
  }
  if (input.risk === 'DESTRUCTIVE' && environmentTone === 'DANGER') {
    return {
      ...base,
      decision: 'REQUIRE_CONFIRMATION',
      enabled: true,
      reasonCode: 'RG.AUTHOR.COMMAND.PRODUCTION_CONFIRMATION',
      requiresExplicitConfirmation: true,
    };
  }
  return {
    ...base,
    decision: 'ALLOW',
    enabled: true,
    reasonCode: '',
    requiresExplicitConfirmation: false,
  };
}

function roleAllows(role: TaskRole, risk: TaskCommandRisk): boolean {
  if (risk === 'READ') return true;
  if (risk === 'EXECUTE') return role !== 'VIEWER';
  return role === 'OWNER' || role === 'EDITOR';
}

function productionLike(environment: string): boolean {
  return environment === 'prod' || environment === 'production';
}

function stagingLike(environment: string): boolean {
  return environment === 'stage' || environment === 'staging' || environment === 'uat';
}

function denied(
  base: Pick<TaskCommandPolicy, 'commandId' | 'environmentTone'>,
  reasonCode: string,
): TaskCommandPolicy {
  return {
    ...base,
    decision: 'DENY',
    enabled: false,
    reasonCode,
    requiresExplicitConfirmation: false,
  };
}
