export class WwwAuthParser<A = any> {
  constructor(private schemes: (keyof A)[]) {}

  /**
   * Parse a Www-Authenticate header into a map of scheme -> parameters
   * @param wwwAuthenticationHeader
   * @returns
   */
  parseHeader(wwwAuthenticationHeader: string): A {
    const tokens = wwwAuthenticationHeader
      .trim()
      .split(',')
      .flatMap((tok) => tok.trim().split(/\s+/));
    let currentScheme: keyof A | null = null;
    let responseMap = {} as any;
    tokens.forEach((tok) => {
      if (
        this.schemes.map((sch) => sch as string).includes(tok) &&
        currentScheme != tok
      ) {
        currentScheme = tok as keyof A;
        responseMap[currentScheme] = {} as Record<string, string>;
      } else if (currentScheme != null) {
        const params = tok.split('=');
        if (params.length == 2) {
          responseMap[currentScheme][params[0]] = unquote(params[1]);
        } else {
          responseMap[currentScheme][params[0]] = true;
        }
      } else {
        throw new Error('Www-Authenticate header is invalid!');
      }
    });
    return responseMap as A;
  }
}

function unquote(str: string): string {
  if (str != null) {
    if (str.startsWith('"') && str.endsWith('"')) {
      return str.slice(1, str.length - 1);
    }
  }
  return str;
}
