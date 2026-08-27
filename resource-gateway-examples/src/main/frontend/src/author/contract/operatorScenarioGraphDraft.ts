import type { ContractDraft } from '../../contract-scenario/domain';
import type {
  DraftNodeBinding,
  GraphDraft,
  OperatorDefinition,
} from '../../types';

function schemaProperties(contract: ContractDraft): Record<string, unknown> {
  const properties = contract.inputSchema.schema.properties;
  return properties && typeof properties === 'object' && !Array.isArray(properties)
    ? properties as Record<string, unknown>
    : {};
}

export function operatorScenarioInputBindings(
  operator: OperatorDefinition,
  contract: ContractDraft,
): Record<string, DraftNodeBinding> {
  const ports = operator.ports?.inputs ?? [];
  if (ports.length === 0) return {};

  const properties = schemaProperties(contract);
  if (ports.length === 1) {
    const portName = ports[0].name || 'input';
    if (Object.prototype.hasOwnProperty.call(properties, portName) || Object.keys(properties).length === 0) {
      return {
        [portName]: {
          kind: 'contextPath',
          path: portName,
          targetPort: portName,
        },
      };
    }
    return Object.fromEntries(Object.keys(properties).map((fieldName) => [
      fieldName,
      {
        kind: 'contextPath',
        path: fieldName,
        targetPort: portName,
        targetPath: fieldName,
      },
    ]));
  }

  return Object.fromEntries(ports.map((port) => {
    const portName = port.name || 'input';
    return [
      portName,
      {
        kind: 'contextPath',
        path: portName,
        targetPort: portName,
      },
    ];
  }));
}

export function operatorScenarioGraphDraft(
  operator: OperatorDefinition,
  contract: ContractDraft,
  tenantId: string,
  environment: string,
  operatorConfig: Record<string, unknown> = {},
): GraphDraft {
  const safeRef = operator.operatorRef.replace(/[^A-Za-z0-9._-]+/g, '-');
  return {
    schemaVersion: 'bloge.visualGraphDraft.v1',
    graphName: `operator-${safeRef}`,
    tenantId,
    namespace: 'operator-contract',
    environment,
    inputSchema: contract.inputSchema,
    outputSchema: contract.outputSchema,
    nodes: [{
      id: 'operator',
      operatorRef: operator.operatorRef,
      label: operator.display?.name || operator.operatorRef,
      inputs: operatorScenarioInputBindings(operator, contract),
      config: operatorConfig,
      position: { x: 160, y: 120 },
    }],
    edges: [],
    visualLayout: {
      authoringMode: 'operator-contract-scenario',
    },
    nodeFixtures: {},
    output: { nodeId: 'operator', path: '' },
    operatorFingerprints: {
      operator: contract.target.fingerprint,
    },
    operatorSnapshots: {
      operator,
    },
  };
}
