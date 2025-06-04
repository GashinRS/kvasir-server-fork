import jsonld from 'jsonld';
import * as N3 from 'n3';
import { ChangeRecord, Timestamped } from '../types';

export function ensureArray<T>(input: T[] | T): T[] {
  if (input == null) {
    return [];
  }
  return Array.isArray(input) ? input : [input];
}

export function parseLinkHeader(value: string | null): Record<string, string> {
  if (value == null) {
    return {};
  }
  const parts = value.split(',');
  return parts.reduce(
    (obj, part) => {
      const [urlPiece, relPiece] = part.split(';');
      const url = urlPiece.slice(1, -1);
      const rel = relPiece.split('=')[1].slice(1, -1);
      obj[rel] = url;
      return obj;
    },
    {} as Record<string, string>,
  );
}

export async function flatMapToNQuads(
  input: ChangeRecord[],
  context: any,
): Promise<string[]> {
  const doc = { '@context': context, '@graph': input };
  const res = await jsonld.toRDF(doc, {
    format: 'application/n-quads',
  });
  return res.toString().split('\n');
}

export async function mapToNQuads(
  input: ChangeRecord[],
  context: any,
): Promise<N3.Quad[]> {
  const parser = new N3.Parser();
  const doc = { '@context': context, '@graph': input };
  const res = await jsonld.toRDF(doc, {
    format: 'application/n-quads',
  });

  return new Promise((resolve, reject) => {
    const results: N3.Quad[] = [];
    parser.parse(res.toString(), (error, quad, prefixes) => {
      if (quad) {
        results.push(quad);
      } else {
        resolve(results);
      }
    });
  });
}

export async function mapToSignedQuads(
  input: ChangeRecord[],
  context: any,
  sign: '+' | '-',
): Promise<SignedQuad[]> {
  const parser = new N3.Parser();
  const doc = { '@context': context, '@graph': input };
  const res = await jsonld.toRDF(doc, {
    format: 'application/n-quads',
  });

  return new Promise((resolve, reject) => {
    const results: N3.Quad[] = [];
    parser.parse(res.toString(), (error, quad, prefixes) => {
      if (error) {
        reject(error);
        return;
      }
      if (quad) {
        results.push(quad);
      } else {
        resolve(
          results.map((quad) => ({
            sign,
            subject: quad.subject.value,
            predicate: quad.predicate.value,
            object: quad.object.value,
          })),
        );
      }
    });
  });
}

export interface SignedQuad {
  sign: '+' | '-';
  subject: string;
  predicate: string;
  object: string;
}

export function sortByTimestamp(direction: 'asc' | 'desc' = 'asc') {
  return function (a: Timestamped, b: Timestamped): number {
    return direction == 'asc'
      ? sortByTimestampAsc(a, b)
      : sortByTimestampAsc(b, a);
  };
}

function sortByTimestampAsc(a: Timestamped, b: Timestamped): number {
  return (
    Date.parse(a['kss:timestamp']).valueOf() -
    Date.parse(b['kss:timestamp']).valueOf()
  );
}
