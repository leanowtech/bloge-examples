'use strict';

function schema(body) {
  return { format: 'json-schema', version: '2020-12', schema: body };
}

function object(properties, required = Object.keys(properties), additionalProperties = false) {
  return { type: 'object', properties, required, additionalProperties };
}

function resource(operatorRef, name, inputProperties, outputProperties) {
  const resourceId = operatorRef.slice('resource:'.length);
  return {
    schemaVersion: 'bloge.visualOperator.v1',
    operatorRef,
    version: '1.0.0',
    display: { name, description: `${name} offline fixture resource.`, tags: ['resource', 'offline-demo'] },
    source: { kind: 'resource-descriptor', libraryId: resourceId },
    ports: {
      inputs: [{ name: 'params', required: true, schema: schema(object(inputProperties)) }],
      outputs: [{ name: 'payload', required: true, schema: schema(object(outputProperties)) }],
    },
    configSchema: schema(object({}, [], true)),
    capabilities: { deterministic: false, sideEffectFree: true },
    lowering: {
      mode: 'resource-descriptor',
      operatorRef: 'httpResource',
      parameters: { resourceId, payloadPath: 'data' },
    },
    diagnostics: [],
  };
}

function offlineOperatorCatalog() {
  const text = { type: 'string' };
  const number = { type: 'number' };
  const integer = { type: 'integer' };
  const openObject = { type: 'object', additionalProperties: true };
  const openArray = { type: 'array', items: openObject };
  const operators = [
    {
      schemaVersion: 'bloge.visualOperator.v1',
      operatorRef: 'bloge:decisionTable',
      version: '1.0.0',
      display: { name: 'Decision Table', description: 'Rules with typed inputs and structured output.', tags: ['logic', 'rules'] },
      source: { kind: 'built-in', libraryId: 'bloge-dsl' },
      ports: {
        inputs: [{ name: 'inputs', required: true, schema: schema(openObject) }],
        outputs: [{ name: 'output', required: true, schema: schema(openObject) }],
      },
      configSchema: schema(openObject),
      capabilities: { deterministic: true, sideEffectFree: true },
      lowering: { mode: 'dsl', operatorRef: 'decision_table', parameters: {} },
      diagnostics: [],
    },
    {
      schemaVersion: 'bloge.visualOperator.v1',
      operatorRef: 'bloge:transform',
      version: '1.0.0',
      display: { name: 'Transform', description: 'Maps expressions into a structured object.', tags: ['logic', 'mapping'] },
      source: { kind: 'built-in', libraryId: 'bloge-dsl' },
      ports: {
        inputs: [{ name: 'inputs', required: false, schema: schema(openObject) }],
        outputs: [{ name: 'output', required: true, schema: schema(openObject) }],
      },
      configSchema: schema(openObject),
      capabilities: { deterministic: true, sideEffectFree: true },
      lowering: { mode: 'dsl', operatorRef: 'transform', parameters: {} },
      diagnostics: [],
    },
    resource('resource:loan-applicant-service.getProfile', 'Loan applicant profile',
      { applicantId: text },
      { applicantId: text, score: integer, segment: text, income: number, employmentYears: number }),
    resource('resource:credit-provider.primary', 'Primary credit score',
      { userId: text }, { score: integer, provider: text, band: text }),
    resource('resource:credit-provider.secondary', 'Secondary credit score',
      { userId: text }, { score: integer, provider: text, band: text }),
    resource('resource:order-service.listOrders', 'Customer orders',
      { userId: text }, { items: openArray, total: integer }),
    resource('resource:logistics-service.getShipping', 'Shipping quote',
      { orderId: text }, { carrier: text, etaDays: integer, cost: number }),
    resource('resource:user-service.getProfile', 'Customer profile',
      { userId: text }, { userId: text, name: text, tier: text, segment: text, score: integer }),
    resource('resource:wallet-service.getBalance', 'Wallet balance',
      { userId: text }, { amount: number, currency: text }),
    resource('resource:recommendation-service.forUser', 'Recommendations',
      { userId: text }, { items: openArray }),
    resource('resource:notification-service.unread', 'Unread notifications',
      { userId: text }, { count: integer, items: openArray }),
  ];
  return {
    schemaVersion: 'bloge.visualOperatorCatalog.v1',
    operators,
    builtInFunctions: [
      functionDefinition('coalesce', 'Returns the first non-null value.', ['value', 'fallback']),
      functionDefinition('toNumber', 'Converts a value to a number.', ['value']),
      functionDefinition('round', 'Rounds a numeric value.', ['value']),
    ],
    diagnostics: [],
    total: operators.length,
    unfilteredTotal: operators.length,
    returned: operators.length,
    hasMore: false,
    query: {},
  };
}

function functionDefinition(name, description, parameters) {
  return {
    name,
    namespace: 'bloge',
    description,
    category: 'offline-demo',
    signatures: [{
      label: `${name}(${parameters.join(', ')})`,
      parameters: parameters.map((parameter) => ({ name: parameter, type: 'any' })),
      returns: { type: 'any' },
    }],
    examples: [`${name}(${parameters.map((parameter) => `inputs.${parameter}`).join(', ')})`],
  };
}

module.exports = { offlineOperatorCatalog };
