import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { AppConfig } from './app/services/config.service';
import { appConfig } from './app/app.config';

const DEFAULT_CONFIG: AppConfig = {
  KVASIR_HOST: 'http://localhost:28080/',
  KVASIR_AUTH_HOST: 'http://localhost:28280',
  KVASIR_AUTH_REALM: 'kvasir',
};

fetch('./_cfg/config.json')
  .then((response) => {
    if (response.status < 400) {
      return response.json();
    } else {
      return DEFAULT_CONFIG;
    }
  })
  .then((config: AppConfig) =>
    bootstrapApplication(AppComponent, appConfig(config)).catch((err) =>
      console.error(err),
    ),
  );
