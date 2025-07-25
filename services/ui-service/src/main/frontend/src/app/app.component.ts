import { Component, inject } from '@angular/core';
import { RouterModule, RouterOutlet } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { NzMenuModule } from 'ng-zorro-antd/menu';
import { VersionComponent } from './components/version/version.component';
import { LoginFSM } from './services/login-fsm';
import { SessionService } from './services/session.service';
import { NzToolTipModule } from 'ng-zorro-antd/tooltip';

@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    NzLayoutModule,
    NzMenuModule,
    NzIconModule,
    NzToolTipModule,
    RouterModule,
    VersionComponent,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.less',
})
export class AppComponent {
  title = 'Kvasir';
  private session = inject(SessionService);
  private fsm = inject(LoginFSM);

  readonly podName = this.session.podName.asReadonly();
  readonly sessionActive = this.session.sessionActive;

  doLogout() {
    this.fsm.logout();
  }
}
