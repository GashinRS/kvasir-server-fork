import { Injectable } from '@angular/core';
import { WwwAuthParser } from '../util/WwwAuthParser';

interface BearerAuthConfig {
  as_uri: string;
}

interface UmaAuthConfig {
  as_uri: string;
  realm: string;
  ticket?: string;
}
export interface AuthTypeMap {
  Bearer?: BearerAuthConfig;
  UMA?: UmaAuthConfig;
}

const WWW_AUTHENTICATE = 'Www-Authenticate';
const AUTH_TYPES: (keyof AuthTypeMap)[] = ['Bearer', 'UMA'];

@Injectable({
  providedIn: 'root',
})
export class WwwAuthenticateParserService {
  parse(headers: Headers): AuthTypeMap | null {
    if (!headers.has(WWW_AUTHENTICATE)) {
      return null;
    }
    const header = headers.get(WWW_AUTHENTICATE)!;
    const parser = new WwwAuthParser<AuthTypeMap>(AUTH_TYPES);
    return parser.parseHeader(header);
  }
}
