import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { AppConfig } from './app/services/config.service';
import { appConfig } from './app/app.config';


fetch('./_cfg/config.json')
  .then((response) => response.json())
  .then((config: AppConfig) =>
    bootstrapApplication(AppComponent, appConfig(config)).catch((err) =>
      console.error(err),
    ),
  );
