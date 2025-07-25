import { Injectable } from '@angular/core';
import { buildDate, version } from '../../environments/version';

@Injectable({
  providedIn: 'root',
})
export class VersionService {
  getVersionString(): string {
    return `${version} @ ${buildDate}`;
  }

  getVersion(): string {
    return version;
  }

  getBuildISOString(): string {
    return buildDate;
  }
}
