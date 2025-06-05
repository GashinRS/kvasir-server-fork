import { Component, inject, TemplateRef, viewChild } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule, NzModalService } from 'ng-zorro-antd/modal';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { switchMap } from 'rxjs';
import { KvasirService } from '../services/kvasir.service';

interface CreateForm {
  podName: AbstractControl<string>;
}

@Component({
  selector: 'app-register',
  imports: [
    NzPageHeaderModule,
    NzFormModule,
    NzInputModule,
    NzButtonModule,
    NzModalModule,
    NzTypographyModule,
    NzTableModule,
    ReactiveFormsModule,
    RouterModule,
  ],
  templateUrl: './register.component.html',
  styleUrl: './register.component.less',
})
export class RegisterComponent {
  createForm: FormGroup<CreateForm>;
  modalTpl = viewChild<TemplateRef<any>>('infoModal');

  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);
  private modal = inject(NzModalService);

  constructor(fb: FormBuilder) {
    this.createForm = fb.group<CreateForm>({
      podName: fb.nonNullable.control('', [Validators.required]),
    });
  }

  createPod(): void {
    if (this.createForm.valid) {
      const podName = this.createForm.controls.podName.value;
      const infoModal = () =>
        this.modal.info({
          nzTitle: `Pod '${podName}' successfully created`,
          nzContent: this.modalTpl(),
          nzData: podName,
        });
      this.kvasir
        .createPod(podName)
        .pipe(switchMap(() => infoModal().afterClose.asObservable()))
        .subscribe({
          next: () => this.router.navigate(['force-session', podName]),
        });
    }
  }
}
