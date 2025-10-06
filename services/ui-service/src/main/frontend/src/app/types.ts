export interface GraphLD<T> {
  '@context': Record<string, string>;
  '@graph': T[];
}

export interface ChangeReport {
  '@id': string;
  'kss:podId': string;
  'kss:statusEntry': ChangeStatusEntry[];
  'kss:sliceId'?: string;
  'kss:nrOfInserts'?: number;
  'kss:nrOfDeletes'?: number;
  'kss:errorMessage'?: string;
}

export interface ChangeStatusEntry extends Timestamped {
  'kss:statusCode': ChangeResultCode;
  'kss:message'?: string;
}

export interface ChangeRecords extends Timestamped {
  '@context': Record<string, string>;
  '@id': string;
  'kss:insert'?: ChangeRecord[] | ChangeRecord;
  'kss:delete'?: ChangeRecord[] | ChangeRecord;
}

export interface ChangeRecord extends Record<string, any> {
  '@id': string;
  '@type'?: string;
}

export interface ChangeRequest extends WriteTransaction {
  'kss:with'?: any[];
  'kss:assert'?: any[];
}

export interface WriteTransaction {
  '@context': Record<string, any>;
  'kss:insert'?: any[];
  'kss:delete'?: any[];
}

export interface TriplePart {
  '@id': string;
  '@type': string;
}

export type RelationshipDefinition = Record<string, TriplePart> & TriplePart;

export class Relationship {
  '@id': string;
  '@type': string;

  constructor(private rel: RelationshipDefinition) {
    this['@id'] = rel['@id'];
    this['@type'] = rel['@type'];
  }

  getRelationName(): string {
    return Object.keys(this.rel)
      .filter((key) => key != '@id' && key != '@type')
      .at(0)!;
  }

  getRelationObject(): TriplePart {
    return Object.entries(this.rel)
      .filter((entry) => entry[0] != '@id' && entry[0] != '@type')
      .map((entry) => entry[1])
      .at(0)!;
  }

  toDefinition(): RelationshipDefinition {
    return this.rel;
  }
}

export interface CheckResult {
  '@context': Record<string, any>;
  'kss-fga:allowed': boolean;
}

export enum ChangeResultCode {
  /**
   * The Change Request was added to the processing queue
   */
  QUEUED,

  /**
   * The Change Request has been preprocessed by the configured preprocessing chain.
   */
  PROCESSING,

  /**
   * The Change Request was successfully applied.
   */
  COMMITTED,

  /**
   * The Change Request was not applied because one or more assertions failed.
   */
  ASSERTION_FAILED,

  /**
   * The Change Request was not applied because the with-clause did not return any results.
   */
  NO_MATCHES,

  /**
   * The Change Request was not applied because the with-clause returned too many results.
   */
  TOO_MANY_MATCHES,

  /**
   * The Change Request was not applied because of a validation error.
   */
  VALIDATION_ERROR,

  /**
   * The Change Request was not applied because of an internal error.
   */
  INTERNAL_ERROR,
}

export interface Slice {
  '@id': string;
  '@context': Record<string, any>;
  'kss:name': string;
  'kss:schema': string;
  'kss:description'?: string;
  'kss:targetGraphs'?: any[];
}

export type SliceInput = Omit<Slice, '@id'>;

export interface Pod {
  '@context': Record<string, string>;
  '@id': string;
  'kss:configuration': PodConfiguration;
}

export interface PodConfiguration {
  'kss:autoIngestRDF': boolean;
  'kss:defaultContext': string;
  'kss:authConfiguration': PodAuthConfiguration;
}

export interface PodAuthConfiguration {
  'kss:clientId': string;
  'kss:clientSecret': string;
  'kss:serverUrl': string;
}

export interface Paged<T> {
  cursor?: string;
  content: T;
}

export interface Timestamped {
  'kss:timestamp': string;
}
