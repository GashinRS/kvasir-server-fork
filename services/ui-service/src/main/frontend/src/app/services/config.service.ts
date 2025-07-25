import { Inject, Injectable, InjectionToken } from '@angular/core';
@Injectable({
  providedIn: 'root',
})
export class ConfigService {
  host: string = '';

  constructor(@Inject(APP_CONFIG_TOKEN) config: AppConfig) {
    this.host = this.removeTrailingSlash(config.KVASIR_HOST);
  }

  private removeTrailingSlash(url: string): string {
    return url.endsWith('/') ? url.slice(0, -1) : url;
  }
}

export interface AppConfig {
  KVASIR_HOST: string;
}

export const APP_CONFIG_TOKEN = new InjectionToken<AppConfig>(
  'The config object received from the initial config.json call.',
);
