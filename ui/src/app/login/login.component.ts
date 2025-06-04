import { Component, effect, inject, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
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
import { LoginFSM } from '../services/login-fsm';

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
  ],
  templateUrl: './login.component.html',
  styleUrl: './login.component.less',
})
export class LoginComponent {
  private kvasir = inject(KvasirService);
  private config = inject(ConfigService);
  private fsm = inject(LoginFSM);

  podName: string = 'alice';
  myForm: FormGroup;
  selectedPod = linkedSignal<string | null>(() =>
    (this.podsResource.value()?.length ?? 0) > 0
      ? this.name(this.podsResource.value()!.at(0)!)
      : null,
  );
  podsResource = rxResource<Pod[], void>({
    loader: () =>
      this.kvasir
        .listPods()
        .pipe(
          map((pods) => pods.sort((a, b) => a['@id'].localeCompare(b['@id']))),
        ),
  });

  constructor(fb: FormBuilder) {
    this.myForm = fb.group({
      podName: [this.selectedPod()],
    });
    effect(() => this.myForm.get('podName')?.setValue(this.selectedPod()));
  }

  name = (pod: Pod): string => {
    return pod?.['@id']?.slice(this.config.host.length + 1);
  };

  async doLogin(): Promise<void> {
    const podName = this.myForm.get('podName')?.value;
    // this.session.newSession(podName);
    this.fsm.login(podName);
  }
}
