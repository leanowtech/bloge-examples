import type {
  CorrectnessApiEnvelope,
  CorrectnessDeploymentCapabilities,
  CorrectnessEvidenceCompanion,
  CorrectnessPreflightReport,
  CorrectnessWorkspaceProjection,
  ExactAssetRef,
  StoredCorrectnessEvidenceCompanion,
  StoredCorrectnessGovernanceFeedback,
  StoredOutcomeCalibrationProposal,
} from './model/domain';

const FP = 'sha256:1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef';

export function exactRef(kind: string, id: string, revision = 1): ExactAssetRef {
  return { kind, id, revision, fingerprint: `${FP.slice(0, -2)}${revision.toString().padStart(2, '0')}` };
}

export function deploymentCapabilities(
  overrides: Record<string, boolean> = {},
): CorrectnessDeploymentCapabilities {
  return {
    schemaVersion: 'resourceGateway.integrationCapabilities.v1',
    features: {
      correctnessWorkspaceApi: true,
      correctnessTargetCatalogApi: true,
      guidedWorkspaceLauncher: true,
      correctnessPreflightApi: true,
      correctnessRunApi: true,
      correctnessEvidenceCompanionApi: true,
      correctnessOutcomeCalibrationApi: true,
      correctnessGovernanceFeedbackApi: true,
      ...overrides,
    },
    endpoints: [],
  };
}

export function workspaceProjection(): CorrectnessWorkspaceProjection {
  return {
    schemaVersion: 'bloge.correctnessWorkspaceProjection.v1',
    queryFingerprint: `${FP}:query`,
    target: { kind: 'GRAPH', id: 'loan-decision', revision: 7, fingerprint: `${FP}:graph` },
    definition: {
      definitionRef: exactRef('CORRECTNESS_DEFINITION', 'loan-decision-correctness', 3),
      title: 'Loan decision correctness',
      businessIntent: 'Approve eligible customers and keep every denial explainable.',
      successCriteria: [
        'Eligible customers receive an approval without manual review.',
        'Every denial contains a governed reason code.',
      ],
      riskLevel: 'HIGH',
      owner: { id: 'risk-owner', kind: 'TEAM', displayName: 'Risk service owner' },
      lifecycle: 'ACTIVE',
    },
    coverage: {
      availability: 'AVAILABLE',
      inventoryRef: exactRef('COVERAGE_INVENTORY', 'loan-obligations', 2),
      lifecycle: 'FROZEN', total: 12, fulfilled: 9, waived: 1, uncovered: 2,
    },
    oracleAssertions: {
      availability: 'AVAILABLE', oracleTotal: 8, proposedOracles: 2, approvedOracles: 6,
      supersededOracles: 0, assertionSetTotal: 7, draftAssertionSets: 1,
      validAssertionSets: 5, staleAssertionSets: 1, unsupportedAssertionSets: 0,
    },
    cases: {
      availability: 'AVAILABLE', scenarioDraftSetRef: exactRef('SCENARIO_DRAFT_SET', 'loan-cases', 4),
      total: 2, nextCursor: '', queryFingerprint: `${FP}:cases`,
      rows: [{
        scenarioDraftSetRef: exactRef('SCENARIO_DRAFT_SET', 'loan-cases', 4),
        caseId: 'eligible-prime', caseFingerprint: `${FP}:case`, name: 'Eligible prime customer',
        businessIntent: 'Prove the automatic approval path.', caseType: 'GOLDEN', risk: 'HIGH',
        owner: { id: 'case-owner', kind: 'USER', displayName: 'Case owner' }, lifecycle: 'CANONICAL',
        obligationCount: 2, oracleCount: 2, assertionSetCount: 2, dependencyCount: 1,
        reviewStatus: 'APPROVED', tags: ['approval'],
      }, {
        scenarioDraftSetRef: exactRef('SCENARIO_DRAFT_SET', 'loan-cases', 4),
        caseId: 'missing-income', caseFingerprint: `${FP}:case2`, name: 'Missing income evidence',
        businessIntent: 'Prove that incomplete applications are rejected safely.',
        caseType: 'NEGATIVE', risk: 'MEDIUM',
        owner: { id: 'case-owner', kind: 'USER', displayName: 'Case owner' }, lifecycle: 'CANONICAL',
        obligationCount: 1, oracleCount: 1, assertionSetCount: 1, dependencyCount: 1,
        reviewStatus: 'APPROVED', tags: ['validation'],
      }],
    },
    fixtures: {
      availability: 'AVAILABLE', total: 2, active: 1, stale: 1,
      rows: [{
        descriptorRef: exactRef('FIXTURE_DESCRIPTOR', 'eligible-prime', 3),
        name: 'Eligible prime profile', variantKey: 'prime-sg', lifecycle: 'ACTIVE',
        classification: 'CONFIDENTIAL',
        schemaRef: { id: 'loan-application-input', revision: 4, fingerprint: `${FP}:schema` },
        materialFingerprint: `${FP}:material`, usageCount: 1,
      }],
    },
    reviews: { pending: 2, approved: 13, rejected: 0, stale: 1 },
    lastPublication: {
      publicationRef: exactRef('CORRECTNESS_PUBLICATION', 'loan-publication', 1),
      lifecycle: 'PUBLISHED', publishedAt: '2026-08-15T12:00:00Z',
    },
    lastRun: {
      runId: 'suite-run-previous', finishedAt: '2026-08-15T12:30:00Z',
      executionStatus: 'SUCCESS', assertionStatus: 'NONE', evidenceRef: null,
    },
    verdict: {
      execution: 'SUCCESS', assertions: 'NONE', coverage: 'GAPPED', evidence: 'NOT_AVAILABLE',
      gate: 'ACCEPTED', proofLevel: 'STRUCTURAL',
      reasons: [{ code: 'ASSERTION_NONE', axis: 'ASSERTIONS', messageId: 'correctness.assertion.none' }],
      nextActions: [{ command: 'OPEN_ASSERTION_BUILDER', reasonCode: 'ASSERTION_NONE' }],
    },
    staleReasons: [], capabilities: ['CORRECTNESS_AUTHORING_V1', 'CORRECTNESS_PREFLIGHT_V1'],
    commandPolicy: { commands: { RUN: { allowed: true, reasonCode: '' } } },
    deepLinks: {
      workspace: '/correctness/', definition: '/correctness/?correctnessView=overview',
      cases: '/correctness/?correctnessView=cases', fixtures: '/correctness/?correctnessView=fixtures',
      lastRun: '/correctness/?correctnessView=runs',
    },
  };
}

export function preflightReport(blockers: CorrectnessPreflightReport['blockers'] = []): CorrectnessPreflightReport {
  return {
    schemaVersion: 'bloge.correctnessPreflightReport.v1',
    publicationRef: { publicationId: 'loan-publication', revision: 1, fingerprint: `${FP.slice(0, -2)}01` },
    target: { kind: 'GRAPH', id: 'loan-decision', revision: 7, fingerprint: `${FP}:graph` },
    compiledTestSuiteRef: exactRef('TEST_SUITE', 'loan-suite', 4),
    selection: {
      mode: 'ALL', caseIds: ['eligible-prime', 'missing-income'],
      selectionFingerprint: `${FP}:selection`,
    },
    proofLevel: 'SIMULATED_BUSINESS',
    cases: [{
      caseId: 'eligible-prime', caseType: 'GOLDEN',
      fixtureBundleRef: exactRef('FIXTURE_BUNDLE', 'eligible-prime', 5),
      executionPlanFingerprint: `${FP}:plan`,
      invocationSites: [{
        invocationSiteId: 'credit-score:1', graphPath: '/credit-score', nodeId: 'credit-score',
        operatorRef: 'risk:credit-score', resourceRef: '', functionRef: '',
        runtimeBindingFingerprint: `${FP}:binding`, invocationKind: 'OPERATOR', sideEffectType: 'READ',
        resolution: 'TEST_DOUBLE', behavior: 'RETURN', boundary: 'NODE', ruleRefs: ['rule:prime'],
        fidelity: 'CONTRACT_FAITHFUL',
      }],
      rulePolicies: [{
        ruleId: 'rule:prime', behavior: 'RETURN', boundary: 'NODE', required: true,
        minUses: 1, maxUses: 1, onUnmatched: 'FAIL', onExhausted: 'FAIL', schemaCheckMode: 'STRICT',
      }],
      executionServices: [], replayDependencyCount: 0,
    }],
    riskSummary: {
      realCount: 0, mockedCount: 1, faultCount: 0, replayCount: 0, observeCount: 0,
      deniedCount: 0, fallbackToRealCount: 0, transportBoundaryCount: 0,
      secretRequirementCount: 0, logicalClockConfigured: true, sideEffectTypes: ['READ'],
    },
    blockers,
    preflightFingerprint: `${FP}:preflight`,
  };
}

export function storedEvidence(): StoredCorrectnessEvidenceCompanion {
  const companion: CorrectnessEvidenceCompanion = {
    schemaVersion: 'bloge.correctnessEvidenceCompanion.v1',
    evidenceCompanionId: 'evidence-companion-1',
    scope: {
      tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan-assist',
      environment: 'test', region: 'sg',
    },
    suiteRunId: 'suite-run-1', suiteEvidenceFingerprint: `${FP}:suite-evidence`,
    clientRequestFingerprint: `${FP}:request`,
    publicationRef: { publicationId: 'loan-publication', revision: 1, fingerprint: `${FP.slice(0, -2)}01` },
    target: { kind: 'GRAPH', id: 'loan-decision', revision: 7, fingerprint: `${FP}:graph` },
    definitionRef: exactRef('CORRECTNESS_DEFINITION', 'loan-decision-correctness', 3),
    inventoryRef: exactRef('COVERAGE_INVENTORY', 'loan-obligations', 2),
    scenarioDraftSetRef: exactRef('SCENARIO_DRAFT_SET', 'loan-cases', 4),
    caseRefs: [{
      scenarioDraftSetRef: exactRef('SCENARIO_DRAFT_SET', 'loan-cases', 4),
      caseId: 'eligible-prime', caseFingerprint: `${FP}:case`,
    }],
    oracleRefs: [exactRef('BUSINESS_ORACLE', 'approve-eligible', 2)],
    assertionSetRefs: [exactRef('ASSERTION_SET', 'approve-eligible', 2)],
    fixtureAssetRefs: [exactRef('FIXTURE_DESCRIPTOR', 'eligible-prime', 3)],
    compiledFixtureBundleRefs: [exactRef('FIXTURE_BUNDLE', 'eligible-prime', 5)],
    compiledTestSuiteRef: exactRef('TEST_SUITE', 'loan-suite', 4),
    selection: { mode: 'ALL', caseIds: ['eligible-prime'], selectionFingerprint: `${FP}:selection` },
    caseExecutions: [{
      caseId: 'eligible-prime', fixtureBundleRef: exactRef('FIXTURE_BUNDLE', 'eligible-prime', 5),
      executionPlanFingerprint: `${FP}:plan`, status: 'SUCCESS', childRunId: 'child-run-1',
      evidenceClass: 'CERTIFIABLE',
    }],
    sourceMap: [{
      source: {
        assetRef: exactRef('BUSINESS_ORACLE', 'approve-eligible', 2),
        elementKind: 'ORACLE', elementId: 'oracle:approve-eligible',
      },
      output: {
        assetRef: exactRef('FIXTURE_ASSERTION', 'approve-eligible', 1),
        elementKind: 'ASSERTION', elementId: 'assertion:decision-equals-approve',
      },
    }],
    riskSummary: {
      realCount: 0, mockedCount: 1, faultCount: 0, replayCount: 0, observeCount: 0,
      deniedCount: 0, fallbackToRealCount: 0, transportBoundaryCount: 0,
      secretRequirementCount: 0, logicalClockConfigured: true, sideEffectTypes: ['READ'],
    },
    dataClassifications: ['CONFIDENTIAL'],
    verdict: {
      execution: 'SUCCESS', assertions: 'PASSED', coverage: 'COMPLETE', evidence: 'CURRENT',
      gate: 'ACCEPTED', proofLevel: 'SIMULATED_BUSINESS', reasons: [], nextActions: [],
    },
    attestation: { signatureStatus: 'VERIFIED', scope: 'SUITE', independentlyVerifiable: true },
    metadata: {
      createdAt: '2026-08-15T12:31:00Z', updatedAt: '2026-08-15T12:31:00Z',
      createdBy: { id: 'runner', kind: 'SERVICE', displayName: 'Correctness runner' },
      updatedBy: { id: 'runner', kind: 'SERVICE', displayName: 'Correctness runner' },
    },
  };
  return {
    schemaVersion: 'bloge.storedCorrectnessEvidenceCompanion.v1',
    companionFingerprint: `${FP}:companion`,
    companion,
  };
}

export function envelope<T>(data: T): CorrectnessApiEnvelope<T> {
  return {
    protocolVersion: 'bloge.correctnessApi.v1', correlationId: 'corr-1',
    capabilities: ['CORRECTNESS_AUTHORING_V1'],
    scope: {
      tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan-assist',
      environment: 'test', region: 'sg',
    },
    data,
  };
}

export function storedGovernanceFeedback(): StoredCorrectnessGovernanceFeedback {
  return {
    schemaVersion: 'bloge.storedCorrectnessGovernanceFeedback.v1',
    feedbackFingerprint: `${FP}:governance-feedback`,
    feedback: {
      schemaVersion: 'toolStudio.resourceGateway.correctnessFeedback.v1',
      feedbackId: 'feedback-1',
      scope: {
        tenantId: 'tenant-a', organizationId: 'customer-service', projectId: 'loan-assist',
        environment: 'test', region: 'sg',
      },
      publicationRef: {
        publicationId: 'loan-publication', revision: 1,
        fingerprint: `${FP.slice(0, -2)}01`,
      },
      sourceSystem: 'ANEKE_TOOL_STUDIO',
      sourceProtocolVersion: '1.1.0',
      sourceDecisionId: 'gate-loan-2026-08-15', sourceDecisionRevision: 3,
      sourceDecisionFingerprint: `${FP}:decision`, decision: 'BLOCKED',
      workbookStatus: 'MISSING', ownerApprovalStatus: 'REQUIRED',
      breakingMigrationStatus: 'NONE',
      findings: [{
        findingId: 'finding-workbook', severity: 'BLOCKING', category: 'WORKBOOK',
        code: 'WORKBOOK_REQUIRED', message: 'Correctness workbook is missing.',
        remediation: 'Create and approve the correctness workbook before publication.',
        deepLink: 'https://aneke.example/workbooks/loan',
      }],
      producedAt: '2026-08-15T12:20:00Z', expiresAt: '2099-08-15T12:20:00Z',
      receivedAt: '2026-08-15T12:20:30Z', receivedBy: 'aneke-sidecar',
      correlationId: 'corr-governance-1',
    },
  };
}

export function storedCalibrationProposal(): StoredOutcomeCalibrationProposal {
  const evidence = storedEvidence();
  const actor = { id: 'author-1', kind: 'USER' as const, displayName: 'Author' };
  return {
    schemaVersion: 'bloge.storedOutcomeCalibrationProposal.v1',
    proposalFingerprint: `${FP}:calibration`,
    proposal: {
      schemaVersion: 'bloge.outcomeCalibrationProposal.v1', proposalId: 'calibration-1',
      scope: evidence.companion.scope, publicationRef: evidence.companion.publicationRef,
      suiteRunId: evidence.companion.suiteRunId,
      evidenceCompanionRef: exactRef(
        'CORRECTNESS_EVIDENCE_COMPANION', evidence.companion.evidenceCompanionId, 1,
      ),
      target: evidence.companion.target, caseRefs: evidence.companion.caseRefs,
      oracleRefs: evidence.companion.oracleRefs, mismatchKind: 'EXPECTED_OUTCOME_DIFFERED',
      reasonCode: 'OBSERVED_OUTCOME_MISMATCH',
      businessRationale: 'The reviewed policy changed after production validation.',
      proposedRegressionTitle: 'Preserve the new reviewed outcome', status: 'PROPOSED',
      owner: actor, correlationId: 'corr-calibration-1',
      metadata: {
        createdAt: '2026-08-15T12:40:00Z', updatedAt: '2026-08-15T12:40:00Z',
        createdBy: actor, updatedBy: actor,
      },
    },
  };
}
