#!/usr/bin/env node
import { readFileSync } from 'node:fs';

const ROLES = ['AUTHOR', 'TEST_ENGINEER', 'GOVERNANCE_REVIEWER', 'INCIDENT_RESPONDER'];
const CRITICAL_INCIDENTS = [
  'SILENT_DATA_LOSS',
  'CROSS_ENVIRONMENT_MISOPERATION',
  'MOCK_EVIDENCE_PUBLISHED',
];

export function evaluateUxEvidence(evidence) {
  validateEnvelope(evidence);
  const sessions = evidence.sessions;
  const tasks = sessions.flatMap((session) => session.tasks);
  const vscodeSessions = sessions.filter((session) => session.host === 'VSCODE_WEBVIEW');
  const completionRate = ratio(tasks.filter((task) => task.completed).length, tasks.length);
  const contextAccuracy = ratio(tasks.filter((task) => task.contextCorrect).length, tasks.length);
  const proofTasks = tasks.filter((task) => task.proofClassificationCorrect !== null);
  const proofAccuracy = ratio(
    proofTasks.filter((task) => task.proofClassificationCorrect).length,
    proofTasks.length,
  );
  const roleCounts = Object.fromEntries(ROLES.map((role) => [
    role,
    sessions.filter((session) => session.role === role).length,
  ]));
  const p75ColdStartMs = percentile(vscodeSessions.map((session) => session.coldStartMs), 0.75);
  const teams = new Set(evidence.releaseCycles.map((cycle) => cycle.teamRef));
  const cyclesByTeam = [...teams].map((teamRef) => (
    evidence.releaseCycles.filter((cycle) => cycle.teamRef === teamRef).length
  ));
  const criticalIncidentCount = evidence.releaseCycles.reduce((count, cycle) => (
    count + CRITICAL_INCIDENTS.reduce((subtotal, key) => subtotal + cycle.incidents[key], 0)
  ), 0);
  const p0p1Findings = tasks.reduce((count, task) => (
    count + task.findings.filter((finding) => finding === 'P0' || finding === 'P1').length
  ), 0);

  const gates = [
    gate('E3 participant count', sessions.length >= 12, `${sessions.length} / 12`),
    gate('E3 role coverage', Object.values(roleCounts).every((count) => count >= 3), formatRoleCounts(roleCounts)),
    gate('Core task success', completionRate >= 0.95, percent(completionRate)),
    gate('Task-coordinate accuracy', contextAccuracy >= 0.95, percent(contextAccuracy)),
    gate('Proof-authority accuracy', proofTasks.length > 0 && proofAccuracy >= 0.95, percent(proofAccuracy)),
    gate('P0/P1 experience findings', p0p1Findings === 0, String(p0p1Findings)),
    gate('VS Code participant coverage', vscodeSessions.length >= 2, `${vscodeSessions.length} / 2`),
    gate('VS Code cold start P75', p75ColdStartMs !== null && p75ColdStartMs > 0 && p75ColdStartMs <= 2_000, formatMs(p75ColdStartMs)),
    gate('E4 team coverage', teams.size >= 2, `${teams.size} / 2`),
    gate('E4 release cycles per team', cyclesByTeam.length >= 2 && cyclesByTeam.every((count) => count >= 2), cyclesByTeam.join(' / ') || '0'),
    gate('Critical organization incidents', criticalIncidentCount === 0, String(criticalIncidentCount)),
  ];

  return {
    passed: gates.every((item) => item.passed),
    metrics: {
      participantCount: sessions.length,
      roleCounts,
      taskCount: tasks.length,
      completionRate,
      contextAccuracy,
      proofAccuracy,
      vscodeParticipantCount: vscodeSessions.length,
      p75ColdStartMs,
      teamCount: teams.size,
      releaseCycleCount: evidence.releaseCycles.length,
      criticalIncidentCount,
      p0p1Findings,
    },
    gates,
  };
}

function validateEnvelope(evidence) {
  if (!evidence || evidence.schemaVersion !== 'resourceGateway.uxEvidence.v1') {
    throw new Error('Expected schemaVersion resourceGateway.uxEvidence.v1');
  }
  if (!Array.isArray(evidence.sessions) || !Array.isArray(evidence.releaseCycles)) {
    throw new Error('sessions and releaseCycles must be arrays');
  }
  for (const session of evidence.sessions) {
    if (!ROLES.includes(session.role)) throw new Error(`Unsupported role: ${session.role}`);
    if (!['WEB', 'VSCODE_WEBVIEW'].includes(session.host)) throw new Error(`Unsupported host: ${session.host}`);
    if (!Array.isArray(session.tasks) || session.tasks.length === 0) throw new Error('Every session needs at least one task');
    if (!Number.isFinite(session.coldStartMs) || session.coldStartMs < 0) throw new Error('coldStartMs must be non-negative');
    for (const task of session.tasks) {
      if (typeof task.completed !== 'boolean' || typeof task.contextCorrect !== 'boolean') {
        throw new Error('Task completion and context accuracy must be boolean');
      }
      if (!Array.isArray(task.findings) || task.findings.some((finding) => !['P0', 'P1', 'P2', 'P3'].includes(finding))) {
        throw new Error('Task findings must use P0/P1/P2/P3');
      }
    }
  }
  for (const cycle of evidence.releaseCycles) {
    if (!cycle.teamRef || !cycle.cycleRef) throw new Error('Every release cycle needs payload-free teamRef and cycleRef');
    for (const incident of CRITICAL_INCIDENTS) {
      if (!Number.isInteger(cycle.incidents?.[incident]) || cycle.incidents[incident] < 0) {
        throw new Error(`Incident ${incident} must be a non-negative integer`);
      }
    }
  }
}

function gate(name, passed, observed) {
  return { name, passed, observed };
}

function ratio(numerator, denominator) {
  return denominator === 0 ? 0 : numerator / denominator;
}

function percentile(values, quantile) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.ceil(sorted.length * quantile) - 1];
}

function percent(value) {
  return `${(value * 100).toFixed(1)}%`;
}

function formatMs(value) {
  return value === null ? 'n/a' : `${value} ms`;
}

function formatRoleCounts(counts) {
  return ROLES.map((role) => `${role}=${counts[role]}`).join(', ');
}

function printReport(report) {
  console.log(`Resource Gateway UX evidence: ${report.passed ? 'PASS' : 'NOT READY'}`);
  for (const item of report.gates) {
    console.log(`${item.passed ? 'PASS' : 'FAIL'}  ${item.name}: ${item.observed}`);
  }
}

if (process.argv[1]?.endsWith('evaluate-resource-gateway-ux-evidence.mjs')) {
  const evidencePath = process.argv[2];
  if (!evidencePath) {
    console.error('Usage: node scripts/evaluate-resource-gateway-ux-evidence.mjs <evidence.json>');
    process.exitCode = 2;
  } else {
    try {
      const evidence = JSON.parse(readFileSync(evidencePath, 'utf8'));
      const report = evaluateUxEvidence(evidence);
      printReport(report);
      if (!report.passed) process.exitCode = 1;
    } catch (cause) {
      console.error(`Invalid UX evidence: ${cause instanceof Error ? cause.message : String(cause)}`);
      process.exitCode = 2;
    }
  }
}
