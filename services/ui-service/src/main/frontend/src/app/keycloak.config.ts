import {
  provideHttpClient,
  withFetch,
  withInterceptors,
} from '@angular/common/http';
import { Provider } from '@angular/core';
import {
  AutoRefreshTokenService,
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  IncludeBearerTokenCondition,
  includeBearerTokenInterceptor,
  provideKeycloak,
  UserActivityService,
  withAutoRefreshToken,
} from 'keycloak-angular';
import { AppConfig } from './services/config.service';
import { KvasirLoginSession } from './services/login-session.service';
import { KEY_KVASIR_LOGIN_SESSION } from './util/constants';

export const provideRequiredKeycloakProviders = (config: AppConfig) => {
  try {
    normalizeAppConfig(config);
    const loginSession = JSON.parse(
      localStorage.getItem(KEY_KVASIR_LOGIN_SESSION)!,
    ) as KvasirLoginSession;

    console.log('Session active: initing Keycloak');
    // If it looks like a Keycloak URL, capture the host part
    const result = /^(?<host>.+)\/realms\/(?<realm>.[^\/]+)$/.exec(
      loginSession!.keycloakUrl,
    );

    return [
      provideKeycloak({
        config: {
          clientId: 'kvasir-ui',
          realm:
            result?.groups?.['realm'] ??
            loginSession.keycloakUrl.substring(
              loginSession.keycloakUrl.lastIndexOf('/'),
            ),
          url: result?.groups?.['host'] ?? loginSession.keycloakUrl,
        },
        initOptions: {
          onLoad: 'check-sso',
          silentCheckSsoRedirectUri:
            window.location.origin + '/_ui/silent-check-sso.html',
          redirectUri: window.location.origin + '/_ui/',
          pkceMethod: 'S256',
        },
        features: [
          withAutoRefreshToken({
            onInactivityTimeout: 'none',
            sessionTimeout: 60000,
          }),
        ],
        providers: [AutoRefreshTokenService, UserActivityService],
      }),
      provideInterceptors(config),
      provideHttpClient(
        withFetch(),
        withInterceptors([includeBearerTokenInterceptor]),
      ),
    ];
  } catch (err: any) {
    // console.error(err);
    console.log('NO session: no keycloak');
    return [provideHttpClient(withFetch())];
  }
};

function normalizeAppConfig(config: AppConfig): void {
  config.KVASIR_HOST = config.KVASIR_HOST.endsWith('/')
    ? config.KVASIR_HOST.slice(0, -1)
    : config.KVASIR_HOST;
}

const provideInterceptors = (config: AppConfig): Provider => {
  const localhostCondition =
    createInterceptorCondition<IncludeBearerTokenCondition>({
      urlPattern: RegExp(`^(${config.KVASIR_HOST})(\/.*)?$`, 'i'),
    });
  return {
    provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
    useValue: [localhostCondition],
  };
};
