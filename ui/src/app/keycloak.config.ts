import { provideHttpClient, withInterceptors } from '@angular/common/http';
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
    const loginSession = JSON.parse(
      localStorage.getItem(KEY_KVASIR_LOGIN_SESSION)!,
    ) as KvasirLoginSession;

    console.log('Session active: initing Keycloak');
    // If it looks like a Keycloak URL, capture the host part
    const result = /^(?<host>.+)\/realms\/.[^\/]+$/.exec(
      loginSession!.keycloakUrl,
    );

    return [
      provideKeycloak({
        config: {
          clientId: 'kvasir-ui',
          realm: loginSession.podName,
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
      provideHttpClient(withInterceptors([includeBearerTokenInterceptor])),
    ];
  } catch (err: any) {
    // console.error(err);
    console.log('NO session: no keycloak');
    return [provideHttpClient()];
  }
};

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
