import { Component, inject, input, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { LoginFSM } from '../services/login-fsm';
import { SessionService } from '../services/session.service';

@Component({
  selector: 'app-force-session',
  imports: [NzTypographyModule, NzSpinModule],
  templateUrl: './force-session.component.html',
  styleUrl: './force-session.component.less',
})
export class ForceSessionComponent implements OnInit {
  podId = input.required<string>();
  router = inject(Router);
  session = inject(SessionService);
  fsm = inject(LoginFSM);

  async ngOnInit() {
    if (this.session.sessionActive()) {
      // Active session of same pod
      if (this.podId() == this.session.podName()) {
        this.router.navigate(['']);
      }
      // Active session of another pod
      else {
        const redirectUri = new URL(window.location.href);
        // Force parameter will force login
        redirectUri.searchParams.set('force', 'true');
        // Reload this page after logout
        this.fsm.logout(redirectUri.toString());
      }
    } else {
      // No active session: add force if query param is present.
      const params = new URLSearchParams(window.location.search);
      const force = params.get('force') == 'true';
      // After login, redirect to root, not this page (which is the default)
      await this.fsm.login(this.podId(), true, force);
    }
  }
}
