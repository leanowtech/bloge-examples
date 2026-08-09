import assert from 'node:assert/strict';
import test from 'node:test';

import { evaluateUxEvidence } from './evaluate-resource-gateway-ux-evidence.mjs';

test('passes only complete E3 and E4 evidence', () => {
  const report = evaluateUxEvidence(completeEvidence());
  assert.equal(report.passed, true);
  assert.equal(report.metrics.participantCount, 12);
  assert.equal(report.metrics.p75ColdStartMs, 1_800);
});

test('fails when proof classification or critical incidents violate the gate', () => {
  const evidence = completeEvidence();
  evidence.sessions[0].tasks[0].proofClassificationCorrect = false;
  evidence.releaseCycles[0].incidents.MOCK_EVIDENCE_PUBLISHED = 1;
  const report = evaluateUxEvidence(evidence);
  assert.equal(report.passed, false);
  assert.equal(report.gates.find((gate) => gate.name === 'Proof-authority accuracy').passed, false);
  assert.equal(report.gates.find((gate) => gate.name === 'Critical organization incidents').passed, false);
});

function completeEvidence() {
  const roles = ['AUTHOR', 'TEST_ENGINEER', 'GOVERNANCE_REVIEWER', 'INCIDENT_RESPONDER'];
  return {
    schemaVersion: 'resourceGateway.uxEvidence.v1',
    studyId: 'study-2026-q3',
    sessions: roles.flatMap((role, roleIndex) => Array.from({ length: 3 }, (_, index) => ({
      sessionId: `${roleIndex}-${index}`,
      participantRef: `participant-${roleIndex}-${index}`,
      role,
      host: index === 0 ? 'VSCODE_WEBVIEW' : 'WEB',
      coldStartMs: index === 0 ? 1_800 : 900,
      warmStartMs: 500,
      tasks: [{
        taskId: 'PROOF_CLASSIFICATION',
        completed: true,
        durationMs: 45_000,
        contextCorrect: true,
        proofClassificationCorrect: true,
        scopeCorrectionCount: 0,
        abandoned: false,
        findings: [],
      }],
    }))),
    releaseCycles: ['team-a', 'team-b'].flatMap((teamRef) => [1, 2].map((cycle) => ({
      teamRef,
      cycleRef: `${teamRef}-cycle-${cycle}`,
      releases: 1,
      incidents: {
        SILENT_DATA_LOSS: 0,
        CROSS_ENVIRONMENT_MISOPERATION: 0,
        MOCK_EVIDENCE_PUBLISHED: 0,
      },
    }))),
  };
}
