import { describe, expect, it } from 'vitest';

import {
  BUSINESS_MIRROR_REMEDIATION_DESCRIPTORS,
  BUSINESS_MIRROR_STEP_CONTRACTS,
  BUSINESS_MIRROR_TASK_ORDER,
  KNOWN_BUSINESS_MIRROR_BLOCKER_CODES,
  remediationDescriptorForGap,
  type RemediationDescriptor,
  type StepContract,
} from './guidance';

describe('Business Mirror guidance contracts', () => {
  it('defines a complete seven-step contract chain', () => {
    expect(Object.keys(BUSINESS_MIRROR_STEP_CONTRACTS).sort()).toEqual([...BUSINESS_MIRROR_TASK_ORDER].sort());

    const visited = new Set<string>();
    let task: string | null = BUSINESS_MIRROR_TASK_ORDER[0];
    while (task) {
      expect(visited.has(task)).toBe(false);
      visited.add(task);
      const contract: StepContract = BUSINESS_MIRROR_STEP_CONTRACTS[
        task as keyof typeof BUSINESS_MIRROR_STEP_CONTRACTS
      ];
      expect(contract.question).toEqual(expect.any(String));
      expect(contract.why).toEqual(expect.any(String));
      expect(contract.inputs.length).toBeGreaterThan(0);
      expect(contract.primaryAction.actionKind).toEqual(expect.any(String));
      expect(contract.primaryAction.anchor).toBeTruthy();
      expect(contract.primaryAction.fieldPath).toBeTruthy();
      expect(contract.primaryAction.capability).toBeTruthy();
      expect(contract.completionCodes.length).toBeGreaterThan(0);
      task = contract.nextStep;
    }

    expect([...visited]).toEqual([...BUSINESS_MIRROR_TASK_ORDER]);
    expect(BUSINESS_MIRROR_STEP_CONTRACTS.calibrate.nextStep).toBeNull();
  });

  it('covers every known blocker with a complete, uniquely anchored descriptor', () => {
    const descriptors = KNOWN_BUSINESS_MIRROR_BLOCKER_CODES.map(
      (code) => BUSINESS_MIRROR_REMEDIATION_DESCRIPTORS[code],
    );

    expect(Object.keys(BUSINESS_MIRROR_REMEDIATION_DESCRIPTORS).sort())
      .toEqual([...KNOWN_BUSINESS_MIRROR_BLOCKER_CODES].sort());
    expect(new Set(descriptors.map((descriptor) => descriptor.anchor)).size)
      .toBe(descriptors.length);

    descriptors.forEach((descriptor) => expectCompleteDescriptor(descriptor));
  });

  it('returns an explicit unavailable action for an unknown gap by category', () => {
    const descriptor = remediationDescriptorForGap({
      code: 'NEW_POLICY_GAP',
      category: 'SCENARIO',
      draftPath: '/scenarioInventoryRef',
    });

    expectCompleteDescriptor(descriptor);
    expect(descriptor.gapCode).toBe('NEW_POLICY_GAP');
    expect(descriptor.taskId).toBe('scenarios');
    expect(descriptor.actionKind).toBe('FOCUS_FIELD');
    expect(descriptor.fallback).toBe('SHOW_UNAVAILABLE');
    expect(descriptor.anchor).toContain('scenario');
  });
});

function expectCompleteDescriptor(descriptor: RemediationDescriptor): void {
  expect(descriptor.gapCode).toBeTruthy();
  expect(descriptor.category).toBeTruthy();
  expect(descriptor.taskId).toBeTruthy();
  expect(descriptor.anchor).toBeTruthy();
  expect(descriptor.surfaceAnchor).toBe(descriptor.anchor);
  expect(descriptor.actionKind).toEqual(expect.any(String));
  expect(descriptor.fieldPath).toBeTruthy();
  expect(descriptor.capability).toBeTruthy();
  expect(descriptor.capabilityRequired).toBe(descriptor.capability);
  expect(descriptor.titleMessageId).toEqual(expect.any(String));
  expect(descriptor.impactMessageId).toEqual(expect.any(String));
  expect(descriptor.instructionMessageId).toEqual(expect.any(String));
  expect(descriptor.completionPredicate).toBeTruthy();
  expect(descriptor.fallback).toEqual(expect.any(String));
}
