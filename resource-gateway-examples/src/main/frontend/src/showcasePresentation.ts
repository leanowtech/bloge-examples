import type { MessageDescriptor, MessageId } from './i18n/messageCatalog';
import type { GatewayExampleScenario } from './types';

export interface ShowcaseScenarioPresentation {
  title: MessageDescriptor;
  pattern: MessageDescriptor;
  description: MessageDescriptor;
  concepts: MessageDescriptor[];
}

interface ShowcaseMessageIds {
  title: MessageId;
  pattern: MessageId;
  description: MessageId;
  concepts: MessageId[];
}

type BuiltInShowcaseGraph =
  | 'userDashboard'
  | 'loanDecisionPolicy'
  | 'productDetail'
  | 'enrichOrderList'
  | 'creditScore'
  | 'resourceDispatch'
  | 'aiEnrichedSearch';

type ShowcaseConcept =
  | 'parallelFanOut' | 'httpResource' | 'timeout' | 'retry' | 'fallback' | 'aggregation'
  | 'decisionTable' | 'uniqueHit' | 'ruleMatrix' | 'explainableOutput'
  | 'conditionalBranch' | 'branchFallback' | 'resourceDescriptor' | 'unifiedResponse'
  | 'foreach' | 'perItemFallback' | 'parallelEnrichment' | 'collectionTransform'
  | 'degradation' | 'branchOnSuccess' | 'providerProvenance'
  | 'descriptorRegistry' | 'parameterMapping' | 'headerOverride' | 'responseProtocol'
  | 'streamNode' | 'sse' | 'parallelStreamFanIn' | 'citationLane';

const PRESENTATIONS = {
  userDashboard: entry('userDashboard', [
    'parallelFanOut', 'httpResource', 'timeout', 'retry', 'fallback', 'aggregation',
  ]),
  loanDecisionPolicy: entry('loanDecisionPolicy', [
    'decisionTable', 'uniqueHit', 'ruleMatrix', 'httpResource', 'explainableOutput',
  ]),
  productDetail: entry('productDetail', [
    'conditionalBranch', 'branchFallback', 'resourceDescriptor', 'unifiedResponse',
  ]),
  enrichOrderList: entry('enrichOrderList', [
    'foreach', 'perItemFallback', 'parallelEnrichment', 'collectionTransform',
  ]),
  creditScore: entry('creditScore', [
    'degradation', 'fallback', 'branchOnSuccess', 'providerProvenance',
  ]),
  resourceDispatch: entry('resourceDispatch', [
    'descriptorRegistry', 'parameterMapping', 'headerOverride', 'responseProtocol',
  ]),
  aiEnrichedSearch: entry('aiEnrichedSearch', [
    'streamNode', 'sse', 'parallelStreamFanIn', 'citationLane',
  ]),
} satisfies Record<BuiltInShowcaseGraph, ShowcaseMessageIds>;

export function presentShowcaseScenario(
  scenario: Pick<GatewayExampleScenario, 'graphName'>,
): ShowcaseScenarioPresentation | null {
  if (!isBuiltInShowcaseGraph(scenario.graphName)) return null;
  const presentation = PRESENTATIONS[scenario.graphName];
  return {
    title: descriptor(presentation.title),
    pattern: descriptor(presentation.pattern),
    description: descriptor(presentation.description),
    concepts: presentation.concepts.map(descriptor),
  };
}

function isBuiltInShowcaseGraph(graphName: string): graphName is BuiltInShowcaseGraph {
  return Object.prototype.hasOwnProperty.call(PRESENTATIONS, graphName);
}

function entry(graphName: BuiltInShowcaseGraph, concepts: ShowcaseConcept[]): ShowcaseMessageIds {
  return {
    title: `showcase.${graphName}.title` as MessageId,
    pattern: `showcase.${graphName}.pattern` as MessageId,
    description: `showcase.${graphName}.description` as MessageId,
    concepts: concepts.map((concept) => `showcase.concept.${concept}` as MessageId),
  };
}

function descriptor(messageId: MessageId): MessageDescriptor {
  return { messageId };
}
