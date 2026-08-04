import type { GraphDraft } from '../../types';
import type {
  ContractDraft,
  DependencyBehaviorDraft,
  ScenarioCaseType,
  ScenarioDraft,
  ScenarioDraftSet,
} from '../domain';
import { canonicalJson, sha256Fingerprint } from '../fingerprint';
import type { ScenarioTableEvidenceByCase } from '../table/scenarioTableModel';

export type CoverageDimensionId =
  | 'CASE'
  | 'CONTRACT'
  | 'DAG'
  | 'DEPENDENCY'
  | 'ASSERTION'
  | 'EVIDENCE';

export type CoverageGapAction =
  | 'GENERATE'
  | 'AUTHOR_CASE'
  | 'AUTHOR_ASSERTION'
  | 'RUN'
  | 'INSTALL_GENERATOR';

export interface CoverageFact {
  factId: string;
  dimension: CoverageDimensionId;
  kind: string;
  coordinate: string;
  label: string;
  description: string;
  coveredByCaseIds: string[];
  action: CoverageGapAction;
  generation?: CoverageGenerationDescriptor;
}

export interface CoverageDimensionProjection {
  dimension: CoverageDimensionId;
  covered: number;
  total: number;
  facts: CoverageFact[];
  gaps: CoverageFact[];
}

export interface CoverageProjection {
  schemaVersion: 'bloge.coverageProjection.v1';
  target: ScenarioDraftSet['target'];
  contractFingerprint: string;
  scenarioDraftSetId: string;
  scenarioDraftSetRevision: number;
  dimensions: CoverageDimensionProjection[];
  projectionFingerprint: string;
}

export type CoverageGenerationKind =
  | 'SET_INPUT'
  | 'DELETE_INPUT'
  | 'DEPENDENCY_BEHAVIOR'
  | 'ERROR_CONTRACT';

export interface CoverageGenerationDescriptor {
  kind: CoverageGenerationKind;
  path: string[];
  value?: unknown;
  caseType: ScenarioCaseType;
  dependencyCoordinate?: string;
  dependencyBehavior?: DependencyBehaviorDraft['behavior']['kind'];
  errorCode?: string;
}

export interface CoverageCandidateSource {
  targetFingerprint: string;
  contractFingerprint: string;
  scenarioDraftSetId: string;
  scenarioDraftSetRevision: number;
  coverageProjectionFingerprint: string;
}

export interface CoverageCandidate {
  candidateId: string;
  candidateFingerprint: string;
  generatorId: string;
  generatorVersion: string;
  seed: number;
  source: CoverageCandidateSource;
  rationale: string;
  contributionFactIds: string[];
  workUnits: number;
  expectedBehavior: {
    status: 'READY' | 'NEEDS_AUTHOR' | 'BLOCKED';
    reason: string;
  };
  promotionEligible: boolean;
  proposal: ScenarioDraft;
}

export interface CoverageCandidateSet {
  schemaVersion: 'bloge.coverageCandidateSet.v1';
  candidateSetId: string;
  generatorVersions: Record<string, string>;
  seed: number;
  source: CoverageCandidateSource;
  budget: {
    maxCandidates: number;
    maxWorkUnits: number;
    consumedWorkUnits: number;
  };
  consideredFactCount: number;
  supportedFactCount: number;
  emittedCandidateCount: number;
  truncated: boolean;
  unsupportedFactIds: string[];
  candidates: CoverageCandidate[];
}

export interface CoverageGenerationOptions {
  seed: number;
  maxCandidates: number;
  maxWorkUnits: number;
  selectedFactIds?: string[];
  generators?: CoverageCandidateGenerator[];
}

export interface CoverageGeneratorContext {
  graphDraft: GraphDraft;
  contract: ContractDraft;
  draftSet: ScenarioDraftSet;
  projection: CoverageProjection;
  seed: number;
}

export interface CoverageCandidateSeed {
  rationale: string;
  contributionFactIds: string[];
  workUnits: number;
  proposal: ScenarioDraft;
}

/** Extension point for audited pairwise, mutation, or domain-specific generators. */
export interface CoverageCandidateGenerator {
  id: string;
  version: string;
  supports(fact: CoverageFact): boolean;
  generate(fact: CoverageFact, context: CoverageGeneratorContext): CoverageCandidateSeed | null;
}

const DIMENSIONS: CoverageDimensionId[] = [
  'CASE',
  'CONTRACT',
  'DAG',
  'DEPENDENCY',
  'ASSERTION',
  'EVIDENCE',
];

const CASE_TYPES: ScenarioCaseType[] = [
  'GOLDEN',
  'NEGATIVE',
  'BOUNDARY',
  'REGRESSION',
  'PROPERTY',
];

const SCHEMA_GENERATOR: CoverageCandidateGenerator = {
  id: 'bloge.schema-boundary',
  version: '1.0.0',
  supports: (fact) => Boolean(fact.generation && fact.generation.kind !== 'DEPENDENCY_BEHAVIOR'),
  generate: (fact, context) => {
    const generation = fact.generation;
    if (!generation || generation.kind === 'DEPENDENCY_BEHAVIOR') return null;
    const base = baseScenario(context);
    const input = generation.kind === 'DELETE_INPUT'
      ? deleteAtPath(base.given.input, generation.path)
      : generation.kind === 'SET_INPUT'
        ? setAtPath(base.given.input, generation.path, generation.value)
        : base.given.input;
    const proposal = candidateScenario(base, fact, generation.caseType, input, base.dependencies);
    if (generation.kind === 'ERROR_CONTRACT' && generation.errorCode) {
      proposal.tags = uniqueStrings([...proposal.tags, `error:${generation.errorCode}`]);
    }
    return {
      rationale: fact.description,
      contributionFactIds: [fact.factId],
      workUnits: 1,
      proposal,
    };
  },
};

const DEPENDENCY_GENERATOR: CoverageCandidateGenerator = {
  id: 'bloge.dependency-behavior',
  version: '1.0.0',
  supports: (fact) => fact.generation?.kind === 'DEPENDENCY_BEHAVIOR',
  generate: (fact, context) => {
    const generation = fact.generation;
    if (!generation?.dependencyCoordinate || !generation.dependencyBehavior) return null;
    const base = baseScenario(context);
    const dependency = dependencyTemplate(
      generation.dependencyCoordinate,
      generation.dependencyBehavior,
      fact.factId,
    );
    return {
      rationale: fact.description,
      contributionFactIds: [fact.factId],
      workUnits: 1,
      proposal: candidateScenario(
        base,
        fact,
        generation.caseType,
        base.given.input,
        [
          ...base.dependencies.filter((entry) => dependencyCoordinate(entry) !== generation.dependencyCoordinate),
          dependency,
        ],
      ),
    };
  },
};

export const BUILT_IN_COVERAGE_GENERATORS: CoverageCandidateGenerator[] = [
  SCHEMA_GENERATOR,
  DEPENDENCY_GENERATOR,
];

export async function buildCoverageProjection(
  graphDraft: GraphDraft,
  contract: ContractDraft,
  draftSet: ScenarioDraftSet,
  evidenceByCase: ScenarioTableEvidenceByCase = {},
): Promise<CoverageProjection> {
  const facts = uniqueFacts([
    ...caseFacts(draftSet),
    ...contractFacts(contract, draftSet),
    ...dagFacts(graphDraft, draftSet),
    ...dependencyFacts(graphDraft, draftSet),
    ...assertionFacts(graphDraft, contract, draftSet),
    ...evidenceFacts(draftSet, evidenceByCase),
  ]);
  const dimensions = DIMENSIONS.map((dimension) => {
    const dimensionFacts = facts
      .filter((fact) => fact.dimension === dimension)
      .sort((left, right) => left.factId.localeCompare(right.factId));
    const gaps = dimensionFacts.filter((fact) => fact.coveredByCaseIds.length === 0);
    return {
      dimension,
      covered: dimensionFacts.length - gaps.length,
      total: dimensionFacts.length,
      facts: dimensionFacts,
      gaps,
    };
  });
  const material = {
    target: draftSet.target,
    contractFingerprint: draftSet.contractFingerprint,
    scenarioDraftSetId: draftSet.scenarioDraftSetId,
    scenarioDraftSetRevision: draftSet.revision,
    facts: facts.map((fact) => ({
      factId: fact.factId,
      coveredByCaseIds: fact.coveredByCaseIds,
    })),
  };
  return {
    schemaVersion: 'bloge.coverageProjection.v1',
    target: { ...draftSet.target },
    contractFingerprint: draftSet.contractFingerprint,
    scenarioDraftSetId: draftSet.scenarioDraftSetId,
    scenarioDraftSetRevision: draftSet.revision,
    dimensions,
    projectionFingerprint: await sha256Fingerprint(material),
  };
}

export async function generateCoverageCandidates(
  graphDraft: GraphDraft,
  contract: ContractDraft,
  draftSet: ScenarioDraftSet,
  projection: CoverageProjection,
  options: CoverageGenerationOptions,
): Promise<CoverageCandidateSet> {
  assertGenerationOptions(options);
  assertProjectionMatchesDraftSet(projection, draftSet);
  const generators = options.generators ?? BUILT_IN_COVERAGE_GENERATORS;
  const selected = options.selectedFactIds ? new Set(options.selectedFactIds) : null;
  const gaps = projection.dimensions
    .flatMap((dimension) => dimension.gaps)
    .filter((fact) => !selected || selected.has(fact.factId));
  const supported = gaps.filter((fact) => generators.some((generator) => generator.supports(fact)));
  const ordered = [...supported].sort((left, right) => {
    const leftOrder = stableOrder(`${options.seed}:${left.factId}`);
    const rightOrder = stableOrder(`${options.seed}:${right.factId}`);
    return leftOrder === rightOrder
      ? left.factId.localeCompare(right.factId)
      : leftOrder - rightOrder;
  });
  const source = coverageCandidateSource(draftSet, projection);
  const candidates: CoverageCandidate[] = [];
  let consumedWorkUnits = 0;
  let truncated = false;
  const context: CoverageGeneratorContext = {
    graphDraft,
    contract,
    draftSet,
    projection,
    seed: options.seed,
  };

  for (const fact of ordered) {
    const generator = generators.find((candidate) => candidate.supports(fact));
    if (!generator) continue;
    const generated = generator.generate(fact, context);
    if (!generated) continue;
    if (
      candidates.length >= options.maxCandidates
      || consumedWorkUnits + generated.workUnits > options.maxWorkUnits
    ) {
      truncated = true;
      continue;
    }
    const coordinate = await sha256Fingerprint({
      source,
      generatorId: generator.id,
      generatorVersion: generator.version,
      seed: options.seed,
      contributionFactIds: generated.contributionFactIds,
      proposal: generated.proposal,
    });
    const shortId = coordinate.slice('sha256:'.length, 'sha256:'.length + 16);
    const proposal = {
      ...generated.proposal,
      scenarioId: `generated-${shortId}`,
    };
    const candidateFingerprint = await sha256Fingerprint({
      source,
      generatorId: generator.id,
      generatorVersion: generator.version,
      seed: options.seed,
      contributionFactIds: generated.contributionFactIds,
      proposal,
    });
    candidates.push({
      candidateId: `candidate-${shortId}`,
      candidateFingerprint,
      generatorId: generator.id,
      generatorVersion: generator.version,
      seed: options.seed,
      source,
      rationale: generated.rationale,
      contributionFactIds: [...generated.contributionFactIds],
      workUnits: generated.workUnits,
      expectedBehavior: {
        status: 'NEEDS_AUTHOR',
        reason: 'Add an explicit business assertion before this case can support promotion.',
      },
      promotionEligible: false,
      proposal,
    });
    consumedWorkUnits += generated.workUnits;
  }

  const candidateSetId = await sha256Fingerprint({
    source,
    seed: options.seed,
    maxCandidates: options.maxCandidates,
    maxWorkUnits: options.maxWorkUnits,
    candidateFingerprints: candidates.map((candidate) => candidate.candidateFingerprint),
  });
  return {
    schemaVersion: 'bloge.coverageCandidateSet.v1',
    candidateSetId,
    generatorVersions: Object.fromEntries(generators.map((generator) => [generator.id, generator.version])),
    seed: options.seed,
    source,
    budget: {
      maxCandidates: options.maxCandidates,
      maxWorkUnits: options.maxWorkUnits,
      consumedWorkUnits,
    },
    consideredFactCount: gaps.length,
    supportedFactCount: supported.length,
    emittedCandidateCount: candidates.length,
    truncated,
    unsupportedFactIds: gaps
      .filter((fact) => !generators.some((generator) => generator.supports(fact)))
      .map((fact) => fact.factId)
      .sort(),
    candidates,
  };
}

/** The only supported transition from ephemeral generation output into canonical Scenario state. */
export function acceptCoverageCandidate(
  draftSet: ScenarioDraftSet,
  currentProjection: CoverageProjection,
  candidate: CoverageCandidate,
): ScenarioDraftSet {
  assertProjectionMatchesDraftSet(currentProjection, draftSet);
  const source = candidate.source;
  if (
    source.coverageProjectionFingerprint !== currentProjection.projectionFingerprint
    || source.targetFingerprint !== draftSet.target.fingerprint
    || source.contractFingerprint !== draftSet.contractFingerprint
    || source.scenarioDraftSetId !== draftSet.scenarioDraftSetId
    || source.scenarioDraftSetRevision !== draftSet.revision
  ) {
    throw new Error('Coverage candidate is stale; regenerate it from the current Scenario set.');
  }
  if (draftSet.scenarios.some((scenario) => scenario.scenarioId === candidate.proposal.scenarioId)) {
    throw new Error(`Scenario ${candidate.proposal.scenarioId} already exists.`);
  }
  return {
    ...draftSet,
    scenarios: [...draftSet.scenarios, cloneJson(candidate.proposal)],
    metadata: {
      ...draftSet.metadata,
      updatedAt: new Date().toISOString(),
      provenance: {
        ...draftSet.metadata.provenance,
        lastCoverageCandidateAcceptance: {
          candidateId: candidate.candidateId,
          candidateFingerprint: candidate.candidateFingerprint,
          generatorId: candidate.generatorId,
          generatorVersion: candidate.generatorVersion,
          sourceProjectionFingerprint: candidate.source.coverageProjectionFingerprint,
          acceptedScenarioId: candidate.proposal.scenarioId,
        },
      },
    },
  };
}

export function coverageCandidateIsCurrent(
  candidate: CoverageCandidate,
  projection: CoverageProjection,
): boolean {
  return candidate.source.coverageProjectionFingerprint === projection.projectionFingerprint
    && candidate.source.targetFingerprint === projection.target.fingerprint
    && candidate.source.contractFingerprint === projection.contractFingerprint
    && candidate.source.scenarioDraftSetId === projection.scenarioDraftSetId
    && candidate.source.scenarioDraftSetRevision === projection.scenarioDraftSetRevision;
}

function caseFacts(draftSet: ScenarioDraftSet): CoverageFact[] {
  return CASE_TYPES.map((caseType) => fact({
    dimension: 'CASE',
    kind: 'CASE_INTENT',
    coordinate: caseType,
    label: `${titleCase(caseType)} intent`,
    description: `At least one explicitly authored ${caseType.toLocaleLowerCase()} case.`,
    coveredByCaseIds: draftSet.scenarios
      .filter((scenario) => scenario.caseType === caseType)
      .map((scenario) => scenario.scenarioId),
    action: 'AUTHOR_CASE',
  }));
}

function contractFacts(contract: ContractDraft, draftSet: ScenarioDraftSet): CoverageFact[] {
  const facts: CoverageFact[] = [];
  walkInputSchema(contract.inputSchema.schema, [], true, draftSet.scenarios, facts);
  for (const leafPath of schemaLeafPaths(contract.outputSchema.schema)) {
    facts.push(fact({
      dimension: 'CONTRACT',
      kind: 'OUTPUT_FIELD_EXPECTED',
      coordinate: displayPath(leafPath),
      label: `Output ${displayPath(leafPath)}`,
      description: `The public output field ${displayPath(leafPath)} has an explicit expected behavior.`,
      coveredByCaseIds: draftSet.scenarios
        .filter((scenario) => scenario.then.assertions.some((assertion) => (
          assertion.scope === 'OUTPUT_PATH' && assertionCoversPath(assertion.path, leafPath)
        )))
        .map((scenario) => scenario.scenarioId),
      action: 'AUTHOR_ASSERTION',
    }));
  }
  for (const error of contract.errorContract) {
    facts.push(fact({
      dimension: 'CONTRACT',
      kind: 'ERROR_VARIANT',
      coordinate: error.code,
      label: `Error ${error.code}`,
      description: `The ${error.code} error contract is exercised by a negative case.`,
      coveredByCaseIds: draftSet.scenarios
        .filter((scenario) => scenario.caseType === 'NEGATIVE' && scenarioMentions(scenario, error.code))
        .map((scenario) => scenario.scenarioId),
      action: 'GENERATE',
      generation: {
        kind: 'ERROR_CONTRACT',
        path: [],
        caseType: 'NEGATIVE',
        errorCode: error.code,
      },
    }));
  }
  return facts;
}

function walkInputSchema(
  rawSchema: unknown,
  path: string[],
  presentByDefinition: boolean,
  scenarios: ScenarioDraft[],
  output: CoverageFact[],
): void {
  const schema = asRecord(rawSchema);
  if (!schema) return;
  const coordinate = displayPath(path);
  if (path.length > 0) {
    output.push(fact({
      dimension: 'CONTRACT',
      kind: 'INPUT_FIELD_PRESENT',
      coordinate,
      label: `Input ${coordinate}`,
      description: `The input field ${coordinate} is present in a case.`,
      coveredByCaseIds: scenarios
        .filter((scenario) => hasAtPath(scenario.given.input, path))
        .map((scenario) => scenario.scenarioId),
      action: 'GENERATE',
      generation: {
        kind: 'SET_INPUT',
        path,
        value: sampleForSchema(schema),
        caseType: 'GOLDEN',
      },
    }));
  }

  const nullable = schema.nullable === true || schemaTypes(schema).includes('null');
  if (path.length > 0 && nullable) {
    output.push(inputValueFact(
      'NULL_ACCEPTED',
      path,
      'Null accepted',
      'BOUNDARY',
      null,
      scenarios,
      (value, exists) => exists && value === null,
    ));
  }
  if (path.length > 0 && presentByDefinition && !nullable) {
    output.push(inputValueFact(
      'NULL_REJECTED',
      path,
      'Null rejected',
      'NEGATIVE',
      null,
      scenarios,
      (value, exists, scenario) => exists && value === null && scenario.caseType === 'NEGATIVE',
    ));
    output.push(fact({
      dimension: 'CONTRACT',
      kind: 'REQUIRED_MISSING_REJECTED',
      coordinate,
      label: `Missing ${coordinate} rejected`,
      description: `A negative case proves that required field ${coordinate} cannot be omitted.`,
      coveredByCaseIds: scenarios
        .filter((scenario) => scenario.caseType === 'NEGATIVE' && !hasAtPath(scenario.given.input, path))
        .map((scenario) => scenario.scenarioId),
      action: 'GENERATE',
      generation: { kind: 'DELETE_INPUT', path, caseType: 'NEGATIVE' },
    }));
  }

  const enumValues = Array.isArray(schema.enum) ? schema.enum : [];
  for (const enumValue of enumValues) {
    output.push(inputValueFact(
      'ENUM_VALUE',
      path,
      `Enum ${renderValue(enumValue)}`,
      'BOUNDARY',
      enumValue,
      scenarios,
      (value, exists) => exists && jsonEquals(value, enumValue),
    ));
  }
  if (enumValues.length > 0) {
    const invalid = invalidEnumValue(enumValues);
    output.push(inputValueFact(
      'INVALID_ENUM_REJECTED',
      path,
      'Invalid enum rejected',
      'NEGATIVE',
      invalid,
      scenarios,
      (value, exists, scenario) => exists
        && scenario.caseType === 'NEGATIVE'
        && !enumValues.some((entry) => jsonEquals(entry, value)),
    ));
  }

  numericBoundaryFacts(schema, path, scenarios, output);
  stringBoundaryFacts(schema, path, scenarios, output);

  const variants = schemaVariants(schema);
  variants.forEach((variant, index) => {
    const sample = sampleForSchema(variant);
    output.push(inputValueFact(
      'UNION_VARIANT',
      path,
      `Union variant ${index + 1}`,
      'BOUNDARY',
      sample,
      scenarios,
      (value, exists) => exists && valueMatchesSchema(value, variant),
      `The input ${coordinate} exercises union variant ${index + 1}.`,
    ));
  });

  const properties = asRecord(schema.properties);
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
  if (properties) {
    for (const propertyName of Object.keys(properties).sort()) {
      walkInputSchema(
        properties[propertyName],
        [...path, propertyName],
        required.has(propertyName),
        scenarios,
        output,
      );
    }
  }
}

function numericBoundaryFacts(
  schema: Record<string, unknown>,
  path: string[],
  scenarios: ScenarioDraft[],
  output: CoverageFact[],
): void {
  if (typeof schema.minimum === 'number') {
    output.push(inputValueFact(
      'MINIMUM_ACCEPTED', path, `Minimum ${schema.minimum}`, 'BOUNDARY', schema.minimum, scenarios,
      (value, exists) => exists && value === schema.minimum,
    ));
    output.push(inputValueFact(
      'BELOW_MINIMUM_REJECTED', path, `Below minimum ${schema.minimum}`, 'NEGATIVE',
      schema.minimum - numericStep(schema), scenarios,
      (value, exists, scenario) => exists && scenario.caseType === 'NEGATIVE'
        && typeof value === 'number' && value < (schema.minimum as number),
    ));
  }
  if (typeof schema.maximum === 'number') {
    output.push(inputValueFact(
      'MAXIMUM_ACCEPTED', path, `Maximum ${schema.maximum}`, 'BOUNDARY', schema.maximum, scenarios,
      (value, exists) => exists && value === schema.maximum,
    ));
    output.push(inputValueFact(
      'ABOVE_MAXIMUM_REJECTED', path, `Above maximum ${schema.maximum}`, 'NEGATIVE',
      schema.maximum + numericStep(schema), scenarios,
      (value, exists, scenario) => exists && scenario.caseType === 'NEGATIVE'
        && typeof value === 'number' && value > (schema.maximum as number),
    ));
  }
}

function stringBoundaryFacts(
  schema: Record<string, unknown>,
  path: string[],
  scenarios: ScenarioDraft[],
  output: CoverageFact[],
): void {
  if (typeof schema.minLength === 'number') {
    const minimum = Math.max(0, schema.minLength);
    output.push(inputValueFact(
      'MIN_LENGTH_ACCEPTED', path, `Minimum length ${minimum}`, 'BOUNDARY', 'x'.repeat(minimum), scenarios,
      (value, exists) => exists && typeof value === 'string' && value.length === minimum,
    ));
    if (minimum > 0) {
      output.push(inputValueFact(
        'BELOW_MIN_LENGTH_REJECTED', path, `Below length ${minimum}`, 'NEGATIVE', 'x'.repeat(minimum - 1), scenarios,
        (value, exists, scenario) => exists && scenario.caseType === 'NEGATIVE'
          && typeof value === 'string' && value.length < minimum,
      ));
    }
  }
  if (typeof schema.maxLength === 'number') {
    const maximum = Math.max(0, schema.maxLength);
    output.push(inputValueFact(
      'MAX_LENGTH_ACCEPTED', path, `Maximum length ${maximum}`, 'BOUNDARY', 'x'.repeat(maximum), scenarios,
      (value, exists) => exists && typeof value === 'string' && value.length === maximum,
    ));
    output.push(inputValueFact(
      'ABOVE_MAX_LENGTH_REJECTED', path, `Above length ${maximum}`, 'NEGATIVE', 'x'.repeat(maximum + 1), scenarios,
      (value, exists, scenario) => exists && scenario.caseType === 'NEGATIVE'
        && typeof value === 'string' && value.length > maximum,
    ));
  }
}

function inputValueFact(
  kind: string,
  path: string[],
  label: string,
  caseType: ScenarioCaseType,
  value: unknown,
  scenarios: ScenarioDraft[],
  predicate: (value: unknown, exists: boolean, scenario: ScenarioDraft) => boolean,
  description = `${label} at ${displayPath(path)}.`,
): CoverageFact {
  return fact({
    dimension: 'CONTRACT',
    kind,
    coordinate: displayPath(path),
    label,
    description,
    coveredByCaseIds: scenarios
      .filter((scenario) => predicate(
        valueAtPath(scenario.given.input, path),
        hasAtPath(scenario.given.input, path),
        scenario,
      ))
      .map((scenario) => scenario.scenarioId),
    action: 'GENERATE',
    generation: { kind: 'SET_INPUT', path, value, caseType },
  });
}

function dagFacts(graphDraft: GraphDraft, draftSet: ScenarioDraftSet): CoverageFact[] {
  const allCaseIds = draftSet.scenarios.map((scenario) => scenario.scenarioId);
  const facts: CoverageFact[] = [];
  for (const node of graphDraft.nodes) {
    const covered = draftSet.scenarios
      .filter((scenario) => scenario.tags.includes(`node:${node.id}`)
        || scenario.dependencies.some((entry) => entry.selector.nodeId === node.id)
        || scenario.then.assertions.some((assertion) => assertion.nodeId === node.id))
      .map((scenario) => scenario.scenarioId);
    facts.push(fact({
      dimension: 'DAG',
      kind: 'NODE_PATH',
      coordinate: node.id,
      label: `Node ${node.label || node.id}`,
      description: `At least one case addresses DAG node ${node.id}.`,
      coveredByCaseIds: covered.length > 0 || graphDraft.edges.every((edge) => !edge.condition)
        ? (covered.length > 0 ? covered : allCaseIds)
        : [],
      action: 'AUTHOR_CASE',
    }));
    const config = asRecord(node.config);
    if (config && ('fallback' in config || 'fallbackNodeId' in config)) {
      facts.push(fact({
        dimension: 'DAG',
        kind: 'FALLBACK_PATH',
        coordinate: node.id,
        label: `Fallback from ${node.id}`,
        description: `A case explicitly exercises the fallback configured for ${node.id}.`,
        coveredByCaseIds: draftSet.scenarios
          .filter((scenario) => scenario.tags.includes(`fallback:${node.id}`)
            || scenario.dependencies.some((entry) => (
              entry.selector.nodeId === node.id && entry.consumption.onExhausted === 'FALLBACK_TO_REAL'
            )))
          .map((scenario) => scenario.scenarioId),
        action: 'AUTHOR_CASE',
      }));
    }
    if (config && ('retry' in config || 'maxAttempts' in config)) {
      facts.push(fact({
        dimension: 'DAG',
        kind: 'RETRY_PATH',
        coordinate: node.id,
        label: `Retry at ${node.id}`,
        description: `A case explicitly exercises more than one attempt at ${node.id}.`,
        coveredByCaseIds: draftSet.scenarios
          .filter((scenario) => scenario.dependencies.some((entry) => (
            entry.selector.nodeId === node.id && entry.selector.attempts.some((attempt) => attempt > 1)
          )))
          .map((scenario) => scenario.scenarioId),
        action: 'AUTHOR_CASE',
      }));
    }
  }
  for (const edge of graphDraft.edges) {
    const explicit = draftSet.scenarios
      .filter((scenario) => scenario.tags.includes(`edge:${edge.id}`)
        || scenario.then.assertions.some((assertion) => (
          assertion.scope === 'EDGE_TRANSFER'
          && assertion.fromNodeId === edge.source.nodeId
          && assertion.toNodeId === edge.target.nodeId
        )))
      .map((scenario) => scenario.scenarioId);
    facts.push(fact({
      dimension: 'DAG',
      kind: edge.condition ? 'CONDITIONAL_EDGE' : 'EDGE_PATH',
      coordinate: edge.id,
      label: `${edge.source.nodeId} -> ${edge.target.nodeId}`,
      description: edge.condition
        ? `A case explicitly exercises conditional edge ${edge.id}: ${edge.condition}.`
        : `At least one case traverses edge ${edge.id}.`,
      coveredByCaseIds: edge.condition ? explicit : (explicit.length > 0 ? explicit : allCaseIds),
      action: 'AUTHOR_CASE',
    }));
  }
  return facts;
}

function dependencyFacts(graphDraft: GraphDraft, draftSet: ScenarioDraftSet): CoverageFact[] {
  const coordinates = new Set<string>([
    ...Object.keys(graphDraft.nodeFixtures ?? {}),
    ...draftSet.scenarios.flatMap((scenario) => scenario.dependencies.map(dependencyCoordinate)),
  ].filter(Boolean));
  const behaviorKinds: DependencyBehaviorDraft['behavior']['kind'][] = [
    'RETURN',
    'ERROR',
    'TIMEOUT',
    'MUST_NOT_CALL',
  ];
  return [...coordinates].sort().flatMap((coordinate) => behaviorKinds.map((behaviorKind) => fact({
    dimension: 'DEPENDENCY',
    kind: 'DEPENDENCY_BEHAVIOR',
    coordinate: `${coordinate}:${behaviorKind}`,
    label: `${coordinate} ${titleCase(behaviorKind)}`,
    description: `A case controls ${coordinate} with ${behaviorKind} behavior.`,
    coveredByCaseIds: draftSet.scenarios
      .filter((scenario) => scenario.dependencies.some((dependency) => (
        dependencyCoordinate(dependency) === coordinate && dependency.behavior.kind === behaviorKind
      )))
      .map((scenario) => scenario.scenarioId),
    action: 'GENERATE',
    generation: {
      kind: 'DEPENDENCY_BEHAVIOR',
      path: [],
      caseType: behaviorKind === 'RETURN' ? 'GOLDEN' : 'NEGATIVE',
      dependencyCoordinate: coordinate,
      dependencyBehavior: behaviorKind,
    },
  })));
}

function assertionFacts(
  graphDraft: GraphDraft,
  contract: ContractDraft,
  draftSet: ScenarioDraftSet,
): CoverageFact[] {
  const facts: CoverageFact[] = [];
  for (const path of schemaLeafPaths(contract.outputSchema.schema)) {
    facts.push(fact({
      dimension: 'ASSERTION',
      kind: 'OUTPUT_ORACLE',
      coordinate: displayPath(path),
      label: `Output oracle ${displayPath(path)}`,
      description: `An assertion checks public output ${displayPath(path)}.`,
      coveredByCaseIds: draftSet.scenarios
        .filter((scenario) => scenario.then.assertions.some((assertion) => (
          assertion.scope === 'OUTPUT_PATH' && assertionCoversPath(assertion.path, path)
        )))
        .map((scenario) => scenario.scenarioId),
      action: 'AUTHOR_ASSERTION',
    }));
  }
  for (const node of graphDraft.nodes) {
    facts.push(fact({
      dimension: 'ASSERTION',
      kind: 'NODE_STATUS_ORACLE',
      coordinate: node.id,
      label: `Node status ${node.id}`,
      description: `An assertion checks the execution status of ${node.id}.`,
      coveredByCaseIds: draftSet.scenarios
        .filter((scenario) => scenario.then.assertions.some((assertion) => (
          assertion.scope === 'NODE_STATUS' && assertion.nodeId === node.id
        )))
        .map((scenario) => scenario.scenarioId),
      action: 'AUTHOR_ASSERTION',
    }));
  }
  for (const edge of graphDraft.edges) {
    facts.push(fact({
      dimension: 'ASSERTION',
      kind: 'EDGE_TRANSFER_ORACLE',
      coordinate: edge.id,
      label: `Edge transfer ${edge.id}`,
      description: `An assertion checks transfer from ${edge.source.nodeId} to ${edge.target.nodeId}.`,
      coveredByCaseIds: draftSet.scenarios
        .filter((scenario) => scenario.then.assertions.some((assertion) => (
          assertion.scope === 'EDGE_TRANSFER'
          && assertion.fromNodeId === edge.source.nodeId
          && assertion.toNodeId === edge.target.nodeId
        )))
        .map((scenario) => scenario.scenarioId),
      action: 'AUTHOR_ASSERTION',
    }));
  }
  const coordinates = new Set(draftSet.scenarios.flatMap((scenario) => (
    scenario.dependencies.map(dependencyCoordinate)
  )).filter(Boolean));
  for (const coordinate of [...coordinates].sort()) {
    facts.push(fact({
      dimension: 'ASSERTION',
      kind: 'INVOCATION_ORACLE',
      coordinate,
      label: `Invocation ${coordinate}`,
      description: `An assertion checks whether dependency ${coordinate} was invoked.`,
      coveredByCaseIds: draftSet.scenarios
        .filter((scenario) => scenario.then.assertions.some((assertion) => (
          assertion.scope === 'INVOCATION' && assertion.nodeId === coordinate
        )))
        .map((scenario) => scenario.scenarioId),
      action: 'AUTHOR_ASSERTION',
    }));
  }
  return facts;
}

function evidenceFacts(
  draftSet: ScenarioDraftSet,
  evidenceByCase: ScenarioTableEvidenceByCase,
): CoverageFact[] {
  return draftSet.scenarios.flatMap((scenario) => {
    const evidence = evidenceByCase[scenario.scenarioId];
    const current = evidence?.freshness === 'CURRENT';
    return [
      fact({
        dimension: 'EVIDENCE',
        kind: 'SUCCESSFUL_EXECUTION',
        coordinate: scenario.scenarioId,
        label: `${scenario.name} execution`,
        description: `Current successful execution evidence exists for ${scenario.scenarioId}.`,
        coveredByCaseIds: current && evidence?.execution === 'SUCCESS' ? [scenario.scenarioId] : [],
        action: 'RUN',
      }),
      fact({
        dimension: 'EVIDENCE',
        kind: 'BUSINESS_ASSERTION',
        coordinate: scenario.scenarioId,
        label: `${scenario.name} oracle`,
        description: `Current passing business assertion evidence exists for ${scenario.scenarioId}.`,
        coveredByCaseIds: current && evidence?.assertions === 'PASSED' ? [scenario.scenarioId] : [],
        action: scenario.then.assertions.length > 0 ? 'RUN' : 'AUTHOR_ASSERTION',
      }),
      fact({
        dimension: 'EVIDENCE',
        kind: 'EXECUTION_PROOF',
        coordinate: scenario.scenarioId,
        label: `${scenario.name} proof`,
        description: `Current execution proof stronger than schema validation exists for ${scenario.scenarioId}.`,
        coveredByCaseIds: current && evidence && evidence.proofStrength !== 'SCHEMA'
          ? [scenario.scenarioId]
          : [],
        action: 'RUN',
      }),
    ];
  });
}

function candidateScenario(
  base: ScenarioDraft,
  coverageFact: CoverageFact,
  caseType: ScenarioCaseType,
  input: unknown,
  dependencies: DependencyBehaviorDraft[],
): ScenarioDraft {
  return {
    ...cloneJson(base),
    scenarioId: 'generated-pending',
    name: `${titleCase(caseType)}: ${coverageFact.label}`,
    description: `Generated candidate for ${coverageFact.factId}.`,
    caseType,
    tags: uniqueStrings([...base.tags, 'generated:coverage', `coverage:${coverageFact.factId}`]),
    given: { input: cloneJson(input), provenance: 'GENERATED' },
    dependencies: cloneJson(dependencies),
    then: { assertions: [] },
  };
}

function baseScenario(context: CoverageGeneratorContext): ScenarioDraft {
  const existing = [...context.draftSet.scenarios]
    .sort((left, right) => left.scenarioId.localeCompare(right.scenarioId))[0];
  if (existing) return cloneJson(existing);
  return {
    scenarioId: 'generated-base',
    name: 'Generated case',
    description: '',
    caseType: 'GOLDEN',
    tags: [],
    given: {
      input: sampleForSchema(context.contract.inputSchema.schema),
      provenance: 'GENERATED',
    },
    dependencies: [],
    then: { assertions: [] },
  };
}

function dependencyTemplate(
  coordinate: string,
  kind: DependencyBehaviorDraft['behavior']['kind'],
  factId: string,
): DependencyBehaviorDraft {
  const behavior: DependencyBehaviorDraft['behavior'] = { kind, boundary: 'NODE' };
  if (kind === 'RETURN') behavior.output = {};
  if (kind === 'ERROR') {
    behavior.errorCode = 'AUTHOR_REQUIRED';
    behavior.errorType = 'GeneratedDependencyError';
    behavior.errorMessage = 'Replace with the expected dependency error.';
  }
  if (kind === 'TIMEOUT') behavior.after = 'PT1S';
  return {
    dependencyId: `generated-${stableOrder(factId).toString(16)}`,
    selector: {
      graphPath: '/root',
      nodeId: coordinate,
      operatorRef: '',
      resourceRef: '',
      functionRef: '',
      attempts: [],
      occurrences: [],
      correlationKey: '',
      pathEquals: {},
    },
    behavior,
    consumption: {
      required: kind !== 'MUST_NOT_CALL',
      minUses: kind === 'MUST_NOT_CALL' ? 0 : 1,
      maxUses: kind === 'MUST_NOT_CALL' ? 0 : 1,
      onExhausted: 'FAIL',
      onUnmatched: 'FAIL',
    },
    schemaCheck: { mode: 'STRICT', waiverReason: '' },
    origin: 'COVERAGE_GENERATOR',
  };
}

function coverageCandidateSource(
  draftSet: ScenarioDraftSet,
  projection: CoverageProjection,
): CoverageCandidateSource {
  return {
    targetFingerprint: draftSet.target.fingerprint,
    contractFingerprint: draftSet.contractFingerprint,
    scenarioDraftSetId: draftSet.scenarioDraftSetId,
    scenarioDraftSetRevision: draftSet.revision,
    coverageProjectionFingerprint: projection.projectionFingerprint,
  };
}

function assertProjectionMatchesDraftSet(
  projection: CoverageProjection,
  draftSet: ScenarioDraftSet,
): void {
  if (
    projection.target.fingerprint !== draftSet.target.fingerprint
    || projection.contractFingerprint !== draftSet.contractFingerprint
    || projection.scenarioDraftSetId !== draftSet.scenarioDraftSetId
    || projection.scenarioDraftSetRevision !== draftSet.revision
  ) {
    throw new Error('Coverage projection does not match the current Scenario set.');
  }
}

function assertGenerationOptions(options: CoverageGenerationOptions): void {
  if (!Number.isSafeInteger(options.seed)) throw new Error('Generation seed must be a safe integer.');
  if (!Number.isInteger(options.maxCandidates) || options.maxCandidates < 1 || options.maxCandidates > 500) {
    throw new Error('maxCandidates must be between 1 and 500.');
  }
  if (!Number.isInteger(options.maxWorkUnits) || options.maxWorkUnits < 1 || options.maxWorkUnits > 10_000) {
    throw new Error('maxWorkUnits must be between 1 and 10000.');
  }
}

function fact(input: Omit<CoverageFact, 'factId'>): CoverageFact {
  return {
    ...input,
    factId: `${input.dimension.toLocaleLowerCase()}:${input.kind.toLocaleLowerCase()}:${encodeURIComponent(input.coordinate)}`,
    coveredByCaseIds: uniqueStrings(input.coveredByCaseIds).sort(),
  };
}

function uniqueFacts(facts: CoverageFact[]): CoverageFact[] {
  const byId = new Map<string, CoverageFact>();
  for (const entry of facts) {
    const existing = byId.get(entry.factId);
    if (!existing) {
      byId.set(entry.factId, entry);
      continue;
    }
    byId.set(entry.factId, {
      ...existing,
      coveredByCaseIds: uniqueStrings([
        ...existing.coveredByCaseIds,
        ...entry.coveredByCaseIds,
      ]).sort(),
    });
  }
  return [...byId.values()];
}

function dependencyCoordinate(dependency: DependencyBehaviorDraft): string {
  return dependency.selector.nodeId
    || dependency.selector.operatorRef
    || dependency.selector.resourceRef
    || dependency.selector.functionRef;
}

function schemaLeafPaths(rawSchema: unknown, path: string[] = []): string[][] {
  const schema = asRecord(rawSchema);
  if (!schema) return path.length > 0 ? [path] : [];
  const properties = asRecord(schema.properties);
  if (properties && Object.keys(properties).length > 0) {
    return Object.keys(properties).sort().flatMap((key) => schemaLeafPaths(properties[key], [...path, key]));
  }
  const variants = schemaVariants(schema);
  if (variants.length > 0) {
    const paths = variants.flatMap((variant) => schemaLeafPaths(variant, path));
    return paths.length > 0 ? dedupePaths(paths) : (path.length > 0 ? [path] : []);
  }
  return path.length > 0 ? [path] : [];
}

function schemaVariants(schema: Record<string, unknown>): Record<string, unknown>[] {
  const raw = Array.isArray(schema.oneOf) ? schema.oneOf : Array.isArray(schema.anyOf) ? schema.anyOf : [];
  return raw.map(asRecord).filter((entry): entry is Record<string, unknown> => Boolean(entry));
}

function sampleForSchema(rawSchema: unknown): unknown {
  const schema = asRecord(rawSchema) ?? {};
  if ('const' in schema) return cloneJson(schema.const);
  if (Array.isArray(schema.enum) && schema.enum.length > 0) return cloneJson(schema.enum[0]);
  const variant = schemaVariants(schema)[0];
  if (variant) return sampleForSchema(variant);
  const types = schemaTypes(schema);
  if (types.includes('object') || asRecord(schema.properties)) {
    const properties = asRecord(schema.properties) ?? {};
    const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
    return Object.fromEntries(Object.keys(properties).sort()
      .filter((key) => required.has(key))
      .map((key) => [key, sampleForSchema(properties[key])]));
  }
  if (types.includes('array')) return [];
  if (types.includes('integer') || types.includes('number')) {
    return typeof schema.minimum === 'number' ? schema.minimum : 0;
  }
  if (types.includes('boolean')) return false;
  if (types.includes('null')) return null;
  if (types.includes('string') || types.length === 0) {
    return 'x'.repeat(typeof schema.minLength === 'number' ? Math.max(1, schema.minLength) : 1);
  }
  return null;
}

function valueMatchesSchema(value: unknown, rawSchema: unknown): boolean {
  const schema = asRecord(rawSchema);
  if (!schema) return true;
  if (Array.isArray(schema.enum) && !schema.enum.some((entry) => jsonEquals(entry, value))) return false;
  const types = schemaTypes(schema);
  if (types.length > 0 && !types.some((type) => valueMatchesType(value, type))) return false;
  if (typeof value === 'number') {
    if (typeof schema.minimum === 'number' && value < schema.minimum) return false;
    if (typeof schema.maximum === 'number' && value > schema.maximum) return false;
  }
  if (typeof value === 'string') {
    if (typeof schema.minLength === 'number' && value.length < schema.minLength) return false;
    if (typeof schema.maxLength === 'number' && value.length > schema.maxLength) return false;
  }
  return true;
}

function schemaTypes(schema: Record<string, unknown>): string[] {
  if (typeof schema.type === 'string') return [schema.type];
  return Array.isArray(schema.type) ? schema.type.map(String) : [];
}

function valueMatchesType(value: unknown, type: string): boolean {
  switch (type) {
    case 'null': return value === null;
    case 'array': return Array.isArray(value);
    case 'object': return value !== null && typeof value === 'object' && !Array.isArray(value);
    case 'integer': return typeof value === 'number' && Number.isInteger(value);
    case 'number': return typeof value === 'number';
    case 'string': return typeof value === 'string';
    case 'boolean': return typeof value === 'boolean';
    default: return true;
  }
}

function numericStep(schema: Record<string, unknown>): number {
  return schemaTypes(schema).includes('integer') ? 1 : 0.01;
}

function invalidEnumValue(values: unknown[]): unknown {
  const stringCandidate = '__outside_contract_enum__';
  if (!values.some((entry) => entry === stringCandidate)) return stringCandidate;
  return { invalid: true };
}

function assertionCoversPath(assertionPath: string, targetPath: string[]): boolean {
  const assertion = parsePath(assertionPath);
  return assertion.length === 0 || (
    assertion.length <= targetPath.length
    && assertion.every((part, index) => targetPath[index] === part)
  );
}

function parsePath(path: string): string[] {
  const trimmed = path.trim();
  if (!trimmed || trimmed === '$' || trimmed === '/') return [];
  if (trimmed.startsWith('/')) {
    return trimmed.slice(1).split('/').filter(Boolean).map((part) => (
      part.replace(/~1/g, '/').replace(/~0/g, '~')
    ));
  }
  return trimmed.replace(/^\$\.?/, '').split('.').filter(Boolean);
}

function displayPath(path: string[]): string {
  return path.length === 0 ? '$' : `$.${path.join('.')}`;
}

function hasAtPath(value: unknown, path: string[]): boolean {
  let current = value;
  for (const part of path) {
    if (!asRecord(current) || !Object.prototype.hasOwnProperty.call(current, part)) return false;
    current = (current as Record<string, unknown>)[part];
  }
  return true;
}

function valueAtPath(value: unknown, path: string[]): unknown {
  let current = value;
  for (const part of path) {
    const record = asRecord(current);
    if (!record) return undefined;
    current = record[part];
  }
  return current;
}

function setAtPath(value: unknown, path: string[], nextValue: unknown): unknown {
  if (path.length === 0) return cloneJson(nextValue);
  const root = asRecord(cloneJson(value)) ?? {};
  let current = root;
  path.forEach((part, index) => {
    if (index === path.length - 1) {
      current[part] = cloneJson(nextValue);
      return;
    }
    const child = asRecord(current[part]) ?? {};
    current[part] = child;
    current = child;
  });
  return root;
}

function deleteAtPath(value: unknown, path: string[]): unknown {
  if (path.length === 0) return {};
  const root = asRecord(cloneJson(value)) ?? {};
  let current = root;
  path.slice(0, -1).forEach((part) => {
    const child = asRecord(current[part]) ?? {};
    current[part] = child;
    current = child;
  });
  delete current[path[path.length - 1]];
  return root;
}

function scenarioMentions(scenario: ScenarioDraft, value: string): boolean {
  return scenario.tags.includes(`error:${value}`)
    || canonicalJson(scenario.then.assertions).includes(JSON.stringify(value));
}

function stableOrder(value: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function cloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function uniqueStrings(values: string[]): string[] {
  return [...new Set(values)];
}

function dedupePaths(paths: string[][]): string[][] {
  return [...new Map(paths.map((path) => [path.join('\u0000'), path])).values()];
}

function renderValue(value: unknown): string {
  const rendered = canonicalJson(value);
  return rendered.length > 28 ? `${rendered.slice(0, 25)}...` : rendered;
}

function titleCase(value: string): string {
  return value.toLocaleLowerCase().replace(/(^|_)([a-z])/g, (_match, prefix, letter: string) => (
    `${prefix ? ' ' : ''}${letter.toLocaleUpperCase()}`
  ));
}

function jsonEquals(left: unknown, right: unknown): boolean {
  return canonicalJson(left) === canonicalJson(right);
}
