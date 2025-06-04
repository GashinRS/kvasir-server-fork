import { DatePipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { VersionService } from '../../services/version.service';

@Component({
  selector: 'app-version',
  imports: [DatePipe, NzFlexModule],
  templateUrl: './version.component.html',
  styleUrl: './version.component.less',
})
export class VersionComponent {
  // DI
  readonly versionService = inject(VersionService);

  readonly version = this.versionService.getVersion();
  readonly commitHash = this.versionService.getCommitHash();
  readonly buildDate = this.versionService.getBuildISOString();
}
