import { Component, inject, input, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
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

  async ngOnInit() {
    if (this.session.isActive()) {
      // Active session of same pod
      if (this.podId() == this.session.podName()) {
        this.router.navigate(['']);
      }
      // Active session of another pod
      else if (
        this.podId() != this.session.podName() &&
        this.session.podName() != null
      ) {
        this.session.logoutAndRedirectToLogin(this.podId());
      } else {
        this.session.doLogin(this.podId());
      }
    } else {
      await this.session.doLogin(this.podId());
    }
  }
}
