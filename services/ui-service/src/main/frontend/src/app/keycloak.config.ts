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

export const provideRequiredKeycloakProviders = (config: AppConfig) => {
  normalizeAppConfig(config);

  return [
    provideKeycloak({
      config: {
        clientId: 'kvasir-ui',
        realm: config.KVASIR_AUTH_REALM,
        url: config.KVASIR_AUTH_HOST,
      },
      initOptions: {
        onLoad: 'check-sso',
        enableLogging: true,
        silentCheckSsoRedirectUri:
          window.location.origin + '/_ui/silent-check-sso.html',
        redirectUri: window.location.origin + '/_ui/',
        pkceMethod: 'S256',
        messageReceiveTimeout: 3000,
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
