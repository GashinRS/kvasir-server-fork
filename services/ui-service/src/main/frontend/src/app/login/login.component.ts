import { Component, inject, linkedSignal, OnInit } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule } from '@angular/forms';
import { form, FormField } from '@angular/forms/signals';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { map } from 'rxjs';
import { ConfigService } from '../services/config.service';
import { KvasirService } from '../services/kvasir.service';
import { SessionService } from '../services/session.service';
import { Pod } from '../types';

@Component({
  selector: 'app-login',
  imports: [
    NzButtonModule,
    NzFlexModule,
    NzGridModule,
    NzPageHeaderModule,
    NzSelectModule,
    NzSpaceModule,
    ReactiveFormsModule,
    FormField,
  ],
  templateUrl: './login.component.html',
  styleUrl: './login.component.less',
})
export class LoginComponent implements OnInit {
  private kvasir = inject(KvasirService);
  private config = inject(ConfigService);
  private session = inject(SessionService);

  podsResource = rxResource<Pod[], void>({
    stream: () =>
      this.kvasir
        .listPods()
        .pipe(
          map((pods) => pods.sort((a, b) => a['@id'].localeCompare(b['@id']))),
        ),
  });
  podModel = linkedSignal<{ selectedPod: string | null }>(() => {
    return { selectedPod: this.name(this.podsResource.value()?.at(0) ?? null) };
  });
  podForm = form(this.podModel);

  constructor() {}

  ngOnInit(): void {
    const params = new URLSearchParams(window.location.search);
    const podFromQuery = params.get('selectedPod');
    if (podFromQuery) {
      this.session.doLogin(podFromQuery);
    }
  }

  name = (pod: Pod | null): string | null => {
    return pod?.['@id']?.slice(this.config.host.length + 1) || null;
  };

  async doLogin(): Promise<void> {
    this.session.doLogin(this.podModel().selectedPod!);
  }
}
