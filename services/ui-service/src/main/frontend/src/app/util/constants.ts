const KEY_PREFIX = 'KVASIR_UI_SESSION#';

export const KEY_LOGIN_STATE = `${KEY_PREFIX}login_state`;
export const KEY_KVASIR_LOGIN_SESSION = `${KEY_PREFIX}login_session`;

export const KSS_PREFIX = 'kss';
export const KSS_FQN = 'https://kvasir.discover.ilabt.imec.be/vocab#';

export const AT_CONTEXT_KSS_FGA = {
  [KSS_PREFIX]: KSS_FQN,
  'kss-fga': 'https://kvasir.discover.ilabt.imec.be/fine-grained-access#',
};

export const KSS_FGA_USER_TYPE = 'kss-fga:User';
export const KSS_FGA_RESOURCE_TYPE = 'kss-fga:Resource';
export const KSS_FGA_EXTERNAL_ACCESS = 'kss-fga:external_access';
export const KSS_FGA_EXTERNAL_ACCESS_UMA = 'kss-fga:Uma';
export const KSS_FGA_EXTERNAL_ACCESS_HTTP_ENDPOINT = 'kss-fga:HttpEndpoint';

export const KSS_FGA_USER_ANONYMOUS = 'urn:kvasir-user:anonymous';
export const KSS_FGA_USER_WILDCARD = 'urn:kvasir-wildcard';
export const REDACTED_CREDENTIALS = '*********';
