import {
  ApplicationConfig,
  importProvidersFrom,
  Provider,
  provideZoneChangeDetection,
} from '@angular/core';
import {
  provideRouter,
  withComponentInputBinding,
  withRouterConfig,
} from '@angular/router';

import { APP_BASE_HREF, registerLocaleData } from '@angular/common';
import en from '@angular/common/locales/en';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { en_US, provideNzI18n } from 'ng-zorro-antd/i18n';
import { environment } from '../environments/environment';
import { routes } from './app.routes';
import { provideNzIcons } from './icons-provider';
import { provideRequiredKeycloakProviders } from './keycloak.config';
import { APP_CONFIG_TOKEN, AppConfig } from './services/config.service';
import { ClipboardModule } from '@angular/cdk/clipboard';
import { NzModalModule } from 'ng-zorro-antd/modal';

registerLocaleData(en);

export function appConfig(config: AppConfig): ApplicationConfig {
  return {
    providers: [
      provideAppConfigToken(config),
      provideRequiredKeycloakProviders(config),
      provideZoneChangeDetection({ eventCoalescing: true }),
      provideRouter(
        routes,
        withComponentInputBinding(),
        withRouterConfig({
          onSameUrlNavigation: 'reload',
        }),
      ),
      provideNzI18n(en_US),
      importProvidersFrom(
        FormsModule,
        ReactiveFormsModule,
        ClipboardModule,
        NzModalModule,
      ),
      provideAnimationsAsync(),
      provideNzIcons(),
    ],
  };
}

export const provideAppConfigToken = (config: AppConfig): Provider => ({
  provide: APP_CONFIG_TOKEN,
  useValue: config,
  multi: false,
});

export function provideBaseHref(): Provider {
  return {
    provide: APP_BASE_HREF,
    useValue: environment.APP_BASE_HREF,
  };
}
