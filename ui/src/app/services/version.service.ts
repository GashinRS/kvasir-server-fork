import { Injectable } from '@angular/core';
import { buildDate, commitHash, version } from '../../environments/version';

@Injectable({
  providedIn: 'root',
})
export class VersionService {
  getVersionString(): string {
    return `${version} ${commitHash} @ ${buildDate}`;
  }

  getVersion(): string {
    return version;
  }

  getCommitHash(): string {
    return commitHash;
  }

  getBuildISOString(): string {
    return buildDate;
  }
}
