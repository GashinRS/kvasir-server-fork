export enum AuthErrorType {
  NO_WWW_AUTH_HEADER,
  NO_AUTH_CHALLENGE,
  NO_AS_URI,
  INVALID_AS_URI,
}

export class AuthenticationError extends Error {
  constructor(type: AuthErrorType, relevantInfo?: string) {
    switch (type) {
      case AuthErrorType.NO_WWW_AUTH_HEADER:
        super('Authentication Error: No Www-Authentication Header found.');
        break;
      case AuthErrorType.NO_AUTH_CHALLENGE:
        super(
          'Authentication Error: No valid auth challenge found in Www-Authentication Header.',
        );
        break;
      case AuthErrorType.NO_AS_URI:
        super(
          'Authentication Error: No as_uri parameter found in Www-Authentication Header challenge.',
        );
        break;
      case AuthErrorType.INVALID_AS_URI:
        let msg =
          'Authentication Error: Invalid as_uri given in Www-Authentication header challenge.';
        if (relevantInfo) {
          msg += `\n[${relevantInfo}]`;
        }
        super(msg);
        break;
      default: {
        super('Authentication Error: Unknown');
      }
    }
  }
}
