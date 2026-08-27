export {
  externalApiFormToDescriptor,
  inferSchema,
  MAX_DEPTH,
  MAX_NODES,
  MAX_STRUCTURED_PROPERTIES,
  structuredObjectSchema,
  toDesignContract,
} from './externalApiModel';
export { saveExternalApi } from './externalApiTransport';
export type {
  ExternalApiFormModel,
  ExternalApiOutputSchema,
  ExternalApiParameter,
  ExternalApiResponseProtocol,
  JsonSchema,
  ResourceDescriptorPayload,
  ResourceDesignContractPayload,
  SchemaEnvelopePayload,
  StructuredSchemaProperty,
  StructuredSchemaPropertyType,
} from './externalApiModel';
export type { ExternalApiRequester, ExternalApiSaveResult } from './externalApiTransport';
