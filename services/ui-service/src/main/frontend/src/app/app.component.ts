import { Component, inject } from '@angular/core';
import { RouterModule, RouterOutlet } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { NzMenuModule } from 'ng-zorro-antd/menu';
import { NzPopoverModule } from 'ng-zorro-antd/popover';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTimelineModule } from 'ng-zorro-antd/timeline';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { VersionComponent } from './components/version/version.component';
import { SessionService } from './services/session.service';

@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    NzLayoutModule,
    NzMenuModule,
    NzIconModule,
    NzTooltipModule,
    NzPopoverModule,
    NzTimelineModule,
    RouterModule,
    VersionComponent,
    NzSpaceModule,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.less',
})
export class AppComponent {
  title = 'Kvasir';
  private session = inject(SessionService);

  readonly podName = this.session.podName.asReadonly();
  readonly podOwner = this.session.podOwner.asReadonly();
  readonly sessionActive = this.session.isActive();

  doLogout() {
    this.session.logout();
  }
}
