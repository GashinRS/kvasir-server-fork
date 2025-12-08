import { Inject, Injectable, InjectionToken } from '@angular/core';

export const APP_CONFIG_TOKEN = new InjectionToken<AppConfig>(
  'The config object received from the initial config.json call.',
);

export interface AppConfig {
  KVASIR_HOST: string;
  KVASIR_AUTH_HOST: string;
  KVASIR_AUTH_REALM: string;
}

@Injectable({
  providedIn: 'root',
})
export class ConfigService {
  host: string = '';
  authHost: string = '';
  authRealm: string = '';

  constructor(@Inject(APP_CONFIG_TOKEN) config: AppConfig) {
    this.host = this.removeTrailingSlash(config.KVASIR_HOST);
    this.authHost = this.removeTrailingSlash(config.KVASIR_AUTH_HOST);
    this.authRealm = config.KVASIR_AUTH_REALM;
  }

  getAuthUri(): string {
    return `${this.authHost}/realms/${this.authRealm}`;
  }

  private removeTrailingSlash(url: string): string {
    return url.endsWith('/') ? url.slice(0, -1) : url;
  }
}
