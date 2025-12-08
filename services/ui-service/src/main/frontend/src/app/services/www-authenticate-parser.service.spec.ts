import {
  AuthTypeMap,
  WwwAuthenticateParserService,
} from './www-authenticate-parser.service';

describe('WwwAuthenticateParserService', () => {
  let service: WwwAuthenticateParserService;

  const HEADER_KEY = 'Www-Authenticate';
  const SINGLE_BEARER_CHALLENGE = `Bearer as_uri="https://bearer.auth.do"`;
  const SINGLE_UMA_CHALLENGE = `UMA realm="example", as_uri="https://uma.auth.do", ticket="afeaEafe-aef1-0asef-asef"`;
  const MULTI_CHALLENGE = `${SINGLE_BEARER_CHALLENGE}, ${SINGLE_UMA_CHALLENGE}`;
  const MULTI_CHALLENGE_REVERSE = `${SINGLE_UMA_CHALLENGE}, ${SINGLE_BEARER_CHALLENGE}`;

  // Expected Results
  const SINGLE_BEARER_RESULT: AuthTypeMap = {
    Bearer: { as_uri: 'https://bearer.auth.do' },
  };
  const SINGLE_UMA_RESULT: AuthTypeMap = {
    UMA: {
      realm: 'example',
      as_uri: 'https://uma.auth.do',
      ticket: 'afeaEafe-aef1-0asef-asef',
    },
  };
  const MULTI_RESULT = {
    ...SINGLE_BEARER_RESULT,
    ...SINGLE_UMA_RESULT,
  };

  beforeEach(() => {
    service = new WwwAuthenticateParserService();
  });

  it('can parse single Bearer challenge', () => {
    const headers = new Headers();
    headers.append(HEADER_KEY, SINGLE_BEARER_CHALLENGE);
    expect(service.parse(headers)).toEqual(SINGLE_BEARER_RESULT);
  });

  it('can parse single UMA challenge', () => {
    const headers = new Headers();
    headers.append(HEADER_KEY, SINGLE_UMA_CHALLENGE);
    expect(service.parse(headers)).toEqual(SINGLE_UMA_RESULT);
  });

  it('can parse multi Bearer, UMA challenge', () => {
    const headers = new Headers();
    headers.append(HEADER_KEY, MULTI_CHALLENGE);
    expect(service.parse(headers)).toEqual(MULTI_RESULT);
  });

  it('can parse multi UMA, Bearer challenge', () => {
    const headers = new Headers();
    headers.append(HEADER_KEY, MULTI_CHALLENGE_REVERSE);
    expect(service.parse(headers)).toEqual(MULTI_RESULT);
  });
});
